package com.chesscoach.overlay.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class ChessEngine {

    private val ttSize = 1 shl 16 // 65,536 entries
    private val ttMask = ttSize - 1
    private val ttKeys = LongArray(ttSize)
    private val ttScores = IntArray(ttSize)
    private val ttDepths = ByteArray(ttSize)
    private val ttFlags = ByteArray(ttSize)
    private val ttMoves = IntArray(ttSize) // packed from | (to << 6) | (promo << 12)

    private val aborted = AtomicBoolean(false)
    private var nodes: Long = 0L
    private var deadlineMs: Long = Long.MAX_VALUE
    private var nodeLimit: Long = Long.MAX_VALUE

    fun cancel() {
        aborted.set(true)
    }

    fun clearTranspositionTable() {
        ttKeys.fill(0L)
        ttFlags.fill(0)
        ttMoves.fill(0)
    }

    fun analyze(
        sourceBoard: ChessBoard,
        targetElo: Int = EloAccuracyHelper.DEFAULT_ELO,
        searchDepth: Int = EloAccuracyHelper.DEFAULT_DEPTH,
        targetAccuracy: Int = EloAccuracyHelper.DEFAULT_ACCURACY,
        playStyle: PlayStyle = PlayStyle.DEFAULT,
        multiPv: Int = 3,
        rng: Random = Random.Default
    ): AnalysisResult {
        aborted.set(false)
        nodes = 0L

        val board = sourceBoard.copy()
        val legalMoves = board.generateLegalMoves()
        if (legalMoves.isEmpty()) {
            val inCheck = board.isInCheck(board.sideToMove)
            val whiteEval = if (!inCheck) {
                0
            } else if (board.sideToMove == Piece.WHITE) {
                -EloAccuracyHelper.MATE_SCORE
            } else {
                EloAccuracyHelper.MATE_SCORE
            }
            return AnalysisResult(
                topMoves = emptyList(),
                whiteEvalCp = whiteEval,
                whiteMateIn = if (inCheck) 0 else null,
                depth = 0,
                nodes = 0L,
                engineName = "Local Engine"
            )
        }

        val clampedElo = targetElo.coerceIn(EloAccuracyHelper.MIN_ELO, EloAccuracyHelper.MAX_ELO)
        val clampedDepth = searchDepth.coerceIn(EloAccuracyHelper.MIN_DEPTH, EloAccuracyHelper.MAX_DEPTH)
        val clampedAcc = targetAccuracy.coerceIn(EloAccuracyHelper.MIN_ACCURACY, EloAccuracyHelper.MAX_ACCURACY)

        // Elo-calibrated depth & node budget (400..4000)
        val eloMaxDepth = when {
            clampedElo >= 3200 -> 8
            clampedElo >= 2600 -> 7
            clampedElo >= 2100 -> 6
            clampedElo >= 1600 -> 4
            clampedElo >= 1100 -> 3
            clampedElo >= 750 -> 2
            else -> 1
        }
        val effectiveDepth = min(clampedDepth, eloMaxDepth).coerceAtLeast(1)

        nodeLimit = when {
            clampedElo >= 3200 -> 500_000L
            clampedElo >= 2400 -> 260_000L
            clampedElo >= 1800 -> 120_000L
            clampedElo >= 1200 -> 40_000L
            else -> 8_000L
        }

        val timeBudgetMs = when {
            effectiveDepth <= 2 -> 180L
            effectiveDepth <= 4 -> 550L
            effectiveDepth <= 6 -> 1200L
            else -> 1900L
        }
        deadlineMs = System.currentTimeMillis() + timeBudgetMs

        val wantCandidates = if (clampedAcc < 100 || clampedElo < 2600 || playStyle != PlayStyle.DEFAULT) {
            max(multiPv, 5)
        } else {
            multiPv.coerceIn(1, 5)
        }

        var scoredRoot = scoreRootMovesAtDepth(board, legalMoves, 1)
        var completedDepth = 1

        for (d in 2..effectiveDepth) {
            if (shouldStop()) break
            val nextScored = scoreRootMovesAtDepth(board, scoredRoot.map { it.move }, d)
            if (!aborted.get()) {
                scoredRoot = nextScored
                completedDepth = d
                // If forced mate found on #1, no need to burn more time
                if (scoredRoot.isNotEmpty() && scoredRoot[0].mateIn != null && scoredRoot[0].mateIn!! > 0) {
                    break
                }
            } else {
                break
            }
        }

        val topPool = EloAccuracyHelper.applyPlayStyle(board, scoredRoot.take(wantCandidates), playStyle)
        val selectedOrder = EloAccuracyHelper.selectByEloAndAccuracy(
            sortedCandidates = topPool,
            targetElo = clampedElo,
            targetAccuracy = clampedAcc,
            engineAlreadyStrengthLimited = false,
            rng = rng
        ).take(multiPv.coerceAtLeast(1))

        val bestOverall = scoredRoot.first()
        val whiteEvalCp = if (board.sideToMove == Piece.WHITE) bestOverall.scoreCp else -bestOverall.scoreCp
        val whiteMateIn = bestOverall.mateIn?.let { m ->
            if (board.sideToMove == Piece.WHITE) m else -m
        }

        return AnalysisResult(
            topMoves = selectedOrder,
            whiteEvalCp = whiteEvalCp,
            whiteMateIn = whiteMateIn,
            depth = completedDepth,
            nodes = nodes,
            engineName = "Local Engine"
        )
    }

    /**
     * Quick evaluation of a specific played move relative to the position before it
     * for Move Quality feedback ("Analyze My Moves").
     */
    fun evaluateMoveQuality(
        boardBefore: ChessBoard,
        playedMove: Move,
        cachedBestBefore: ScoredMove?,
        depth: Int = 3
    ): MoveFeedback {
        val mover = boardBefore.sideToMove
        val bestScoreBefore: Int
        val wasTopMove: Boolean

        if (cachedBestBefore != null) {
            bestScoreBefore = cachedBestBefore.scoreCp
            wasTopMove = cachedBestBefore.move.from == playedMove.from &&
                cachedBestBefore.move.to == playedMove.to &&
                Piece.typeOf(cachedBestBefore.move.promotion) == Piece.typeOf(playedMove.promotion)
        } else {
            val res = analyze(
                sourceBoard = boardBefore,
                targetElo = 3600,
                searchDepth = depth.coerceIn(2, 4),
                targetAccuracy = 100,
                multiPv = 1
            )
            val best = res.topMoves.firstOrNull()
            bestScoreBefore = best?.scoreCp ?: 0
            wasTopMove = best != null &&
                best.move.from == playedMove.from &&
                best.move.to == playedMove.to &&
                Piece.typeOf(best.move.promotion) == Piece.typeOf(playedMove.promotion)
        }

        val after = boardBefore.copy()
        after.pushMove(playedMove)
        val afterRes = analyze(
            sourceBoard = after,
            targetElo = 3600,
            searchDepth = (depth - 1).coerceIn(2, 3),
            targetAccuracy = 100,
            multiPv = 1
        )
        // afterRes.whiteEvalCp is from White's perspective; convert to mover's perspective
        val playedEvalMover = if (mover == Piece.WHITE) afterRes.whiteEvalCp else -afterRes.whiteEvalCp
        val cpLoss = (bestScoreBefore - playedEvalMover).coerceAtLeast(0)
        val quality = EloAccuracyHelper.classifyMoveQuality(bestScoreBefore, playedEvalMover, wasTopMove)
        return MoveFeedback(playedMove, quality, cpLoss)
    }

    private fun scoreRootMovesAtDepth(
        board: ChessBoard,
        orderedMoves: List<Move>,
        depth: Int
    ): List<ScoredMove> {
        val results = ArrayList<ScoredMove>(orderedMoves.size)
        for (move in orderedMoves) {
            if (shouldStop() && results.isNotEmpty()) break
            val undo = board.makeMove(move)
            val score = -negamax(board, depth - 1, -INF, INF, 1)
            board.unmakeMove(move, undo)
            if (aborted.get() && results.isNotEmpty()) break
            results.add(ScoredMove(move, score, scoreToMateIn(score)))
        }
        results.sortByDescending { it.scoreCp }
        return results
    }

    private fun negamax(
        board: ChessBoard,
        depth: Int,
        alphaIn: Int,
        betaIn: Int,
        ply: Int
    ): Int {
        if (shouldStop()) return 0
        nodes++

        if (board.halfMoveClock >= 100 || board.isInsufficientMaterial()) {
            return 0
        }

        var alpha = alphaIn
        var beta = betaIn
        val hash = board.computeZobristHash()
        val ttIdx = (hash and ttMask.toLong()).toInt()
        var ttMovePacked = 0

        if (ttKeys[ttIdx] == hash) {
            ttMovePacked = ttMoves[ttIdx]
            if (ttDepths[ttIdx].toInt() >= depth) {
                val ttScore = ttScores[ttIdx]
                when (ttFlags[ttIdx].toInt()) {
                    TT_EXACT -> return ttScore
                    TT_LOWER -> if (ttScore > alpha) alpha = ttScore
                    TT_UPPER -> if (ttScore < beta) beta = ttScore
                }
                if (alpha >= beta) return ttScore
            }
        }

        val inCheck = board.isInCheck(board.sideToMove)
        val effectiveDepth = if (inCheck && ply < 10) depth + 1 else depth

        if (effectiveDepth <= 0) {
            return quiescence(board, alpha, beta, ply)
        }

        val moves = board.generateLegalMoves()
        if (moves.isEmpty()) {
            return if (inCheck) -(EloAccuracyHelper.MATE_SCORE - ply) else 0
        }

        val sortedMoves = orderMoves(board, moves, ttMovePacked)
        var bestScore = -INF
        var bestMovePacked = 0
        val origAlpha = alpha

        for (move in sortedMoves) {
            val undo = board.makeMove(move)
            val score = -negamax(board, effectiveDepth - 1, -beta, -alpha, ply + 1)
            board.unmakeMove(move, undo)

            if (aborted.get()) return 0

            if (score > bestScore) {
                bestScore = score
                bestMovePacked = packMove(move)
            }
            if (score > alpha) {
                alpha = score
            }
            if (alpha >= beta) {
                break
            }
        }

        val flag = when {
            bestScore <= origAlpha -> TT_UPPER
            bestScore >= beta -> TT_LOWER
            else -> TT_EXACT
        }
        ttKeys[ttIdx] = hash
        ttScores[ttIdx] = bestScore
        ttDepths[ttIdx] = effectiveDepth.coerceIn(0, 127).toByte()
        ttFlags[ttIdx] = flag.toByte()
        ttMoves[ttIdx] = bestMovePacked

        return bestScore
    }

    private fun quiescence(board: ChessBoard, alphaIn: Int, beta: Int, ply: Int): Int {
        if (shouldStop()) return 0
        nodes++

        val standPat = evaluate(board)
        if (standPat >= beta) return beta
        var alpha = max(alphaIn, standPat)

        if (ply >= 16) return alpha

        val captures = board.generateLegalMoves(capturesOnly = true)
        if (captures.isEmpty()) return alpha

        val sorted = orderMoves(board, captures, 0)
        for (move in sorted) {
            val undo = board.makeMove(move)
            val score = -quiescence(board, -beta, -alpha, ply + 1)
            board.unmakeMove(move, undo)
            if (aborted.get()) return 0
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun orderMoves(board: ChessBoard, moves: List<Move>, ttMovePacked: Int): List<Move> {
        if (moves.size <= 1) return moves
        return moves.sortedByDescending { m ->
            val packed = packMove(m)
            if (packed == ttMovePacked && ttMovePacked != 0) {
                1_000_000
            } else {
                var score = 0
                val victim = if (m.isEnPassant) Piece.PAWN else Piece.typeOf(board.squares[m.to])
                if (victim != Piece.EMPTY) {
                    val attacker = Piece.typeOf(board.squares[m.from])
                    score += 100_000 + Piece.materialValue(victim) * 10 - Piece.materialValue(attacker)
                }
                if (m.promotion != Piece.EMPTY) {
                    score += 80_000 + Piece.materialValue(m.promotion)
                }
                if (m.isCastle) {
                    score += 500
                }
                score
            }
        }
    }

    /**
     * PeSTO tapered middlegame/endgame evaluation, returned from side-to-move's perspective.
     */
    fun evaluate(board: ChessBoard): Int {
        var mgWhite = 0
        var mgBlack = 0
        var egWhite = 0
        var egBlack = 0
        var phase = 0
        var whiteBishops = 0
        var blackBishops = 0

        for (sq in 0..63) {
            val p = board.squares[sq]
            if (p == Piece.EMPTY) continue
            val type = Piece.typeOf(p)
            val rank = Squares.rankOf(sq)
            val file = Squares.fileOf(sq)

            phase += PHASE_WEIGHT[type]

            if (p > 0) {
                val pstIdx = (7 - rank) * 8 + file
                mgWhite += MG_VALUE[type] + MG_PST[type][pstIdx]
                egWhite += EG_VALUE[type] + EG_PST[type][pstIdx]
                if (type == Piece.BISHOP) whiteBishops++
            } else {
                val pstIdx = rank * 8 + file
                mgBlack += MG_VALUE[type] + MG_PST[type][pstIdx]
                egBlack += EG_VALUE[type] + EG_PST[type][pstIdx]
                if (type == Piece.BISHOP) blackBishops++
            }
        }

        // Bishop pair bonus
        if (whiteBishops >= 2) {
            mgWhite += 30
            egWhite += 45
        }
        if (blackBishops >= 2) {
            mgBlack += 30
            egBlack += 45
        }

        val clampedPhase = phase.coerceIn(0, 24)
        val mgScore = mgWhite - mgBlack
        val egScore = egWhite - egBlack
        val taperedWhite = (mgScore * clampedPhase + egScore * (24 - clampedPhase)) / 24

        // Small tempo bonus
        val tempo = 10
        return if (board.sideToMove == Piece.WHITE) taperedWhite + tempo else -taperedWhite + tempo
    }

    private fun shouldStop(): Boolean {
        if (aborted.get()) return true
        if (nodes >= nodeLimit) {
            aborted.set(true)
            return true
        }
        if ((nodes and 1023L) == 0L && System.currentTimeMillis() >= deadlineMs) {
            aborted.set(true)
            return true
        }
        return false
    }

    private fun packMove(m: Move): Int =
        m.from or (m.to shl 6) or (Piece.typeOf(m.promotion) shl 12)

    private fun scoreToMateIn(score: Int): Int? {
        if (abs(score) > EloAccuracyHelper.MATE_SCORE - 500) {
            val plies = (EloAccuracyHelper.MATE_SCORE - abs(score)).coerceAtLeast(1)
            val moves = (plies + 1) / 2
            return if (score > 0) moves else -moves
        }
        return null
    }

    companion object {
        private const val INF = 32000
        private const val TT_EXACT = 1
        private const val TT_LOWER = 2
        private const val TT_UPPER = 3

        private val PHASE_WEIGHT = intArrayOf(0, 0, 1, 1, 2, 4, 0)
        private val MG_VALUE = intArrayOf(0, 82, 337, 365, 477, 1025, 0)
        private val EG_VALUE = intArrayOf(0, 94, 281, 297, 512, 936, 0)

        // PeSTO Middlegame Piece-Square Tables (Rank 8 -> Rank 1 for White)
        private val MG_PAWN = intArrayOf(
              0,   0,   0,   0,   0,   0,  0,   0,
             98, 134,  61,  95,  68, 126, 34, -11,
             -6,   7,  26,  31,  65,  56, 25, -20,
            -14,  13,   6,  21,  23,  12, 17, -23,
            -27,  -2,  -5,  12,  17,   6, 10, -25,
            -26,  -4,  -4, -10,   3,   3, 33, -12,
            -35,  -1, -20, -23, -15,  24, 38, -22,
              0,   0,   0,   0,   0,   0,  0,   0
        )
        private val EG_PAWN = intArrayOf(
              0,   0,   0,   0,   0,   0,   0,   0,
            178, 173, 158, 134, 147, 132, 165, 187,
             94, 100,  85,  67,  56,  53,  82,  84,
             32,  24,  13,   5,  -2,   4,  17,  17,
             13,   9,  -3,  -7,  -7,  -8,   3,  -1,
              4,   7,  -6,   1,   0,  -5,  -1,  -8,
             13,   8,   8,  10,  13,   0,   2,  -7,
              0,   0,   0,   0,   0,   0,   0,   0
        )
        private val MG_KNIGHT = intArrayOf(
            -167, -89, -34, -49,  61, -97, -15, -107,
             -73, -41,  72,  36,  23,  62,   7,  -17,
             -47,  60,  37,  65,  84, 129,  73,   44,
              -9,  17,  19,  53,  37,  69,  18,   22,
             -13,   4,  16,  13,  28,  19,  21,   -8,
             -23,  -9,  12,  10,  19,  17,  25,  -16,
             -29, -53, -12,  -3,  -1,  18, -14,  -19,
            -105, -21, -58, -33, -17, -28, -19,  -23
        )
        private val EG_KNIGHT = intArrayOf(
            -58, -38, -13, -28, -31, -27, -63, -99,
            -25,  -8, -25,  -2,  -9, -25, -24, -52,
            -24, -20,  10,   9,  -1,  -9, -19, -41,
            -17,   3,  22,  22,  22,  11,   8, -18,
            -18,  -6,  16,  25,  16,  17,   4, -18,
            -23,  -3,  -1,  15,  10,  -3, -20, -22,
            -42, -20, -10,  -5,  -2, -20, -23, -44,
            -29, -51, -23, -15, -22, -18, -50, -64
        )
        private val MG_BISHOP = intArrayOf(
            -29,   4, -82, -37, -25, -42,   7,  -8,
            -26,  16, -18, -13,  30,  59,  18, -47,
            -16,  37,  43,  40,  35,  50,  37,  -2,
             -4,   5,  19,  50,  37,  37,   7,  -2,
             -6,  13,  13,  26,  34,  12,  10,   4,
              0,  15,  15,  15,  14,  27,  18,  10,
              4,  15,  16,   0,   7,  21,  33,   1,
            -33,  -3, -14, -21, -13, -12, -39, -21
        )
        private val EG_BISHOP = intArrayOf(
            -14, -21, -11,  -8, -7,  -9, -17, -24,
             -8,  -4,   7, -12, -3, -13,  -4, -14,
              2,  -8,   0,  -1, -2,   6,   0,   4,
             -3,   9,  12,   9, 14,  10,   3,   2,
             -6,   3,  13,  19,  7,  10,  -3,  -9,
            -12,  -3,   8,  10, 13,   3,  -7, -15,
            -14, -18,  -7,  -1,  4,  -9, -15, -27,
            -23,  -9, -23,  -5, -9, -16,  -5, -17
        )
        private val MG_ROOK = intArrayOf(
             32,  42,  32,  51, 63,  9,  31,  43,
             27,  32,  58,  62, 80, 67,  26,  44,
             -5,  19,  26,  36, 17, 45,  61,  16,
            -24, -11,   7,  26, 24, 35,  -8, -20,
            -36, -26, -12,  -1,  9, -7,   6, -23,
            -45, -25, -16, -17,  3,  0,  -5, -33,
            -44, -16, -20,  -9, -1, 11,  -6, -71,
            -19, -13,   1,  17, 16,  7, -37, -26
        )
        private val EG_ROOK = intArrayOf(
            13, 10, 18, 15, 12,  12,   8,   5,
            11, 13, 13, 11, -3,   3,   8,   3,
             7,  7,  7,  5,  4,  -3,  -5,  -3,
             4,  3, 13,  1,  2,   1,  -1,   2,
             3,  5,  8,  4, -5,  -6,  -8, -11,
            -4,  0, -5, -1, -7, -12,  -8, -16,
            -6, -6,  0,  2, -9,  -9, -11,  -3,
            -9,  2,  3, -1, -5, -13,   4, -20
        )
        private val MG_QUEEN = intArrayOf(
            -28,   0,  29,  12,  59,  44,  43,  45,
            -24, -39,  -5,   1, -16,  57,  28,  54,
            -13, -17,   7,   8,  29,  56,  47,  57,
            -27, -27, -16, -16,  -1,  17,  -2,   1,
             -9, -26,  -9, -10,  -2,  -4,   3,  -3,
            -14,   2, -11,  -2,  -5,   2,  14,   5,
            -35,  -8,  11,   2,   8,  15,  -3,   1,
             -1, -18,  -9,  10, -15, -25, -31, -50
        )
        private val EG_QUEEN = intArrayOf(
             -9,  22,  22,  27,  27,  19,  10,  20,
            -17,  20,  32,  41,  58,  25,  30,   0,
            -20,   6,   9,  49,  47,  35,  19,   9,
              3,  22,  24,  45,  57,  40,  57,  36,
            -18,  28,  19,  47,  31,  34,  39,  23,
            -16, -27,  15,   6,   9,  17,  10,   5,
            -22, -23, -30, -16, -16, -23, -36, -32,
            -33, -28, -22, -43,  -5, -32, -20, -41
        )
        private val MG_KING = intArrayOf(
            -65,  23,  16, -15, -56, -34,   2,  13,
             29,  -1, -20,  -7,  -8,  -4, -38, -29,
             -9,  24,   2, -16, -20,   6,  22, -22,
            -17, -20, -12, -27, -30, -25, -14, -36,
            -49,  -1, -27, -39, -46, -44, -33, -51,
            -14, -14, -22, -46, -44, -30, -15, -27,
              1,   7,  -8, -64, -43, -16,   9,   8,
            -15,  36,  12, -54,   8, -28,  24,  14
        )
        private val EG_KING = intArrayOf(
            -74, -35, -18, -18, -11,  15,   4, -17,
            -12,  17,  14,  17,  17,  38,  23,  11,
             10,  17,  23,  15,  20,  45,  44,  13,
             -8,  22,  24,  27,  26,  33,  26,   3,
            -18,  -4,  21,  24,  27,  23,   9, -11,
            -19,  -3,  11,  21,  23,  16,   7,  -9,
            -27, -11,   4,  13,  14,   4,  -5, -17,
            -53, -34, -21, -11, -28, -14, -24, -43
        )

        private val EMPTY_PST = IntArray(64)
        private val MG_PST = arrayOf(EMPTY_PST, MG_PAWN, MG_KNIGHT, MG_BISHOP, MG_ROOK, MG_QUEEN, MG_KING)
        private val EG_PST = arrayOf(EMPTY_PST, EG_PAWN, EG_KNIGHT, EG_BISHOP, EG_ROOK, EG_QUEEN, EG_KING)
    }
}
