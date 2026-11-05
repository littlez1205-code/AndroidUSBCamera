package com.littlez.usbdisplay

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the reason of the last crash ("闪退") so that it can be shown and copied on the next start.
 * Java crashes are written by an uncaught exception handler, native crashes and ANRs are read
 * from the system's exit records (Android 11+).
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private const val PREFS = "crash"
    private const val KEY_EXIT_SEEN = "exit_seen"
    private const val KEY_STATE = "state"
    private const val MAX_JAVA_TRACE = 6000
    private const val MAX_NATIVE_LINES = 60
    private const val MAX_TRACE_BYTES = 2 * 1024 * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is JavaCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(JavaCrashHandler(app, previous))
    }

    /** what the app is doing right now (capture card, mode...), included in a report */
    fun setState(context: Context, state: String) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_STATE, state).apply()
        } catch (ignored: Throwable) {
        }
    }

    /** report of an abnormal exit that has not been shown yet, or null */
    fun takeReport(context: Context): String? {
        val app = context.applicationContext
        var report: String? = null
        try {
            val file = File(app.filesDir, FILE_NAME)
            if (file.exists()) {
                report = file.readText()
                file.delete()
            }
        } catch (ignored: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
                val am = app.getSystemService(ActivityManager::class.java)
                val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
                val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
                if (newest > seen) prefs.edit().putLong(KEY_EXIT_SEEN, newest).apply()
                if (report == null) {
                    // Java crashes are already covered by the file above
                    val exit = exits.firstOrNull {
                        it.timestamp > seen && (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                            || it.reason == ApplicationExitInfo.REASON_ANR)
                    }
                    if (exit != null) report = describe(app, exit)
                }
            } catch (ignored: Throwable) {
            }
        }
        return report
    }

    private class JavaCrashHandler(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = header(app, System.currentTimeMillis()) +
                    "Java 异常（线程 ${t.name}）\n" + sw.toString().take(MAX_JAVA_TRACE)
                File(app.filesDir, FILE_NAME).writeText(text)
            } catch (ignored: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun describe(app: Context, exit: ApplicationExitInfo): String {
        val anr = exit.reason == ApplicationExitInfo.REASON_ANR
        val sb = StringBuilder(header(app, exit.timestamp))
        sb.append(if (anr) "应用无响应（ANR）" else "原生代码崩溃").append('\n')
        exit.description?.let { sb.append(it).append('\n') }
        try {
            exit.traceInputStream?.use { input ->
                val bytes = readLimited(input)
                sb.append(if (anr) anrSummary(String(bytes)) else tombstoneSummary(bytes))
            }
        } catch (ignored: Throwable) {
        }
        return sb.toString()
    }

    /** decoded tombstone, or the readable lines of it when it can not be decoded */
    private fun tombstoneSummary(bytes: ByteArray): String {
        val decoded = try {
            Tombstone.parse(bytes).format()
        } catch (e: Throwable) {
            null
        }
        return if (decoded.isNullOrBlank()) nativeSummary(bytes) else decoded
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < MAX_TRACE_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Minimal reader for the binary tombstone of a native crash
     * (AOSP system/core/debuggerd/proto/tombstone.proto): signal, abort message,
     * backtrace of the crashing thread and the last log lines of the app.
     */
    private class Tombstone {
        var tid = -1L
        var signal = ""
        var faultAddress: Long? = null
        var abortMessage = ""
        val causes = ArrayList<String>()
        val threadNames = HashMap<Long, String>()
        val threadFrames = HashMap<Long, List<String>>()
        val logs = ArrayList<String>()

        fun format(): String {
            if (signal.isEmpty() && abortMessage.isEmpty() && threadFrames.isEmpty()) return ""
            val sb = StringBuilder()
            sb.append("信号：").append(signal)
            faultAddress?.let { sb.append("  地址：0x").append(java.lang.Long.toHexString(it)) }
            sb.append('\n')
            if (abortMessage.isNotEmpty()) sb.append("中止信息：").append(abortMessage).append('\n')
            causes.forEach { sb.append("原因：").append(it).append('\n') }
            sb.append("出错线程：").append(threadNames[tid] ?: "?").append(" (").append(tid).append(")\n")
            threadFrames[tid]?.forEachIndexed { i, f -> sb.append(String.format(Locale.US, "#%02d %s\n", i, f)) }
            if (logs.isNotEmpty()) {
                sb.append("最近日志：\n")
                logs.takeLast(MAX_LOG_LINES).forEach { sb.append(it).append('\n') }
            }
            return sb.toString()
        }

        companion object {
            private const val MAX_FRAMES = 25
            private const val MAX_LOG_LINES = 40
            private val OWN_TAGS = setOf("CaptureController", "MonitorActivity", "libUVCCamera", "UVCCamera", "UVCPreview")

            fun parse(bytes: ByteArray): Tombstone {
                val t = Tombstone()
                Reader(bytes, 0, bytes.size).fields { field, r ->
                    when (field) {
                        6 -> t.tid = r.varint()
                        10 -> r.message().fields { f, s ->
                            when (f) {
                                2 -> t.signal = s.string() + t.signal
                                4 -> t.signal = t.signal + " (" + s.string() + ")"
                                9 -> t.faultAddress = s.varint()
                                else -> s.skip()
                            }
                        }
                        14 -> t.abortMessage = r.string()
                        15 -> r.message().fields { f, s -> if (f == 1) t.causes.add(s.string()) else s.skip() }
                        16 -> parseThreadEntry(t, r.message())
                        18 -> r.message().fields { f, s -> if (f == 2) parseLog(t, s.message()) else s.skip() }
                        else -> r.skip()
                    }
                }
                return t
            }

            private fun parseThreadEntry(t: Tombstone, entry: Reader) {
                var key = -1L
                var name = ""
                val frames = ArrayList<String>()
                entry.fields { f, r ->
                    when (f) {
                        1 -> key = r.varint()
                        2 -> r.message().fields { tf, tr ->
                            when (tf) {
                                2 -> name = tr.string()
                                4 -> if (frames.size < MAX_FRAMES) frames.add(parseFrame(tr.message())) else tr.skip()
                                else -> tr.skip()
                            }
                        }
                        else -> r.skip()
                    }
                }
                t.threadNames[key] = name
                t.threadFrames[key] = frames
            }

            private fun parseFrame(r: Reader): String {
                var relPc = 0L
                var function = ""
                var offset = 0L
                var file = ""
                r.fields { f, fr ->
                    when (f) {
                        1 -> relPc = fr.varint()
                        4 -> function = fr.string()
                        5 -> offset = fr.varint()
                        6 -> file = fr.string()
                        else -> fr.skip()
                    }
                }
                val lib = file.substringAfterLast('/').substringAfterLast('!')
                val fn = if (function.isEmpty()) "pc 0x" + java.lang.Long.toHexString(relPc) else "$function+$offset"
                return "$lib  $fn"
            }

            private fun parseLog(t: Tombstone, r: Reader) {
                var priority = 0L
                var tag = ""
                var message = ""
                r.fields { f, lr ->
                    when (f) {
                        4 -> priority = lr.varint()
                        5 -> tag = lr.string()
                        6 -> message = lr.string()
                        else -> lr.skip()
                    }
                }
                // warnings and errors, plus everything this app logs itself
                if (priority >= 5 || tag in OWN_TAGS) {
                    // android.util.Log priorities: 2 VERBOSE ... 7 ASSERT
                    val level = "??VDIWEF".getOrElse(priority.toInt()) { '?' }
                    t.logs.add("$level $tag: ${message.trim().take(200)}")
                }
            }
        }
    }

    /** protobuf wire format reader */
    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        private var wireType = 0

        fun fields(block: (field: Int, reader: Reader) -> Unit) {
            while (pos < end) {
                val tag = varint()
                wireType = (tag and 7).toInt()
                block((tag ushr 3).toInt(), this)
            }
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                check(pos < end) { "truncated varint" }
                val b = buf[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                check(shift < 64) { "bad varint" }
            }
        }

        private fun length(): Int {
            val len = varint()
            check(len >= 0 && pos + len <= end) { "bad length" }
            return len.toInt()
        }

        fun string(): String {
            check(wireType == 2) { "not a string" }
            val len = length()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun message(): Reader {
            check(wireType == 2) { "not a message" }
            val len = length()
            val r = Reader(buf, pos, pos + len)
            pos += len
            return r
        }

        fun skip() {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    // not "pos += length()": that reads pos before length() moves it
                    val len = length()
                    pos += len
                }
                5 -> pos += 4
                else -> throw IllegalStateException("unsupported wire type $wireType")
            }
            check(pos <= end) { "truncated field" }
        }
    }

    /** the native trace is a binary tombstone, keep the readable lines that matter */
    private fun nativeSummary(bytes: ByteArray): String {
        val interesting = Regex("""\.so|_Z|::|SIG|signal|[Aa]bort|fault|Cause""")
        val lines = ArrayList<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 6) {
                val s = run.toString()
                if (interesting.containsMatchIn(s) && (lines.isEmpty() || lines.last() != s)) lines.add(s)
            }
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) run.append(c.toChar()) else flush()
            if (lines.size >= MAX_NATIVE_LINES) break
        }
        flush()
        return lines.take(MAX_NATIVE_LINES).joinToString("\n")
    }

    /** ANR traces list every thread, the main thread is what matters */
    private fun anrSummary(text: String): String {
        val start = text.indexOf("\"main\"")
        if (start < 0) return text.take(3000)
        return text.substring(start).lineSequence().take(40).joinToString("\n")
    }

    /** time, app version, device and state lines, as used at the top of a report */
    fun header(context: Context): String = header(context.applicationContext, System.currentTimeMillis())

    private fun header(app: Context, time: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
        val version = try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
            "${info.versionName} (${info.longVersionCode})"
        } catch (e: Throwable) {
            "?"
        }
        val state = try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null)
        } catch (e: Throwable) {
            null
        }
        return "时间：$date\n" +
            "版本：$version\n" +
            "设备：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n" +
            "状态：${state ?: "-"}\n"
    }
}
