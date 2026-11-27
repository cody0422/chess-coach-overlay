package com.chesscoach.overlay.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

object Piece {
    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    const val WHITE = 1
    const val BLACK = -1

    fun colorOf(piece: Int): Int = when {
        piece > 0 -> WHITE
        piece < 0 -> BLACK
        else -> 0
    }

    fun typeOf(piece: Int): Int = abs(piece)

    fun make(color: Int, type: Int): Int = color * type

    fun materialValue(type: Int): Int = when (abs(type)) {
        PAWN -> 100
        KNIGHT -> 320
        BISHOP -> 330
        ROOK -> 500
        QUEEN -> 900
        KING -> 20000
        else -> 0
    }

    fun unicodeSymbol(piece: Int): String = when (piece) {
        WHITE * PAWN -> "♙"
        WHITE * KNIGHT -> "♘"
        WHITE * BISHOP -> "♗"
        WHITE * ROOK -> "♖"
        WHITE * QUEEN -> "♕"
        WHITE * KING -> "♔"
        BLACK * PAWN -> "♟"
        BLACK * KNIGHT -> "♞"
        BLACK * BISHOP -> "♝"
        BLACK * ROOK -> "♜"
        BLACK * QUEEN -> "♛"
        BLACK * KING -> "♚"
        else -> ""
    }

    fun letterSymbol(piece: Int): String = when (abs(piece)) {
        PAWN -> "P"
        KNIGHT -> "N"
        BISHOP -> "B"
        ROOK -> "R"
        QUEEN -> "Q"
        KING -> "K"
        else -> ""
    }
}

object Squares {
    fun of(file: Int, rank: Int): Int = rank * 8 + file
    fun fileOf(sq: Int): Int = sq and 7
    fun rankOf(sq: Int): Int = sq ushr 3

    fun toName(sq: Int): String {
        if (sq !in 0..63) return "-"
        val fileChar = ('a'.code + fileOf(sq)).toChar()
        val rankChar = ('1'.code + rankOf(sq)).toChar()
        return "$fileChar$rankChar"
    }

    fun parse(name: String): Int {
        if (name.length != 2) return -1
        val f = name[0].lowercaseChar() - 'a'
        val r = name[1] - '1'
        if (f !in 0..7 || r !in 0..7) return -1
        return of(f, r)
    }
}

data class Move(
    val from: Int,
    val to: Int,
    val promotion: Int = Piece.EMPTY,
    val isEnPassant: Boolean = false,
    val isCastle: Boolean = false
) {
    fun toUci(): String {
        val promo = when (abs(promotion)) {
            Piece.QUEEN -> "q"
            Piece.ROOK -> "r"
            Piece.BISHOP -> "b"
            Piece.KNIGHT -> "n"
            else -> ""
        }
        return Squares.toName(from) + Squares.toName(to) + promo
    }
}

data class ScoredMove(
    val move: Move,
    val scoreCp: Int,
    val mateIn: Int? = null
)

enum class MoveQuality(val label: String, val badgeSymbol: String, val colorArgb: Int) {
    BEST("Best", "★", 0xFF19E58F.toInt()),
    GOOD("Good", "✓", 0xFF34D399.toInt()),
    INACCURACY("Inaccuracy", "?!", 0xFFFBBF24.toInt()),
    MISTAKE("Mistake", "?", 0xFFF97316.toInt()),
    BLUNDER("Blunder", "??", 0xFFEF4444.toInt())
}

data class MoveFeedback(
    val move: Move,
    val quality: MoveQuality,
    val cpLoss: Int
)

data class PinnedPiece(
    val square: Int,
    val pinnerSquare: Int,
    val kingSquare: Int
)

enum class WarningType {
    CHECK,
    HANGING,
    THREATENED
}

data class BoardWarning(
    val square: Int,
    val type: WarningType,
    val attackerSquare: Int = -1
)

data class LineRisk(
    val attackerSquare: Int,
    val targetSquare: Int,
    val blockerSquare: Int = -1,
    val isDirectAttack: Boolean = false
)


enum class PlayStyle(val label: String, val shortTag: String) {
    DEFAULT("Default", "DEF"),
    AGGRESSIVE("Aggressive", "AGG"),
    PASSIVE("Passive", "PAS"),
    HUMAN("Human", "HUM");

    fun next(): PlayStyle {
        val vals = entries
        return vals[(ordinal + 1) % vals.size]
    }

    companion object {
        fun fromName(name: String?): PlayStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

enum class GameStatus {
    ONGOING,
    CHECKMATE_WHITE_WINS,
    CHECKMATE_BLACK_WINS,
    STALEMATE,
    DRAW_INSUFFICIENT,
    DRAW_FIFTY_MOVE
}

data class AnalysisResult(
    val topMoves: List<ScoredMove>,
    val whiteEvalCp: Int,
    val whiteMateIn: Int? = null,
    val depth: Int = 0,
    val nodes: Long = 0L,
    val engineName: String = "Local Engine"
)

object EloAccuracyHelper {
    const val MIN_ELO = 400
    const val MAX_ELO = 4000
    const val ELO_STEP = 50
    const val DEFAULT_ELO = 2000

    const val MIN_ACCURACY = 50
    const val MAX_ACCURACY = 100
    const val DEFAULT_ACCURACY = 100

    const val MIN_DEPTH = 1
    const val MAX_DEPTH = 8
    const val DEFAULT_DEPTH = 4

    const val MATE_SCORE = 29000

    fun eloTierLabel(elo: Int): String = when {
        elo >= 3800 -> "Max Engine (Full Strength)"
        elo >= 3200 -> "Super GM"
        elo >= 2800 -> "Grandmaster"
        elo >= 2400 -> "Master"
        elo >= 2000 -> "Expert"
        elo >= 1600 -> "Club Player"
        elo >= 1200 -> "Casual"
        else -> "Beginner"
    }

    fun accuracyCaption(accuracy: Int): String = when {
        accuracy >= 100 -> "Pure top engine choice"
        accuracy >= 92 -> "Sharp & precise"
        accuracy >= 82 -> "Solid human play"
        accuracy >= 70 -> "Club realistic (mixed lines)"
        else -> "Casual (frequent inaccuracies)"
    }

    fun depthCaption(depth: Int): String = when {
        depth <= 2 -> "Instant answer"
        depth <= 4 -> "Fast tactical answer"
        depth <= 6 -> "Strong calculation"
        else -> "Deep search"
    }

    fun classifyMoveQuality(
        bestEvalMover: Int,
        playedEvalMover: Int,
        playedWasTopMove: Boolean
    ): MoveQuality {
        if (playedWasTopMove) return MoveQuality.BEST
        val loss = (bestEvalMover - playedEvalMover).coerceAtLeast(0)
        return when {
            loss <= 15 -> MoveQuality.BEST
            loss <= 45 -> MoveQuality.GOOD
            loss <= 110 -> MoveQuality.INACCURACY
            loss <= 240 -> MoveQuality.MISTAKE
            else -> MoveQuality.BLUNDER
        }
    }

    /**
     * Reorders/selects the primary candidate move from a sorted best-first MultiPV list
     * according to targetElo (400..4000) and targetAccuracy (50..100).
     *
     * At targetAccuracy == 100 (and Elo >= 2800 or engineAlreadyStrengthLimited),
     * always keeps the #1 best move at index 0.
     * Below 100%, probabilistically mixes in top candidate moves using a centipawn-loss
     * budget and Boltzmann softmax temperature derived from targetAccuracy & targetElo.
     */

    /**
     * Re-ranks MultiPV candidate moves according to [playStyle] within a safe centipawn window
     * so style preferences shine without making suicidal blunders or missing forced mates.
     */
    fun applyPlayStyle(
        board: ChessBoard,
        sortedCandidates: List<ScoredMove>,
        playStyle: PlayStyle
    ): List<ScoredMove> {
        if (playStyle == PlayStyle.DEFAULT || sortedCandidates.size <= 1) {
            return sortedCandidates
        }
        val best = sortedCandidates.first()
        // Never override a forced winning mate
        if (best.mateIn != null && best.mateIn > 0) return sortedCandidates

        val mover = board.sideToMove
        val enemy = -mover
        val enemyKingSq = if (enemy == Piece.WHITE) board.whiteKingSq else board.blackKingSq
        val ekRank = if (enemyKingSq in 0..63) Squares.rankOf(enemyKingSq) else 4
        val ekFile = if (enemyKingSq in 0..63) Squares.fileOf(enemyKingSq) else 4

        val maxStyleWindow = when (playStyle) {
            PlayStyle.AGGRESSIVE -> 65
            PlayStyle.PASSIVE -> 55
            PlayStyle.HUMAN -> 40
            PlayStyle.DEFAULT -> 0
        }

        val adjusted = sortedCandidates.map { cand ->
            val loss = (best.scoreCp - cand.scoreCp).coerceAtLeast(0)
            val blundersMate = cand.mateIn != null && cand.mateIn < 0 && best.mateIn == null
            if (loss > maxStyleWindow || blundersMate) {
                cand to cand.scoreCp
            } else {
                val bonus = computeStyleBonus(board, cand.move, mover, enemy, ekRank, ekFile, playStyle)
                cand to (cand.scoreCp + bonus)
            }
        }

        return adjusted.sortedByDescending { it.second }.map { it.first }
    }

    private fun computeStyleBonus(
        board: ChessBoard,
        move: Move,
        mover: Int,
        enemy: Int,
        ekRank: Int,
        ekFile: Int,
        playStyle: PlayStyle
    ): Int {
        val movingPiece = board.squares[move.from]
        val movingType = Piece.typeOf(movingPiece)
        val isCapture = board.squares[move.to] != Piece.EMPTY || move.isEnPassant
        val fromRank = Squares.rankOf(move.from)
        val toRank = Squares.rankOf(move.to)
        val fromFile = Squares.fileOf(move.from)
        val toFile = Squares.fileOf(move.to)
        val forwardDelta = if (mover == Piece.WHITE) (toRank - fromRank) else (fromRank - toRank)

        val sim = board.copy()
        sim.makeMove(move)
        val givesCheck = sim.isInCheck(enemy)
        val destAttackedByEnemy = sim.isSquareAttacked(move.to, enemy)

        val distBefore = abs(fromRank - ekRank) + abs(fromFile - ekFile)
        val distAfter = abs(toRank - ekRank) + abs(toFile - ekFile)
        val closerToEnemyKing = distBefore - distAfter

        return when (playStyle) {
            PlayStyle.DEFAULT -> 0

            PlayStyle.AGGRESSIVE -> {
                var b = 0
                if (givesCheck) b += 38
                if (isCapture) b += 24
                if (move.promotion != Piece.EMPTY) b += 25
                if (closerToEnemyKing > 0) b += closerToEnemyKing * 7
                if (forwardDelta > 0 && movingType != Piece.KING) b += forwardDelta * 5
                // Bonus if piece now attacks an enemy non-pawn piece
                if (attacksEnemyPiece(sim, move.to, enemy)) b += 14
                b.coerceIn(-20, 62)
            }

            PlayStyle.PASSIVE -> {
                var b = 0
                if (move.isCastle) b += 40
                if (!isCapture && !givesCheck) b += 18
                if (!destAttackedByEnemy) b += 14 else b -= 16
                // Prefer solid pawn/minor moves and retreating exposed pieces
                if (board.isSquareAttacked(move.from, enemy) && !destAttackedByEnemy) b += 20
                if (movingType == Piece.PAWN && (toFile in 2..5) && forwardDelta == 1) b += 10
                if (isCapture && movingType == Piece.QUEEN) b -= 15
                b.coerceIn(-25, 52)
            }

            PlayStyle.HUMAN -> {
                var b = 0
                val homeRank = if (mover == Piece.WHITE) 0 else 7
                // Natural piece development off back rank
                if ((movingType == Piece.KNIGHT || movingType == Piece.BISHOP) && fromRank == homeRank && toRank != homeRank) {
                    b += 26
                }
                if (move.isCastle) b += 30
                // Center control (d4, e4, d5, e5, c4, f4, c5, f5)
                if (toFile in 2..5 && toRank in 2..5) b += 14
                // Natural recaptures
                if (isCapture) b += 10
                // Penalize awkward computer-style rim pawn pushes / pointless rook edge shuffles early
                if (movingType == Piece.PAWN && (toFile == 0 || toFile == 7) && board.fullMoveNumber <= 12) {
                    b -= 20
                }
                if (movingType == Piece.ROOK && !isCapture && !givesCheck && toFile in intArrayOf(0, 7) && board.fullMoveNumber <= 12) {
                    b -= 16
                }
                b.coerceIn(-25, 38)
            }
        }
    }

    private fun attacksEnemyPiece(board: ChessBoard, fromSq: Int, enemyColor: Int): Boolean {
        // Quick heuristic: check if any enemy non-pawn piece is attacked more directly
        for (sq in 0..63) {
            val p = board.squares[sq]
            if (Piece.colorOf(p) == enemyColor && Piece.typeOf(p) >= Piece.KNIGHT) {
                val (atkSq, _) = board.findLowestAttacker(sq, -enemyColor)
                if (atkSq == fromSq) return true
            }
        }
        return false
    }

    fun selectByEloAndAccuracy(
        sortedCandidates: List<ScoredMove>,
        targetElo: Int,
        targetAccuracy: Int,
        engineAlreadyStrengthLimited: Boolean = false,
        rng: Random = Random.Default
    ): List<ScoredMove> {
        if (sortedCandidates.size <= 1) return sortedCandidates

        val clampedAcc = targetAccuracy.coerceIn(MIN_ACCURACY, MAX_ACCURACY)
        val clampedElo = targetElo.coerceIn(MIN_ELO, MAX_ELO)

        val needsAccuracyNoise = clampedAcc < 100
        val needsEloNoise = !engineAlreadyStrengthLimited && clampedElo < 2600

        if (!needsAccuracyNoise && !needsEloNoise) {
            return sortedCandidates
        }

        val best = sortedCandidates.first()
        val bestScore = best.scoreCp

        // Avoid throwing away an immediate forced mate-in-1 unless accuracy is very low
        val hasForcedMateIn1 = best.mateIn != null && best.mateIn == 1
        if (hasForcedMateIn1 && clampedAcc > 55 && (engineAlreadyStrengthLimited || clampedElo >= 900)) {
            return sortedCandidates
        }

        // Compute allowed centipawn loss & softmax temperature
        val accDrop = (100 - clampedAcc) / 50.0 // 0.0 at 100%, 1.0 at 50%
        val eloDrop = if (needsEloNoise) {
            ((2600 - clampedElo).coerceAtLeast(0) / 2200.0)
        } else {
            0.0
        }

        val maxLossCp = when {
            needsAccuracyNoise && needsEloNoise -> {
                val accCap = (accDrop * accDrop * 260.0 + accDrop * 90.0)
                val eloCap = (eloDrop * eloDrop * 320.0 + eloDrop * 80.0)
                maxOf(accCap, eloCap).toInt().coerceAtLeast(15)
            }
            needsAccuracyNoise -> {
                (accDrop * accDrop * 260.0 + accDrop * 90.0).toInt().coerceAtLeast(12)
            }
            else -> {
                (eloDrop * eloDrop * 320.0 + eloDrop * 80.0).toInt().coerceAtLeast(15)
            }
        }

        // Filter candidates within loss budget (never allow stepping into opponent mate-in-1 unless accuracy <= 55)
        val pool = sortedCandidates.filterIndexed { idx, cand ->
            if (idx == 0) return@filterIndexed true
            val loss = (bestScore - cand.scoreCp).coerceAtLeast(0)
            val blundersIntoMate = cand.mateIn != null && cand.mateIn < 0 && best.mateIn == null
            if (blundersIntoMate && clampedAcc > 55) return@filterIndexed false
            loss <= maxLossCp
        }

        if (pool.size <= 1) return sortedCandidates

        // Softmax temperature: higher drop -> flatter distribution across pool
        val combinedDrop = maxOf(accDrop, eloDrop)
        val temperature = (18.0 + combinedDrop * 165.0)

        // When accuracy is < 90%, slightly temper #1 dominance so realistic 2nd/3rd moves appear
        val weights = DoubleArray(pool.size) { i ->
            val loss = (bestScore - pool[i].scoreCp).coerceAtLeast(0).toDouble()
            val baseWeight = exp(-loss / temperature)
            if (i == 0 && clampedAcc <= 85) {
                // Blend #1 weight down smoothly toward human accuracy target
                val topFactor = (0.35 + ((clampedAcc - 50) / 35.0) * 0.65).coerceIn(0.35, 1.0)
                baseWeight * topFactor
            } else {
                baseWeight
            }
        }

        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) return sortedCandidates

        var r = rng.nextDouble() * totalWeight
        var chosenIdx = 0
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0.0) {
                chosenIdx = i
                break
            }
        }

        val chosen = pool[chosenIdx]
        if (chosen.move == best.move) return sortedCandidates

        val reordered = ArrayList<ScoredMove>(sortedCandidates.size)
        reordered.add(chosen)
        for (cand in sortedCandidates) {
            if (cand.move != chosen.move) {
                reordered.add(cand)
            }
        }
        return reordered
    }
}
