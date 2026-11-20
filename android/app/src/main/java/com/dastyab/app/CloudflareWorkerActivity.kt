package com.dastyab.app

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * CloudflareWorkerActivity — Complete Cloudflare Worker Deploy, Clean-IP Discovery,
 * and Interactive Config Studio for Android (full Linux `dastyab` CLI parity).
 *
 * Reads and writes the shared `~/.dastyab/profiles/<id>/` directory directly under
 * `filesDir`, so every token, deployed worker base link, clean IP, and config record
 * stays 100% synchronized with `libdastyab.so` and Terminal Mode.
 */
class CloudflareWorkerActivity : DastyabActivity() {

    private companion object {
        const val REQ_VPN_PREPARE = 4101
        const val PROCESS_TIMEOUT_MS = 240_000L
        const val MAX_CONSOLE_CHARS = 40_000
    }

    private lateinit var binary: File
    private val handler = Handler(Looper.getMainLooper())
    private val activeProcess = AtomicReference<Process?>(null)
    private val cliCancelled = AtomicBoolean(false)
    private val cliRunning = AtomicBoolean(false)

    private var activeProfileId: String = "default"
    private var profileList: List<WorkerConfigStudio.ProfileMeta> = emptyList()
    private var currentSummary: WorkerConfigStudio.ProfileSummary? = null
    private var selectedPreset: WorkerConfigStudio.WorkerPreset = WorkerConfigStudio.WorkerPreset.STANDARD
    private var selectedExportFormat: WorkerConfigStudio.ExportFormat = WorkerConfigStudio.ExportFormat.SHARE_LINK
    private var originalWorkerHostForReset: String = ""
    private var suppressPreviewWatcher: Boolean = false
    private var suppressSpinnerSelection: Boolean = false

    // Views
    private lateinit var spProfile: Spinner
    private lateinit var cfProgress: ProgressBar
    private lateinit var tvTokenStatus: TextView
    private lateinit var etTokenLabel: EditText
    private lateinit var etApiToken: EditText
    private lateinit var btnModeDeployNew: Button
    private lateinit var btnModeExistingWorker: Button
    private lateinit var panelDeployNew: LinearLayout
    private lateinit var panelExistingWorker: LinearLayout
    private lateinit var llAccountChips: LinearLayout
    private lateinit var etDeployAccountId: EditText
    private lateinit var etDeployWorkerName: EditText
    private lateinit var btnPresetStandard: Button
    private lateinit var btnPresetGaming: Button
    private lateinit var btnPresetMaxCompat: Button
    private lateinit var tvPresetDesc: TextView
    private lateinit var etExistingWorkerHost: EditText
    private lateinit var etExistingWorkerUuid: EditText
    private lateinit var etExistingWorkerPath: EditText
    private lateinit var etExistingWorkerPort: EditText

    private lateinit var tvCleanIpStatus: TextView
    private lateinit var etCustomCleanIp: EditText
    private lateinit var llCleanIpChips: LinearLayout

    private lateinit var tvEditorBaseStatus: TextView
    private lateinit var llHistoryConfigChips: LinearLayout
    private lateinit var etEditAddress: EditText
    private lateinit var etEditPort: EditText
    private lateinit var etEditSni: EditText
    private lateinit var etEditHost: EditText
    private lateinit var etEditPath: EditText
    private lateinit var etEditFp: EditText
    private lateinit var etEditUuid: EditText
    private lateinit var etEditRemark: EditText

    private lateinit var btnFmtLink: Button
    private lateinit var btnFmtV2rayN: Button
    private lateinit var btnFmtSingBox: Button
    private lateinit var btnFmtClash: Button
    private lateinit var tvExportPreview: TextView

    private lateinit var llLinuxCliChips: LinearLayout
    private lateinit var etStudioCliArgs: EditText
    private lateinit var btnStudioRunCli: Button
    private lateinit var btnStudioCancelCli: Button
    private lateinit var tvStudioConsole: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloudflare_worker)
        binary = File(applicationInfo.nativeLibraryDir, "libdastyab.so")

        bindViews()
        setupListeners()
        buildLinuxCliChips()
        selectPreset(WorkerConfigStudio.WorkerPreset.STANDARD)
        selectWorkerMode(deployNew = true)
        selectExportFormat(WorkerConfigStudio.ExportFormat.SHARE_LINK)
        reloadProfileState(loadInitialEditorConfig = true)
    }

    private fun bindViews() {
        spProfile = findViewById(R.id.spStudioProfile)
        cfProgress = findViewById(R.id.cfProgress)
        tvTokenStatus = findViewById(R.id.tvTokenStatus)
        etTokenLabel = findViewById(R.id.etTokenLabel)
        etApiToken = findViewById(R.id.etApiToken)

        btnModeDeployNew = findViewById(R.id.btnModeDeployNew)
        btnModeExistingWorker = findViewById(R.id.btnModeExistingWorker)
        panelDeployNew = findViewById(R.id.panelDeployNew)
        panelExistingWorker = findViewById(R.id.panelExistingWorker)
        llAccountChips = findViewById(R.id.llAccountChips)
        etDeployAccountId = findViewById(R.id.etDeployAccountId)
        etDeployWorkerName = findViewById(R.id.etDeployWorkerName)
        btnPresetStandard = findViewById(R.id.btnPresetStandard)
        btnPresetGaming = findViewById(R.id.btnPresetGaming)
        btnPresetMaxCompat = findViewById(R.id.btnPresetMaxCompat)
        tvPresetDesc = findViewById(R.id.tvPresetDesc)

        etExistingWorkerHost = findViewById(R.id.etExistingWorkerHost)
        etExistingWorkerUuid = findViewById(R.id.etExistingWorkerUuid)
        etExistingWorkerPath = findViewById(R.id.etExistingWorkerPath)
        etExistingWorkerPort = findViewById(R.id.etExistingWorkerPort)

        tvCleanIpStatus = findViewById(R.id.tvCleanIpStatus)
        etCustomCleanIp = findViewById(R.id.etCustomCleanIp)
        llCleanIpChips = findViewById(R.id.llCleanIpChips)

        tvEditorBaseStatus = findViewById(R.id.tvEditorBaseStatus)
        llHistoryConfigChips = findViewById(R.id.llHistoryConfigChips)
        etEditAddress = findViewById(R.id.etEditAddress)
        etEditPort = findViewById(R.id.etEditPort)
        etEditSni = findViewById(R.id.etEditSni)
        etEditHost = findViewById(R.id.etEditHost)
        etEditPath = findViewById(R.id.etEditPath)
        etEditFp = findViewById(R.id.etEditFp)
        etEditUuid = findViewById(R.id.etEditUuid)
        etEditRemark = findViewById(R.id.etEditRemark)

        btnFmtLink = findViewById(R.id.btnFmtLink)
        btnFmtV2rayN = findViewById(R.id.btnFmtV2rayN)
        btnFmtSingBox = findViewById(R.id.btnFmtSingBox)
        btnFmtClash = findViewById(R.id.btnFmtClash)
        tvExportPreview = findViewById(R.id.tvExportPreview)

        llLinuxCliChips = findViewById(R.id.llLinuxCliChips)
        etStudioCliArgs = findViewById(R.id.etStudioCliArgs)
        btnStudioRunCli = findViewById(R.id.btnStudioRunCli)
        btnStudioCancelCli = findViewById(R.id.btnStudioCancelCli)
        tvStudioConsole = findViewById(R.id.tvStudioConsole)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnStudioBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnStudioRefresh).setOnClickListener {
            reloadProfileState(loadInitialEditorConfig = false)
            Toast.makeText(this, getString(R.string.cf_toast_reloaded), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnStudioNewProfile).setOnClickListener { showCreateProfileDialog() }

        spProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerSelection) return
                val picked = profileList.getOrNull(position) ?: return
                if (picked.id != activeProfileId) {
                    activeProfileId = picked.id
                    reloadProfileState(loadInitialEditorConfig = true)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Token & Account Actions
        findViewById<Button>(R.id.btnPasteToken).setOnClickListener {
            val clip = readClipboardText()
            if (clip.isBlank()) {
                Toast.makeText(this, getString(R.string.tunnel_clipboard_empty), Toast.LENGTH_SHORT).show()
            } else {
                etApiToken.setText(clip.trim())
            }
        }
        findViewById<Button>(R.id.btnSaveVerifyToken).setOnClickListener { saveAndVerifyToken() }
        findViewById<Button>(R.id.btnListAccounts).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildAccountsArgs(activeProfileId)) {
                reloadProfileState(loadInitialEditorConfig = false)
            }
        }
        findViewById<Button>(R.id.btnCfVerifyOnly).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildVerifyArgs(activeProfileId)) {
                reloadProfileState(loadInitialEditorConfig = false)
            }
        }
        findViewById<Button>(R.id.btnCfTokenList).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "token-list"))
        }
        findViewById<Button>(R.id.btnCfZones).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "zones"))
        }
        findViewById<Button>(R.id.btnCfAnalytics).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "analytics"))
        }

        // Worker Deploy / Existing Worker Switcher
        btnModeDeployNew.setOnClickListener { selectWorkerMode(deployNew = true) }
        btnModeExistingWorker.setOnClickListener { selectWorkerMode(deployNew = false) }
        btnPresetStandard.setOnClickListener { selectPreset(WorkerConfigStudio.WorkerPreset.STANDARD) }
        btnPresetGaming.setOnClickListener { selectPreset(WorkerConfigStudio.WorkerPreset.GAMING) }
        btnPresetMaxCompat.setOnClickListener { selectPreset(WorkerConfigStudio.WorkerPreset.MAX_COMPAT) }

        findViewById<Button>(R.id.btnDeployWorker).setOnClickListener { deployWorkerToCloudflare() }
        findViewById<Button>(R.id.btnMultiWorkerDeploy).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildMultiWorkerDeployArgs(activeProfileId, 2)) {
                reloadProfileState(loadInitialEditorConfig = true)
            }
        }
        findViewById<Button>(R.id.btnRotateWorkerSig).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "multi-worker", "status"))
        }

        findViewById<Button>(R.id.btnGenUuid).setOnClickListener {
            etExistingWorkerUuid.setText(UUID.randomUUID().toString())
        }
        findViewById<Button>(R.id.btnBuildFromExistingWorker).setOnClickListener { buildConfigFromExistingWorker() }

        // Clean IP Discovery & Pool
        findViewById<Button>(R.id.btnFindCleanRust).setOnClickListener {
            val seeds = currentSummary?.cleanIps?.take(5).orEmpty()
            runNativeCli(WorkerConfigStudio.buildFindArgs(activeProfileId, count = 3, seedIps = seeds)) {
                reloadProfileState(loadInitialEditorConfig = false)
            }
        }
        findViewById<Button>(R.id.btnScoutCleanDevice).setOnClickListener { runOnDeviceCleanIpScout() }
        findViewById<Button>(R.id.btnAddCustomCleanIp).setOnClickListener { addCustomCleanIp() }
        findViewById<Button>(R.id.btnBatchCleanConfigs).setOnClickListener { generateBatchCleanIpConfigs() }
        findViewById<Button>(R.id.btnListCleanIpsCli).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildCleanipListArgs(activeProfileId))
        }

        // Editor & Exporters
        findViewById<Button>(R.id.btnPasteVlessConfig).setOnClickListener { pasteVlessIntoEditor() }
        findViewById<Button>(R.id.btnLoadActiveVpnConfig).setOnClickListener {
            val uri = TunnelConfig.load(this).vlessUri
            val parsed = WorkerConfigStudio.parseVless(uri)
            if (parsed == null) {
                Toast.makeText(this, getString(R.string.cf_toast_no_active_vpn), Toast.LENGTH_SHORT).show()
            } else {
                populateEditor(parsed, isBaseWorker = false)
                Toast.makeText(this, getString(R.string.cf_toast_loaded_active_vpn), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnResetWorkerHost).setOnClickListener {
            val host = originalWorkerHostForReset.ifBlank { etEditSni.text.toString().trim() }
            if (host.isNotEmpty()) {
                etEditAddress.setText(host)
                etEditPort.setText("443")
                etEditRemark.setText(host)
                updateExportPreview()
            }
        }

        findViewById<Button>(R.id.btnTestHandshake).setOnClickListener { testEditorHandshake() }
        findViewById<Button>(R.id.btnSaveActivateVpn).setOnClickListener { saveAndActivateEditorConfig() }
        findViewById<Button>(R.id.btnStudioQr).setOnClickListener {
            val cfg = readConfigFromEditor()
            if (cfg == null) {
                Toast.makeText(this, getString(R.string.cf_toast_fill_uuid_addr), Toast.LENGTH_SHORT).show()
            } else {
                QrShareDialog.show(this, "QR · ${cfg.name}", cfg.toVlessUri())
            }
        }
        findViewById<Button>(R.id.btnRefreshExport).setOnClickListener { updateExportPreview() }

        btnFmtLink.setOnClickListener { selectExportFormat(WorkerConfigStudio.ExportFormat.SHARE_LINK) }
        btnFmtV2rayN.setOnClickListener { selectExportFormat(WorkerConfigStudio.ExportFormat.V2RAYN) }
        btnFmtSingBox.setOnClickListener { selectExportFormat(WorkerConfigStudio.ExportFormat.SINGBOX) }
        btnFmtClash.setOnClickListener { selectExportFormat(WorkerConfigStudio.ExportFormat.CLASH) }

        findViewById<Button>(R.id.btnCopyExport).setOnClickListener {
            val text = tvExportPreview.text.toString()
            if (UiSafety.copyTextSafely(this, "DastYab ${selectedExportFormat.label}", text)) {
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnShareExport).setOnClickListener {
            val text = tvExportPreview.text.toString()
            if (text.isNotBlank()) {
                val share = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, getString(R.string.share_title))
                if (!UiSafety.safeStartActivity(this, share)) {
                    UiSafety.copyTextSafely(this, "DastYab Config", text)
                    Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnCliExport).setOnClickListener {
            runNativeCli(WorkerConfigStudio.buildExportArgs(activeProfileId, selectedExportFormat))
        }

        val liveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressPreviewWatcher) updateExportPreview()
            }
        }
        listOf(etEditAddress, etEditPort, etEditSni, etEditHost, etEditPath, etEditFp, etEditUuid, etEditRemark)
            .forEach { it.addTextChangedListener(liveWatcher) }

        // Linux Console Actions
        btnStudioRunCli.setOnClickListener { runCustomCliInput() }
        btnStudioCancelCli.setOnClickListener { cancelActiveCli() }
        etStudioCliArgs.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runCustomCliInput()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.btnStudioClearConsole).setOnClickListener {
            tvStudioConsole.text = getString(R.string.cf_console_empty)
        }
        findViewById<Button>(R.id.btnStudioCopyConsole).setOnClickListener {
            if (UiSafety.copyTextSafely(this, "DastYab Studio Console", tvStudioConsole.text.toString())) {
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Profile Loading & UI Sync
    // -------------------------------------------------------------------------

    private fun reloadProfileState(loadInitialEditorConfig: Boolean) {
        val profiles = WorkerConfigStudio.listProfiles(filesDir)
        profileList = profiles
        val targetIdx = profiles.indexOfFirst { it.id == activeProfileId }.let {
            if (it >= 0) it else 0
        }
        if (profiles.isNotEmpty()) {
            activeProfileId = profiles[targetIdx].id
        }

        val labels = profiles.map { "${it.name} (${it.id})" }
        suppressSpinnerSelection = true
        spProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spProfile.setSelection(targetIdx)
        suppressSpinnerSelection = false

        val summary = WorkerConfigStudio.loadProfileSummary(filesDir, activeProfileId)
        currentSummary = summary

        val textSecondary = ThemeManager.resolveColor(this, R.attr.dastyabTextSecondary, R.color.colorTextSecondary)

        // Token status
        tvTokenStatus.text = if (summary.tokens.isEmpty()) {
            getString(R.string.cf_token_status_profile_empty, summary.profileName)
        } else {
            val active = summary.tokens.firstOrNull { it.isActive } ?: summary.tokens.first()
            val accInfo = if (summary.accounts.isNotEmpty()) {
                " · ${summary.accounts.size}: ${summary.accounts.joinToString { it.workersDevSuffix() }}"
            } else ""
            getString(R.string.cf_token_status_active, active.label, active.maskedToken, summary.tokens.size, accInfo)
        }

        // Account chips
        llAccountChips.removeAllViews()
        if (summary.accounts.isEmpty()) {
            llAccountChips.addView(TextView(this).apply {
                text = getString(R.string.cf_accounts_empty_hint)
                textSize = 11f
                setTextColor(textSecondary)
            })
        } else {
            summary.accounts.forEach { acc ->
                val chipLabel = if (acc.subdomain.isNotBlank()) "${acc.name.ifBlank { acc.subdomain }} (${acc.subdomain}.workers.dev)"
                else "${acc.name.ifBlank { "Account" }} (${acc.id.take(8)}…)"
                val chipBtn = Button(this, null, 0, R.style.DastyabChip).apply {
                    text = chipLabel
                    setOnClickListener {
                        etDeployAccountId.setText(acc.id)
                        Toast.makeText(this@CloudflareWorkerActivity, getString(R.string.cf_toast_selected_account, acc.id.take(8)), Toast.LENGTH_SHORT).show()
                    }
                }
                DastyabMotion.attachPressSpring(chipBtn)
                llAccountChips.addView(chipBtn)
            }
            if (etDeployAccountId.text.toString().isBlank()) {
                val defaultAcc = summary.activeAccountId.ifBlank { summary.accounts.first().id }
                etDeployAccountId.setText(defaultAcc)
            }
        }

        // Clean IP pool chips
        tvCleanIpStatus.text = getString(R.string.cf_cleanip_status_count, summary.cleanIps.size)
        llCleanIpChips.removeAllViews()
        if (summary.cleanIps.isEmpty()) {
            llCleanIpChips.addView(TextView(this).apply {
                text = getString(R.string.cf_cleanip_empty_hint)
                textSize = 11f
                setTextColor(textSecondary)
            })
        } else {
            summary.cleanIps.forEach { ep ->
                val cleanChip = Button(this, null, 0, R.style.DastyabChip).apply {
                    text = ep
                    setOnClickListener { applyCleanIpToEditor(ep) }
                    setOnLongClickListener {
                        showCleanIpChipMenu(ep)
                        true
                    }
                }
                DastyabMotion.attachPressSpring(cleanChip)
                llCleanIpChips.addView(cleanChip)
            }
        }

        // History / deployed config chips
        llHistoryConfigChips.removeAllViews()
        val seenLinks = LinkedHashSet<String>()
        if (summary.lastDeployedBaseLink.isNotBlank()) {
            WorkerConfigStudio.parseVless(summary.lastDeployedBaseLink)?.let { baseCfg ->
                seenLinks.add(summary.lastDeployedBaseLink)
                val baseBtn = Button(this, null, 0, R.style.DastyabChip).apply {
                    text = "Base: ${baseCfg.address}"
                    setOnClickListener { populateEditor(baseCfg, isBaseWorker = true) }
                }
                DastyabMotion.attachPressSpring(baseBtn)
                llHistoryConfigChips.addView(baseBtn)
            }
        }
        summary.history.take(20).forEach { rec ->
            if (rec.link.isNotBlank() && seenLinks.add(rec.link)) {
                val parsed = WorkerConfigStudio.parseVless(rec.link)
                if (parsed != null) {
                    val tag = if (rec.isCleanIp) "Clean: ${rec.address}:${rec.port}" else "Worker: ${rec.address}"
                    val histBtn = Button(this, null, 0, R.style.DastyabChip).apply {
                        text = tag
                        setOnClickListener { populateEditor(parsed, isBaseWorker = !rec.isCleanIp) }
                    }
                    DastyabMotion.attachPressSpring(histBtn)
                    llHistoryConfigChips.addView(histBtn)
                }
            }
        }

        if (loadInitialEditorConfig && etEditUuid.text.toString().isBlank()) {
            val initialConfig = WorkerConfigStudio.parseVless(summary.lastDeployedBaseLink)
                ?: summary.history.firstOrNull { !it.isCleanIp }?.let { WorkerConfigStudio.parseVless(it.link) }
                ?: summary.history.firstOrNull()?.let { WorkerConfigStudio.parseVless(it.link) }
                ?: WorkerConfigStudio.parseVless(TunnelConfig.load(this).vlessUri)
            if (initialConfig != null) {
                populateEditor(initialConfig, isBaseWorker = !summary.lastDeployedBaseLink.isBlank())
            }
        }
    }

    private fun styleStudioTab(tab: Button, active: Boolean) {
        tab.alpha = 1.0f
        tab.background = getDrawable(if (active) R.drawable.tab_active_bg else R.drawable.tab_inactive_bg)
        tab.setTextColor(
            if (active) ThemeManager.resolveColor(this, R.attr.dastyabButtonPrimaryText, R.color.colorButtonPrimaryText)
            else ThemeManager.resolveColor(this, R.attr.dastyabTextSecondary, R.color.colorTextSecondary)
        )
    }

    private fun selectWorkerMode(deployNew: Boolean) {
        if (deployNew) {
            DastyabMotion.switchModeContainer(panelDeployNew, listOf(panelExistingWorker), -1)
        } else {
            DastyabMotion.switchModeContainer(panelExistingWorker, listOf(panelDeployNew), 1)
        }
        styleStudioTab(btnModeDeployNew, deployNew)
        styleStudioTab(btnModeExistingWorker, !deployNew)
    }

    private fun selectPreset(preset: WorkerConfigStudio.WorkerPreset) {
        selectedPreset = preset
        styleStudioTab(btnPresetStandard, preset == WorkerConfigStudio.WorkerPreset.STANDARD)
        styleStudioTab(btnPresetGaming, preset == WorkerConfigStudio.WorkerPreset.GAMING)
        styleStudioTab(btnPresetMaxCompat, preset == WorkerConfigStudio.WorkerPreset.MAX_COMPAT)
        tvPresetDesc.text = "${preset.label}: ${preset.description}"
    }

    private fun selectExportFormat(format: WorkerConfigStudio.ExportFormat) {
        selectedExportFormat = format
        styleStudioTab(btnFmtLink, format == WorkerConfigStudio.ExportFormat.SHARE_LINK)
        styleStudioTab(btnFmtV2rayN, format == WorkerConfigStudio.ExportFormat.V2RAYN)
        styleStudioTab(btnFmtSingBox, format == WorkerConfigStudio.ExportFormat.SINGBOX)
        styleStudioTab(btnFmtClash, format == WorkerConfigStudio.ExportFormat.CLASH)
        updateExportPreview()
    }

    // -------------------------------------------------------------------------
    // Core Cloudflare Actions
    // -------------------------------------------------------------------------

    private fun showCreateProfileDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.cf_dialog_profile_hint)
            setSingleLine(true)
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cf_dialog_profile_title))
                .setView(input)
                .setPositiveButton(getString(R.string.cf_btn_create)) { _, _ ->
                    val name = input.text.toString().trim()
                    val args = WorkerConfigStudio.buildProfileCreateArgs(name)
                    if (args == null) {
                        Toast.makeText(this, getString(R.string.cf_toast_enter_profile_name), Toast.LENGTH_SHORT).show()
                    } else {
                        runNativeCli(args) {
                            val newest = WorkerConfigStudio.listProfiles(filesDir)
                                .firstOrNull { it.name.equals(name, ignoreCase = true) }
                            if (newest != null) activeProfileId = newest.id
                            reloadProfileState(loadInitialEditorConfig = true)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun saveAndVerifyToken() {
        val token = etApiToken.text.toString().trim()
        val label = etTokenLabel.text.toString().trim().ifEmpty { "default" }
        if (token.isEmpty()) {
            // If input empty but profile already has a token, just run verify
            if (currentSummary?.tokens?.isNotEmpty() == true) {
                runNativeCli(WorkerConfigStudio.buildVerifyArgs(activeProfileId)) {
                    reloadProfileState(loadInitialEditorConfig = false)
                }
            } else {
                Toast.makeText(this, getString(R.string.cf_toast_paste_token_first), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Persist immediately to profile token.json so both Studio and Rust CLI see it cleanly,
        // then run `token-recover` through libdastyab.so to verify against Cloudflare API.
        WorkerConfigStudio.saveTokenToProfile(filesDir, activeProfileId, label, token, makeActive = true)
        reloadProfileState(loadInitialEditorConfig = false)

        val args = WorkerConfigStudio.buildTokenRecoverArgs(activeProfileId, label, token)
        if (args != null) {
            runNativeCli(args) {
                reloadProfileState(loadInitialEditorConfig = false)
            }
        }
    }

    private fun deployWorkerToCloudflare() {
        val accId = etDeployAccountId.text.toString().trim().ifEmpty { null }
        val workerName = etDeployWorkerName.text.toString().trim().ifEmpty { null }
        val args = WorkerConfigStudio.buildDeployArgs(
            profileId = activeProfileId,
            accountId = accId,
            workerName = workerName,
            preset = selectedPreset
        )
        runNativeCli(args) { exitCode ->
            reloadProfileState(loadInitialEditorConfig = false)
            val refreshed = WorkerConfigStudio.loadProfileSummary(filesDir, activeProfileId)
            val baseLink = refreshed.lastDeployedBaseLink.ifBlank {
                refreshed.history.firstOrNull()?.link.orEmpty()
            }
            val parsed = WorkerConfigStudio.parseVless(baseLink)
            if (parsed != null) {
                populateEditor(parsed, isBaseWorker = true)
                Toast.makeText(
                    this,
                    if (exitCode == 0) getString(R.string.cf_toast_worker_deployed) else getString(R.string.cf_toast_loaded_latest),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildConfigFromExistingWorker() {
        val host = etExistingWorkerHost.text.toString().trim()
        val uuid = etExistingWorkerUuid.text.toString().trim()
        val path = etExistingWorkerPath.text.toString().trim().ifEmpty { "/" }
        val port = etExistingWorkerPort.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 443

        val cleanIp = currentSummary?.cleanIps?.firstOrNull()
        val config = WorkerConfigStudio.fromExistingWorker(
            workerHostOrUrl = host,
            uuidInput = uuid,
            wsPath = path,
            port = port,
            cleanIpEndpoint = null,
            label = null
        )
        if (config == null) {
            Toast.makeText(this, getString(R.string.cf_toast_invalid_worker_host), Toast.LENGTH_SHORT).show()
            return
        }

        if (etExistingWorkerUuid.text.toString().isBlank()) {
            etExistingWorkerUuid.setText(config.uuid)
        }
        WorkerConfigStudio.appendHistoryRecord(filesDir, activeProfileId, config, isCleanIp = false, note = "existing-worker")
        populateEditor(config, isBaseWorker = true)
        reloadProfileState(loadInitialEditorConfig = false)

        if (cleanIp != null) {
            Toast.makeText(this, getString(R.string.cf_toast_existing_ready_with_ip, cleanIp), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.cf_toast_existing_ready), Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // Clean IP Discovery & Editing
    // -------------------------------------------------------------------------

    private fun runOnDeviceCleanIpScout() {
        setBusy(true)
        appendConsole("\n\$ fast-device-scout (probing Cloudflare CIDR edges on TCP/443)\n---\n")
        thread(isDaemon = true, name = "dastyab-cf-scout") {
            val results = CleanEndpointScout.scoutCleanEdgeIps(samplesPerCidr = 4, timeoutMs = 1800)
            val discovered = results.map { it.address }
            if (discovered.isNotEmpty()) {
                WorkerConfigStudio.appendCleanIps(filesDir, activeProfileId, discovered)
            }
            UiSafety.safePostUi(this, handler) {
                setBusy(false)
                if (results.isEmpty()) {
                    appendConsole("No responsive edge IPs found in quick CIDR sample. Retry or add a custom Clean IP.\n")
                } else {
                    appendConsole("Found ${results.size} responsive Cloudflare edge IP(s):\n")
                    results.forEach { r ->
                        appendConsole("  ✓ ${r.address}:443  (${r.latencyMs} ms · ${r.label})\n")
                    }
                    reloadProfileState(loadInitialEditorConfig = false)
                    val fastest = results.first().address
                    if (readConfigFromEditor() != null) {
                        applyCleanIpToEditor(fastest)
                    }
                }
            }
        }
    }

    private fun addCustomCleanIp() {
        val raw = etCustomCleanIp.text.toString()
        val parsedList = WorkerConfigStudio.extractCleanIpsFromText(raw)
        if (parsedList.isEmpty()) {
            Toast.makeText(this, getString(R.string.cf_toast_invalid_cleanip_input), Toast.LENGTH_SHORT).show()
            return
        }
        WorkerConfigStudio.appendCleanIps(filesDir, activeProfileId, parsedList)
        etCustomCleanIp.setText("")
        reloadProfileState(loadInitialEditorConfig = false)
        if (readConfigFromEditor() != null) {
            applyCleanIpToEditor(parsedList.first())
        }
        appendConsole("Added ${parsedList.size} Clean IP(s) to profile '$activeProfileId': ${parsedList.joinToString()}\n")
    }

    private fun showCleanIpChipMenu(endpoint: String) {
        val sniHost = etEditSni.text.toString().trim()
        val sniTag = if (sniHost.isNotBlank()) " → $sniHost" else ""
        val options = arrayOf(
            getString(R.string.cf_cleanip_menu_apply, endpoint),
            getString(R.string.cf_cleanip_menu_test, "$endpoint$sniTag"),
            getString(R.string.cf_cleanip_menu_remove, endpoint)
        )
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle("Clean IP: $endpoint")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> applyCleanIpToEditor(endpoint)
                        1 -> {
                            val parsed = WorkerConfigStudio.parseCleanEndpoint(endpoint) ?: return@setItems
                            val port = parsed.second ?: 443
                            setBusy(true)
                            appendConsole("\nProbing TLS+SNI to ${parsed.first}:$port (SNI: ${sniHost.ifBlank { "(none)" }})…\n")
                            thread(isDaemon = true, name = "dastyab-probe-cleanip") {
                                val ms = CleanEndpointScout.probeTlsWithSni(parsed.first, port, sniHost, 3000)
                                UiSafety.safePostUi(this, handler) {
                                    setBusy(false)
                                    if (ms >= 0) {
                                        appendConsole("OK: ${parsed.first}:$port TLS handshake succeeded in ${ms} ms!\n")
                                        Toast.makeText(this, getString(R.string.cf_toast_tls_ok, ms.toInt()), Toast.LENGTH_SHORT).show()
                                    } else {
                                        appendConsole("ERROR: ${parsed.first}:$port TLS handshake failed or timed out.\n")
                                        Toast.makeText(this, getString(R.string.cf_toast_tls_failed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        2 -> {
                            WorkerConfigStudio.removeCleanIp(filesDir, activeProfileId, endpoint)
                            reloadProfileState(loadInitialEditorConfig = false)
                        }
                    }
                }
        )
    }

    private fun applyCleanIpToEditor(cleanEndpoint: String) {
        val current = readConfigFromEditor()
        if (current == null) {
            // Try loading base config from profile if editor is empty
            val fallbackBase = WorkerConfigStudio.parseVless(currentSummary?.lastDeployedBaseLink)
                ?: WorkerConfigStudio.parseVless(TunnelConfig.load(this).vlessUri)
            if (fallbackBase == null) {
                Toast.makeText(this, getString(R.string.cf_toast_need_worker_first), Toast.LENGTH_LONG).show()
                return
            }
            val patched = fallbackBase.applyCleanIp(cleanEndpoint)
            if (patched != null) {
                originalWorkerHostForReset = fallbackBase.effectiveSni()
                populateEditor(patched, isBaseWorker = false)
                Toast.makeText(this, getString(R.string.cf_toast_applied_cleanip, cleanEndpoint, patched.effectiveSni()), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val patched = current.applyCleanIp(cleanEndpoint)
        if (patched == null) {
            Toast.makeText(this, getString(R.string.cf_toast_invalid_cleanip_ep, cleanEndpoint), Toast.LENGTH_SHORT).show()
            return
        }
        populateEditor(patched, isBaseWorker = false)
        Toast.makeText(this, getString(R.string.cf_toast_patched_cleanip, patched.address, patched.port, patched.effectiveSni()), Toast.LENGTH_SHORT).show()
    }

    private fun generateBatchCleanIpConfigs() {
        val base = readConfigFromEditor()
        if (base == null) {
            Toast.makeText(this, getString(R.string.cf_toast_need_worker_first), Toast.LENGTH_LONG).show()
            return
        }
        val pool = currentSummary?.cleanIps.orEmpty().take(5)
        if (pool.isEmpty()) {
            Toast.makeText(this, getString(R.string.cf_toast_no_cleanips_saved), Toast.LENGTH_LONG).show()
            return
        }
        val generatedUris = mutableListOf<String>()
        pool.forEach { ep ->
            val patched = base.applyCleanIp(ep)
            if (patched != null) {
                WorkerConfigStudio.appendHistoryRecord(filesDir, activeProfileId, patched, isCleanIp = true, note = "batch-cleanip")
                generatedUris.add(patched.toVlessUri())
            }
        }
        if (generatedUris.isNotEmpty()) {
            val existingHarvested = TunnelConfig.loadHarvestedConfigs(this)
            TunnelConfig.saveHarvestedConfigs(this, generatedUris + existingHarvested)
            TunnelConfig.saveVlessUri(this, generatedUris.first())
            val layer = VpnLayerStore.VpnLayer(
                id = "layer-cf-worker",
                name = "CF Worker · ${base.effectiveSni().take(24)}",
                mode = TunnelConfig.Mode.SMART,
                configUri = generatedUris.first(),
                notes = "Generated with ${generatedUris.size} Clean IP(s)"
            )
            VpnLayerStore.addOrUpdate(this, layer)
            reloadProfileState(loadInitialEditorConfig = false)
            appendConsole("Generated ${generatedUris.size} Clean-IP config(s) & synced to VPN pool:\n" +
                generatedUris.joinToString("\n") + "\n")
            Toast.makeText(this, getString(R.string.cf_toast_saved_batch, generatedUris.size), Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // Editor Helpers, Handshake Test, Save & VPN Activation
    // -------------------------------------------------------------------------

    private fun populateEditor(config: WorkerConfigStudio.VlessConfig, isBaseWorker: Boolean) {
        if (isBaseWorker || originalWorkerHostForReset.isBlank()) {
            originalWorkerHostForReset = config.effectiveSni()
        }
        suppressPreviewWatcher = true
        etEditAddress.setText(config.address)
        etEditPort.setText(config.port.toString())
        etEditSni.setText(config.effectiveSni())
        etEditHost.setText(config.effectiveHost())
        etEditPath.setText(config.canonicalPath())
        etEditFp.setText(config.effectiveFingerprint())
        etEditUuid.setText(config.uuid)
        etEditRemark.setText(config.name)
        suppressPreviewWatcher = false

        tvEditorBaseStatus.text = "Editing: ${config.address}:${config.port} · SNI=${config.effectiveSni()} · path=${config.canonicalPath()}"
        updateExportPreview()
    }

    private fun readConfigFromEditor(): WorkerConfigStudio.VlessConfig? {
        val address = etEditAddress.text.toString().trim()
        val uuid = etEditUuid.text.toString().trim()
        if (address.isEmpty() || uuid.isEmpty()) return null
        val port = etEditPort.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
        val sni = etEditSni.text.toString().trim().ifEmpty { address }
        val host = etEditHost.text.toString().trim().ifEmpty { sni }
        val path = etEditPath.text.toString().trim().let {
            if (it.isEmpty()) "/" else if (it.startsWith("/")) it else "/$it"
        }
        val fp = etEditFp.text.toString().trim().ifEmpty { "chrome" }
        val remark = etEditRemark.text.toString().trim().ifEmpty { "cf-$address" }
        return WorkerConfigStudio.VlessConfig(
            uuid = uuid,
            address = address,
            port = port,
            security = "tls",
            transport = "ws",
            sni = sni,
            host = host,
            path = path,
            fp = fp,
            encryption = "none",
            name = remark
        )
    }

    private fun updateExportPreview() {
        val cfg = readConfigFromEditor()
        if (cfg == null) {
            tvExportPreview.text = getString(R.string.cf_export_empty_format, selectedExportFormat.label)
            return
        }
        tvExportPreview.text = cfg.export(selectedExportFormat)
    }

    private fun pasteVlessIntoEditor() {
        val clip = readClipboardText()
        val parsed = WorkerConfigStudio.parseVless(clip)
        if (parsed == null) {
            Toast.makeText(this, getString(R.string.tunnel_vless_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val isClean = WorkerConfigStudio.isValidCanonicalIpv4(parsed.address)
        populateEditor(parsed, isBaseWorker = !isClean)
        Toast.makeText(this, getString(R.string.cf_toast_imported_vless), Toast.LENGTH_SHORT).show()
    }

    private fun testEditorHandshake() {
        val cfg = readConfigFromEditor()
        if (cfg == null) {
            Toast.makeText(this, getString(R.string.cf_toast_fill_uuid_addr), Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        appendConsole("\nTesting TCP + TLS SNI handshake → ${cfg.address}:${cfg.port} (SNI=${cfg.effectiveSni()})…\n")
        thread(isDaemon = true, name = "dastyab-tls-test") {
            val ms = CleanEndpointScout.probeTlsWithSni(cfg.address, cfg.port, cfg.effectiveSni(), 3500)
            UiSafety.safePostUi(this, handler) {
                setBusy(false)
                if (ms >= 0) {
                    appendConsole("OK: Handshake to ${cfg.address}:${cfg.port} with SNI '${cfg.effectiveSni()}' succeeded in ${ms} ms!\n")
                    Toast.makeText(this, getString(R.string.cf_toast_tls_ok, ms.toInt()), Toast.LENGTH_SHORT).show()
                } else {
                    appendConsole("ERROR: Handshake to ${cfg.address}:${cfg.port} (SNI '${cfg.effectiveSni()}') failed/reset.\n" +
                        "Tip: Try a different Clean IP from the pool above.\n")
                    Toast.makeText(this, getString(R.string.cf_toast_handshake_failed_retry), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveAndActivateEditorConfig() {
        val cfg = readConfigFromEditor()
        if (cfg == null) {
            Toast.makeText(this, getString(R.string.cf_toast_fill_uuid_addr), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = cfg.toVlessUri()
        val isClean = WorkerConfigStudio.isValidCanonicalIpv4(cfg.address)

        // 1. Save into shared ~/.dastyab/profiles/<id>/history.json
        WorkerConfigStudio.appendHistoryRecord(filesDir, activeProfileId, cfg, isCleanIp = isClean, note = "android-studio-edited")
        if (isClean) {
            WorkerConfigStudio.appendCleanIps(filesDir, activeProfileId, listOf("${cfg.address}:${cfg.port}"))
        }

        // 2. Save into Android TunnelConfig & VpnLayerStore
        TunnelConfig.saveVlessUri(this, uri)
        val layer = VpnLayerStore.VpnLayer(
            id = "layer-cf-worker",
            name = cfg.name.ifBlank { "CF Worker (${cfg.address})" },
            mode = TunnelConfig.load(this).mode.let {
                if (it == TunnelConfig.Mode.DNS_BLACKOUT) TunnelConfig.Mode.SMART else it
            },
            configUri = uri,
            notes = "SNI=${cfg.effectiveSni()} · ${cfg.address}:${cfg.port}"
        )
        VpnLayerStore.addOrUpdate(this, layer)
        VpnLayerStore.activateLayer(this, layer, restartIfConnected = false)
        reloadProfileState(loadInitialEditorConfig = false)
        appendConsole("OK: Saved config & set as active DastYab VPN layer:\n$uri\n")

        // 3. Connect / reconnect Android VPN service
        val prep = VpnService.prepare(this)
        if (prep != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(prep, REQ_VPN_PREPARE)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.cf_toast_saved_to_profile), Toast.LENGTH_LONG).show()
            }
        } else {
            DastyabVpnService.start(this)
            Toast.makeText(this, getString(R.string.cf_toast_saved_vpn_activated), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE && resultCode == RESULT_OK) {
            DastyabVpnService.start(this)
            Toast.makeText(this, getString(R.string.cf_toast_vpn_started_worker), Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // Linux Power CLI Integration (`libdastyab.so`)
    // -------------------------------------------------------------------------

    private fun buildLinuxCliChips() {
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "configs list" to { runNativeCli(WorkerConfigStudio.buildConfigsListArgs(activeProfileId)) },
            "routes list" to { runNativeCli(WorkerConfigStudio.buildRoutesListArgs(activeProfileId)) },
            "+ route" to { showCreateRouteDialog() },
            "dns list" to { showDnsListDialog() },
            "sni-spoof scan" to { runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "sni-spoof", "scan")) },
            "presets" to { runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "presets")) },
            "doctor" to { runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "doctor")) },
            "security" to { runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "security")) },
            "e2e" to { runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "e2e", "--skip-worker-deploy")) },
            "backup list" to { runNativeCli(WorkerConfigStudio.buildSimpleSubcommandArgs(activeProfileId, "backup", "list")) }
        )
        llLinuxCliChips.removeAllViews()
        actions.forEach { (label, onClick) ->
            llLinuxCliChips.addView(Button(this, null, 0, R.style.DastyabChip).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }
    }

    private fun showCreateRouteDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val etPattern = EditText(this).apply { hint = getString(R.string.cf_dialog_route_pattern_hint) }
        val etScript = EditText(this).apply {
            hint = getString(R.string.cf_dialog_route_script_hint)
            setText(etDeployWorkerName.text.toString().trim())
        }
        box.addView(etPattern)
        box.addView(etScript)

        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cf_dialog_route_title))
                .setView(box)
                .setPositiveButton(getString(R.string.cf_btn_create)) { _, _ ->
                    val args = WorkerConfigStudio.buildRoutesCreateArgs(
                        activeProfileId,
                        etPattern.text.toString(),
                        etScript.text.toString()
                    )
                    if (args == null) {
                        Toast.makeText(this, getString(R.string.cf_toast_enter_pattern_script), Toast.LENGTH_SHORT).show()
                    } else {
                        runNativeCli(args)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun showDnsListDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.cf_dialog_dns_hint)
            setSingleLine(true)
        }
        UiSafety.safeShowDialog(
            this,
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cf_dialog_dns_title))
                .setView(input)
                .setPositiveButton(getString(R.string.cf_btn_list)) { _, _ ->
                    val args = WorkerConfigStudio.buildDnsListArgs(activeProfileId, input.text.toString())
                    if (args == null) {
                        Toast.makeText(this, getString(R.string.cf_toast_enter_zone), Toast.LENGTH_SHORT).show()
                    } else {
                        runNativeCli(args)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun runCustomCliInput() {
        val raw = etStudioCliArgs.text.toString().trim()
        if (raw.isEmpty()) return
        val args = try {
            CommandArgs.parse(raw)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message ?: getString(R.string.error_invalid_args), Toast.LENGTH_SHORT).show()
            return
        }
        if (args.isEmpty()) return
        val finalArgs = if (activeProfileId != "default" && !args.contains("--profile")) {
            listOf("--profile", activeProfileId) + args
        } else {
            args
        }
        runNativeCli(finalArgs) {
            reloadProfileState(loadInitialEditorConfig = false)
        }
    }

    private fun runNativeCli(args: List<String>, onFinished: ((Int) -> Unit)? = null) {
        if (!cliRunning.compareAndSet(false, true)) {
            Toast.makeText(this, getString(R.string.cf_toast_cli_busy), Toast.LENGTH_SHORT).show()
            return
        }
        when (val verdict = CommandCompat.check(args)) {
            is CommandCompat.Verdict.Blocked -> {
                cliRunning.set(false)
                appendConsole("\n${CommandCompat.blockedTitle(verdict)}:\n${CommandCompat.blockedMessage(verdict)}\n")
                return
            }
            is CommandCompat.Verdict.OpenAistudio,
            is CommandCompat.Verdict.OpenFlow -> {
                cliRunning.set(false)
                appendConsole("\nUse the main screen's AI Studio / Flow launcher for browser tunneling.\n")
                return
            }
            CommandCompat.Verdict.RunNative -> Unit
        }

        if (!binary.isFile || !binary.canExecute()) {
            cliRunning.set(false)
            appendConsole("\nERROR: Bundled libdastyab.so engine not executable on this ABI.\n")
            return
        }

        cliCancelled.set(false)
        setBusy(true)
        appendConsole("\n\$ dastyab ${args.joinToString(" ")}\n---\n")

        thread(isDaemon = true, name = "dastyab-studio-cli") {
            var process: Process? = null
            var readerThread: Thread? = null
            var exitCode = -1
            try {
                val child = ProcessBuilder(listOf(binary.absolutePath) + args).apply {
                    directory(filesDir)
                    environment()["HOME"] = filesDir.absolutePath
                    environment()["TMPDIR"] = cacheDir.absolutePath
                    redirectErrorStream(true)
                }.start()
                process = child
                activeProcess.set(child)
                child.outputStream.close()

                readerThread = thread(isDaemon = true, name = "dastyab-studio-out") {
                    try {
                        child.inputStream.reader(Charsets.UTF_8).use { reader ->
                            val buf = CharArray(2048)
                            while (true) {
                                val n = reader.read(buf)
                                if (n < 0) break
                                val chunk = String(buf, 0, n)
                                UiSafety.safePostUi(this, handler) { appendConsole(chunk) }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_TIMEOUT_MS)
                while (true) {
                    try {
                        exitCode = child.exitValue()
                        break
                    } catch (_: IllegalThreadStateException) {
                        if (cliCancelled.get() || System.nanoTime() >= deadline) {
                            terminate(child)
                            exitCode = if (cliCancelled.get()) 130 else 124
                            break
                        }
                        Thread.sleep(50)
                    }
                }
            } catch (e: Exception) {
                UiSafety.safePostUi(this, handler) {
                    appendConsole("ERROR: ${e.message ?: e.javaClass.simpleName}\n")
                }
            } finally {
                process?.let { runCatching { terminate(it) } }
                runCatching { readerThread?.join(800) }
                activeProcess.set(null)
                cliRunning.set(false)
                val finalExit = exitCode
                UiSafety.safePostUi(this, handler) {
                    setBusy(false)
                    appendConsole("--- exit code $finalExit ---\n")
                    onFinished?.invoke(finalExit)
                }
            }
        }
    }

    private fun cancelActiveCli() {
        cliCancelled.set(true)
        activeProcess.get()?.let { terminate(it) }
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (Build.VERSION.SDK_INT >= 26) {
            process.destroyForcibly()
        }
    }

    private fun setBusy(busy: Boolean) {
        cfProgress.visibility = if (busy) View.VISIBLE else View.GONE
        btnStudioCancelCli.visibility = if (busy && cliRunning.get()) View.VISIBLE else View.GONE
        btnStudioRunCli.isEnabled = !busy
    }

    private fun appendConsole(text: String) {
        val current = tvStudioConsole.text.toString()
        val emptyHint = getString(R.string.cf_console_empty)
        val updated = if (current == emptyHint) text else current + text
        tvStudioConsole.text = if (updated.length > MAX_CONSOLE_CHARS) {
            updated.substring(updated.length - MAX_CONSOLE_CHARS)
        } else {
            updated
        }
    }

    private fun readClipboardText(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(this).toString()
        } catch (_: Throwable) {
            ""
        }
    }

    override fun onDestroy() {
        cancelActiveCli()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
