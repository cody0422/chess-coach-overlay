package com.chesscoach.overlay.vision

import com.chesscoach.overlay.engine.ChessBoard
import com.chesscoach.overlay.engine.Move
import com.chesscoach.overlay.engine.Piece
import com.chesscoach.overlay.engine.Squares
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PixelRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun scaled(scaleX: Float, scaleY: Float): PixelRect = PixelRect(
        left = (left * scaleX).roundToInt(),
        top = (top * scaleY).roundToInt(),
        width = (width * scaleX).roundToInt(),
        height = (height * scaleY).roundToInt()
    )
}

data class Rgb(val r: Int, val g: Int, val b: Int) {
    fun luminance(): Float = (299 * r + 587 * g + 114 * b) / 1000f
}

data class BoardDetectionResult(
    val found: Boolean,
    val rect: PixelRect,
    val confidence: Float,
    val lightRgb: Rgb,
    val darkRgb: Rgb,
    val evenRgb: Rgb = lightRgb,
    val oddRgb: Rgb = darkRgb
) {
    companion object {
        val NOT_FOUND = BoardDetectionResult(
            found = false,
            rect = PixelRect(0, 0, 0, 0),
            confidence = 0f,
            lightRgb = Rgb(238, 238, 210),
            darkRgb = Rgb(118, 150, 86)
        )
    }
}

enum class SquareOccupancy {
    EMPTY,
    WHITE,
    BLACK
}

data class StartPositionDetection(
    val isStartPosition: Boolean,
    val playingAsWhite: Boolean
)

data class LegalMoveMatch(
    val moves: List<Move>,
    val score: Int,
    val currentScore: Int
)

data class FrameAnalysis(
    val detection: BoardDetectionResult,
    val grid: Array<SquareOccupancy>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameAnalysis) return false
        return detection == other.detection && grid.contentEquals(other.grid)
    }

    override fun hashCode(): Int {
        var result = detection.hashCode()
        result = 31 * result + (grid?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Pure-Kotlin, theme-agnostic chess board detector, occupancy/color classifier,
 * and legal-move differential tracker operating on ARGB (0xFFRRGGBB) pixel buffers.
 */
object BoardVisionAnalyzer {

    private val CORNER_OX = floatArrayOf(0.86f, 0.14f, 0.50f, 0.50f)
    private val CORNER_OY = floatArrayOf(0.14f, 0.86f, 0.08f, 0.92f)
    private val CANDIDATE_WIDTH_FRACS = floatArrayOf(1.00f, 0.96f, 0.92f, 0.88f, 0.85f)

    private data class CandidateEval(
        val valid: Boolean,
        val score: Float,
        val confidence: Float,
        val evenInliers: Int,
        val oddInliers: Int,
        val separation: Float,
        val seamRatio: Float,
        val evenRgb: Rgb,
        val oddRgb: Rgb
    )

    fun analyzeFrame(
        pixels: IntArray,
        width: Int,
        height: Int,
        lockedRect: PixelRect? = null
    ): FrameAnalysis {
        if (width < 64 || height < 64 || pixels.size < width * height) {
            return FrameAnalysis(BoardDetectionResult.NOT_FOUND, null)
        }
        val detection = if (lockedRect != null && lockedRect.width >= 64 && lockedRect.height >= 64) {
            verifyLockedBoard(pixels, width, height, lockedRect)
        } else {
            detectBoard(pixels, width, height)
        }
        if (!detection.found) {
            return FrameAnalysis(detection, null)
        }
        val grid = classifyGrid(pixels, width, height, detection)
        return FrameAnalysis(detection, grid)
    }

    /**
     * Scans a screen buffer looking for a large centered square 8x8 chess board
     * (width ~85%-100% of screen width, y within ~10%-86% of screen height).
     */
    fun detectBoard(pixels: IntArray, width: Int, height: Int): BoardDetectionResult {
        if (width < 64 || height < 64 || pixels.size < width * height) {
            return BoardDetectionResult.NOT_FOUND
        }

        val minDim = min(width, height)
        val yMin = (height * 0.08f).toInt().coerceAtLeast(0)
        val yMaxTotal = (height * 0.88f).toInt().coerceAtMost(height)

        var bestScore = 0f
        var bestLeft = 0
        var bestTop = 0
        var bestSize = 0
        var bestEval: CandidateEval? = null

        // Stage 1: Coarse scan across portrait widths and vertical positions
        for (frac in CANDIDATE_WIDTH_FRACS) {
            val size = (minDim * frac).roundToInt().coerceIn(64, minDim)
            val left = (width - size) / 2
            val maxTop = (yMaxTotal - size).coerceAtLeast(yMin)
            val stepY = max(6, size / 28)

            var top = yMin
            while (top <= maxTop) {
                val ev = evaluateCandidate(pixels, width, height, left, top, size, checkSeams = false)
                if (ev.score > bestScore) {
                    bestScore = ev.score
                    bestLeft = left
                    bestTop = top
                    bestSize = size
                    bestEval = ev
                }
                top += stepY
            }
        }

        if (bestEval == null || bestScore <= 0.15f) {
            return BoardDetectionResult.NOT_FOUND
        }

        // Stage 2: Fine coordinate refinement around best coarse candidate (with seam alignment)
        var curLeft = bestLeft
        var curTop = bestTop
        var curSize = bestSize
        var curEval = evaluateCandidate(pixels, width, height, curLeft, curTop, curSize, checkSeams = true)
        var curScore = curEval.score

        val refineSteps = intArrayOf(8, 4, 2, 1)
        for (step in refineSteps) {
            var improved = true
            var passes = 0
            while (improved && passes < 4) {
                improved = false
                passes++
                // Try shifting top
                for (dt in intArrayOf(-step, step)) {
                    val nt = curTop + dt
                    if (nt >= 0 && nt + curSize <= height) {
                        val ev = evaluateCandidate(pixels, width, height, curLeft, nt, curSize, checkSeams = true)
                        if (ev.score > curScore + 0.0005f) {
                            curTop = nt
                            curEval = ev
                            curScore = ev.score
                            improved = true
                        }
                    }
                }
                // Try adjusting size (keeping centered or nudging left)
                for (ds in intArrayOf(-step * 2, step * 2)) {
                    val ns = curSize + ds
                    val minS = (minDim * 0.80f).toInt().coerceAtLeast(64)
                    if (ns in minS..minDim) {
                        val nl = ((width - ns) / 2).coerceIn(0, width - ns)
                        val nt = (curTop - ds / 2).coerceIn(0, height - ns)
                        val ev = evaluateCandidate(pixels, width, height, nl, nt, ns, checkSeams = true)
                        if (ev.score > curScore + 0.0005f) {
                            curLeft = nl
                            curTop = nt
                            curSize = ns
                            curEval = ev
                            curScore = ev.score
                            improved = true
                        }
                    }
                }
                // Try slight horizontal nudge
                for (dl in intArrayOf(-step, step)) {
                    val nl = curLeft + dl
                    if (nl >= 0 && nl + curSize <= width) {
                        val ev = evaluateCandidate(pixels, width, height, nl, curTop, curSize, checkSeams = true)
                        if (ev.score > curScore + 0.0005f) {
                            curLeft = nl
                            curEval = ev
                            curScore = ev.score
                            improved = true
                        }
                    }
                }
            }
        }

        val finalEval = evaluateCandidate(pixels, width, height, curLeft, curTop, curSize, checkSeams = true)
        if (!finalEval.valid) {
            return BoardDetectionResult.NOT_FOUND
        }

        val evenLum = finalEval.evenRgb.luminance()
        val oddLum = finalEval.oddRgb.luminance()
        val lightRgb = if (evenLum >= oddLum) finalEval.evenRgb else finalEval.oddRgb
        val darkRgb = if (evenLum >= oddLum) finalEval.oddRgb else finalEval.evenRgb

        return BoardDetectionResult(
            found = true,
            rect = PixelRect(curLeft, curTop, curSize, curSize),
            confidence = finalEval.confidence.coerceIn(0f, 1f),
            lightRgb = lightRgb,
            darkRgb = darkRgb,
            evenRgb = finalEval.evenRgb,
            oddRgb = finalEval.oddRgb
        )
    }

    private fun verifyLockedBoard(
        pixels: IntArray,
        width: Int,
        height: Int,
        rect: PixelRect
    ): BoardDetectionResult {
        val left = rect.left.coerceIn(0, width - 8)
        val top = rect.top.coerceIn(0, height - 8)
        val sizeW = rect.width.coerceAtMost(width - left)
        val sizeH = rect.height.coerceAtMost(height - top)
        val size = min(sizeW, sizeH)
        if (size < 64) return BoardDetectionResult.NOT_FOUND

        val ev = evaluateCandidate(pixels, width, height, left, top, size, checkSeams = true)
        if (!ev.valid) return BoardDetectionResult.NOT_FOUND

        val evenLum = ev.evenRgb.luminance()
        val oddLum = ev.oddRgb.luminance()
        val lightRgb = if (evenLum >= oddLum) ev.evenRgb else ev.oddRgb
        val darkRgb = if (evenLum >= oddLum) ev.oddRgb else ev.evenRgb
        return BoardDetectionResult(
            found = true,
            rect = PixelRect(left, top, sizeW, sizeH),
            confidence = ev.confidence.coerceIn(0f, 1f),
            lightRgb = lightRgb,
            darkRgb = darkRgb,
            evenRgb = ev.evenRgb,
            oddRgb = ev.oddRgb
        )
    }

    private fun evaluateCandidate(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        size: Int,
        checkSeams: Boolean
    ): CandidateEval {
        val sq = size / 8f
        val evenR = IntArray(32)
        val evenG = IntArray(32)
        val evenB = IntArray(32)
        val oddR = IntArray(32)
        val oddG = IntArray(32)
        val oddB = IntArray(32)
        var ei = 0
        var oi = 0

        val tmpRgb = IntArray(3)
        for (r in 0..7) {
            for (c in 0..7) {
                sampleSquareCornerBg(pixels, width, height, left, top, sq, sq, r, c, tmpRgb)
                if (((r + c) and 1) == 0) {
                    evenR[ei] = tmpRgb[0]
                    evenG[ei] = tmpRgb[1]
                    evenB[ei] = tmpRgb[2]
                    ei++
                } else {
                    oddR[oi] = tmpRgb[0]
                    oddG[oi] = tmpRgb[1]
                    oddB[oi] = tmpRgb[2]
                    oi++
                }
            }
        }

        val medER = median32(evenR)
        val medEG = median32(evenG)
        val medEB = median32(evenB)
        val medOR = median32(oddR)
        val medOG = median32(oddG)
        val medOB = median32(oddB)

        val sep = colorDist(medER, medEG, medEB, medOR, medOG, medOB)
        if (sep < 28f) {
            return CandidateEval(
                false, 0f, 0f, 0, 0, sep, 0f,
                Rgb(medER, medEG, medEB), Rgb(medOR, medOG, medOB)
            )
        }

        // Tolerance scales gently with board contrast (handles JPEG/scaling/theme gradients)
        val tol = (sep * 0.24f).coerceIn(22f, 34f)
        var evenIn = 0
        var oddIn = 0
        var errSum = 0f
        for (i in 0 until 32) {
            val de = colorDist(evenR[i], evenG[i], evenB[i], medER, medEG, medEB)
            if (de <= tol) {
                evenIn++
                errSum += de
            }
            val doDist = colorDist(oddR[i], oddG[i], oddB[i], medOR, medOG, medOB)
            if (doDist <= tol) {
                oddIn++
                errSum += doDist
            }
        }

        val totalIn = evenIn + oddIn
        val minPerParity = if (checkSeams) 24 else 20
        val minTotal = if (checkSeams) 52 else 44
        if (evenIn < minPerParity || oddIn < minPerParity || totalIn < minTotal) {
            return CandidateEval(
                false, 0f, 0f, evenIn, oddIn, sep, 0f,
                Rgb(medER, medEG, medEB), Rgb(medOR, medOG, medOB)
            )
        }

        var seamRatio = 1f
        if (checkSeams) {
            seamRatio = measureSeamSharpness(pixels, width, height, left, top, sq, sep)
            if (seamRatio < 0.55f) {
                return CandidateEval(
                    false, 0f, 0f, evenIn, oddIn, sep, seamRatio,
                    Rgb(medER, medEG, medEB), Rgb(medOR, medOG, medOB)
                )
            }
        }

        val inlierFrac = totalIn / 64f
        val meanInlierErr = errSum / max(1, totalIn)
        val tightness = (1f - (meanInlierErr / 40f)).coerceIn(0.2f, 1f)
        val sepFactor = (sep / 90f).coerceIn(0.35f, 1.15f)
        val score = inlierFrac * tightness * sepFactor * (0.55f + 0.45f * seamRatio)
        val confidence = (inlierFrac * (sep / 70f).coerceAtMost(1f) * seamRatio).coerceIn(0f, 1f)

        return CandidateEval(
            valid = true,
            score = score,
            confidence = confidence,
            evenInliers = evenIn,
            oddInliers = oddIn,
            separation = sep,
            seamRatio = seamRatio,
            evenRgb = Rgb(medER, medEG, medEB),
            oddRgb = Rgb(medOR, medOG, medOB)
        )
    }

    /**
     * Checks pixel contrast across the 7 internal vertical & 7 internal horizontal square boundaries
     * using clean offsets near square corners (where pieces do not obscure seams).
     */
    private fun measureSeamSharpness(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        sq: Float,
        expectedSep: Float
    ): Float {
        val delta = max(2, (sq * 0.08f).roundToInt())
        val minEdgeJump = expectedSep * 0.42f
        var hits = 0
        var total = 0

        // Vertical seams (between col c-1 and col c) sampled near top of each row (r + 0.14)
        for (c in 1..7) {
            val sx = (left + c * sq).roundToInt()
            val xL = (sx - delta).coerceIn(0, width - 1)
            val xR = (sx + delta).coerceIn(0, width - 1)
            for (r in 0..7) {
                val py = (top + (r + 0.14f) * sq).roundToInt().coerceIn(0, height - 1)
                val p1 = pixels[py * width + xL]
                val p2 = pixels[py * width + xR]
                if (pixelDist(p1, p2) >= minEdgeJump) hits++
                total++
            }
        }

        // Horizontal seams (between row r-1 and row r) sampled near right edge of each col (c + 0.86)
        for (r in 1..7) {
            val sy = (top + r * sq).roundToInt()
            val yU = (sy - delta).coerceIn(0, height - 1)
            val yD = (sy + delta).coerceIn(0, height - 1)
            for (c in 0..7) {
                val px = (left + (c + 0.86f) * sq).roundToInt().coerceIn(0, width - 1)
                val p1 = pixels[yU * width + px]
                val p2 = pixels[yD * width + px]
                if (pixelDist(p1, p2) >= minEdgeJump) hits++
                total++
            }
        }

        return if (total > 0) hits.toFloat() / total else 0f
    }

    /**
     * Classifies all 64 squares in screen order (row 0 = top .. row 7 = bottom,
     * col 0 = left .. col 7 = right) as EMPTY, WHITE, or BLACK.
     */
    fun classifyGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        detection: BoardDetectionResult
    ): Array<SquareOccupancy> {
        val grid = Array(64) { SquareOccupancy.EMPTY }
        val rect = detection.rect
        if (!detection.found || rect.width < 32 || rect.height < 32) return grid

        val sqW = rect.width / 8f
        val sqH = rect.height / 8f
        val localBg = IntArray(3)
        val fgLums = IntArray(144)

        for (r in 0..7) {
            for (c in 0..7) {
                val parityBg = if (((r + c) and 1) == 0) detection.evenRgb else detection.oddRgb
                val pairDist = sampleSquareCornerBg(
                    pixels, width, height, rect.left, rect.top, sqW, sqH, r, c, localBg
                )

                // If square has a clean uniform last-move highlight tint (e.g., Chess.com yellow),
                // use the square's own corner RGB as its background so highlighted empty squares
                // are not misread as occupied!
                val localDistFromParity = colorDist(
                    localBg[0], localBg[1], localBg[2],
                    parityBg.r, parityBg.g, parityBg.b
                )
                val useLocalBg = pairDist <= 20f && localDistFromParity in 14f..145f
                val bgR = if (useLocalBg) localBg[0] else parityBg.r
                val bgG = if (useLocalBg) localBg[1] else parityBg.g
                val bgB = if (useLocalBg) localBg[2] else parityBg.b

                val samples = 12
                var validSamples = 0
                var fgCount = 0
                var fgLumSum = 0L
                var brightFg = 0
                var darkFg = 0
                var edgeSteps = 0

                val startX = rect.left + (c + 0.21f) * sqW
                val spanX = 0.58f * sqW
                val startY = rect.top + (r + 0.21f) * sqH
                val spanY = 0.58f * sqH

                for (sy in 0 until samples) {
                    val py = (startY + (sy + 0.5f) * spanY / samples)
                        .roundToInt().coerceIn(0, height - 1)
                    val rowOff = py * width
                    var prevPx = -1
                    for (sx in 0 until samples) {
                        val px = (startX + (sx + 0.5f) * spanX / samples)
                            .roundToInt().coerceIn(0, width - 1)
                        val argb = pixels[rowOff + px]
                        val pr = (argb ushr 16) and 0xFF
                        val pg = (argb ushr 8) and 0xFF
                        val pb = argb and 0xFF

                        if (isOverlayAnnotationPixel(pr, pg, pb, bgR, bgG, bgB)) {
                            continue
                        }
                        validSamples++

                        if (prevPx != -1 && pixelDist(prevPx, argb) >= 34f) {
                            edgeSteps++
                        }
                        prevPx = argb

                        val dist = colorDist(pr, pg, pb, bgR, bgG, bgB)
                        if (dist > 30f) {
                            val lum = (299 * pr + 587 * pg + 114 * pb) / 1000
                            fgLums[fgCount++] = lum
                            fgLumSum += lum
                            if (lum >= 170) brightFg++
                            if (lum <= 105) darkFg++
                        }
                    }
                }

                if (validSamples < 24) {
                    grid[r * 8 + c] = SquareOccupancy.EMPTY
                    continue
                }

                val fgFrac = fgCount.toFloat() / validSamples.toFloat()
                // Reject flat tinted empty squares (pieces always have internal luminance/edge structure)
                val hasPieceStructure = edgeSteps >= 4 || fgFrac >= 0.24f
                if (fgFrac >= 0.10f && hasPieceStructure && fgCount >= 8) {
                    fgLums.sort(0, fgCount)
                    val meanLum = fgLumSum.toFloat() / fgCount.toFloat()
                    val p75Lum = fgLums[(fgCount * 3) / 4].toFloat()
                    val brightFrac = brightFg.toFloat() / fgCount.toFloat()
                    val darkFrac = darkFg.toFloat() / fgCount.toFloat()

                    val isWhite = (p75Lum >= 165f) ||
                        (meanLum >= 145f) ||
                        (brightFrac >= 0.35f && darkFrac < 0.45f)

                    grid[r * 8 + c] = if (isWhite) SquareOccupancy.WHITE else SquareOccupancy.BLACK
                } else {
                    grid[r * 8 + c] = SquareOccupancy.EMPTY
                }
            }
        }
        return grid
    }

    /**
     * Detects whether the observed 64-square grid is the standard starting position,
     * and determines orientation (playingAsWhite = bottom two rows are WHITE).
     */
    fun detectStartingPosition(screenGrid: Array<SquareOccupancy>): StartPositionDetection {
        if (screenGrid.size != 64) return StartPositionDetection(false, true)
        var topWhite = 0
        var topBlack = 0
        var midOccupied = 0
        var botWhite = 0
        var botBlack = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val occ = screenGrid[r * 8 + c]
                when (r) {
                    0, 1 -> {
                        if (occ == SquareOccupancy.WHITE) topWhite++
                        else if (occ == SquareOccupancy.BLACK) topBlack++
                    }
                    6, 7 -> {
                        if (occ == SquareOccupancy.WHITE) botWhite++
                        else if (occ == SquareOccupancy.BLACK) botBlack++
                    }
                    else -> {
                        if (occ != SquareOccupancy.EMPTY) midOccupied++
                    }
                }
            }
        }

        // Require zero pieces in middle 4 ranks so opening moves (1. e4, 1. Nf3, etc.)
        // are NEVER misread as the starting position.
        if (midOccupied == 0) {
            if (topBlack >= 15 && botWhite >= 15) {
                return StartPositionDetection(isStartPosition = true, playingAsWhite = true)
            }
            if (topWhite >= 15 && botBlack >= 15) {
                return StartPositionDetection(isStartPosition = true, playingAsWhite = false)
            }
        }
        return StartPositionDetection(isStartPosition = false, playingAsWhite = true)
    }

    /**
     * Maps a logical [ChessBoard] into screen-order 64-square occupancy (row 0 = top .. row 7 = bottom).
     */
    fun boardToScreenGrid(board: ChessBoard, playingAsWhite: Boolean): Array<SquareOccupancy> {
        val out = Array(64) { SquareOccupancy.EMPTY }
        for (r in 0..7) {
            for (c in 0..7) {
                val file = if (playingAsWhite) c else (7 - c)
                val rank = if (playingAsWhite) (7 - r) else r
                val p = board.squares[Squares.of(file, rank)]
                out[r * 8 + c] = when {
                    p > 0 -> SquareOccupancy.WHITE
                    p < 0 -> SquareOccupancy.BLACK
                    else -> SquareOccupancy.EMPTY
                }
            }
        }
        return out
    }

    fun countGridMatches(a: Array<SquareOccupancy>, b: Array<SquareOccupancy>): Int {
        if (a.size != 64 || b.size != 64) return 0
        var matches = 0
        for (i in 0 until 64) {
            if (a[i] == b[i]) matches++
        }
        return matches
    }

    /**
     * Matches an observed 64-square color-occupancy grid against all legal 1-ply moves
     * (and 2-ply fallback if both players moved quickly between frames).
     */
    fun matchLegalMove(
        currentBoard: ChessBoard,
        observedScreenGrid: Array<SquareOccupancy>,
        playingAsWhite: Boolean
    ): LegalMoveMatch? {
        if (observedScreenGrid.size != 64) return null

        val currentScore = countBoardMatches(currentBoard, observedScreenGrid, playingAsWhite)
        if (currentScore >= 63) return null

        val sim = currentBoard.copy()
        val legal1 = sim.generateLegalMoves()
        if (legal1.isEmpty()) return null

        var best1Move: Move? = null
        var best1Score = -1

        for (m in legal1) {
            val undo = sim.makeMove(m)
            val s = countBoardMatches(sim, observedScreenGrid, playingAsWhite)
            sim.unmakeMove(m, undo)
            if (s > best1Score || (s == best1Score && preferPromotionQueen(m, best1Move))) {
                best1Score = s
                best1Move = m
            }
        }

        // If 1-ply move is an exact or near-exact match (63-64/64), return immediately
        if (best1Move != null && best1Score >= 63 && best1Score >= currentScore + 2) {
            return LegalMoveMatch(listOf(best1Move), best1Score, currentScore)
        }

        // 2-ply lookahead (in case both players moved between two capture frames)
        var best2M1: Move? = null
        var best2M2: Move? = null
        var best2Score = -1

        for (m1 in legal1) {
            // Skip underpromotions in 2-ply search to keep it ultra-fast
            if (m1.promotion != Piece.EMPTY && m1.promotion != Piece.QUEEN) continue
            val undo1 = sim.makeMove(m1)
            val legal2 = sim.generateLegalMoves()
            for (m2 in legal2) {
                if (m2.promotion != Piece.EMPTY && m2.promotion != Piece.QUEEN) continue
                val undo2 = sim.makeMove(m2)
                val s2 = countBoardMatches(sim, observedScreenGrid, playingAsWhite)
                sim.unmakeMove(m2, undo2)
                if (s2 > best2Score) {
                    best2Score = s2
                    best2M1 = m1
                    best2M2 = m2
                }
            }
            sim.unmakeMove(m1, undo1)
        }

        if (best2M1 != null && best2M2 != null &&
            best2Score >= 62 &&
            best2Score >= currentScore + 3 &&
            best2Score >= best1Score + 2
        ) {
            return LegalMoveMatch(listOf(best2M1, best2M2), best2Score, currentScore)
        }

        if (best1Move != null && best1Score >= 61 && best1Score >= currentScore + 2) {
            return LegalMoveMatch(listOf(best1Move), best1Score, currentScore)
        }

        return null
    }

    private fun preferPromotionQueen(candidate: Move, existing: Move?): Boolean {
        if (existing == null) return true
        return candidate.promotion == Piece.QUEEN && existing.promotion != Piece.QUEEN
    }

    private fun countBoardMatches(
        board: ChessBoard,
        observedScreenGrid: Array<SquareOccupancy>,
        playingAsWhite: Boolean
    ): Int {
        var matches = 0
        for (r in 0..7) {
            val rank = if (playingAsWhite) (7 - r) else r
            val rowOff = r * 8
            for (c in 0..7) {
                val file = if (playingAsWhite) c else (7 - c)
                val p = board.squares[rank * 8 + file]
                val expected = when {
                    p > 0 -> SquareOccupancy.WHITE
                    p < 0 -> SquareOccupancy.BLACK
                    else -> SquareOccupancy.EMPTY
                }
                if (expected == observedScreenGrid[rowOff + c]) matches++
            }
        }
        return matches
    }

    /**
     * Samples 4 near-corner/edge background probes in a square and averages the closest pair,
     * making background estimation immune to pieces, rank/file labels, and overlay arrows.
     * Returns the RGB distance between the chosen closest pair.
     */
    private fun sampleSquareCornerBg(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        sqW: Float,
        sqH: Float,
        row: Int,
        col: Int,
        outRgb: IntArray
    ): Float {
        val rs = IntArray(4)
        val gs = IntArray(4)
        val bs = IntArray(4)

        for (k in 0 until 4) {
            val px = (left + (col + CORNER_OX[k]) * sqW).roundToInt().coerceIn(0, width - 1)
            val py = (top + (row + CORNER_OY[k]) * sqH).roundToInt().coerceIn(0, height - 1)
            val argb = pixels[py * width + px]
            rs[k] = (argb ushr 16) and 0xFF
            gs[k] = (argb ushr 8) and 0xFF
            bs[k] = argb and 0xFF
        }

        var bestD = Float.MAX_VALUE
        var bestI = 0
        var bestJ = 1
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                val d = colorDist(rs[i], gs[i], bs[i], rs[j], gs[j], bs[j])
                if (d < bestD) {
                    bestD = d
                    bestI = i
                    bestJ = j
                }
            }
        }
        outRgb[0] = (rs[bestI] + rs[bestJ]) ushr 1
        outRgb[1] = (gs[bestI] + gs[bestJ]) ushr 1
        outRgb[2] = (bs[bestI] + bs[bestJ]) ushr 1
        return bestD
    }

    /**
     * Excludes vivid high-saturation pixels belonging to our own overlay arrows/warnings
     * (neon emerald #22C55E / #19E58F, cyan #38BDF8, amber #F59E0B, warning red #EF4444)
     * so arrows drawn on empty squares never cause false piece detections!
     */
    private fun isOverlayAnnotationPixel(
        r: Int,
        g: Int,
        b: Int,
        bgR: Int,
        bgG: Int,
        bgB: Int
    ): Boolean {
        val bgDist = colorDist(r, g, b, bgR, bgG, bgB)
        if (bgDist <= 28f) return false

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val chroma = maxC - minC
        if (chroma < 65) return false

        // Vivid neon emerald arrow (#22C55E / #19E58F / alpha-blended)
        if (g >= 155 && g - r >= 50 && g - b >= 22) return true
        // Vivid sky-blue secondary arrow (#38BDF8)
        if (b >= 175 && g >= 120 && b - r >= 65) return true
        // Vivid amber/gold arrow (#F59E0B / #FBBF24)
        if (r >= 205 && g >= 140 && b <= 75 && r - b >= 145) return true
        // Vivid warning red (#EF4444)
        if (r >= 185 && r - g >= 95 && r - b >= 95) return true

        return false
    }

    private fun median32(arr: IntArray): Int {
        arr.sort()
        return (arr[15] + arr[16]) ushr 1
    }

    private fun pixelDist(p1: Int, p2: Int): Float {
        val r1 = (p1 ushr 16) and 0xFF
        val g1 = (p1 ushr 8) and 0xFF
        val b1 = p1 and 0xFF
        val r2 = (p2 ushr 16) and 0xFF
        val g2 = (p2 ushr 8) and 0xFF
        val b2 = p2 and 0xFF
        return colorDist(r1, g1, b1, r2, g2, b2)
    }

    private fun colorDist(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float {
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
}

sealed class BoardTrackingEvent {
    data object BoardHidden : BoardTrackingEvent()
    data class BoardVisible(val rect: PixelRect, val confidence: Float) : BoardTrackingEvent()
    data class NewGameDetected(val playingAsWhite: Boolean, val rect: PixelRect) : BoardTrackingEvent()
    data class MovesDetected(val moves: List<Move>, val rect: PixelRect) : BoardTrackingEvent()
}

/**
 * Pure-Kotlin temporal stability filter sitting between [BoardVisionAnalyzer] and [OverlayService]:
 * - Auto-hides board overlay after 2 consecutive frames with no board detected (e.g. menu screen).
 * - Requires 2 consecutive stable frames agreeing on the 64-square grid before resetting to a
 *   new game or applying a legal move (prevents false triggers mid-drag / mid-animation).
 */
class BoardTrackingSession {

    var boardVisible: Boolean = false
        private set

    var lastConfirmedRect: PixelRect? = null
        private set

    private var consecutiveMisses: Int = 0
    private var pendingGrid: Array<SquareOccupancy>? = null
    private var pendingStableCount: Int = 0
    private var hasInitializedGame: Boolean = false

    fun resetTracking() {
        consecutiveMisses = 0
        pendingGrid = null
        pendingStableCount = 0
        hasInitializedGame = false
    }

    fun onFrame(
        analysis: FrameAnalysis,
        currentBoard: ChessBoard,
        currentPlayingAsWhite: Boolean
    ): List<BoardTrackingEvent> {
        val events = ArrayList<BoardTrackingEvent>(2)

        if (!analysis.detection.found || analysis.grid == null) {
            consecutiveMisses++
            pendingGrid = null
            pendingStableCount = 0
            if (consecutiveMisses >= 2 && boardVisible) {
                boardVisible = false
                events.add(BoardTrackingEvent.BoardHidden)
            }
            return events
        }

        consecutiveMisses = 0
        val rect = analysis.detection.rect
        val rectChangedSignificantly = lastConfirmedRect == null ||
            abs(rect.left - lastConfirmedRect!!.left) > 4 ||
            abs(rect.top - lastConfirmedRect!!.top) > 4 ||
            abs(rect.width - lastConfirmedRect!!.width) > 4 ||
            abs(rect.height - lastConfirmedRect!!.height) > 4

        if (!boardVisible || rectChangedSignificantly) {
            boardVisible = true
            lastConfirmedRect = rect
            events.add(BoardTrackingEvent.BoardVisible(rect, analysis.detection.confidence))
        }

        val grid = analysis.grid
        val prev = pendingGrid
        if (prev != null && BoardVisionAnalyzer.countGridMatches(prev, grid) >= 63) {
            pendingStableCount++
        } else {
            pendingGrid = grid.copyOf()
            pendingStableCount = 1
        }

        // Wait for 2 consecutive stable frames before committing state changes
        if (pendingStableCount < 2) {
            return events
        }

        // Check for starting position (new game or orientation flip at start)
        val startCheck = BoardVisionAnalyzer.detectStartingPosition(grid)
        if (startCheck.isStartPosition) {
            val needReset = !hasInitializedGame ||
                currentBoard.historySize() > 0 ||
                currentPlayingAsWhite != startCheck.playingAsWhite
            hasInitializedGame = true
            if (needReset) {
                events.add(BoardTrackingEvent.NewGameDetected(startCheck.playingAsWhite, rect))
            }
            return events
        }

        hasInitializedGame = true

        // Match legal 1-ply (or 2-ply) move from currentBoard
        val match = BoardVisionAnalyzer.matchLegalMove(currentBoard, grid, currentPlayingAsWhite)
        if (match != null && match.moves.isNotEmpty()) {
            // Reset stability counter so we don't double-fire on the same move before board updates
            pendingStableCount = 0
            pendingGrid = null
            events.add(BoardTrackingEvent.MovesDetected(match.moves, rect))
        }

        return events
    }
}
