package com.chesscoach.overlay.overlay

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import com.chesscoach.overlay.R
import com.chesscoach.overlay.data.OverlayPrefs
import com.chesscoach.overlay.engine.EloAccuracyHelper

class SettingsModalController(
    themedContext: Context,
    private val prefs: OverlayPrefs,
    private val engineNameProvider: () -> String,
    private val screenReadStatusProvider: () -> String,
    private val onSettingsChanged: (reanalyze: Boolean) -> Unit,
    private val onAutoReadToggled: (enabled: Boolean) -> Unit,
    private val onTapInputToggled: (enabled: Boolean) -> Unit,
    private val onAlignClicked: () -> Unit,
    private val onFlipClicked: () -> Unit,
    private val onUndoClicked: () -> Unit,
    private val onNewGameClicked: () -> Unit,
    private val onRedetectClicked: () -> Unit,
    private val onDismiss: () -> Unit
) {
    val rootView: View = LayoutInflater.from(themedContext).inflate(R.layout.overlay_settings_modal, null)

    private val tvScreenReadStatus: TextView = rootView.findViewById(R.id.tvScreenReadStatus)
    private val btnRedetect: AppCompatButton = rootView.findViewById(R.id.btnSettingsRedetect)

    private val seekElo: SeekBar = rootView.findViewById(R.id.seekTargetElo)
    private val tvEloValue: TextView = rootView.findViewById(R.id.tvEloValue)
    private val tvEloTier: TextView = rootView.findViewById(R.id.tvEloTier)

    private val seekAccuracy: SeekBar = rootView.findViewById(R.id.seekTargetAccuracy)
    private val tvAccuracyValue: TextView = rootView.findViewById(R.id.tvAccuracyValue)
    private val tvAccuracyCaption: TextView = rootView.findViewById(R.id.tvAccuracyCaption)

    private val seekDepth: SeekBar = rootView.findViewById(R.id.seekSearchDepth)
    private val tvDepthValue: TextView = rootView.findViewById(R.id.tvDepthValue)
    private val tvDepthCaption: TextView = rootView.findViewById(R.id.tvDepthCaption)

    private val swAutoReadScreen: SwitchCompat = rootView.findViewById(R.id.swAutoReadScreen)
    private val swTapInputMode: SwitchCompat = rootView.findViewById(R.id.swTapInputMode)
    private val swShowArrows: SwitchCompat = rootView.findViewById(R.id.swShowArrows)
    private val swMultiArrows: SwitchCompat = rootView.findViewById(R.id.swMultiArrows)
    private val swAnalyzeMyMoves: SwitchCompat = rootView.findViewById(R.id.swAnalyzeMyMoves)
    private val swBoardWarnings: SwitchCompat = rootView.findViewById(R.id.swBoardWarnings)
    private val swEvalBar: SwitchCompat = rootView.findViewById(R.id.swEvalBar)
    private val swMarkPinned: SwitchCompat = rootView.findViewById(R.id.swMarkPinned)
    private val swMarkLineRisks: SwitchCompat = rootView.findViewById(R.id.swMarkLineRisks)
    private val swPieceMarkers: SwitchCompat = rootView.findViewById(R.id.swPieceMarkers)

    private val btnPlayStyle: AppCompatButton = rootView.findViewById(R.id.btnPlayStyle)
    private val btnPlayWhite: AppCompatButton = rootView.findViewById(R.id.btnPlayWhite)
    private val btnPlayBlack: AppCompatButton = rootView.findViewById(R.id.btnPlayBlack)
    private val tvEngineFooter: TextView = rootView.findViewById(R.id.tvSettingsEngineFooter)

    private var syncing = false

    init {
        rootView.findViewById<View>(R.id.settingsBackdrop).setOnClickListener { onDismiss() }
        rootView.findViewById<View>(R.id.settingsCardContent).setOnClickListener { /* consume click */ }
        rootView.findViewById<ImageButton>(R.id.btnCloseSettings).setOnClickListener { onDismiss() }

        rootView.findViewById<AppCompatButton>(R.id.btnSettingsAlignBoard).setOnClickListener {
            onDismiss()
            onAlignClicked()
        }
        rootView.findViewById<AppCompatButton>(R.id.btnSettingsFlipBoard).setOnClickListener {
            onFlipClicked()
            syncFromPrefs()
        }
        rootView.findViewById<AppCompatButton>(R.id.btnSettingsUndoMove).setOnClickListener {
            onUndoClicked()
        }
        rootView.findViewById<AppCompatButton>(R.id.btnSettingsNewGame).setOnClickListener {
            onDismiss()
            onNewGameClicked()
        }
        btnRedetect.setOnClickListener {
            onDismiss()
            onRedetectClicked()
        }

        syncFromPrefs()
        bindListeners()
    }

    fun syncFromPrefs() {
        syncing = true
        try {
            val elo = prefs.targetElo
            seekElo.max = (EloAccuracyHelper.MAX_ELO - EloAccuracyHelper.MIN_ELO) / EloAccuracyHelper.ELO_STEP
            seekElo.progress = ((elo - EloAccuracyHelper.MIN_ELO) / EloAccuracyHelper.ELO_STEP).coerceIn(0, seekElo.max)
            updateEloLabels(elo)

            val acc = prefs.targetAccuracy
            seekAccuracy.max = EloAccuracyHelper.MAX_ACCURACY - EloAccuracyHelper.MIN_ACCURACY
            seekAccuracy.progress = (acc - EloAccuracyHelper.MIN_ACCURACY).coerceIn(0, seekAccuracy.max)
            updateAccuracyLabels(acc)

            val depth = prefs.searchDepth
            seekDepth.max = EloAccuracyHelper.MAX_DEPTH - EloAccuracyHelper.MIN_DEPTH
            seekDepth.progress = (depth - EloAccuracyHelper.MIN_DEPTH).coerceIn(0, seekDepth.max)
            updateDepthLabels(depth)

            swAutoReadScreen.isChecked = prefs.autoReadScreen
            swTapInputMode.isChecked = prefs.tapInputMode
            swShowArrows.isChecked = prefs.showArrows
            swMultiArrows.isChecked = prefs.showMultiArrows
            swAnalyzeMyMoves.isChecked = prefs.analyzeMyMoves
            swBoardWarnings.isChecked = prefs.boardWarnings
            swEvalBar.isChecked = prefs.showEvalBar
            swMarkPinned.isChecked = prefs.markPinnedPieces
            swMarkLineRisks.isChecked = prefs.markLineRisks
            swPieceMarkers.isChecked = prefs.showPieceMarkers

            tvScreenReadStatus.text = screenReadStatusProvider()
            updatePlayStyleButton()
            updateSideButtons(prefs.playingAsWhite)
            tvEngineFooter.text = "Engine: ${engineNameProvider()} • ∞ Moves/Day"
        } finally {
            syncing = false
        }
    }

    private fun bindListeners() {
        seekElo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val elo = EloAccuracyHelper.MIN_ELO + progress * EloAccuracyHelper.ELO_STEP
                updateEloLabels(elo)
                if (fromUser) prefs.targetElo = elo
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                onSettingsChanged(true)
            }
        })

        seekAccuracy.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val acc = EloAccuracyHelper.MIN_ACCURACY + progress
                updateAccuracyLabels(acc)
                if (fromUser) prefs.targetAccuracy = acc
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                onSettingsChanged(true)
            }
        })

        seekDepth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val depth = EloAccuracyHelper.MIN_DEPTH + progress
                updateDepthLabels(depth)
                if (fromUser) prefs.searchDepth = depth
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                onSettingsChanged(true)
            }
        })

        swAutoReadScreen.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.autoReadScreen = checked
            onAutoReadToggled(checked)
            tvScreenReadStatus.text = screenReadStatusProvider()
        }
        swTapInputMode.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.tapInputMode = checked
            onTapInputToggled(checked)
        }
        swShowArrows.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.showArrows = checked
            onSettingsChanged(false)
        }
        swMultiArrows.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.showMultiArrows = checked
            onSettingsChanged(false)
        }
        swAnalyzeMyMoves.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.analyzeMyMoves = checked
            onSettingsChanged(false)
        }
        swBoardWarnings.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.boardWarnings = checked
            onSettingsChanged(false)
        }
        swEvalBar.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.showEvalBar = checked
            onSettingsChanged(false)
        }
        swMarkPinned.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.markPinnedPieces = checked
            onSettingsChanged(false)
        }
        swMarkLineRisks.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.markLineRisks = checked
            onSettingsChanged(false)
        }
        swPieceMarkers.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.showPieceMarkers = checked
            onSettingsChanged(false)
        }

        btnPlayStyle.setOnClickListener {
            prefs.playStyle = prefs.playStyle.next()
            updatePlayStyleButton()
            onSettingsChanged(true)
        }

        btnPlayWhite.setOnClickListener {
            if (!prefs.playingAsWhite) {
                prefs.playingAsWhite = true
                updateSideButtons(true)
                onSettingsChanged(false)
            }
        }
        btnPlayBlack.setOnClickListener {
            if (prefs.playingAsWhite) {
                prefs.playingAsWhite = false
                updateSideButtons(false)
                onSettingsChanged(false)
            }
        }
    }

    private fun updateEloLabels(elo: Int) {
        tvEloValue.text = elo.toString()
        tvEloTier.text = "${EloAccuracyHelper.eloTierLabel(elo)} (${EloAccuracyHelper.MIN_ELO} – ${EloAccuracyHelper.MAX_ELO} Elo)"
    }

    private fun updateAccuracyLabels(acc: Int) {
        tvAccuracyValue.text = "$acc%"
        tvAccuracyCaption.text = EloAccuracyHelper.accuracyCaption(acc)
    }

    private fun updateDepthLabels(depth: Int) {
        tvDepthValue.text = depth.toString()
        tvDepthCaption.text = EloAccuracyHelper.depthCaption(depth)
    }

    private fun updatePlayStyleButton() {
        btnPlayStyle.text = "${prefs.playStyle.label} ▾"
    }

    private fun updateSideButtons(playingWhite: Boolean) {
        if (playingWhite) {
            btnPlayWhite.setBackgroundResource(R.drawable.bg_button_primary)
            btnPlayWhite.setTextColor(Color.parseColor("#071A12"))
            btnPlayBlack.setBackgroundResource(R.drawable.bg_button_secondary)
            btnPlayBlack.setTextColor(Color.parseColor("#F3F6FA"))
        } else {
            btnPlayWhite.setBackgroundResource(R.drawable.bg_button_secondary)
            btnPlayWhite.setTextColor(Color.parseColor("#F3F6FA"))
            btnPlayBlack.setBackgroundResource(R.drawable.bg_button_primary)
            btnPlayBlack.setTextColor(Color.parseColor("#071A12"))
        }
    }
}
