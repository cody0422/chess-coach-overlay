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
        emeraldArrowSquares: Set<Int> = emptySet()
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
                    fillRect(pixels, screenW, screenH, x0 + 3, y0 + 3, x0 + 9, y0 + 12, if (isLight) darkColor else lightColor)
                }
                if (r == 7) {
                    fillRect(pixels, screenW, screenH, x1 - 10, y1 - 12, x1 - 3, y1 - 3, if (isLight) darkColor else lightColor)
                }

                // Draw realistic piece silhouette (white = bright white fill with dark outline; black = dark charcoal)
                val piece = board.squares[chessSq]
                if (piece != Piece.EMPTY) {
                    val cx = (x0 + x1) / 2
                    val cy = (y0 + y1) / 2
                    val rad = (sq * 0.30f).toInt().coerceAtLeast(8)
                    val innerRad = (sq * 0.22f).toInt().coerceAtLeast(5)
                    if (piece > 0) {
                        // White piece: thin dark outline + crisp white interior
                        fillRect(pixels, screenW, screenH, cx - rad, cy - rad, cx + rad, cy + rad, rgb(55, 55, 55))
                        fillRect(pixels, screenW, screenH, cx - innerRad, cy - innerRad, cx + innerRad, cy + innerRad, rgb(248, 248, 248))
                    } else {
                        // Black piece: dark charcoal silhouette with subtle highlight
                        fillRect(pixels, screenW, screenH, cx - rad, cy - rad, cx + rad, cy + rad, rgb(42, 42, 42))
                        fillRect(pixels, screenW, screenH, cx - innerRad / 2, cy - innerRad / 2, cx + innerRad / 2, cy + innerRad / 2, rgb(78, 78, 78))
                    }
                }

                // Optional simulated emerald overlay arrow drawn across the square center
                if (chessSq in emeraldArrowSquares) {
                    val cx = (x0 + x1) / 2
                    val cy = (y0 + y1) / 2
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
        fillRect(pixels, w, h, 24, 960, w - 24, 1040, rgb(129, 182, 76)) // Green Play button

        // Tiny 4x4 decorative avatar chessboard (width = 72px << 85% of screen)
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
    fun detectsStartingPositionWhiteAtBottom() {
        val board = ChessBoard()
        val pixels = renderBoardScreen(board, playingAsWhite = true, boardSize = 540, boardTop = 340)

        val analysis = BoardVisionAnalyzer.analyzeFrame(pixels, 540, 1170)
        assertTrue("Expected board to be detected", analysis.detection.found)
        assertTrue("Expected high confidence", analysis.detection.confidence > 0.70f)
        assertTrue("Expected top near 340, got ${analysis.detection.rect.top}", abs(analysis.detection.rect.top - 340) <= 6)
        assertTrue("Expected size near 540, got ${analysis.detection.rect.width}", abs(analysis.detection.rect.width - 540) <= 8)

        val grid = analysis.grid
        assertNotNull(grid)
        val start = BoardVisionAnalyzer.detectStartingPosition(grid!!)
        assertTrue("Expected starting position", start.isStartPosition)
        assertTrue("Expected playingAsWhite=true", start.playingAsWhite)
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
            darkColor = rgb(181, 136, 99)
        )

        val analysis = BoardVisionAnalyzer.analyzeFrame(pixels, 540, 1170)
        assertTrue("Expected wood board to be detected", analysis.detection.found)
        val start = BoardVisionAnalyzer.detectStartingPosition(analysis.grid!!)
        assertTrue("Expected starting position", start.isStartPosition)
        assertFalse("Expected playingAsWhite=false", start.playingAsWhite)
    }

    @Test
    fun tracksOpeningMovesWithHighlightsAndEmeraldOverlayArrow() {
        val liveBoard = ChessBoard()
        val trackedBoard = ChessBoard()

        // First verify unchanged board WITH emerald arrow over empty squares e3 & e4 does NOT trigger a move
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

        // Frame 1: becomes visible, waiting for 2nd stable frame before NewGameDetected
        val ev1 = session.onFrame(startFrame, board, currentPlayingAsWhite = true)
        assertTrue(ev1.any { it is BoardTrackingEvent.BoardVisible })
        assertFalse(ev1.any { it is BoardTrackingEvent.NewGameDetected })

        // Frame 2: stable start confirmed
        val ev2 = session.onFrame(startFrame, board, currentPlayingAsWhite = true)
        assertTrue(ev2.any { it is BoardTrackingEvent.NewGameDetected })

        // Play 1. e4 on live screen
        val afterE4 = board.copy().apply { pushUciMove("e2e4") }
        val e4Frame = BoardVisionAnalyzer.analyzeFrame(
            renderBoardScreen(afterE4, playingAsWhite = true),
            540,
            1170
        )

        // Single frame must NOT fire move yet (prevents mid-drag glitches)
        val evDrag = session.onFrame(e4Frame, board, currentPlayingAsWhite = true)
        assertFalse(evDrag.any { it is BoardTrackingEvent.MovesDetected })

        // Second consecutive stable frame fires e2e4!
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
        // White king on e1, White pawn on e2, Black rook on e8 -> x-ray line risk onto e1!
        assertTrue(board.loadFen("4r1k1/8/8/8/8/8/4P3/4K3 w - - 0 1"))
        val risks = board.findLineRisks(Piece.WHITE)
        assertEquals(1, risks.size)
        assertEquals(Squares.parse("e8"), risks[0].attackerSquare)
        assertEquals(Squares.parse("e1"), risks[0].targetSquare)
        assertEquals(Squares.parse("e2"), risks[0].blockerSquare)
        assertFalse(risks[0].isDirectAttack)
    }
}
