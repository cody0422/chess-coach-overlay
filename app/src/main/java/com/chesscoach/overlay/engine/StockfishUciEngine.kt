package com.chesscoach.overlay.engine

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Pure-Kotlin policy governing when best-move arrows should be computed/displayed
 * when "My Turn Only" is enabled (default true so opponent pieces never get arrows).
 */
object TurnArrowPolicy {
    fun myColor(playingAsWhite: Boolean): Int =
        if (playingAsWhite) Piece.WHITE else Piece.BLACK

    fun isMyTurn(sideToMove: Int, playingAsWhite: Boolean): Boolean =
        sideToMove == myColor(playingAsWhite)

    fun shouldShowArrow(
        myTurnOnly: Boolean,
        sideToMove: Int,
        playingAsWhite: Boolean
    ): Boolean {
        return !myTurnOnly || isMyTurn(sideToMove, playingAsWhite)
    }

    fun filterAnalysisForDisplay(
        analysis: AnalysisResult?,
        myTurnOnly: Boolean,
        sideToMove: Int,
        playingAsWhite: Boolean
    ): AnalysisResult? {
        if (analysis == null) return null
        return if (shouldShowArrow(myTurnOnly, sideToMove, playingAsWhite)) analysis else null
    }
}

data class ParsedUciInfo(
    val multiPv: Int,
    val depth: Int,
    val nodes: Long,
    val scoredMove: ScoredMove
)

data class SearchBudget(
    val sfDepth: Int,
    val moveTimeMs: Long
)

/**
 * Pure-Kotlin UCI `info` parser, MultiPV / search budget planner, and live-PV interim streaming policy.
 * Kept Android-free so every branch is directly covered by JVM unit tests.
 */
object StockfishUciParser {
    const val MIN_INTERIM_DEPTH = 5
    const val MIN_INTERIM_INTERVAL_MS = 35L

    /**
     * Avoid forcing MultiPV=5 when only a single arrow is displayed:
     * - Single arrow (`requestedMultiPv == 1`) with >=95% accuracy & Default style -> MultiPV = 1 (~3-5x faster!)
     * - Single arrow with human/accuracy variation -> MultiPV = 2 (never 5!)
     * - Multi-arrows (`requestedMultiPv > 1`) -> honor requestedMultiPv (clamped 1..5).
     */
    fun resolveActualMultiPv(
        requestedMultiPv: Int,
        clampedAcc: Int,
        playStyle: PlayStyle,
        legalMoveCount: Int
    ): Int {
        val req = requestedMultiPv.coerceIn(1, 5)
        val wantPv = when {
            req > 1 -> req
            clampedAcc >= 95 && playStyle == PlayStyle.DEFAULT -> 1
            else -> 2
        }
        return wantPv.coerceAtMost(legalMoveCount).coerceAtLeast(1)
    }

    /**
     * Maps Depth slider 1..8 cleanly from instant sub-second (<150ms..750ms) up to 5s on Depth 8.
     */
    fun searchBudgetFor(sliderDepth: Int, clampedElo: Int): SearchBudget {
        val d = sliderDepth.coerceIn(EloAccuracyHelper.MIN_DEPTH, EloAccuracyHelper.MAX_DEPTH)
        val base = when (d) {
            1 -> SearchBudget(sfDepth = 8, moveTimeMs = 150L)
            2 -> SearchBudget(sfDepth = 10, moveTimeMs = 250L)
            3 -> SearchBudget(sfDepth = 12, moveTimeMs = 450L)
            4 -> SearchBudget(sfDepth = 14, moveTimeMs = 750L)
            5 -> SearchBudget(sfDepth = 16, moveTimeMs = 1400L)
            6 -> SearchBudget(sfDepth = 18, moveTimeMs = 2400L)
            7 -> SearchBudget(sfDepth = 20, moveTimeMs = 3600L)
            else -> SearchBudget(sfDepth = 24, moveTimeMs = 5000L)
        }
        return when {
            clampedElo < 900 -> SearchBudget(
                sfDepth = d.coerceIn(1, 4),
                moveTimeMs = base.moveTimeMs.coerceAtMost(250L)
            )
            clampedElo < 1320 -> SearchBudget(
                sfDepth = (d + 1).coerceIn(2, 6),
                moveTimeMs = base.moveTimeMs.coerceAtMost(400L)
            )
            else -> base
        }
    }

    fun parseInfoLine(line: String, legalMoves: List<Move>): ParsedUciInfo? {
        if (!line.startsWith("info ") || !line.contains(" pv ")) return null
        val tokens = line.trim().split(Regex("\s+"))
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
        return ParsedUciInfo(
            multiPv = multiPv,
            depth = depth,
            nodes = nodes,
            scoredMove = ScoredMove(move, scoreCp, mateIn)
        )
    }

    fun matchUciMove(uci: String, legalMoves: List<Move>): Move? {
        val clean = uci.trim().lowercase()
        return legalMoves.firstOrNull { it.toUci() == clean }
    }

    fun shouldEmitInterim(
        depth: Int,
        topMoveUci: String,
        lastEmittedMoveUci: String?,
        nowMs: Long,
        lastEmitMs: Long,
        minDepth: Int = MIN_INTERIM_DEPTH,
        minIntervalMs: Long = MIN_INTERIM_INTERVAL_MS
    ): Boolean {
        if (depth < minDepth) return false
        if (lastEmittedMoveUci == null) return true
        if (topMoveUci != lastEmittedMoveUci) return true
        return (nowMs - lastEmitMs) >= minIntervalMs
    }

    fun buildAnalysisResult(
        sourceBoard: ChessBoard,
        pvByRank: Map<Int, ScoredMove>,
        highestDepth: Int,
        lastNodes: Long,
        clampedElo: Int,
        clampedAcc: Int,
        playStyle: PlayStyle,
        multiPv: Int,
        bestMoveUci: String? = null,
        legalMoves: List<Move> = emptyList(),
        rng: Random = Random.Default
    ): AnalysisResult? {
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

        return AnalysisResult(
            topMoves = selectedOrder,
            whiteEvalCp = whiteEvalCp,
            whiteMateIn = whiteMateIn,
            depth = highestDepth.coerceAtLeast(1),
            nodes = lastNodes,
            engineName = "Stockfish 19"
        )
    }
}

/**
 * Wraps the optional bundled native Stockfish 19 UCI binary (`libstockfish.so` in nativeLibraryDir)
 * with live PV streaming (~50-200ms first arrow), UCI option caching, and seamlessly falls back
 * to the pure-Kotlin [ChessEngine] whenever the binary is absent or encounters any runtime error.
 */
class StockfishUciEngine(private val context: Context) {

    private val fallbackEngine = ChessEngine()
    private val ioLock = Any()
    private val searching = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    // Cached UCI options so we only send setoption + isready when settings actually change
    private var lastMultiPv: Int = -1
    private var lastLimitStrength: Boolean? = null
    private var lastUciElo: Int = -1
    private var lastSkillLevel: Int = -1

    @Volatile
    private var stockfishFailed = false

    @Volatile
    private var initialized = false

    @Volatile
    var activeEngineName: String = "Local Engine"
        private set

    @Volatile
    var lastStartupError: String? = null
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

    private fun resetCachedOptionsLocked() {
        lastMultiPv = -1
        lastLimitStrength = null
        lastUciElo = -1
        lastSkillLevel = -1
    }

    private fun startStockfishLocked(): Boolean {
        val sfFile = File(context.applicationInfo.nativeLibraryDir, STOCKFISH_LIB_NAME)
        if (!sfFile.exists() || sfFile.length() == 0L) {
            lastStartupError = "Stockfish binary missing"
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
            resetCachedOptionsLocked()

            sendLineLocked("uci")
            val gotUciOk = waitForTokenLocked("uciok", COLD_START_TIMEOUT_MS)
            if (!gotUciOk) {
                destroyProcessLocked()
                stockfishFailed = true
                lastStartupError = "uciok timeout (${COLD_START_TIMEOUT_MS}ms)"
                activeEngineName = "Local Engine"
                return false
            }

            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            sendLineLocked("setoption name Threads value $threads")
            sendLineLocked("setoption name Hash value 64")
            sendLineLocked("setoption name MultiPV value 1")
            lastMultiPv = 1
            sendLineLocked("isready")
            val ready = waitForTokenLocked("readyok", COLD_START_TIMEOUT_MS)
            if (!ready) {
                destroyProcessLocked()
                stockfishFailed = true
                lastStartupError = "readyok timeout (${COLD_START_TIMEOUT_MS}ms)"
                activeEngineName = "Local Engine"
                return false
            }

            initialized = true
            lastStartupError = null
            activeEngineName = "Stockfish 19"
            true
        } catch (t: Throwable) {
            destroyProcessLocked()
            stockfishFailed = true
            lastStartupError = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(80)
            activeEngineName = "Local Engine"
            false
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        fallbackEngine.cancel()
        if (searching.get()) {
            try {
                writer?.let {
                    it.write("stop
")
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
        multiPv: Int = 1,
        rng: Random = Random.Default,
        onInterimResult: ((AnalysisResult) -> Unit)? = null
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
                rng = rng,
                onInterimResult = onInterimResult
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
        rng: Random,
        onInterimResult: ((AnalysisResult) -> Unit)?
    ): AnalysisResult? {
        synchronized(ioLock) {
            if (process?.isAlive != true) {
                if (!startStockfishLocked()) return null
            }

            val clampedElo = targetElo.coerceIn(EloAccuracyHelper.MIN_ELO, EloAccuracyHelper.MAX_ELO)
            val clampedDepth = searchDepth.coerceIn(EloAccuracyHelper.MIN_DEPTH, EloAccuracyHelper.MAX_DEPTH)
            val clampedAcc = targetAccuracy.coerceIn(EloAccuracyHelper.MIN_ACCURACY, EloAccuracyHelper.MAX_ACCURACY)

            val actualPv = StockfishUciParser.resolveActualMultiPv(
                requestedMultiPv = multiPv,
                clampedAcc = clampedAcc,
                playStyle = playStyle,
                legalMoveCount = legalMoves.size
            )

            return try {
                cancelRequested.set(false)
                searching.set(true)

                var optionsChanged = false

                if (lastMultiPv != actualPv) {
                    sendLineLocked("setoption name MultiPV value $actualPv")
                    lastMultiPv = actualPv
                    optionsChanged = true
                }

                // Configure Stockfish 19 strength for 400..4000 Elo (only emit when changed)
                when {
                    clampedElo > 3190 -> {
                        if (lastLimitStrength != false) {
                            sendLineLocked("setoption name UCI_LimitStrength value false")
                            lastLimitStrength = false
                            optionsChanged = true
                        }
                        if (lastSkillLevel != 20) {
                            sendLineLocked("setoption name Skill Level value 20")
                            lastSkillLevel = 20
                            optionsChanged = true
                        }
                        lastUciElo = -1
                    }
                    clampedElo >= 1320 -> {
                        if (lastLimitStrength != true) {
                            sendLineLocked("setoption name UCI_LimitStrength value true")
                            lastLimitStrength = true
                            optionsChanged = true
                        }
                        if (lastUciElo != clampedElo) {
                            sendLineLocked("setoption name UCI_Elo value $clampedElo")
                            lastUciElo = clampedElo
                            optionsChanged = true
                        }
                        lastSkillLevel = -1
                    }
                    else -> {
                        val skill = ((clampedElo - 400) * 5 / 920).coerceIn(0, 5)
                        if (lastLimitStrength != false) {
                            sendLineLocked("setoption name UCI_LimitStrength value false")
                            lastLimitStrength = false
                            optionsChanged = true
                        }
                        if (lastSkillLevel != skill) {
                            sendLineLocked("setoption name Skill Level value $skill")
                            lastSkillLevel = skill
                            optionsChanged = true
                        }
                        lastUciElo = -1
                    }
                }

                if (optionsChanged) {
                    sendLineLocked("isready")
                    if (!waitForTokenLocked("readyok", 3000L)) {
                        throw IllegalStateException("Stockfish not ready")
                    }
                }

                val fen = sourceBoard.toFen()
                sendLineLocked("position fen $fen")

                val budget = StockfishUciParser.searchBudgetFor(clampedDepth, clampedElo)
                val sfDepth = budget.sfDepth
                val moveTimeMs = budget.moveTimeMs
                val interimMinDepth = if (sfDepth < StockfishUciParser.MIN_INTERIM_DEPTH) {
                    sfDepth.coerceAtLeast(1)
                } else {
                    StockfishUciParser.MIN_INTERIM_DEPTH
                }

                sendLineLocked("go depth $sfDepth movetime $moveTimeMs")

                val pvByRank = HashMap<Int, ScoredMove>()
                var highestDepth = 0
                var lastNodes = 0L
                var bestMoveUci: String? = null
                var lastInterimMoveUci: String? = null
                var lastInterimEmitMs = 0L
                val deterministicInterimSeed = fen.hashCode().toLong()
                val hardDeadline = System.currentTimeMillis() + moveTimeMs + 3500L

                val r = reader ?: return null
                while (System.currentTimeMillis() < hardDeadline) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("info ") && line.contains(" pv ")) {
                        val parsed = StockfishUciParser.parseInfoLine(line, legalMoves)
                        if (parsed != null) {
                            if (parsed.depth > highestDepth) highestDepth = parsed.depth
                            if (parsed.nodes > lastNodes) lastNodes = parsed.nodes
                            pvByRank[parsed.multiPv] = parsed.scoredMove

                            if (onInterimResult != null && !cancelRequested.get() && parsed.multiPv == 1) {
                                val now = System.currentTimeMillis()
                                val topUci = parsed.scoredMove.move.toUci()
                                if (StockfishUciParser.shouldEmitInterim(
                                        depth = parsed.depth,
                                        topMoveUci = topUci,
                                        lastEmittedMoveUci = lastInterimMoveUci,
                                        nowMs = now,
                                        lastEmitMs = lastInterimEmitMs,
                                        minDepth = interimMinDepth
                                    )
                                ) {
                                    val interim = StockfishUciParser.buildAnalysisResult(
                                        sourceBoard = sourceBoard,
                                        pvByRank = pvByRank,
                                        highestDepth = highestDepth,
                                        lastNodes = lastNodes,
                                        clampedElo = clampedElo,
                                        clampedAcc = clampedAcc,
                                        playStyle = playStyle,
                                        multiPv = multiPv,
                                        legalMoves = legalMoves,
                                        rng = Random(deterministicInterimSeed)
                                    )
                                    if (interim != null && !cancelRequested.get()) {
                                        lastInterimMoveUci = interim.topMoves.firstOrNull()?.move?.toUci() ?: topUci
                                        lastInterimEmitMs = now
                                        onInterimResult(interim)
                                    }
                                }
                            }
                        }
                    } else if (line.startsWith("bestmove ")) {
                        val tokens = line.split(' ')
                        bestMoveUci = tokens.getOrNull(1)
                        break
                    }
                }

                searching.set(false)

                StockfishUciParser.buildAnalysisResult(
                    sourceBoard = sourceBoard,
                    pvByRank = pvByRank,
                    highestDepth = highestDepth,
                    lastNodes = lastNodes,
                    clampedElo = clampedElo,
                    clampedAcc = clampedAcc,
                    playStyle = playStyle,
                    multiPv = multiPv,
                    bestMoveUci = bestMoveUci,
                    legalMoves = legalMoves,
                    rng = rng
                )
            } catch (t: Throwable) {
                searching.set(false)
                destroyProcessLocked()
                stockfishFailed = true
                lastStartupError = "search: ${t.javaClass.simpleName}".take(80)
                null
            }
        }
    }

    private fun sendLineLocked(cmd: String) {
        val w = writer ?: throw IllegalStateException("Stockfish writer closed")
        w.write(cmd)
        w.write("
")
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
        resetCachedOptionsLocked()
        try {
            writer?.write("quit
")
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
        private const val COLD_START_TIMEOUT_MS = 10000L
    }
}
