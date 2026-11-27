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
import com.chesscoach.overlay.engine.Move
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Android 14/15 (API 34+) safe MediaProjection screen-capture reader.
 *
 * Key guarantees:
 * 1. Caller MUST have already called startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION).
 * 2. mediaProjection.registerCallback(callback, mainHandler) runs BEFORE createVirtualDisplay(...).
 * 3. VirtualDisplay DPI is scaled proportionally to the downscaled capture width.
 * 4. ImageReader.setOnImageAvailableListener immediately copies every new frame into a reusable
 *    latestPixels buffer (closing Image in finally {}), and the 450ms capture tick re-analyzes
 *    latestPixels even on static screens (e.g. offline/bot games without a ticking clock) so
 *    BoardTrackingSession never starves for consecutive frames.
 * 5. Never silently swallows setup or revocation errors — exposes exact stage + exception in lastError.
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

    // Accessed exclusively on bgHandler (serialized across ImageAvailableListener & captureRunnable)
    private var latestPixels: IntArray = IntArray(0)
    private var latestWidth: Int = 0
    private var latestHeight: Int = 0
    private var hasLatestFrame: Boolean = false
    private var framesCaptured: Long = 0L
    private var latestFrameMinLum: Int = -1
    private var latestFrameMaxLum: Int = -1

    @Volatile
    var lockedScreenRect: PixelRect? = null

    @Volatile
    var activeArrow: Move? = null

    @Volatile
    var activeArrowPlayingAsWhite: Boolean = true

    @Volatile
    var lastError: String? = null
        private set

    val isActive: Boolean
        get() = running.get()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!running.get()) return
            lastError = "Screen capture stopped by Android"
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
        lastError = null

        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            lastError = "MediaProjectionManager unavailable"
            return false
        }

        val mp = try {
            projectionManager.getMediaProjection(resultCode, projectionData)
        } catch (t: Throwable) {
            lastError = formatError("getMediaProjection", t)
            null
        }
        if (mp == null) {
            if (lastError == null) {
                lastError = "getMediaProjection returned null"
            }
            return false
        }

        // Compute full physical screen dimensions so detected rects match FLAG_LAYOUT_IN_SCREEN
        val (fullW, fullH, dpi) = queryPhysicalScreenMetrics()
        screenWidth = fullW.coerceAtLeast(320)
        screenHeight = fullH.coerceAtLeast(480)

        capWidth = min(screenWidth, TARGET_CAPTURE_WIDTH).coerceAtLeast(320) and 1.inv()
        capHeight = ((screenHeight.toLong() * capWidth) / screenWidth).toInt().coerceAtLeast(320) and 1.inv()
        val capDpi = ((dpi.toLong() * capWidth) / screenWidth).toInt().coerceAtLeast(120)

        latestPixels = IntArray(capWidth * capHeight)
        latestWidth = 0
        latestHeight = 0
        hasLatestFrame = false
        framesCaptured = 0L
        latestFrameMinLum = -1
        latestFrameMaxLum = -1

        val thread = HandlerThread("ScreenBoardReader").apply { start() }
        val handler = Handler(thread.looper)

        bgThread = thread
        bgHandler = handler
        mediaProjection = mp

        var stage = "registerCallback"
        try {
            // CRITICAL Android 14 (API 34) requirement: registerCallback BEFORE createVirtualDisplay
            mp.registerCallback(projectionCallback, mainHandler)

            stage = "ImageReader"
            val reader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                try {
                    drainLatestImageIntoBuffer(r)
                } catch (_: Throwable) {
                }
            }, handler)
            imageReader = reader
            running.set(true)

            stage = "createVirtualDisplay"
            val vd = mp.createVirtualDisplay(
                "ChessBoardCapture",
                capWidth,
                capHeight,
                capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            if (vd == null) {
                lastError = "createVirtualDisplay returned null"
                stop()
                return false
            }
            virtualDisplay = vd
            handler.postDelayed(captureRunnable, INITIAL_DELAY_MS)
            return true
        } catch (t: Throwable) {
            lastError = formatError(stage, t)
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
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Throwable) {
        }
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
        hasLatestFrame = false
        framesCaptured = 0L
        latestWidth = 0
        latestHeight = 0
        latestFrameMinLum = -1
        latestFrameMaxLum = -1
    }

    private fun drainLatestImageIntoBuffer(reader: ImageReader): Boolean {
        if (!running.get()) return false
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return false

        try {
            val imgW = image.width
            val imgH = image.height
            if (imgW <= 0 || imgH <= 0) return false

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val needed = imgW * imgH
            if (latestPixels.size < needed) {
                latestPixels = IntArray(needed)
            }

            var outIdx = 0
            for (y in 0 until imgH) {
                val rowBase = y * rowStride
                for (x in 0 until imgW) {
                    val off = rowBase + x * pixelStride
                    val r = buffer.get(off).toInt() and 0xFF
                    val g = buffer.get(off + 1).toInt() and 0xFF
                    val b = buffer.get(off + 2).toInt() and 0xFF
                    latestPixels[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            // Fast 16x16 luma sample (<0.1ms) to detect black/blank OS capture frames
            var minLum = 255
            var maxLum = 0
            val gridN = 16
            for (gy in 0 until gridN) {
                val sy = ((gy + 0.5f) * imgH / gridN).toInt().coerceIn(0, imgH - 1)
                val rowOff = sy * imgW
                for (gx in 0 until gridN) {
                    val sx = ((gx + 0.5f) * imgW / gridN).toInt().coerceIn(0, imgW - 1)
                    val p = latestPixels[rowOff + sx]
                    val pr = (p ushr 16) and 0xFF
                    val pg = (p ushr 8) and 0xFF
                    val pb = p and 0xFF
                    val lum = (299 * pr + 587 * pg + 114 * pb) / 1000
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum
                }
            }
            latestFrameMinLum = minLum
            latestFrameMaxLum = maxLum

            latestWidth = imgW
            latestHeight = imgH
            hasLatestFrame = true
            framesCaptured++
            return true
        } finally {
            image.close()
        }
    }

    private fun captureAndAnalyzeOnce() {
        val reader = imageReader ?: return

        // Step 1: Drain any newly rendered frame first
        try {
            drainLatestImageIntoBuffer(reader)
        } catch (_: Throwable) {
        }

        // Step 2: If no screen frame has arrived yet, still report diagnostic heartbeat
        if (!hasLatestFrame || latestWidth <= 0 || latestHeight <= 0) {
            val waitingAnalysis = FrameAnalysis(
                detection = BoardDetectionResult.NOT_FOUND,
                grid = null,
                framesCaptured = framesCaptured,
                lastBestScore = 0f,
                lastDetectionNote = "Waiting for screen frames..."
            )
            if (running.get()) {
                mainHandler.post {
                    if (running.get()) {
                        onFrameAnalyzed(waitingAnalysis)
                    }
                }
            }
            return
        }

        // Step 3: Analyze latestPixels even when screen is static (no new frame since last tick)
        val imgW = latestWidth
        val imgH = latestHeight
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

        val raw = BoardVisionAnalyzer.analyzeFrame(
            pixels = latestPixels,
            width = imgW,
            height = imgH,
            lockedRect = lockedCapRect,
            activeArrow = activeArrow,
            activeArrowPlayingAsWhite = activeArrowPlayingAsWhite
        )
        val scaledDetection = if (raw.detection.found) {
            raw.detection.copy(rect = raw.detection.rect.scaled(scaleToScreenX, scaleToScreenY))
        } else {
            raw.detection
        }
        val analysis = FrameAnalysis(
            detection = scaledDetection,
            grid = raw.grid,
            framesCaptured = framesCaptured,
            lastBestScore = raw.lastBestScore,
            lastDetectionNote = raw.lastDetectionNote,
            frameMinLum = latestFrameMinLum,
            frameMaxLum = latestFrameMaxLum
        )

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

    private fun formatError(stage: String, t: Throwable): String {
        val msg = t.message?.trim().orEmpty()
        return if (msg.isNotEmpty()) {
            "$stage: ${t.javaClass.simpleName}: $msg"
        } else {
            "$stage: ${t.javaClass.simpleName}"
        }
    }

    companion object {
        private const val TARGET_CAPTURE_WIDTH = 540
        private const val POLL_INTERVAL_MS = 220L
        private const val INITIAL_DELAY_MS = 350L
    }
}
