package com.chesscoach.overlay.vision

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.chesscoach.overlay.engine.Move
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Silent AccessibilityService screen reader (Android 11+ / API 30+).
 *
 * Uses [AccessibilityService.takeScreenshot] instead of MediaProjection so Android displays
 * ZERO red cast/recording box in the status bar and ZERO "Start now" consent popup per launch.
 */
class ChessAccessibilityService : AccessibilityService() {

    private var captureExecutor: ExecutorService? = null
    private val captureInFlight = AtomicBoolean(false)

    private fun ensureExecutor(): ExecutorService {
        val current = captureExecutor
        if (current != null && !current.isShutdown) return current
        return Executors.newSingleThreadExecutor { r ->
            Thread(r, "ChessA11yScreenshot").apply { isDaemon = true }
        }.also { captureExecutor = it }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyConnectionChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            onScreenContentChanged?.invoke(pkg)
        }
    }

    override fun onInterrupt() {
        // No audio/haptic feedback to interrupt
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        notifyConnectionChanged(false)
        shutdownExecutor()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        notifyConnectionChanged(false)
        shutdownExecutor()
        super.onDestroy()
    }

    private fun shutdownExecutor() {
        try {
            captureExecutor?.shutdownNow()
        } catch (_: Throwable) {
        }
        captureExecutor = null
        captureInFlight.set(false)
    }

    fun captureFrame(
        onFrame: (pixels: IntArray, width: Int, height: Int, minLum: Int, maxLum: Int) -> Unit,
        onRateLimited: () -> Unit,
        onError: (errorCode: Int, message: String) -> Unit = { _, _ -> }
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onError(-1, "Android 11+ required for No-Red-Box mode")
            return false
        }
        if (!captureInFlight.compareAndSet(false, true)) {
            return false
        }
        return captureFrameApi30(onFrame, onRateLimited, onError)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureFrameApi30(
        onFrame: (pixels: IntArray, width: Int, height: Int, minLum: Int, maxLum: Int) -> Unit,
        onRateLimited: () -> Unit,
        onError: (errorCode: Int, message: String) -> Unit
    ): Boolean {
        val executor = try {
            ensureExecutor()
        } catch (t: Throwable) {
            captureInFlight.set(false)
            onError(-1, "executor: ${t.javaClass.simpleName}")
            return false
        }

        return try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            processScreenshotResult(screenshot, onFrame, onError)
                        } catch (t: Throwable) {
                            onError(-1, "process: ${t.javaClass.simpleName}: ${t.message ?: ""}")
                        } finally {
                            captureInFlight.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        captureInFlight.set(false)
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            onRateLimited()
                        } else {
                            onError(errorCode, formatErrorCode(errorCode))
                        }
                    }
                }
            )
            true
        } catch (t: Throwable) {
            captureInFlight.set(false)
            onError(-1, "takeScreenshot: ${t.javaClass.simpleName}: ${t.message ?: ""}")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processScreenshotResult(
        screenshot: ScreenshotResult,
        onFrame: (pixels: IntArray, width: Int, height: Int, minLum: Int, maxLum: Int) -> Unit,
        onError: (errorCode: Int, message: String) -> Unit
    ) {
        val hwBuffer = screenshot.hardwareBuffer
        val colorSpace = screenshot.colorSpace
        var hwBitmap: Bitmap? = null
        var swBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
            if (hwBitmap == null) {
                onError(-1, "wrapHardwareBuffer returned null")
                return
            }
            swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (swBitmap == null) {
                onError(-1, "Bitmap.copy returned null")
                return
            }
            val srcW = swBitmap.width
            val srcH = swBitmap.height
            if (srcW <= 0 || srcH <= 0) {
                onError(-1, "Empty screenshot dimensions")
                return
            }

            val capW: Int
            val capH: Int
            if (srcW > TARGET_CAPTURE_WIDTH) {
                capW = TARGET_CAPTURE_WIDTH and 1.inv()
                capH = ((srcH.toLong() * capW) / srcW).toInt().coerceAtLeast(320) and 1.inv()
                scaledBitmap = Bitmap.createScaledBitmap(swBitmap, capW, capH, true)
            } else {
                capW = srcW
                capH = srcH
                scaledBitmap = swBitmap
            }

            val pixels = IntArray(capW * capH)
            scaledBitmap.getPixels(pixels, 0, capW, 0, 0, capW, capH)

            // Fast 16x16 luma sample (<0.1ms) to detect blank/black frames
            var minLum = 255
            var maxLum = 0
            val gridN = 16
            for (gy in 0 until gridN) {
                val sy = ((gy + 0.5f) * capH / gridN).toInt().coerceIn(0, capH - 1)
                val rowOff = sy * capW
                for (gx in 0 until gridN) {
                    val sx = ((gx + 0.5f) * capW / gridN).toInt().coerceIn(0, capW - 1)
                    val p = pixels[rowOff + sx]
                    val pr = (p ushr 16) and 0xFF
                    val pg = (p ushr 8) and 0xFF
                    val pb = p and 0xFF
                    val lum = (299 * pr + 587 * pg + 114 * pb) / 1000
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum
                }
            }

            onFrame(pixels, capW, capH, minLum, maxLum)
        } finally {
            if (scaledBitmap != null && scaledBitmap !== swBitmap) {
                try {
                    scaledBitmap.recycle()
                } catch (_: Throwable) {
                }
            }
            try {
                swBitmap?.recycle()
            } catch (_: Throwable) {
            }
            try {
                hwBitmap?.recycle()
            } catch (_: Throwable) {
            }
            try {
                hwBuffer.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun formatErrorCode(code: Int): String {
        return when (code) {
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Accessibility screenshot internal error"
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No accessibility screenshot permission"
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshot interval too short"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display"
            else -> "Accessibility screenshot error ($code)"
        }
    }

    companion object {
        private const val TARGET_CAPTURE_WIDTH = 540

        @Volatile
        var instance: ChessAccessibilityService? = null
            private set

        @Volatile
        var onScreenContentChanged: ((packageName: String) -> Unit)? = null

        private val connectionListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        fun addConnectionListener(listener: (Boolean) -> Unit) {
            connectionListeners.add(listener)
        }

        fun removeConnectionListener(listener: (Boolean) -> Unit) {
            connectionListeners.remove(listener)
        }

        private fun notifyConnectionChanged(connected: Boolean) {
            for (listener in connectionListeners) {
                try {
                    listener(connected)
                } catch (_: Throwable) {
                }
            }
        }

        fun isSupportedOnDevice(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        fun isConnected(): Boolean = isSupportedOnDevice() && instance != null
    }
}

/**
 * Event-driven + heartbeat screen reader backed by [ChessAccessibilityService.takeScreenshot].
 *
 * - Fires an immediate settled read (~140ms after a window/content change) whenever a chess piece
 *   moves on screen, respecting Android's ~1s Accessibility screenshot interval cleanly.
 * - Runs a 1100ms background heartbeat so initial board lock and idle updates never starve.
 * - Produces identical [FrameAnalysis] objects to [ScreenBoardReader], scaled to physical screen coords.
 */
class AccessibilityBoardReader(
    private val context: Context,
    private val onFrameAnalyzed: (FrameAnalysis) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private var framesCaptured: Long = 0L
    private var lastCaptureAttemptMs: Long = 0L
    private var nextAllowedCaptureMs: Long = 0L

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
        get() = running.get() && ChessAccessibilityService.isConnected()

    private val eventCaptureRunnable = Runnable {
        if (!running.get()) return@Runnable
        requestCaptureNow()
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val now = SystemClock.uptimeMillis()
            if (now >= nextAllowedCaptureMs && (now - lastCaptureAttemptMs) >= MIN_CAPTURE_INTERVAL_MS) {
                requestCaptureNow()
            }
            if (running.get()) {
                bgHandler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun start(): Boolean {
        stop()
        lastError = null

        if (!ChessAccessibilityService.isConnected()) {
            lastError = "No-Red-Box Accessibility Service not enabled"
            return false
        }

        val (fullW, fullH) = queryPhysicalScreenSize()
        screenWidth = fullW.coerceAtLeast(320)
        screenHeight = fullH.coerceAtLeast(480)
        framesCaptured = 0L
        lastCaptureAttemptMs = 0L
        nextAllowedCaptureMs = 0L

        val thread = HandlerThread("AccessibilityBoardReader").apply { start() }
        val handler = Handler(thread.looper)
        bgThread = thread
        bgHandler = handler
        running.set(true)

        ChessAccessibilityService.onScreenContentChanged = { pkg ->
            onWindowContentEvent(pkg)
        }
        ChessAccessibilityService.addConnectionListener(connectionListener)

        handler.postDelayed(eventCaptureRunnable, INITIAL_CAPTURE_DELAY_MS)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        return true
    }

    private val connectionListener: (Boolean) -> Unit = { connected ->
        if (!connected && running.get()) {
            lastError = "No-Red-Box service disconnected"
            stop()
            mainHandler.post { onDisconnected() }
        }
    }

    fun stop() {
        running.set(false)
        ChessAccessibilityService.onScreenContentChanged = null
        ChessAccessibilityService.removeConnectionListener(connectionListener)
        bgHandler?.removeCallbacks(eventCaptureRunnable)
        bgHandler?.removeCallbacks(heartbeatRunnable)
        bgThread?.let { t ->
            try {
                t.quitSafely()
            } catch (_: Throwable) {
            }
        }
        bgThread = null
        bgHandler = null
        framesCaptured = 0L
    }

    private fun onWindowContentEvent(pkg: String) {
        if (!running.get()) return
        if (pkg == context.packageName) return
        val handler = bgHandler ?: return
        handler.post {
            if (!running.get()) return@post
            val now = SystemClock.uptimeMillis()
            val earliest = max(lastCaptureAttemptMs + MIN_CAPTURE_INTERVAL_MS, nextAllowedCaptureMs)
            val delay = max(EVENT_SETTLE_DELAY_MS, earliest - now)
            handler.removeCallbacks(eventCaptureRunnable)
            handler.postDelayed(eventCaptureRunnable, delay)
        }
    }

    private fun requestCaptureNow() {
        if (!running.get()) return
        val service = ChessAccessibilityService.instance
        if (service == null) {
            lastError = "No-Red-Box service disconnected"
            stop()
            mainHandler.post { onDisconnected() }
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now < nextAllowedCaptureMs) {
            val wait = (nextAllowedCaptureMs - now).coerceAtLeast(100L)
            bgHandler?.removeCallbacks(eventCaptureRunnable)
            bgHandler?.postDelayed(eventCaptureRunnable, wait)
            return
        }

        lastCaptureAttemptMs = now
        service.captureFrame(
            onFrame = { pixels, capW, capH, minLum, maxLum ->
                if (!running.get()) return@captureFrame
                bgHandler?.post {
                    if (!running.get()) return@post
                    analyzeCapturedFrame(pixels, capW, capH, minLum, maxLum)
                }
            },
            onRateLimited = {
                if (!running.get()) return@captureFrame
                bgHandler?.post {
                    if (!running.get()) return@post
                    // Back off gently without surfacing an error
                    nextAllowedCaptureMs = SystemClock.uptimeMillis() + RATE_LIMIT_BACKOFF_MS
                    bgHandler?.removeCallbacks(eventCaptureRunnable)
                    bgHandler?.postDelayed(eventCaptureRunnable, RATE_LIMIT_BACKOFF_MS)
                }
            },
            onError = { _, msg ->
                if (!running.get()) return@captureFrame
                lastError = msg
            }
        )
    }

    private fun analyzeCapturedFrame(
        pixels: IntArray,
        imgW: Int,
        imgH: Int,
        minLum: Int,
        maxLum: Int
    ) {
        if (imgW <= 0 || imgH <= 0) return
        framesCaptured++
        lastError = null

        val (fullW, fullH) = queryPhysicalScreenSize()
        if (fullW > 0 && fullH > 0) {
            screenWidth = fullW
            screenHeight = fullH
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

        val raw = BoardVisionAnalyzer.analyzeFrame(
            pixels = pixels,
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
            frameMinLum = minLum,
            frameMaxLum = maxLum
        )

        if (running.get()) {
            mainHandler.post {
                if (running.get()) {
                    onFrameAnalyzed(analysis)
                }
            }
        }
    }

    private fun queryPhysicalScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(dm)
            val w = if (dm.widthPixels > 0) dm.widthPixels else context.resources.displayMetrics.widthPixels
            val h = if (dm.heightPixels > 0) dm.heightPixels else context.resources.displayMetrics.heightPixels
            w to h
        }
    }

    companion object {
        private const val INITIAL_CAPTURE_DELAY_MS = 180L
        private const val EVENT_SETTLE_DELAY_MS = 140L
        private const val MIN_CAPTURE_INTERVAL_MS = 1020L
        private const val HEARTBEAT_INTERVAL_MS = 1100L
        private const val RATE_LIMIT_BACKOFF_MS = 650L
    }
}
