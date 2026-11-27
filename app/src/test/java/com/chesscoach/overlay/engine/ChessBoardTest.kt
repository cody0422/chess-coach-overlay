package com.chesscoach.overlay.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessBoardTest {

    @Test
    fun initialPositionHas20LegalMovesAnd400AtDepth2() {
        val board = ChessBoard()
        val moves = board.generateLegalMoves()
        assertEquals(20, moves.size)

        var depth2 = 0
        for (m in moves) {
            val undo = board.makeMove(m)
            depth2 += board.generateLegalMoves().size
            board.unmakeMove(m, undo)
        }
        assertEquals(400, depth2)
        assertEquals(ChessBoard.START_FEN, board.toFen())
    }

    @Test
    fun fenRoundTripPreservesState() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val board = ChessBoard()
        assertTrue(board.loadFen(fen))
        assertEquals(fen, board.toFen())
        assertEquals(48, board.generateLegalMoves().size)
    }

    @Test
    fun castlingBlockedWhenPassingThroughCheck() {
        val board = ChessBoard()
        // Black rook on f8 attacks f1, preventing White O-O
        assertTrue(board.loadFen("5r2/8/8/8/8/8/8/4K2R w K - 0 1"))
        val moves = board.generateLegalMoves().map { it.toUci() }
        assertFalse(moves.contains("e1g1"))

        // Remove rook -> O-O is legal
        assertTrue(board.loadFen("8/8/8/8/8/8/8/4K2R w K - 0 1"))
        val movesClear = board.generateLegalMoves().map { it.toUci() }
        assertTrue(movesClear.contains("e1g1"))
    }

    @Test
    fun enPassantCaptureAndUndo() {
        val board = ChessBoard()
        assertNotNull(board.pushUciMove("e2e4"))
        assertNotNull(board.pushUciMove("a7a6"))
        assertNotNull(board.pushUciMove("e4e5"))
        assertNotNull(board.pushUciMove("d7d5"))

        val beforeEpFen = board.toFen()
        val epMove = board.pushUciMove("e5d6")
        assertNotNull(epMove)
        assertTrue(epMove!!.isEnPassant)
        assertEquals(Piece.EMPTY, board.squares[Squares.parse("d5")])
        assertEquals(Piece.WHITE * Piece.PAWN, board.squares[Squares.parse("d6")])

        board.undoLastMove()
        assertEquals(beforeEpFen, board.toFen())
    }

    @Test
    fun pawnPromotionGeneratesAllFourChoices() {
        val board = ChessBoard()
        assertTrue(board.loadFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1"))
        val promoMoves = board.generateLegalMoves().filter { it.from == Squares.parse("a7") }
        assertEquals(4, promoMoves.size)
        val ucis = promoMoves.map { it.toUci() }.toSet()
        assertEquals(setOf("a7a8q", "a7a8r", "a7a8b", "a7a8n"), ucis)
    }

    @Test
    fun checkmateAndStalemateDetection() {
        // Fool's mate
        val mateBoard = ChessBoard()
        assertNotNull(mateBoard.pushUciMove("f2f3"))
        assertNotNull(mateBoard.pushUciMove("e7e5"))
        assertNotNull(mateBoard.pushUciMove("g2g4"))
        assertNotNull(mateBoard.pushUciMove("d8h4"))
        assertEquals(GameStatus.CHECKMATE_BLACK_WINS, mateBoard.getGameStatus())

        // Classic stalemate: Black king on h8, White queen on f7, White king on g6
        val staleBoard = ChessBoard()
        assertTrue(staleBoard.loadFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"))
        assertEquals(GameStatus.STALEMATE, staleBoard.getGameStatus())
    }

    @Test
    fun pinDetectionFindsAbsolutePins() {
        val board = ChessBoard()
        // White knight on e2 pinned to White king on e1 by Black rook on e8
        assertTrue(board.loadFen("4r2k/8/8/8/8/8/4N3/4K3 w - - 0 1"))
        val pins = board.findPinnedSquares(Piece.WHITE)
        assertEquals(1, pins.size)
        assertEquals(Squares.parse("e2"), pins[0].square)
        assertEquals(Squares.parse("e8"), pins[0].pinnerSquare)
    }

    @Test
    fun boardWarningsDetectCheckAndHangingPiece() {
        val board = ChessBoard()
        // White king on e1 checked by Black rook on e8; White queen on d4 undefended and attacked by Black bishop on g7
        assertTrue(board.loadFen("4r2k/6b1/8/8/3Q4/8/8/4K3 w - - 0 1"))
        val warnings = board.findBoardWarnings(Piece.WHITE)
        assertTrue(warnings.any { it.type == WarningType.CHECK && it.square == Squares.parse("e1") })
        assertTrue(warnings.any { it.type == WarningType.HANGING && it.square == Squares.parse("d4") })
    }
}
