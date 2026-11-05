package com.littlez.usbdisplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jiangdg.uvc.UVCCamera
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full screen "monitor": shows the picture of an HDMI capture card.
 * Tap the screen for a small menu (resolution, fit / stretch, orientation).
 */
class MonitorActivity : Activity(), CaptureController.Listener, SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var menuPanel: LinearLayout
    private lateinit var buttonRow: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var modeButton: Button
    private lateinit var scaleButton: Button
    private lateinit var rotateButton: Button
    private lateinit var diagButton: Button

    private lateinit var prefs: SharedPreferences
    private lateinit var controller: CaptureController
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideMenuRunnable = Runnable { hideMenu() }

    private var started = false
    private var currentDevice: UsbDevice? = null
    private var permissionPending = false
    private var retryOnTap = false
    private var openSettingsOnTap = false
    private var shownMenuForStream = false
    /** true while the crash report is shown: do not touch the capture card until it is closed */
    private var holdConnection = false
    private var deviceLabel = "-"

    private var frameWidth = 1920
    private var frameHeight = 1080
    private var stretch = false
    private var rotation = ROTATION_NONE
    private var modes: List<CaptureMode> = emptyList()
    private var activeMode: CaptureMode? = null
    private var autoMode = true
    private var lastStats: CaptureController.Stats? = null
    private var captureStatus = CaptureController.Status.DISCONNECTED

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        stretch = prefs.getBoolean(KEY_STRETCH, false)
        rotation = prefs.getInt(KEY_ROTATION, ROTATION_NONE)
        autoMode = prefs.getString(KEY_MODE, null) == null

        buildViews()
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = attrs
        }
        hideSystemBars()

        controller = CaptureController(this, this)
        controller.setTransform(transformFor(rotation))
        applyOrientation()
        registerUsbReceiver()
        updateMenuLabels()
        CrashLog.takeReport(this)?.let { showCrashReport(it) }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
        started = true
        hideSystemBars()
        startConnection()
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        started = false
        permissionPending = false
        currentDevice = null
        controller.close()
        setKeepScreenOn(false)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(usbReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
        controller.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.usbDevice() ?: return
            if (isCaptureDevice(device) && currentDevice?.deviceName != device.deviceName) {
                connect(device)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startConnection()
        } else {
            // "don't ask again": the only way left is the app settings page
            openSettingsOnTap = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            showMessage(getString(R.string.status_need_camera))
            retryOnTap = true
        }
    }

    // ---------------------------------------------------------------- USB

    private fun startConnection() {
        if (!started || holdConnection) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        val device = findCaptureDevice()
        if (device == null) {
            showNoDevice()
            return
        }
        connect(device)
    }

    private fun connect(device: UsbDevice) {
        if (!started) return
        if (usbManager.hasPermission(device)) {
            currentDevice = device
            retryOnTap = false
            deviceLabel = describeDevice(device)
            saveCrashState()
            controller.open(device, prefs.getString(KEY_MODE, null))
        } else if (!permissionPending) {
            permissionPending = true
            showMessage(getString(R.string.status_wait_usb))
            // mutable so that the system can add the result extras, explicit package as Android 14 requires
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
        }
    }

    private fun findCaptureDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { isCaptureDevice(it) }

    private fun isCaptureDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice()
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    permissionPending = false
                    if (device == null || !started) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        || usbManager.hasPermission(device)) {
                        connect(device)
                    } else {
                        showMessage(getString(R.string.status_need_usb))
                        retryOnTap = true
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && isCaptureDevice(device) && currentDevice == null) {
                        // let the system's own "open with" dialog go first, it grants permission too
                        mainHandler.postDelayed({
                            if (started && currentDevice == null) connect(device)
                        }, ATTACH_DELAY_MS)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == currentDevice?.deviceName) {
                        currentDevice = null
                        controller.close()
                        showNoDevice()
                    }
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    // ---------------------------------------------------------------- CaptureController.Listener

    override fun onStatus(status: CaptureController.Status, error: String?) {
        val previous = captureStatus
        captureStatus = status
        when (status) {
            CaptureController.Status.DISCONNECTED -> setKeepScreenOn(false)
            CaptureController.Status.CONNECTING -> {
                shownMenuForStream = false
                showMessage(getString(R.string.status_connecting))
                setKeepScreenOn(true)
            }
            CaptureController.Status.STREAMING -> {
                showMessage(null)
                setKeepScreenOn(true)
                if (!shownMenuForStream && previous != CaptureController.Status.STREAMING) {
                    // show the frame rate once so that it is easy to check
                    shownMenuForStream = true
                    showMenu()
                }
            }
            CaptureController.Status.NO_SIGNAL -> {
                showMessage(getString(if (autoMode) R.string.status_no_signal else R.string.status_no_signal_manual))
                setKeepScreenOn(true)
            }
            CaptureController.Status.ERROR -> {
                showMessage(getString(R.string.status_error, error ?: getString(R.string.error_open)))
                retryOnTap = true
                setKeepScreenOn(false)
            }
        }
        updateInfo()
    }

    override fun onModes(modes: List<CaptureMode>, active: CaptureMode?, auto: Boolean) {
        this.modes = modes
        this.activeMode = active
        this.autoMode = auto
        saveCrashState()
        applyFrameRateHint()
        updateInfo()
    }

    override fun onFrameSize(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        updateSurfaceLayout()
    }

    override fun onStats(stats: CaptureController.Stats) {
        lastStats = stats
        if (menuPanel.visibility == View.VISIBLE) updateInfo()
    }

    // ---------------------------------------------------------------- SurfaceHolder.Callback

    override fun surfaceCreated(holder: SurfaceHolder) {
        controller.setSurface(holder.surface)
        applyFrameRateHint()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // buffer size is set by the native side, the compositor scales it to the view
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        controller.setSurface(null)
    }

    // ---------------------------------------------------------------- UI

    private fun buildViews() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener { onScreenTapped() }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    post { updateSurfaceLayout() }
                }
            }
        }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
        })

        infoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(8))
        }
        modeButton = menuButton { showModeDialog() }
        scaleButton = menuButton { toggleStretch() }
        rotateButton = menuButton { cycleRotation() }
        diagButton = menuButton { showDiagnostics() }
        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(modeButton)
            addView(scaleButton)
            addView(rotateButton)
            addView(diagButton)
        }
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true  // taps on the panel must not toggle it
            visibility = View.GONE
            addView(infoText)
            addView(buttonRow)
        }
        root.addView(menuPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
    }

    private fun menuButton(onClick: () -> Unit): Button = Button(this).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener {
            onClick()
            scheduleMenuHide()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), dp(2), dp(4), dp(2))
        }
    }

    private fun onScreenTapped() {
        when {
            retryOnTap && openSettingsOnTap -> {
                retryOnTap = false
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
            retryOnTap -> {
                retryOnTap = false
                currentDevice = null
                controller.close()
                startConnection()
            }
            menuPanel.visibility == View.VISIBLE -> hideMenu()
            else -> showMenu()
        }
    }

    private fun showMenu() {
        updateMenuLabels()
        updateInfo()
        menuPanel.visibility = View.VISIBLE
        scheduleMenuHide()
    }

    private fun hideMenu() {
        mainHandler.removeCallbacks(hideMenuRunnable)
        menuPanel.visibility = View.GONE
    }

    private fun scheduleMenuHide() {
        mainHandler.removeCallbacks(hideMenuRunnable)
        mainHandler.postDelayed(hideMenuRunnable, MENU_TIMEOUT_MS)
    }

    private fun showMessage(text: CharSequence?) {
        if (text.isNullOrEmpty()) {
            statusText.visibility = View.GONE
        } else {
            statusText.text = text
            statusText.visibility = View.VISIBLE
        }
    }

    private fun showNoDevice() {
        retryOnTap = false
        showMessage(getString(R.string.status_no_device) + "\n\n" + getString(R.string.status_no_device_hint))
    }

    private fun updateMenuLabels() {
        modeButton.setText(R.string.menu_mode)
        diagButton.setText(R.string.menu_diag)
        scaleButton.setText(if (stretch) R.string.menu_stretch else R.string.menu_fit)
        rotateButton.setText(when (rotation) {
            ROTATION_CW -> R.string.menu_portrait_cw
            ROTATION_CCW -> R.string.menu_portrait_ccw
            else -> R.string.menu_landscape
        })
    }

    private fun updateInfo() {
        val lines = ArrayList<String>()
        val stats = lastStats
        if (stats != null && captureStatus == CaptureController.Status.STREAMING) {
            lines.add(getString(R.string.info_stream, stats.mode.width, stats.mode.height,
                formatLabel(stats.mode.format), stats.captureFps, stats.displayFps, stats.drawMs))
            val negotiated = stats.negotiatedFps.roundToInt()
            if (negotiated in 1 until CaptureMode.MIN_SMOOTH_FPS) {
                lines.add(getString(R.string.info_low_fps, negotiated))
            }
        }
        if (rotation != ROTATION_NONE) lines.add(getString(R.string.info_portrait_hint))
        infoText.text = lines.joinToString("\n")
        infoText.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showModeDialog() {
        val list = modes
        if (list.isEmpty()) return
        val current = activeMode
        val labels = ArrayList<String>()
        labels.add(if (autoMode && current != null) {
            getString(R.string.mode_auto_current, modeLabel(current))
        } else {
            getString(R.string.mode_auto)
        })
        list.forEach { labels.add(modeLabel(it)) }
        val savedKey = prefs.getString(KEY_MODE, null)
        val checked = if (autoMode) 0 else list.indexOfFirst { it.key == savedKey } + 1
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.mode_dialog_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked.coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                val key = if (which <= 0) null else list[which - 1].key
                prefs.edit().apply {
                    if (key == null) remove(KEY_MODE) else putString(KEY_MODE, key)
                }.apply()
                autoMode = key == null
                lastStats = null
                controller.selectMode(key)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun modeLabel(m: CaptureMode) =
        getString(R.string.mode_label, m.width, m.height, m.fps, formatLabel(m.format))

    private fun formatLabel(f: CaptureMode.Format) =
        getString(if (f == CaptureMode.Format.YUYV) R.string.format_yuyv else R.string.format_mjpeg)

    private fun toggleStretch() {
        stretch = !stretch
        prefs.edit().putBoolean(KEY_STRETCH, stretch).apply()
        saveCrashState()
        updateSurfaceLayout()
        updateMenuLabels()
    }

    private fun cycleRotation() {
        rotation = when (rotation) {
            ROTATION_NONE -> ROTATION_CW
            ROTATION_CW -> ROTATION_CCW
            else -> ROTATION_NONE
        }
        prefs.edit().putInt(KEY_ROTATION, rotation).apply()
        saveCrashState()
        controller.setTransform(transformFor(rotation))
        applyOrientation()
        updateSurfaceLayout()
        updateMenuLabels()
        updateInfo()
    }

    private fun transformFor(r: Int) = when (r) {
        ROTATION_CW -> UVCCamera.PREVIEW_TRANSFORM_ROTATE_90
        ROTATION_CCW -> UVCCamera.PREVIEW_TRANSFORM_ROTATE_270
        else -> UVCCamera.PREVIEW_TRANSFORM_NONE
    }

    private fun applyOrientation() {
        requestedOrientation = if (rotation == ROTATION_NONE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /** size the SurfaceView: keep the aspect ratio (letterbox) or fill the screen */
    private fun updateSurfaceLayout() {
        val cw = root.width
        val ch = root.height
        if (cw <= 0 || ch <= 0 || frameWidth <= 0 || frameHeight <= 0) return
        val rotated = rotation != ROTATION_NONE
        val srcW = if (rotated) frameHeight else frameWidth
        val srcH = if (rotated) frameWidth else frameHeight
        val w: Int
        val h: Int
        if (stretch) {
            w = cw
            h = ch
        } else {
            val scale = min(cw.toFloat() / srcW, ch.toFloat() / srcH)
            w = (srcW * scale).roundToInt()
            h = (srcH * scale).roundToInt()
        }
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h || lp.gravity != Gravity.CENTER) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
        // narrow (portrait) screens: stack the menu buttons
        val orientation = if (cw < ch) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (buttonRow.orientation != orientation) buttonRow.orientation = orientation
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Build.VERSION.SDK_INT < 35) {
                // Android 15+ always draws edge to edge
                @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false)
            }
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    /** tell the system the frame rate of the picture so that adaptive refresh rate never goes below it */
    private fun applyFrameRateHint() {
        if (Build.VERSION.SDK_INT < 30) return
        val fps = activeMode?.fps ?: return
        val surface = surfaceView.holder.surface ?: return
        if (!surface.isValid) return
        try {
            surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        } catch (e: Exception) {
            // not supported on this device, the default refresh rate is used
        }
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun describeDevice(device: UsbDevice): String {
        val name = try {
            device.productName
        } catch (e: Exception) {
            null
        }
        val usb = try {
            device.version
        } catch (e: Exception) {
            null
        }
        return String.format(Locale.US, "%s (%04x:%04x) USB %s", name ?: "?", device.vendorId, device.productId, usb ?: "?")
    }

    /** remembered for the crash report: which card and settings were in use */
    private fun saveCrashState() {
        val mode = activeMode?.key ?: "-"
        val view = if (stretch) "stretch" else "fit"
        CrashLog.setState(this, "采集卡 $deviceLabel，模式 $mode${if (autoMode) "(自动)" else ""}，方向 $rotation，$view")
    }

    private fun showCrashReport(report: String) {
        holdConnection = true
        showReportDialog(R.string.crash_title, R.string.crash_message, report) {
            holdConnection = false
            hideSystemBars()
            startConnection()
        }
    }

    private fun showDiagnostics() {
        controller.diagnostics { text ->
            if (isFinishing || isDestroyed) return@diagnostics
            val report = CrashLog.header(this) + "采集卡：$deviceLabel\n" + text
            showReportDialog(R.string.diag_title, R.string.diag_message, report) { hideSystemBars() }
        }
    }

    private fun showReportDialog(title: Int, message: Int, report: String, onDismiss: () -> Unit) {
        val text = TextView(this).apply {
            this.text = report
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setView(scroll)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                try {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("crash", report))
                    Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
                } catch (ignored: Exception) {
                }
            }
            .setNegativeButton(R.string.crash_close, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    companion object {
        private const val TAG = "MonitorActivity"
        private const val ACTION_USB_PERMISSION = "com.littlez.usbdisplay.USB_PERMISSION"
        private const val REQUEST_CAMERA = 1
        private const val ATTACH_DELAY_MS = 1000L
        private const val MENU_TIMEOUT_MS = 6000L

        private const val PREFS = "display"
        private const val KEY_MODE = "mode"
        private const val KEY_STRETCH = "stretch"
        private const val KEY_ROTATION = "rotation"

        private const val ROTATION_NONE = 0
        private const val ROTATION_CW = 90
        private const val ROTATION_CCW = 270
    }
}
