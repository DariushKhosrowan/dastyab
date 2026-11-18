package com.dastyab.app

import android.app.Activity
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
 * TunnelSettingsActivity — full customization screen for DastYab VPN:
 *  • Tunnel Mode: Smart Tunnel, Global VPN, Split Include, Split Exclude
 *  • VLESS upstream link import & live validation
 *  • Custom Primary & Secondary DNS servers
 *  • Custom MTU (1280–1500)
 *  • LAN bypass & Auto-VPN for AI Studio / Google Flow toggles
 *  • Interactive Split-Tunnel App Picker (select installed apps)
 */
class TunnelSettingsActivity : Activity() {

    private lateinit var spinnerMode: Spinner
    private lateinit var tvModeHint: TextView
    private lateinit var etVlessUri: EditText
    private lateinit var tvVlessStatus: TextView
    private lateinit var etDnsPrimary: EditText
    private lateinit var etDnsSecondary: EditText
    private lateinit var etMtu: EditText
    private lateinit var cbBypassLan: CheckBox
    private lateinit var cbAutoVpnGoogle: CheckBox
    private lateinit var tvSplitSummary: TextView

    private val modes = TunnelConfig.Mode.values().toList()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunnel_settings)

        spinnerMode = findViewById(R.id.spinnerTunnelMode)
        tvModeHint = findViewById(R.id.tvTunnelModeHint)
        etVlessUri = findViewById(R.id.etVlessUri)
        tvVlessStatus = findViewById(R.id.tvVlessStatus)
        etDnsPrimary = findViewById(R.id.etDnsPrimary)
        etDnsSecondary = findViewById(R.id.etDnsSecondary)
        etMtu = findViewById(R.id.etMtu)
        cbBypassLan = findViewById(R.id.cbBypassLan)
        cbAutoVpnGoogle = findViewById(R.id.cbAutoVpnGoogle)
        tvSplitSummary = findViewById(R.id.tvSplitSummary)

        val settings = TunnelConfig.load(this)
        selectedPackages.clear()
        selectedPackages.addAll(settings.splitPackages)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            modes.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMode.adapter = adapter
        spinnerMode.setSelection(modes.indexOf(settings.mode).coerceAtLeast(0))
        tvModeHint.text = settings.mode.subtitle

        spinnerMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                tvModeHint.text = modes[position].subtitle
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        etVlessUri.setText(settings.vlessUri)
        etDnsPrimary.setText(settings.dnsPrimary)
        etDnsSecondary.setText(settings.dnsSecondary)
        etMtu.setText(settings.mtu.toString())
        cbBypassLan.isChecked = settings.bypassLan
        cbAutoVpnGoogle.isChecked = settings.autoVpnForGoogle

        updateVlessPreview(settings.vlessUri)
        updateSplitSummary()

        findViewById<Button>(R.id.btnPasteVless).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
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
            saveAndApply()
        }
    }

    private fun updateVlessPreview(uri: String) {
        if (uri.isBlank()) {
            tvVlessStatus.text = getString(R.string.tunnel_vless_none)
            return
        }
        val parsed = TunnelConfig.parseVlessUri(uri)
        tvVlessStatus.text = if (parsed != null) {
            "✓ Parsed: ${parsed.summary()} · SNI: ${parsed.sni}"
        } else {
            "✗ Invalid vless:// link format"
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
            .show()
    }

    private fun saveAndApply() {
        val vless = etVlessUri.text.toString().trim()
        if (vless.isNotEmpty() && TunnelConfig.parseVlessUri(vless) == null) {
            updateVlessPreview(vless)
            Toast.makeText(this, getString(R.string.tunnel_vless_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        val mtuVal = etMtu.text.toString().trim().toIntOrNull() ?: TunnelConfig.DEFAULT_MTU
        val settings = TunnelConfig.Settings(
            mode = modes[spinnerMode.selectedItemPosition.coerceIn(modes.indices)],
            vlessUri = vless,
            dnsPrimary = TunnelConfig.sanitizeDns(etDnsPrimary.text.toString(), TunnelConfig.DEFAULT_DNS_PRIMARY),
            dnsSecondary = TunnelConfig.sanitizeDns(etDnsSecondary.text.toString(), TunnelConfig.DEFAULT_DNS_SECONDARY),
            mtu = TunnelConfig.clampMtu(mtuVal),
            bypassLan = cbBypassLan.isChecked,
            autoVpnForGoogle = cbAutoVpnGoogle.isChecked,
            splitPackages = TunnelConfig.normalizePackages(selectedPackages)
        )
        TunnelConfig.save(this, settings)
        updateVlessPreview(vless)

        // If VPN is already connected, restart with new settings immediately.
        if (DastyabVpnService.isConnected()) {
            DastyabVpnService.start(this)
        }

        Toast.makeText(this, getString(R.string.tunnel_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
