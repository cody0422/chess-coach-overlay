package com.chesscoach.overlay.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chesscoach.overlay.MainActivity
import com.chesscoach.overlay.R
import com.chesscoach.overlay.data.BoardRect
import com.chesscoach.overlay.data.OverlayPrefs
import com.chesscoach.overlay.engine.AnalysisResult
import com.chesscoach.overlay.engine.ChessBoard
import com.chesscoach.overlay.engine.Move
import com.chesscoach.overlay.engine.MoveFeedback
import com.chesscoach.overlay.engine.Piece
import com.chesscoach.overlay.engine.StockfishUciEngine
import com.chesscoach.overlay.engine.TurnArrowPolicy
import com.chesscoach.overlay.vision.AccessibilityBoardReader
import com.chesscoach.overlay.vision.AutoReadStatusFormatter
import com.chesscoach.overlay.vision.BoardTrackingEvent
import com.chesscoach.overlay.vision.BoardTrackingSession
import com.chesscoach.overlay.vision.ChessAccessibilityService
import com.chesscoach.overlay.vision.FrameAnalysis
import com.chesscoach.overlay.vision.PixelRect
import com.chesscoach.overlay.vision.ScreenBoardReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var themedContext: Context
    private lateinit var prefs: OverlayPrefs
    private lateinit var engine: StockfishUciEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var analysisJob: Job? = null
    private var analysisGeneration: Long = 0L

    private val board = ChessBoard()
    private var lastAnalysis: AnalysisResult? = null
    private var lastFeedback: MoveFeedback? = null

    private var activeTargetPackage: String = ""
    private var currentMode: OverlayMode = OverlayMode.PASS_THROUGH
    private var overlayArrowsActive: Boolean = true

    // Screen-capture auto-read state (No-Red-Box Accessibility preferred, MediaProjection Cast fallback)
    private var screenBoardReader: ScreenBoardReader? = null
    private var accessibilityBoardReader: AccessibilityBoardReader? = null
    private val trackingSession = BoardTrackingSession()
    private var hasMediaProjectionGrant: Boolean = false
    private var usingAccessibilityCapture: Boolean = false
    private var autoBoardDetectedVisible: Boolean = false
    private var manualAlignLocked: Boolean = false
    private var lastFramesCaptured: Long = 0L
    private var lastDetectionBestScore: Float = 0f
    private var lastDetectionNote: String = ""
    private var lastFrameMinLum: Int = -1
    private var lastFrameMaxLum: Int = -1
    private var lastCaptureError: String? = null

    private var boardView: BoardOverlayView? = null
    private var boardParams: WindowManager.LayoutParams? = null

    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var ivPillPlay: ImageView? = null
    private var viewPillStatusDot: View? = null

    private var settingsController: SettingsModalController? = null
    private var settingsAttached = false

    private val accessibilityConnectionListener: (Boolean) -> Unit = { connected ->
        mainHandler.post {
            if (!isRunning) return@post
            if (connected && prefs.autoReadScreen && !prefs.useFastCastMode && !usingAccessibilityCapture) {
                // Seamless upgrade to Silent No-Red-Box Accessibility Mode (removes any MediaProjection red box!)
                stopAllCapture()
                startAsForegroundService(includeMediaProjection = false)
                startAccessibilityCapture()
            } else {
                updateBoardVisibility()
                updatePillVisualState()
                settingsController?.syncFromPrefs()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        themedContext = ContextThemeWrapper(this, R.style.Theme_ChessCoachOverlay)
        prefs = OverlayPrefs(this)
        engine = StockfishUciEngine(this)
        activeTargetPackage = prefs.selectedAppPackage

        // Start foreground immediately with specialUse (ZERO red box!).
        // Only upgrades to mediaProjection if Fast Cast Mode is explicitly started.
        startAsForegroundService(includeMediaProjection = false)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        currentMode = if (prefs.tapInputMode) OverlayMode.TAP_MOVE else OverlayMode.PASS_THROUGH
        ChessAccessibilityService.addConnectionListener(accessibilityConnectionListener)

        setupBoardOverlay()
        setupPillBar()
        applyPrefsToViews()

        // Pre-initialize Stockfish 19 binary in background, then run initial analysis (if our turn)
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                engine.ensureInitialized()
            }
            triggerAnalysis()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OVERLAY, ACTION_UPDATE_TARGET_APP -> {
                val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: prefs.selectedAppPackage
                if (pkg != activeTargetPackage) {
                    activeTargetPackage = pkg
                    manualAlignLocked = false
                    screenBoardReader?.lockedScreenRect = null
                    accessibilityBoardReader?.lockedScreenRect = null
                    reloadBoardRectForActiveApp()
                }

                val shouldLaunchTargetApp = intent.getBooleanExtra(EXTRA_LAUNCH_TARGET_APP, false)
                val useAccessibilityRequested = intent.getBooleanExtra(EXTRA_USE_ACCESSIBILITY_MODE, false)
                val projCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                val projData: Intent? = extractProjectionData(intent)
                val incomingCaptureError = intent.getStringExtra(EXTRA_CAPTURE_ERROR)

                if (useAccessibilityRequested && prefs.autoReadScreen && ChessAccessibilityService.isConnected()) {
                    // Silent No-Red-Box Accessibility Mode — pure specialUse FGS, ZERO status-bar cast icon!
                    stopAllCapture()
                    lastCaptureError = null
                    startAsForegroundService(includeMediaProjection = false)
                    startAccessibilityCapture()
                    if (shouldLaunchTargetApp) {
                        val pkgToLaunch = activeTargetPackage
                        mainHandler.postDelayed({ launchTargetChessApp(pkgToLaunch) }, 150L)
                    }
                } else if (projCode == Activity.RESULT_OK && projData != null && prefs.autoReadScreen) {
                    // Fast Cast Mode (MediaProjection fallback):
                    // 1. Upgrade foreground service type to include MEDIA_PROJECTION FIRST
                    lastCaptureError = null
                    val fgsOk = startAsForegroundService(includeMediaProjection = true)
                    if (fgsOk) {
                        // 2. THEN call getMediaProjection -> registerCallback -> createVirtualDisplay synchronously
                        startScreenCapture(projCode, projData)
                    } else {
                        startAsForegroundService(includeMediaProjection = false)
                        stopAllCapture()
                        Toast.makeText(
                            this,
                            "Auto-Read error: ${(lastCaptureError ?: "FGS failed").take(70)}",
                            Toast.LENGTH_LONG
                        ).show()
                        updateBoardVisibility()
                        updatePillVisualState()
                        settingsController?.syncFromPrefs()
                    }
                    // 3. ONLY AFTER FGS + VirtualDisplay are settled, launch target chess app
                    if (shouldLaunchTargetApp) {
                        val pkgToLaunch = activeTargetPackage
                        mainHandler.postDelayed({ launchTargetChessApp(pkgToLaunch) }, 250L)
                    }
                } else if (intent.action == ACTION_START_OVERLAY) {
                    if (prefs.autoReadScreen && !prefs.useFastCastMode && ChessAccessibilityService.isConnected()) {
                        stopAllCapture()
                        lastCaptureError = null
                        startAsForegroundService(includeMediaProjection = false)
                        startAccessibilityCapture()
                    } else if (!hasActiveAutoReadCapture()) {
                        // Started without screen capture (user disabled Auto-Read or declined prompt)
                        stopAllCapture()
                        lastCaptureError = incomingCaptureError
                        if (!prefs.autoReadScreen) {
                            setOverlayMode(if (prefs.tapInputMode) OverlayMode.TAP_MOVE else OverlayMode.PASS_THROUGH)
                        } else {
                            // Declined prompt -> default cleanly into manual Tap mode
                            prefs.tapInputMode = true
                            setOverlayMode(OverlayMode.TAP_MOVE)
                        }
                        updateBoardVisibility()
                        updatePillVisualState()
                        settingsController?.syncFromPrefs()
                    }
                    if (shouldLaunchTargetApp) {
                        val pkgToLaunch = activeTargetPackage
                        mainHandler.postDelayed({ launchTargetChessApp(pkgToLaunch) }, 150L)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun launchTargetChessApp(targetPkg: String) {
        if (targetPkg.isBlank()) return
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(targetPkg)
        } catch (_: Throwable) {
            null
        } ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun extractProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
    }

    private fun startAsForegroundService(includeMediaProjection: Boolean): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifText = when {
            includeMediaProjection -> "Fast Cast auto-read • Stockfish 19 • ∞ moves"
            usingAccessibilityCapture -> "Silent No-Red-Box mode • Stockfish 19 • ∞ moves"
            else -> getString(R.string.overlay_running_notif_text)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_running_notif_title))
            .setContentText(notifText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = if (includeMediaProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && includeMediaProjection) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            if (includeMediaProjection) {
                val msg = t.message?.trim().orEmpty()
                lastCaptureError = if (msg.isNotEmpty()) {
                    "FGS: ${t.javaClass.simpleName}: $msg"
                } else {
                    "FGS: ${t.javaClass.simpleName}"
                }
            }
            false
        }
    }

    private fun startAccessibilityCapture() {
        stopAllCapture()
        trackingSession.resetTracking()
        autoBoardDetectedVisible = false
        lastFramesCaptured = 0L
        lastDetectionBestScore = 0f
        lastDetectionNote = ""
        lastFrameMinLum = -1
        lastFrameMaxLum = -1
        lastCaptureError = null

        var readerRef: AccessibilityBoardReader? = null
        val reader = AccessibilityBoardReader(
            context = this,
            onFrameAnalyzed = { analysis -> handleScreenFrame(analysis) },
            onDisconnected = {
                usingAccessibilityCapture = false
                hasActiveScreenCapture = hasMediaProjectionGrant && (screenBoardReader?.isActive == true)
                autoBoardDetectedVisible = false
                lastCaptureError = readerRef?.lastError ?: "No-Red-Box service disconnected"
                updateBoardVisibility()
                updatePillVisualState()
                settingsController?.syncFromPrefs()
            },
            onCaptureError = { msg ->
                lastCaptureError = msg
                settingsController?.syncFromPrefs()
            }
        )
        readerRef = reader

        if (manualAlignLocked) {
            boardParams?.let { bp ->
                reader.lockedScreenRect = PixelRect(bp.x, bp.y, bp.width, bp.height)
            }
        }

        val ok = reader.start()
        if (ok) {
            accessibilityBoardReader = reader
            usingAccessibilityCapture = true
            hasActiveScreenCapture = true
            lastCaptureError = null
            prefs.tapInputMode = false
            setOverlayMode(OverlayMode.PASS_THROUGH)
            updateBoardVisibility()
            updatePillVisualState()
            settingsController?.syncFromPrefs()
        } else {
            usingAccessibilityCapture = false
            hasActiveScreenCapture = false
            lastCaptureError = reader.lastError ?: "No-Red-Box service unavailable"
            updateBoardVisibility()
            updatePillVisualState()
            settingsController?.syncFromPrefs()
        }
    }

    private fun startScreenCapture(resultCode: Int, projectionData: Intent) {
        stopAllCapture()
        trackingSession.resetTracking()
        autoBoardDetectedVisible = false
        lastFramesCaptured = 0L
        lastDetectionBestScore = 0f
        lastDetectionNote = ""
        lastFrameMinLum = -1
        lastFrameMaxLum = -1
        lastCaptureError = null

        var readerRef: ScreenBoardReader? = null
        val reader = ScreenBoardReader(
            context = this,
            onFrameAnalyzed = { analysis -> handleScreenFrame(analysis) },
            onProjectionStopped = {
                hasMediaProjectionGrant = false
                hasActiveScreenCapture = usingAccessibilityCapture && (accessibilityBoardReader?.isActive == true)
                autoBoardDetectedVisible = false
                lastCaptureError = readerRef?.lastError ?: "Screen capture stopped by Android"
                updateBoardVisibility()
                updatePillVisualState()
                settingsController?.syncFromPrefs()
            }
        )
        readerRef = reader

        if (manualAlignLocked) {
            boardParams?.let { bp ->
                reader.lockedScreenRect = PixelRect(bp.x, bp.y, bp.width, bp.height)
            }
        }

        val ok = reader.start(resultCode, projectionData)
        if (ok) {
            screenBoardReader = reader
            hasMediaProjectionGrant = true
            usingAccessibilityCapture = false
            hasActiveScreenCapture = true
            lastCaptureError = null
            prefs.tapInputMode = false
            setOverlayMode(OverlayMode.PASS_THROUGH)
            updateBoardVisibility()
            updatePillVisualState()
            settingsController?.syncFromPrefs()
        } else {
            hasMediaProjectionGrant = false
            hasActiveScreenCapture = false
            lastCaptureError = reader.lastError ?: "Screen capture failed to start"
            Toast.makeText(
                this,
                "Auto-Read error: ${lastCaptureError!!.take(70)}",
                Toast.LENGTH_LONG
            ).show()
            updateBoardVisibility()
            updatePillVisualState()
            settingsController?.syncFromPrefs()
        }
    }

    private fun stopAllCapture() {
        accessibilityBoardReader?.stop()
        accessibilityBoardReader = null
        usingAccessibilityCapture = false

        screenBoardReader?.stop()
        screenBoardReader = null
        hasMediaProjectionGrant = false
        hasActiveScreenCapture = false

        autoBoardDetectedVisible = false
        lastFramesCaptured = 0L
        lastDetectionBestScore = 0f
        lastDetectionNote = ""
        lastFrameMinLum = -1
        lastFrameMaxLum = -1
        trackingSession.resetTracking()
    }

    private fun hasActiveAutoReadCapture(): Boolean {
        return (usingAccessibilityCapture && accessibilityBoardReader?.isActive == true) ||
            (hasMediaProjectionGrant && screenBoardReader?.isActive == true)
    }

    private fun isAutoReadLive(): Boolean {
        return prefs.autoReadScreen && hasActiveAutoReadCapture()
    }

    private fun handleScreenFrame(analysis: FrameAnalysis) {
        if (!isAutoReadLive()) return

        if (lastCaptureError != null) {
            lastCaptureError = null
        }
        lastFramesCaptured = analysis.framesCaptured

        // Preserve pre-Settings detection telemetry & luma while the dark Settings modal covers the board
        if (settingsAttached) {
            settingsController?.updateScreenReadStatus(describeScreenReadStatus())
            return
        }

        lastDetectionBestScore = analysis.lastBestScore
        lastDetectionNote = analysis.lastDetectionNote
        lastFrameMinLum = analysis.frameMinLum
        lastFrameMaxLum = analysis.frameMaxLum

        // Don't fight the user while dragging/resizing in Align mode
        if (currentMode == OverlayMode.ALIGN) return

        val events = trackingSession.onFrame(
            analysis = analysis,
            currentBoard = board,
            currentPlayingAsWhite = prefs.playingAsWhite,
            singleFrameMoveConfirm = usingAccessibilityCapture
        )

        for (ev in events) {
            when (ev) {
                is BoardTrackingEvent.BoardHidden -> {
                    if (!manualAlignLocked) {
                        autoBoardDetectedVisible = false
                    }
                    updateBoardVisibility()
                    updatePillVisualState()
                }
                is BoardTrackingEvent.BoardVisible -> {
                    val justBecameVisible = !autoBoardDetectedVisible
                    autoBoardDetectedVisible = true
                    if (!manualAlignLocked) {
                        snapBoardOverlayToRect(ev.rect)
                    }
                    updateBoardVisibility()
                    updatePillVisualState()
                    if (lastAnalysis == null || justBecameVisible) {
                        triggerAnalysis()
                    }
                }
                is BoardTrackingEvent.OrientationDetected -> {
                    autoBoardDetectedVisible = true
                    if (!manualAlignLocked) {
                        snapBoardOverlayToRect(ev.rect)
                    }
                    prefs.playingAsWhite = ev.playingAsWhite
                    applyPrefsToViews()
                    updateBoardVisibility()
                    updatePillVisualState()
                    // Re-evaluate turn ownership (immediately hides opponent arrows or starts Stockfish for player's turn)
                    triggerAnalysis()
                }
                is BoardTrackingEvent.NewGameDetected -> {
                    autoBoardDetectedVisible = true
                    if (!manualAlignLocked) {
                        snapBoardOverlayToRect(ev.rect)
                    }
                    prefs.playingAsWhite = ev.playingAsWhite
                    board.reset()
                    lastAnalysis = null
                    lastFeedback = null
                    applyPrefsToViews()
                    updateBoardVisibility()
                    updatePillVisualState()
                    triggerAnalysis()
                }
                is BoardTrackingEvent.MovesDetected -> {
                    autoBoardDetectedVisible = true
                    if (!manualAlignLocked) {
                        snapBoardOverlayToRect(ev.rect)
                    }
                    applyAutoDetectedMoves(ev.moves)
                    updateBoardVisibility()
                    updatePillVisualState()
                }
            }
        }
    }

    private fun snapBoardOverlayToRect(rect: PixelRect) {
        val bv = boardView ?: return
        val bp = boardParams ?: return
        if (rect.width < 120 || rect.height < 120) return

        val changed = abs(bp.x - rect.left) > 3 ||
            abs(bp.y - rect.top) > 3 ||
            abs(bp.width - rect.width) > 3 ||
            abs(bp.height - rect.height) > 3

        if (changed) {
            bp.x = rect.left
            bp.y = rect.top
            bp.width = rect.width
            bp.height = rect.height
            try {
                windowManager.updateViewLayout(bv, bp)
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyAutoDetectedMoves(moves: List<Move>) {
        if (moves.isEmpty()) return
        val playerColor = TurnArrowPolicy.myColor(prefs.playingAsWhite)
        var gradeBefore: ChessBoard? = null
        var gradedMove: Move? = null
        val cachedBestBefore = lastAnalysis?.topMoves?.firstOrNull()

        for (m in moves) {
            val before = board.copy()
            val mover = before.sideToMove
            if (!board.pushMove(m)) break
            if (prefs.analyzeMyMoves && mover == playerColor) {
                gradeBefore = before
                gradedMove = m
            }
        }

        lastAnalysis = null
        if (gradedMove == null) {
            lastFeedback = null
        }

        val myTurnNow = TurnArrowPolicy.shouldShowArrow(prefs.myTurnOnly, board.sideToMove, prefs.playingAsWhite)
        refreshBoardView(analyzing = myTurnNow)
        triggerAnalysis(
            gradeBefore = gradeBefore,
            gradedMove = gradedMove,
            cachedBestBefore = if (moves.size == 1) cachedBestBefore else null
        )
    }

    private fun syncActiveArrowMask() {
        val arrowVisible = overlayArrowsActive &&
            prefs.showArrows &&
            currentMode != OverlayMode.ALIGN &&
            !settingsAttached &&
            (autoBoardDetectedVisible || manualAlignLocked || !isAutoReadLive())
        val displayAnalysis = TurnArrowPolicy.filterAnalysisForDisplay(
            analysis = lastAnalysis,
            myTurnOnly = prefs.myTurnOnly,
            sideToMove = board.sideToMove,
            playingAsWhite = prefs.playingAsWhite
        )
        val activeMove = if (arrowVisible) displayAnalysis?.topMoves?.firstOrNull()?.move else null

        screenBoardReader?.let { reader ->
            reader.activeArrow = activeMove
            reader.activeArrowPlayingAsWhite = prefs.playingAsWhite
        }
        accessibilityBoardReader?.let { reader ->
            reader.activeArrow = activeMove
            reader.activeArrowPlayingAsWhite = prefs.playingAsWhite
        }
    }

    private fun updateBoardVisibility() {
        val bv = boardView ?: return
        val shouldShow = when {
            currentMode == OverlayMode.ALIGN -> true
            !overlayArrowsActive -> false
            isAutoReadLive() -> autoBoardDetectedVisible || manualAlignLocked
            else -> true
        }
        val targetVis = if (shouldShow) View.VISIBLE else View.INVISIBLE
        if (bv.visibility != targetVis) {
            bv.visibility = targetVis
        }
        syncActiveArrowMask()
    }

    private fun defaultBoardRect(): BoardRect {
        val dm = resources.displayMetrics
        val size = min(dm.widthPixels, dm.heightPixels).coerceAtLeast(320)
        val x = (dm.widthPixels - size) / 2
        val y = ((dm.heightPixels - size) * 0.48f).toInt().coerceAtLeast(80)
        return BoardRect(x, y, size, size)
    }

    private fun setupBoardOverlay() {
        val def = defaultBoardRect()
        val saved = prefs.getBoardRect(activeTargetPackage, def.x, def.y, def.width)

        val view = BoardOverlayView(themedContext).apply {
            mode = currentMode
            onMoveEntered = { move -> handleUserMove(move) }
            onRectDelta = { dx, dy, dw, dh -> adjustBoardRect(dx, dy, dw, dh) }
            onLockAlign = { lockAlignment() }
        }

        val params = WindowManager.LayoutParams(
            saved.width,
            saved.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsForMode(currentMode),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved.x
            y = saved.y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        boardView = view
        boardParams = params
        try {
            windowManager.addView(view, params)
        } catch (_: Throwable) {
            stopSelf()
        }
        updateBoardVisibility()
    }

    private fun reloadBoardRectForActiveApp() {
        val bv = boardView ?: return
        val bp = boardParams ?: return
        val def = defaultBoardRect()
        val saved = prefs.getBoardRect(activeTargetPackage, def.x, def.y, def.width)
        bp.x = saved.x
        bp.y = saved.y
        bp.width = saved.width
        bp.height = saved.height
        try {
            windowManager.updateViewLayout(bv, bp)
        } catch (_: Throwable) {
        }
    }

    private fun flagsForMode(mode: OverlayMode): Int {
        return when (mode) {
            OverlayMode.PASS_THROUGH ->
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            OverlayMode.ALIGN, OverlayMode.TAP_MOVE ->
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
    }

    private fun setOverlayMode(newMode: OverlayMode) {
        currentMode = newMode
        val bv = boardView
        val bp = boardParams
        if (bv != null && bp != null) {
            bv.mode = newMode
            bp.flags = flagsForMode(newMode)
            try {
                windowManager.updateViewLayout(bv, bp)
            } catch (_: Throwable) {
            }
        }
        updateBoardVisibility()
        updatePillVisualState()
    }

    private fun adjustBoardRect(dx: Int, dy: Int, dw: Int, dh: Int) {
        val bv = boardView ?: return
        val bp = boardParams ?: return
        val dm = resources.displayMetrics
        val minSize = (160f * dm.density).toInt()
        val maxW = dm.widthPixels
        val maxH = dm.heightPixels

        val newW = (bp.width + dw).coerceIn(minSize, maxW)
        val newH = (bp.height + dh).coerceIn(minSize, maxH)
        val appliedDw = newW - bp.width
        val appliedDh = newH - bp.height

        val nextX = if (dw != 0 && dx != 0) bp.x - appliedDw else bp.x + dx
        val nextY = if (dh != 0 && dy != 0) bp.y - appliedDh else bp.y + dy

        bp.width = newW
        bp.height = newH
        bp.x = nextX.coerceIn(-newW / 2, maxW - minSize / 2)
        bp.y = nextY.coerceIn(0, maxH - minSize / 2)

        try {
            windowManager.updateViewLayout(bv, bp)
        } catch (_: Throwable) {
        }
    }

    private fun lockAlignment() {
        boardParams?.let { bp ->
            prefs.saveBoardRect(
                activeTargetPackage,
                BoardRect(bp.x, bp.y, bp.width, bp.height)
            )
            manualAlignLocked = true
            autoBoardDetectedVisible = true
            val lockedRect = PixelRect(bp.x, bp.y, bp.width, bp.height)
            screenBoardReader?.lockedScreenRect = lockedRect
            accessibilityBoardReader?.lockedScreenRect = lockedRect
        }
        val nextMode = if (prefs.tapInputMode) OverlayMode.TAP_MOVE else OverlayMode.PASS_THROUGH
        setOverlayMode(nextMode)
        if (lastAnalysis == null) {
            triggerAnalysis()
        } else {
            refreshBoardView(analyzing = false)
        }
    }

    private fun setupPillBar() {
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_pill_bar, null)
        val dm = resources.displayMetrics
        val defaultX = (dm.widthPixels * 0.22f).toInt().coerceAtLeast(16)
        val defaultY = (40f * dm.density).toInt()

        val savedX = if (prefs.pillX >= 0) prefs.pillX else defaultX
        val savedY = if (prefs.pillY >= 0) prefs.pillY else defaultY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        pillView = view
        pillParams = params

        val playContainer = view.findViewById<View>(R.id.btnPillPlayContainer)
        ivPillPlay = view.findViewById(R.id.ivPillPlay)
        viewPillStatusDot = view.findViewById(R.id.viewPillStatusDot)
        val btnSettings = view.findViewById<ImageButton>(R.id.btnPillSettings)
        val btnClose = view.findViewById<ImageButton>(R.id.btnPillClose)

        view.setOnTouchListener(createDragOrClickListener(onClick = null))
        playContainer.setOnTouchListener(createDragOrClickListener {
            overlayArrowsActive = !overlayArrowsActive
            updateBoardVisibility()
            updatePillVisualState()
            Toast.makeText(
                this,
                if (overlayArrowsActive) "Arrows ON" else "Arrows PAUSED",
                Toast.LENGTH_SHORT
            ).show()
            if (overlayArrowsActive) {
                triggerAnalysis()
            }
        })
        btnSettings.setOnTouchListener(createDragOrClickListener {
            toggleSettingsModal()
        })
        btnClose.setOnTouchListener(createDragOrClickListener {
            stopSelf()
        })

        try {
            windowManager.addView(view, params)
        } catch (_: Throwable) {
            stopSelf()
        }
        updatePillVisualState()
    }

    private fun createDragOrClickListener(onClick: (() -> Unit)?): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val pp = pillParams ?: return false
                val pv = pillView ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = pp.x
                        startY = pp.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        v.isPressed = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) {
                            moved = true
                            v.isPressed = false
                        }
                        if (moved) {
                            pp.x = (startX + dx).coerceAtLeast(0)
                            pp.y = (startY + dy).coerceAtLeast(0)
                            try {
                                windowManager.updateViewLayout(pv, pp)
                            } catch (_: Throwable) {
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        if (moved) {
                            prefs.pillX = pp.x
                            prefs.pillY = pp.y
                        } else {
                            onClick?.invoke()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun updatePillVisualState() {
        val playIcon = ivPillPlay
        if (playIcon != null) {
            if (overlayArrowsActive) {
                playIcon.setImageResource(R.drawable.ic_pause)
                playIcon.setColorFilter(Color.parseColor("#F8FAFC"))
            } else {
                playIcon.setImageResource(R.drawable.ic_play)
                playIcon.setColorFilter(Color.parseColor("#94A3B8"))
            }
        }

        val dot = viewPillStatusDot
        if (dot != null) {
            val dotColor = when {
                !overlayArrowsActive -> Color.parseColor("#64748B")
                isAutoReadLive() && (autoBoardDetectedVisible || manualAlignLocked) -> Color.parseColor("#22C55E")
                isAutoReadLive() -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#38BDF8")
            }
            val bg = dot.background
            if (bg is GradientDrawable) {
                bg.setColor(dotColor)
            } else {
                dot.setBackgroundColor(dotColor)
            }
        }
    }

    private fun describeScreenReadStatus(): String {
        return AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = prefs.autoReadScreen,
            autoReadLive = isAutoReadLive(),
            hasMediaProjectionGrant = hasActiveAutoReadCapture(),
            boardVisibleOrLocked = autoBoardDetectedVisible || manualAlignLocked,
            playingAsWhite = prefs.playingAsWhite,
            fullMoveNumber = board.fullMoveNumber,
            framesCaptured = lastFramesCaptured,
            bestScore = lastDetectionBestScore,
            lastCaptureError = lastCaptureError,
            tapInputMode = prefs.tapInputMode,
            frameMaxLum = lastFrameMaxLum,
            usingAccessibilityMode = usingAccessibilityCapture
        )
    }

    private fun launchGrantScreenPermissionActivity(forceFastCast: Boolean = false) {
        overlayArrowsActive = true
        updatePillVisualState()
        hideSettingsModal()
        if (!forceFastCast && !prefs.useFastCastMode && ChessAccessibilityService.isConnected()) {
            startAsForegroundService(includeMediaProjection = false)
            startAccessibilityCapture()
            Toast.makeText(this, "No-Red-Box mode active!", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            "Opening Screen Capture permission…",
            Toast.LENGTH_SHORT
        ).show()
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_AUTO_READ, true)
            putExtra(MainActivity.EXTRA_FORCE_FAST_CAST, forceFastCast)
        }
        try {
            startActivity(i)
        } catch (_: Throwable) {
        }
    }

    private fun openAccessibilitySettingsFromOverlay() {
        hideSettingsModal()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
            Toast.makeText(
                this,
                "Switch ON ChessCoach Board Reader (No Red Box)",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
        }
    }

    private fun openAppInfoFromOverlay() {
        hideSettingsModal()
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
            Toast.makeText(
                this,
                "Tap 3 dots (top right) → Allow restricted settings",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
        }
    }

    private fun toggleSettingsModal() {
        if (settingsAttached) {
            hideSettingsModal()
        } else {
            showSettingsModal()
        }
    }

    private fun showSettingsModal() {
        if (settingsAttached) return
        val controller = settingsController ?: SettingsModalController(
            themedContext = themedContext,
            prefs = prefs,
            engineNameProvider = { engine.activeEngineName },
            screenReadStatusProvider = { describeScreenReadStatus() },
            needsScreenPermissionProvider = { prefs.autoReadScreen && !hasActiveAutoReadCapture() },
            onSettingsChanged = { reanalyze ->
                applyPrefsToViews()
                if (reanalyze) {
                    triggerAnalysis()
                } else {
                    refreshBoardView(analyzing = false)
                }
            },
            onAutoReadToggled = { enabled ->
                if (enabled && !hasActiveAutoReadCapture()) {
                    launchGrantScreenPermissionActivity(forceFastCast = prefs.useFastCastMode)
                } else if (!enabled) {
                    lastCaptureError = null
                    stopAllCapture()
                    startAsForegroundService(includeMediaProjection = false)
                    updateBoardVisibility()
                    updatePillVisualState()
                }
            },
            onFastCastModeToggled = { useFastCast ->
                if (prefs.autoReadScreen) {
                    if (useFastCast) {
                        if (!hasMediaProjectionGrant) {
                            stopAllCapture()
                            launchGrantScreenPermissionActivity(forceFastCast = true)
                        }
                    } else if (ChessAccessibilityService.isConnected()) {
                        stopAllCapture()
                        startAsForegroundService(includeMediaProjection = false)
                        startAccessibilityCapture()
                        Toast.makeText(this, "Switched to No-Red-Box mode", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onEnableNoRedBoxClicked = { openAccessibilitySettingsFromOverlay() },
            onOpenAppInfoClicked = { openAppInfoFromOverlay() },
            onTapInputToggled = { tapEnabled ->
                setOverlayMode(if (tapEnabled) OverlayMode.TAP_MOVE else OverlayMode.PASS_THROUGH)
            },
            onAlignClicked = {
                overlayArrowsActive = true
                setOverlayMode(OverlayMode.ALIGN)
            },
            onFlipClicked = {
                prefs.playingAsWhite = !prefs.playingAsWhite
                applyPrefsToViews()
                triggerAnalysis()
            },
            onUndoClicked = {
                if (board.undoLastMove() != null) {
                    lastFeedback = null
                    triggerAnalysis()
                }
            },
            onNewGameClicked = { resetGame() },
            onRedetectClicked = {
                overlayArrowsActive = true
                if (prefs.autoReadScreen && !hasActiveAutoReadCapture()) {
                    launchGrantScreenPermissionActivity(forceFastCast = prefs.useFastCastMode)
                } else {
                    manualAlignLocked = false
                    screenBoardReader?.lockedScreenRect = null
                    accessibilityBoardReader?.lockedScreenRect = null
                    trackingSession.resetTracking()
                    autoBoardDetectedVisible = false
                    hideSettingsModal()
                    updateBoardVisibility()
                    updatePillVisualState()
                    Toast.makeText(this, "Scanning for board…", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { hideSettingsModal() }
        ).also { settingsController = it }

        controller.syncFromPrefs()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(controller.rootView, params)
            settingsAttached = true
        } catch (_: Throwable) {
        }
    }

    private fun hideSettingsModal() {
        if (!settingsAttached) return
        settingsController?.rootView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) {
            }
        }
        settingsAttached = false
    }

    private fun applyPrefsToViews() {
        boardView?.apply {
            flipped = !prefs.playingAsWhite
            showArrows = prefs.showArrows
            showMultiArrows = prefs.showMultiArrows
            showEvalBar = prefs.showEvalBar
            showBoardWarnings = prefs.boardWarnings
            showPinnedPieces = prefs.markPinnedPieces
            showLineRisks = prefs.markLineRisks
            showPieceMarkers = prefs.showPieceMarkers
            showMoveFeedback = prefs.analyzeMyMoves
        }
        updatePillVisualState()
    }

    private fun handleUserMove(move: Move) {
        val boardBefore = board.copy()
        val mover = boardBefore.sideToMove
        val cachedBestBefore = lastAnalysis?.topMoves?.firstOrNull()

        if (!board.pushMove(move)) return

        val playerColor = TurnArrowPolicy.myColor(prefs.playingAsWhite)
        val shouldGrade = prefs.analyzeMyMoves && mover == playerColor

        lastAnalysis = null
        if (!shouldGrade) {
            lastFeedback = null
        }

        val myTurnNow = TurnArrowPolicy.shouldShowArrow(prefs.myTurnOnly, board.sideToMove, prefs.playingAsWhite)
        refreshBoardView(analyzing = myTurnNow)
        triggerAnalysis(
            gradeBefore = if (shouldGrade) boardBefore else null,
            gradedMove = if (shouldGrade) move else null,
            cachedBestBefore = cachedBestBefore
        )
    }

    private fun resetGame() {
        board.reset()
        lastAnalysis = null
        lastFeedback = null
        trackingSession.resetTracking()
        triggerAnalysis()
    }

    private fun triggerAnalysis(
        gradeBefore: ChessBoard? = null,
        gradedMove: Move? = null,
        cachedBestBefore: com.chesscoach.overlay.engine.ScoredMove? = null
    ) {
        val gen = ++analysisGeneration
        engine.cancel()
        analysisJob?.cancel()

        val boardCopy = board.copy()
        val elo = prefs.targetElo
        val acc = prefs.targetAccuracy
        val depth = prefs.searchDepth
        val style = prefs.playStyle
        val playingWhiteAtStart = prefs.playingAsWhite
        val myTurnOnlyAtStart = prefs.myTurnOnly

        // MY TURN ONLY: never draw arrows or run best-move search for the opponent's pieces!
        if (!TurnArrowPolicy.shouldShowArrow(myTurnOnlyAtStart, boardCopy.sideToMove, playingWhiteAtStart)) {
            lastAnalysis = null
            boardView?.setAnalysis(null)
            refreshBoardView(analyzing = false)

            // Still grade the player's move that just finished if requested (without showing any opponent arrow)
            if (gradeBefore != null && gradedMove != null) {
                analysisJob = serviceScope.launch {
                    val fb = withContext(Dispatchers.Default) {
                        engine.evaluateMoveQuality(gradeBefore, gradedMove, cachedBestBefore, depth = 3)
                    }
                    if (!isActive || gen != analysisGeneration) return@launch
                    lastFeedback = fb
                    refreshBoardView(analyzing = false)
                }
            }
            return
        }

        refreshBoardView(analyzing = true)

        analysisJob = serviceScope.launch {
            val (feedback, result) = withContext(Dispatchers.Default) {
                val fb = if (gradeBefore != null && gradedMove != null) {
                    engine.evaluateMoveQuality(gradeBefore, gradedMove, cachedBestBefore, depth = 3)
                } else {
                    null
                }
                val res = engine.analyze(
                    sourceBoard = boardCopy,
                    targetElo = elo,
                    searchDepth = depth,
                    targetAccuracy = acc,
                    playStyle = style,
                    multiPv = 3,
                    onInterimResult = { interim ->
                        mainHandler.post {
                            if (!isRunning || gen != analysisGeneration) return@post
                            if (!TurnArrowPolicy.shouldShowArrow(
                                    prefs.myTurnOnly,
                                    board.sideToMove,
                                    prefs.playingAsWhite
                                )
                            ) {
                                return@post
                            }
                            lastAnalysis = interim
                            refreshBoardView(analyzing = true)
                        }
                    }
                )
                fb to res
            }
            if (!isActive || gen != analysisGeneration) return@launch
            if (feedback != null) {
                lastFeedback = feedback
            }
            if (TurnArrowPolicy.shouldShowArrow(prefs.myTurnOnly, board.sideToMove, prefs.playingAsWhite)) {
                lastAnalysis = result
            } else {
                lastAnalysis = null
            }
            refreshBoardView(analyzing = false)
        }
    }

    private fun refreshBoardView(analyzing: Boolean) {
        val playerColor = TurnArrowPolicy.myColor(prefs.playingAsWhite)
        val pins = board.findPinnedSquares(Piece.WHITE) + board.findPinnedSquares(Piece.BLACK)
        val warnings = board.findBoardWarnings(playerColor)
        val risks = board.findLineRisks(playerColor)
        val status = board.getGameStatus()
        val displayAnalysis = TurnArrowPolicy.filterAnalysisForDisplay(
            analysis = lastAnalysis,
            myTurnOnly = prefs.myTurnOnly,
            sideToMove = board.sideToMove,
            playingAsWhite = prefs.playingAsWhite
        )

        boardView?.updateState(
            board = board,
            analysisResult = displayAnalysis,
            pins = pins,
            boardWarnings = warnings,
            feedback = lastFeedback,
            status = status,
            analyzing = analyzing && (displayAnalysis != null || TurnArrowPolicy.shouldShowArrow(prefs.myTurnOnly, board.sideToMove, prefs.playingAsWhite)),
            risks = risks
        )
        syncActiveArrowMask()
    }

    override fun onDestroy() {
        isRunning = false
        hasActiveScreenCapture = false
        ChessAccessibilityService.removeConnectionListener(accessibilityConnectionListener)
        mainHandler.removeCallbacksAndMessages(null)
        stopAllCapture()
        analysisJob?.cancel()
        engine.close()
        serviceScope.cancel()
        hideSettingsModal()
        pillView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
            }
        }
        boardView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
            }
        }
        pillView = null
        boardView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_OVERLAY = "com.chesscoach.overlay.ACTION_START_OVERLAY"
        const val ACTION_STOP = "com.chesscoach.overlay.ACTION_STOP"
        const val ACTION_UPDATE_TARGET_APP = "com.chesscoach.overlay.ACTION_UPDATE_TARGET_APP"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_LAUNCH_TARGET_APP = "extra_launch_target_app"
        const val EXTRA_USE_ACCESSIBILITY_MODE = "extra_use_accessibility_mode"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_CAPTURE_ERROR = "extra_capture_error"

        private const val CHANNEL_ID = "chess_coach_overlay_channel"
        private const val NOTIFICATION_ID = 4107

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var hasActiveScreenCapture: Boolean = false
            private set
    }
}
