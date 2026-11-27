package com.chesscoach.overlay.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun turnArrowPolicyHidesOpponentArrowsForBothWhiteAndBlack() {
        val board = ChessBoard()
        val sampleMove = board.generateLegalMoves().first { it.toUci() == "e2e4" }
        val sampleAnalysis = AnalysisResult(
            topMoves = listOf(ScoredMove(sampleMove, 35)),
            whiteEvalCp = 35,
            whiteMateIn = null,
            depth = 12,
            nodes = 50000L,
            engineName = "Stockfish 19"
        )

        // Playing as White, start position (White to move) -> SHOW
        assertTrue(TurnArrowPolicy.shouldShowArrow(myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = true))
        assertNotNull(TurnArrowPolicy.filterAnalysisForDisplay(sampleAnalysis, myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = true))

        // Playing as Black, start position (White to move = opponent!) -> HIDE
        assertFalse(TurnArrowPolicy.shouldShowArrow(myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = false))
        assertNull(TurnArrowPolicy.filterAnalysisForDisplay(sampleAnalysis, myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = false))

        // After 1. e4 -> Black to move
        board.pushMove(sampleMove)
        assertEquals(Piece.BLACK, board.sideToMove)

        // Playing as White -> opponent's turn -> HIDE
        assertFalse(TurnArrowPolicy.shouldShowArrow(myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = true))
        assertNull(TurnArrowPolicy.filterAnalysisForDisplay(sampleAnalysis, myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = true))

        // Playing as Black -> our turn -> SHOW
        assertTrue(TurnArrowPolicy.shouldShowArrow(myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = false))
        assertNotNull(TurnArrowPolicy.filterAnalysisForDisplay(sampleAnalysis, myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = false))

        // After 1... e5 -> White to move again
        val e5 = board.generateLegalMoves().first { it.toUci() == "e7e5" }
        board.pushMove(e5)
        assertTrue(TurnArrowPolicy.shouldShowArrow(myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = true))
        assertFalse(TurnArrowPolicy.shouldShowArrow(myTurnOnly = true, sideToMove = board.sideToMove, playingAsWhite = false))

        // When myTurnOnly = false -> always SHOW on both turns
        assertTrue(TurnArrowPolicy.shouldShowArrow(myTurnOnly = false, sideToMove = Piece.WHITE, playingAsWhite = false))
        assertTrue(TurnArrowPolicy.shouldShowArrow(myTurnOnly = false, sideToMove = Piece.BLACK, playingAsWhite = true))
    }

    @Test
    fun stockfishUciParserStreamsInterimMovesAtDepth7WithThrottleAndMoveChange() {
        val board = ChessBoard()
        val legalMoves = board.generateLegalMoves()

        val lineD6 = "info depth 6 seldepth 8 multipv 1 score cp 28 nodes 4200 pv e2e4 e7e5"
        val parsed6 = StockfishUciParser.parseInfoLine(lineD6, legalMoves)
        assertNotNull(parsed6)
        assertEquals(6, parsed6!!.depth)
        assertEquals("e2e4", parsed6.scoredMove.move.toUci())
        assertEquals(28, parsed6.scoredMove.scoreCp)

        // Below depth 7 -> never emit interim
        assertFalse(
            StockfishUciParser.shouldEmitInterim(
                depth = 6,
                topMoveUci = "e2e4",
                lastEmittedMoveUci = null,
                nowMs = 1000L,
                lastEmitMs = 0L
            )
        )

        // First emit at depth 7 -> emit immediately!
        assertTrue(
            StockfishUciParser.shouldEmitInterim(
                depth = 7,
                topMoveUci = "e2e4",
                lastEmittedMoveUci = null,
                nowMs = 1050L,
                lastEmitMs = 0L
            )
        )

        // Same move 30ms later -> throttled
        assertFalse(
            StockfishUciParser.shouldEmitInterim(
                depth = 8,
                topMoveUci = "e2e4",
                lastEmittedMoveUci = "e2e4",
                nowMs = 1080L,
                lastEmitMs = 1050L
            )
        )

        // Same move 80ms later -> allowed
        assertTrue(
            StockfishUciParser.shouldEmitInterim(
                depth = 9,
                topMoveUci = "e2e4",
                lastEmittedMoveUci = "e2e4",
                nowMs = 1130L,
                lastEmitMs = 1050L
            )
        )

        // Top move changed just 15ms later -> emit immediately without waiting for 70ms throttle!
        assertTrue(
            StockfishUciParser.shouldEmitInterim(
                depth = 10,
                topMoveUci = "d2d4",
                lastEmittedMoveUci = "e2e4",
                nowMs = 1145L,
                lastEmitMs = 1130L
            )
        )

        // Build AnalysisResult from parsed PV map
        val pvMap = mapOf(1 to parsed6.scoredMove)
        val built = StockfishUciParser.buildAnalysisResult(
            sourceBoard = board,
            pvByRank = pvMap,
            highestDepth = 10,
            lastNodes = 90000L,
            clampedElo = 3500,
            clampedAcc = 100,
            playStyle = PlayStyle.DEFAULT,
            multiPv = 3,
            legalMoves = legalMoves
        )
        assertNotNull(built)
        assertEquals("Stockfish 19", built!!.engineName)
        assertEquals("e2e4", built.topMoves.first().move.toUci())
        assertEquals(28, built.whiteEvalCp)
    }
}
