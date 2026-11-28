package com.chesscoach.overlay.vision

import com.chesscoach.overlay.engine.ChessBoard
import com.chesscoach.overlay.engine.Move
import com.chesscoach.overlay.engine.Piece
import com.chesscoach.overlay.engine.Squares
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BoardVisionAnalyzerTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun fillRect(
        pixels: IntArray,
        w: Int,
        h: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int
    ) {
        val sx = x0.coerceIn(0, w)
        val ex = x1.coerceIn(0, w)
        val sy = y0.coerceIn(0, h)
        val ey = y1.coerceIn(0, h)
        for (y in sy until ey) {
            val row = y * w
            for (x in sx until ex) {
                pixels[row + x] = color
            }
        }
    }

    private fun renderBoardScreen(
        board: ChessBoard,
        playingAsWhite: Boolean,
        screenW: Int = 540,
        screenH: Int = 1170,
        boardSize: Int = 540,
        boardTop: Int = 340,
        lightColor: Int = rgb(239, 239, 213),
        darkColor: Int = rgb(118, 150, 83),
        highlightSquares: Set<Int> = emptySet(),
        emeraldArrowSquares: Set<Int> = emptySet(),
        largePieces: Boolean = false
    ): IntArray {
        val pixels = IntArray(screenW * screenH) { rgb(49, 46, 41) }
        val left = (screenW - boardSize) / 2
        val sq = boardSize / 8f

        // Render top/bottom UI chrome outside board
        fillRect(pixels, screenW, screenH, 0, 0, screenW, 90, rgb(32, 30, 28))
        fillRect(pixels, screenW, screenH, 0, screenH - 110, screenW, screenH, rgb(32, 30, 28))

        val yellowLight = rgb(246, 246, 105)
        val yellowDark = rgb(186, 202, 43)

        for (r in 0..7) {
            for (c in 0..7) {
                val x0 = left + (c * sq).toInt()
                val y0 = boardTop + (r * sq).toInt()
                val x1 = left + ((c + 1) * sq).toInt()
                val y1 = boardTop + ((r + 1) * sq).toInt()

                val file = if (playingAsWhite) c else (7 - c)
                val rank = if (playingAsWhite) (7 - r) else r
                val chessSq = Squares.of(file, rank)
                val isLight = ((r + c) and 1) == 0

                val bg = if (chessSq in highlightSquares) {
                    if (isLight) yellowLight else yellowDark
                } else {
                    if (isLight) lightColor else darkColor
                }
                fillRect(pixels, screenW, screenH, x0, y0, x1, y1, bg)

                // Simulate Chess.com corner rank/file coordinate glyph on col 0 & row 7
                if (c == 0) {
                    fillRect(
                        pixels, screenW, screenH,
                        x0 + 2, y0 + 2, x0 + 10, y0 + 13,
                        if (isLight) darkColor else lightColor
                    )
                }
                if (r == 7) {
                    fillRect(
                        pixels, screenW, screenH,
                        x1 - 10, y1 - 13, x1 - 2, y1 - 2,
                        if (isLight) darkColor else lightColor
                    )
                }

                // Draw realistic tapered chess piece silhouette
                val piece = board.squares[chessSq]
                if (piece != Piece.EMPTY) {
                    val cx = (x0 + x1) * 0.5f
                    val cy = (y0 + y1) * 0.5f
                    val rx = if (largePieces) sq * 0.34f else sq * 0.30f
                    val ry = if (largePieces) sq * 0.37f else sq * 0.31f
                    val irx = rx * 0.80f
                    val iry = ry * 0.80f
                    val outline = if (piece > 0) rgb(48, 48, 48) else rgb(24, 24, 24)
                    val interior = if (piece > 0) rgb(248, 248, 248) else rgb(42, 42, 42)
                    val hi = if (piece > 0) rgb(252, 252, 252) else rgb(68, 68, 68)

                    val syMin = y0.coerceIn(0, screenH)
                    val syMax = y1.coerceIn(0, screenH)
                    val sxMin = x0.coerceIn(0, screenW)
                    val sxMax = x1.coerceIn(0, screenW)
                    for (py in syMin until syMax) {
                        val dy = py - cy
                        val rowOff = py * screenW
                        for (px in sxMin until sxMax) {
                            val dx = px - cx
                            val ellOuter = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) <= 1f
                            val baseOuter = largePieces && dy >= sq * 0.16f && dy <= sq * 0.37f && abs(dx) <= sq * 0.34f
                            if (ellOuter || baseOuter) {
                                val ellInner = (dx * dx) / (irx * irx) + (dy * dy) / (iry * iry) <= 1f
                                val baseInner = largePieces && dy >= sq * 0.20f && dy <= sq * 0.33f && abs(dx) <= sq * 0.28f
                                val core = abs(dx) <= irx * 0.45f && abs(dy) <= iry * 0.45f
                                pixels[rowOff + px] = when {
                                    core -> hi
                                    ellInner || baseInner -> interior
                                    else -> outline
                                }
                            }
                        }
                    }
                }

                // Optional simulated emerald overlay arrow drawn across the square center
                if (chessSq in emeraldArrowSquares) {
                    val cx = (x0 + x1) / 2
                    val aw = (sq * 0.14f).toInt().coerceAtLeast(4)
                    fillRect(pixels, screenW, screenH, cx - aw, y0 + 6, cx + aw, y1 - 6, rgb(34, 197, 94))
                }
            }
        }
        return pixels
    }

    @Test
    fun menuScreenDoesNotFalseDetectBoard() {
        val w = 540
        val h = 1170
        val pixels = IntArray(w * h) { rgb(38, 36, 33) }

        // Simulate Chess.com Home screen cards, Play button, and small decorative chessboard avatar
        fillRect(pixels, w, h, 24, 120, w - 24, 230, rgb(24, 34, 58)) // Premium banner
        fillRect(pixels, w, h, 24, 280, 420, 460, rgb(45, 88, 42)) // Puzzles card
        fillRect(pixels, w, h, 24, 540, w - 24, 650, rgb(54, 51, 47)) // Match card
        fillRect(pixels, w, h, 24, 700, 175, 840, rgb(48, 45, 42)) // Rapid rating card
        fillRect(pixels, w, h, 190, 700, 345, 840, rgb(48, 45, 42)) // Bullet rating card
        fillRect(pixels, w, h, 360, 700, 515, 840, rgb(48, 45, 42)) // Blitz rating card
        fillRect(pixels, w, h, 24, 960, w - 24, 1040, rgb(129, 182, 76)) // Green Play button

        // Tiny 4x4 decorative avatar chessboard (width = 72px << 80% of screen)
        for (r in 0..3) {
            for (c in 0..3) {
                val col = if ((r + c) % 2 == 0) rgb(238, 238, 210) else rgb(118, 150, 86)
                fillRect(pixels, w, h, 36 + c * 18, 558 + r * 18, 36 + (c + 1) * 18, 558 + (r + 1) * 18, col)
            }
        }

        val det = BoardVisionAnalyzer.detectBoard(pixels, w, h)
        assertFalse("Menu screen must not detect a chess board", det.found)
    }

    @Test
    fun detectsNonAlignedVerticalOffsetTop413() {
        val board = ChessBoard()
        // Exact coarse-stride offset (top = 413 on 540x1170) that v2.0 missed!
        val pixels = renderBoardScreen(
            board = board,
            playingAsWhite = true,
            boardSize = 540,
            boardTop = 413,
            largePieces = true
        )

        val analysis = BoardVisionAnalyzer.analyzeFrame(pixels, 540, 1170)
        assertTrue("Expected board at non-aligned top=413 to be detected", analysis.detection.found)
        assertTrue("Expected top near 413, got ${analysis.detection.rect.top}", abs(analysis.detection.rect.top - 413) <= 5)
        assertTrue("Expected size near 540, got ${analysis.detection.rect.width}", abs(analysis.detection.rect.width - 540) <= 6)

        val start = BoardVisionAnalyzer.detectStartingPosition(analysis.grid!!)
        assertTrue("Expected starting position", start.isStartPosition)
        assertTrue("Expected playingAsWhite=true", start.playingAsWhite)
    }

    @Test
    fun detectsClassicGreenDarkSlateAndBrownWoodWithLargePiecesAndHighlights() {
        val board = ChessBoard()
        assertNotNull(board.pushUciMove("e2e4"))
        val highlights = setOf(Squares.parse("e2"), Squares.parse("e4"))

        data class ThemeCase(
            val name: String,
            val top: Int,
            val size: Int,
            val light: Int,
            val dark: Int
        )

        val themes = listOf(
            ThemeCase("Classic Green", 413, 540, rgb(238, 238, 210), rgb(118, 150, 86)),
            ThemeCase("Dark Slate (sep~24)", 327, 540, rgb(82, 86, 94), rgb(68, 72, 80)),
            ThemeCase("Brown Wood", 441, 496, rgb(240, 217, 181), rgb(181, 136, 99))
        )

        for (t in themes) {
            val pixels = renderBoardScreen(
                board = board,
                playingAsWhite = true,
                boardSize = t.size,
                boardTop = t.top,
                lightColor = t.light,
                darkColor = t.dark,
                highlightSquares = highlights,
                largePieces = true
            )
            val analysis = BoardVisionAnalyzer.analyzeFrame(pixels, 540, 1170)
            assertTrue("Expected ${t.name} board to be detected", analysis.detection.found)
            assertTrue(
                "Expected ${t.name} top near ${t.top}, got ${analysis.detection.rect.top}",
                abs(analysis.detection.rect.top - t.top) <= 6
            )
            assertTrue(
                "Expected ${t.name} size near ${t.size}, got ${analysis.detection.rect.width}",
                abs(analysis.detection.rect.width - t.size) <= 8
            )

            // Verify legal move e2e4 is cleanly recognized from start board on EVERY theme!
            val match = BoardVisionAnalyzer.matchLegalMove(ChessBoard(), analysis.grid!!, playingAsWhite = true)
            assertNotNull("Expected e2e4 match on ${t.name}", match)
            assertEquals("Expected exact e2e4 on ${t.name}", "e2e4", match!!.moves[0].toUci())
        }
    }

    @Test
    fun detectsStartingPositionBlackAtBottomWoodTheme() {
        val board = ChessBoard()
        val pixels = renderBoardScreen(
            board = board,
            playingAsWhite = false,
            boardSize = 496,
            boardTop = 360,
            lightColor = rgb(240, 217, 181),
            darkColor = rgb(181, 136, 99),
            largePieces = true
        )

        val analysis = BoardVisionAnalyzer.analyzeFrame(pixels, 540, 1170)
        assertTrue("Expected wood board to be detected", analysis.detection.found)
        val start = BoardVisionAnalyzer.detectStartingPosition(analysis.grid!!)
        assertTrue("Expected starting position", start.isStartPosition)
        assertFalse("Expected playingAsWhite=false", start.playingAsWhite)
    }

    @Test
    fun manualLockedAlignBoxAcceptsLowContrastTheme() {
        val board = ChessBoard()
        // Ultra-low-contrast dark theme (sep ~ 15) inside a manually aligned box
        val pixels = renderBoardScreen(
            board = board,
            playingAsWhite = true,
            boardSize = 500,
            boardTop = 310,
            lightColor = rgb(75, 78, 84),
            darkColor = rgb(66, 69, 75),
            largePieces = true
        )
        val manualBox = PixelRect(left = 20, top = 310, width = 500, height = 500)
        val analysis = BoardVisionAnalyzer.analyzeFrame(pixels, 540, 1170, lockedRect = manualBox)
        assertTrue("Manual Align box with sep >= 12 must always be accepted", analysis.detection.found)
        assertEquals(manualBox, analysis.detection.rect)
    }

    @Test
    fun staticScreenRepeatFrameTracksOpeningAndOrientationFlip() {
        val session = BoardTrackingSession()
        val trackedBoard = ChessBoard()
        val liveBoard = ChessBoard()

        // 1. Static starting position (White at bottom) re-emitted across 2 ticks
        val startPixels = renderBoardScreen(liveBoard, playingAsWhite = true, boardTop = 413, largePieces = true)
        val startFrame1 = BoardVisionAnalyzer.analyzeFrame(startPixels, 540, 1170).copy(framesCaptured = 1L)
        val startFrame2 = BoardVisionAnalyzer.analyzeFrame(startPixels, 540, 1170).copy(framesCaptured = 1L)

        // Frame 1 immediately emits BoardVisible so arrows can render right away
        val ev1 = session.onFrame(startFrame1, trackedBoard, currentPlayingAsWhite = true)
        assertTrue("BoardVisible must fire on frame #1", ev1.any { it is BoardTrackingEvent.BoardVisible })
        assertFalse("NewGameDetected waits for 2-frame debounce", ev1.any { it is BoardTrackingEvent.NewGameDetected })

        // Repeat static frame #2 confirms NewGameDetected even with no new screen frames!
        val ev2 = session.onFrame(startFrame2, trackedBoard, currentPlayingAsWhite = true)
        val ng = ev2.filterIsInstance<BoardTrackingEvent.NewGameDetected>().firstOrNull()
        assertNotNull("Static repeat frame must confirm NewGameDetected", ng)
        assertTrue(ng!!.playingAsWhite)

        // 2. Track 1. e4 e5 2. Nf3 across static repeat-frame pairs
        for (uci in listOf("e2e4", "e7e5", "g1f3")) {
            val m = liveBoard.pushUciMove(uci)
            assertNotNull(m)
            val px = renderBoardScreen(
                board = liveBoard,
                playingAsWhite = true,
                boardTop = 413,
                highlightSquares = setOf(m!!.from, m.to),
                emeraldArrowSquares = setOf(Squares.parse("d3"), Squares.parse("d4")),
                largePieces = true
            )
            val f1 = BoardVisionAnalyzer.analyzeFrame(px, 540, 1170)
            val f2 = BoardVisionAnalyzer.analyzeFrame(px, 540, 1170)

            val firstEvents = session.onFrame(f1, trackedBoard, currentPlayingAsWhite = true)
            assertFalse("Single frame must not fire $uci yet", firstEvents.any { it is BoardTrackingEvent.MovesDetected })

            val secondEvents = session.onFrame(f2, trackedBoard, currentPlayingAsWhite = true)
            val moveEv = secondEvents.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
            assertNotNull("Second static frame must fire $uci", moveEv)
            assertEquals(uci, moveEv!!.moves.first().toUci())
            assertTrue(trackedBoard.pushMove(moveEv.moves.first()))
        }

        // 3. New game started as Black (orientation flip!)
        val blackGame = ChessBoard()
        val blackPx = renderBoardScreen(blackGame, playingAsWhite = false, boardTop = 413, largePieces = true)
        val bf1 = BoardVisionAnalyzer.analyzeFrame(blackPx, 540, 1170)
        val bf2 = BoardVisionAnalyzer.analyzeFrame(blackPx, 540, 1170)
        session.onFrame(bf1, trackedBoard, currentPlayingAsWhite = true)
        val flipEvents = session.onFrame(bf2, trackedBoard, currentPlayingAsWhite = true)
        val flipNewGame = flipEvents.filterIsInstance<BoardTrackingEvent.NewGameDetected>().firstOrNull()
        assertNotNull("Expected NewGameDetected on orientation flip", flipNewGame)
        assertFalse("Expected playingAsWhite=false after flip", flipNewGame!!.playingAsWhite)
    }

    @Test
    fun tracksOpeningMovesWithHighlightsAndEmeraldOverlayArrow() {
        val liveBoard = ChessBoard()
        val trackedBoard = ChessBoard()

        val startWithArrow = renderBoardScreen(
            board = liveBoard,
            playingAsWhite = true,
            emeraldArrowSquares = setOf(Squares.parse("e2"), Squares.parse("e3"), Squares.parse("e4"))
        )
        val startAnalysis = BoardVisionAnalyzer.analyzeFrame(startWithArrow, 540, 1170)
        assertTrue(startAnalysis.detection.found)
        val noMove = BoardVisionAnalyzer.matchLegalMove(trackedBoard, startAnalysis.grid!!, playingAsWhite = true)
        assertNull("Emerald arrow on empty squares must not trigger a false move", noMove)

        val sequence = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5")
        for (uci in sequence) {
            val moved = liveBoard.pushUciMove(uci)
            assertNotNull("Move $uci should be legal", moved)
            val highlights = setOf(moved!!.from, moved.to)
            val frame = renderBoardScreen(
                board = liveBoard,
                playingAsWhite = true,
                highlightSquares = highlights,
                emeraldArrowSquares = setOf(Squares.parse("d3"), Squares.parse("d4"))
            )
            val analysis = BoardVisionAnalyzer.analyzeFrame(frame, 540, 1170)
            assertTrue("Board should stay detected after $uci", analysis.detection.found)

            val match = BoardVisionAnalyzer.matchLegalMove(trackedBoard, analysis.grid!!, playingAsWhite = true)
            assertNotNull("Expected legal move match for $uci", match)
            assertEquals("Expected 1-ply match for $uci", 1, match!!.moves.size)
            assertEquals("Expected exact UCI $uci", uci, match.moves[0].toUci())
            assertTrue(trackedBoard.pushMove(match.moves[0]))
        }
    }

    @Test
    fun twoPlyLookaheadRecoversFastDoubleMove() {
        val liveBoard = ChessBoard()
        val trackedBoard = ChessBoard()

        assertNotNull(liveBoard.pushUciMove("e2e4"))
        assertNotNull(liveBoard.pushUciMove("e7e5"))

        val frame = renderBoardScreen(liveBoard, playingAsWhite = true)
        val analysis = BoardVisionAnalyzer.analyzeFrame(frame, 540, 1170)
        assertTrue(analysis.detection.found)

        val match = BoardVisionAnalyzer.matchLegalMove(trackedBoard, analysis.grid!!, playingAsWhite = true)
        assertNotNull("Expected 2-ply match for 1. e4 e5", match)
        assertEquals(2, match!!.moves.size)
        assertEquals("e2e4", match.moves[0].toUci())
        assertEquals("e7e5", match.moves[1].toUci())
    }

    @Test
    fun trackingSessionDebouncesAndAutoHidesOnMenus() {
        val session = BoardTrackingSession()
        val board = ChessBoard()

        val startFrame = BoardVisionAnalyzer.analyzeFrame(
            renderBoardScreen(board, playingAsWhite = true),
            540,
            1170
        )

        val ev1 = session.onFrame(startFrame, board, currentPlayingAsWhite = true)
        assertTrue(ev1.any { it is BoardTrackingEvent.BoardVisible })
        assertFalse(ev1.any { it is BoardTrackingEvent.NewGameDetected })

        val ev2 = session.onFrame(startFrame, board, currentPlayingAsWhite = true)
        assertTrue(ev2.any { it is BoardTrackingEvent.NewGameDetected })

        val afterE4 = board.copy().apply { pushUciMove("e2e4") }
        val e4Frame = BoardVisionAnalyzer.analyzeFrame(
            renderBoardScreen(afterE4, playingAsWhite = true),
            540,
            1170
        )

        val evDrag = session.onFrame(e4Frame, board, currentPlayingAsWhite = true)
        assertFalse(evDrag.any { it is BoardTrackingEvent.MovesDetected })

        val evConfirm = session.onFrame(e4Frame, board, currentPlayingAsWhite = true)
        val moveEv = evConfirm.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
        assertNotNull(moveEv)
        assertEquals("e2e4", moveEv!!.moves.first().toUci())

        // Switch to menu screen (no board): 1 miss keeps visible, 2nd miss hides overlay!
        val emptyFrame = FrameAnalysis(BoardDetectionResult.NOT_FOUND, null)
        val miss1 = session.onFrame(emptyFrame, afterE4, currentPlayingAsWhite = true)
        assertFalse(miss1.any { it is BoardTrackingEvent.BoardHidden })
        assertTrue(session.boardVisible)

        val miss2 = session.onFrame(emptyFrame, afterE4, currentPlayingAsWhite = true)
        assertTrue(miss2.any { it is BoardTrackingEvent.BoardHidden })
        assertFalse(session.boardVisible)
    }

    @Test
    fun boardDetectsSlidingLineRisksOntoKing() {
        val board = ChessBoard()
        assertTrue(board.loadFen("4r1k1/8/8/8/8/8/4P3/4K3 w - - 0 1"))
        val risks = board.findLineRisks(Piece.WHITE)
        assertEquals(1, risks.size)
        assertEquals(Squares.parse("e8"), risks[0].attackerSquare)
        assertEquals(Squares.parse("e1"), risks[0].targetSquare)
        assertEquals(Squares.parse("e2"), risks[0].blockerSquare)
        assertFalse(risks[0].isDirectAttack)
    }

    @Test
    fun autoReadStatusFormatterCoversErrorTelemetryScanningAndGrantButton() {
        val longErr = "getMediaProjection: SecurityException: Media projections require a foreground service of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"
        val errStatus = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = false,
            hasMediaProjectionGrant = false,
            boardVisibleOrLocked = false,
            playingAsWhite = true,
            fullMoveNumber = 1,
            framesCaptured = 0L,
            bestScore = 0f,
            lastCaptureError = longErr,
            tapInputMode = false
        )
        assertTrue(errStatus.startsWith("Auto-Read error: getMediaProjection: SecurityException:"))
        assertEquals("Auto-Read error: ".length + 90, errStatus.length)

        val scanStatus = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = true,
            hasMediaProjectionGrant = true,
            boardVisibleOrLocked = false,
            playingAsWhite = true,
            fullMoveNumber = 1,
            framesCaptured = 14L,
            bestScore = 0.678f,
            lastCaptureError = null,
            tapInputMode = false
        )
        assertEquals("Auto-Read: Scanning (14f · best 67%) — open a game", scanStatus)

        val lockedStatus = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = true,
            hasMediaProjectionGrant = true,
            boardVisibleOrLocked = true,
            playingAsWhite = false,
            fullMoveNumber = 12,
            framesCaptured = 20L,
            bestScore = 0.94f,
            lastCaptureError = null,
            tapInputMode = false
        )
        assertEquals("Auto-Read: Locked (Black · move 12)", lockedStatus)

        val needsPerm = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = false,
            hasMediaProjectionGrant = false,
            boardVisibleOrLocked = false,
            playingAsWhite = true,
            fullMoveNumber = 1,
            framesCaptured = 0L,
            bestScore = 0f,
            lastCaptureError = null,
            tapInputMode = true
        )
        assertEquals("Auto-Read: Needs screen permission (tap Grant Access)", needsPerm)

        assertEquals(
            "Grant Access",
            AutoReadStatusFormatter.actionButtonLabel(autoReadEnabled = true, hasMediaProjectionGrant = false)
        )
        assertEquals(
            "Re-Detect",
            AutoReadStatusFormatter.actionButtonLabel(autoReadEnabled = true, hasMediaProjectionGrant = true)
        )
        assertEquals(
            "Re-Detect",
            AutoReadStatusFormatter.actionButtonLabel(autoReadEnabled = false, hasMediaProjectionGrant = false)
        )

        val blankStatus = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = true,
            hasMediaProjectionGrant = true,
            boardVisibleOrLocked = false,
            playingAsWhite = true,
            fullMoveNumber = 1,
            framesCaptured = 12L,
            bestScore = 0f,
            lastCaptureError = null,
            tapInputMode = false,
            frameMaxLum = 0
        )
        assertEquals(
            "Auto-Read: Blank frames (12f) — tap Grant Access & pick Entire screen",
            blankStatus
        )
    }

    private fun drawCrispBlackArrow(
        pixels: IntArray,
        w: Int,
        h: Int,
        left: Int,
        top: Int,
        boardSize: Int,
        arrow: Move,
        playingAsWhite: Boolean
    ) {
        val sq = boardSize / 8f
        fun sqCenter(sqIdx: Int): Pair<Float, Float> {
            val file = Squares.fileOf(sqIdx)
            val rank = Squares.rankOf(sqIdx)
            val col = if (playingAsWhite) file else (7 - file)
            val row = if (playingAsWhite) (7 - rank) else rank
            return (left + (col + 0.5f) * sq) to (top + (row + 0.5f) * sq)
        }
        val (x1, y1) = sqCenter(arrow.from)
        val (x2, y2) = sqCenter(arrow.to)
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = hypot(dx, dy)
        if (dist < 1f) return
        val cosA = dx / dist
        val sinA = dy / dist
        val tailX = x1 + cosA * (sq * 0.10f)
        val tailY = y1 + sinA * (sq * 0.10f)
        val tipX = x2 - cosA * (sq * 0.04f)
        val tipY = y2 - sinA * (sq * 0.04f)
        val headLen = min(sq * 0.28f, dist * 0.48f)
        val baseX = tipX - cosA * headLen
        val baseY = tipY - sinA * headLen
        val shaftHalfW = sq * 0.065f
        val headHalfW = sq * 0.20f
        val outlinePad = 2.0f

        val minY = (top).coerceIn(0, h - 1)
        val maxY = (top + boardSize).coerceIn(0, h - 1)
        val minX = (left).coerceIn(0, w - 1)
        val maxX = (left + boardSize).coerceIn(0, w - 1)

        val segDx = baseX - tailX
        val segDy = baseY - tailY
        val segLenSq = segDx * segDx + segDy * segDy

        for (py in minY until maxY) {
            val rowOff = py * w
            for (px in minX until maxX) {
                val fx = px.toFloat()
                val fy = py.toFloat()
                val t = if (segLenSq > 1e-4f) {
                    (((fx - tailX) * segDx + (fy - tailY) * segDy) / segLenSq).coerceIn(0f, 1f)
                } else 0f
                val dShaft = hypot(fx - (tailX + t * segDx), fy - (tailY + t * segDy))

                val rx = fx - baseX
                val ry = fy - baseY
                val u = rx * cosA + ry * sinA
                val v = abs(-rx * sinA + ry * cosA)
                val inHeadFill = u in 0f..headLen && v <= ((headLen - u) / headLen) * headHalfW
                val inHeadOutline = u in -outlinePad..(headLen + outlinePad) &&
                    v <= (((headLen - u).coerceIn(0f, headLen) / headLen) * headHalfW + outlinePad)

                val inFill = dShaft <= shaftHalfW || inHeadFill
                val inOutline = dShaft <= (shaftHalfW + outlinePad) || inHeadOutline
                if (inFill) {
                    pixels[rowOff + px] = rgb(11, 15, 20)
                } else if (inOutline) {
                    pixels[rowOff + px] = rgb(248, 250, 252)
                }
            }
        }
    }

    @Test
    fun texturedOceanBoardWithEdgeHandleAndRankDigitsClassifies64of64Clean() {
        val board = ChessBoard()
        val w = 540
        val h = 1170
        val boardSize = 540
        val boardTop = 370
        // Ocean theme colors matching Cody's real Samsung screenshot (light cream + blue wave)
        val pixels = renderBoardScreen(
            board = board,
            playingAsWhite = true,
            screenW = w,
            screenH = h,
            boardSize = boardSize,
            boardTop = boardTop,
            lightColor = rgb(250, 240, 230),
            darkColor = rgb(78, 138, 170),
            largePieces = true
        )
        val sq = boardSize / 8f
        // Paint diagonal water-wave texture stripes on dark Ocean squares (deltas ~24 RGB)
        for (r in 0..7) {
            for (c in 0..7) {
                if (((r + c) and 1) == 1) {
                    val x0 = (c * sq).toInt()
                    val y0 = boardTop + (r * sq).toInt()
                    val x1 = ((c + 1) * sq).toInt()
                    val y1 = boardTop + ((r + 1) * sq).toInt()
                    for (py in y0 until y1) {
                        val rowOff = py * w
                        for (px in x0 until x1) {
                            if (pixels[rowOff + px] == rgb(78, 138, 170) && ((px + py) / 9) % 2 == 0) {
                                pixels[rowOff + px] = rgb(62, 122, 154)
                            }
                        }
                    }
                }
            }
        }
        // Paint Samsung Edge Panel dark grey handle along left border of a5 (r=3, c=0, x in 10..15)
        fillRect(pixels, w, h, 9, boardTop + (2.75f * sq).toInt(), 15, boardTop + (3.85f * sq).toInt(), rgb(72, 82, 92))

        val analysis = BoardVisionAnalyzer.analyzeFrame(pixels, w, h)
        assertTrue("Ocean board must be detected", analysis.detection.found)
        val grid = analysis.grid!!
        val expected = BoardVisionAnalyzer.boardToScreenGrid(board, playingAsWhite = true)
        assertEquals(64, BoardVisionAnalyzer.countGridMatches(grid, expected))

        val start = BoardVisionAnalyzer.detectStartingPosition(grid)
        assertTrue("Ocean starting position must match", start.isStartPosition)
        assertTrue("Ocean orientation must detect playingAsWhite=true", start.playingAsWhite)
    }

    @Test
    fun crispBlackArrowOnE2E4DoesNotCauseGhostPieceAndStillDetectsMovedPiece() {
        val startBoard = ChessBoard()
        val e2 = Squares.parse("e2")
        val e4 = Squares.parse("e4")
        val arrowMove = Move(e2, e4)

        // Case 1: Empty e3 & e4 with sleek crisp black arrow drawn from e2 -> e4
        val pixelsEmpty = renderBoardScreen(
            board = startBoard,
            playingAsWhite = true,
            boardSize = 540,
            boardTop = 340,
            lightColor = rgb(250, 240, 230),
            darkColor = rgb(78, 138, 170)
        )
        drawCrispBlackArrow(pixelsEmpty, 540, 1170, 0, 340, 540, arrowMove, playingAsWhite = true)

        val analysisEmpty = BoardVisionAnalyzer.analyzeFrame(
            pixels = pixelsEmpty,
            width = 540,
            height = 1170,
            activeArrow = arrowMove,
            activeArrowPlayingAsWhite = true
        )
        assertTrue(analysisEmpty.detection.found)
        val gridEmpty = analysisEmpty.grid!!
        // e3 = row 5, col 4; e4 = row 4, col 4
        assertEquals("Empty e3 under black arrow must stay EMPTY", SquareOccupancy.EMPTY, gridEmpty[5 * 8 + 4])
        assertEquals("Empty e4 under black arrow must stay EMPTY", SquareOccupancy.EMPTY, gridEmpty[4 * 8 + 4])
        assertEquals("Starting e2 pawn must stay WHITE", SquareOccupancy.WHITE, gridEmpty[6 * 8 + 4])

        // Case 2: White pawn actually moved onto e4 right under the exact same black arrow!
        val afterE4 = ChessBoard()
        val legalE4 = afterE4.generateLegalMoves().first { it.toUci() == "e2e4" }
        afterE4.pushMove(legalE4)

        val pixelsMoved = renderBoardScreen(
            board = afterE4,
            playingAsWhite = true,
            boardSize = 540,
            boardTop = 340,
            lightColor = rgb(250, 240, 230),
            darkColor = rgb(78, 138, 170)
        )
        drawCrispBlackArrow(pixelsMoved, 540, 1170, 0, 340, 540, arrowMove, playingAsWhite = true)

        val analysisMoved = BoardVisionAnalyzer.analyzeFrame(
            pixels = pixelsMoved,
            width = 540,
            height = 1170,
            activeArrow = arrowMove,
            activeArrowPlayingAsWhite = true
        )
        assertTrue(analysisMoved.detection.found)
        val gridMoved = analysisMoved.grid!!
        assertEquals("Vacated e2 must be EMPTY", SquareOccupancy.EMPTY, gridMoved[6 * 8 + 4])
        assertEquals("Empty e3 under black arrow must stay EMPTY", SquareOccupancy.EMPTY, gridMoved[5 * 8 + 4])
        assertEquals("Moved White pawn on e4 under black arrow must be WHITE", SquareOccupancy.WHITE, gridMoved[4 * 8 + 4])

        // Legal move matcher must cleanly recognize 1. e4!
        val match = BoardVisionAnalyzer.matchLegalMove(startBoard, gridMoved, playingAsWhite = true)
        assertNotNull("Expected 1. e4 to match cleanly under active black arrow", match)
        assertEquals("e2e4", match!!.moves.first().toUci())
    }

    @Test
    fun automaticOrientationDetectionWorksAtStartAndMidOpeningForBothColors() {
        // 1. Mid-opening game (after 1. e4 e5 2. Nf3) viewed from Black's perspective
        val midBoard = ChessBoard()
        for (uci in listOf("e2e4", "e7e5", "g1f3")) {
            val m = midBoard.generateLegalMoves().first { it.toUci() == uci }
            midBoard.pushMove(m)
        }

        val pixelsBlackBottom = renderBoardScreen(midBoard, playingAsWhite = false)
        val analysisBlack = BoardVisionAnalyzer.analyzeFrame(pixelsBlackBottom, 540, 1170)
        assertTrue(analysisBlack.detection.found)
        assertEquals(false, BoardVisionAnalyzer.inferBoardOrientation(analysisBlack.grid!!))

        // BoardTrackingSession starts assuming playingAsWhite=true, locks mid-opening -> auto-flips to Black!
        val session = BoardTrackingSession()
        val ev1 = session.onFrame(analysisBlack, ChessBoard(), currentPlayingAsWhite = true)
        assertTrue(ev1.any { it is BoardTrackingEvent.BoardVisible })
        val ev2 = session.onFrame(analysisBlack, ChessBoard(), currentPlayingAsWhite = true)
        val orientEv = ev2.filterIsInstance<BoardTrackingEvent.OrientationDetected>().firstOrNull()
        assertNotNull("Expected automatic OrientationDetected event", orientEv)
        assertFalse("Expected playingAsWhite=false for Black at bottom", orientEv!!.playingAsWhite)

        // 2. White at bottom mid-opening
        val pixelsWhiteBottom = renderBoardScreen(midBoard, playingAsWhite = true)
        val analysisWhite = BoardVisionAnalyzer.analyzeFrame(pixelsWhiteBottom, 540, 1170)
        assertTrue(analysisWhite.detection.found)
        assertEquals(true, BoardVisionAnalyzer.inferBoardOrientation(analysisWhite.grid!!))
    }

    @Test
    fun singleFrameMoveConfirmationWorksInAccessibilityModeWhileCastRequiresTwoFrames() {
        val startBoard = ChessBoard()
        val afterE4 = ChessBoard()
        val e2e4 = afterE4.generateLegalMoves().first { it.toUci() == "e2e4" }
        afterE4.pushMove(e2e4)

        val startPixels = renderBoardScreen(startBoard, playingAsWhite = true)
        val startAnalysis = BoardVisionAnalyzer.analyzeFrame(startPixels, 540, 1170)
        assertTrue(startAnalysis.detection.found)

        val e4Pixels = renderBoardScreen(afterE4, playingAsWhite = true)
        val e4Analysis = BoardVisionAnalyzer.analyzeFrame(e4Pixels, 540, 1170)
        assertTrue(e4Analysis.detection.found)

        // 1. Accessibility mode (singleFrameMoveConfirm = true): confirms start & 1. e4 in ONE frame!
        val a11ySession = BoardTrackingSession()
        val a11yStartEv = a11ySession.onFrame(
            analysis = startAnalysis,
            currentBoard = startBoard,
            currentPlayingAsWhite = true,
            singleFrameMoveConfirm = true
        )
        assertTrue(
            "Accessibility mode must detect NewGame on first frame",
            a11yStartEv.any { it is BoardTrackingEvent.NewGameDetected }
        )

        val a11yMoveEv = a11ySession.onFrame(
            analysis = e4Analysis,
            currentBoard = startBoard,
            currentPlayingAsWhite = true,
            singleFrameMoveConfirm = true
        )
        val detectedMoves = a11yMoveEv.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
        assertNotNull("Accessibility mode must confirm 1. e4 in a single frame", detectedMoves)
        assertEquals("e2e4", detectedMoves!!.moves.first().toUci())

        // 2. Fast Cast mode (singleFrameMoveConfirm = false): still requires 2 consecutive frames
        val castSession = BoardTrackingSession()
        castSession.onFrame(startAnalysis, startBoard, currentPlayingAsWhite = true, singleFrameMoveConfirm = false)
        castSession.onFrame(startAnalysis, startBoard, currentPlayingAsWhite = true, singleFrameMoveConfirm = false)

        val castFrame1 = castSession.onFrame(e4Analysis, startBoard, currentPlayingAsWhite = true, singleFrameMoveConfirm = false)
        assertTrue(
            "Cast mode should wait for 2nd frame before confirming move",
            castFrame1.none { it is BoardTrackingEvent.MovesDetected }
        )
        val castFrame2 = castSession.onFrame(e4Analysis, startBoard, currentPlayingAsWhite = true, singleFrameMoveConfirm = false)
        val castMove = castFrame2.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
        assertNotNull("Cast mode should confirm 1. e4 on 2nd frame", castMove)
        assertEquals("e2e4", castMove!!.moves.first().toUci())
    }

    @Test
    fun autoReadStatusFormatterLabelsNoRedBoxAccessibilityModeCleanly() {
        val locked = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = true,
            hasMediaProjectionGrant = true,
            boardVisibleOrLocked = true,
            playingAsWhite = true,
            fullMoveNumber = 4,
            framesCaptured = 9L,
            bestScore = 0.96f,
            lastCaptureError = null,
            tapInputMode = false,
            usingAccessibilityMode = true
        )
        assertEquals("No-Red-Box: Locked (White · move 4)", locked)

        val scanning = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = true,
            hasMediaProjectionGrant = true,
            boardVisibleOrLocked = false,
            playingAsWhite = true,
            fullMoveNumber = 1,
            framesCaptured = 3L,
            bestScore = 0.42f,
            lastCaptureError = null,
            tapInputMode = false,
            usingAccessibilityMode = true
        )
        assertEquals("No-Red-Box: Scanning (3f · best 42%) — open a game", scanning)
    }

    @Test
    fun fullFourMoveLiveGameUnderActiveBlackArrowsAndYellowHighlightsTracksCleanly() {
        // Test BOTH Classic Green/Cream AND Ocean themes across a complete 4-ply opening:
        // 1. e4 e5 2. Nf3 Nc6 — with active black arrows drawn RIGHT ON TOP of the player's
        // destination squares and Chess.com #F6F669 / #BACA2B yellow last-move highlights!
        val themes = listOf(
            Triple("Classic Green", rgb(238, 238, 210), rgb(118, 150, 86)),
            Triple("Ocean", rgb(250, 240, 230), rgb(78, 138, 170))
        )

        for ((themeName, lightRgb, darkRgb) in themes) {
            val tracked = ChessBoard()
            val truth = ChessBoard()
            val session = BoardTrackingSession()

            val e2 = Squares.parse("e2")
            val e4 = Squares.parse("e4")
            val e7 = Squares.parse("e7")
            val e5 = Squares.parse("e5")
            val g1 = Squares.parse("g1")
            val f3 = Squares.parse("f3")
            val b8 = Squares.parse("b8")
            val c6 = Squares.parse("c6")

            // Step 0: Starting position with Move-1 black arrow e2 -> e4 drawn on screen
            val arrow1 = Move(e2, e4)
            val frame0Pixels = renderBoardScreen(
                board = truth,
                playingAsWhite = true,
                lightColor = lightRgb,
                darkColor = darkRgb
            )
            drawCrispBlackArrow(frame0Pixels, 540, 1170, 0, 340, 540, arrow1, playingAsWhite = true)
            val f0 = BoardVisionAnalyzer.analyzeFrame(
                pixels = frame0Pixels,
                width = 540,
                height = 1170,
                activeArrow = arrow1,
                activeArrowPlayingAsWhite = true
            )
            assertTrue("$themeName start should detect", f0.detection.found)
            assertEquals("$themeName empty e3 under arrow1", SquareOccupancy.EMPTY, f0.grid!![5 * 8 + 4])
            assertEquals("$themeName empty e4 under arrow1", SquareOccupancy.EMPTY, f0.grid!![4 * 8 + 4])
            session.onFrame(f0, tracked, currentPlayingAsWhite = true, singleFrameMoveConfirm = true)

            // Step 1: White plays 1. e4 with yellow highlights on e2/e4 WHILE arrow1 (e2->e4) is STILL drawn!
            val m1 = truth.generateLegalMoves().first { it.toUci() == "e2e4" }
            assertTrue(truth.pushMove(m1))
            val frame1Pixels = renderBoardScreen(
                board = truth,
                playingAsWhite = true,
                lightColor = lightRgb,
                darkColor = darkRgb,
                highlightSquares = setOf(e2, e4)
            )
            drawCrispBlackArrow(frame1Pixels, 540, 1170, 0, 340, 540, arrow1, playingAsWhite = true)
            val f1 = BoardVisionAnalyzer.analyzeFrame(
                pixels = frame1Pixels,
                width = 540,
                height = 1170,
                activeArrow = arrow1,
                activeArrowPlayingAsWhite = true
            )
            assertEquals("$themeName vacated yellow e2 must be EMPTY", SquareOccupancy.EMPTY, f1.grid!![6 * 8 + 4])
            assertEquals("$themeName moved White pawn on yellow e4 under arrow1 must be WHITE", SquareOccupancy.WHITE, f1.grid!![4 * 8 + 4])
            val ev1 = session.onFrame(f1, tracked, currentPlayingAsWhite = true, singleFrameMoveConfirm = true)
            val det1 = ev1.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
            assertNotNull("$themeName must detect 1. e4 under active black arrow", det1)
            assertEquals(listOf("e2e4"), det1!!.moves.map { it.toUci() })
            det1.moves.forEach { assertTrue(tracked.pushMove(it)) }

            // Step 2: Arrow clears (My Turn Only = Black's turn); Black plays 1... e5 with yellow highlights on e7/e5
            val m2 = truth.generateLegalMoves().first { it.toUci() == "e7e5" }
            assertTrue(truth.pushMove(m2))
            val frame2Pixels = renderBoardScreen(
                board = truth,
                playingAsWhite = true,
                lightColor = lightRgb,
                darkColor = darkRgb,
                highlightSquares = setOf(e7, e5)
            )
            val f2 = BoardVisionAnalyzer.analyzeFrame(
                pixels = frame2Pixels,
                width = 540,
                height = 1170,
                activeArrow = null,
                activeArrowPlayingAsWhite = true
            )
            assertEquals("$themeName vacated yellow e7 must be EMPTY", SquareOccupancy.EMPTY, f2.grid!![1 * 8 + 4])
            assertEquals("$themeName moved Black pawn on yellow e5 must be BLACK", SquareOccupancy.BLACK, f2.grid!![3 * 8 + 4])
            val ev2 = session.onFrame(f2, tracked, currentPlayingAsWhite = true, singleFrameMoveConfirm = true)
            val det2 = ev2.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
            assertNotNull("$themeName must detect 1... e5", det2)
            assertEquals(listOf("e7e5"), det2!!.moves.map { it.toUci() })
            det2.moves.forEach { assertTrue(tracked.pushMove(it)) }

            // Step 3: White's turn — black arrow g1->f3 drawn; White plays 2. Nf3 WHILE g1->f3 arrow is STILL drawn!
            val arrow3 = Move(g1, f3)
            val m3 = truth.generateLegalMoves().first { it.toUci() == "g1f3" }
            assertTrue(truth.pushMove(m3))
            val frame3Pixels = renderBoardScreen(
                board = truth,
                playingAsWhite = true,
                lightColor = lightRgb,
                darkColor = darkRgb,
                highlightSquares = setOf(g1, f3)
            )
            drawCrispBlackArrow(frame3Pixels, 540, 1170, 0, 340, 540, arrow3, playingAsWhite = true)
            val f3Analysis = BoardVisionAnalyzer.analyzeFrame(
                pixels = frame3Pixels,
                width = 540,
                height = 1170,
                activeArrow = arrow3,
                activeArrowPlayingAsWhite = true
            )
            assertEquals("$themeName moved White knight on yellow f3 under arrow3 must be WHITE", SquareOccupancy.WHITE, f3Analysis.grid!![5 * 8 + 5])
            val ev3 = session.onFrame(f3Analysis, tracked, currentPlayingAsWhite = true, singleFrameMoveConfirm = true)
            val det3 = ev3.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
            assertNotNull("$themeName must detect 2. Nf3 under active black arrow", det3)
            assertEquals(listOf("g1f3"), det3!!.moves.map { it.toUci() })
            det3.moves.forEach { assertTrue(tracked.pushMove(it)) }

            // Step 4: Black plays 2... Nc6 (arrow hidden on Black's turn)
            val m4 = truth.generateLegalMoves().first { it.toUci() == "b8c6" }
            assertTrue(truth.pushMove(m4))
            val frame4Pixels = renderBoardScreen(
                board = truth,
                playingAsWhite = true,
                lightColor = lightRgb,
                darkColor = darkRgb,
                highlightSquares = setOf(b8, c6)
            )
            val f4 = BoardVisionAnalyzer.analyzeFrame(
                pixels = frame4Pixels,
                width = 540,
                height = 1170,
                activeArrow = null,
                activeArrowPlayingAsWhite = true
            )
            val ev4 = session.onFrame(f4, tracked, currentPlayingAsWhite = true, singleFrameMoveConfirm = true)
            val det4 = ev4.filterIsInstance<BoardTrackingEvent.MovesDetected>().firstOrNull()
            assertNotNull("$themeName must detect 2... Nc6", det4)
            assertEquals(listOf("b8c6"), det4!!.moves.map { it.toUci() })
            det4.moves.forEach { assertTrue(tracked.pushMove(it)) }
            assertEquals("$themeName final FEN must match ground truth after 4 plies", truth.toFen(), tracked.toFen())
        }
    }

    @Test
    fun playingAsBlackDetectsBlackPawnUnderActiveBlackArrow() {
        val board = ChessBoard()
        val m1 = board.generateLegalMoves().first { it.toUci() == "e2e4" }
        assertTrue(board.pushMove(m1))

        val afterE5 = board.copy()
        val m2 = afterE5.generateLegalMoves().first { it.toUci() == "e7e5" }
        assertTrue(afterE5.pushMove(m2))

        val e7 = Squares.parse("e7")
        val e5 = Squares.parse("e5")
        val arrowBlack = Move(e7, e5)

        val pixels = renderBoardScreen(
            board = afterE5,
            playingAsWhite = false,
            lightColor = rgb(238, 238, 210),
            darkColor = rgb(118, 150, 86),
            highlightSquares = setOf(e7, e5)
        )
        drawCrispBlackArrow(pixels, 540, 1170, 0, 340, 540, arrowBlack, playingAsWhite = false)

        val analysis = BoardVisionAnalyzer.analyzeFrame(
            pixels = pixels,
            width = 540,
            height = 1170,
            activeArrow = arrowBlack,
            activeArrowPlayingAsWhite = false
        )
        assertTrue(analysis.detection.found)
        // When playing as Black: file e = col (7-4) = 3, rank 5 (idx 4) = row 4
        assertEquals("Moved Black pawn on e5 under black arrow must read BLACK", SquareOccupancy.BLACK, analysis.grid!![4 * 8 + 3])
        val match = BoardVisionAnalyzer.matchLegalMove(board, analysis.grid!!, playingAsWhite = false)
        assertNotNull("Expected 1... e5 to match cleanly when playing as Black under active black arrow", match)
        assertEquals("e7e5", match!!.moves.first().toUci())
    }

    @Test
    fun matchLegalMoveAcceptsExact64FromCurrentScore63() {
        // After 1. e4 d5, if 2. exd5 is played (capture: e4->EMPTY, d5 WHITE replaces BLACK),
        // e4 and d5 both change (score=62). What if one square already looks occupied by White
        // (e.g. a noisy capture where only the vacated square differs, so currentScore == 63)?
        // Verify a unique exact 64/64 1-ply match from currentScore == 63 is accepted!
        val board = ChessBoard()
        // Setup position where White captures Black piece with 1 square already matching -> 63/64
        // Actually: a capture where only 1 square differs? Let's test directly via a board where
        // e2->e4 is NOT 63, whereas on a capture e4xd5, e4 changes WHITE->EMPTY and d5 changes BLACK->WHITE (2 sq).
        // Simulate a 1-square-difference noisy pre-frame (currentScore == 63) -> exact 64/64 legal move:
        // Wait: any legal chess move changes at least 2 squares (from & to), UNLESS the observed grid
        // has a single-square artifact that happens to match the destination already (currentScore == 63)
        // OR wait: if observedGrid equals board-after-move, currentScore vs board-before-move is 62.
        // If one of the two changed squares is untouched in observedGrid, then best1Score would be 63, not 64.
        // Verify that a 1-ply move with 1 noisy square elsewhere (currentScore = 61 -> best1Score = 63)
        // AND a 1-ply move where currentScore = 62 -> best1Score = 63 (unique best, secondBest <= 62) both match!
        val afterE4 = board.copy()
        val m1 = afterE4.generateLegalMoves().first { it.toUci() == "e2e4" }
        assertTrue(afterE4.pushMove(m1))
        val noisyGrid = BoardVisionAnalyzer.boardToScreenGrid(afterE4, playingAsWhite = true)
        // Introduce 1 transient highlight/artifact on e4 (WHITE -> EMPTY) so currentScore vs start = 63?
        // Wait: if e4 is EMPTY and e2 is EMPTY, currentScore vs start = 63, and e2e4 score = 63 (not 64).
        // Instead introduce 1 transient artifact on an unrelated empty square a4 (EMPTY -> WHITE)
        // while e2=EMPTY and e4=WHITE: currentScore = 61, best1Score(e2e4) = 63, secondBest = 61 -> must match!
        noisyGrid[4 * 8 + 0] = SquareOccupancy.WHITE
        val match = BoardVisionAnalyzer.matchLegalMove(board, noisyGrid, playingAsWhite = true)
        assertNotNull("Expected 1. e4 to match despite 1 unrelated noisy square", match)
        assertEquals("e2e4", match!!.moves.first().toUci())
        assertEquals(63, match.score)
    }

    @Test
    fun threePlyBulletCatchUpRecoversRapidOpeningAndCaptureSequences() {
        // Sequence 1: 3 quiet plies (1. e4 e5 2. Nf3) between two screenshots
        val start1 = ChessBoard()
        val target1 = start1.copy()
        for (uci in listOf("e2e4", "e7e5", "g1f3")) {
            val m = target1.generateLegalMoves().first { it.toUci() == uci }
            assertTrue(target1.pushMove(m))
        }
        val grid1 = BoardVisionAnalyzer.boardToScreenGrid(target1, playingAsWhite = true)
        val match1 = BoardVisionAnalyzer.matchLegalMove(start1, grid1, playingAsWhite = true)
        assertNotNull("3-ply catch-up must recover 1. e4 e5 2. Nf3", match1)
        assertEquals(3, match1!!.moves.size)
        assertEquals(64, match1.score)
        val verify1 = start1.copy()
        match1.moves.forEach { assertTrue(verify1.pushMove(it)) }
        assertTrue(
            BoardVisionAnalyzer.boardToScreenGrid(verify1, true).contentEquals(grid1)
        )

        // Sequence 2: 3 plies WITH a capture on the same square (1. e4 d5 2. exd5)
        val start2 = ChessBoard()
        val target2 = start2.copy()
        for (uci in listOf("e2e4", "d7d5", "e4d5")) {
            val m = target2.generateLegalMoves().first { it.toUci() == uci }
            assertTrue(target2.pushMove(m))
        }
        val grid2 = BoardVisionAnalyzer.boardToScreenGrid(target2, playingAsWhite = true)
        val match2 = BoardVisionAnalyzer.matchLegalMove(start2, grid2, playingAsWhite = true)
        assertNotNull("3-ply catch-up must recover 1. e4 d5 2. exd5 capture sequence", match2)
        assertEquals(listOf("e2e4", "d7d5", "e4d5"), match2!!.moves.map { it.toUci() })
        assertEquals(64, match2.score)
    }
}
