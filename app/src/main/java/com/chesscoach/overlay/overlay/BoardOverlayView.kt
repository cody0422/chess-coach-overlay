package com.chesscoach.overlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.chesscoach.overlay.engine.AnalysisResult
import com.chesscoach.overlay.engine.BoardWarning
import com.chesscoach.overlay.engine.ChessBoard
import com.chesscoach.overlay.engine.GameStatus
import com.chesscoach.overlay.engine.LineRisk
import com.chesscoach.overlay.engine.Move
import com.chesscoach.overlay.engine.MoveFeedback
import com.chesscoach.overlay.engine.Piece
import com.chesscoach.overlay.engine.PinnedPiece
import com.chesscoach.overlay.engine.ScoredMove
import com.chesscoach.overlay.engine.Squares
import com.chesscoach.overlay.engine.WarningType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

enum class OverlayMode {
    ALIGN,
    TAP_MOVE,
    PASS_THROUGH
}

class BoardOverlayView(context: Context) : View(context) {

    var mode: OverlayMode = OverlayMode.PASS_THROUGH
        set(value) {
            field = value
            selectedSquare = -1
            legalTargetsForSelection = emptyList()
            pendingPromotionMoves = emptyList()
            invalidate()
        }

    var flipped: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showArrows: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showMultiArrows: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showEvalBar: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showBoardWarnings: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showPinnedPieces: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showLineRisks: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showPieceMarkers: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showMoveFeedback: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var boardSnapshot: ChessBoard = ChessBoard()
    private var analysis: AnalysisResult? = null
    private var pinnedPieces: List<PinnedPiece> = emptyList()
    private var lineRisks: List<LineRisk> = emptyList()
    private var warnings: List<BoardWarning> = emptyList()
    private var lastFeedback: MoveFeedback? = null
    private var gameStatus: GameStatus = GameStatus.ONGOING
    private var isAnalyzing: Boolean = false

    private var selectedSquare: Int = -1
    private var legalTargetsForSelection: List<Move> = emptyList()
    private var pendingPromotionMoves: List<Move> = emptyList()

    // Align-mode drag state
    private enum class DragHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var activeHandle: DragHandle = DragHandle.NONE
    private var lastRawX: Float = 0f
    private var lastRawY: Float = 0f
    private val lockBtnRect = RectF()
    private val promoRects = Array(4) { RectF() }

    // Callbacks
    var onMoveEntered: ((Move) -> Unit)? = null
    var onRectDelta: ((dx: Int, dy: Int, dw: Int, dh: Int) -> Unit)? = null
    var onLockAlign: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val alignFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 16, 40, 30)
        style = Paint.Style.FILL
    }
    private val alignBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#19E58F")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 25, 229, 143)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val subtleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }
    private val lineRiskDirectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 239, 68, 68)
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }
    private val lineRiskXrayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 249, 115, 22)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#19E58F")
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B0E14")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val selectSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 25, 229, 143)
        style = Paint.Style.FILL
    }
    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 251, 191, 36)
        style = Paint.Style.FILL
    }
    private val legalDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 25, 229, 143)
        style = Paint.Style.FILL
    }
    private val legalCaptureRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 25, 229, 143)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val pieceHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 12, 16, 24)
        style = Paint.Style.FILL
    }
    private val whitePieceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val blackPieceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val coordTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 25, 229, 143)
        textSize = 11f * density
        isFakeBoldText = true
    }
    private val warningRedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 239, 68, 68)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val warningRedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(52, 239, 68, 68)
        style = Paint.Style.FILL
    }
    private val warningOrangeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 249, 115, 22)
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
    }
    private val pinnedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCF8FAFC")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val evalWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 241, 245, 249)
        style = Paint.Style.FILL
    }
    private val evalBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 20, 24, 34)
        style = Paint.Style.FILL
    }
    private val evalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 100, 116, 139)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 16, 20, 30)
        style = Paint.Style.FILL
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        isFakeBoldText = true
    }

    fun updateState(
        board: ChessBoard,
        analysisResult: AnalysisResult?,
        pins: List<PinnedPiece>,
        boardWarnings: List<BoardWarning>,
        feedback: MoveFeedback?,
        status: GameStatus,
        analyzing: Boolean,
        risks: List<LineRisk> = emptyList()
    ) {
        boardSnapshot = board.copy()
        analysis = analysisResult
        pinnedPieces = pins
        lineRisks = risks
        warnings = boardWarnings
        lastFeedback = feedback
        gameStatus = status
        isAnalyzing = analyzing
        selectedSquare = -1
        legalTargetsForSelection = emptyList()
        pendingPromotionMoves = emptyList()
        invalidate()
    }

    fun setAnalyzing(analyzing: Boolean) {
        isAnalyzing = analyzing
        invalidate()
    }

    private fun squareToScreenColRow(sq: Int): Pair<Int, Int> {
        val file = Squares.fileOf(sq)
        val rank = Squares.rankOf(sq)
        return if (!flipped) {
            file to (7 - rank)
        } else {
            (7 - file) to rank
        }
    }

    private fun screenXYToSquare(x: Float, y: Float): Int {
        if (width <= 0 || height <= 0) return -1
        val col = ((x / width) * 8f).toInt().coerceIn(0, 7)
        val row = ((y / height) * 8f).toInt().coerceIn(0, 7)
        val file = if (!flipped) col else (7 - col)
        val rank = if (!flipped) (7 - row) else row
        return Squares.of(file, rank)
    }

    private fun squareRect(sq: Int): RectF {
        val (col, row) = squareToScreenColRow(sq)
        val sqW = width / 8f
        val sqH = height / 8f
        return RectF(col * sqW, row * sqH, (col + 1) * sqW, (row + 1) * sqH)
    }

    private fun squareCenter(sq: Int): Pair<Float, Float> {
        val (col, row) = squareToScreenColRow(sq)
        val sqW = width / 8f
        val sqH = height / 8f
        return ((col + 0.5f) * sqW) to ((row + 0.5f) * sqH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val sqW = width / 8f
        val sqH = height / 8f
        val minSq = min(sqW, sqH)

        if (mode == OverlayMode.ALIGN) {
            drawAlignOverlay(canvas, sqW, sqH)
            return
        }

        // Clean zero-border pass-through play; only highlight last move softly in manual TAP_MOVE mode
        if (mode == OverlayMode.TAP_MOVE) {
            boardSnapshot.lastMove()?.let { lm ->
                canvas.drawRect(squareRect(lm.from), lastMovePaint)
                canvas.drawRect(squareRect(lm.to), lastMovePaint)
            }
        }

        // Highlight selected square & legal destination markers
        if (mode == OverlayMode.TAP_MOVE && selectedSquare in 0..63) {
            canvas.drawRect(squareRect(selectedSquare), selectSquarePaint)
            for (m in legalTargetsForSelection) {
                val (cx, cy) = squareCenter(m.to)
                val isCap = boardSnapshot.squares[m.to] != Piece.EMPTY || m.isEnPassant
                if (isCap) {
                    canvas.drawCircle(cx, cy, minSq * 0.40f, legalCaptureRingPaint)
                } else {
                    canvas.drawCircle(cx, cy, minSq * 0.16f, legalDotPaint)
                }
            }
        }

        // Board warnings (Check / Hanging / Threatened)
        if (showBoardWarnings) {
            drawWarnings(canvas, minSq)
        }

        // Pinned pieces
        if (showPinnedPieces) {
            drawPinnedPieces(canvas, minSq)
        }

        // Line risks (enemy sliding-piece attack/x-ray lines onto King/Queen)
        if (showLineRisks) {
            drawLineRisks(canvas, minSq)
        }

        // Optional subtle piece markers so tracked board state is clear
        if (showPieceMarkers) {
            drawPieceMarkers(canvas, minSq)
        }

        // Best-move arrows (drawn above markers so arrows are always crystal clear)
        if (showArrows) {
            val moves = analysis?.topMoves.orEmpty()
            if (moves.isNotEmpty()) {
                val count = if (showMultiArrows) min(moves.size, 3) else 1
                // Draw secondary arrows first so primary #1 emerald arrow sits cleanly on top
                for (i in (count - 1) downTo 0) {
                    drawMoveArrow(canvas, moves[i], i, minSq)
                }
            }
        }

        // Move quality badge on destination square + floating pill
        if (showMoveFeedback) {
            lastFeedback?.let { fb ->
                drawMoveQuality(canvas, fb, minSq)
            }
        }

        // Vertical Evaluation Bar along left edge
        if (showEvalBar) {
            drawEvalBar(canvas)
        }

        // Game over or analyzing status pill at bottom-right / top-right
        drawStatusPill(canvas)

        // Promotion chooser if active
        if (pendingPromotionMoves.isNotEmpty()) {
            drawPromotionBar(canvas, minSq)
        }
    }

    private fun drawAlignOverlay(canvas: Canvas, sqW: Float, sqH: Float) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alignFillPaint)

        // 8x8 Grid
        for (i in 1..7) {
            val x = i * sqW
            val y = i * sqH
            canvas.drawLine(x, 0f, x, height.toFloat(), gridLinePaint)
            canvas.drawLine(0f, y, width.toFloat(), y, gridLinePaint)
        }

        // Coordinate labels along bottom & left edges
        for (i in 0..7) {
            val fileChar = if (!flipped) ('a' + i) else ('h' - i)
            val rankChar = if (!flipped) ('8' - i) else ('1' + i)
            canvas.drawText(fileChar.toString(), i * sqW + sqW * 0.42f, height - 6f * density, coordTextPaint)
            canvas.drawText(rankChar.toString(), 5f * density, i * sqH + sqH * 0.6f, coordTextPaint)
        }

        // Outer border
        val inset = 1.5f * density
        canvas.drawRect(inset, inset, width - inset, height - inset, alignBorderPaint)

        // 4 corner resize handles
        val r = 11f * density
        val corners = arrayOf(
            r to r,
            (width - r) to r,
            r to (height - r),
            (width - r) to (height - r)
        )
        for ((cx, cy) in corners) {
            canvas.drawCircle(cx, cy, r, handleFillPaint)
            canvas.drawCircle(cx, cy, r, handleStrokePaint)
        }

        // Centered instruction + LOCK BOARD pill button
        val btnW = min(width * 0.56f, 168f * density)
        val btnH = 38f * density
        val left = (width - btnW) / 2f
        val top = height * 0.5f - btnH / 2f
        lockBtnRect.set(left, top, left + btnW, top + btnH)

        badgeBgPaint.color = Color.parseColor("#19E58F")
        canvas.drawRoundRect(lockBtnRect, 19f * density, 19f * density, badgeBgPaint)

        badgeTextPaint.color = Color.parseColor("#071A12")
        badgeTextPaint.textSize = 13f * density
        val textY = lockBtnRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText("LOCK BOARD", lockBtnRect.centerX(), textY, badgeTextPaint)
    }

    private fun drawPieceMarkers(canvas: Canvas, minSq: Float) {
        val badgeRadius = minSq * 0.22f
        val textSize = minSq * 0.30f
        whitePieceTextPaint.textSize = textSize
        blackPieceTextPaint.textSize = textSize

        for (sq in 0..63) {
            val p = boardSnapshot.squares[sq]
            if (p == Piece.EMPTY) continue
            val rect = squareRect(sq)
            // Place compact marker in bottom-left corner of square so underlying piece stays visible
            val cx = rect.left + badgeRadius + 2f * density
            val cy = rect.bottom - badgeRadius - 2f * density
            canvas.drawCircle(cx, cy, badgeRadius, pieceHaloPaint)

            val paint = if (p > 0) whitePieceTextPaint else blackPieceTextPaint
            val sym = Piece.unicodeSymbol(p)
            val ty = cy - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(sym, cx, ty, paint)
        }
    }

    private fun drawWarnings(canvas: Canvas, minSq: Float) {
        val inset = 2.5f * density
        for (w in warnings) {
            val rect = squareRect(w.square)
            val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            when (w.type) {
                WarningType.CHECK -> {
                    canvas.drawRect(inner, warningRedFill)
                    canvas.drawRect(inner, warningRedStroke)
                    drawCornerBadge(canvas, rect, "!", Color.parseColor("#EF4444"), minSq, topRight = false)
                }
                WarningType.HANGING -> {
                    canvas.drawRect(inner, warningRedFill)
                    canvas.drawRect(inner, warningRedStroke)
                    drawCornerBadge(canvas, rect, "!", Color.parseColor("#EF4444"), minSq, topRight = true)
                }
                WarningType.THREATENED -> {
                    canvas.drawRect(inner, warningOrangeStroke)
                    drawCornerBadge(canvas, rect, "!", Color.parseColor("#F97316"), minSq, topRight = true)
                }
            }
        }
    }

    private fun drawPinnedPieces(canvas: Canvas, minSq: Float) {
        val inset = 3f * density
        for (pin in pinnedPieces) {
            val rect = squareRect(pin.square)
            val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            canvas.drawRect(inner, pinnedStrokePaint)
            drawCornerBadge(canvas, rect, "P", Color.parseColor("#0284C7"), minSq, topRight = false)
        }
    }

    private fun drawLineRisks(canvas: Canvas, minSq: Float) {
        for (risk in lineRisks) {
            val (ax, ay) = squareCenter(risk.attackerSquare)
            val (tx, ty) = squareCenter(risk.targetSquare)
            val paint = if (risk.isDirectAttack) lineRiskDirectPaint else lineRiskXrayPaint
            canvas.drawLine(ax, ay, tx, ty, paint)
            canvas.drawCircle(tx, ty, minSq * 0.36f, paint)
        }
    }

    private fun drawCornerBadge(
        canvas: Canvas,
        sqRect: RectF,
        symbol: String,
        bgColor: Int,
        minSq: Float,
        topRight: Boolean
    ) {
        val r = (minSq * 0.17f).coerceAtLeast(7f * density)
        val cx = if (topRight) sqRect.right - r - 2f * density else sqRect.left + r + 2f * density
        val cy = sqRect.top + r + 2f * density
        badgeBgPaint.color = bgColor
        canvas.drawCircle(cx, cy, r, badgeBgPaint)
        badgeTextPaint.color = Color.WHITE
        badgeTextPaint.textSize = r * 1.25f
        val ty = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText(symbol, cx, ty, badgeTextPaint)
    }

    private fun drawMoveArrow(canvas: Canvas, scored: ScoredMove, rankIndex: Int, minSq: Float) {
        val (x1, y1) = squareCenter(scored.move.from)
        val (x2, y2) = squareCenter(scored.move.to)

        val dx = x2 - x1
        val dy = y2 - y1
        val dist = hypot(dx, dy)
        if (dist < 1f) return

        val angle = atan2(dy, dx)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val perpX = -sinA
        val perpY = cosA

        // Sleek crisp black vector arrow matching reference proportions (with subtle light outer outline)
        val (arrowColor, shaftWidth, headLen, headHalfW) = when (rankIndex) {
            0 -> Quad(Color.parseColor("#E60B0F14"), minSq * 0.13f, minSq * 0.28f, minSq * 0.20f)
            1 -> Quad(Color.argb(165, 30, 41, 59), minSq * 0.10f, minSq * 0.24f, minSq * 0.17f)
            else -> Quad(Color.argb(135, 71, 85, 105), minSq * 0.09f, minSq * 0.21f, minSq * 0.15f)
        }

        val shaftHalfW = shaftWidth * 0.5f
        val startOffset = minSq * 0.10f
        val tailX = x1 + cosA * startOffset
        val tailY = y1 + sinA * startOffset
        val tipX = x2 - cosA * (minSq * 0.04f)
        val tipY = y2 - sinA * (minSq * 0.04f)
        val effHeadLen = min(headLen, dist * 0.48f)
        val baseX = tipX - cosA * effHeadLen
        val baseY = tipY - sinA * effHeadLen

        // Single unified closed vector path (rounded tail + straight shaft + wide arrowhead)
        // eliminating any semi-transparent alpha overlap seam!
        val tailCapR = shaftHalfW
        val tailRect = RectF(
            tailX - tailCapR,
            tailY - tailCapR,
            tailX + tailCapR,
            tailY + tailCapR
        )
        val angleDeg = Math.toDegrees(angle.toDouble()).toFloat()

        val arrowPath = Path().apply {
            moveTo(baseX + perpX * shaftHalfW, baseY + perpY * shaftHalfW)
            lineTo(baseX + perpX * headHalfW, baseY + perpY * headHalfW)
            lineTo(tipX, tipY)
            lineTo(baseX - perpX * headHalfW, baseY - perpY * headHalfW)
            lineTo(baseX - perpX * shaftHalfW, baseY - perpY * shaftHalfW)
            lineTo(tailX - perpX * shaftHalfW, tailY - perpY * shaftHalfW)
            arcTo(tailRect, angleDeg - 90f, -180f, false)
            close()
        }

        // Draw light outer outline first so the slim black shaft stays 100% razor-crisp inside
        // while popping cleanly on both dark blue/slate/green and light cream/white squares.
        if (rankIndex == 0) {
            arrowOutlinePaint.color = Color.parseColor("#CCF8FAFC")
            arrowOutlinePaint.strokeWidth = 3.2f * density
            canvas.drawPath(arrowPath, arrowOutlinePaint)
        } else {
            arrowOutlinePaint.color = Color.argb(140, 248, 250, 252)
            arrowOutlinePaint.strokeWidth = 2.2f * density
            canvas.drawPath(arrowPath, arrowOutlinePaint)
        }

        arrowFillPaint.color = arrowColor
        canvas.drawPath(arrowPath, arrowFillPaint)
    }

    private data class Quad(val color: Int, val shaft: Float, val headLen: Float, val headHalfW: Float)

    private fun drawMoveQuality(canvas: Canvas, fb: MoveFeedback, minSq: Float) {
        val destRect = squareRect(fb.move.to)
        drawCornerBadge(canvas, destRect, fb.quality.badgeSymbol, fb.quality.colorArgb, minSq, topRight = true)

        // Compact floating pill at top center
        val lossTxt = if (fb.cpLoss > 15) " (-${String.format("%.1f", fb.cpLoss / 100f)})" else ""
        val label = "${fb.quality.badgeSymbol} ${fb.quality.label}$lossTxt"
        pillTextPaint.textSize = 11.5f * density
        val textW = pillTextPaint.measureText(label)
        val padH = 10f * density
        val pillH = 24f * density
        val cx = width / 2f
        val rect = RectF(cx - textW / 2f - padH, 6f * density, cx + textW / 2f + padH, 6f * density + pillH)

        pillBgPaint.color = Color.argb(230, 14, 18, 28)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, pillBgPaint)

        pillTextPaint.color = fb.quality.colorArgb
        val ty = rect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.drawText(label, rect.centerX(), ty, pillTextPaint)
    }

    private fun drawEvalBar(canvas: Canvas) {
        val res = analysis ?: return
        val barW = 10f * density
        val barRect = RectF(0f, 0f, barW, height.toFloat())

        // Map whiteEvalCp to white share [0.05..0.95] via smooth tanh curve
        val whiteShare = when {
            res.whiteMateIn != null -> if (res.whiteMateIn > 0) 0.98f else 0.02f
            else -> (0.5 + 0.45 * tanh(res.whiteEvalCp / 450.0)).toFloat().coerceIn(0.05f, 0.95f)
        }

        canvas.drawRect(barRect, evalBlackPaint)
        if (!flipped) {
            // White at bottom
            val splitY = height * (1f - whiteShare)
            canvas.drawRect(0f, splitY, barW, height.toFloat(), evalWhitePaint)
        } else {
            // White at top
            val splitY = height * whiteShare
            canvas.drawRect(0f, 0f, barW, splitY, evalWhitePaint)
        }
        canvas.drawRect(barRect, evalBorderPaint)

        // Tiny eval label next to bar
        val scoreLabel = when {
            res.whiteMateIn != null -> {
                val m = res.whiteMateIn
                if (m == 0) "#" else if (m > 0) "+M$m" else "-M${-m}"
            }
            else -> {
                val pawns = res.whiteEvalCp / 100f
                if (pawns >= 0) String.format("+%.1f", pawns) else String.format("%.1f", pawns)
            }
        }
        pillTextPaint.textSize = 10f * density
        val tw = pillTextPaint.measureText(scoreLabel)
        val lRect = RectF(barW + 3f * density, 4f * density, barW + 3f * density + tw + 10f * density, 22f * density)
        pillBgPaint.color = Color.argb(215, 14, 18, 28)
        canvas.drawRoundRect(lRect, 6f * density, 6f * density, pillBgPaint)
        pillTextPaint.color = if (res.whiteEvalCp >= 0) Color.parseColor("#19E58F") else Color.parseColor("#F87171")
        val ty = lRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.drawText(scoreLabel, lRect.centerX(), ty, pillTextPaint)
    }

    private fun drawStatusPill(canvas: Canvas) {
        val text = when {
            gameStatus == GameStatus.CHECKMATE_WHITE_WINS -> "Checkmate • White Wins"
            gameStatus == GameStatus.CHECKMATE_BLACK_WINS -> "Checkmate • Black Wins"
            gameStatus == GameStatus.STALEMATE -> "Stalemate"
            gameStatus == GameStatus.DRAW_INSUFFICIENT -> "Draw • Insufficient Material"
            gameStatus == GameStatus.DRAW_FIFTY_MOVE -> "Draw • 50-Move Rule"
            else -> return
        }
        pillTextPaint.textSize = 11f * density
        val tw = pillTextPaint.measureText(text)
        val pad = 10f * density
        val h = 24f * density
        val rect = RectF(
            width - tw - pad * 2f - 6f * density,
            height - h - 6f * density,
            width - 6f * density,
            height - 6f * density
        )
        pillBgPaint.color = Color.argb(230, 14, 18, 28)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, pillBgPaint)
        pillTextPaint.color = Color.parseColor("#19E58F")
        val ty = rect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), ty, pillTextPaint)
    }

    private fun drawPromotionBar(canvas: Canvas, minSq: Float) {
        val choices = intArrayOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)
        val cellSize = (minSq * 1.15f).coerceIn(42f * density, 64f * density)
        val totalW = cellSize * 4f + 12f * density
        val startX = (width - totalW) / 2f
        val startY = (height - cellSize) / 2f

        val bg = RectF(startX - 8f * density, startY - 8f * density, startX + totalW + 8f * density, startY + cellSize + 8f * density)
        pillBgPaint.color = Color.argb(245, 18, 21, 30)
        canvas.drawRoundRect(bg, 14f * density, 14f * density, pillBgPaint)
        canvas.drawRoundRect(bg, 14f * density, 14f * density, alignBorderPaint)

        val mover = boardSnapshot.sideToMove
        whitePieceTextPaint.textSize = cellSize * 0.62f

        for (i in 0..3) {
            val left = startX + i * (cellSize + 4f * density)
            promoRects[i].set(left, startY, left + cellSize, startY + cellSize)
            badgeBgPaint.color = Color.parseColor("#1E293B")
            canvas.drawRoundRect(promoRects[i], 10f * density, 10f * density, badgeBgPaint)
            val sym = Piece.unicodeSymbol(Piece.make(mover, choices[i]))
            val ty = promoRects[i].centerY() - (whitePieceTextPaint.descent() + whitePieceTextPaint.ascent()) / 2f
            canvas.drawText(sym, promoRects[i].centerX(), ty, whitePieceTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == OverlayMode.PASS_THROUGH) return false

        if (mode == OverlayMode.ALIGN) {
            return handleAlignTouch(event)
        }

        if (mode == OverlayMode.TAP_MOVE && event.action == MotionEvent.ACTION_UP) {
            handleTapMove(event.x, event.y)
            return true
        }
        return mode == OverlayMode.TAP_MOVE
    }

    private fun handleAlignTouch(event: MotionEvent): Boolean {
        val cornerHitRadius = 38f * density
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (lockBtnRect.contains(event.x, event.y)) {
                    onLockAlign?.invoke()
                    return true
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                activeHandle = when {
                    event.x <= cornerHitRadius && event.y <= cornerHitRadius -> DragHandle.TOP_LEFT
                    event.x >= width - cornerHitRadius && event.y <= cornerHitRadius -> DragHandle.TOP_RIGHT
                    event.x <= cornerHitRadius && event.y >= height - cornerHitRadius -> DragHandle.BOTTOM_LEFT
                    event.x >= width - cornerHitRadius && event.y >= height - cornerHitRadius -> DragHandle.BOTTOM_RIGHT
                    else -> DragHandle.MOVE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                if (dx == 0 && dy == 0) return true
                lastRawX = event.rawX
                lastRawY = event.rawY
                when (activeHandle) {
                    DragHandle.MOVE -> onRectDelta?.invoke(dx, dy, 0, 0)
                    DragHandle.TOP_LEFT -> onRectDelta?.invoke(dx, dy, -dx, -dy)
                    DragHandle.TOP_RIGHT -> onRectDelta?.invoke(0, dy, dx, -dy)
                    DragHandle.BOTTOM_LEFT -> onRectDelta?.invoke(dx, 0, -dx, dy)
                    DragHandle.BOTTOM_RIGHT -> onRectDelta?.invoke(0, 0, dx, dy)
                    DragHandle.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = DragHandle.NONE
                return true
            }
        }
        return true
    }

    private fun handleTapMove(x: Float, y: Float) {
        // Handle active promotion bar first
        if (pendingPromotionMoves.isNotEmpty()) {
            val choices = intArrayOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)
            for (i in 0..3) {
                if (promoRects[i].contains(x, y)) {
                    val chosen = pendingPromotionMoves.firstOrNull { Piece.typeOf(it.promotion) == choices[i] }
                        ?: pendingPromotionMoves.first()
                    pendingPromotionMoves = emptyList()
                    selectedSquare = -1
                    legalTargetsForSelection = emptyList()
                    onMoveEntered?.invoke(chosen)
                    return
                }
            }
            // Tapped outside promotion bar -> auto-promote to Queen
            val queenMove = pendingPromotionMoves.firstOrNull { Piece.typeOf(it.promotion) == Piece.QUEEN }
                ?: pendingPromotionMoves.first()
            pendingPromotionMoves = emptyList()
            selectedSquare = -1
            legalTargetsForSelection = emptyList()
            onMoveEntered?.invoke(queenMove)
            return
        }

        val tappedSq = screenXYToSquare(x, y)
        if (tappedSq !in 0..63) return

        // If a square is already selected, check if tappedSq is a legal destination
        if (selectedSquare in 0..63) {
            val matching = legalTargetsForSelection.filter { it.to == tappedSq }
            if (matching.size == 1) {
                selectedSquare = -1
                legalTargetsForSelection = emptyList()
                onMoveEntered?.invoke(matching.first())
                return
            } else if (matching.size > 1) {
                // Pawn promotion (Q, R, B, N)
                pendingPromotionMoves = matching
                invalidate()
                return
            }
        }

        // Otherwise try selecting a friendly piece of sideToMove
        val piece = boardSnapshot.squares[tappedSq]
        if (Piece.colorOf(piece) == boardSnapshot.sideToMove) {
            selectedSquare = tappedSq
            legalTargetsForSelection = boardSnapshot.generateLegalMoves().filter { it.from == tappedSq }
        } else {
            selectedSquare = -1
            legalTargetsForSelection = emptyList()
        }
        invalidate()
    }
}
