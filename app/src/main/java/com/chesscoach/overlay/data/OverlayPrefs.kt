package com.chesscoach.overlay.data

import android.content.Context
import android.content.SharedPreferences
import com.chesscoach.overlay.engine.EloAccuracyHelper
import com.chesscoach.overlay.engine.PlayStyle

data class BoardRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class PresetChessApp(
    val name: String,
    val packageName: String,
    val subtitle: String
)

class OverlayPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        val schema = prefs.getInt(KEY_SCHEMA_VERSION, 1)
        if (schema < CURRENT_SCHEMA_VERSION) {
            // Upgrade from v1.0.0 cleanly to uncluttered v2.0.0 defaults while preserving
            // user's Elo, Accuracy, Depth, Play Style, and chosen chess app.
            prefs.edit()
                .putBoolean(KEY_AUTO_READ_SCREEN, true)
                .putBoolean(KEY_SHOW_MULTI_ARROWS, false)
                .putBoolean(KEY_SHOW_EVAL_BAR, false)
                .putBoolean(KEY_MARK_PINNED, false)
                .putBoolean(KEY_MARK_LINE_RISKS, false)
                .putBoolean(KEY_SHOW_PIECE_MARKERS, false)
                .putBoolean(KEY_TAP_INPUT_MODE, false)
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .apply()
        }
    }

    var targetElo: Int
        get() = prefs.getInt(KEY_TARGET_ELO, EloAccuracyHelper.DEFAULT_ELO)
            .coerceIn(EloAccuracyHelper.MIN_ELO, EloAccuracyHelper.MAX_ELO)
        set(value) {
            prefs.edit().putInt(
                KEY_TARGET_ELO,
                value.coerceIn(EloAccuracyHelper.MIN_ELO, EloAccuracyHelper.MAX_ELO)
            ).apply()
        }

    var targetAccuracy: Int
        get() = prefs.getInt(KEY_TARGET_ACCURACY, EloAccuracyHelper.DEFAULT_ACCURACY)
            .coerceIn(EloAccuracyHelper.MIN_ACCURACY, EloAccuracyHelper.MAX_ACCURACY)
        set(value) {
            prefs.edit().putInt(
                KEY_TARGET_ACCURACY,
                value.coerceIn(EloAccuracyHelper.MIN_ACCURACY, EloAccuracyHelper.MAX_ACCURACY)
            ).apply()
        }

    var searchDepth: Int
        get() = prefs.getInt(KEY_SEARCH_DEPTH, EloAccuracyHelper.DEFAULT_DEPTH)
            .coerceIn(EloAccuracyHelper.MIN_DEPTH, EloAccuracyHelper.MAX_DEPTH)
        set(value) {
            prefs.edit().putInt(
                KEY_SEARCH_DEPTH,
                value.coerceIn(EloAccuracyHelper.MIN_DEPTH, EloAccuracyHelper.MAX_DEPTH)
            ).apply()
        }

    var autoReadScreen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ_SCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ_SCREEN, value).apply()

    var tapInputMode: Boolean
        get() = prefs.getBoolean(KEY_TAP_INPUT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_TAP_INPUT_MODE, value).apply()

    var showArrows: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ARROWS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ARROWS, value).apply()

    var showMultiArrows: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MULTI_ARROWS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_MULTI_ARROWS, value).apply()

    var analyzeMyMoves: Boolean
        get() = prefs.getBoolean(KEY_ANALYZE_MY_MOVES, true)
        set(value) = prefs.edit().putBoolean(KEY_ANALYZE_MY_MOVES, value).apply()

    var boardWarnings: Boolean
        get() = prefs.getBoolean(KEY_BOARD_WARNINGS, true)
        set(value) = prefs.edit().putBoolean(KEY_BOARD_WARNINGS, value).apply()

    var showEvalBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EVAL_BAR, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_EVAL_BAR, value).apply()

    var markPinnedPieces: Boolean
        get() = prefs.getBoolean(KEY_MARK_PINNED, false)
        set(value) = prefs.edit().putBoolean(KEY_MARK_PINNED, value).apply()

    var markLineRisks: Boolean
        get() = prefs.getBoolean(KEY_MARK_LINE_RISKS, false)
        set(value) = prefs.edit().putBoolean(KEY_MARK_LINE_RISKS, value).apply()

    var showPieceMarkers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PIECE_MARKERS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PIECE_MARKERS, value).apply()

    var playStyle: PlayStyle
        get() = PlayStyle.fromName(prefs.getString(KEY_PLAY_STYLE, PlayStyle.DEFAULT.name))
        set(value) = prefs.edit().putString(KEY_PLAY_STYLE, value.name).apply()

    var playingAsWhite: Boolean
        get() = prefs.getBoolean(KEY_PLAYING_AS_WHITE, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYING_AS_WHITE, value).apply()

    var selectedAppPackage: String
        get() = prefs.getString(KEY_SELECTED_APP_PKG, PRESET_APPS.first().packageName) ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_APP_PKG, value).apply()

    var selectedAppLabel: String
        get() = prefs.getString(KEY_SELECTED_APP_LABEL, PRESET_APPS.first().name) ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_APP_LABEL, value).apply()

    var pillX: Int
        get() = prefs.getInt(KEY_PILL_X, -1)
        set(value) = prefs.edit().putInt(KEY_PILL_X, value).apply()

    var pillY: Int
        get() = prefs.getInt(KEY_PILL_Y, -1)
        set(value) = prefs.edit().putInt(KEY_PILL_Y, value).apply()

    fun getBoardRect(
        packageName: String,
        defaultX: Int,
        defaultY: Int,
        defaultSize: Int
    ): BoardRect {
        val keyPrefix = rectPrefix(packageName)
        if (!prefs.contains("${keyPrefix}_w")) {
            return BoardRect(defaultX, defaultY, defaultSize, defaultSize)
        }
        val x = prefs.getInt("${keyPrefix}_x", defaultX)
        val y = prefs.getInt("${keyPrefix}_y", defaultY)
        val w = prefs.getInt("${keyPrefix}_w", defaultSize).coerceAtLeast(240)
        val h = prefs.getInt("${keyPrefix}_h", defaultSize).coerceAtLeast(240)
        return BoardRect(x, y, w, h)
    }

    fun saveBoardRect(packageName: String, rect: BoardRect) {
        val keyPrefix = rectPrefix(packageName)
        prefs.edit()
            .putInt("${keyPrefix}_x", rect.x)
            .putInt("${keyPrefix}_y", rect.y)
            .putInt("${keyPrefix}_w", rect.width)
            .putInt("${keyPrefix}_h", rect.height)
            .apply()
    }

    private fun rectPrefix(packageName: String): String {
        val clean = packageName.trim().ifEmpty { "default" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "board_rect_$clean"
    }

    companion object {
        private const val PREFS_NAME = "chess_coach_overlay_prefs"
        private const val CURRENT_SCHEMA_VERSION = 2
        private const val KEY_SCHEMA_VERSION = "prefs_schema_version"

        private const val KEY_TARGET_ELO = "target_elo"
        private const val KEY_TARGET_ACCURACY = "target_accuracy"
        private const val KEY_SEARCH_DEPTH = "search_depth"
        private const val KEY_AUTO_READ_SCREEN = "auto_read_screen"
        private const val KEY_TAP_INPUT_MODE = "tap_input_mode"
        private const val KEY_SHOW_ARROWS = "show_arrows"
        private const val KEY_SHOW_MULTI_ARROWS = "show_multi_arrows"
        private const val KEY_ANALYZE_MY_MOVES = "analyze_my_moves"
        private const val KEY_BOARD_WARNINGS = "board_warnings"
        private const val KEY_SHOW_EVAL_BAR = "show_eval_bar"
        private const val KEY_MARK_PINNED = "mark_pinned"
        private const val KEY_MARK_LINE_RISKS = "mark_line_risks"
        private const val KEY_SHOW_PIECE_MARKERS = "show_piece_markers"
        private const val KEY_PLAY_STYLE = "play_style"
        private const val KEY_PLAYING_AS_WHITE = "playing_as_white"
        private const val KEY_SELECTED_APP_PKG = "selected_app_package"
        private const val KEY_SELECTED_APP_LABEL = "selected_app_label"
        private const val KEY_PILL_X = "pill_x"
        private const val KEY_PILL_Y = "pill_y"

        val PRESET_APPS: List<PresetChessApp> = listOf(
            PresetChessApp("Chess.com", "com.chess", "Chess.com official app"),
            PresetChessApp("Lichess", "org.lichess.mobileapp", "Lichess mobile"),
            PresetChessApp("Chess · Splend", "com.splendapps.chess", "Splend Apps Chess"),
            PresetChessApp("Chess Free", "uk.co.aifactory.chessfree", "AI Factory Chess Free")
        )
    }
}
