package com.littlez.usbdisplay

import com.jiangdg.uvc.UVCCamera
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/**
 * One output mode of the capture card: size, frame rate and pixel format.
 */
data class CaptureMode(
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: Format,
) {
    enum class Format { YUYV, MJPEG }

    /** stable id used to remember the user's choice */
    val key: String get() = "${width}x${height}@${fps}:${format.name}"

    val uvcFormat: Int
        get() = if (format == Format.YUYV) UVCCamera.FRAME_FORMAT_YUYV else UVCCamera.FRAME_FORMAT_MJPEG

    val area: Int get() = width * height

    val isSixtyFps: Boolean get() = fps >= MIN_SMOOTH_FPS

    /** 16:9 within 2%, e.g. 1920x1080, 1280x720, 1360x768 */
    val is16x9: Boolean get() = abs(width * 9 - height * 16) <= height * 16 / 50

    companion object {
        /** 59.94 Hz sources are reported as 59 or 60 fps */
        const val MIN_SMOOTH_FPS = 59

        /** decoding more than 1080p at 60 fps is too heavy for many phones */
        private const val AUTO_MAX_AREA = 1920 * 1080

        /** how many modes the automatic selection tries before starting over */
        private const val AUTO_MAX_CANDIDATES = 4

        private const val UVC_VS_FORMAT_UNCOMPRESSED = 4
        private const val UVC_VS_FORMAT_MJPEG = 6

        /**
         * Parse the JSON from [UVCCamera.getSupportedSize]:
         * {"formats":[{"type":6,"fourcc":"MJPG","size":["1920x1080",...],"fps":[[60,30],...]}]}
         * For every size and format only the highest frame rate is kept,
         * plus 60 fps when the card also offers something faster.
         */
        fun parse(json: String?): List<CaptureMode> {
            if (json.isNullOrEmpty()) return emptyList()
            val result = LinkedHashSet<CaptureMode>()
            try {
                val formats = JSONObject(json).optJSONArray("formats") ?: return emptyList()
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val fourcc = f.optString("fourcc", "")
                    val format = when (f.optInt("type", -1)) {
                        UVC_VS_FORMAT_MJPEG -> Format.MJPEG
                        // the native side can only convert YUY2 among the uncompressed formats
                        UVC_VS_FORMAT_UNCOMPRESSED -> if (fourcc.isEmpty() || fourcc == "YUY2") Format.YUYV else null
                        else -> null
                    } ?: continue
                    val sizes = f.optJSONArray("size") ?: continue
                    val fpsLists = f.optJSONArray("fps")
                    for (j in 0 until sizes.length()) {
                        val parts = sizes.optString(j).split("x")
                        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                        if (w <= 0 || h <= 0) continue
                        val fpsList = fpsLists?.optJSONArray(j)
                        val rates = if (fpsList != null && fpsList.length() > 0) {
                            (0 until fpsList.length()).map { fpsList.optInt(it) }.filter { it > 0 }
                        } else {
                            listOf(30)  // older native library without frame rate information
                        }
                        val max = rates.maxOrNull() ?: continue
                        result.add(CaptureMode(w, h, max, format))
                        val sixty = rates.filter { it in MIN_SMOOTH_FPS..61 }.maxOrNull()
                        if (sixty != null && max > 61) {
                            result.add(CaptureMode(w, h, sixty, format))
                        }
                    }
                }
            } catch (e: JSONException) {
                return emptyList()
            }
            return result.sortedWith(menuOrder)
        }

        /** order shown in the menu: 60 fps modes first, then bigger, then faster */
        private val menuOrder = compareBy<CaptureMode>(
            { if (it.isSixtyFps) 0 else 1 },
            { -it.area },
            { -it.fps },
            { if (it.format == Format.YUYV) 0 else 1 },
        )

        /**
         * Modes tried by the automatic selection, best first:
         * at least 60 fps, up to 1080p, 16:9, as big as possible,
         * uncompressed before MJPEG (sharper text, no decoding needed).
         * When the card has no 60 fps mode the fastest modes are used.
         */
        fun autoCandidates(modes: List<CaptureMode>): List<CaptureMode> {
            val order = compareBy<CaptureMode>(
                { if (it.isSixtyFps) 0 else 1 },
                { if (it.isSixtyFps) 0 else -it.fps },
                { if (it.area <= AUTO_MAX_AREA) 0 else 1 },
                { if (it.is16x9) 0 else 1 },
                { -it.area },
                { if (it.format == Format.YUYV) 0 else 1 },
                { abs(it.fps - 60) },
            )
            return modes.sortedWith(order).take(AUTO_MAX_CANDIDATES)
        }
    }
}
