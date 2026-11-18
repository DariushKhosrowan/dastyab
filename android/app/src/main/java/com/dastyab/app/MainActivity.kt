package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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

    // VPN Tunnel card views
    private lateinit var vpnDot: View
    private lateinit var tvVpnState: TextView
    private lateinit var tvVpnDetails: TextView
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
            refreshVpnCard()
        }
    }

    private class Operation {
        val cancelled = AtomicBoolean(false)
        val process = AtomicReference<Process?>(null)
    }

    companion object {
        private const val PROCESS_TIMEOUT_MS = 300_000L
        private const val MAX_OUTPUT_CHARS = 50_000
        private const val MAX_ARGS_LENGTH = 4096
        private const val REQ_VPN_PREPARE = 4001
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

        // VPN Tunnel card bindings
        vpnDot = findViewById(R.id.vpnDot)
        tvVpnState = findViewById(R.id.tvVpnState)
        tvVpnDetails = findViewById(R.id.tvVpnDetails)
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

        // ── VPN Tunnel controls ──
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
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Dastyab output", output.text.toString()))
            Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val text = output.text.toString()
            if (text.isNotEmpty()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, getString(R.string.share_title)))
            }
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            val entries = CommandHistory.getAll()
            if (entries.isEmpty()) {
                Toast.makeText(this, getString(R.string.history_empty), Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this).setTitle(getString(R.string.history_dialog_title))
                    .setItems(entries.map { it.command }.toTypedArray()) { _, index ->
                        argsInput.setText(entries[index].command)
                    }.setPositiveButton(android.R.string.ok, null).show()
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
    }

    override fun onResume() {
        super.onResume()
        refreshVpnCard()
    }

    private fun registerVpnReceiver() {
        if (vpnReceiverRegistered) return
        val filter = IntentFilter(DastyabVpnService.ACTION_STATE_CHANGED)
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
            .show()
    }

    private fun showGoogleRescueDialog(serviceName: String, url: String) {
        val options = arrayOf(
            "1. Open $serviceName anyway (current connection)",
            "2. Activate Emergency DNS Blackout VPN (DoH / WhiteDNS)",
            "3. Run Smart Config Harvester (GitHub → Telegram)",
            "4. Run Clean IP & DNS Scout",
            "5. Run 7-Layer Connectivity Troubleshooter"
        )
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
            .show()
    }

    private fun openPendingGoogleUrlIfAny() {
        val url = pendingGoogleLaunchUrl ?: return
        val name = pendingGoogleLaunchName ?: "Google Service"
        pendingGoogleLaunchUrl = null
        pendingGoogleLaunchName = null
        openBrowserUrl(name, url)
    }

    private fun launchGoogleServiceViaVpn(serviceName: String, url: String) {
        val settings = TunnelConfig.load(this)
        if (settings.autoVpnForGoogle && !DastyabVpnService.isConnected()) {
            postOutput("Starting ${settings.mode.label} VPN before launching $serviceName…\n")
            requestStartVpn(serviceName, url)
        } else {
            openBrowserUrl(serviceName, url)
        }
    }

    private fun openBrowserUrl(serviceName: String, url: String) {
        postOutput("Opening $serviceName in your browser…\n")
        postStatus("Opening $serviceName…")
        setStatusDot(DotState.ORANGE)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            val vpnNote = if (DastyabVpnService.isConnected()) " (VPN active)" else ""
            postOutput("OK: $serviceName opened$vpnNote → $url\n")
            postStatus("$serviceName opened$vpnNote")
            setStatusDot(DotState.GREEN)
        } catch (_: android.content.ActivityNotFoundException) {
            postOutput("ERROR: No browser available. Open manually: $url\n")
            postStatus("No browser available")
            setStatusDot(DotState.RED)
        }
    }

    private fun showImportVlessDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.tunnel_vless_hint)
            setText(TunnelConfig.load(this@MainActivity).vlessUri)
            setSingleLine(false)
            maxLines = 4
        }
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
            .show()
    }

    private fun refreshVpnCard() {
        val statusObj = DastyabVpnService.getStatus()
        val settings = TunnelConfig.load(this)
        val activeMode = if (statusObj.state == DastyabVpnService.State.CONNECTED) statusObj.mode else settings.mode

        when (statusObj.state) {
            DastyabVpnService.State.CONNECTED -> {
                vpnDot.background = getDrawable(R.drawable.status_dot_green)
                tvVpnState.text = "${getString(R.string.vpn_status_connected)} · ${activeMode.label}"
                btnVpnToggle.text = getString(R.string.vpn_btn_disconnect)
            }
            DastyabVpnService.State.CONNECTING -> {
                vpnDot.background = getDrawable(R.drawable.status_dot_orange)
                tvVpnState.text = getString(R.string.vpn_status_connecting)
                btnVpnToggle.text = getString(R.string.vpn_btn_disconnect)
            }
            DastyabVpnService.State.DISCONNECTED -> {
                vpnDot.background = getDrawable(R.drawable.status_dot_gray)
                tvVpnState.text = "${getString(R.string.vpn_status_disconnected)} · ${activeMode.label}"
                btnVpnToggle.text = getString(R.string.vpn_btn_connect)
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
        tvVpnDetails.text = "$endpoint · DNS ${settings.dnsPrimary}$splitSuffix"

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
            if (!destroyed) handler.post {
                synchronized(uiLock) { if (!destroyed) action() }
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
