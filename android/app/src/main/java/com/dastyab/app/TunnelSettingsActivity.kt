package com.dastyab.app

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * TunnelSettingsActivity — full customization screen for DastYab VPN & AI Studio/Flow Proxy:
 *  • Tunnel Mode: Smart Tunnel, Global VPN, Split Include, Split Exclude, DNS Blackout
 *  • AI Studio & Flow Geo-Bypass Mode: Auto Smart, External VPN (Lantern), Internal App Proxy,
 *    DastYab VPN + Proxy Chain, Local HTTP+SOCKS5 Proxy Port (for v2rayNG & multi-layering)
 *  • VLESS upstream link import & live validation
 *  • Custom Primary & Secondary DNS servers + MTU (1280–1500)
 *  • Interactive Split-Tunnel App Picker
 */
class TunnelSettingsActivity : DastyabActivity() {

    private lateinit var spinnerMode: Spinner
    private lateinit var tvModeHint: TextView
    private lateinit var spinnerGoogleAccessMode: Spinner
    private lateinit var tvGoogleAccessModeHint: TextView
    private lateinit var spinnerUpstreamScheme: Spinner
    private lateinit var etUpstreamHost: EditText
    private lateinit var etUpstreamPort: EditText
    private lateinit var etLocalBridgePort: EditText
    private lateinit var cbAutoStartBridge: CheckBox
    private lateinit var btnToggleLocalBridge: Button
    private lateinit var etVlessUri: EditText
    private lateinit var tvVlessStatus: TextView
    private lateinit var etDnsPrimary: EditText
    private lateinit var etDnsSecondary: EditText
    private lateinit var etMtu: EditText
    private lateinit var cbBypassLan: CheckBox
    private lateinit var cbAutoVpnGoogle: CheckBox
    private lateinit var cbWatchdogEnabled: CheckBox
    private lateinit var cbAutoConnectBoot: CheckBox
    private lateinit var tvSplitSummary: TextView

    private val modes = TunnelConfig.Mode.values().toList()
    private val accessModes = TunnelConfig.GoogleAccessMode.values().toList()
    private val proxySchemes = TunnelConfig.ProxyScheme.values().toList()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunnel_settings)

        spinnerMode = findViewById(R.id.spinnerTunnelMode)
        tvModeHint = findViewById(R.id.tvTunnelModeHint)
        spinnerGoogleAccessMode = findViewById(R.id.spinnerGoogleAccessMode)
        tvGoogleAccessModeHint = findViewById(R.id.tvGoogleAccessModeHint)
        spinnerUpstreamScheme = findViewById(R.id.spinnerUpstreamScheme)
        etUpstreamHost = findViewById(R.id.etUpstreamHost)
        etUpstreamPort = findViewById(R.id.etUpstreamPort)
        etLocalBridgePort = findViewById(R.id.etLocalBridgePort)
        cbAutoStartBridge = findViewById(R.id.cbAutoStartBridge)
        btnToggleLocalBridge = findViewById(R.id.btnToggleLocalBridge)
        etVlessUri = findViewById(R.id.etVlessUri)
        tvVlessStatus = findViewById(R.id.tvVlessStatus)
        etDnsPrimary = findViewById(R.id.etDnsPrimary)
        etDnsSecondary = findViewById(R.id.etDnsSecondary)
        etMtu = findViewById(R.id.etMtu)
        cbBypassLan = findViewById(R.id.cbBypassLan)
        cbAutoVpnGoogle = findViewById(R.id.cbAutoVpnGoogle)
        cbWatchdogEnabled = findViewById(R.id.cbWatchdogEnabled)
        cbAutoConnectBoot = findViewById(R.id.cbAutoConnectBoot)
        tvSplitSummary = findViewById(R.id.tvSplitSummary)

        val settings = TunnelConfig.load(this)
        selectedPackages.clear()
        selectedPackages.addAll(settings.splitPackages)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            modes.map { it.localizedLabel(this) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMode.adapter = adapter
        spinnerMode.setSelection(modes.indexOf(settings.mode).coerceAtLeast(0))
        tvModeHint.text = settings.mode.localizedSubtitle(this)

        spinnerMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                tvModeHint.text = modes[position].localizedSubtitle(this@TunnelSettingsActivity)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val accessAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            accessModes.map { it.localizedLabel(this) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerGoogleAccessMode.adapter = accessAdapter
        spinnerGoogleAccessMode.setSelection(accessModes.indexOf(settings.googleAccessMode).coerceAtLeast(0))
        tvGoogleAccessModeHint.text = settings.googleAccessMode.localizedSubtitle(this)
        spinnerGoogleAccessMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                tvGoogleAccessModeHint.text = accessModes[position].localizedSubtitle(this@TunnelSettingsActivity)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val schemeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            proxySchemes.map { it.localizedLabel(this) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerUpstreamScheme.adapter = schemeAdapter
        spinnerUpstreamScheme.setSelection(proxySchemes.indexOf(settings.upstreamProxyScheme).coerceAtLeast(0))

        etUpstreamHost.setText(settings.upstreamProxyHost)
        etUpstreamPort.setText(settings.upstreamProxyPort.toString())
        etLocalBridgePort.setText(settings.localBridgePort.toString())
        cbAutoStartBridge.isChecked = settings.autoStartLocalBridge

        findViewById<Button>(R.id.btnPresetV2rayHttp).setOnClickListener {
            applyPreset(TunnelConfig.ProxyScheme.HTTP, "127.0.0.1", 10809)
        }
        findViewById<Button>(R.id.btnPresetV2raySocks).setOnClickListener {
            applyPreset(TunnelConfig.ProxyScheme.SOCKS5, "127.0.0.1", 10808)
        }
        findViewById<Button>(R.id.btnPresetNekobox).setOnClickListener {
            applyPreset(TunnelConfig.ProxyScheme.SOCKS5, "127.0.0.1", 2080)
        }

        refreshBridgeToggleButton()
        btnToggleLocalBridge.setOnClickListener {
            if (LocalProxyService.isRunning()) {
                LocalProxyService.stop(this)
                Toast.makeText(this, getString(R.string.tunnel_local_bridge_stopped), Toast.LENGTH_SHORT).show()
            } else {
                saveSettingsFromUi(finishAfter = false, silentToast = true)
                LocalProxyService.startSafely(this)
                Toast.makeText(
                    this,
                    getString(R.string.tunnel_local_bridge_started, etLocalBridgePort.text.toString().trim()),
                    Toast.LENGTH_LONG
                ).show()
            }
            btnToggleLocalBridge.postDelayed({ refreshBridgeToggleButton() }, 250)
        }

        findViewById<Button>(R.id.btnCopyV2rayNgSocks).setOnClickListener {
            val port = TunnelConfig.clampPort(
                etLocalBridgePort.text.toString().trim().toIntOrNull() ?: TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT,
                TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT,
                TunnelConfig.MIN_LOCAL_PORT
            )
            val uri = GeoBypassEngine.formatV2rayNgSocksUri("127.0.0.1", port)
            UiSafety.copyTextSafely(this, "DastYab Local Proxy URI", uri)
            Toast.makeText(this, getString(R.string.tunnel_copied_prefix, uri), Toast.LENGTH_LONG).show()
        }

        etVlessUri.setText(settings.vlessUri)
        etDnsPrimary.setText(settings.dnsPrimary)
        etDnsSecondary.setText(settings.dnsSecondary)
        etMtu.setText(settings.mtu.toString())
        cbBypassLan.isChecked = settings.bypassLan
        cbAutoVpnGoogle.isChecked = settings.autoVpnForGoogle
        cbWatchdogEnabled.isChecked = settings.watchdogEnabled
        cbAutoConnectBoot.isChecked = settings.autoConnectOnBoot

        updateVlessPreview(settings.vlessUri)
        updateSplitSummary()

        findViewById<Button>(R.id.btnDomainDnsRules).setOnClickListener {
            DomainDnsDialog.show(this)
        }

        findViewById<Button>(R.id.btnPasteVless).setOnClickListener {
            val text = runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            }.getOrDefault("")
            if (text.isEmpty()) {
                Toast.makeText(this, getString(R.string.tunnel_clipboard_empty), Toast.LENGTH_SHORT).show()
            } else {
                etVlessUri.setText(text)
                updateVlessPreview(text)
            }
        }

        findViewById<Button>(R.id.btnPickApps).setOnClickListener {
            openSplitAppPicker()
        }

        findViewById<Button>(R.id.btnSaveTunnel).setOnClickListener {
            saveSettingsFromUi(finishAfter = true, silentToast = false)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBridgeToggleButton()
    }

    private fun applyPreset(scheme: TunnelConfig.ProxyScheme, host: String, port: Int) {
        spinnerGoogleAccessMode.setSelection(accessModes.indexOf(TunnelConfig.GoogleAccessMode.IN_APP_PROXY).coerceAtLeast(0))
        spinnerUpstreamScheme.setSelection(proxySchemes.indexOf(scheme).coerceAtLeast(0))
        etUpstreamHost.setText(host)
        etUpstreamPort.setText(port.toString())
        Toast.makeText(this, getString(R.string.tunnel_preset_loaded, scheme.name, host, port), Toast.LENGTH_SHORT).show()
    }

    private fun refreshBridgeToggleButton() {
        val running = LocalProxyService.isRunning()
        val activePort = LocalProxyService.activePort()
        btnToggleLocalBridge.text = if (running && activePort != null) {
            "${getString(R.string.tunnel_btn_bridge_toggle_stop)} (:$activePort ✓)"
        } else {
            getString(R.string.tunnel_btn_bridge_toggle_start)
        }
    }

    private fun updateVlessPreview(uri: String) {
        if (uri.isBlank()) {
            tvVlessStatus.text = getString(R.string.tunnel_vless_none)
            return
        }
        val parsed = TunnelConfig.parseVlessUri(uri)
        tvVlessStatus.text = if (parsed != null) {
            getString(R.string.tunnel_vless_parsed, "${parsed.summary()} · SNI: ${parsed.sni}")
        } else {
            getString(R.string.tunnel_vless_invalid)
        }
    }

    private fun updateSplitSummary() {
        tvSplitSummary.text = if (selectedPackages.isEmpty()) {
            getString(R.string.tunnel_split_none)
        } else {
            getString(R.string.tunnel_split_count, selectedPackages.size)
        }
    }

    private fun openSplitAppPicker() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString() ?: pkg
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, getString(R.string.tunnel_no_apps_found), Toast.LENGTH_SHORT).show()
            return
        }

        val labels = apps.map { "${it.first} (${it.second})" }.toTypedArray()
        val checked = BooleanArray(apps.size) { i -> selectedPackages.contains(apps[i].second) }

        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.tunnel_pick_apps_title))
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val pkg = apps[which].second
                    if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                }
                .setPositiveButton(android.R.string.ok) { _, _ -> updateSplitSummary() }
                .setNeutralButton(getString(R.string.tunnel_clear_apps)) { _, _ ->
                    selectedPackages.clear()
                    updateSplitSummary()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun saveSettingsFromUi(finishAfter: Boolean, silentToast: Boolean) {
        val vless = etVlessUri.text.toString().trim()
        if (vless.isNotEmpty() && TunnelConfig.parseVlessUri(vless) == null) {
            updateVlessPreview(vless)
            Toast.makeText(this, getString(R.string.tunnel_vless_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        val mtuVal = etMtu.text.toString().trim().toIntOrNull() ?: TunnelConfig.DEFAULT_MTU
        val upPort = etUpstreamPort.text.toString().trim().toIntOrNull() ?: TunnelConfig.DEFAULT_UPSTREAM_PROXY_PORT
        val bridgePort = etLocalBridgePort.text.toString().trim().toIntOrNull() ?: TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT

        val settings = TunnelConfig.Settings(
            mode = modes[spinnerMode.selectedItemPosition.coerceIn(modes.indices)],
            vlessUri = vless,
            dnsPrimary = TunnelConfig.sanitizeDns(etDnsPrimary.text.toString(), TunnelConfig.DEFAULT_DNS_PRIMARY),
            dnsSecondary = TunnelConfig.sanitizeDns(etDnsSecondary.text.toString(), TunnelConfig.DEFAULT_DNS_SECONDARY),
            mtu = TunnelConfig.clampMtu(mtuVal),
            bypassLan = cbBypassLan.isChecked,
            autoVpnForGoogle = cbAutoVpnGoogle.isChecked,
            splitPackages = TunnelConfig.normalizePackages(selectedPackages),
            autoConnectOnBoot = cbAutoConnectBoot.isChecked,
            watchdogEnabled = cbWatchdogEnabled.isChecked,
            googleAccessMode = accessModes[spinnerGoogleAccessMode.selectedItemPosition.coerceIn(accessModes.indices)],
            upstreamProxyScheme = proxySchemes[spinnerUpstreamScheme.selectedItemPosition.coerceIn(proxySchemes.indices)],
            upstreamProxyHost = TunnelConfig.sanitizeProxyHost(etUpstreamHost.text.toString()),
            upstreamProxyPort = TunnelConfig.clampPort(upPort, TunnelConfig.DEFAULT_UPSTREAM_PROXY_PORT, min = 1),
            localBridgePort = TunnelConfig.clampPort(bridgePort, TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT, min = TunnelConfig.MIN_LOCAL_PORT),
            autoStartLocalBridge = cbAutoStartBridge.isChecked
        )
        TunnelConfig.save(this, settings)
        updateVlessPreview(vless)

        if (settings.autoStartLocalBridge) {
            LocalProxyService.startSafely(this)
        } else if (LocalProxyService.isRunning()) {
            // Restart running bridge to pick up updated upstream/port
            LocalProxyService.startSafely(this)
        }

        // If VPN is already connected, restart with new settings immediately.
        if (DastyabVpnService.isConnected()) {
            DastyabVpnService.start(this)
        }

        if (!silentToast) {
            Toast.makeText(this, getString(R.string.tunnel_saved), Toast.LENGTH_SHORT).show()
        }
        if (finishAfter) {
            finish()
        }
    }
}
