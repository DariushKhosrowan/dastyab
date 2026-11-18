package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * BlackoutRescueActivity — DastYab's resilient anti-censorship & blackout rescue hub.
 *
 * Combines:
 *  1. 5-Stage Resilient Config Harvester (GitHub Direct → jsDelivr Mirror → Telegram →
 *     Alternate VPN Detection → Temporary Bootstrap Config → Emergency DNS Blackout)
 *  2. Full Emergency DNS Blackout VPN (WhiteDNS / DoH / SlipNet)
 *  3. On-Device IP Range & Clean DNS/DoH Scout (IP-Range-Scout-Android style)
 *  4. 7-Point Smart Connectivity Troubleshooter with tailored Rescue Plan
 *  5. Installed Fallback VPN Launcher & Official GitHub Links (WhiteDNS, WhiteVPN,
 *     IP Range Scout, SlipNet, ShiroKhorshid, Lantern, Tor/Orbot, v2rayNG, Hiddify, NekoBox, Psiphon, WARP)
 */
class BlackoutRescueActivity : Activity() {

    companion object {
        const val EXTRA_AUTO_ACTION = "extra_auto_action"
        const val ACTION_HARVEST = "harvest"
        const val ACTION_SCOUT = "scout"
        const val ACTION_TROUBLESHOOT = "troubleshoot"
        const val ACTION_DNS_VPN = "dns_vpn"
        private const val REQ_VPN_BLACKOUT = 5001
    }

    private lateinit var progress: ProgressBar
    private lateinit var tvHarvestSummary: TextView
    private lateinit var tvDiagnosisHeadline: TextView
    private lateinit var tvRescueLog: TextView
    private lateinit var spinnerDohPreset: Spinner
    private lateinit var etDnsTunnelDomain: EditText
    private lateinit var fallbackToolsContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blackout_rescue)

        progress = findViewById(R.id.rescueProgress)
        tvHarvestSummary = findViewById(R.id.tvHarvestSummary)
        tvDiagnosisHeadline = findViewById(R.id.tvDiagnosisHeadline)
        tvRescueLog = findViewById(R.id.tvRescueLog)
        spinnerDohPreset = findViewById(R.id.spinnerDohPreset)
        etDnsTunnelDomain = findViewById(R.id.etDnsTunnelDomain)
        fallbackToolsContainer = findViewById(R.id.fallbackToolsContainer)

        setupDohSpinner()
        refreshHarvestSummary()
        populateFallbackTools()

        findViewById<Button>(R.id.btnRunHarvest).setOnClickListener { runSmartHarvest() }
        findViewById<Button>(R.id.btnPickHarvested).setOnClickListener { showHarvestedConfigsDialog() }
        findViewById<Button>(R.id.btnSetBootstrap).setOnClickListener { showBootstrapConfigDialog() }
        findViewById<Button>(R.id.btnActivateDnsBlackout).setOnClickListener { activateDnsBlackoutVpn() }
        findViewById<Button>(R.id.btnScoutEdgeIps).setOnClickListener { runEdgeIpScout() }
        findViewById<Button>(R.id.btnScoutCleanDns).setOnClickListener { runCleanDnsScout() }
        findViewById<Button>(R.id.btnRunTroubleshoot).setOnClickListener { runTroubleshooter() }
        findViewById<Button>(R.id.btnRescueBack).setOnClickListener { finish() }

        when (intent?.getStringExtra(EXTRA_AUTO_ACTION)) {
            ACTION_HARVEST -> runSmartHarvest()
            ACTION_SCOUT -> runEdgeIpScout()
            ACTION_TROUBLESHOOT -> runTroubleshooter()
            ACTION_DNS_VPN -> activateDnsBlackoutVpn()
        }
    }

    private fun setupDohSpinner() {
        val settings = TunnelConfig.load(this)
        val labels = CleanEndpointScout.DOH_PRESETS.map { "${it.name} · ${it.bootstrapIp}" }
        spinnerDohPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selectedIdx = CleanEndpointScout.DOH_PRESETS.indexOfFirst {
            it.url.equals(settings.dohUrl, ignoreCase = true)
        }.coerceAtLeast(0)
        spinnerDohPreset.setSelection(selectedIdx)
        etDnsTunnelDomain.setText(settings.dnsTunnelDomain)
    }

    private fun refreshHarvestSummary() {
        val cached = TunnelConfig.loadHarvestedConfigs(this)
        val settings = TunnelConfig.load(this)
        val bootstrapNote = if (settings.bootstrapConfig.isNotBlank()) " · Bootstrap saved" else ""
        tvHarvestSummary.text = if (cached.isEmpty()) {
            getString(R.string.rescue_harvester_hint) + bootstrapNote
        } else {
            "${cached.size} cached offline config(s) ready$bootstrapNote. Tap 'Run Smart Harvest' to refresh from GitHub → Telegram."
        }
    }

    private fun populateFallbackTools() {
        fallbackToolsContainer.removeAllViews()
        val tools = ConfigHarvester.detectFallbackTools(this)
        // Show installed tools first so the user can launch them immediately
        val sorted = tools.sortedByDescending { it.isInstalled }
        for (tool in sorted) {
            val btn = Button(this, null, 0, R.style.DastyabButton).apply {
                val badge = if (tool.isInstalled) "● INSTALLED — Launch" else "GitHub"
                text = "${tool.name} (${tool.category}) · $badge"
                setOnClickListener {
                    if (tool.isInstalled && tool.installedPackage != null) {
                        val launch = packageManager.getLaunchIntentForPackage(tool.installedPackage)
                        if (launch != null && UiSafety.safeStartActivity(this@BlackoutRescueActivity, launch)) {
                            Toast.makeText(
                                this@BlackoutRescueActivity,
                                "Connect ${tool.name}, then return to DastYab and tap Smart Harvest.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            openExternalUrl(tool.githubUrl)
                        }
                    } else {
                        openExternalUrl(tool.githubUrl)
                    }
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            fallbackToolsContainer.addView(btn, params)
        }
    }

    private fun runSmartHarvest() {
        if (!busy.compareAndSet(false, true)) return
        setLoading(true)
        clearLog("Starting 5-stage resilient config harvest…\n")

        thread(isDaemon = true, name = "dastyab-harvest") {
            val report = ConfigHarvester.harvestResilient(this) { line ->
                appendLog("$line\n")
            }
            UiSafety.safePostUi(this, handler) {
                setLoading(false)
                busy.set(false)
                refreshHarvestSummary()
                appendLog("\n=== RESULT: ${report.outcome.name} ===\n${report.guidance}\n")
                when (report.outcome) {
                    ConfigHarvester.HarvestOutcome.SUCCESS_GITHUB,
                    ConfigHarvester.HarvestOutcome.SUCCESS_TELEGRAM,
                    ConfigHarvester.HarvestOutcome.SUCCESS_MIXED -> {
                        Toast.makeText(this, "Harvested ${report.configs.size} configs!", Toast.LENGTH_SHORT).show()
                        showHarvestedConfigsDialog()
                    }
                    ConfigHarvester.HarvestOutcome.NEEDS_ALTERNATE_VPN,
                    ConfigHarvester.HarvestOutcome.NEEDS_BOOTSTRAP_CONFIG,
                    ConfigHarvester.HarvestOutcome.BLACKOUT_DNS_ONLY -> {
                        showHarvestFailureRecoveryDialog(report)
                    }
                }
            }
        }
    }

    private fun showHarvestFailureRecoveryDialog(report: ConfigHarvester.HarvestReport) {
        val options = mutableListOf<String>()
        options.add("1. Activate Emergency DNS Blackout VPN (DoH / WhiteDNS)")
        options.add("2. Paste Temporary Base Working Config & Connect")
        options.add("3. Run Clean IP & DNS Scout")
        if (report.installedFallbackTools.isNotEmpty()) {
            options.add("4. Launch Installed Fallback VPN (${report.installedFallbackTools.first().name})")
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("GitHub & Telegram Unreachable")
                .setMessage(report.guidance)
                .setItems(options.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> activateDnsBlackoutVpn()
                        1 -> showBootstrapConfigDialog()
                        2 -> runCleanDnsScout()
                        3 -> {
                            val pkg = report.installedFallbackTools.firstOrNull()?.installedPackage
                            if (pkg != null) {
                                packageManager.getLaunchIntentForPackage(pkg)?.let {
                                    UiSafety.safeStartActivity(this, it)
                                }
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun showHarvestedConfigsDialog() {
        val configs = TunnelConfig.loadHarvestedConfigs(this)
        if (configs.isEmpty()) {
            Toast.makeText(this, "No harvested configs yet. Tap 'Run Smart Harvest' first.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = configs.mapIndexed { idx, uri ->
            val proto = ConfigHarvester.protocolOf(uri).uppercase()
            val vless = TunnelConfig.parseVlessUri(uri)
            if (vless != null) {
                "${idx + 1}. [$proto] ${vless.summary()}"
            } else {
                "${idx + 1}. [$proto] ${uri.take(48)}…"
            }
        }.toTypedArray()

        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Harvested Config Pool (${configs.size})")
                .setItems(labels) { _, which ->
                    val chosen = configs[which]
                    if (chosen.startsWith("vless://", ignoreCase = true)) {
                        TunnelConfig.saveVlessUri(this, chosen)
                        Toast.makeText(this, "Applied VLESS config to DastYab VPN!", Toast.LENGTH_SHORT).show()
                        appendLog("Applied harvested config #${which + 1} as active VPN endpoint.\n")
                        if (DastyabVpnService.isConnected()) {
                            DastyabVpnService.start(this)
                        }
                    } else {
                        if (UiSafety.copyTextSafely(this, "DastYab Config", chosen)) {
                            Toast.makeText(this, "Copied ${ConfigHarvester.protocolOf(chosen).uppercase()} config to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                        TunnelConfig.saveBootstrapConfig(this, chosen)
                        appendLog("Copied harvested config #${which + 1} to clipboard.\n")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun showBootstrapConfigDialog() {
        val current = TunnelConfig.load(this).bootstrapConfig
        val input = EditText(this).apply {
            hint = "Paste temporary working vless://, trojan://, vmess://, ss://, or hy2:// link"
            setText(current)
            setSingleLine(false)
            maxLines = 5
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Temporary Base Bootstrap Config")
                .setMessage("When GitHub & Telegram are blocked, paste any single working config here. DastYab will save it, connect VPN, and let you harvest fresh free configs through it.")
                .setView(input)
                .setPositiveButton("Save & Connect") { _, _ ->
                    val cfg = input.text.toString().trim()
                    if (cfg.isEmpty()) {
                        TunnelConfig.saveBootstrapConfig(this, "")
                        refreshHarvestSummary()
                        return@setPositiveButton
                    }
                    TunnelConfig.saveBootstrapConfig(this, cfg)
                    refreshHarvestSummary()
                    appendLog("Saved temporary bootstrap config (${ConfigHarvester.protocolOf(cfg).uppercase()}).\n")
                    if (cfg.startsWith("vless://", ignoreCase = true)) {
                        TunnelConfig.saveMode(this, TunnelConfig.Mode.SMART)
                        requestStartVpnNow()
                    } else {
                        if (UiSafety.copyTextSafely(this, "Bootstrap Config", cfg)) {
                            Toast.makeText(this, "Saved & copied! Connect it in v2rayNG/Hiddify, then tap Smart Harvest.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNeutralButton("Paste") { _, _ ->
                    val clip = runCatching {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                    }.getOrDefault("")
                    if (clip.isNotBlank()) {
                        TunnelConfig.saveBootstrapConfig(this, clip)
                        refreshHarvestSummary()
                        Toast.makeText(this, "Pasted & saved bootstrap config!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun activateDnsBlackoutVpn() {
        val idx = spinnerDohPreset.selectedItemPosition.coerceIn(0, CleanEndpointScout.DOH_PRESETS.lastIndex)
        val preset = CleanEndpointScout.DOH_PRESETS[idx]
        val dnsDomain = etDnsTunnelDomain.text.toString().trim()
        TunnelConfig.saveDohAndDns(this, preset.url, preset.bootstrapIp, dnsDomain)
        TunnelConfig.saveMode(this, TunnelConfig.Mode.DNS_BLACKOUT)
        appendLog("Activating Emergency DNS Blackout VPN → ${preset.name} (UDP fallback: ${preset.bootstrapIp})…\n")
        requestStartVpnNow()
    }

    private fun requestStartVpnNow() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(prep, REQ_VPN_BLACKOUT)
            } catch (_: Exception) {
                Toast.makeText(this, "System VPN consent dialog unavailable.", Toast.LENGTH_SHORT).show()
            }
        } else {
            DastyabVpnService.start(this)
            Toast.makeText(this, "DastYab VPN activated!", Toast.LENGTH_SHORT).show()
            appendLog("OK: DastYab VPN is now active.\n")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_BLACKOUT && resultCode == RESULT_OK) {
            DastyabVpnService.start(this)
            Toast.makeText(this, "DastYab VPN activated!", Toast.LENGTH_SHORT).show()
            appendLog("OK: DastYab VPN is now active.\n")
        }
    }

    private fun runEdgeIpScout() {
        if (!busy.compareAndSet(false, true)) return
        setLoading(true)
        clearLog("Scouting clean Cloudflare/CDN IPv4 edge ranges (port 443)…\n")

        thread(isDaemon = true, name = "dastyab-ip-scout") {
            val results = CleanEndpointScout.scoutCleanEdgeIps()
            UiSafety.safePostUi(this, handler) {
                setLoading(false)
                busy.set(false)
                if (results.isEmpty()) {
                    appendLog("No clean edge IPs responded within 1.8s.\nTip: Try Clean DNS Scout or Emergency DNS Blackout VPN.\n")
                    return@safePostUi
                }
                appendLog("Found ${results.size} reachable Clean Edge IPs:\n")
                results.take(10).forEach { r ->
                    appendLog("  • ${r.address}  (${r.latencyMs} ms)  ${r.label}\n")
                }
                showApplyScoutedIpDialog(results.take(10))
            }
        }
    }

    private fun showApplyScoutedIpDialog(results: List<CleanEndpointScout.EndpointResult>) {
        val items = results.map { "${it.address}  ·  ${it.latencyMs} ms  (${it.label})" }.toTypedArray()
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Clean Edge IPs Found — Tap to Apply")
                .setItems(items) { _, which ->
                    val chosenIp = results[which].address
                    val current = TunnelConfig.load(this).vlessUri
                    val patched = if (current.isNotBlank()) {
                        CleanEndpointScout.patchVlessWithCleanIp(current, chosenIp)
                    } else null
                    if (patched != null) {
                        TunnelConfig.saveVlessUri(this, patched)
                        Toast.makeText(this, "Patched active VLESS config with $chosenIp!", Toast.LENGTH_SHORT).show()
                        appendLog("Patched VLESS endpoint with Clean IP $chosenIp.\n")
                        if (DastyabVpnService.isConnected()) DastyabVpnService.start(this)
                    } else {
                        if (UiSafety.copyTextSafely(this, "Clean Edge IP", chosenIp)) {
                            Toast.makeText(this, "Copied Clean IP $chosenIp to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                        appendLog("Copied Clean IP $chosenIp to clipboard (import a vless:// link to auto-patch).\n")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun runCleanDnsScout() {
        if (!busy.compareAndSet(false, true)) return
        setLoading(true)
        clearLog("Scouting unpoisoned Clean UDP DNS & DoH resolvers…\n")

        thread(isDaemon = true, name = "dastyab-dns-scout") {
            val results = CleanEndpointScout.scoutCleanDnsAndDoh()
            UiSafety.safePostUi(this, handler) {
                setLoading(false)
                busy.set(false)
                if (results.isEmpty()) {
                    appendLog("No external DNS or DoH resolvers responded.\nTip: Try an installed fallback VPN (Tor/Orbot, Lantern, SlipNet, WhiteVPN).\n")
                    return@safePostUi
                }
                appendLog("Found ${results.size} reachable DNS / DoH resolvers:\n")
                results.take(10).forEach { r ->
                    appendLog("  • ${r.label}: ${r.address} (${r.latencyMs} ms)\n")
                }
                val items = results.take(10).map { "${it.label} · ${it.latencyMs} ms" }.toTypedArray()
                UiSafety.safeShowDialog(
                    this,
                    AlertDialog.Builder(this)
                        .setTitle("Reachable Clean DNS / DoH — Tap to Apply")
                        .setItems(items) { _, which ->
                            val picked = results[which]
                            if (picked.kind == CleanEndpointScout.Kind.DOH) {
                                TunnelConfig.saveDohAndDns(this, picked.address, null)
                                Toast.makeText(this, "Set DoH resolver: ${picked.label}", Toast.LENGTH_SHORT).show()
                                appendLog("Applied DoH resolver → ${picked.address}\n")
                            } else {
                                TunnelConfig.saveDohAndDns(this, null, picked.address)
                                Toast.makeText(this, "Set Primary DNS: ${picked.address}", Toast.LENGTH_SHORT).show()
                                appendLog("Applied Primary Clean DNS → ${picked.address}\n")
                            }
                            if (DastyabVpnService.isConnected()) DastyabVpnService.start(this)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                )
            }
        }
    }

    private fun runTroubleshooter() {
        if (!busy.compareAndSet(false, true)) return
        setLoading(true)
        clearLog("Running 7-layer connectivity & censorship diagnostics…\n")

        thread(isDaemon = true, name = "dastyab-troubleshoot") {
            val report = ConnectivityTroubleshooter.runLiveDiagnostics(this) { step ->
                appendLog("  • $step\n")
            }
            UiSafety.safePostUi(this, handler) {
                setLoading(false)
                busy.set(false)
                tvDiagnosisHeadline.text = "${report.headline}\n${report.summary}"
                val s = report.snapshot
                fun fmt(ms: Long) = if (ms >= 0) "${ms}ms" else "BLOCKED"
                appendLog(
                    "\n=== DIAGNOSIS: ${report.headline} ===\n" +
                        "Clean UDP DNS : ${fmt(s.cleanDnsMs)}\n" +
                        "DoH (HTTPS)   : ${fmt(s.dohMs)}\n" +
                        "GitHub Raw    : ${fmt(s.githubDirectMs)}\n" +
                        "GitHub Mirror : ${fmt(s.githubMirrorMs)}\n" +
                        "Telegram Web  : ${fmt(s.telegramMs)}\n" +
                        "AI Studio     : ${fmt(s.aiStudioMs)}\n" +
                        "Google Flow   : ${fmt(s.flowMs)}\n" +
                        "Active VPN    : ${s.vpnActive}\n" +
                        "Fallback Apps : ${s.installedFallbackCount}\n\n" +
                        "${report.summary}\n"
                )
                showRescuePlanDialog(report)
            }
        }
    }

    private fun showRescuePlanDialog(report: ConnectivityTroubleshooter.DiagnosticReport) {
        val labels = report.rescueSteps.mapIndexed { i, step -> "${i + 1}. ${step.title} — ${step.detail}" }.toTypedArray()
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(report.headline)
                .setItems(labels) { _, which ->
                    when (report.rescueSteps[which].actionId) {
                        ConnectivityTroubleshooter.RescueActionId.CONNECT_SMART_VPN -> {
                            TunnelConfig.saveMode(this, TunnelConfig.Mode.SMART)
                            requestStartVpnNow()
                        }
                        ConnectivityTroubleshooter.RescueActionId.ENABLE_DNS_BLACKOUT_VPN -> activateDnsBlackoutVpn()
                        ConnectivityTroubleshooter.RescueActionId.HARVEST_CONFIGS -> runSmartHarvest()
                        ConnectivityTroubleshooter.RescueActionId.RUN_IP_SCOUT -> runEdgeIpScout()
                        ConnectivityTroubleshooter.RescueActionId.SET_BOOTSTRAP_CONFIG -> showBootstrapConfigDialog()
                        ConnectivityTroubleshooter.RescueActionId.LAUNCH_FALLBACK_VPN -> {
                            val installed = ConfigHarvester.detectFallbackTools(this).firstOrNull { it.isInstalled }
                            val launch = installed?.installedPackage?.let { packageManager.getLaunchIntentForPackage(it) }
                            if (launch == null || !UiSafety.safeStartActivity(this, launch)) {
                                Toast.makeText(this, "Scroll down to install a fallback VPN from GitHub.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        ConnectivityTroubleshooter.RescueActionId.OPEN_TUNNEL_SETTINGS -> {
                            UiSafety.safeStartActivity(this, Intent(this, TunnelSettingsActivity::class.java))
                        }
                    }
                }
                .setNegativeButton(android.R.string.ok, null)
        )
    }

    private fun openExternalUrl(url: String) {
        if (!UiSafety.safeStartActivity(this, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(this, "Open in browser: $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun clearLog(initial: String) {
        UiSafety.safePostUi(this, handler) { tvRescueLog.text = initial }
    }

    private fun appendLog(text: String) {
        UiSafety.safePostUi(this, handler) { tvRescueLog.append(text) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
