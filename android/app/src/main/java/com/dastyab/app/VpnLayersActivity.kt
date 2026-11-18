package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import kotlin.concurrent.thread

/**
 * VpnLayersActivity — Visual Multi-Layer VPN Manager + Harvester GitHub URLs & Telegram Channels Editor.
 *
 * Allows the user to:
 *  • Add, edit, clone, delete, benchmark, and 1-tap activate custom VPN layers
 *  • Export / Import all VPN layers as portable JSON
 *  • Add (single or bulk), edit, and remove custom GitHub subscription URLs (auto-converts blob → raw)
 *  • Add (single or bulk) and remove custom Telegram channels (`@channel` or `t.me/s/channel`)
 *  • Benchmark the harvested config pool in parallel and promote the fastest working config into a VPN layer
 */
class VpnLayersActivity : Activity() {

    companion object {
        private const val REQ_VPN_LAYER_START = 6001
    }

    private lateinit var progress: ProgressBar
    private lateinit var layersListContainer: LinearLayout
    private lateinit var githubUrlsContainer: LinearLayout
    private lateinit var telegramChannelsContainer: LinearLayout
    private lateinit var tvGithubCount: TextView
    private lateinit var tvTelegramCount: TextView
    private lateinit var tvLayersLog: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn_layers)

        progress = findViewById(R.id.layersProgress)
        layersListContainer = findViewById(R.id.layersListContainer)
        githubUrlsContainer = findViewById(R.id.githubUrlsContainer)
        telegramChannelsContainer = findViewById(R.id.telegramChannelsContainer)
        tvGithubCount = findViewById(R.id.tvGithubCount)
        tvTelegramCount = findViewById(R.id.tvTelegramCount)
        tvLayersLog = findViewById(R.id.tvLayersLog)

        findViewById<Button>(R.id.btnAddLayer).setOnClickListener { showLayerEditorDialog(null) }
        findViewById<Button>(R.id.btnAutoBestLayer).setOnClickListener { benchmarkAndSwitchBestLayer() }
        findViewById<Button>(R.id.btnExportLayers).setOnClickListener { exportLayersToClipboard() }
        findViewById<Button>(R.id.btnImportLayers).setOnClickListener { showImportLayersDialog() }

        findViewById<Button>(R.id.btnAddGithubUrl).setOnClickListener { showAddGithubUrlDialog() }
        findViewById<Button>(R.id.btnAddTelegramChannel).setOnClickListener { showAddTelegramChannelDialog() }
        findViewById<Button>(R.id.btnBenchmarkPool).setOnClickListener { benchmarkHarvestedPool() }
        findViewById<Button>(R.id.btnResetSources).setOnClickListener {
            HarvestSourceStore.resetToDefaults(this)
            refreshSourcesUi()
            Toast.makeText(this, "Custom sources reset to built-in defaults.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnLayersBack).setOnClickListener { finish() }

        refreshAllUi()
    }

    private fun refreshAllUi() {
        refreshLayersUi()
        refreshSourcesUi()
    }

    private fun refreshLayersUi() {
        layersListContainer.removeAllViews()
        val layers = VpnLayerStore.loadAll(this)
        val activeId = VpnLayerStore.getActiveLayerId(this)
        val density = resources.displayMetrics.density

        for (layer in layers) {
            val isActive = layer.id == activeId
            val btn = Button(this, null, 0, if (isActive) R.style.DastyabButtonPrimary else R.style.DastyabButton).apply {
                val marker = if (isActive) "● ACTIVE · " else ""
                text = "$marker${layer.name}\n${layer.subtitle()}"
                setOnClickListener { showLayerActionSheet(layer) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            layersListContainer.addView(btn, params)
        }
    }

    private fun showLayerActionSheet(layer: VpnLayerStore.VpnLayer) {
        val options = arrayOf(
            "1. Activate & Connect this VPN Layer",
            "2. Edit Layer Settings",
            "3. Share Layer as Offline QR Code",
            "4. Clone Layer",
            "5. Delete Layer"
        )
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(layer.name)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            VpnLayerStore.activateLayer(this, layer, restartIfConnected = false)
                            refreshLayersUi()
                            startVpnWithConsent()
                        }
                        1 -> showLayerEditorDialog(layer)
                        2 -> {
                            val payload = layer.configUri.ifBlank { layer.dohUrl }
                            QrShareDialog.show(this, "QR · ${layer.name}", payload)
                        }
                        3 -> {
                            val copy = layer.copy(
                                id = java.util.UUID.randomUUID().toString().take(8),
                                name = "${layer.name} (Copy)"
                            )
                            VpnLayerStore.addOrUpdate(this, copy)
                            refreshLayersUi()
                            Toast.makeText(this, "Cloned layer!", Toast.LENGTH_SHORT).show()
                        }
                        4 -> {
                            VpnLayerStore.delete(this, layer.id)
                            refreshLayersUi()
                            Toast.makeText(this, "Deleted layer.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun showLayerEditorDialog(existing: VpnLayerStore.VpnLayer?) {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val etName = EditText(this).apply {
            hint = "Layer Name (e.g. Fast Cloudflare VLESS)"
            setText(existing?.name ?: "")
        }
        val modes = TunnelConfig.Mode.values()
        val spinnerMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@VpnLayersActivity,
                android.R.layout.simple_spinner_item,
                modes.map { it.label }
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val idx = modes.indexOf(existing?.mode ?: TunnelConfig.Mode.SMART).coerceAtLeast(0)
            setSelection(idx)
        }
        val etUri = EditText(this).apply {
            hint = "Optional Config URI (vless://, trojan://, vmess://, hy2://)"
            setText(existing?.configUri ?: "")
            setSingleLine(false)
            maxLines = 3
        }
        val etDns = EditText(this).apply {
            hint = "Primary DNS (default 1.1.1.1)"
            setText(existing?.dnsPrimary ?: TunnelConfig.DEFAULT_DNS_PRIMARY)
        }
        val etDoh = EditText(this).apply {
            hint = "DoH URL (default https://1.1.1.1/dns-query)"
            setText(existing?.dohUrl ?: TunnelConfig.DEFAULT_DOH_URL)
        }

        container.addView(etName)
        container.addView(spinnerMode)
        container.addView(etUri)
        container.addView(etDns)
        container.addView(etDoh)

        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(if (existing == null) "Add New VPN Layer" else "Edit VPN Layer")
                .setView(container)
                .setPositiveButton("Save & Activate") { _, _ ->
                    val name = etName.text.toString().trim().ifEmpty { "Custom VPN Layer" }
                    val mode = modes[spinnerMode.selectedItemPosition.coerceIn(0, modes.lastIndex)]
                    val layer = (existing ?: VpnLayerStore.VpnLayer(name = name, mode = mode)).copy(
                        name = name,
                        mode = mode,
                        configUri = etUri.text.toString().trim(),
                        dnsPrimary = TunnelConfig.sanitizeDns(etDns.text.toString(), TunnelConfig.DEFAULT_DNS_PRIMARY),
                        dohUrl = TunnelConfig.sanitizeDohUrl(etDoh.text.toString())
                    )
                    VpnLayerStore.addOrUpdate(this, layer)
                    VpnLayerStore.activateLayer(this, layer, restartIfConnected = true)
                    refreshLayersUi()
                    tvLayersLog.text = "Saved & activated layer: ${layer.name}\n${layer.subtitle()}"
                    Toast.makeText(this, "Layer saved & activated!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun benchmarkAndSwitchBestLayer() {
        progress.visibility = View.VISIBLE
        tvLayersLog.text = "Benchmarking all saved VPN layers in parallel…\n"
        val layers = VpnLayerStore.loadAll(this)
        thread(isDaemon = true, name = "dastyab-layer-bench") {
            val results = VpnLayerStore.benchmarkLayers(layers)
            UiSafety.safePostUi(this, handler) {
                progress.visibility = View.GONE
                val sb = StringBuilder("=== VPN LAYER BENCHMARK ===\n")
                results.forEach { r ->
                    val status = if (r.reachable) "${r.latencyMs} ms" else "UNREACHABLE"
                    sb.append("• ${r.layer.name}: $status\n")
                }
                val best = results.firstOrNull { it.reachable }
                if (best != null) {
                    VpnLayerStore.activateLayer(this, best.layer, restartIfConnected = true)
                    refreshLayersUi()
                    sb.append("\n✓ Switched to fastest layer: ${best.layer.name} (${best.latencyMs} ms)")
                    Toast.makeText(this, "Switched to ${best.layer.name} (${best.latencyMs}ms)", Toast.LENGTH_SHORT).show()
                } else {
                    sb.append("\n✗ No layers responded — try DNS Blackout VPN or IP Scout.")
                }
                tvLayersLog.text = sb.toString()
            }
        }
    }

    private fun exportLayersToClipboard() {
        val json = VpnLayerStore.serializeLayers(VpnLayerStore.loadAll(this))
        if (UiSafety.copyTextSafely(this, "DastYab VPN Layers", json)) {
            Toast.makeText(this, "Exported all VPN layers JSON to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImportLayersDialog() {
        val input = EditText(this).apply {
            hint = "Paste exported DastYab VPN Layers JSON here"
            setSingleLine(false)
            maxLines = 5
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Import VPN Layers JSON")
                .setView(input)
                .setPositiveButton("Import") { _, _ ->
                    val imported = VpnLayerStore.deserializeLayers(input.text.toString())
                    if (imported.isEmpty()) {
                        Toast.makeText(this, "Invalid JSON or no layers found.", Toast.LENGTH_SHORT).show()
                    } else {
                        VpnLayerStore.saveAll(this, imported)
                        refreshLayersUi()
                        Toast.makeText(this, "Imported ${imported.size} VPN layer(s)!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun refreshSourcesUi() {
        val customUrls = HarvestSourceStore.getCustomGithubUrls(this)
        val customChannels = HarvestSourceStore.getCustomTelegramChannels(this)
        val density = resources.displayMetrics.density

        tvGithubCount.text = "GitHub Subscription URLs (${customUrls.size} custom + ${ConfigHarvester.DEFAULT_GITHUB_FEEDS.size} built-in)"
        githubUrlsContainer.removeAllViews()
        if (customUrls.isEmpty()) {
            githubUrlsContainer.addView(TextView(this).apply {
                text = "Using built-in GitHub feeds + jsDelivr mirrors. Tap below to add your own URLs."
                textSize = 12f
                setTextColor(getColor(R.color.colorTextSecondary))
            })
        } else {
            for (url in customUrls) {
                val btn = Button(this, null, 0, R.style.DastyabChip).apply {
                    text = "✕  ${url.removePrefix("https://").take(46)}"
                    setOnClickListener {
                        HarvestSourceStore.removeGithubUrl(this@VpnLayersActivity, url)
                        refreshSourcesUi()
                        Toast.makeText(this@VpnLayersActivity, "Removed GitHub URL", Toast.LENGTH_SHORT).show()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
                githubUrlsContainer.addView(btn, lp)
            }
        }

        tvTelegramCount.text = "Telegram Public Channels (${customChannels.size} custom + ${ConfigHarvester.DEFAULT_TELEGRAM_CHANNELS.size} built-in)"
        telegramChannelsContainer.removeAllViews()
        if (customChannels.isEmpty()) {
            telegramChannelsContainer.addView(TextView(this).apply {
                text = "Built-in: ${ConfigHarvester.DEFAULT_TELEGRAM_CHANNELS.joinToString { "@$it" }}. Tap below to add more."
                textSize = 12f
                setTextColor(getColor(R.color.colorTextSecondary))
            })
        } else {
            for (ch in customChannels) {
                val btn = Button(this, null, 0, R.style.DastyabChip).apply {
                    text = "✕  @$ch  (t.me/s/$ch)"
                    setOnClickListener {
                        HarvestSourceStore.removeTelegramChannel(this@VpnLayersActivity, ch)
                        refreshSourcesUi()
                        Toast.makeText(this@VpnLayersActivity, "Removed @$ch", Toast.LENGTH_SHORT).show()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
                telegramChannelsContainer.addView(btn, lp)
            }
        }
    }

    private fun showAddGithubUrlDialog() {
        val input = EditText(this).apply {
            hint = "Paste one or more GitHub / subscription URLs (one per line)\ngithub.com/blob links auto-convert to raw!"
            setSingleLine(false)
            maxLines = 5
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Add GitHub / Subscription URLs")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val added = HarvestSourceStore.addGithubUrls(this, input.text.toString())
                    refreshSourcesUi()
                    Toast.makeText(
                        this,
                        if (added > 0) "Added $added GitHub URL(s)!" else "No valid http(s) URLs found.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun showAddTelegramChannelDialog() {
        val input = EditText(this).apply {
            hint = "Paste @channel, t.me/channel, or one per line"
            setSingleLine(false)
            maxLines = 5
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Add Telegram Public Channels")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val added = HarvestSourceStore.addTelegramChannels(this, input.text.toString())
                    refreshSourcesUi()
                    Toast.makeText(
                        this,
                        if (added > 0) "Added $added Telegram channel(s)!" else "No valid channel usernames found.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun benchmarkHarvestedPool() {
        val pool = TunnelConfig.loadHarvestedConfigs(this)
        if (pool.isEmpty()) {
            Toast.makeText(this, "Harvested pool is empty — run Smart Harvest first!", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        tvLayersLog.text = "Testing up to 25 harvested configs in parallel…\n"
        thread(isDaemon = true, name = "dastyab-pool-bench") {
            val working = HarvestSourceStore.benchmarkHarvestedConfigs(pool, maxToTest = 25)
            UiSafety.safePostUi(this, handler) {
                progress.visibility = View.GONE
                if (working.isEmpty()) {
                    tvLayersLog.text = "No harvested configs responded within 1.8s.\nTip: Run Smart Harvest again or activate DNS Blackout VPN."
                    return@safePostUi
                }
                val sb = StringBuilder("Found ${working.size} live configs (fastest first):\n")
                working.take(10).forEach { r ->
                    sb.append("• [${r.protocol.uppercase()}] ${r.host}:${r.port} (${r.latencyMs}ms)\n")
                }
                tvLayersLog.text = sb.toString()

                val items = working.take(10).map {
                    "[${it.protocol.uppercase()}] ${it.host}:${it.port} · ${it.latencyMs}ms"
                }.toTypedArray()
                UiSafety.safeShowDialog(
                    this,
                    AlertDialog.Builder(this)
                        .setTitle("Promote Fastest Config to VPN Layer")
                        .setItems(items) { _, which ->
                            val picked = working[which]
                            val newLayer = VpnLayerStore.VpnLayer(
                                name = "Fast ${picked.protocol.uppercase()} (${picked.host})",
                                mode = TunnelConfig.Mode.SMART,
                                configUri = picked.uri
                            )
                            VpnLayerStore.addOrUpdate(this, newLayer)
                            VpnLayerStore.activateLayer(this, newLayer, restartIfConnected = true)
                            refreshLayersUi()
                            Toast.makeText(this, "Promoted & activated ${newLayer.name}!", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                )
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startVpnWithConsent() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(prep, REQ_VPN_LAYER_START)
            } catch (_: Exception) {
                Toast.makeText(this, "VPN consent dialog unavailable.", Toast.LENGTH_SHORT).show()
            }
        } else {
            DastyabVpnService.start(this)
            Toast.makeText(this, "VPN Layer connected!", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_LAYER_START && resultCode == RESULT_OK) {
            DastyabVpnService.start(this)
            Toast.makeText(this, "VPN Layer connected!", Toast.LENGTH_SHORT).show()
        }
    }
}
