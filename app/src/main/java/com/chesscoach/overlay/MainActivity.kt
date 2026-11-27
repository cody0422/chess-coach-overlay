package com.chesscoach.overlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chesscoach.overlay.data.OverlayPrefs
import com.chesscoach.overlay.engine.StockfishUciEngine
import com.chesscoach.overlay.overlay.OverlayService
import com.chesscoach.overlay.vision.ChessAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: OverlayPrefs

    private lateinit var cardSelectedApp: View
    private lateinit var tvSelectedAppName: TextView
    private lateinit var tvSelectedAppSub: TextView
    private lateinit var tvSelectedAppBadge: TextView

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvEngineStatus: TextView
    private lateinit var tvCurrentConfig: TextView
    private lateinit var swAutoReadBoardMain: SwitchCompat
    private lateinit var cardNoRedBoxMain: View
    private lateinit var tvNoRedBoxStatus: TextView
    private lateinit var tvNoRedBoxHelper: TextView
    private lateinit var rowNoRedBoxButtons: View
    private lateinit var btnEnableNoRedBox: AppCompatButton
    private lateinit var btnOpenAppInfo: AppCompatButton
    private lateinit var btnGrantPermission: AppCompatButton
    private lateinit var btnToggleOverlay: AppCompatButton

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchOverlayServiceAndChessApp(result.resultCode, result.data)
        } else {
            Toast.makeText(
                this,
                "Screen capture declined — starting in Manual Tap mode",
                Toast.LENGTH_SHORT
            ).show()
            launchOverlayServiceAndChessApp(Activity.RESULT_CANCELED, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = OverlayPrefs(this)

        cardSelectedApp = findViewById(R.id.cardSelectedApp)
        tvSelectedAppName = findViewById(R.id.tvSelectedAppName)
        tvSelectedAppSub = findViewById(R.id.tvSelectedAppSub)
        tvSelectedAppBadge = findViewById(R.id.tvSelectedAppBadge)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvEngineStatus = findViewById(R.id.tvEngineStatus)
        tvCurrentConfig = findViewById(R.id.tvCurrentConfig)
        swAutoReadBoardMain = findViewById(R.id.swAutoReadBoardMain)
        cardNoRedBoxMain = findViewById(R.id.cardNoRedBoxMain)
        tvNoRedBoxStatus = findViewById(R.id.tvNoRedBoxStatus)
        tvNoRedBoxHelper = findViewById(R.id.tvNoRedBoxHelper)
        rowNoRedBoxButtons = findViewById(R.id.rowNoRedBoxButtons)
        btnEnableNoRedBox = findViewById(R.id.btnEnableNoRedBox)
        btnOpenAppInfo = findViewById(R.id.btnOpenAppInfo)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)

        btnEnableNoRedBox.setOnClickListener {
            openAccessibilitySettings()
        }
        btnOpenAppInfo.setOnClickListener {
            openAppInfoSettings()
        }

        swAutoReadBoardMain.isChecked = prefs.autoReadScreen
        swAutoReadBoardMain.setOnCheckedChangeListener { _, checked ->
            prefs.autoReadScreen = checked
            updateUi()
        }

        cardSelectedApp.setOnClickListener {
            showAppPickerDialog()
        }

        btnGrantPermission.setOnClickListener {
            openOverlayPermissionSettings()
        }

        btnToggleOverlay.setOnClickListener {
            val needsScreenPermission = OverlayService.isRunning &&
                prefs.autoReadScreen &&
                !OverlayService.hasActiveScreenCapture
            if (needsScreenPermission) {
                startOverlayAndLaunchApp()
            } else if (OverlayService.isRunning) {
                stopOverlay()
            } else {
                startOverlayAndLaunchApp()
            }
        }

        requestNotificationPermissionIfNeeded()
        updateUi()
        handleAutoReadRequestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoReadRequestIntent(intent)
    }

    private fun handleAutoReadRequestIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_AUTO_READ, false) == true) {
            val forceCast = intent.getBooleanExtra(EXTRA_FORCE_FAST_CAST, false)
            intent.removeExtra(EXTRA_REQUEST_AUTO_READ)
            intent.removeExtra(EXTRA_FORCE_FAST_CAST)
            if (Settings.canDrawOverlays(this)) {
                prefs.autoReadScreen = true
                swAutoReadBoardMain.isChecked = true
                if (!forceCast && !prefs.useFastCastMode && ChessAccessibilityService.isConnected()) {
                    launchOverlayServiceSilentAccessibility()
                } else {
                    requestScreenCaptureOrStartManual()
                }
            } else {
                Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_SHORT).show()
                openOverlayPermissionSettings()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val canOverlay = Settings.canDrawOverlays(this)
        if (canOverlay) {
            tvPermissionStatus.text = "Granted"
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            btnGrantPermission.visibility = View.GONE
        } else {
            tvPermissionStatus.text = "Not Granted"
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
            btnGrantPermission.visibility = View.VISIBLE
        }

        val hasStockfish = StockfishUciEngine(this).hasBinary()
        tvEngineStatus.text = if (hasStockfish) {
            "Engine: Stockfish 19 (ARM64) + Pure-Kotlin Fallback • ∞ Moves/Day"
        } else {
            "Engine: Offline Pure-Kotlin Chess Engine • ∞ Moves/Day"
        }

        swAutoReadBoardMain.isChecked = prefs.autoReadScreen
        val a11ySupported = ChessAccessibilityService.isSupportedOnDevice()
        val a11yConnected = ChessAccessibilityService.isConnected()
        cardNoRedBoxMain.visibility = if (a11ySupported && prefs.autoReadScreen) View.VISIBLE else View.GONE
        if (a11yConnected && !prefs.useFastCastMode) {
            tvNoRedBoxStatus.text = "Active ✓"
            tvNoRedBoxStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            tvNoRedBoxHelper.text = "Zero red box in status bar & instant silent board reading enabled."
            rowNoRedBoxButtons.visibility = View.GONE
        } else if (a11yConnected && prefs.useFastCastMode) {
            tvNoRedBoxStatus.text = "Fast Cast On"
            tvNoRedBoxStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            tvNoRedBoxHelper.text = "Fast Cast Mode is enabled in Settings (shows status bar icon). Toggle it off in Settings to return to No-Red-Box mode."
            rowNoRedBoxButtons.visibility = View.GONE
        } else {
            tvNoRedBoxStatus.text = "Off"
            tvNoRedBoxStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
            tvNoRedBoxHelper.text = "First time? Tap Open App Info → 3 dots (top right) → Allow restricted settings, then switch ON ChessCoach Board Reader."
            rowNoRedBoxButtons.visibility = View.VISIBLE
        }

        val turnTag = if (prefs.myTurnOnly) "My Turn Only" else "Both Sides"
        tvCurrentConfig.text =
            "Elo ${prefs.targetElo} • ${prefs.targetAccuracy}% • Depth ${prefs.searchDepth} • $turnTag"

        val selectedPkg = prefs.selectedAppPackage
        val selectedName = prefs.selectedAppLabel.ifBlank { "Tap to pick app" }
        val installed = isAppInstalled(selectedPkg)

        tvSelectedAppName.text = selectedName
        tvSelectedAppSub.text = if (selectedPkg.isNotBlank()) {
            "$selectedPkg • Board layout saved per app"
        } else {
            "Tap to pick any chess app on your phone"
        }
        if (selectedPkg.isBlank()) {
            tvSelectedAppBadge.text = "Pick App"
            tvSelectedAppBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else if (installed) {
            tvSelectedAppBadge.text = "Installed"
            tvSelectedAppBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
        } else {
            tvSelectedAppBadge.text = "Install"
            tvSelectedAppBadge.setTextColor(ContextCompat.getColor(this, R.color.warning_orange))
        }

        val needsScreenPermission = OverlayService.isRunning &&
            prefs.autoReadScreen &&
            !OverlayService.hasActiveScreenCapture
        if (needsScreenPermission) {
            btnToggleOverlay.text = if (installed) {
                "Grant Screen Permission & Open $selectedName"
            } else {
                "Grant Screen Permission"
            }
            btnToggleOverlay.setBackgroundResource(R.drawable.bg_button_primary)
            btnToggleOverlay.setTextColor(Color.parseColor("#071A12"))
        } else if (OverlayService.isRunning) {
            btnToggleOverlay.text = getString(R.string.stop_overlay)
            btnToggleOverlay.setBackgroundResource(R.drawable.bg_button_secondary)
            btnToggleOverlay.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            btnToggleOverlay.text = if (installed) {
                "Start Overlay & Open $selectedName"
            } else {
                getString(R.string.start_overlay)
            }
            btnToggleOverlay.setBackgroundResource(R.drawable.bg_button_primary)
            btnToggleOverlay.setTextColor(Color.parseColor("#071A12"))
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "Open Android Settings > Display over other apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Switch ON ChessCoach Board Reader (No Red Box)",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Open Android Settings > Accessibility > Installed apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppInfoSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "Tap 3 dots (top right) → Allow restricted settings",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Open Settings > Apps > ChessCoach Overlay", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlayAndLaunchApp() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_SHORT).show()
            openOverlayPermissionSettings()
            return
        }

        if (prefs.autoReadScreen) {
            if (ChessAccessibilityService.isConnected() && !prefs.useFastCastMode) {
                launchOverlayServiceSilentAccessibility()
            } else {
                requestScreenCaptureOrStartManual()
            }
        } else {
            launchOverlayServiceAndChessApp(Activity.RESULT_CANCELED, null)
        }
    }

    private fun launchOverlayServiceSilentAccessibility() {
        val targetPkg = prefs.selectedAppPackage
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_OVERLAY
            putExtra(OverlayService.EXTRA_TARGET_PACKAGE, targetPkg)
            putExtra(OverlayService.EXTRA_LAUNCH_TARGET_APP, true)
            putExtra(OverlayService.EXTRA_USE_ACCESSIBILITY_MODE, true)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        btnToggleOverlay.postDelayed({ updateUi() }, 300L)
    }

    private fun requestScreenCaptureOrStartManual() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) {
            launchOverlayServiceAndChessApp(
                Activity.RESULT_CANCELED,
                null,
                captureError = "MediaProjectionManager unavailable"
            )
            return
        }

        var lastCreateError: Throwable? = null
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } catch (t: Throwable) {
                lastCreateError = t
                try {
                    mpm.createScreenCaptureIntent()
                } catch (t2: Throwable) {
                    lastCreateError = t2
                    null
                }
            }
        } else {
            try {
                mpm.createScreenCaptureIntent()
            } catch (t: Throwable) {
                lastCreateError = t
                null
            }
        }

        if (captureIntent == null) {
            val err = lastCreateError?.let { "${it.javaClass.simpleName}: ${it.message ?: "unknown"}" }
                ?: "createScreenCaptureIntent failed"
            Toast.makeText(
                this,
                "Screen capture unavailable — starting in Manual Tap mode",
                Toast.LENGTH_SHORT
            ).show()
            launchOverlayServiceAndChessApp(Activity.RESULT_CANCELED, null, captureError = err)
            return
        }

        try {
            screenCaptureLauncher.launch(captureIntent)
        } catch (t: Throwable) {
            val err = "launchCapture: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            Toast.makeText(
                this,
                "Screen capture unavailable — starting in Manual Tap mode",
                Toast.LENGTH_SHORT
            ).show()
            launchOverlayServiceAndChessApp(Activity.RESULT_CANCELED, null, captureError = err)
        }
    }

    private fun launchOverlayServiceAndChessApp(
        projectionResultCode: Int,
        projectionData: Intent?,
        captureError: String? = null
    ) {
        val targetPkg = prefs.selectedAppPackage
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_OVERLAY
            putExtra(OverlayService.EXTRA_TARGET_PACKAGE, targetPkg)
            putExtra(OverlayService.EXTRA_LAUNCH_TARGET_APP, true)
            putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
            if (projectionData != null) {
                putExtra(OverlayService.EXTRA_PROJECTION_DATA, projectionData)
            }
            if (!captureError.isNullOrBlank()) {
                putExtra(OverlayService.EXTRA_CAPTURE_ERROR, captureError)
            }
        }
        // Keep MainActivity in the foreground while OverlayService upgrades FGS to MEDIA_PROJECTION
        // and calls getMediaProjection() + createVirtualDisplay(); OverlayService launches the chess app after!
        ContextCompat.startForegroundService(this, serviceIntent)
        btnToggleOverlay.postDelayed({ updateUi() }, 350L)
    }

    private fun stopOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
        btnToggleOverlay.postDelayed({ updateUi() }, 200L)
    }

    private fun openPlayStore(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                    )
                )
            } catch (_: Throwable) {
                Toast.makeText(this, "Could not open Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectChessApp(name: String, packageName: String) {
        prefs.selectedAppLabel = name
        prefs.selectedAppPackage = packageName
        if (OverlayService.isRunning) {
            val updateIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_TARGET_APP
                putExtra(OverlayService.EXTRA_TARGET_PACKAGE, packageName)
            }
            startService(updateIntent)
        }
        updateUi()
    }

    private fun showAppPickerDialog() {
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12151E"))
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Which app are you playing on?"
            setTextColor(Color.parseColor("#F3F6FA"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        container.addView(title)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        for (preset in OverlayPrefs.PRESET_APPS) {
            val installed = isAppInstalled(preset.packageName)
            val isSelected = prefs.selectedAppPackage == preset.packageName

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(if (isSelected) R.drawable.bg_badge_green else R.drawable.bg_button_secondary)
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * dp).toInt()
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = preset.name
                setTextColor(Color.parseColor("#F3F6FA"))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            val subView = TextView(this).apply {
                text = preset.subtitle
                setTextColor(Color.parseColor("#9BA4B5"))
                textSize = 12f
            }
            textCol.addView(nameView)
            textCol.addView(subView)
            row.addView(textCol)

            if (installed) {
                val badge = TextView(this).apply {
                    text = "Installed"
                    setTextColor(Color.parseColor("#19E58F"))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_badge_green)
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                }
                row.addView(badge)
                row.setOnClickListener {
                    selectChessApp(preset.name, preset.packageName)
                    dialog.dismiss()
                }
            } else {
                val installBtn = TextView(this).apply {
                    text = "Install"
                    setTextColor(Color.parseColor("#071A12"))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_button_primary)
                    setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                    setOnClickListener {
                        openPlayStore(preset.packageName)
                    }
                }
                row.addView(installBtn)
                row.setOnClickListener {
                    selectChessApp(preset.name, preset.packageName)
                    dialog.dismiss()
                }
            }

            container.addView(row)
        }

        // "Other app" option -> opens searchable list of all installed launcher apps
        val otherRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_button_secondary)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * dp).toInt()
            }
            setOnClickListener {
                dialog.dismiss()
                showAllInstalledAppsDialog()
            }
        }
        otherRow.addView(TextView(this).apply {
            text = "Other app..."
            setTextColor(Color.parseColor("#19E58F"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        otherRow.addView(TextView(this).apply {
            text = "Tap to pick any installed app on your phone"
            setTextColor(Color.parseColor("#9BA4B5"))
            textSize = 12f
        })
        container.addView(otherRow)

        dialog.show()
    }

    private fun showAllInstalledAppsDialog() {
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = packageManager.queryIntentActivities(mainIntent, 0)
        val installedApps = resolved
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = ri.loadLabel(packageManager)?.toString()?.trim().orEmpty().ifBlank { pkg }
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12151E"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Pick Any Installed App"
            setTextColor(Color.parseColor("#F3F6FA"))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (10 * dp).toInt())
        })

        val searchBox = EditText(this).apply {
            hint = "Search installed apps..."
            setHintTextColor(Color.parseColor("#667085"))
            setTextColor(Color.parseColor("#F3F6FA"))
            setBackgroundResource(R.drawable.bg_button_secondary)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            isSingleLine = true
        }
        root.addView(searchBox)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (340 * dp).toInt()
            ).apply {
                topMargin = (10 * dp).toInt()
            }
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listLayout)
        root.addView(scroll)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()

        fun populate(filter: String) {
            listLayout.removeAllViews()
            val q = filter.trim().lowercase()
            val matches = if (q.isEmpty()) installedApps else installedApps.filter {
                it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
            }
            for ((label, pkg) in matches) {
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_button_secondary)
                    setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (6 * dp).toInt()
                    }
                    setOnClickListener {
                        selectChessApp(label, pkg)
                        dialog.dismiss()
                    }
                }
                item.addView(TextView(this).apply {
                    text = label
                    setTextColor(Color.parseColor("#F3F6FA"))
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                })
                item.addView(TextView(this).apply {
                    text = pkg
                    setTextColor(Color.parseColor("#9BA4B5"))
                    textSize = 11f
                })
                listLayout.addView(item)
            }
            if (matches.isEmpty()) {
                listLayout.addView(TextView(this).apply {
                    text = "No matching apps found"
                    setTextColor(Color.parseColor("#9BA4B5"))
                    textSize = 13f
                    setPadding((8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt())
                })
            }
        }

        populate("")
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populate(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.show()
    }

    companion object {
        private const val REQ_NOTIF = 1001
        const val EXTRA_REQUEST_AUTO_READ = "extra_request_auto_read"
        const val EXTRA_FORCE_FAST_CAST = "extra_force_fast_cast"
    }
}
