package com.chesscoach.overlay.engine

import kotlin.math.abs

class ChessBoard {
    val squares = IntArray(64)
    var sideToMove: Int = Piece.WHITE
        private set
    var castlingRights: Int = CASTLE_ALL
        private set
    var enPassantSquare: Int = -1
        private set
    var halfMoveClock: Int = 0
        private set
    var fullMoveNumber: Int = 1
        private set

    var whiteKingSq: Int = 4
        private set
    var blackKingSq: Int = 60
        private set

    data class UndoState(
        val capturedPiece: Int,
        val capturedSq: Int,
        val prevCastling: Int,
        val prevEnPassant: Int,
        val prevHalfMove: Int,
        val prevFullMove: Int,
        val prevWhiteKingSq: Int,
        val prevBlackKingSq: Int,
        val movedPiece: Int
    )

    private val history = ArrayList<Pair<Move, UndoState>>()

    init {
        reset()
    }

    fun reset() {
        loadFen(START_FEN)
    }

    fun copy(): ChessBoard {
        val b = ChessBoard()
        System.arraycopy(this.squares, 0, b.squares, 0, 64)
        b.sideToMove = this.sideToMove
        b.castlingRights = this.castlingRights
        b.enPassantSquare = this.enPassantSquare
        b.halfMoveClock = this.halfMoveClock
        b.fullMoveNumber = this.fullMoveNumber
        b.whiteKingSq = this.whiteKingSq
        b.blackKingSq = this.blackKingSq
        return b
    }

    fun historySize(): Int = history.size

    fun lastMove(): Move? = history.lastOrNull()?.first

    fun loadFen(fen: String): Boolean {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.size < 4) return false

        squares.fill(Piece.EMPTY)
        whiteKingSq = -1
        blackKingSq = -1

        val ranks = parts[0].split("/")
        if (ranks.size != 8) return false

        for (rIdx in 0..7) {
            val rank = 7 - rIdx
            var file = 0
            for (ch in ranks[rIdx]) {
                if (ch in '1'..'8') {
                    file += (ch - '0')
                } else {
                    if (file > 7) return false
                    val piece = charToPiece(ch)
                    if (piece == Piece.EMPTY) return false
                    val sq = Squares.of(file, rank)
                    squares[sq] = piece
                    if (piece == Piece.WHITE * Piece.KING) whiteKingSq = sq
                    if (piece == Piece.BLACK * Piece.KING) blackKingSq = sq
                    file++
                }
            }
            if (file != 8) return false
        }

        sideToMove = if (parts[1] == "b") Piece.BLACK else Piece.WHITE

        var cr = 0
        if (parts[2] != "-") {
            for (ch in parts[2]) {
                when (ch) {
                    'K' -> cr = cr or CASTLE_WK
                    'Q' -> cr = cr or CASTLE_WQ
                    'k' -> cr = cr or CASTLE_BK
                    'q' -> cr = cr or CASTLE_BQ
                }
            }
        }
        castlingRights = cr

        enPassantSquare = if (parts[3] == "-") -1 else Squares.parse(parts[3])
        halfMoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0
        fullMoveNumber = parts.getOrNull(5)?.toIntOrNull() ?: 1
        history.clear()
        return true
    }

    fun toFen(): String {
        val sb = StringBuilder(80)
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = squares[Squares.of(file, rank)]
                if (p == Piece.EMPTY) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(pieceToChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }

        sb.append(if (sideToMove == Piece.WHITE) " w " else " b ")

        if (castlingRights == 0) {
            sb.append('-')
        } else {
            if ((castlingRights and CASTLE_WK) != 0) sb.append('K')
            if ((castlingRights and CASTLE_WQ) != 0) sb.append('Q')
            if ((castlingRights and CASTLE_BK) != 0) sb.append('k')
            if ((castlingRights and CASTLE_BQ) != 0) sb.append('q')
        }

        sb.append(' ')
        sb.append(if (enPassantSquare in 0..63) Squares.toName(enPassantSquare) else "-")
        sb.append(' ')
        sb.append(halfMoveClock)
        sb.append(' ')
        sb.append(fullMoveNumber)
        return sb.toString()
    }

    fun computeZobristHash(): Long {
        var h = 0L
        for (sq in 0..63) {
            val p = squares[sq]
            if (p != Piece.EMPTY) {
                val pIdx = pieceToZobristIndex(p)
                h = h xor ZOBRIST_PIECES[pIdx * 64 + sq]
            }
        }
        if (sideToMove == Piece.BLACK) {
            h = h xor ZOBRIST_BLACK_TO_MOVE
        }
        h = h xor ZOBRIST_CASTLING[castlingRights and 15]
        if (enPassantSquare in 0..63) {
            h = h xor ZOBRIST_EP_FILE[Squares.fileOf(enPassantSquare)]
        }
        return h
    }

    fun makeMove(move: Move): UndoState {
        val mover = sideToMove
        val movedPiece = squares[move.from]
        val movedType = Piece.typeOf(movedPiece)

        var capturedSq = move.to
        var capturedPiece = squares[move.to]
        if (move.isEnPassant) {
            capturedSq = if (mover == Piece.WHITE) move.to - 8 else move.to + 8
            capturedPiece = squares[capturedSq]
            squares[capturedSq] = Piece.EMPTY
        }

        val undo = UndoState(
            capturedPiece = capturedPiece,
            capturedSq = capturedSq,
            prevCastling = castlingRights,
            prevEnPassant = enPassantSquare,
            prevHalfMove = halfMoveClock,
            prevFullMove = fullMoveNumber,
            prevWhiteKingSq = whiteKingSq,
            prevBlackKingSq = blackKingSq,
            movedPiece = movedPiece
        )

        squares[move.from] = Piece.EMPTY
        val placedPiece = if (move.promotion != Piece.EMPTY) {
            Piece.make(mover, Piece.typeOf(move.promotion))
        } else {
            movedPiece
        }
        squares[move.to] = placedPiece

        if (movedType == Piece.KING) {
            if (mover == Piece.WHITE) {
                whiteKingSq = move.to
                castlingRights = castlingRights and (CASTLE_WK or CASTLE_WQ).inv()
            } else {
                blackKingSq = move.to
                castlingRights = castlingRights and (CASTLE_BK or CASTLE_BQ).inv()
            }
            if (move.isCastle) {
                when (move.to) {
                    6 -> { // White O-O: h1(7) -> f1(5)
                        squares[5] = squares[7]
                        squares[7] = Piece.EMPTY
                    }
                    2 -> { // White O-O-O: a1(0) -> d1(3)
                        squares[3] = squares[0]
                        squares[0] = Piece.EMPTY
                    }
                    62 -> { // Black O-O: h8(63) -> f8(61)
                        squares[61] = squares[63]
                        squares[63] = Piece.EMPTY
                    }
                    58 -> { // Black O-O-O: a8(56) -> d8(59)
                        squares[59] = squares[56]
                        squares[56] = Piece.EMPTY
                    }
                }
            }
        }

        // Update castling rights if a rook moved from or was captured on a corner square
        if (move.from == 0 || move.to == 0) castlingRights = castlingRights and CASTLE_WQ.inv()
        if (move.from == 7 || move.to == 7) castlingRights = castlingRights and CASTLE_WK.inv()
        if (move.from == 56 || move.to == 56) castlingRights = castlingRights and CASTLE_BQ.inv()
        if (move.from == 63 || move.to == 63) castlingRights = castlingRights and CASTLE_BK.inv()

        // En passant square
        enPassantSquare = if (movedType == Piece.PAWN && abs(move.to - move.from) == 16) {
            (move.from + move.to) / 2
        } else {
            -1
        }

        // Half-move & full-move clocks
        halfMoveClock = if (movedType == Piece.PAWN || capturedPiece != Piece.EMPTY) 0 else halfMoveClock + 1
        if (mover == Piece.BLACK) {
            fullMoveNumber++
        }

        sideToMove = -mover
        return undo
    }

    fun unmakeMove(move: Move, undo: UndoState) {
        val mover = -sideToMove
        sideToMove = mover

        squares[move.from] = undo.movedPiece
        squares[move.to] = Piece.EMPTY

        if (move.isEnPassant) {
            squares[undo.capturedSq] = undo.capturedPiece
        } else {
            squares[move.to] = undo.capturedPiece
        }

        if (move.isCastle) {
            when (move.to) {
                6 -> {
                    squares[7] = squares[5]
                    squares[5] = Piece.EMPTY
                }
                2 -> {
                    squares[0] = squares[3]
                    squares[3] = Piece.EMPTY
                }
                62 -> {
                    squares[63] = squares[61]
                    squares[61] = Piece.EMPTY
                }
                58 -> {
                    squares[56] = squares[59]
                    squares[59] = Piece.EMPTY
                }
            }
        }

        castlingRights = undo.prevCastling
        enPassantSquare = undo.prevEnPassant
        halfMoveClock = undo.prevHalfMove
        fullMoveNumber = undo.prevFullMove
        whiteKingSq = undo.prevWhiteKingSq
        blackKingSq = undo.prevBlackKingSq
    }

    fun pushMove(move: Move): Boolean {
        val legal = generateLegalMoves()
        val matched = legal.firstOrNull {
            it.from == move.from && it.to == move.to &&
                (Piece.typeOf(it.promotion) == Piece.typeOf(move.promotion))
        } ?: return false
        val undo = makeMove(matched)
        history.add(matched to undo)
        return true
    }

    fun pushUciMove(uci: String): Move? {
        val clean = uci.trim().lowercase()
        if (clean.length < 4) return null
        val from = Squares.parse(clean.substring(0, 2))
        val to = Squares.parse(clean.substring(2, 4))
        if (from !in 0..63 || to !in 0..63) return null
        val promo = when (clean.getOrNull(4)) {
            'q' -> Piece.QUEEN
            'r' -> Piece.ROOK
            'b' -> Piece.BISHOP
            'n' -> Piece.KNIGHT
            else -> Piece.EMPTY
        }
        val legal = generateLegalMoves()
        val matched = legal.firstOrNull {
            it.from == from && it.to == to && Piece.typeOf(it.promotion) == promo
        } ?: return null
        val undo = makeMove(matched)
        history.add(matched to undo)
        return matched
    }

    fun undoLastMove(): Move? {
        if (history.isEmpty()) return null
        val (move, undo) = history.removeAt(history.size - 1)
        unmakeMove(move, undo)
        return move
    }

    fun isInCheck(color: Int): Boolean {
        val kSq = if (color == Piece.WHITE) whiteKingSq else blackKingSq
        if (kSq !in 0..63) return false
        return isSquareAttacked(kSq, -color)
    }

    fun isSquareAttacked(sq: Int, byColor: Int): Boolean {
        val r = Squares.rankOf(sq)
        val f = Squares.fileOf(sq)

        // Pawn attacks
        val pawnRank = if (byColor == Piece.WHITE) r - 1 else r + 1
        if (pawnRank in 0..7) {
            val pawnPiece = Piece.make(byColor, Piece.PAWN)
            if (f > 0 && squares[Squares.of(f - 1, pawnRank)] == pawnPiece) return true
            if (f < 7 && squares[Squares.of(f + 1, pawnRank)] == pawnPiece) return true
        }

        // Knight attacks
        val knightPiece = Piece.make(byColor, Piece.KNIGHT)
        for (i in 0..7) {
            val nr = r + KNIGHT_DR[i]
            val nf = f + KNIGHT_DF[i]
            if (nr in 0..7 && nf in 0..7 && squares[Squares.of(nf, nr)] == knightPiece) {
                return true
            }
        }

        // King attacks
        val kingPiece = Piece.make(byColor, Piece.KING)
        for (i in 0..7) {
            val kr = r + KING_DR[i]
            val kf = f + KING_DF[i]
            if (kr in 0..7 && kf in 0..7 && squares[Squares.of(kf, kr)] == kingPiece) {
                return true
            }
        }

        // Diagonal sliders (Bishop / Queen)
        val bishopPiece = Piece.make(byColor, Piece.BISHOP)
        val queenPiece = Piece.make(byColor, Piece.QUEEN)
        for (d in 0..3) {
            var cr = r + DIAG_DR[d]
            var cf = f + DIAG_DF[d]
            while (cr in 0..7 && cf in 0..7) {
                val p = squares[Squares.of(cf, cr)]
                if (p != Piece.EMPTY) {
                    if (p == bishopPiece || p == queenPiece) return true
                    break
                }
                cr += DIAG_DR[d]
                cf += DIAG_DF[d]
            }
        }

        // Orthogonal sliders (Rook / Queen)
        val rookPiece = Piece.make(byColor, Piece.ROOK)
        for (d in 0..3) {
            var cr = r + ORTHO_DR[d]
            var cf = f + ORTHO_DF[d]
            while (cr in 0..7 && cf in 0..7) {
                val p = squares[Squares.of(cf, cr)]
                if (p != Piece.EMPTY) {
                    if (p == rookPiece || p == queenPiece) return true
                    break
                }
                cr += ORTHO_DR[d]
                cf += ORTHO_DF[d]
            }
        }

        return false
    }

    /**
     * Returns (attackerSquare, attackerPieceType) for the lowest-value piece of [byColor]
     * attacking [sq], or (-1, 0) if not attacked.
     */
    fun findLowestAttacker(sq: Int, byColor: Int): Pair<Int, Int> {
        val r = Squares.rankOf(sq)
        val f = Squares.fileOf(sq)

        // 1. Pawn
        val pawnRank = if (byColor == Piece.WHITE) r - 1 else r + 1
        if (pawnRank in 0..7) {
            val pawnPiece = Piece.make(byColor, Piece.PAWN)
            if (f > 0) {
                val s = Squares.of(f - 1, pawnRank)
                if (squares[s] == pawnPiece) return s to Piece.PAWN
            }
            if (f < 7) {
                val s = Squares.of(f + 1, pawnRank)
                if (squares[s] == pawnPiece) return s to Piece.PAWN
            }
        }

        // 2. Knight
        val knightPiece = Piece.make(byColor, Piece.KNIGHT)
        for (i in 0..7) {
            val nr = r + KNIGHT_DR[i]
            val nf = f + KNIGHT_DF[i]
            if (nr in 0..7 && nf in 0..7) {
                val s = Squares.of(nf, nr)
                if (squares[s] == knightPiece) return s to Piece.KNIGHT
            }
        }

        // 3. Bishop
        val bishopPiece = Piece.make(byColor, Piece.BISHOP)
        val queenPiece = Piece.make(byColor, Piece.QUEEN)
        var queenSq = -1
        for (d in 0..3) {
            var cr = r + DIAG_DR[d]
            var cf = f + DIAG_DF[d]
            while (cr in 0..7 && cf in 0..7) {
                val s = Squares.of(cf, cr)
                val p = squares[s]
                if (p != Piece.EMPTY) {
                    if (p == bishopPiece) return s to Piece.BISHOP
                    if (p == queenPiece && queenSq == -1) queenSq = s
                    break
                }
                cr += DIAG_DR[d]
                cf += DIAG_DF[d]
            }
        }

        // 4. Rook
        val rookPiece = Piece.make(byColor, Piece.ROOK)
        for (d in 0..3) {
            var cr = r + ORTHO_DR[d]
            var cf = f + ORTHO_DF[d]
            while (cr in 0..7 && cf in 0..7) {
                val s = Squares.of(cf, cr)
                val p = squares[s]
                if (p != Piece.EMPTY) {
                    if (p == rookPiece) return s to Piece.ROOK
                    if (p == queenPiece && queenSq == -1) queenSq = s
                    break
                }
                cr += ORTHO_DR[d]
                cf += ORTHO_DF[d]
            }
        }

        // 5. Queen
        if (queenSq != -1) return queenSq to Piece.QUEEN

        // 6. King
        val kingPiece = Piece.make(byColor, Piece.KING)
        for (i in 0..7) {
            val kr = r + KING_DR[i]
            val kf = f + KING_DF[i]
            if (kr in 0..7 && kf in 0..7) {
                val s = Squares.of(kf, kr)
                if (squares[s] == kingPiece) return s to Piece.KING
            }
        }

        return -1 to Piece.EMPTY
    }

    fun generateLegalMoves(capturesOnly: Boolean = false): List<Move> {
        val pseudo = ArrayList<Move>(48)
        generatePseudoLegalMoves(pseudo, capturesOnly)
        val legal = ArrayList<Move>(pseudo.size)
        val mover = sideToMove
        for (m in pseudo) {
            val undo = makeMove(m)
            if (!isInCheck(mover)) {
                legal.add(m)
            }
            unmakeMove(m, undo)
        }
        return legal
    }

    private fun generatePseudoLegalMoves(out: MutableList<Move>, capturesOnly: Boolean) {
        val us = sideToMove
        val them = -us

        for (sq in 0..63) {
            val p = squares[sq]
            if (Piece.colorOf(p) != us) continue
            val r = Squares.rankOf(sq)
            val f = Squares.fileOf(sq)

            when (Piece.typeOf(p)) {
                Piece.PAWN -> {
                    val dir = if (us == Piece.WHITE) 1 else -1
                    val startRank = if (us == Piece.WHITE) 1 else 6
                    val promoRank = if (us == Piece.WHITE) 7 else 0
                    val nextRank = r + dir

                    // Forward moves
                    if (nextRank in 0..7) {
                        val oneStep = Squares.of(f, nextRank)
                        if (squares[oneStep] == Piece.EMPTY) {
                            if (nextRank == promoRank) {
                                addPromotions(out, sq, oneStep)
                            } else if (!capturesOnly) {
                                out.add(Move(sq, oneStep))
                                if (r == startRank) {
                                    val twoStep = Squares.of(f, r + 2 * dir)
                                    if (squares[twoStep] == Piece.EMPTY) {
                                        out.add(Move(sq, twoStep))
                                    }
                                }
                            }
                        }

                        // Diagonal captures + en passant
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val capSq = Squares.of(nf, nextRank)
                            if (Piece.colorOf(squares[capSq]) == them) {
                                if (nextRank == promoRank) {
                                    addPromotions(out, sq, capSq)
                                } else {
                                    out.add(Move(sq, capSq))
                                }
                            } else if (capSq == enPassantSquare) {
                                out.add(Move(sq, capSq, isEnPassant = true))
                            }
                        }
                    }
                }

                Piece.KNIGHT -> {
                    for (i in 0..7) {
                        val nr = r + KNIGHT_DR[i]
                        val nf = f + KNIGHT_DF[i]
                        if (nr !in 0..7 || nf !in 0..7) continue
                        val to = Squares.of(nf, nr)
                        val target = squares[to]
                        if (Piece.colorOf(target) == us) continue
                        if (capturesOnly && target == Piece.EMPTY) continue
                        out.add(Move(sq, to))
                    }
                }

                Piece.BISHOP -> addSliderMoves(out, sq, r, f, us, DIAG_DR, DIAG_DF, capturesOnly)
                Piece.ROOK -> addSliderMoves(out, sq, r, f, us, ORTHO_DR, ORTHO_DF, capturesOnly)
                Piece.QUEEN -> {
                    addSliderMoves(out, sq, r, f, us, DIAG_DR, DIAG_DF, capturesOnly)
                    addSliderMoves(out, sq, r, f, us, ORTHO_DR, ORTHO_DF, capturesOnly)
                }

                Piece.KING -> {
                    for (i in 0..7) {
                        val kr = r + KING_DR[i]
                        val kf = f + KING_DF[i]
                        if (kr !in 0..7 || kf !in 0..7) continue
                        val to = Squares.of(kf, kr)
                        val target = squares[to]
                        if (Piece.colorOf(target) == us) continue
                        if (capturesOnly && target == Piece.EMPTY) continue
                        out.add(Move(sq, to))
                    }
                    if (!capturesOnly) {
                        addCastlingMoves(out, us)
                    }
                }
            }
        }
    }

    private fun addPromotions(out: MutableList<Move>, from: Int, to: Int) {
        out.add(Move(from, to, promotion = Piece.QUEEN))
        out.add(Move(from, to, promotion = Piece.ROOK))
        out.add(Move(from, to, promotion = Piece.BISHOP))
        out.add(Move(from, to, promotion = Piece.KNIGHT))
    }

    private fun addSliderMoves(
        out: MutableList<Move>,
        from: Int,
        r: Int,
        f: Int,
        us: Int,
        drs: IntArray,
        dfs: IntArray,
        capturesOnly: Boolean
    ) {
        for (d in drs.indices) {
            var cr = r + drs[d]
            var cf = f + dfs[d]
            while (cr in 0..7 && cf in 0..7) {
                val to = Squares.of(cf, cr)
                val target = squares[to]
                if (target == Piece.EMPTY) {
                    if (!capturesOnly) out.add(Move(from, to))
                } else {
                    if (Piece.colorOf(target) != us) {
                        out.add(Move(from, to))
                    }
                    break
                }
                cr += drs[d]
                cf += dfs[d]
            }
        }
    }

    private fun addCastlingMoves(out: MutableList<Move>, us: Int) {
        val them = -us
        if (us == Piece.WHITE) {
            if (whiteKingSq != 4) return
            if ((castlingRights and CASTLE_WK) != 0 &&
                squares[5] == Piece.EMPTY &&
                squares[6] == Piece.EMPTY &&
                squares[7] == Piece.WHITE * Piece.ROOK &&
                !isSquareAttacked(4, them) &&
                !isSquareAttacked(5, them) &&
                !isSquareAttacked(6, them)
            ) {
                out.add(Move(4, 6, isCastle = true))
            }
            if ((castlingRights and CASTLE_WQ) != 0 &&
                squares[3] == Piece.EMPTY &&
                squares[2] == Piece.EMPTY &&
                squares[1] == Piece.EMPTY &&
                squares[0] == Piece.WHITE * Piece.ROOK &&
                !isSquareAttacked(4, them) &&
                !isSquareAttacked(3, them) &&
                !isSquareAttacked(2, them)
            ) {
                out.add(Move(4, 2, isCastle = true))
            }
        } else {
            if (blackKingSq != 60) return
            if ((castlingRights and CASTLE_BK) != 0 &&
                squares[61] == Piece.EMPTY &&
                squares[62] == Piece.EMPTY &&
                squares[63] == Piece.BLACK * Piece.ROOK &&
                !isSquareAttacked(60, them) &&
                !isSquareAttacked(61, them) &&
                !isSquareAttacked(62, them)
            ) {
                out.add(Move(60, 62, isCastle = true))
            }
            if ((castlingRights and CASTLE_BQ) != 0 &&
                squares[59] == Piece.EMPTY &&
                squares[58] == Piece.EMPTY &&
                squares[57] == Piece.EMPTY &&
                squares[56] == Piece.BLACK * Piece.ROOK &&
                !isSquareAttacked(60, them) &&
                !isSquareAttacked(59, them) &&
                !isSquareAttacked(58, them)
            ) {
                out.add(Move(60, 58, isCastle = true))
            }
        }
    }

    /**
     * Detects all pieces of [color] that are absolutely pinned to their King.
     */
    fun findPinnedSquares(color: Int): List<PinnedPiece> {
        val kSq = if (color == Piece.WHITE) whiteKingSq else blackKingSq
        if (kSq !in 0..63) return emptyList()
        val kr = Squares.rankOf(kSq)
        val kf = Squares.fileOf(kSq)
        val enemy = -color
        val result = ArrayList<PinnedPiece>()

        val allDr = intArrayOf(1, 1, -1, -1, 1, -1, 0, 0)
        val allDf = intArrayOf(1, -1, 1, -1, 0, 0, 1, -1)

        for (d in 0..7) {
            val isDiag = d < 4
            var cr = kr + allDr[d]
            var cf = kf + allDf[d]
            var friendlySq = -1

            while (cr in 0..7 && cf in 0..7) {
                val sq = Squares.of(cf, cr)
                val p = squares[sq]
                if (p != Piece.EMPTY) {
                    if (Piece.colorOf(p) == color) {
                        if (friendlySq == -1) {
                            friendlySq = sq
                        } else {
                            // Two friendly pieces block the ray -> no pin
                            break
                        }
                    } else {
                        // Enemy piece
                        if (friendlySq != -1) {
                            val pType = Piece.typeOf(p)
                            val isPinner = if (isDiag) {
                                pType == Piece.BISHOP || pType == Piece.QUEEN
                            } else {
                                pType == Piece.ROOK || pType == Piece.QUEEN
                            }
                            if (isPinner) {
                                result.add(PinnedPiece(friendlySq, sq, kSq))
                            }
                        }
                        break
                    }
                }
                cr += allDr[d]
                cf += allDf[d]
            }
        }
        return result
    }

    /**
     * Detects tactical board warnings for [forColor]:
     * - King in check (CHECK)
     * - Undefended pieces under attack (HANGING)
     * - Pieces attacked by strictly lower-value opponent pieces (THREATENED)
     */
    fun findBoardWarnings(forColor: Int): List<BoardWarning> {
        val warnings = ArrayList<BoardWarning>()
        val enemy = -forColor

        val kingSq = if (forColor == Piece.WHITE) whiteKingSq else blackKingSq
        if (kingSq in 0..63 && isSquareAttacked(kingSq, enemy)) {
            val (atkSq, _) = findLowestAttacker(kingSq, enemy)
            warnings.add(BoardWarning(kingSq, WarningType.CHECK, atkSq))
        }

        for (sq in 0..63) {
            val p = squares[sq]
            if (Piece.colorOf(p) != forColor) continue
            val pType = Piece.typeOf(p)
            if (pType == Piece.KING) continue

            val (attackerSq, attackerType) = findLowestAttacker(sq, enemy)
            if (attackerSq == -1) continue

            val defended = isSquareAttacked(sq, forColor)
            if (!defended) {
                warnings.add(BoardWarning(sq, WarningType.HANGING, attackerSq))
            } else if (Piece.materialValue(attackerType) < Piece.materialValue(pType)) {
                warnings.add(BoardWarning(sq, WarningType.THREATENED, attackerSq))
            }
        }
        return warnings
    }

    fun getGameStatus(): GameStatus {
        val legal = generateLegalMoves()
        if (legal.isEmpty()) {
            return if (isInCheck(sideToMove)) {
                if (sideToMove == Piece.WHITE) GameStatus.CHECKMATE_BLACK_WINS
                else GameStatus.CHECKMATE_WHITE_WINS
            } else {
                GameStatus.STALEMATE
            }
        }
        if (halfMoveClock >= 100) return GameStatus.DRAW_FIFTY_MOVE
        if (isInsufficientMaterial()) return GameStatus.DRAW_INSUFFICIENT
        return GameStatus.ONGOING
    }

    fun isInsufficientMaterial(): Boolean {
        var whiteMinors = 0
        var blackMinors = 0
        var whiteBishopColor = -1
        var blackBishopColor = -1

        for (sq in 0..63) {
            val p = squares[sq]
            if (p == Piece.EMPTY) continue
            val type = Piece.typeOf(p)
            if (type == Piece.PAWN || type == Piece.ROOK || type == Piece.QUEEN) {
                return false
            }
            if (type == Piece.KNIGHT || type == Piece.BISHOP) {
                if (Piece.colorOf(p) == Piece.WHITE) {
                    whiteMinors++
                    if (type == Piece.BISHOP) {
                        whiteBishopColor = (Squares.rankOf(sq) + Squares.fileOf(sq)) and 1
                    }
                } else {
                    blackMinors++
                    if (type == Piece.BISHOP) {
                        blackBishopColor = (Squares.rankOf(sq) + Squares.fileOf(sq)) and 1
                    }
                }
            }
        }

        if (whiteMinors == 0 && blackMinors == 0) return true
        if (whiteMinors == 1 && blackMinors == 0) return true
        if (whiteMinors == 0 && blackMinors == 1) return true
        if (whiteMinors == 1 && blackMinors == 1 &&
            whiteBishopColor != -1 && blackBishopColor != -1 &&
            whiteBishopColor == blackBishopColor
        ) {
            return true
        }
        return false
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        const val CASTLE_WK = 1
        const val CASTLE_WQ = 2
        const val CASTLE_BK = 4
        const val CASTLE_BQ = 8
        const val CASTLE_ALL = CASTLE_WK or CASTLE_WQ or CASTLE_BK or CASTLE_BQ

        private val KNIGHT_DR = intArrayOf(2, 2, 1, 1, -1, -1, -2, -2)
        private val KNIGHT_DF = intArrayOf(1, -1, 2, -2, 2, -2, 1, -1)
        private val KING_DR = intArrayOf(1, 1, 1, 0, 0, -1, -1, -1)
        private val KING_DF = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        private val DIAG_DR = intArrayOf(1, 1, -1, -1)
        private val DIAG_DF = intArrayOf(1, -1, 1, -1)
        private val ORTHO_DR = intArrayOf(1, -1, 0, 0)
        private val ORTHO_DF = intArrayOf(0, 0, 1, -1)

        // Deterministic PRNG for Zobrist keys
        private val ZOBRIST_PIECES = LongArray(12 * 64)
        private val ZOBRIST_CASTLING = LongArray(16)
        private val ZOBRIST_EP_FILE = LongArray(8)
        private var ZOBRIST_BLACK_TO_MOVE: Long = 0L

        init {
            var seed = 0x9E3779B97F4A7C15UL.toLong()
            fun nextRand(): Long {
                seed = seed xor (seed shl 13)
                seed = seed xor (seed ushr 7)
                seed = seed xor (seed shl 17)
                return seed
            }
            for (i in ZOBRIST_PIECES.indices) ZOBRIST_PIECES[i] = nextRand()
            for (i in ZOBRIST_CASTLING.indices) ZOBRIST_CASTLING[i] = nextRand()
            for (i in ZOBRIST_EP_FILE.indices) ZOBRIST_EP_FILE[i] = nextRand()
            ZOBRIST_BLACK_TO_MOVE = nextRand()
        }

        private fun pieceToZobristIndex(piece: Int): Int {
            val typeIdx = Piece.typeOf(piece) - 1 // 0..5
            return if (piece > 0) typeIdx else typeIdx + 6
        }

        private fun charToPiece(c: Char): Int = when (c) {
            'P' -> Piece.WHITE * Piece.PAWN
            'N' -> Piece.WHITE * Piece.KNIGHT
            'B' -> Piece.WHITE * Piece.BISHOP
            'R' -> Piece.WHITE * Piece.ROOK
            'Q' -> Piece.WHITE * Piece.QUEEN
            'K' -> Piece.WHITE * Piece.KING
            'p' -> Piece.BLACK * Piece.PAWN
            'n' -> Piece.BLACK * Piece.KNIGHT
            'b' -> Piece.BLACK * Piece.BISHOP
            'r' -> Piece.BLACK * Piece.ROOK
            'q' -> Piece.BLACK * Piece.QUEEN
            'k' -> Piece.BLACK * Piece.KING
            else -> Piece.EMPTY
        }

        private fun pieceToChar(p: Int): Char = when (p) {
            Piece.WHITE * Piece.PAWN -> 'P'
            Piece.WHITE * Piece.KNIGHT -> 'N'
            Piece.WHITE * Piece.BISHOP -> 'B'
            Piece.WHITE * Piece.ROOK -> 'R'
            Piece.WHITE * Piece.QUEEN -> 'Q'
            Piece.WHITE * Piece.KING -> 'K'
            Piece.BLACK * Piece.PAWN -> 'p'
            Piece.BLACK * Piece.KNIGHT -> 'n'
            Piece.BLACK * Piece.BISHOP -> 'b'
            Piece.BLACK * Piece.ROOK -> 'r'
            Piece.BLACK * Piece.QUEEN -> 'q'
            Piece.BLACK * Piece.KING -> 'k'
            else -> '.'
        }
    }
}
