package com.chesscoach.overlay.vision

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Android 14+ (API 34) safe MediaProjection screen-capture reader.
 *
 * Lifecycle order enforced:
 * 1. Caller MUST have already called startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION).
 * 2. getMediaProjection(resultCode, data)
 * 3. mediaProjection.registerCallback(callback, mainHandler) BEFORE createVirtualDisplay(...)
 * 4. ImageReader (RGBA_8888, downscaled to ~540px width for low-CPU/battery capture)
 * 5. Poll latest frame every ~450ms on a background thread, always closing Image in finally {}
 */
class ScreenBoardReader(
    private val context: Context,
    private val onFrameAnalyzed: (FrameAnalysis) -> Unit,
    private val onProjectionStopped: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var capWidth: Int = 540
    private var capHeight: Int = 1200
    private var pixelBuffer: IntArray = IntArray(0)

    @Volatile
    var lockedScreenRect: PixelRect? = null

    val isActive: Boolean
        get() = running.get()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onProjectionStopped()
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try {
                captureAndAnalyzeOnce()
            } catch (_: Throwable) {
                // Keep polling resilient across transient Surface/display changes
            }
            if (running.get()) {
                bgHandler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    fun start(resultCode: Int, projectionData: Intent): Boolean {
        stop()

        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return false

        val mp = try {
            projectionManager.getMediaProjection(resultCode, projectionData)
        } catch (_: Throwable) {
            null
        } ?: return false

        // Compute full physical screen dimensions so detected rects match FLAG_LAYOUT_IN_SCREEN
        val (fullW, fullH, dpi) = queryPhysicalScreenMetrics()
        screenWidth = fullW.coerceAtLeast(320)
        screenHeight = fullH.coerceAtLeast(480)

        capWidth = min(screenWidth, TARGET_CAPTURE_WIDTH).coerceAtLeast(320) and 1.inv()
        capHeight = ((screenHeight.toLong() * capWidth) / screenWidth).toInt().coerceAtLeast(320) and 1.inv()
        pixelBuffer = IntArray(capWidth * capHeight)

        val thread = HandlerThread("ScreenBoardReader").apply { start() }
        val handler = Handler(thread.looper)

        bgThread = thread
        bgHandler = handler
        mediaProjection = mp

        try {
            // CRITICAL Android 14 (API 34) requirement: registerCallback BEFORE createVirtualDisplay
            mp.registerCallback(projectionCallback, mainHandler)

            val reader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            val vd = mp.createVirtualDisplay(
                "ChessBoardCapture",
                capWidth,
                capHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            if (vd == null) {
                stop()
                return false
            }
            virtualDisplay = vd
            running.set(true)
            handler.postDelayed(captureRunnable, INITIAL_DELAY_MS)
            return true
        } catch (_: Throwable) {
            stop()
            return false
        }
    }

    fun stop() {
        val wasRunning = running.getAndSet(false)

        bgHandler?.removeCallbacks(captureRunnable)

        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null

        mediaProjection?.let { mp ->
            try {
                mp.unregisterCallback(projectionCallback)
            } catch (_: Throwable) {
            }
            if (wasRunning) {
                try {
                    mp.stop()
                } catch (_: Throwable) {
                }
            }
        }
        mediaProjection = null

        bgThread?.let { t ->
            try {
                t.quitSafely()
            } catch (_: Throwable) {
            }
        }
        bgThread = null
        bgHandler = null
    }

    private fun captureAndAnalyzeOnce() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        val analysis: FrameAnalysis
        try {
            val imgW = image.width
            val imgH = image.height
            if (imgW <= 0 || imgH <= 0) return

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            if (pixelBuffer.size < imgW * imgH) {
                pixelBuffer = IntArray(imgW * imgH)
            }

            var outIdx = 0
            for (y in 0 until imgH) {
                val rowBase = y * rowStride
                for (x in 0 until imgW) {
                    val off = rowBase + x * pixelStride
                    val r = buffer.get(off).toInt() and 0xFF
                    val g = buffer.get(off + 1).toInt() and 0xFF
                    val b = buffer.get(off + 2).toInt() and 0xFF
                    pixelBuffer[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val scaleToScreenX = screenWidth.toFloat() / imgW.toFloat()
            val scaleToScreenY = screenHeight.toFloat() / imgH.toFloat()

            val lockedCapRect = lockedScreenRect?.let { sr ->
                PixelRect(
                    left = (sr.left / scaleToScreenX).toInt().coerceIn(0, imgW - 1),
                    top = (sr.top / scaleToScreenY).toInt().coerceIn(0, imgH - 1),
                    width = (sr.width / scaleToScreenX).toInt().coerceAtLeast(16),
                    height = (sr.height / scaleToScreenY).toInt().coerceAtLeast(16)
                )
            }

            val raw = BoardVisionAnalyzer.analyzeFrame(pixelBuffer, imgW, imgH, lockedCapRect)
            val scaledDetection = if (raw.detection.found) {
                raw.detection.copy(rect = raw.detection.rect.scaled(scaleToScreenX, scaleToScreenY))
            } else {
                raw.detection
            }
            analysis = FrameAnalysis(scaledDetection, raw.grid)
        } finally {
            image.close()
        }

        if (running.get()) {
            mainHandler.post {
                if (running.get()) {
                    onFrameAnalyzed(analysis)
                }
            }
        }
    }

    private fun queryPhysicalScreenMetrics(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(160)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(dm)
            Triple(
                if (dm.widthPixels > 0) dm.widthPixels else context.resources.displayMetrics.widthPixels,
                if (dm.heightPixels > 0) dm.heightPixels else context.resources.displayMetrics.heightPixels,
                dpi
            )
        }
    }

    companion object {
        private const val TARGET_CAPTURE_WIDTH = 540
        private const val POLL_INTERVAL_MS = 450L
        private const val INITIAL_DELAY_MS = 350L
    }
}
