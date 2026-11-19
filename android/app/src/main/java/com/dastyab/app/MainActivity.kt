package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * MainActivity — DastYab Android control plane.
 *
 * Combines:
 *  • Built-in Smart / Global / Split VPN Tunnel (with auto-VPN for AI Studio & Google Flow)
 *  • 14 one-tap Quick Actions covering Google access, Worker deploy, Autopilot,
 *    Clean IP scanning, Profiles, Tokens, Configs, Security Audit, Doctor, Backup, and Zones
 *  • Full interactive CLI console with history, quick-command chips, and clickable URLs
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var outputEmptyState: LinearLayout
    private lateinit var argsInput: EditText
    private lateinit var btnRun: Button
    private lateinit var btnCancel: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusDot: View
    private lateinit var historySpinner: Spinner
    private lateinit var binary: File

    // Simple / Advanced / Terminal Mode views
    private lateinit var btnTabSimple: Button
    private lateinit var btnTabAdvanced: Button
    private lateinit var btnTabTerminal: Button
    private lateinit var simpleModeContainer: LinearLayout
    private lateinit var advancedModeContainer: LinearLayout
    private lateinit var terminalModeContainer: LinearLayout
    private lateinit var simpleStatusDot: View
    private lateinit var tvSimpleHeroTitle: TextView
    private lateinit var tvSimpleReachability: TextView
    private lateinit var simpleProgress: ProgressBar
    private lateinit var btnSimpleMagicAutopilot: Button
    private lateinit var btnSimpleQuickToggle: Button

    // Terminal Mode views (CPython 3.12 Linux-compatible shell)
    private lateinit var tvTerminalRuntimeBadge: TextView
    private lateinit var tvTerminalPromptHeader: TextView
    private lateinit var terminalOutputScroll: ScrollView
    private lateinit var tvTerminalOutput: TextView
    private lateinit var terminalProgress: ProgressBar
    private lateinit var terminalInput: EditText
    private lateinit var btnTerminalRun: Button
    private lateinit var btnTerminalStop: Button
    private val terminalRunning = AtomicBoolean(false)
    private val terminalProcessHolder = AtomicReference<Process?>(null)
    @Volatile
    private var terminalBannerShown = false

    // VPN Tunnel card views (Advanced Mode)
    private lateinit var vpnDot: View
    private lateinit var tvVpnState: TextView
    private lateinit var tvVpnDetails: TextView
    private lateinit var tvReachabilityBadge: TextView
    private lateinit var btnNewbieAutopilot: Button
    private lateinit var btnVpnToggle: Button
    private lateinit var btnModeSmart: Button
    private lateinit var btnModeGlobal: Button
    private lateinit var btnModeSplit: Button
    private lateinit var btnModeDns: Button

    // Quick action cards (entire card is clickable)
    private lateinit var btnSimpleAistudio: View
    private lateinit var btnSimpleFlow: View
    private lateinit var btnSimpleDeploy: View
    private lateinit var btnSimpleAutopilot: View
    private lateinit var btnSimpleVerify: View
    private lateinit var btnSimpleAccounts: View
    private lateinit var btnSimpleProfiles: View
    private lateinit var btnSimpleTokens: View
    private lateinit var btnSimpleScan: View
    private lateinit var btnSimpleConfigs: View
    private lateinit var btnSimpleSecurity: View
    private lateinit var btnSimpleDoctor: View
    private lateinit var btnSimpleBackup: View
    private lateinit var btnSimpleZones: View

    // Advanced section toggle
    private lateinit var btnShowAdvanced: Button
    private lateinit var btnHideAdvanced: Button
    private lateinit var advancedSection: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val uiLock = Any()
    private var destroyed = false // Protected by uiLock, including callback enqueue/removal.
    private val operation = AtomicReference<Operation?>(null)
    private var pendingGoogleLaunchUrl: String? = null
    private var pendingGoogleLaunchName: String? = null
    private var vpnReceiverRegistered = false

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (UiSafety.isActivityAlive(this@MainActivity)) {
                refreshVpnCard()
            }
        }
    }

    private class Operation {
        val cancelled = AtomicBoolean(false)
        val process = AtomicReference<Process?>(null)
    }

    companion object {
        const val EXTRA_AUTO_CONNECT_VPN = "extra_auto_connect_vpn"
        private const val PROCESS_TIMEOUT_MS = 300_000L
        private const val MAX_OUTPUT_CHARS = 50_000
        private const val MAX_ARGS_LENGTH = 4096
        private const val REQ_VPN_PREPARE = 4001
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DastyabLogger.init(filesDir)
        CrashHandler.install(applicationContext, filesDir)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        output = findViewById(R.id.output)
        outputEmptyState = findViewById(R.id.outputEmptyState)
        argsInput = findViewById(R.id.argsInput)
        btnRun = findViewById(R.id.btnRun)
        btnCancel = findViewById(R.id.btnCancel)
        progress = findViewById(R.id.progress)
        statusDot = findViewById(R.id.statusDot)
        historySpinner = findViewById(R.id.historySpinner)
        binary = File(applicationInfo.nativeLibraryDir, "libdastyab.so")

        // Simple / Advanced / Terminal Mode bindings
        btnTabSimple = findViewById(R.id.btnTabSimple)
        btnTabAdvanced = findViewById(R.id.btnTabAdvanced)
        btnTabTerminal = findViewById(R.id.btnTabTerminal)
        simpleModeContainer = findViewById(R.id.simpleModeContainer)
        advancedModeContainer = findViewById(R.id.advancedModeContainer)
        terminalModeContainer = findViewById(R.id.terminalModeContainer)
        simpleStatusDot = findViewById(R.id.simpleStatusDot)
        tvSimpleHeroTitle = findViewById(R.id.tvSimpleHeroTitle)
        tvSimpleReachability = findViewById(R.id.tvSimpleReachability)
        simpleProgress = findViewById(R.id.simpleProgress)
        btnSimpleMagicAutopilot = findViewById(R.id.btnSimpleMagicAutopilot)
        btnSimpleQuickToggle = findViewById(R.id.btnSimpleQuickToggle)

        // Terminal Mode bindings
        tvTerminalRuntimeBadge = findViewById(R.id.tvTerminalRuntimeBadge)
        tvTerminalPromptHeader = findViewById(R.id.tvTerminalPromptHeader)
        terminalOutputScroll = findViewById(R.id.terminalOutputScroll)
        tvTerminalOutput = findViewById(R.id.tvTerminalOutput)
        terminalProgress = findViewById(R.id.terminalProgress)
        terminalInput = findViewById(R.id.terminalInput)
        btnTerminalRun = findViewById(R.id.btnTerminalRun)
        btnTerminalStop = findViewById(R.id.btnTerminalStop)

        // VPN Tunnel card bindings
        vpnDot = findViewById(R.id.vpnDot)
        tvVpnState = findViewById(R.id.tvVpnState)
        tvVpnDetails = findViewById(R.id.tvVpnDetails)
        tvReachabilityBadge = findViewById(R.id.tvReachabilityBadge)
        btnNewbieAutopilot = findViewById(R.id.btnNewbieAutopilot)
        btnVpnToggle = findViewById(R.id.btnVpnToggle)
        btnModeSmart = findViewById(R.id.btnModeSmart)
        btnModeGlobal = findViewById(R.id.btnModeGlobal)
        btnModeSplit = findViewById(R.id.btnModeSplit)
        btnModeDns = findViewById(R.id.btnModeDns)

        // Quick action card bindings
        btnSimpleAistudio = findViewById(R.id.btnSimpleAistudio)
        btnSimpleFlow = findViewById(R.id.btnSimpleFlow)
        btnSimpleDeploy = findViewById(R.id.btnSimpleDeploy)
        btnSimpleAutopilot = findViewById(R.id.btnSimpleAutopilot)
        btnSimpleVerify = findViewById(R.id.btnSimpleVerify)
        btnSimpleAccounts = findViewById(R.id.btnSimpleAccounts)
        btnSimpleProfiles = findViewById(R.id.btnSimpleProfiles)
        btnSimpleTokens = findViewById(R.id.btnSimpleTokens)
        btnSimpleScan = findViewById(R.id.btnSimpleScan)
        btnSimpleConfigs = findViewById(R.id.btnSimpleConfigs)
        btnSimpleSecurity = findViewById(R.id.btnSimpleSecurity)
        btnSimpleDoctor = findViewById(R.id.btnSimpleDoctor)
        btnSimpleBackup = findViewById(R.id.btnSimpleBackup)
        btnSimpleZones = findViewById(R.id.btnSimpleZones)

        // Advanced section toggle
        btnShowAdvanced = findViewById(R.id.btnShowAdvanced)
        btnHideAdvanced = findViewById(R.id.btnHideAdvanced)
        advancedSection = findViewById(R.id.advancedSection)

        CommandHistory.init(filesDir)
        applyPersistedSettings()

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version_format, version)
        savedInstanceState?.getString("pendingOutput")?.let { applyFormattedOutput(it) }

        // ── Normal Chain vs Emergency Mode Toggle ──
        val btnModeToggle = findViewById<Button>(R.id.btnModeToggle)
        fun refreshModeToggleLabel() {
            val mode = TunnelConfig.load(this).mode
            btnModeToggle.text = if (mode == TunnelConfig.Mode.DNS_BLACKOUT) {
                getString(R.string.mode_emergency_badge)
            } else {
                getString(R.string.mode_normal_badge)
            }
        }
        refreshModeToggleLabel()
        btnModeToggle.setOnClickListener {
            val cur = TunnelConfig.load(this).mode
            val newMode = if (cur == TunnelConfig.Mode.DNS_BLACKOUT) {
                TunnelConfig.Mode.SMART
            } else {
                TunnelConfig.Mode.DNS_BLACKOUT
            }
            TunnelConfig.saveMode(this, newMode)
            refreshModeToggleLabel()
            refreshVpnCard()
            if (DastyabVpnService.isConnected()) {
                DastyabVpnService.start(this)
                Toast.makeText(this, "Switched mode → ${newMode.label} (reconnected)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Switched mode → ${newMode.label}", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Bilingual Persian (RTL) / English Toggle ──
        findViewById<Button>(R.id.btnLangToggle).apply {
            text = LocaleHelper.toggleButtonLabel(LocaleHelper.getLanguage(this@MainActivity))
            setOnClickListener {
                LocaleHelper.toggleLanguage(this@MainActivity)
                DastyabWidgetProvider.updateAllWidgets(this@MainActivity)
                recreate()
            }
        }

        // ── Simple / Advanced / Terminal Mode Switcher ──
        btnTabSimple.setOnClickListener { applyUiMode(UiModePrefs.Mode.SIMPLE) }
        btnTabAdvanced.setOnClickListener { applyUiMode(UiModePrefs.Mode.ADVANCED) }
        btnTabTerminal.setOnClickListener { applyUiMode(UiModePrefs.Mode.TERMINAL) }
        setupTerminalModeUi()
        applyUiMode(UiModePrefs.getMode(this), persist = false)

        // ── Simple Mode 1-Click Controls ──
        btnSimpleMagicAutopilot.setOnClickListener { runNewbieAutopilot() }
        btnSimpleQuickToggle.setOnClickListener { toggleVpnTunnel() }
        findViewById<Button>(R.id.btnSimpleSpeedIp).setOnClickListener { runQuickSpeedAndIpCheck() }
        findViewById<View>(R.id.btnSimpleOneClickAiStudio).setOnClickListener {
            if (operation.get() == null) launchGoogleServiceViaVpn("Google AI Studio", CommandCompat.AISTUDIO_URL)
        }
        findViewById<View>(R.id.btnSimpleOneClickFlow).setOnClickListener {
            if (operation.get() == null) launchGoogleServiceViaVpn("Google Flow", CommandCompat.FLOW_URL)
        }
        findViewById<View>(R.id.btnSimpleOneClickRefresh).setOnClickListener {
            openRescueHub(BlackoutRescueActivity.ACTION_HARVEST)
        }
        findViewById<View>(R.id.btnSimpleOneClickBlackout).setOnClickListener {
            openRescueHub(BlackoutRescueActivity.ACTION_DNS_VPN)
        }
        findViewById<Button>(R.id.btnSimpleProxyPort).setOnClickListener {
            GoogleAccessSheet.show(this) { refreshVpnCard() }
        }
        findViewById<Button>(R.id.btnSimpleLayerPick).setOnClickListener {
            showQuickLayerSwitcherDialog()
        }
        findViewById<Button>(R.id.btnSimpleGoAdvanced).setOnClickListener {
            applyUiMode(UiModePrefs.Mode.ADVANCED)
        }

        // ── VPN Tunnel controls (Advanced Mode) ──
        btnNewbieAutopilot.setOnClickListener { runNewbieAutopilot() }
        btnVpnToggle.setOnClickListener { toggleVpnTunnel() }
        btnModeSmart.setOnClickListener { switchTunnelMode(TunnelConfig.Mode.SMART) }
        btnModeGlobal.setOnClickListener { switchTunnelMode(TunnelConfig.Mode.GLOBAL) }
        btnModeSplit.setOnClickListener { switchTunnelMode(TunnelConfig.Mode.SPLIT_INCLUDE) }
        btnModeDns.setOnClickListener { switchTunnelMode(TunnelConfig.Mode.DNS_BLACKOUT) }
        findViewById<Button>(R.id.btnTunnelSettings).setOnClickListener {
            openActivity(TunnelSettingsActivity::class.java)
        }
        findViewById<Button>(R.id.btnImportVless).setOnClickListener {
            showImportVlessDialog()
        }
        findViewById<Button>(R.id.btnSwitchLayer).setOnClickListener {
            showQuickLayerSwitcherDialog()
        }
        findViewById<Button>(R.id.btnShareQr).setOnClickListener {
            val layer = VpnLayerStore.getActiveLayer(this)
            val cfg = layer.configUri.ifBlank { TunnelConfig.load(this).vlessUri }
                .ifBlank { TunnelConfig.loadHarvestedConfigs(this).firstOrNull().orEmpty() }
            QrShareDialog.show(this, "QR · ${layer.name}", cfg)
        }
        findViewById<Button>(R.id.btnDnsRules).setOnClickListener {
            DomainDnsDialog.show(this) { refreshVpnCard() }
        }
        findViewById<Button>(R.id.btnGoogleAccessMode).setOnClickListener {
            GoogleAccessSheet.show(this) { refreshVpnCard() }
        }

        // ── Blackout Rescue & Harvesting Quick Actions ──
        findViewById<View>(R.id.btnSimpleHarvest).setOnClickListener {
            openRescueHub(BlackoutRescueActivity.ACTION_HARVEST)
        }
        findViewById<View>(R.id.btnSimpleDnsBlackout).setOnClickListener {
            openRescueHub(BlackoutRescueActivity.ACTION_DNS_VPN)
        }
        findViewById<View>(R.id.btnSimpleIpScout).setOnClickListener {
            openRescueHub(BlackoutRescueActivity.ACTION_SCOUT)
        }
        findViewById<View>(R.id.btnSimpleTroubleshoot).setOnClickListener {
            openRescueHub(BlackoutRescueActivity.ACTION_TROUBLESHOOT)
        }
        findViewById<View>(R.id.btnSimpleVpnLayers).setOnClickListener {
            openActivity(VpnLayersActivity::class.java)
        }
        findViewById<View>(R.id.btnSimpleEditSources).setOnClickListener {
            openActivity(VpnLayersActivity::class.java)
        }
        findViewById<View>(R.id.btnSimpleToolbox).setOnClickListener {
            openActivity(NetworkToolboxActivity::class.java)
        }

        // ── Google & Tunnel Quick Actions ──
        btnSimpleAistudio.setOnClickListener {
            if (operation.get() == null) launchGoogleServiceViaVpn("Google AI Studio", CommandCompat.AISTUDIO_URL)
        }
        btnSimpleAistudio.setOnLongClickListener {
            showGoogleRescueDialog("Google AI Studio", CommandCompat.AISTUDIO_URL)
            true
        }
        btnSimpleFlow.setOnClickListener {
            if (operation.get() == null) launchGoogleServiceViaVpn("Google Flow", CommandCompat.FLOW_URL)
        }
        btnSimpleFlow.setOnLongClickListener {
            showGoogleRescueDialog("Google Flow", CommandCompat.FLOW_URL)
            true
        }
        btnSimpleDeploy.setOnClickListener {
            if (operation.get() == null) runSimple("deploy --preset standard", listOf("deploy", "--preset", "standard"))
        }
        btnSimpleAutopilot.setOnClickListener {
            if (operation.get() == null) runSimple("autopilot status", listOf("autopilot", "status"))
        }

        // ── Cloudflare & Stack Quick Actions ──
        btnSimpleVerify.setOnClickListener {
            if (operation.get() == null) runSimple("verify", listOf("verify"))
        }
        btnSimpleAccounts.setOnClickListener {
            if (operation.get() == null) runSimple("accounts", listOf("accounts"))
        }
        btnSimpleProfiles.setOnClickListener {
            if (operation.get() == null) runSimple("profiles", listOf("profiles"))
        }
        btnSimpleTokens.setOnClickListener {
            if (operation.get() == null) runSimple("token-list", listOf("token-list"))
        }
        btnSimpleScan.setOnClickListener {
            if (operation.get() == null) runSimple("scan", listOf("scan"))
        }
        btnSimpleConfigs.setOnClickListener {
            if (operation.get() == null) runSimple("configs", listOf("configs"))
        }
        btnSimpleSecurity.setOnClickListener {
            if (operation.get() == null) runSimple("security", listOf("security"))
        }
        btnSimpleDoctor.setOnClickListener {
            if (operation.get() == null) runSimple("doctor", listOf("doctor"))
        }
        btnSimpleBackup.setOnClickListener {
            if (operation.get() == null) runSimple("backup", listOf("backup"))
        }
        btnSimpleZones.setOnClickListener {
            if (operation.get() == null) runSimple("zones", listOf("zones"))
        }

        // ── Advanced toggle ──
        btnShowAdvanced.setOnClickListener { showAdvanced() }
        btnHideAdvanced.setOnClickListener { hideAdvanced() }

        // ── Run / cancel ──
        btnRun.setOnClickListener { runTyped() }
        btnCancel.setOnClickListener { cancelCurrentOperation() }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            output.text = ""
            outputEmptyState.visibility = View.VISIBLE
            output.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnViewLogs).setOnClickListener {
            openActivity(LogViewerActivity::class.java)
        }
        findViewById<Button>(R.id.btnAbout).setOnClickListener { openActivity(AboutActivity::class.java) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { openActivity(SettingsActivity::class.java) }
        findViewById<Button>(R.id.btnUpdate).apply {
            text = "Releases"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DastYab/dastyab/releases")))
                } catch (_: android.content.ActivityNotFoundException) {
                    postStatus("No browser available to open releases")
                }
            }
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            if (UiSafety.copyTextSafely(this, "Dastyab output", output.text.toString())) {
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val text = output.text.toString()
            if (text.isNotEmpty()) {
                val share = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, getString(R.string.share_title))
                if (!UiSafety.safeStartActivity(this, share)) {
                    Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                    UiSafety.copyTextSafely(this, "Dastyab output", text)
                }
            }
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            val entries = CommandHistory.getAll()
            if (entries.isEmpty()) {
                Toast.makeText(this, getString(R.string.history_empty), Toast.LENGTH_SHORT).show()
            } else {
                UiSafety.safeShowDialog(
                    this,
                    AlertDialog.Builder(this).setTitle(getString(R.string.history_dialog_title))
                        .setItems(entries.map { it.command }.toTypedArray()) { _, index ->
                            argsInput.setText(entries[index].command)
                        }.setPositiveButton(android.R.string.ok, null)
                )
            }
        }

        buildQuickButtons(R.id.quickButtons, listOf(
            "--help", "--version", "verify", "accounts", "scan",
            "deploy --preset standard", "autopilot status", "doctor", "security"
        ))
        buildQuickButtons(R.id.quickButtonsProfiles, listOf(
            "profiles", "token-list", "configs", "zones", "routes list",
            "manifest-verify", "inventory", "backup", "update-check"
        ))
        setupHistorySpinner()
        registerVpnReceiver()
        refreshVpnCard()
        setBusy(false)
        evaluateBinaryState()

        CrashHandler.consumePendingCrashNotice()?.let { notice ->
            showSafeModeRecoveryDialog(notice)
        }

        val initialSettings = TunnelConfig.load(this)
        if (initialSettings.autoStartLocalBridge && !LocalProxyService.isRunning()) {
            LocalProxyService.startSafely(this)
        }

        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT_VPN, false) == true && !DastyabVpnService.isConnected()) {
            requestStartVpn(null, null)
        }
    }

    private fun showSafeModeRecoveryDialog(notice: String) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Safe-Mode Recovery")
            .setMessage("The previous session closed unexpectedly:\n$notice\n\nDastYab cleared the transient fault and started safely.")
            .setPositiveButton("View Crash Logs") { _, _ ->
                openActivity(LogViewerActivity::class.java)
            }
            .setNegativeButton(android.R.string.ok, null)
        UiSafety.safeShowDialog(this, builder)
    }

    override fun onResume() {
        super.onResume()
        refreshVpnCard()
    }

    private fun registerVpnReceiver() {
        if (vpnReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(DastyabVpnService.ACTION_STATE_CHANGED)
            addAction(LocalProxyService.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnStateReceiver, filter)
        }
        vpnReceiverRegistered = true
    }

    private fun toggleVpnTunnel() {
        if (DastyabVpnService.isConnected()) {
            DastyabVpnService.stop(this)
            postOutput("VPN tunnel disconnected.\n")
            refreshVpnCard()
            return
        }
        requestStartVpn(null, null)
    }

    private fun switchTunnelMode(mode: TunnelConfig.Mode) {
        TunnelConfig.saveMode(this, mode)
        refreshVpnCard()
        if (DastyabVpnService.isConnected()) {
            DastyabVpnService.start(this)
            postOutput("Switched VPN mode to ${mode.label} and reconnected.\n")
        } else {
            Toast.makeText(this, "Tunnel mode: ${mode.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStartVpn(launchName: String?, launchUrl: String?) {
        pendingGoogleLaunchName = launchName
        pendingGoogleLaunchUrl = launchUrl
        val prepIntent = VpnService.prepare(this)
        if (prepIntent != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(prepIntent, REQ_VPN_PREPARE)
            } catch (_: Exception) {
                postOutput("WARNING: System VPN consent dialog unavailable on this device.\n")
                openPendingGoogleUrlIfAny()
            }
        } else {
            DastyabVpnService.start(this)
            refreshVpnCard()
            openPendingGoogleUrlIfAny()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE) {
            if (resultCode == RESULT_OK) {
                DastyabVpnService.start(this)
                postOutput("OK: DastYab VPN tunnel started.\n")
                refreshVpnCard()
                openPendingGoogleUrlIfAny()
            } else {
                postOutput("WARNING: VPN permission declined by user.\n")
                val failedName = pendingGoogleLaunchName
                val failedUrl = pendingGoogleLaunchUrl
                pendingGoogleLaunchName = null
                pendingGoogleLaunchUrl = null
                if (failedName != null && failedUrl != null) {
                    showGoogleRescueDialog(failedName, failedUrl)
                }
            }
        }
    }

    private fun openRescueHub(autoAction: String? = null) {
        try {
            val intent = Intent(this, BlackoutRescueActivity::class.java)
            if (autoAction != null) intent.putExtra(BlackoutRescueActivity.EXTRA_AUTO_ACTION, autoAction)
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            postStatus("Blackout Rescue screen unavailable")
        }
    }

    private fun showQuickLayerSwitcherDialog() {
        val layers = VpnLayerStore.loadAll(this)
        val activeId = VpnLayerStore.getActiveLayerId(this)
        val labels = layers.map { l ->
            val mark = if (l.id == activeId) "● " else "   "
            "$mark${l.name} (${l.mode.label})"
        } + "+ Manage / Add VPN Layers & Channels…"

        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Quick Switch VPN Layer")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == layers.size) {
                        openActivity(VpnLayersActivity::class.java)
                    } else {
                        val picked = layers[which]
                        VpnLayerStore.activateLayer(this, picked, restartIfConnected = true)
                        refreshVpnCard()
                        postOutput("Switched active VPN layer → ${picked.name} (${picked.mode.label})\n")
                        if (!DastyabVpnService.isConnected()) {
                            requestStartVpn(null, null)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun showGoogleRescueDialog(serviceName: String, url: String) {
        val options = arrayOf(
            "1. Open $serviceName anyway (current connection)",
            "2. Activate Emergency DNS Blackout VPN (DoH / WhiteDNS)",
            "3. Run Smart Config Harvester (GitHub → Telegram)",
            "4. Run Clean IP & DNS Scout",
            "5. Run 7-Layer Connectivity Troubleshooter"
        )
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Troubleshoot $serviceName Access")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> openBrowserUrl(serviceName, url)
                        1 -> openRescueHub(BlackoutRescueActivity.ACTION_DNS_VPN)
                        2 -> openRescueHub(BlackoutRescueActivity.ACTION_HARVEST)
                        3 -> openRescueHub(BlackoutRescueActivity.ACTION_SCOUT)
                        4 -> openRescueHub(BlackoutRescueActivity.ACTION_TROUBLESHOOT)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun openPendingGoogleUrlIfAny() {
        val url = pendingGoogleLaunchUrl ?: return
        val name = pendingGoogleLaunchName ?: "Google Service"
        pendingGoogleLaunchUrl = null
        pendingGoogleLaunchName = null
        verifyAndOpenGoogleService(name, url)
    }

    private fun launchGoogleServiceViaVpn(serviceName: String, url: String) {
        setBusy(true)
        postStatus("Preparing best route for $serviceName…")
        setStatusDot(DotState.ORANGE)
        thread(isDaemon = true, name = "dastyab-google-launch") {
            val settings = TunnelConfig.load(this)
            var route = GeoBypassEngine.resolveCurrentRoute(this, settings)
            if (route.shouldStartLocalBridge) {
                LocalProxyService.startSafely(this)
                var waited = 0
                while (LocalProxyService.activePort() == null && waited < 900) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { break }
                    waited += 60
                }
                route = GeoBypassEngine.resolveCurrentRoute(this, settings)
            }
            postUi {
                setBusy(false)
                if (route.shouldStartDastyabVpn && !DastyabVpnService.isConnected()) {
                    postOutput("Starting ${settings.mode.label} encrypted tunnel before checking $serviceName…\n")
                    requestStartVpn(serviceName, url)
                } else {
                    if (route.externalVpnActive) {
                        postOutput("External VPN (e.g. Lantern) active — keeping OS VPN untouched.\n")
                    } else if (route.webViewHttpProxyPort != null) {
                        postOutput("Using Internal App Proxy (${route.routeLabel}) without conflicting with system VPN.\n")
                    }
                    verifyAndOpenGoogleService(serviceName, url, route)
                }
            }
        }
    }

    /**
     * Smart AI Studio / Flow launcher: verifies BOTH egress country (151-country Flow allowlist)
     * and real HTTPS reachability through the active VPN/proxy path before opening.
     */
    private fun verifyAndOpenGoogleService(
        serviceName: String,
        url: String,
        resolvedRoute: GeoBypassEngine.RouteResolution? = null
    ) {
        setBusy(true)
        postStatus("Verifying $serviceName geo & reachability…")
        setStatusDot(DotState.ORANGE)
        postOutput("Verifying live geo country & HTTPS access for $serviceName ($url)…\n")

        thread(isDaemon = true, name = "dastyab-verify-google") {
            val route = resolvedRoute ?: GeoBypassEngine.resolveCurrentRoute(this)
            val probe = GeoBypassEngine.runAccessProbe(route, timeoutMs = 3800)
            val isFlowTarget = url.contains("flow.google.com", ignoreCase = true)
            val targetOk = if (isFlowTarget) probe.readyForFlow else probe.readyForAiStudio

            postUi {
                setBusy(false)
                val ccTag = probe.egress?.countryCode ?: "??"
                if (targetOk) {
                    val lat = if (isFlowTarget) probe.flow.latencyMs else probe.aiStudio.latencyMs
                    tvReachabilityBadge.text = "$serviceName ✓ ($ccTag · ${lat}ms · ${probe.routeLabel})"
                    postOutput("OK: $serviceName verified via ${probe.routeLabel} (Exit $ccTag ${probe.egress?.ip ?: ""})\n")
                    openBrowserUrl(serviceName, url)
                } else {
                    val reason = when {
                        probe.egress != null && !probe.flowCountrySupported ->
                            "Geo-blocked country $ccTag (Flow requires foreign IP)"
                        isFlowTarget && probe.flow.unsupportedRegion ->
                            "Flow returned /unsupported-country ($ccTag)"
                        isFlowTarget -> probe.flow.detail
                        else -> probe.aiStudio.detail
                    }
                    tvReachabilityBadge.text = "$serviceName blocked ($ccTag) → Tap Route & Proxy"
                    postOutput("WARNING: $serviceName check failed via ${probe.routeLabel}: $reason\n")
                    postStatus("$serviceName geo/access issue — switch route or open anyway")
                    setStatusDot(DotState.RED)
                    UiSafety.safeShowDialog(
                        this,
                        AlertDialog.Builder(this)
                            .setTitle(probe.headline)
                            .setMessage(
                                "Route: ${probe.routeLabel}\n" +
                                    "Exit IP: ${probe.egress?.ip ?: "Unknown"} ($ccTag)\n" +
                                    "Issue: $reason\n\n" +
                                    probe.recommendation
                            )
                            .setPositiveButton("Route / Proxy Modes") { _, _ ->
                                GoogleAccessSheet.show(this, serviceName, url) { refreshVpnCard() }
                            }
                            .setNeutralButton("Open Browser Anyway") { _, _ ->
                                openBrowserUrl(serviceName, url)
                            }
                            .setNegativeButton("1-Tap Autopilot") { _, _ -> runNewbieAutopilot() }
                    )
                }
            }
        }
    }

    /**
     * 1-Tap Newbie Autopilot: does everything automatically (reachability check, DoH scout,
     * non-blackholing VPN activation, GitHub→Telegram harvest, parallel config benchmark,
     * and final AI Studio & Google Flow verification).
     */
    private fun runNewbieAutopilot() {
        if (operation.get() != null) return
        setBusy(true)
        btnNewbieAutopilot.isEnabled = false
        btnSimpleMagicAutopilot.isEnabled = false
        simpleProgress.visibility = View.VISIBLE
        postStatus("Autopilot running (auto-fixing AI Studio & Flow)…")
        setStatusDot(DotState.ORANGE)
        val runningText = "Autopilot running… scouting DoH, harvesting & verifying…"
        tvReachabilityBadge.text = runningText
        tvSimpleReachability.text = runningText
        applyFormattedOutput("\n=== DASTYAB 1-TAP NEWBIE AUTOPILOT ===\n")

        thread(isDaemon = true, name = "dastyab-newbie-autopilot") {
            val report = NewbieAutopilot.run(this) { step ->
                postOutput("$step\n")
            }
            postUi {
                setBusy(false)
                btnNewbieAutopilot.isEnabled = true
                btnSimpleMagicAutopilot.isEnabled = true
                simpleProgress.visibility = View.GONE
                refreshVpnCard()

                val v = report.verification
                val webMark = if (v.internet.isOk) "Web ✓" else "Web ✗"
                val aiMark = if (v.aiStudio.isOk) "AI Studio ✓" else "AI Studio ✗"
                val flowMark = if (v.flow.isOk) "Flow ✓" else "Flow ✗"
                val badge = "$webMark · $aiMark · $flowMark"
                tvReachabilityBadge.text = badge
                tvSimpleReachability.text = badge

                postStatus(report.headline)
                setStatusDot(
                    when (report.outcome) {
                        NewbieAutopilot.Outcome.READY_DIRECT,
                        NewbieAutopilot.Outcome.READY_WITH_VPN -> DotState.GREEN
                        NewbieAutopilot.Outcome.NEEDS_VPN_CONSENT,
                        NewbieAutopilot.Outcome.NEEDS_FULL_PROXY_APP -> DotState.ORANGE
                        NewbieAutopilot.Outcome.OFFLINE_OR_BLACKOUT -> DotState.RED
                    }
                )

                val dialog = AlertDialog.Builder(this)
                    .setTitle(report.headline)
                    .setMessage(report.summary)

                when (report.outcome) {
                    NewbieAutopilot.Outcome.READY_DIRECT,
                    NewbieAutopilot.Outcome.READY_WITH_VPN -> {
                        dialog.setPositiveButton("Open AI Studio") { _, _ ->
                            openBrowserUrl("Google AI Studio", CommandCompat.AISTUDIO_URL)
                        }
                        dialog.setNeutralButton("Open Google Flow") { _, _ ->
                            openBrowserUrl("Google Flow", CommandCompat.FLOW_URL)
                        }
                        dialog.setNegativeButton(android.R.string.ok, null)
                    }
                    NewbieAutopilot.Outcome.NEEDS_VPN_CONSENT -> {
                        dialog.setPositiveButton("Allow & Connect VPN") { _, _ ->
                            requestStartVpn("Google AI Studio", CommandCompat.AISTUDIO_URL)
                        }
                        dialog.setNegativeButton(android.R.string.cancel, null)
                    }
                    NewbieAutopilot.Outcome.NEEDS_FULL_PROXY_APP -> {
                        val bestUri = report.bestHarvestedUri
                        val firstApp = report.installedProxyApps.firstOrNull()
                        if (bestUri != null) {
                            dialog.setPositiveButton(
                                if (firstApp != null) "Copy Config & Open ${firstApp.name}" else "Copy Fastest Config"
                            ) { _, _ ->
                                if (UiSafety.copyTextSafely(this, "DastYab Fastest Config", bestUri)) {
                                    Toast.makeText(this, "Fastest verified config copied!", Toast.LENGTH_LONG).show()
                                }
                                firstApp?.installedPackage?.let { pkg ->
                                    packageManager.getLaunchIntentForPackage(pkg)?.let {
                                        UiSafety.safeStartActivity(this, it)
                                    }
                                }
                            }
                        }
                        dialog.setNeutralButton("Open Rescue Hub") { _, _ -> openRescueHub() }
                        dialog.setNegativeButton(android.R.string.ok, null)
                    }
                    NewbieAutopilot.Outcome.OFFLINE_OR_BLACKOUT -> {
                        dialog.setPositiveButton("Open Blackout Rescue") { _, _ ->
                            openRescueHub(BlackoutRescueActivity.ACTION_TROUBLESHOOT)
                        }
                        dialog.setNegativeButton(android.R.string.ok, null)
                    }
                }
                UiSafety.safeShowDialog(this, dialog)
            }
        }
    }

    private fun openBrowserUrl(serviceName: String, url: String) {
        postOutput("Opening $serviceName in DastYab built-in browser…\n")
        postStatus("Opening $serviceName…")
        setStatusDot(DotState.ORANGE)
        try {
            InAppBrowserActivity.launch(this, serviceName, url)
            val vpnNote = if (DastyabVpnService.isConnected()) " (VPN active)" else ""
            postOutput("OK: $serviceName opened$vpnNote → $url\n")
            postStatus("$serviceName opened$vpnNote")
            setStatusDot(DotState.GREEN)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: android.content.ActivityNotFoundException) {
                postOutput("ERROR: No browser available. Open manually: $url\n")
                postStatus("No browser available")
                setStatusDot(DotState.RED)
            }
        }
    }

    private fun showImportVlessDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.tunnel_vless_hint)
            setText(TunnelConfig.load(this@MainActivity).vlessUri)
            setSingleLine(false)
            maxLines = 4
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.vpn_btn_import_vless))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val uri = input.text.toString().trim()
                    val parsed = TunnelConfig.parseVlessUri(uri)
                    if (uri.isEmpty()) {
                        TunnelConfig.saveVlessUri(this, "")
                        refreshVpnCard()
                        postOutput("Cleared custom VLESS endpoint (using Cloudflare Edge Smart DNS).\n")
                    } else if (parsed == null) {
                        Toast.makeText(this, getString(R.string.tunnel_vless_invalid), Toast.LENGTH_SHORT).show()
                    } else {
                        TunnelConfig.saveVlessUri(this, uri)
                        refreshVpnCard()
                        postOutput("OK: Imported VLESS endpoint → ${parsed.summary()} (SNI: ${parsed.sni})\n")
                        if (DastyabVpnService.isConnected()) {
                            DastyabVpnService.start(this)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun refreshVpnCard() {
        if (!UiSafety.isActivityAlive(this)) return
        val statusObj = DastyabVpnService.getStatus()
        val settings = TunnelConfig.load(this)
        val externalVpn = NetworkUtils.hasActiveExternalVpn(this)
        val bridgePort = LocalProxyService.activePort()
        val activeMode = if (statusObj.state == DastyabVpnService.State.CONNECTED) statusObj.mode else settings.mode

        when {
            statusObj.state == DastyabVpnService.State.CONNECTED -> {
                vpnDot.background = getDrawable(R.drawable.status_dot_green)
                simpleStatusDot.background = getDrawable(R.drawable.status_dot_green)
                tvVpnState.text = "${getString(R.string.vpn_status_connected)} · ${activeMode.label}"
                tvSimpleHeroTitle.text = getString(R.string.simple_hero_connected)
                btnVpnToggle.text = getString(R.string.vpn_btn_disconnect)
                btnSimpleQuickToggle.text = getString(R.string.vpn_btn_disconnect)
            }
            statusObj.state == DastyabVpnService.State.CONNECTING -> {
                vpnDot.background = getDrawable(R.drawable.status_dot_orange)
                simpleStatusDot.background = getDrawable(R.drawable.status_dot_orange)
                tvVpnState.text = getString(R.string.vpn_status_connecting)
                tvSimpleHeroTitle.text = getString(R.string.vpn_status_connecting)
                btnVpnToggle.text = getString(R.string.vpn_btn_disconnect)
                btnSimpleQuickToggle.text = getString(R.string.vpn_btn_disconnect)
            }
            externalVpn || bridgePort != null -> {
                vpnDot.background = getDrawable(R.drawable.status_dot_green)
                simpleStatusDot.background = getDrawable(R.drawable.status_dot_green)
                val extLabel = when {
                    externalVpn && bridgePort != null -> "External VPN + Proxy :$bridgePort"
                    externalVpn -> "External VPN Active (Lantern / System)"
                    else -> "Local Proxy Active (:$bridgePort)"
                }
                tvVpnState.text = extLabel
                tvSimpleHeroTitle.text = extLabel
                btnVpnToggle.text = getString(R.string.vpn_btn_connect)
                btnSimpleQuickToggle.text = getString(R.string.vpn_btn_connect)
            }
            else -> {
                vpnDot.background = getDrawable(R.drawable.status_dot_gray)
                simpleStatusDot.background = getDrawable(R.drawable.status_dot_gray)
                tvVpnState.text = "${getString(R.string.vpn_status_disconnected)} · ${activeMode.label}"
                tvSimpleHeroTitle.text = getString(R.string.simple_hero_disconnected)
                btnVpnToggle.text = getString(R.string.vpn_btn_connect)
                btnSimpleQuickToggle.text = getString(R.string.vpn_btn_connect)
            }
        }

        val endpoint = when {
            activeMode == TunnelConfig.Mode.DNS_BLACKOUT -> "DoH (${settings.dohUrl.removePrefix("https://")})"
            else -> settings.parsedEndpoint()?.summary() ?: "Cloudflare Edge Smart DNS"
        }
        val splitSuffix = if (activeMode == TunnelConfig.Mode.SPLIT_INCLUDE || activeMode == TunnelConfig.Mode.SPLIT_EXCLUDE) {
            " · ${settings.splitPackages.size} app(s)"
        } else {
            ""
        }
        val iranBypassTag = if (DomainDnsRules.load(this).iranBypassEnabled) " · .ir Bypass" else ""
        val watchdogSnap = FailoverWatchdog.getSnapshot()
        val watchdogTag = if (statusObj.state == DastyabVpnService.State.CONNECTED && watchdogSnap.active) {
            " · 5m Auto-Heal"
        } else {
            ""
        }
        val proxyTag = when {
            bridgePort != null -> " · LocalPort :$bridgePort"
            settings.upstreamProxyScheme != TunnelConfig.ProxyScheme.DIRECT ->
                " · ${settings.upstreamProxyScheme.name}:${settings.upstreamProxyPort}"
            else -> ""
        }
        tvVpnDetails.text = "$endpoint · DNS ${settings.dnsPrimary}$splitSuffix$iranBypassTag$watchdogTag$proxyTag"

        // Highlight active quick-mode chip
        val isSplit = activeMode == TunnelConfig.Mode.SPLIT_INCLUDE || activeMode == TunnelConfig.Mode.SPLIT_EXCLUDE
        btnModeSmart.alpha = if (activeMode == TunnelConfig.Mode.SMART) 1.0f else 0.55f
        btnModeGlobal.alpha = if (activeMode == TunnelConfig.Mode.GLOBAL) 1.0f else 0.55f
        btnModeSplit.alpha = if (isSplit) 1.0f else 0.55f
        btnModeDns.alpha = if (activeMode == TunnelConfig.Mode.DNS_BLACKOUT) 1.0f else 0.55f
    }

    private fun openActivity(type: Class<out Activity>) {
        try { startActivity(Intent(this, type)) }
        catch (_: android.content.ActivityNotFoundException) { postStatus("Screen unavailable") }
    }

    /** Switches between Simple Mode, Advanced Mode, and Terminal Mode (CPython 3.12). */
    private fun applyUiMode(mode: UiModePrefs.Mode, persist: Boolean = true) {
        if (persist) UiModePrefs.setMode(this, mode)
        simpleModeContainer.visibility = if (mode == UiModePrefs.Mode.SIMPLE) View.VISIBLE else View.GONE
        advancedModeContainer.visibility = if (mode == UiModePrefs.Mode.ADVANCED) View.VISIBLE else View.GONE
        terminalModeContainer.visibility = if (mode == UiModePrefs.Mode.TERMINAL) View.VISIBLE else View.GONE
        btnTabSimple.alpha = if (mode == UiModePrefs.Mode.SIMPLE) 1.0f else 0.55f
        btnTabAdvanced.alpha = if (mode == UiModePrefs.Mode.ADVANCED) 1.0f else 0.55f
        btnTabTerminal.alpha = if (mode == UiModePrefs.Mode.TERMINAL) 1.0f else 0.55f
        if (mode == UiModePrefs.Mode.TERMINAL) {
            initTerminalSessionIfNeeded()
        }
    }

    private fun setupTerminalModeUi() {
        buildTerminalPresetChips(R.id.terminalChipsFlow, TerminalCommandRouter.PYTHON_FLOW_PRESETS)
        buildTerminalPresetChips(R.id.terminalChipsShell, TerminalCommandRouter.LINUX_PYTHON_PRESETS)

        btnTerminalRun.setOnClickListener {
            runTerminalCommand(terminalInput.text.toString())
        }
        btnTerminalStop.setOnClickListener { cancelTerminalCommand() }
        findViewById<Button>(R.id.btnTerminalClear).setOnClickListener {
            tvTerminalOutput.text = ""
        }
        findViewById<Button>(R.id.btnTerminalCopy).setOnClickListener {
            if (UiSafety.copyTextSafely(this, "Dastyab Terminal", tvTerminalOutput.text.toString())) {
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnTerminalSyncNodes).setOnClickListener {
            thread(isDaemon = true, name = "dastyab-term-sync") {
                val count = PythonRuntimeBridge.syncProfile(this)
                postUi {
                    appendTerminalOutput("[sync] Synced $count node(s) + active route into ~/.dastyab/profiles/default/\n")
                }
            }
        }
        findViewById<Button>(R.id.btnTerminalOpenAiStudio).setOnClickListener {
            launchGoogleServiceViaVpn("Google AI Studio", CommandCompat.AISTUDIO_URL)
        }
        findViewById<Button>(R.id.btnTerminalOpenFlow).setOnClickListener {
            launchGoogleServiceViaVpn("Google Flow", CommandCompat.FLOW_URL)
        }
        terminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runTerminalCommand(terminalInput.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun buildTerminalPresetChips(containerId: Int, presets: List<TerminalCommandRouter.QuickPreset>) {
        val container = findViewById<LinearLayout>(containerId)
        presets.forEach { preset ->
            container.addView(Button(this, null, 0, R.style.DastyabChip).apply {
                text = preset.label
                setOnClickListener {
                    if (!terminalRunning.get()) {
                        terminalInput.setText(preset.command)
                        if (preset.autoRun) {
                            runTerminalCommand(preset.command)
                        }
                    }
                }
            })
        }
    }

    private fun initTerminalSessionIfNeeded() {
        if (terminalBannerShown) return
        terminalBannerShown = true
        appendTerminalOutput("Booting embedded CPython 3.12 Linux-compatible shell...\n")
        thread(isDaemon = true, name = "dastyab-cpython-boot") {
            val ver = PythonRuntimeBridge.getPythonVersion(this)
            val synced = PythonRuntimeBridge.syncProfile(this)
            val cwd = PythonRuntimeBridge.getPromptPath(this)
            postUi {
                tvTerminalRuntimeBadge.text = ver
                tvTerminalPromptHeader.text = TerminalCommandRouter.formatPrompt(cwd).trimEnd()
                appendTerminalOutput(
                    "Ready: $ver | Synced $synced node(s) to ~/.dastyab/profiles/default/\n" +
                        "Run `dastyab flow best`, `dastyab flow start`, `dastyab aistudio verify`, or `python3 -c \"...\"`.\n\n"
                )
            }
        }
    }

    private fun runTerminalCommand(rawCommand: String) {
        if (!terminalRunning.compareAndSet(false, true)) return
        val cmd = TerminalCommandRouter.normalizeInput(rawCommand)
        when (TerminalCommandRouter.classify(cmd)) {
            TerminalCommandRouter.Action.EMPTY -> {
                terminalRunning.set(false)
                return
            }
            TerminalCommandRouter.Action.CLEAR -> {
                tvTerminalOutput.text = ""
                terminalInput.setText("")
                terminalRunning.set(false)
                return
            }
            TerminalCommandRouter.Action.PYTHON_SHELL -> { /* execute */ }
        }

        val currentPrompt = tvTerminalPromptHeader.text.toString()
        appendTerminalOutput("$currentPrompt $cmd\n")
        terminalInput.setText("")
        setTerminalBusy(true)

        thread(isDaemon = true, name = "dastyab-cpython-exec") {
            val exitCode = try {
                PythonRuntimeBridge.executeCommand(
                    context = this,
                    commandLine = cmd,
                    callbacks = object : PythonRuntimeBridge.HostCallbacks {
                        override fun onStdout(text: String) {
                            postUi { appendTerminalOutput(text) }
                        }

                        override fun onEvent(kind: String, payloadJson: String) {
                            postUi { handleTerminalHostEvent(kind, payloadJson) }
                        }

                        override fun runNativeCli(args: List<String>, outputCallback: (String) -> Unit): Int {
                            return PythonRuntimeBridge.execNativeBinary(
                                context = this@MainActivity,
                                binary = binary,
                                args = args,
                                onChunk = outputCallback,
                                processHolder = terminalProcessHolder
                            )
                        }
                    }
                )
            } finally {
                terminalRunning.set(false)
            }
            val nextCwd = PythonRuntimeBridge.getPromptPath(this)
            postUi {
                setTerminalBusy(false)
                tvTerminalPromptHeader.text = TerminalCommandRouter.formatPrompt(nextCwd).trimEnd()
                if (exitCode != 0) {
                    appendTerminalOutput("[exit $exitCode]\n")
                }
                refreshVpnCard()
            }
        }
    }

    private fun cancelTerminalCommand() {
        PythonRuntimeBridge.requestStop(this)
        terminalProcessHolder.get()?.let { terminate(it) }
        appendTerminalOutput("\n^C Stop requested\n")
    }

    private fun setTerminalBusy(busy: Boolean) {
        btnTerminalRun.isEnabled = !busy
        terminalInput.isEnabled = !busy
        btnTerminalStop.visibility = if (busy) View.VISIBLE else View.GONE
        terminalProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun appendTerminalOutput(text: String) {
        tvTerminalOutput.append(formatOutput(text))
        if (tvTerminalOutput.length() > MAX_OUTPUT_CHARS) {
            val full = tvTerminalOutput.text
            tvTerminalOutput.text = full.subSequence(full.length - MAX_OUTPUT_CHARS, full.length)
        }
        terminalOutputScroll.post {
            terminalOutputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun handleTerminalHostEvent(kind: String, payloadJson: String) {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull() ?: JSONObject()
        when (kind) {
            "CLEAR_TERMINAL" -> tvTerminalOutput.text = ""
            "OPEN_BROWSER" -> {
                val url = obj.optString("url", CommandCompat.FLOW_URL)
                val title = obj.optString("title", "DastYab Browser")
                openBrowserUrl(title, url)
            }
            "SYNC_BEST_VLESS" -> {
                val uri = obj.optString("uri", "")
                if (uri.startsWith("vless://", ignoreCase = true)) {
                    val cur = TunnelConfig.load(this)
                    if (cur.vlessUri.isBlank()) {
                        TunnelConfig.saveVlessUri(this, uri)
                        appendTerminalOutput("[android] Synced fastest VLESS endpoint to active profile.\n")
                    }
                }
            }
        }
    }

    /** 1-tap Speed, Exit Country/Geo & AI Studio / Flow live check right from Simple Mode. */
    private fun runQuickSpeedAndIpCheck() {
        simpleProgress.visibility = View.VISIBLE
        tvSimpleReachability.text = "Testing Exit Country, AI Studio & Google Flow…"
        thread(isDaemon = true, name = "dastyab-quick-speed") {
            val route = GeoBypassEngine.resolveCurrentRoute(this)
            val probe = GeoBypassEngine.runAccessProbe(route, timeoutMs = 3800)
            postUi {
                simpleProgress.visibility = View.GONE
                val egress = probe.egress
                val ccMark = if (egress != null) {
                    if (probe.flowCountrySupported) "${egress.countryCode} ✓" else "${egress.countryCode} ✗"
                } else "Geo ?"
                val aiMark = if (probe.readyForAiStudio) "AI Studio ✓" else "AI Studio ✗"
                val flowMark = if (probe.readyForFlow) "Flow ✓" else "Flow ✗"
                val badge = "$ccMark · $aiMark · $flowMark"
                tvSimpleReachability.text = badge
                tvReachabilityBadge.text = badge

                val ipLine = if (egress != null) {
                    val supportTag = if (probe.flowCountrySupported) "Supported for Flow ✓" else "Geo-blocked for Flow ✗"
                    "Route  : ${probe.routeLabel}\nExit IP: ${egress.ip} (${egress.countryCode} · ${egress.countryName})\nGeo Gate: $supportTag"
                } else {
                    "Route  : ${probe.routeLabel}\nExit IP: Unreachable (check Lantern, proxy port, or Autopilot)"
                }
                val msg = "$ipLine\n\nAI Studio: ${probe.aiStudio.detail}\nGoogle Flow: ${probe.flow.detail}\n\n${probe.recommendation}"
                UiSafety.safeShowDialog(
                    this,
                    AlertDialog.Builder(this)
                        .setTitle(probe.headline)
                        .setMessage(msg)
                        .setPositiveButton("Route / Proxy Modes") { _, _ ->
                            GoogleAccessSheet.show(this) { refreshVpnCard() }
                        }
                        .setNeutralButton("1-Tap Autopilot") { _, _ -> runNewbieAutopilot() }
                        .setNegativeButton(android.R.string.ok, null)
                )
            }
        }
    }

    /** Show the advanced (raw command) section. */
    private fun showAdvanced() {
        advancedSection.visibility = View.VISIBLE
        btnShowAdvanced.visibility = View.GONE
    }

    /** Hide the advanced (raw command) section. */
    private fun hideAdvanced() {
        advancedSection.visibility = View.GONE
        btnShowAdvanced.visibility = View.VISIBLE
    }

    /** Run a predefined simple action command (no typing). */
    private fun runSimple(label: String, args: List<String>) {
        CommandHistory.add(label)
        setupHistorySpinner()
        applyFormattedOutput("\n\$ dastyab ${args.joinToString(" ")}\n---\n")
        setBusy(true)
        postStatus("Running…")
        setStatusDot(DotState.ORANGE)

        when (val verdict = CommandCompat.check(args)) {
            is CommandCompat.Verdict.OpenAistudio -> {
                setBusy(false)
                launchGoogleServiceViaVpn("Google AI Studio", verdict.url)
                return
            }
            is CommandCompat.Verdict.OpenFlow -> {
                setBusy(false)
                launchGoogleServiceViaVpn("Google Flow", verdict.url)
                return
            }
            is CommandCompat.Verdict.Blocked -> {
                postStatus(CommandCompat.blockedTitle(verdict))
                postOutput(CommandCompat.blockedMessage(verdict) + "\n")
                setStatusDot(DotState.RED)
                setBusy(false)
                return
            }
            CommandCompat.Verdict.RunNative -> { /* proceed */ }
        }

        if (!binary.isFile || !binary.canExecute()) {
            setBusy(false)
            evaluateBinaryState()
            return
        }
        runCommand(args, label)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingOutput", output.text.toString())
    }

    override fun onDestroy() {
        if (vpnReceiverRegistered) {
            try { unregisterReceiver(vpnStateReceiver) } catch (_: Exception) {}
            vpnReceiverRegistered = false
        }
        synchronized(uiLock) {
            destroyed = true
            handler.removeCallbacksAndMessages(null)
        }
        cancelCurrentOperation()
        cancelTerminalCommand()
        super.onDestroy()
    }

    private fun evaluateBinaryState() {
        val ready = binary.isFile && binary.canExecute() && BinaryUtils.isValidElfBinary(binary)
        btnRun.isEnabled = ready
        postStatus(if (ready) getString(R.string.status_ready)
            else "Bundled CLI unavailable for this device. Install a compatible APK from Releases.")
        setStatusDot(if (ready) DotState.GREEN else DotState.RED)
    }

    private fun runTyped() {
        if (operation.get() != null) return
        val text = argsInput.text.toString()
        if (text.length > MAX_ARGS_LENGTH) {
            postStatus(getString(R.string.error_args_too_long))
            return
        }
        val args = try { CommandArgs.parse(text) } catch (e: IllegalArgumentException) {
            postStatus(e.message ?: "Invalid arguments")
            return
        }
        if (args.isEmpty()) return
        when (val verdict = CommandCompat.check(args)) {
            is CommandCompat.Verdict.OpenAistudio -> {
                launchGoogleServiceViaVpn("Google AI Studio", verdict.url)
                return
            }
            is CommandCompat.Verdict.OpenFlow -> {
                launchGoogleServiceViaVpn("Google Flow", verdict.url)
                return
            }
            is CommandCompat.Verdict.Blocked -> {
                postStatus(CommandCompat.blockedTitle(verdict))
                postOutput(CommandCompat.blockedMessage(verdict) + "\n")
                setStatusDot(DotState.RED)
                setBusy(false)
                return
            }
            CommandCompat.Verdict.RunNative -> { /* proceed to run the binary */ }
        }
        if (!binary.isFile || !binary.canExecute()) {
            evaluateBinaryState()
            return
        }
        runCommand(args, text)
    }

    private fun buildQuickButtons(containerId: Int, commands: List<String>) {
        val container = findViewById<LinearLayout>(containerId)
        commands.forEach { command ->
            container.addView(Button(this, null, 0, R.style.DastyabChip).apply {
                text = command
                setOnClickListener {
                    if (operation.get() == null) {
                        argsInput.setText(command)
                        runTyped()
                    }
                }
            })
        }
    }

    private fun setupHistorySpinner() {
        val items = listOf(getString(R.string.history_prompt)) + CommandHistory.getCommands()
        historySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        historySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && operation.get() == null) argsInput.setText(items[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyPersistedSettings() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(SettingsActivity.PREF_LOG_LEVEL, null)?.let {
            try { DastyabLogger.setMinFileLevel(DastyabLogger.LogLevel.valueOf(it)) }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun cancelCurrentOperation() {
        operation.get()?.let { current ->
            current.cancelled.set(true)
            current.process.get()?.let { terminate(it) }
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly()
    }

    // Timed Process.waitFor is API 26; polling exitValue also supports API 24/25.
    private fun awaitExit(process: Process): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_TIMEOUT_MS)
        while (true) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) { }
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(50)
        }
    }

    /** A timed wait runs independently of the output reader, even for a silent child. */
    private fun runCommand(args: List<String>, commandText: String) {
        val current = Operation()
        if (!operation.compareAndSet(null, current)) return
        CommandHistory.add(commandText)
        setupHistorySpinner()
        applyFormattedOutput("\n\$ dastyab $commandText\n---\n")
        setBusy(true)
        postStatus("Running…")
        setStatusDot(DotState.ORANGE)
        val command = listOf(binary.absolutePath) + args
        val home = filesDir
        val temporary = cacheDir.absolutePath
        thread(isDaemon = true, name = "dastyab-command") {
            var process: Process? = null
            var readerThread: Thread? = null
            var result = "Command failed"
            var success = false
            try {
                if (!current.cancelled.get()) {
                    val child = ProcessBuilder(command).apply {
                        directory(home)
                        environment()["HOME"] = home.absolutePath
                        environment()["TMPDIR"] = temporary
                        redirectErrorStream(true)
                    }.start()
                    process = child
                    current.process.set(child)
                    if (current.cancelled.get()) terminate(child)
                    child.outputStream.close()
                    readerThread = thread(isDaemon = true, name = "dastyab-output") {
                        try {
                            child.inputStream.reader(Charsets.UTF_8).use { reader ->
                                val buffer = CharArray(4096)
                                while (true) {
                                    val count = reader.read(buffer)
                                    if (count < 0) break
                                    postOutput(String(buffer, 0, count))
                                }
                            }
                        } catch (_: java.io.IOException) {
                            // Closing the child during cancellation/timeout ends the reader.
                        }
                    }
                    if (!awaitExit(child)) {
                        result = "Command timed out after ${PROCESS_TIMEOUT_MS / 1000} seconds"
                        terminate(child)
                    } else {
                        val code = child.exitValue()
                        result = "--- exit code $code ---"
                        success = code == 0
                    }
                }
            } catch (e: Exception) {
                result = "ERROR: ${e.message ?: e.javaClass.simpleName}"
                DastyabLogger.e("Run", e, "Command failed")
            } finally {
                process?.let { child ->
                    runCatching { terminate(child) }
                    runCatching { readerThread?.join(1000) }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                    runCatching { child.outputStream.close() }
                }
                current.process.set(null)
                if (current.cancelled.get()) {
                    result = "Command cancelled."
                    success = false
                }
                val finalResult = result
                val finalSuccess = success
                postUi {
                    applyFormattedOutput("\n$finalResult\n")
                    status.text = finalResult
                    setStatusDot(if (finalSuccess) DotState.GREEN else DotState.RED)
                    operation.compareAndSet(current, null)
                    setBusy(false)
                }
                synchronized(uiLock) {
                    if (destroyed) operation.compareAndSet(current, null)
                }
            }
        }
    }

    /** Enqueue and teardown use the same lock: no callbacks can be added after teardown. */
    private fun postUi(action: () -> Unit) {
        synchronized(uiLock) {
            if (!destroyed && UiSafety.isActivityAlive(this)) handler.post {
                synchronized(uiLock) {
                    if (!destroyed && UiSafety.isActivityAlive(this)) {
                        try {
                            action()
                        } catch (t: Throwable) {
                            DastyabLogger.e("MainActivity", t, "Suppressed UI callback error")
                        }
                    }
                }
            }
        }
    }

    private fun postStatus(message: String) = postUi { status.text = message }
    private fun postOutput(message: String) = postUi { applyFormattedOutput(message) }

    private fun applyFormattedOutput(message: String) {
        outputEmptyState.visibility = View.GONE
        output.visibility = View.VISIBLE
        output.append(formatOutput(message))
        if (output.length() > MAX_OUTPUT_CHARS) {
            output.text = output.text.subSequence(output.length() - MAX_OUTPUT_CHARS, output.length())
        }
        output.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    fun formatOutput(text: String): Spannable {
        val spans = SpannableStringBuilder(text)
        var offset = 0
        for (line in text.split('\n')) {
            val lower = line.trimStart().lowercase()
            val color = when {
                lower.startsWith("error") -> Color.rgb(0xF8, 0x71, 0x71)
                lower.startsWith("warn") -> Color.rgb(0xFB, 0xBF, 0x24)
                lower.startsWith("ok") || lower.startsWith("success") -> Color.rgb(0x4A, 0xDE, 0x80)
                else -> null
            }
            if (color != null) spans.setSpan(
                ForegroundColorSpan(color),
                offset,
                offset + line.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (Regex("""^---\s*exit code\s+-?\d+\s*---$""", RegexOption.IGNORE_CASE)
                    .matches(line.trim())) {
                spans.setSpan(
                    StyleSpan(Typeface.BOLD),
                    offset,
                    offset + line.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            Regex("https?://[^\\s]+").findAll(line).forEach { match ->
                val url = match.value
                spans.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        catch (_: android.content.ActivityNotFoundException) { }
                    }
                }, offset + match.range.first, offset + match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            offset += line.length + 1
        }
        return spans
    }

    private enum class DotState { GREEN, ORANGE, RED }
    private fun setStatusDot(state: DotState) {
        statusDot.background = getDrawable(when (state) {
            DotState.GREEN -> R.drawable.status_dot_green
            DotState.ORANGE -> R.drawable.status_dot_orange
            DotState.RED -> R.drawable.status_dot_red
        })
    }

    private fun setBusy(busy: Boolean) {
        btnRun.isEnabled = !busy && binary.isFile && binary.canExecute()
        argsInput.isEnabled = !busy
        btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = true
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
}
