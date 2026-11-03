package com.littlez.usbdisplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the capture card. Every UVCCamera call happens on one background thread,
 * results are reported to [Listener] on the main thread.
 */
class CaptureController(context: Context, private val listener: Listener) {

    enum class Status { DISCONNECTED, CONNECTING, STREAMING, NO_SIGNAL, ERROR }

    data class Stats(
        val mode: CaptureMode,
        /** frame rate agreed with the capture card */
        val negotiatedFps: Float,
        /** frames per second coming from the card */
        val captureFps: Float,
        /** frames per second drawn on the screen */
        val displayFps: Float,
        /** average time to decode and draw one frame */
        val drawMs: Float,
    )

    interface Listener {
        fun onStatus(status: Status, error: String?)
        fun onModes(modes: List<CaptureMode>, active: CaptureMode?, auto: Boolean)
        fun onFrameSize(width: Int, height: Int)
        fun onStats(stats: Stats)
    }

    private val appContext = context.applicationContext
    private val thread = HandlerThread("capture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // everything below is only touched on the capture thread
    private var ctrlBlock: USBMonitor.UsbControlBlock? = null
    private var camera: UVCCamera? = null
    private var deviceName: String? = null
    private var surface: Surface? = null
    private var modes: List<CaptureMode> = emptyList()
    private var preferredKey: String? = null
    private var candidates: List<CaptureMode> = emptyList()
    private var candidateIndex = 0
    private var failedCycles = 0
    private var deviceDeliveredFrames = false
    private var activeMode: CaptureMode? = null
    private var previewing = false
    private var transform = UVCCamera.PREVIEW_TRANSFORM_NONE
    private var status = Status.DISCONNECTED

    private val stats = IntArray(UVCCamera.STAT_COUNT)
    private var startTime = 0L
    private var lastFrameTime = 0L
    private var lastReceivedSeen = -1
    private var lastStatsTime = 0L
    private var lastStatsReceived = 0
    private var lastStatsDrawn = 0

    private val pollRunnable = Runnable { poll() }

    // ---------------------------------------------------------------- public API

    fun open(device: UsbDevice, preferredModeKey: String?) {
        handler.post { openInternal(device, preferredModeKey) }
    }

    fun close() {
        handler.post { closeInternal() }
    }

    /** null = surface is going away; waits until drawing has stopped */
    fun setSurface(s: Surface?) {
        if (s != null) {
            handler.post {
                surface = s
                if (previewing) restartPreview() else startIfReady()
            }
        } else {
            val done = CountDownLatch(1)
            handler.post {
                stopPreviewInternal()
                surface = null
                done.countDown()
            }
            try {
                done.await(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** one of UVCCamera.PREVIEW_TRANSFORM_* */
    fun setTransform(t: Int) {
        handler.post {
            transform = t
            try {
                camera?.setPreviewTransform(t)
            } catch (e: Exception) {
                Log.w(TAG, "setPreviewTransform failed", e)
            }
        }
    }

    /** null = automatic selection */
    fun selectMode(key: String?) {
        handler.post {
            preferredKey = key
            if (camera == null) return@post
            deviceDeliveredFrames = false
            buildCandidates()
            postModes()
            restartPreview()
        }
    }

    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- capture thread

    private fun openInternal(device: UsbDevice, preferredModeKey: String?) {
        if (camera != null && deviceName == device.deviceName) return
        closeInternal()
        preferredKey = preferredModeKey
        setStatus(Status.CONNECTING)
        var block: USBMonitor.UsbControlBlock? = null
        var cam: UVCCamera? = null
        try {
            block = USBMonitor.getInstance(appContext).openDevice(device)
            cam = UVCCamera()
            cam.open(block)
            val parsed = CaptureMode.parse(cam.supportedSize)
            Log.i(TAG, "supported: ${cam.supportedSize}")
            if (parsed.isEmpty()) {
                throw IllegalStateException(appContext.getString(R.string.error_no_modes))
            }
            ctrlBlock = block
            camera = cam
            deviceName = device.deviceName
            modes = parsed
            deviceDeliveredFrames = false
            buildCandidates()
            postModes()
            startIfReady()
        } catch (e: Throwable) {
            // Throwable: a missing native library shows up as UnsatisfiedLinkError
            Log.e(TAG, "open failed", e)
            try {
                cam?.destroy()
            } catch (ignored: Throwable) {
            }
            try {
                block?.close()
            } catch (ignored: Throwable) {
            }
            camera = null
            ctrlBlock = null
            deviceName = null
            val message = if (e is IllegalStateException && e.message != null) {
                e.message
            } else {
                appContext.getString(R.string.error_open)
            }
            setStatus(Status.ERROR, message)
        }
    }

    private fun closeInternal() {
        stopPreviewInternal()
        try {
            camera?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "destroy failed", e)
        }
        camera = null
        try {
            ctrlBlock?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "close failed", e)
        }
        ctrlBlock = null
        deviceName = null
        modes = emptyList()
        candidates = emptyList()
        activeMode = null
        setStatus(Status.DISCONNECTED)
    }

    private fun buildCandidates() {
        val chosen = preferredKey?.let { key -> modes.firstOrNull { it.key == key } }
        candidates = if (chosen != null) listOf(chosen) else CaptureMode.autoCandidates(modes)
        candidateIndex = 0
        failedCycles = 0
    }

    private fun startIfReady() {
        val cam = camera ?: return
        val s = surface ?: return
        if (previewing || candidates.isEmpty()) return
        // a mode the card rejects right away is skipped immediately
        for (attempt in candidates.indices) {
            val index = (candidateIndex + attempt) % candidates.size
            if (startWithMode(cam, s, candidates[index])) {
                candidateIndex = index
                return
            }
        }
        setStatus(Status.ERROR, appContext.getString(R.string.error_no_start))
    }

    private fun startWithMode(cam: UVCCamera, s: Surface, mode: CaptureMode): Boolean {
        return try {
            cam.setPreviewSize(mode.width, mode.height, mode.fps - 1, mode.fps + 1,
                mode.uvcFormat, UVCCamera.DEFAULT_BANDWIDTH)
            cam.setLowLatencyMode(true)
            cam.setPreviewTransform(transform)
            cam.setPreviewDisplay(s)
            cam.startPreview()
            previewing = true
            activeMode = mode
            val now = SystemClock.elapsedRealtime()
            startTime = now
            lastFrameTime = 0L
            cam.getPreviewStats(stats)
            lastReceivedSeen = stats[UVCCamera.STAT_FRAMES_RECEIVED]
            lastStatsTime = now
            lastStatsReceived = stats[UVCCamera.STAT_FRAMES_RECEIVED]
            lastStatsDrawn = stats[UVCCamera.STAT_FRAMES_DRAWN]
            Log.i(TAG, "preview started: ${mode.key}")
            main.post { listener.onFrameSize(mode.width, mode.height) }
            postModes()
            if (status != Status.NO_SIGNAL) setStatus(Status.CONNECTING)
            handler.removeCallbacks(pollRunnable)
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "mode ${mode.key} rejected", e)
            false
        }
    }

    private fun stopPreviewInternal() {
        handler.removeCallbacks(pollRunnable)
        if (previewing) {
            try {
                camera?.stopPreview()
            } catch (e: Throwable) {
                Log.w(TAG, "stopPreview failed", e)
            }
            previewing = false
        }
    }

    private fun restartPreview() {
        stopPreviewInternal()
        startIfReady()
    }

    /** runs every POLL_INTERVAL_MS while previewing: frame rate, no-signal detection, mode fallback */
    private fun poll() {
        val cam = camera ?: return
        if (!previewing) return
        val mode = activeMode ?: return
        if (cam.getPreviewStats(stats) < UVCCamera.STAT_COUNT) return
        val now = SystemClock.elapsedRealtime()
        val received = stats[UVCCamera.STAT_FRAMES_RECEIVED]
        val drawn = stats[UVCCamera.STAT_FRAMES_DRAWN]
        val streamState = stats[UVCCamera.STAT_STREAM_STATE]

        if (received != lastReceivedSeen) {
            lastReceivedSeen = received
            lastFrameTime = now
            deviceDeliveredFrames = true
            failedCycles = 0
            setStatus(Status.STREAMING)
        } else if (lastFrameTime == 0L) {
            // nothing arrived since this mode was started
            val timeout = if (failedCycles == 0) PROBE_TIMEOUT_MS else PROBE_TIMEOUT_SLOW_MS
            if (streamState < 0 || now - startTime >= timeout) {
                val auto = preferredKey == null
                if (auto && !deviceDeliveredFrames && candidates.size > 1) {
                    // the card may list a mode it can not deliver on this USB port, try the next one
                    candidateIndex = (candidateIndex + 1) % candidates.size
                    if (candidateIndex == 0) failedCycles++
                    if (failedCycles > 0) setStatus(Status.NO_SIGNAL)
                    Log.i(TAG, "no frames with ${mode.key}, trying ${candidates[candidateIndex].key}")
                    restartPreview()
                    return
                }
                setStatus(Status.NO_SIGNAL)
            }
        } else if (now - lastFrameTime >= NO_SIGNAL_MS) {
            setStatus(Status.NO_SIGNAL)
        }

        val dt = now - lastStatsTime
        if (dt >= STATS_INTERVAL_MS) {
            val captureFps = (received - lastStatsReceived) * 1000f / dt
            val displayFps = (drawn - lastStatsDrawn) * 1000f / dt
            val result = Stats(
                mode = mode,
                negotiatedFps = stats[UVCCamera.STAT_FPS_X100] / 100f,
                captureFps = captureFps,
                displayFps = displayFps,
                drawMs = stats[UVCCamera.STAT_DRAW_TIME_US] / 1000f,
            )
            lastStatsTime = now
            lastStatsReceived = received
            lastStatsDrawn = drawn
            main.post { listener.onStats(result) }
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun postModes() {
        val list = modes
        val active = activeMode
        val auto = preferredKey == null || list.none { it.key == preferredKey }
        main.post { listener.onModes(list, active, auto) }
    }

    private fun setStatus(s: Status, error: String? = null) {
        if (s == status && error == null) return
        status = s
        main.post { listener.onStatus(s, error) }
    }

    companion object {
        private const val TAG = "CaptureController"
        private const val POLL_INTERVAL_MS = 500L
        private const val STATS_INTERVAL_MS = 1000L
        private const val PROBE_TIMEOUT_MS = 4000L
        private const val PROBE_TIMEOUT_SLOW_MS = 10000L
        private const val NO_SIGNAL_MS = 2000L
    }
}
