package com.chesscoach.overlay.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChessEngineTest {

    @Test
    fun findsMateInOne() {
        val board = ChessBoard()
        // White queen on g6, White king on f6, Black king on h8 -> Qg7# is mate in 1
        assertTrue(board.loadFen("7k/8/5KQ1/8/8/8/8/8 w - - 0 1"))
        val engine = ChessEngine()
        val result = engine.analyze(
            sourceBoard = board,
            targetElo = 3600,
            searchDepth = 3,
            targetAccuracy = 100
        )
        assertTrue(result.topMoves.isNotEmpty())
        val best = result.topMoves.first()
        assertEquals("g6g7", best.move.toUci())
        assertEquals(1, best.mateIn)
    }

    @Test
    fun capturesFreeQueenAtMaxStrength() {
        val board = ChessBoard()
        // White rook on d1 can capture undefended Black queen on d7
        assertTrue(board.loadFen("4k3/3q4/8/8/8/8/8/3RK3 w - - 0 1"))
        val engine = ChessEngine()
        val result = engine.analyze(
            sourceBoard = board,
            targetElo = 4000,
            searchDepth = 3,
            targetAccuracy = 100
        )
        assertTrue(result.topMoves.isNotEmpty())
        assertEquals("d1d7", result.topMoves.first().move.toUci())
    }

    @Test
    fun accuracy100AlwaysPicksTopCandidateWhileLowerAccuracyBlendsCandidates() {
        val m1 = ScoredMove(Move(Squares.parse("e2"), Squares.parse("e4")), 100)
        val m2 = ScoredMove(Move(Squares.parse("d2"), Squares.parse("d4")), 75)
        val m3 = ScoredMove(Move(Squares.parse("g1"), Squares.parse("f3")), 55)
        val m4 = ScoredMove(Move(Squares.parse("b1"), Squares.parse("c3")), 20)
        val candidates = listOf(m1, m2, m3, m4)

        // At 100% accuracy and 3200 Elo, always picks #1
        for (seed in 1..25) {
            val res = EloAccuracyHelper.selectByEloAndAccuracy(
                sortedCandidates = candidates,
                targetElo = 3200,
                targetAccuracy = 100,
                engineAlreadyStrengthLimited = true,
                rng = Random(seed)
            )
            assertEquals(m1.move, res.first().move)
        }

        // At 60% accuracy, over 60 trials should mix in alternative candidates
        var nonTopCount = 0
        for (seed in 1..60) {
            val res = EloAccuracyHelper.selectByEloAndAccuracy(
                sortedCandidates = candidates,
                targetElo = 3200,
                targetAccuracy = 60,
                engineAlreadyStrengthLimited = true,
                rng = Random(seed)
            )
            if (res.first().move != m1.move) nonTopCount++
        }
        assertTrue("Expected non-top candidate picks at 60% accuracy, got $nonTopCount", nonTopCount > 5)
    }

    @Test
    fun playStyleReranksWithinSafeWindow() {
        val board = ChessBoard()
        // White has e1-g1 castling (quiet/passive) and d1-h5 check (aggressive) with similar eval
        assertTrue(board.loadFen("r1bqk2r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"))
        val quietCastle = ScoredMove(Move(Squares.parse("e1"), Squares.parse("g1"), isCastle = true), 35)
        val aggressiveCapture = ScoredMove(Move(Squares.parse("c4"), Squares.parse("f7")), 30)
        val cands = listOf(quietCastle, aggressiveCapture)

        val aggOrder = EloAccuracyHelper.applyPlayStyle(board, cands, PlayStyle.AGGRESSIVE)
        assertEquals(aggressiveCapture.move, aggOrder.first().move)

        val pasOrder = EloAccuracyHelper.applyPlayStyle(board, listOf(aggressiveCapture, quietCastle), PlayStyle.PASSIVE)
        assertEquals(quietCastle.move, pasOrder.first().move)
    }

    @Test
    fun eloTierLabelsCover400To4000() {
        assertEquals("Beginner", EloAccuracyHelper.eloTierLabel(400))
        assertEquals("Casual", EloAccuracyHelper.eloTierLabel(1200))
        assertEquals("Club Player", EloAccuracyHelper.eloTierLabel(1600))
        assertEquals("Expert", EloAccuracyHelper.eloTierLabel(2000))
        assertEquals("Master", EloAccuracyHelper.eloTierLabel(2400))
        assertEquals("Grandmaster", EloAccuracyHelper.eloTierLabel(2800))
        assertEquals("Super GM", EloAccuracyHelper.eloTierLabel(3200))
        assertEquals("Max Engine (Full Strength)", EloAccuracyHelper.eloTierLabel(4000))
    }

    @Test
    fun moveQualityClassifiesCorrectly() {
        assertEquals(MoveQuality.BEST, EloAccuracyHelper.classifyMoveQuality(100, 100, true))
        assertEquals(MoveQuality.GOOD, EloAccuracyHelper.classifyMoveQuality(100, 70, false))
        assertEquals(MoveQuality.INACCURACY, EloAccuracyHelper.classifyMoveQuality(100, 20, false))
        assertEquals(MoveQuality.MISTAKE, EloAccuracyHelper.classifyMoveQuality(100, -80, false))
        assertEquals(MoveQuality.BLUNDER, EloAccuracyHelper.classifyMoveQuality(100, -250, false))
    }

    @Test
    fun engineAnalyzeReturnsValidMoveFromStartPos() {
        val board = ChessBoard()
        val engine = ChessEngine()
        val res = engine.analyze(
            sourceBoard = board,
            targetElo = 2000,
            searchDepth = 2,
            targetAccuracy = 95,
            playStyle = PlayStyle.HUMAN
        )
        assertNotNull(res.topMoves.firstOrNull())
        assertTrue(board.generateLegalMoves().contains(res.topMoves.first().move))
    }
}
