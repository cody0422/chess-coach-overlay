package com.chesscoach.overlay.overlay

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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
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
import com.chesscoach.overlay.engine.PlayStyle
import com.chesscoach.overlay.engine.StockfishUciEngine
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var analysisJob: Job? = null

    private val board = ChessBoard()
    private var lastAnalysis: AnalysisResult? = null
    private var lastFeedback: MoveFeedback? = null

    private var activeTargetPackage: String = ""
    private var currentMode: OverlayMode = OverlayMode.TAP_MOVE

    private var boardView: BoardOverlayView? = null
    private var boardParams: WindowManager.LayoutParams? = null

    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var tvPillStats: TextView? = null
    private var btnPillAlign: ImageButton? = null
    private var btnPillTouchMode: ImageButton? = null

    private var settingsController: SettingsModalController? = null
    private var settingsAttached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        themedContext = ContextThemeWrapper(this, R.style.Theme_ChessCoachOverlay)
        prefs = OverlayPrefs(this)
        engine = StockfishUciEngine(this)
        activeTargetPackage = prefs.selectedAppPackage

        startAsForegroundService()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        setupBoardOverlay()
        setupPillBar()
        applyPrefsToViews()

        // Pre-initialize Stockfish binary (if bundled) in background, then run initial analysis
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
            ACTION_UPDATE_TARGET_APP -> {
                val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: prefs.selectedAppPackage
                if (pkg != activeTargetPackage) {
                    activeTargetPackage = pkg
                    reloadBoardRectForActiveApp()
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForegroundService() {
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

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_running_notif_title))
            .setContentText(getString(R.string.overlay_running_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun defaultBoardRect(): BoardRect {
        val dm = resources.displayMetrics
        val size = (min(dm.widthPixels, dm.heightPixels) * 0.92f).toInt().coerceAtLeast(320)
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
        }

        boardView = view
        boardParams = params
        try {
            windowManager.addView(view, params)
        } catch (_: Throwable) {
            stopSelf()
        }
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
        updatePillButtonStates()
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

        // Only shift x/y proportionally when resizing from top/left corners
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
        }
        setOverlayMode(OverlayMode.TAP_MOVE)
    }

    private fun setupPillBar() {
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_pill_bar, null)
        val dm = resources.displayMetrics
        val defaultX = (12f * dm.density).toInt()
        val defaultY = (44f * dm.density).toInt()

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

        tvPillStats = view.findViewById(R.id.tvPillStats)
        btnPillAlign = view.findViewById(R.id.btnPillAlign)
        btnPillTouchMode = view.findViewById(R.id.btnPillTouchMode)

        view.findViewById<ImageButton>(R.id.btnPillAnalyze).setOnClickListener {
            triggerAnalysis()
        }
        btnPillAlign?.setOnClickListener {
            if (currentMode == OverlayMode.ALIGN) {
                lockAlignment()
            } else {
                setOverlayMode(OverlayMode.ALIGN)
            }
        }
        btnPillTouchMode?.setOnClickListener {
            val next = if (currentMode == OverlayMode.PASS_THROUGH) {
                OverlayMode.TAP_MOVE
            } else {
                OverlayMode.PASS_THROUGH
            }
            setOverlayMode(next)
        }
        view.findViewById<ImageButton>(R.id.btnPillFlip).setOnClickListener {
            prefs.playingAsWhite = !prefs.playingAsWhite
            applyPrefsToViews()
            refreshBoardView(analyzing = false)
        }
        view.findViewById<ImageButton>(R.id.btnPillUndo).setOnClickListener {
            if (board.undoLastMove() != null) {
                lastFeedback = null
                triggerAnalysis()
            }
        }
        view.findViewById<ImageButton>(R.id.btnPillReset).setOnClickListener {
            resetGame()
        }
        view.findViewById<ImageButton>(R.id.btnPillSettings).setOnClickListener {
            toggleSettingsModal()
        }
        view.findViewById<ImageButton>(R.id.btnPillClose).setOnClickListener {
            stopSelf()
        }

        // Draggable pill bar via root & stats badge
        val dragListener = createPillDragListener()
        view.setOnTouchListener(dragListener)
        tvPillStats?.setOnTouchListener(dragListener)

        try {
            windowManager.addView(view, params)
        } catch (_: Throwable) {
            stopSelf()
        }
        updatePillButtonStates()
    }

    private fun createPillDragListener(): View.OnTouchListener {
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
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 6 || abs(dy) > 6) moved = true
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
                        if (moved) {
                            prefs.pillX = pp.x
                            prefs.pillY = pp.y
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun updatePillButtonStates() {
        val styleSuffix = if (prefs.playStyle != PlayStyle.DEFAULT) "·${prefs.playStyle.shortTag}" else ""
        tvPillStats?.text = "${prefs.targetElo}·${prefs.targetAccuracy}%$styleSuffix"

        btnPillAlign?.setColorFilter(
            if (currentMode == OverlayMode.ALIGN) Color.parseColor("#19E58F") else Color.parseColor("#F3F6FA")
        )

        if (currentMode == OverlayMode.PASS_THROUGH) {
            btnPillTouchMode?.setImageResource(R.drawable.ic_passthrough)
            btnPillTouchMode?.setColorFilter(Color.parseColor("#9BA4B5"))
        } else {
            btnPillTouchMode?.setImageResource(R.drawable.ic_touch)
            btnPillTouchMode?.setColorFilter(Color.parseColor("#19E58F"))
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
            onSettingsChanged = { reanalyze ->
                applyPrefsToViews()
                updatePillButtonStates()
                if (reanalyze) {
                    triggerAnalysis()
                } else {
                    refreshBoardView(analyzing = false)
                }
            },
            onAlignClicked = { setOverlayMode(OverlayMode.ALIGN) },
            onNewGameClicked = { resetGame() },
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
            showPieceMarkers = prefs.showPieceMarkers
            showMoveFeedback = prefs.analyzeMyMoves
        }
        updatePillButtonStates()
    }

    private fun handleUserMove(move: Move) {
        val boardBefore = board.copy()
        val mover = boardBefore.sideToMove
        val cachedBestBefore = lastAnalysis?.topMoves?.firstOrNull()

        if (!board.pushMove(move)) return

        val playerColor = if (prefs.playingAsWhite) Piece.WHITE else Piece.BLACK
        val shouldGrade = prefs.analyzeMyMoves && mover == playerColor

        lastAnalysis = null
        if (!shouldGrade) {
            lastFeedback = null
        }

        refreshBoardView(analyzing = true)
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
        triggerAnalysis()
    }

    private fun triggerAnalysis(
        gradeBefore: ChessBoard? = null,
        gradedMove: Move? = null,
        cachedBestBefore: com.chesscoach.overlay.engine.ScoredMove? = null
    ) {
        engine.cancel()
        analysisJob?.cancel()

        val boardCopy = board.copy()
        val elo = prefs.targetElo
        val acc = prefs.targetAccuracy
        val depth = prefs.searchDepth
        val style = prefs.playStyle

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
                    multiPv = 3
                )
                fb to res
            }
            if (!isActive) return@launch
            if (feedback != null) {
                lastFeedback = feedback
            }
            lastAnalysis = result
            refreshBoardView(analyzing = false)
        }
    }

    private fun refreshBoardView(analyzing: Boolean) {
        val playerColor = if (prefs.playingAsWhite) Piece.WHITE else Piece.BLACK
        val pins = board.findPinnedSquares(Piece.WHITE) + board.findPinnedSquares(Piece.BLACK)
        val warnings = board.findBoardWarnings(playerColor)
        val status = board.getGameStatus()

        boardView?.updateState(
            board = board,
            analysisResult = lastAnalysis,
            pins = pins,
            boardWarnings = warnings,
            feedback = lastFeedback,
            status = status,
            analyzing = analyzing
        )
    }

    override fun onDestroy() {
        isRunning = false
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
        const val ACTION_STOP = "com.chesscoach.overlay.ACTION_STOP"
        const val ACTION_UPDATE_TARGET_APP = "com.chesscoach.overlay.ACTION_UPDATE_TARGET_APP"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"

        private const val CHANNEL_ID = "chess_coach_overlay_channel"
        private const val NOTIFICATION_ID = 4107

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
