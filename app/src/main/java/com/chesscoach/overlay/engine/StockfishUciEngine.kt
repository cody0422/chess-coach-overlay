package com.chesscoach.overlay.engine

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.random.Random

/**
 * Wraps the optional bundled native Stockfish UCI binary (`libstockfish.so` in nativeLibraryDir)
 * and seamlessly falls back to the pure-Kotlin [ChessEngine] whenever the binary is absent
 * or encounters any runtime error.
 */
class StockfishUciEngine(private val context: Context) {

    private val fallbackEngine = ChessEngine()
    private val ioLock = Any()
    private val searching = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var stockfishFailed = false

    @Volatile
    private var initialized = false

    @Volatile
    var activeEngineName: String = "Local Engine"
        private set

    fun hasBinary(): Boolean {
        val f = File(context.applicationInfo.nativeLibraryDir, STOCKFISH_LIB_NAME)
        return f.exists() && f.length() > 0L
    }

    fun ensureInitialized(): Boolean {
        if (stockfishFailed) return false
        synchronized(ioLock) {
            if (initialized && process?.isAlive == true) return true
            return startStockfishLocked()
        }
    }

    private fun startStockfishLocked(): Boolean {
        val sfFile = File(context.applicationInfo.nativeLibraryDir, STOCKFISH_LIB_NAME)
        if (!sfFile.exists() || sfFile.length() == 0L) {
            activeEngineName = "Local Engine"
            return false
        }
        return try {
            if (!sfFile.canExecute()) {
                sfFile.setExecutable(true, false)
            }
            val pb = ProcessBuilder(sfFile.absolutePath)
                .redirectErrorStream(true)
            val proc = pb.start()
            val w = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val r = BufferedReader(InputStreamReader(proc.inputStream))

            process = proc
            writer = w
            reader = r

            sendLineLocked("uci")
            val gotUciOk = waitForTokenLocked("uciok", 4000L)
            if (!gotUciOk) {
                destroyProcessLocked()
                stockfishFailed = true
                activeEngineName = "Local Engine"
                return false
            }

            sendLineLocked("setoption name Threads value 2")
            sendLineLocked("setoption name Hash value 32")
            sendLineLocked("setoption name MultiPV value 3")
            sendLineLocked("isready")
            val ready = waitForTokenLocked("readyok", 4000L)
            if (!ready) {
                destroyProcessLocked()
                stockfishFailed = true
                activeEngineName = "Local Engine"
                return false
            }

            initialized = true
            activeEngineName = "Stockfish 19"
            true
        } catch (_: Throwable) {
            destroyProcessLocked()
            stockfishFailed = true
            activeEngineName = "Local Engine"
            false
        }
    }

    fun cancel() {
        fallbackEngine.cancel()
        if (searching.get()) {
            try {
                writer?.let {
                    it.write("stop\n")
                    it.flush()
                }
            } catch (_: Throwable) {
                // Ignore cancellation write failure
            }
        }
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
        val legalMoves = sourceBoard.generateLegalMoves()
        if (legalMoves.isEmpty()) {
            return fallbackEngine.analyze(sourceBoard, targetElo, searchDepth, targetAccuracy, playStyle, multiPv, rng)
        }

        if (!stockfishFailed && ensureInitialized()) {
            val sfResult = analyzeWithStockfish(
                sourceBoard = sourceBoard,
                legalMoves = legalMoves,
                targetElo = targetElo,
                searchDepth = searchDepth,
                targetAccuracy = targetAccuracy,
                playStyle = playStyle,
                multiPv = multiPv,
                rng = rng
            )
            if (sfResult != null) {
                return sfResult
            }
        }

        activeEngineName = "Local Engine"
        return fallbackEngine.analyze(sourceBoard, targetElo, searchDepth, targetAccuracy, playStyle, multiPv, rng)
    }

    fun evaluateMoveQuality(
        boardBefore: ChessBoard,
        playedMove: Move,
        cachedBestBefore: ScoredMove?,
        depth: Int = 3
    ): MoveFeedback {
        return fallbackEngine.evaluateMoveQuality(boardBefore, playedMove, cachedBestBefore, depth)
    }

    private fun analyzeWithStockfish(
        sourceBoard: ChessBoard,
        legalMoves: List<Move>,
        targetElo: Int,
        searchDepth: Int,
        targetAccuracy: Int,
        playStyle: PlayStyle,
        multiPv: Int,
        rng: Random
    ): AnalysisResult? {
        synchronized(ioLock) {
            if (process?.isAlive != true) {
                if (!startStockfishLocked()) return null
            }

            val clampedElo = targetElo.coerceIn(EloAccuracyHelper.MIN_ELO, EloAccuracyHelper.MAX_ELO)
            val clampedDepth = searchDepth.coerceIn(EloAccuracyHelper.MIN_DEPTH, EloAccuracyHelper.MAX_DEPTH)
            val clampedAcc = targetAccuracy.coerceIn(EloAccuracyHelper.MIN_ACCURACY, EloAccuracyHelper.MAX_ACCURACY)

            val wantPv = if (clampedAcc < 100 || playStyle != PlayStyle.DEFAULT) max(multiPv, 5) else multiPv.coerceIn(1, 5)
            val actualPv = wantPv.coerceAtMost(legalMoves.size).coerceAtLeast(1)

            return try {
                searching.set(true)

                sendLineLocked("setoption name MultiPV value $actualPv")

                // Configure Stockfish strength for 400..4000 Elo
                when {
                    clampedElo > 3190 -> {
                        sendLineLocked("setoption name UCI_LimitStrength value false")
                        sendLineLocked("setoption name Skill Level value 20")
                    }
                    clampedElo >= 1320 -> {
                        sendLineLocked("setoption name UCI_LimitStrength value true")
                        sendLineLocked("setoption name UCI_Elo value $clampedElo")
                    }
                    else -> {
                        // Below Stockfish UCI_Elo floor (1320): scale Skill Level 0..5
                        val skill = ((clampedElo - 400) * 5 / 920).coerceIn(0, 5)
                        sendLineLocked("setoption name UCI_LimitStrength value false")
                        sendLineLocked("setoption name Skill Level value $skill")
                    }
                }

                sendLineLocked("isready")
                if (!waitForTokenLocked("readyok", 3000L)) {
                    throw IllegalStateException("Stockfish not ready")
                }

                sendLineLocked("position fen ${sourceBoard.toFen()}")

                // Map depth slider (1..8) + Elo to Stockfish depth & safe movetime cap
                val sfDepth = when {
                    clampedElo < 900 -> clampedDepth.coerceIn(1, 4)
                    clampedElo < 1320 -> (clampedDepth + 1).coerceIn(2, 6)
                    clampedElo >= 3200 -> (clampedDepth * 2 + 2).coerceIn(4, 18)
                    else -> (clampedDepth + 4).coerceIn(3, 14)
                }
                val moveTimeMs = when {
                    clampedDepth <= 2 -> 200
                    clampedDepth <= 4 -> 550
                    clampedDepth <= 6 -> 1200
                    else -> 2200
                }

                sendLineLocked("go depth $sfDepth movetime $moveTimeMs")

                val pvByRank = HashMap<Int, ScoredMove>()
                var highestDepth = 0
                var lastNodes = 0L
                var bestMoveUci: String? = null
                val hardDeadline = System.currentTimeMillis() + moveTimeMs + 3500L

                val r = reader ?: return null
                while (System.currentTimeMillis() < hardDeadline) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("info ") && line.contains(" pv ")) {
                        val parsed = parseInfoLine(line, legalMoves)
                        if (parsed != null) {
                            if (parsed.depth > highestDepth) highestDepth = parsed.depth
                            if (parsed.nodes > lastNodes) lastNodes = parsed.nodes
                            pvByRank[parsed.multiPv] = parsed.scoredMove
                        }
                    } else if (line.startsWith("bestmove ")) {
                        val tokens = line.split(' ')
                        bestMoveUci = tokens.getOrNull(1)
                        break
                    }
                }

                searching.set(false)

                val sortedCandidates = pvByRank.entries
                    .sortedBy { it.key }
                    .map { it.value }
                    .distinctBy { it.move.toUci() }
                    .sortedByDescending { it.scoreCp }
                    .toMutableList()

                if (sortedCandidates.isEmpty() && bestMoveUci != null) {
                    val matched = matchUciMove(bestMoveUci, legalMoves)
                    if (matched != null) {
                        sortedCandidates.add(ScoredMove(matched, 0, null))
                    }
                }

                if (sortedCandidates.isEmpty()) return null

                val styledCandidates = EloAccuracyHelper.applyPlayStyle(sourceBoard, sortedCandidates, playStyle)
                val selectedOrder = EloAccuracyHelper.selectByEloAndAccuracy(
                    sortedCandidates = styledCandidates,
                    targetElo = clampedElo,
                    targetAccuracy = clampedAcc,
                    engineAlreadyStrengthLimited = (clampedElo <= 3190),
                    rng = rng
                ).take(multiPv.coerceAtLeast(1))

                val bestOverall = sortedCandidates.first()
                val whiteEvalCp = if (sourceBoard.sideToMove == Piece.WHITE) {
                    bestOverall.scoreCp
                } else {
                    -bestOverall.scoreCp
                }
                val whiteMateIn = bestOverall.mateIn?.let { m ->
                    if (sourceBoard.sideToMove == Piece.WHITE) m else -m
                }

                AnalysisResult(
                    topMoves = selectedOrder,
                    whiteEvalCp = whiteEvalCp,
                    whiteMateIn = whiteMateIn,
                    depth = highestDepth.coerceAtLeast(1),
                    nodes = lastNodes,
                    engineName = "Stockfish 19"
                )
            } catch (_: Throwable) {
                searching.set(false)
                destroyProcessLocked()
                stockfishFailed = true
                null
            }
        }
    }

    private data class ParsedInfo(
        val multiPv: Int,
        val depth: Int,
        val nodes: Long,
        val scoredMove: ScoredMove
    )

    private fun parseInfoLine(line: String, legalMoves: List<Move>): ParsedInfo? {
        val tokens = line.trim().split(Regex("\\s+"))
        var depth = 0
        var multiPv = 1
        var nodes = 0L
        var scoreCp = 0
        var mateIn: Int? = null
        var hasScore = false
        var firstPvMove: String? = null

        var i = 1
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> {
                    depth = tokens.getOrNull(i + 1)?.toIntOrNull() ?: depth
                    i += 2
                }
                "multipv" -> {
                    multiPv = tokens.getOrNull(i + 1)?.toIntOrNull() ?: 1
                    i += 2
                }
                "nodes" -> {
                    nodes = tokens.getOrNull(i + 1)?.toLongOrNull() ?: nodes
                    i += 2
                }
                "score" -> {
                    val kind = tokens.getOrNull(i + 1)
                    val value = tokens.getOrNull(i + 2)?.toIntOrNull()
                    if (kind == "cp" && value != null) {
                        scoreCp = value
                        mateIn = null
                        hasScore = true
                        i += 3
                    } else if (kind == "mate" && value != null) {
                        mateIn = value
                        scoreCp = if (value > 0) {
                            EloAccuracyHelper.MATE_SCORE - value * 2
                        } else {
                            -(EloAccuracyHelper.MATE_SCORE + value * 2)
                        }
                        hasScore = true
                        i += 3
                    } else {
                        i++
                    }
                }
                "pv" -> {
                    firstPvMove = tokens.getOrNull(i + 1)
                    break
                }
                else -> i++
            }
        }

        if (!hasScore || firstPvMove == null) return null
        val move = matchUciMove(firstPvMove, legalMoves) ?: return null
        return ParsedInfo(
            multiPv = multiPv,
            depth = depth,
            nodes = nodes,
            scoredMove = ScoredMove(move, scoreCp, mateIn)
        )
    }

    private fun matchUciMove(uci: String, legalMoves: List<Move>): Move? {
        val clean = uci.trim().lowercase()
        return legalMoves.firstOrNull { it.toUci() == clean }
    }

    private fun sendLineLocked(cmd: String) {
        val w = writer ?: throw IllegalStateException("Stockfish writer closed")
        w.write(cmd)
        w.write("\n")
        w.flush()
    }

    private fun waitForTokenLocked(token: String, timeoutMs: Long): Boolean {
        val r = reader ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = r.readLine() ?: return false
            if (line.trim() == token) return true
        }
        return false
    }

    private fun destroyProcessLocked() {
        try {
            writer?.write("quit\n")
            writer?.flush()
        } catch (_: Throwable) {
        }
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        writer = null
        reader = null
        process = null
        initialized = false
    }

    fun close() {
        cancel()
        synchronized(ioLock) {
            destroyProcessLocked()
        }
    }

    companion object {
        const val STOCKFISH_LIB_NAME = "libstockfish.so"
    }
}
