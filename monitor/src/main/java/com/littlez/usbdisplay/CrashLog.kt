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
                sb.append(if (anr) anrSummary(String(bytes)) else nativeSummary(bytes))
            }
        } catch (ignored: Throwable) {
        }
        return sb.toString()
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
