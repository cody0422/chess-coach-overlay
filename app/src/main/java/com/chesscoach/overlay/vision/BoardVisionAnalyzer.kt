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
    val grid: Array<SquareOccupancy>?,
    val framesCaptured: Long = 0L,
    val lastBestScore: Float = detection.confidence,
    val lastDetectionNote: String = "",
    val frameMinLum: Int = -1,
    val frameMaxLum: Int = -1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameAnalysis) return false
        return detection == other.detection &&
            grid.contentEquals(other.grid) &&
            framesCaptured == other.framesCaptured &&
            lastBestScore == other.lastBestScore &&
            lastDetectionNote == other.lastDetectionNote &&
            frameMinLum == other.frameMinLum &&
            frameMaxLum == other.frameMaxLum
    }

    override fun hashCode(): Int {
        var result = detection.hashCode()
        result = 31 * result + (grid?.contentHashCode() ?: 0)
        result = 31 * result + framesCaptured.hashCode()
        result = 31 * result + lastBestScore.hashCode()
        result = 31 * result + lastDetectionNote.hashCode()
        result = 31 * result + frameMinLum
        result = 31 * result + frameMaxLum
        return result
    }
}

/**
 * Pure-Kotlin, theme-agnostic chess board detector, occupancy/color classifier,
 * and legal-move differential tracker operating on ARGB (0xFFRRGGBB) pixel buffers.
 */
object BoardVisionAnalyzer {

    // Safe inner corners (15% inside each square): far from seams even under coarse-step offset
    // AND well outside tapered piece crowns/bases and corner rank/file coordinate letters.
    private val CORNER_OX = floatArrayOf(0.15f, 0.85f, 0.15f, 0.85f)
    private val CORNER_OY = floatArrayOf(0.15f, 0.15f, 0.85f, 0.85f)
    private val CANDIDATE_WIDTH_FRACS = floatArrayOf(1.00f, 0.98f, 0.95f, 0.92f, 0.88f, 0.84f, 0.80f, 0.75f, 0.70f)

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

    private data class SeamMetrics(
        val wideRatio: Float,
        val tightRatio: Float,
        val vSeamsHit: Int,
        val hSeamsHit: Int
    )

    private data class CoarsePeak(
        val score: Float,
        val left: Int,
        val top: Int,
        val size: Int
    )

    private class CandidateScratch {
        val sqR = IntArray(64)
        val sqG = IntArray(64)
        val sqB = IntArray(64)
        val evenR = IntArray(32)
        val evenG = IntArray(32)
        val evenB = IntArray(32)
        val oddR = IntArray(32)
        val oddG = IntArray(32)
        val oddB = IntArray(32)
        val rowEven = IntArray(8)
        val rowOdd = IntArray(8)
        val colEven = IntArray(8)
        val colOdd = IntArray(8)
        val tmpRgb = IntArray(3)
    }

    private data class DetectionInternal(
        val result: BoardDetectionResult,
        val bestScore: Float,
        val note: String
    )

    fun analyzeFrame(
        pixels: IntArray,
        width: Int,
        height: Int,
        lockedRect: PixelRect? = null,
        activeArrow: Move? = null,
        activeArrowPlayingAsWhite: Boolean = true
    ): FrameAnalysis {
        if (width < 64 || height < 64 || pixels.size < width * height) {
            return FrameAnalysis(
                detection = BoardDetectionResult.NOT_FOUND,
                grid = null,
                lastBestScore = 0f,
                lastDetectionNote = "invalid frame"
            )
        }
        val detInternal = if (lockedRect != null && lockedRect.width >= 64 && lockedRect.height >= 64) {
            verifyLockedBoardInternal(pixels, width, height, lockedRect)
        } else {
            detectBoardInternal(pixels, width, height)
        }
        val detection = detInternal.result
        if (!detection.found) {
            return FrameAnalysis(
                detection = detection,
                grid = null,
                lastBestScore = detInternal.bestScore,
                lastDetectionNote = detInternal.note
            )
        }
        val grid = classifyGrid(
            pixels = pixels,
            width = width,
            height = height,
            detection = detection,
            activeArrow = activeArrow,
            activeArrowPlayingAsWhite = activeArrowPlayingAsWhite
        )
        return FrameAnalysis(
            detection = detection,
            grid = grid,
            lastBestScore = detInternal.bestScore,
            lastDetectionNote = detInternal.note
        )
    }

    /**
     * Scans a screen buffer looking for a large centered square 8x8 chess board
     * (width ~80%-100% of screen width, y within ~5%-92% of screen height).
     */
    fun detectBoard(pixels: IntArray, width: Int, height: Int): BoardDetectionResult {
        return detectBoardInternal(pixels, width, height).result
    }

    private fun detectBoardInternal(pixels: IntArray, width: Int, height: Int): DetectionInternal {
        if (width < 64 || height < 64 || pixels.size < width * height) {
            return DetectionInternal(BoardDetectionResult.NOT_FOUND, 0f, "invalid frame")
        }

        val scratch = CandidateScratch()
        val minDim = min(width, height)
        val yMin = (height * 0.05f).toInt().coerceAtLeast(0)
        val yMaxTotal = (height * 0.96f).toInt().coerceAtMost(height)

        var bestCoarseScore = 0f
        val peaks = ArrayList<CoarsePeak>(8)

        // Stage 1: Fast dense coarse scan across portrait widths and vertical positions.
        // stepY = max(4, size / 90) keeps max vertical offset <= ~3px, safely inside the
        // 0.15 * sq (~10px) inner corner margin.
        for (frac in CANDIDATE_WIDTH_FRACS) {
            val size = (minDim * frac).roundToInt().coerceIn(64, minDim)
            val left = (width - size) / 2
            val maxTop = (yMaxTotal - size).coerceAtLeast(yMin)
            val stepY = max(4, size / 90)

            var top = yMin
            while (top <= maxTop) {
                val ev = evaluateCandidate(pixels, width, height, left, top, size, checkSeams = false, scratch = scratch)
                if (ev.score > bestCoarseScore) {
                    bestCoarseScore = ev.score
                }
                if (ev.score > 0.10f) {
                    var merged = false
                    for (i in peaks.indices) {
                        val p = peaks[i]
                        if (abs(p.top - top) < 24 && abs(p.size - size) < 24) {
                            if (ev.score > p.score) {
                                peaks[i] = CoarsePeak(ev.score, left, top, size)
                            }
                            merged = true
                            break
                        }
                    }
                    if (!merged) {
                        peaks.add(CoarsePeak(ev.score, left, top, size))
                        peaks.sortByDescending { it.score }
                        if (peaks.size > 6) {
                            peaks.removeAt(peaks.lastIndex)
                        }
                    }
                }
                top += stepY
            }
        }

        if (peaks.isEmpty()) {
            return DetectionInternal(BoardDetectionResult.NOT_FOUND, bestCoarseScore, "no coarse candidate")
        }

        peaks.sortByDescending { it.score }

        // Stage 2: Fine coordinate refinement around the top distinct coarse peaks (with seam alignment)
        var bestFinalEval: CandidateEval? = null
        var bestFinalLeft = 0
        var bestFinalTop = 0
        var bestFinalSize = 0
        var bestRefinedScore = bestCoarseScore

        val peaksToRefine = min(4, peaks.size)
        val refineSteps = intArrayOf(8, 4, 2, 1)
        val minRefineSize = (minDim * 0.78f).toInt().coerceAtLeast(64)

        for (pi in 0 until peaksToRefine) {
            val peak = peaks[pi]
            var curLeft = peak.left
            var curTop = peak.top
            var curSize = peak.size
            var curEval = evaluateCandidate(pixels, width, height, curLeft, curTop, curSize, checkSeams = true, scratch = scratch)
            var curScore = curEval.score

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
                            val ev = evaluateCandidate(pixels, width, height, curLeft, nt, curSize, checkSeams = true, scratch = scratch)
                            if (ev.score > curScore + 0.0005f) {
                                curTop = nt
                                curEval = ev
                                curScore = ev.score
                                improved = true
                            }
                        }
                    }
                    // Try adjusting size (keeping centered vertically/horizontally)
                    for (ds in intArrayOf(-step * 2, step * 2)) {
                        val ns = curSize + ds
                        if (ns in minRefineSize..minDim) {
                            val nl = ((width - ns) / 2).coerceIn(0, width - ns)
                            val nt = (curTop - ds / 2).coerceIn(0, height - ns)
                            val ev = evaluateCandidate(pixels, width, height, nl, nt, ns, checkSeams = true, scratch = scratch)
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
                            val ev = evaluateCandidate(pixels, width, height, nl, curTop, curSize, checkSeams = true, scratch = scratch)
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

            if (curScore > bestRefinedScore) {
                bestRefinedScore = curScore
            }

            val finalEval = evaluateCandidate(pixels, width, height, curLeft, curTop, curSize, checkSeams = true, scratch = scratch)
            if (finalEval.valid && (bestFinalEval == null || finalEval.score > bestFinalEval.score)) {
                bestFinalEval = finalEval
                bestFinalLeft = curLeft
                bestFinalTop = curTop
                bestFinalSize = curSize
            }
        }

        val winner = bestFinalEval
            ?: return DetectionInternal(
                BoardDetectionResult.NOT_FOUND,
                bestRefinedScore,
                "failed seam/border check"
            )

        val evenLum = winner.evenRgb.luminance()
        val oddLum = winner.oddRgb.luminance()
        val lightRgb = if (evenLum >= oddLum) winner.evenRgb else winner.oddRgb
        val darkRgb = if (evenLum >= oddLum) winner.oddRgb else winner.evenRgb

        val result = BoardDetectionResult(
            found = true,
            rect = PixelRect(bestFinalLeft, bestFinalTop, bestFinalSize, bestFinalSize),
            confidence = winner.confidence.coerceIn(0f, 1f),
            lightRgb = lightRgb,
            darkRgb = darkRgb,
            evenRgb = winner.evenRgb,
            oddRgb = winner.oddRgb
        )
        return DetectionInternal(
            result = result,
            bestScore = winner.score,
            note = "locked ${bestFinalSize}px @ y=$bestFinalTop sep=${winner.separation.roundToInt()}"
        )
    }

    /**
     * Verifies a user-locked manual Align box. Accepts the box as visible as long as the two
     * checkerboard parities have distinct median colors (sep >= 12f), so manual Align ALWAYS
     * works reliably as an instant fallback across any theme or board layout.
     */
    private fun verifyLockedBoardInternal(
        pixels: IntArray,
        width: Int,
        height: Int,
        rect: PixelRect
    ): DetectionInternal {
        val left = rect.left.coerceIn(0, width - 8)
        val top = rect.top.coerceIn(0, height - 8)
        val sizeW = rect.width.coerceAtMost(width - left)
        val sizeH = rect.height.coerceAtMost(height - top)
        if (sizeW < 64 || sizeH < 64) {
            return DetectionInternal(BoardDetectionResult.NOT_FOUND, 0f, "locked rect too small")
        }

        val sqW = sizeW / 8f
        val sqH = sizeH / 8f
        val scratch = CandidateScratch()
        var ei = 0
        var oi = 0
        for (r in 0..7) {
            for (c in 0..7) {
                sampleSquareCornerBg(pixels, width, height, left, top, sqW, sqH, r, c, scratch.tmpRgb)
                if (((r + c) and 1) == 0) {
                    scratch.evenR[ei] = scratch.tmpRgb[0]
                    scratch.evenG[ei] = scratch.tmpRgb[1]
                    scratch.evenB[ei] = scratch.tmpRgb[2]
                    ei++
                } else {
                    scratch.oddR[oi] = scratch.tmpRgb[0]
                    scratch.oddG[oi] = scratch.tmpRgb[1]
                    scratch.oddB[oi] = scratch.tmpRgb[2]
                    oi++
                }
            }
        }

        val medER = median32(scratch.evenR)
        val medEG = median32(scratch.evenG)
        val medEB = median32(scratch.evenB)
        val medOR = median32(scratch.oddR)
        val medOG = median32(scratch.oddG)
        val medOB = median32(scratch.oddB)
        val sep = colorDist(medER, medEG, medEB, medOR, medOG, medOB)

        if (sep < 12f) {
            return DetectionInternal(
                BoardDetectionResult.NOT_FOUND,
                0f,
                "locked rect low contrast (sep=${sep.roundToInt()})"
            )
        }

        val evenRgb = Rgb(medER, medEG, medEB)
        val oddRgb = Rgb(medOR, medOG, medOB)
        val evenLum = evenRgb.luminance()
        val oddLum = oddRgb.luminance()
        val lightRgb = if (evenLum >= oddLum) evenRgb else oddRgb
        val darkRgb = if (evenLum >= oddLum) oddRgb else evenRgb
        val conf = (sep / 50f).coerceIn(0.35f, 1f)

        return DetectionInternal(
            result = BoardDetectionResult(
                found = true,
                rect = PixelRect(left, top, sizeW, sizeH),
                confidence = conf,
                lightRgb = lightRgb,
                darkRgb = darkRgb,
                evenRgb = evenRgb,
                oddRgb = oddRgb
            ),
            bestScore = conf,
            note = "manual align locked (sep=${sep.roundToInt()})"
        )
    }

    private fun evaluateCandidate(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        size: Int,
        checkSeams: Boolean,
        scratch: CandidateScratch
    ): CandidateEval {
        val sq = size / 8f
        var ei = 0
        var oi = 0

        for (r in 0..7) {
            for (c in 0..7) {
                sampleSquareCornerBg(pixels, width, height, left, top, sq, sq, r, c, scratch.tmpRgb)
                val idx = r * 8 + c
                val mr = scratch.tmpRgb[0]
                val mg = scratch.tmpRgb[1]
                val mb = scratch.tmpRgb[2]
                scratch.sqR[idx] = mr
                scratch.sqG[idx] = mg
                scratch.sqB[idx] = mb
                if (((r + c) and 1) == 0) {
                    scratch.evenR[ei] = mr
                    scratch.evenG[ei] = mg
                    scratch.evenB[ei] = mb
                    ei++
                } else {
                    scratch.oddR[oi] = mr
                    scratch.oddG[oi] = mg
                    scratch.oddB[oi] = mb
                    oi++
                }
            }
        }

        val medER = median32(scratch.evenR)
        val medEG = median32(scratch.evenG)
        val medEB = median32(scratch.evenB)
        val medOR = median32(scratch.oddR)
        val medOG = median32(scratch.oddG)
        val medOB = median32(scratch.oddB)

        val sep = colorDist(medER, medEG, medEB, medOR, medOG, medOB)
        if (sep < 18f) {
            return CandidateEval(
                false, 0f, 0f, 0, 0, sep, 0f,
                Rgb(medER, medEG, medEB), Rgb(medOR, medOG, medOB)
            )
        }

        // Tolerance scales gently with board contrast (handles dark/slate, wood, green, blue & JPEG compression)
        val tol = (sep * 0.30f).coerceIn(20f, 38f)
        var evenIn = 0
        var oddIn = 0
        var errSum = 0f

        for (i in 0 until 8) {
            scratch.rowEven[i] = 0
            scratch.rowOdd[i] = 0
            scratch.colEven[i] = 0
            scratch.colOdd[i] = 0
        }

        for (r in 0..7) {
            for (c in 0..7) {
                val idx = r * 8 + c
                if (((r + c) and 1) == 0) {
                    val de = colorDist(scratch.sqR[idx], scratch.sqG[idx], scratch.sqB[idx], medER, medEG, medEB)
                    if (de <= tol) {
                        evenIn++
                        errSum += de
                        scratch.rowEven[r]++
                        scratch.colEven[c]++
                    }
                } else {
                    val doDist = colorDist(scratch.sqR[idx], scratch.sqG[idx], scratch.sqB[idx], medOR, medOG, medOB)
                    if (doDist <= tol) {
                        oddIn++
                        errSum += doDist
                        scratch.rowOdd[r]++
                        scratch.colOdd[c]++
                    }
                }
            }
        }

        val totalIn = evenIn + oddIn
        val minPerParity = if (checkSeams) 18 else 16
        val minTotal = if (checkSeams) 40 else 36
        if (evenIn < minPerParity || oddIn < minPerParity || totalIn < minTotal) {
            return CandidateEval(
                false, 0f, 0f, evenIn, oddIn, sep, 0f,
                Rgb(medER, medEG, medEB), Rgb(medOR, medOG, medOB)
            )
        }

        // Anti-1-row-shift boundary validation: outer rows (0 & 7) and outer cols (0 & 7)
        // must each contain alternating light & dark board squares.
        val borderRowsOk = (scratch.rowEven[0] >= 1 && scratch.rowOdd[0] >= 1) &&
            (scratch.rowEven[7] >= 1 && scratch.rowOdd[7] >= 1)
        val borderColsOk = (scratch.colEven[0] >= 1 && scratch.colOdd[0] >= 1) &&
            (scratch.colEven[7] >= 1 && scratch.colOdd[7] >= 1)
        var activeLines = 0
        for (i in 0..7) {
            if (scratch.rowEven[i] >= 1 && scratch.rowOdd[i] >= 1) activeLines++
            if (scratch.colEven[i] >= 1 && scratch.colOdd[i] >= 1) activeLines++
        }
        var borderFactor = activeLines / 16f
        borderFactor *= if (borderRowsOk && borderColsOk) 1.15f else 0.65f

        var seamRatio = 1f
        var seamScore = 1f
        var valid = true
        if (checkSeams) {
            val sm = measureSeamSharpness(pixels, width, height, left, top, sq, sep)
            seamRatio = sm.wideRatio
            // Wide seam provides blur/compression resilience; tight seam sharpens alignment to ±1-2px
            seamScore = 0.45f * sm.wideRatio + 0.55f * sm.tightRatio
            valid = sm.wideRatio >= 0.35f &&
                sm.vSeamsHit >= 6 &&
                sm.hSeamsHit >= 6 &&
                borderRowsOk &&
                borderColsOk
        }

        val inlierFrac = totalIn / 64f
        val meanInlierErr = errSum / max(1, totalIn)
        val tightness = (1f - (meanInlierErr / 42f)).coerceIn(0.2f, 1f)
        val sepFactor = (sep / 80f).coerceIn(0.35f, 1.15f)
        // Continuous score (no hard 0 cliff on seamRatio) so Stage 2 climbs smoothly to exact seams
        val score = inlierFrac * tightness * sepFactor * borderFactor * (0.30f + 0.70f * seamScore)
        val confidence = (inlierFrac * (sep / 50f).coerceAtMost(1f) * (seamRatio / 0.75f).coerceAtMost(1f))
            .coerceIn(0f, 1f)

        return CandidateEval(
            valid = valid,
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
     * sampled at BOTH 0.16 and 0.84 along each seam segment so pieces & coordinate labels do not
     * break seams. Tracks per-seam hit counts so non-board menus/cards never false-positive.
     */
    private fun measureSeamSharpness(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        sq: Float,
        expectedSep: Float
    ): SeamMetrics {
        val wideDelta = max(3, (sq * 0.07f).roundToInt())
        val tightDelta = (sq * 0.03f).roundToInt().coerceIn(1, 2)
        val minEdgeJump = max(8f, expectedSep * 0.38f)

        var wideHits = 0
        var tightHits = 0
        var total = 0
        var vSeamsHit = 0
        var hSeamsHit = 0

        // Vertical seams (between col c-1 and col c) at 0.16 and 0.84 of each row
        for (c in 1..7) {
            val sx = (left + c * sq).roundToInt()
            val xWL = (sx - wideDelta).coerceIn(0, width - 1)
            val xWR = (sx + wideDelta).coerceIn(0, width - 1)
            val xTL = (sx - tightDelta).coerceIn(0, width - 1)
            val xTR = (sx + tightDelta).coerceIn(0, width - 1)
            var seamHits = 0
            for (r in 0..7) {
                for (k in 0..1) {
                    val oy = if (k == 0) 0.16f else 0.84f
                    val py = (top + (r + oy) * sq).roundToInt().coerceIn(0, height - 1)
                    val rowOff = py * width
                    if (pixelDist(pixels[rowOff + xWL], pixels[rowOff + xWR]) >= minEdgeJump) {
                        wideHits++
                        seamHits++
                    }
                    if (pixelDist(pixels[rowOff + xTL], pixels[rowOff + xTR]) >= minEdgeJump) {
                        tightHits++
                    }
                    total++
                }
            }
            if (seamHits > 0) vSeamsHit++
        }

        // Horizontal seams (between row r-1 and row r) at 0.16 and 0.84 of each col
        for (r in 1..7) {
            val sy = (top + r * sq).roundToInt()
            val yWU = (sy - wideDelta).coerceIn(0, height - 1)
            val yWD = (sy + wideDelta).coerceIn(0, height - 1)
            val yTU = (sy - tightDelta).coerceIn(0, height - 1)
            val yTD = (sy + tightDelta).coerceIn(0, height - 1)
            var seamHits = 0
            for (c in 0..7) {
                for (k in 0..1) {
                    val ox = if (k == 0) 0.16f else 0.84f
                    val px = (left + (c + ox) * sq).roundToInt().coerceIn(0, width - 1)
                    if (pixelDist(pixels[yWU * width + px], pixels[yWD * width + px]) >= minEdgeJump) {
                        wideHits++
                        seamHits++
                    }
                    if (pixelDist(pixels[yTU * width + px], pixels[yTD * width + px]) >= minEdgeJump) {
                        tightHits++
                    }
                    total++
                }
            }
            if (seamHits > 0) hSeamsHit++
        }

        val wRatio = if (total > 0) wideHits.toFloat() / total else 0f
        val tRatio = if (total > 0) tightHits.toFloat() / total else 0f
        return SeamMetrics(wRatio, tRatio, vSeamsHit, hSeamsHit)
    }

    /**
     * Classifies all 64 squares in screen order (row 0 = top .. row 7 = bottom,
     * col 0 = left .. col 7 = right) as EMPTY, WHITE, or BLACK.
     */
    private class ArrowMaskGeometry(
        val tailX: Float,
        val tailY: Float,
        val baseX: Float,
        val baseY: Float,
        val cosA: Float,
        val sinA: Float,
        val headLen: Float,
        val shaftRadius: Float,
        val headHalfW: Float,
        val headPad: Float
    ) {
        fun contains(px: Float, py: Float): Boolean {
            // Distance to arrow shaft segment (tail -> base)
            val dx = baseX - tailX
            val dy = baseY - tailY
            val lenSq = dx * dx + dy * dy
            val segDist = if (lenSq < 1e-4f) {
                sqrt((px - tailX) * (px - tailX) + (py - tailY) * (py - tailY))
            } else {
                val t = (((px - tailX) * dx + (py - tailY) * dy) / lenSq).coerceIn(0f, 1f)
                val cx = tailX + t * dx
                val cy = tailY + t * dy
                sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
            }
            if (segDist <= shaftRadius) return true

            // Arrowhead triangle in local (u along arrow from base to tip, v perpendicular)
            val rx = px - baseX
            val ry = py - baseY
            val u = rx * cosA + ry * sinA
            if (u >= -headPad && u <= headLen + headPad) {
                val v = abs(-rx * sinA + ry * cosA)
                val frac = ((headLen - u) / max(1f, headLen)).coerceIn(0f, 1f)
                if (v <= frac * headHalfW + headPad) return true
            }
            return false
        }
    }

    private fun buildArrowMaskGeometry(
        rect: PixelRect,
        sqW: Float,
        sqH: Float,
        arrow: Move,
        playingAsWhite: Boolean
    ): ArrowMaskGeometry? {
        fun sqCenter(sqIdx: Int): Pair<Float, Float> {
            val file = Squares.fileOf(sqIdx)
            val rank = Squares.rankOf(sqIdx)
            val col = if (playingAsWhite) file else (7 - file)
            val row = if (playingAsWhite) (7 - rank) else rank
            return (rect.left + (col + 0.5f) * sqW) to (rect.top + (row + 0.5f) * sqH)
        }
        val (x1, y1) = sqCenter(arrow.from)
        val (x2, y2) = sqCenter(arrow.to)
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f) return null
        val minSq = min(sqW, sqH)
        val cosA = dx / dist
        val sinA = dy / dist
        val tailX = x1 + cosA * (minSq * 0.10f)
        val tailY = y1 + sinA * (minSq * 0.10f)
        val tipX = x2 - cosA * (minSq * 0.04f)
        val tipY = y2 - sinA * (minSq * 0.04f)
        val headLen = min(minSq * 0.28f, dist * 0.48f)
        val baseX = tipX - cosA * headLen
        val baseY = tipY - sinA * headLen
        return ArrowMaskGeometry(
            tailX = tailX,
            tailY = tailY,
            baseX = baseX,
            baseY = baseY,
            cosA = cosA,
            sinA = sinA,
            headLen = headLen,
            shaftRadius = minSq * 0.12f,
            headHalfW = minSq * 0.20f,
            headPad = minSq * 0.04f
        )
    }

    fun classifyGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        detection: BoardDetectionResult,
        activeArrow: Move? = null,
        activeArrowPlayingAsWhite: Boolean = true
    ): Array<SquareOccupancy> {
        val grid = Array(64) { SquareOccupancy.EMPTY }
        val rect = detection.rect
        if (!detection.found || rect.width < 32 || rect.height < 32) return grid

        val sqW = rect.width / 8f
        val sqH = rect.height / 8f
        val localBg = IntArray(3)
        val fgLums = IntArray(144)

        val arrowMask = if (activeArrow != null) {
            buildArrowMaskGeometry(rect, sqW, sqH, activeArrow, activeArrowPlayingAsWhite)
        } else {
            null
        }

        val boardSep = colorDist(
            detection.evenRgb.r, detection.evenRgb.g, detection.evenRgb.b,
            detection.oddRgb.r, detection.oddRgb.g, detection.oddRgb.b
        )
        // Scale foreground distance threshold cleanly across low-contrast slate & textured Ocean themes
        val fgDistThreshold = (boardSep * 0.28f).coerceIn(22f, 44f)

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
                val useLocalBg = pairDist <= 20f && localDistFromParity in 14f..280f
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

                // Sample inner 0.25..0.75 box so rank/file coordinates and Samsung Edge Panel handles
                // along outer square borders NEVER cause false piece detections!
                val startX = rect.left + (c + 0.25f) * sqW
                val spanX = 0.50f * sqW
                val startY = rect.top + (r + 0.25f) * sqH
                val spanY = 0.50f * sqH

                for (sy in 0 until samples) {
                    val py = (startY + (sy + 0.5f) * spanY / samples)
                        .roundToInt().coerceIn(0, height - 1)
                    val rowOff = py * width
                    var prevPx = -1
                    for (sx in 0 until samples) {
                        val px = (startX + (sx + 0.5f) * spanX / samples)
                            .roundToInt().coerceIn(0, width - 1)
                        if (arrowMask != null && arrowMask.contains(px.toFloat(), py.toFloat())) {
                            prevPx = -1
                            continue
                        }
                        val argb = pixels[rowOff + px]
                        val pr = (argb ushr 16) and 0xFF
                        val pg = (argb ushr 8) and 0xFF
                        val pb = argb and 0xFF

                        if (isOverlayAnnotationPixel(pr, pg, pb, bgR, bgG, bgB)) {
                            prevPx = -1
                            continue
                        }
                        validSamples++

                        if (prevPx != -1 && pixelDist(prevPx, argb) >= 30f) {
                            edgeSteps++
                        }
                        prevPx = argb

                        val dist = colorDist(pr, pg, pb, bgR, bgG, bgB)
                        if (dist > fgDistThreshold) {
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
                // Reject flat/textured empty squares (real pieces have dense fg coverage + internal edges)
                val hasPieceStructure = edgeSteps >= 5 || fgFrac >= 0.26f
                if (fgFrac >= 0.13f && hasPieceStructure && fgCount >= 8) {
                    fgLums.sort(0, fgCount)
                    val meanLum = fgLumSum.toFloat() / fgCount.toFloat()
                    val p75Lum = fgLums[(fgCount * 3) / 4].toFloat()
                    val brightFrac = brightFg.toFloat() / fgCount.toFloat()
                    val darkFrac = darkFg.toFloat() / fgCount.toFloat()

                    val isWhite = (p75Lum >= 170f && darkFrac < 0.45f) ||
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

        // Allow up to 1 stray mid-rank artifact on heavily textured themes while still requiring
        // near-full starting back two ranks on both sides (opening moves vacate a rank 2/7 square).
        if (midOccupied <= 1) {
            val whiteStart = topBlack >= 14 && botWhite >= 14 && (midOccupied == 0 || (topBlack >= 15 && botWhite >= 16) || (topBlack >= 16 && botWhite >= 15))
            if (whiteStart) {
                return StartPositionDetection(isStartPosition = true, playingAsWhite = true)
            }
            val blackStart = topWhite >= 14 && botBlack >= 14 && (midOccupied == 0 || (topWhite >= 15 && botBlack >= 16) || (topWhite >= 16 && botBlack >= 15))
            if (blackStart) {
                return StartPositionDetection(isStartPosition = true, playingAsWhite = false)
            }
        }
        return StartPositionDetection(isStartPosition = false, playingAsWhite = true)
    }

    /**
     * Infers board orientation (playingAsWhite = true when White pieces occupy the bottom two
     * ranks, false when Black pieces occupy the bottom two ranks) even when a game is already
     * 1-6 moves in so the user never has to manually pick their color.
     */
    fun inferBoardOrientation(screenGrid: Array<SquareOccupancy>): Boolean? {
        if (screenGrid.size != 64) return null
        var topWhite = 0
        var topBlack = 0
        var botWhite = 0
        var botBlack = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val occ = screenGrid[r * 8 + c]
                if (r == 0 || r == 1) {
                    if (occ == SquareOccupancy.WHITE) topWhite++
                    else if (occ == SquareOccupancy.BLACK) topBlack++
                } else if (r == 6 || r == 7) {
                    if (occ == SquareOccupancy.WHITE) botWhite++
                    else if (occ == SquareOccupancy.BLACK) botBlack++
                }
            }
        }
        if (topBlack >= 11 && botWhite >= 11 && topWhite <= 2 && botBlack <= 2) return true
        if (topWhite >= 11 && botBlack >= 11 && topBlack <= 2 && botWhite <= 2) return false
        return null
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
    data class OrientationDetected(val playingAsWhite: Boolean, val rect: PixelRect) : BoardTrackingEvent()
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

        // First board lock mid-opening (e.g. 1-6 moves already played): automatically detect
        // White vs Black orientation so the user never has to tap Flip manually!
        var effectivePlayingAsWhite = currentPlayingAsWhite
        if (!hasInitializedGame) {
            val inferred = BoardVisionAnalyzer.inferBoardOrientation(grid)
            if (inferred != null && inferred != currentPlayingAsWhite) {
                effectivePlayingAsWhite = inferred
                events.add(BoardTrackingEvent.OrientationDetected(inferred, rect))
            }
        }
        hasInitializedGame = true

        // Match legal 1-ply (or 2-ply) move from currentBoard
        val match = BoardVisionAnalyzer.matchLegalMove(currentBoard, grid, effectivePlayingAsWhite)
        if (match != null && match.moves.isNotEmpty()) {
            // Reset stability counter so we don't double-fire on the same move before board updates
            pendingStableCount = 0
            pendingGrid = null
            events.add(BoardTrackingEvent.MovesDetected(match.moves, rect))
        }

        return events
    }
}
/**
 * Pure-Kotlin formatter for the Settings Auto-Read status banner and action button label.
 * Kept Android-free so every state branch is directly covered by JVM unit tests.
 */
object AutoReadStatusFormatter {
    fun formatStatus(
        autoReadEnabled: Boolean,
        autoReadLive: Boolean,
        hasMediaProjectionGrant: Boolean,
        boardVisibleOrLocked: Boolean,
        playingAsWhite: Boolean,
        fullMoveNumber: Int,
        framesCaptured: Long,
        bestScore: Float,
        lastCaptureError: String?,
        tapInputMode: Boolean,
        frameMaxLum: Int = -1
    ): String {
        return when {
            autoReadLive && boardVisibleOrLocked -> {
                val side = if (playingAsWhite) "White" else "Black"
                "Auto-Read: Locked ($side · move $fullMoveNumber)"
            }
            autoReadLive && framesCaptured == 0L ->
                "Auto-Read: Waiting for screen frames..."
            autoReadLive && frameMaxLum in 0..11 ->
                "Auto-Read: Blank frames (${framesCaptured}f) — tap Grant Access & pick Entire screen"
            autoReadLive -> {
                val bestPct = (bestScore * 100f).toInt().coerceIn(0, 100)
                "Auto-Read: Scanning (${framesCaptured}f · best $bestPct%) — open a game"
            }
            autoReadEnabled && !hasMediaProjectionGrant && !lastCaptureError.isNullOrBlank() ->
                "Auto-Read error: ${lastCaptureError.take(90)}"
            autoReadEnabled && !hasMediaProjectionGrant ->
                "Auto-Read: Needs screen permission (tap Grant Access)"
            tapInputMode ->
                "Manual Tap Mode (tap moves on board)"
            else ->
                "Manual Pass-Through Mode"
        }
    }

    fun actionButtonLabel(
        autoReadEnabled: Boolean,
        hasMediaProjectionGrant: Boolean
    ): String {
        return if (autoReadEnabled && !hasMediaProjectionGrant) {
            "Grant Access"
        } else {
            "Re-Detect"
        }
    }
}
