package com.chesscoach.overlay.vision

import com.chesscoach.overlay.engine.ChessBoard
import com.chesscoach.overlay.engine.Piece
import com.chesscoach.overlay.engine.Squares
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
}
