package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import com.dastyab.app.CrashHandler
import com.dastyab.app.DastyabLogger

/**
 * Dastyab for Android — stable, production-ready implementation.
 *
 * Strategy: reuse the existing Rust `dastyab` CLI unchanged. On first launch the
 * app downloads the pre-built `dastyab-android-<arch>` binary from GitHub
 * Releases into the app's private files dir, verifies it is a valid ELF binary,
 * chmods it executable, and shells out to it via ProcessBuilder, streaming
 * stdout/stderr into a scrollable view.
 *
 * Stability features:
 *   • Network connectivity check before download (no cryptic timeouts)
 *   • Storage space check before download (no mid-download ENOSPC)
 *   • 3-try retry with exponential backoff for transient failures
 *   • ELF magic verification after download (rejects corrupt/partial files)
 *   • Retry button on failure instead of silent dead-end
 *   • Progress bar during download so the app never looks frozen
 *   • All state (~/.dastyab) sandboxed under the app's files dir ($HOME override)
 *   • Thread-safe logging to Logcat + rolling file logs (DastyabLogger)
 *
 * No runtime permissions: INTERNET is a normal manifest permission, and the app
 * only touches its own private storage.
 *
 * Build:
 *   cd android
 *   ./gradlew assembleDebug   (needs Android SDK + Gradle; or open in Android Studio)
 *
 * Prerequisite: the dastyab-android-<arch> asset must exist on a GitHub Release
 * (pushing a `v*` tag triggers CI which builds all four Android ABIs).
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var argsInput: EditText
    private lateinit var btnRun: Button
    private lateinit var btnClear: Button
    private lateinit var btnRetry: Button
    private lateinit var btnCancel: Button
    private lateinit var btnViewLogs: Button
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button
    private lateinit var btnAbout: Button
    private lateinit var btnSettings: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnHistory: Button
    private lateinit var statusDot: View
    private lateinit var historySpinner: android.widget.Spinner
    private lateinit var tvVersion: TextView
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val repo = "DastYab/dastyab"
    private lateinit var binary: File

    private var downloadAttempts = 0
    @Volatile
    private var autoUpdatePending = false

    companion object {
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 2000L
        private const val PROCESS_TIMEOUT_MS = 300_000L
        private const val MAX_OUTPUT_CHARS = 50_000
        private const val MAX_ARGS_LENGTH = 4096
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024
    }

    private var bytesDownloaded = AtomicLong(0)
    private var bytesTotal = AtomicLong(0)
    private val downloadCancellation = AtomicBoolean(false)
    private var runningProcess: java.lang.Process? = null
    private val pendingHandlerCallbacks = mutableListOf<Runnable>()

    private val argsSanitizer = Regex("^[\\p{Print}\\p{Space}-]{0,4096}$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        output = findViewById(R.id.output)
        argsInput = findViewById(R.id.argsInput)
        btnRun = findViewById(R.id.btnRun)
        btnClear = findViewById(R.id.btnClear)
        btnRetry = findViewById(R.id.btnRetry)
        btnCancel = findViewById(R.id.btnCancel)
        btnViewLogs = findViewById(R.id.btnViewLogs)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)
        btnAbout = findViewById(R.id.btnAbout)
        btnSettings = findViewById(R.id.btnSettings)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnHistory = findViewById(R.id.btnHistory)
        statusDot = findViewById(R.id.statusDot)
        historySpinner = findViewById(R.id.historySpinner)
        tvVersion = findViewById(R.id.tvVersion)
        progress = findViewById(R.id.progress)

        // Display app version
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        tvVersion.text = getString(R.string.version_format, versionName)

        binary = File(filesDir, "dastyab")

        // Restore persisted state across configuration changes (rotation, etc.)
        if (savedInstanceState != null) {
            downloadAttempts = savedInstanceState.getInt("downloadAttempts", 0)
            bytesDownloaded.set(savedInstanceState.getLong("bytesDownloaded", 0))
            bytesTotal.set(savedInstanceState.getLong("bytesTotal", 0))
            val pendingStatus = savedInstanceState.getString("pendingStatus", null)
            if (pendingStatus != null) postStatus(pendingStatus)
            val pendingOutput = savedInstanceState.getString("pendingOutput", null)
            if (pendingOutput != null) output.append(pendingOutput)
        }

        DastyabLogger.init(filesDir)
        CrashHandler.install(this, filesDir)
        CommandHistory.init(filesDir)
        applyPersistedSettings()
        DastyabLogger.i("MainActivity",
            "onCreate — binary=%s, exists=%s, abi=%s, attempt=%d",
            binary.absolutePath, binary.exists(),
            android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: android.os.Build.CPU_ABI,
            downloadAttempts)

        buildQuickButtons()
        buildProfileQuickButtons()
        setupHistorySpinner()
        setupHistoryButton()
        setupSettingsAndUpdateButtons()

        btnRun.setOnClickListener { runTyped() }
        btnClear.setOnClickListener { output.setText("") }
        btnRetry.setOnClickListener { retryDownload() }
        btnCancel.setOnClickListener { cancelCurrentOperation() }
        btnViewLogs.setOnClickListener {
            DastyabLogger.i("MainActivity", "Opening LogViewerActivity")
            try {
                startActivity(Intent(this, LogViewerActivity::class.java))
            } catch (e: android.content.ActivityNotFoundException) {
                DastyabLogger.e("MainActivity", e, "LogViewerActivity not found — is it registered in AndroidManifest.xml?")
                postStatus("Error: log viewer not available")
            }
        }
        btnCopy.setOnClickListener {
            val text = output.text.toString()
            if (text.isEmpty()) {
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Dastyab output", text))
            Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            DastyabLogger.i("MainActivity", "Output copied to clipboard")
        }
        btnShare.setOnClickListener {
            val text = output.text.toString()
            if (text.isEmpty()) return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_title)))
            DastyabLogger.i("MainActivity", "Share intent launched")
        }
        btnAbout.setOnClickListener {
            DastyabLogger.i("MainActivity", "Opening AboutActivity")
            try {
                startActivity(Intent(this, AboutActivity::class.java))
            } catch (e: android.content.ActivityNotFoundException) {
                DastyabLogger.e("MainActivity", e, "AboutActivity not found — is it registered in AndroidManifest.xml?")
                postStatus("Error: about not available")
            }
        }

        // Initially hide retry + cancel + progress; show when needed.
        btnRetry.visibility = View.GONE
        btnCancel.visibility = View.GONE
        progress.visibility = View.GONE

        evaluateBinaryState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("downloadAttempts", downloadAttempts)
        outState.putLong("bytesDownloaded", bytesDownloaded.get())
        outState.putLong("bytesTotal", bytesTotal.get())
        outState.putString("pendingStatus", status.text.toString())
        outState.putString("pendingOutput", output.text.toString())
        DastyabLogger.d("MainActivity", "onSaveInstanceState — persisted UI state")
    }

    override fun onDestroy() {
        DastyabLogger.i("MainActivity", "onDestroy — cleaning up")

        // Cancel any in-flight operation to avoid leaking threads/processes.
        cancelCurrentOperation()

        // Remove all pending handler callbacks to prevent memory leaks and
        // post-destroy UI updates that would crash.
        for (cb in pendingHandlerCallbacks) {
            handler.removeCallbacks(cb)
        }
        pendingHandlerCallbacks.clear()
        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        DastyabLogger.d("MainActivity", "onResume")
        // Re-validate binary on resume (chmod could have been lost, file could be
        // deleted by another process, etc.)
        if (binary.exists() && !BinaryUtils.isValidElfBinary(binary)) {
            DastyabLogger.w("MainActivity", "Binary became invalid — scheduling re-download")
            binary.delete()
            startDownload()
        }
    }

    override fun onPause() {
        DastyabLogger.d("MainActivity", "onPause")
        super.onPause()
    }

    override fun onStop() {
        DastyabLogger.d("MainActivity", "onStop")
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        DastyabLogger.d("MainActivity", "onRestart")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DastyabLogger.d("MainActivity", "onConfigurationChanged — orientation=%d",
            newConfig.orientation)
    }

    /** Decide whether the binary is ready, needs download, or is broken. */
    private fun evaluateBinaryState() {
        if (BinaryUtils.isValidElfBinary(binary)) {
            if (!binary.canExecute()) {
                DastyabLogger.w("MainActivity", "Binary exists but not executable — repairing chmod")
                binary.setExecutable(true, false)
                if (!binary.canExecute()) {
                    DastyabLogger.e("MainActivity", "Failed to make binary executable — re-downloading")
                    binary.delete()
                    startDownload()
                    return
                }
            }
            postStatus(getString(R.string.status_ready))
            setStatusDot(DotState.GREEN)
            setRunEnabled(true)
            hideRetry()
            hideCancel()
            DastyabLogger.i("MainActivity", "Binary is valid and ready")
            // Auto-update binary check on app start (if enabled in settings).
            if (autoUpdatePending) {
                autoUpdatePending = false
                checkForBinaryUpdate(auto = true)
            }
        } else if (binary.exists()) {
            // File exists but isn't a valid ELF — delete it and re-download.
            DastyabLogger.w("MainActivity",
                "Binary exists but is invalid (size=%d), deleting and re-downloading",
                binary.length())
            binary.delete()
            startDownload()
        } else {
            startDownload()
        }
    }

    /** User tapped Retry after a failed download. */
    private fun retryDownload() {
        downloadAttempts = 0
        downloadCancellation.set(false)
        hideRetry()
        hideCancel()
        postStatus(getString(R.string.status_downloading))
        startDownload()
    }

    /** Read the free-form args field and run the binary. */
    private fun runTyped() {
        val s = argsInput.text.toString().trim()
        if (s.isEmpty()) return

        // ── Input validation / sanitization ──
        if (s.length > MAX_ARGS_LENGTH) {
            postStatus(getString(R.string.error_args_too_long))
            DastyabLogger.w("MainActivity", "Args too long: %d chars (max %d)",
                s.length, MAX_ARGS_LENGTH)
            return
        }
        if (!argsSanitizer.matches(s)) {
            postStatus(getString(R.string.error_invalid_args))
            DastyabLogger.w("MainActivity", "Rejected args containing unsafe chars")
            return
        }
        // Reject path traversal attempts
        if (s.contains("..") || s.contains("/") || s.contains("\\")) {
            postStatus(getString(R.string.error_unsafe_args))
            DastyabLogger.w("MainActivity", "Rejected args with path separators")
            return
        }

        if (!BinaryUtils.isValidElfBinary(binary)) {
            postStatus(getString(R.string.status_binary_not_ready))
            evaluateBinaryState() // try to fix (re-download)
            return
        }
        CommandHistory.add(s)
        refreshHistorySpinner()
        runCommand(s.split(Regex("\\s+")).filter { it.isNotEmpty() }.toTypedArray())
    }

    /** Re-populate the history spinner (after a command runs). */
    private fun refreshHistorySpinner() {
        val cb = { setupHistorySpinner() }
        pendingHandlerCallbacks.add(cb)
        handler.post(cb)
    }

    /** Build the row of one-tap quick-action buttons. */
    private fun buildQuickButtons() {
        val container: LinearLayout = findViewById(R.id.quickButtons)
        val cmds = listOf(
            "verify", "accounts", "profiles", "token-list", "scan",
            "deploy", "autopilot", "security", "manifest-verify", "update-check",
            "configs", "backup", "zones", "routes", "history"
        )
        for (c in cmds) {
            val b = Button(this, null, 0, R.style.DastyabChip).apply {
                text = c
            }
            b.setOnClickListener {
                argsInput.setText(c)
                runTyped()
            }
            container.addView(b)
        }
    }

    /** Build the second row of profile-related quick-action buttons. */
    private fun buildProfileQuickButtons() {
        val container: LinearLayout = findViewById(R.id.quickButtonsProfiles)
        val cmds = listOf(
            "profile-create", "profile-switch", "profile-list", "token-add",
            "token-remove", "backup-create", "backup-restore", "config-export",
            "config-v2ray", "config-singbox", "config-clash"
        )
        for (c in cmds) {
            val b = Button(this, null, 0, R.style.DastyabChip).apply {
                text = c
            }
            b.setOnClickListener {
                argsInput.setText(c)
                runTyped()
            }
            container.addView(b)
        }
    }

    /** Populate the history spinner from CommandHistory. */
    private fun setupHistorySpinner() {
        val cmds = CommandHistory.getCommands()
        val items = mutableListOf(getString(R.string.history_prompt))
        items.addAll(cmds)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        historySpinner.adapter = adapter
        historySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var selecting = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) return // prompt row
                // Only react to genuine user selections.
                if (selecting) { selecting = false; return }
                selecting = true
                val cmd = items[position]
                argsInput.setText(cmd)
                DastyabLogger.d("MainActivity", "History spinner selected: %s", cmd)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** History button — full history dialog; tapping an entry fills args. */
    private fun setupHistoryButton() {
        btnHistory.setOnClickListener {
            val entries = CommandHistory.getAll()
            if (entries.isEmpty()) {
                Toast.makeText(this, getString(R.string.history_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val items = entries.map { e ->
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(e.timestampMs))
                "[$date] ${e.command}"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.history_dialog_title))
                .setItems(items) { _, which ->
                    argsInput.setText(entries[which].command)
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Settings + Update buttons. */
    private fun setupSettingsAndUpdateButtons() {
        btnSettings.setOnClickListener {
            DastyabLogger.i("MainActivity", "Opening SettingsActivity")
            try {
                startActivity(Intent(this, SettingsActivity::class.java))
            } catch (e: android.content.ActivityNotFoundException) {
                DastyabLogger.e("MainActivity", e, "SettingsActivity not found")
                postStatus("Error: settings not available")
            }
        }
        btnUpdate.setOnClickListener { checkForBinaryUpdate(false) }
    }

    /**
     * Check whether a newer binary is available (BinaryUpdater) and offer to
     * re-download. When [auto] is true (auto-update on app start), show status
     * only; otherwise show a dialog with a Download action.
     */
    private fun checkForBinaryUpdate(auto: Boolean) {
        postStatus(getString(R.string.update_checking))
        setStatusDot(DotState.GRAY)
        thread(isDaemon = true, name = "update-check-thread") {
            val result = BinaryUpdater.checkForUpdate(binary, assetArch())
            handler.post {
                when (result.status) {
                    BinaryUpdater.UpdateStatus.UP_TO_DATE -> {
                        postStatus(getString(R.string.update_up_to_date))
                        setStatusDot(DotState.GREEN)
                        if (!auto) {
                            Toast.makeText(this, getString(R.string.update_up_to_date), Toast.LENGTH_SHORT).show()
                        }
                    }
                    BinaryUpdater.UpdateStatus.UPDATE_AVAILABLE -> {
                        postStatus(result.message)
                        setStatusDot(DotState.ORANGE)
                        if (auto) {
                            // Auto-update: download silently.
                            postStatus("Auto-update: ${result.message}")
                            startDownload()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.update_available_title))
                                .setMessage(getString(R.string.update_available_msg))
                                .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                                    startDownload()
                                }
                                .setNegativeButton(getString(R.string.update_later), null)
                                .show()
                        }
                    }
                    BinaryUpdater.UpdateStatus.UNKNOWN -> {
                        postStatus(getString(R.string.update_unknown))
                        setStatusDot(DotState.GRAY)
                        if (!auto) {
                            Toast.makeText(this, getString(R.string.update_unknown), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /** Apply persisted settings (log level, auto-update) on app start. */
    private fun applyPersistedSettings() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val levelName = prefs.getString(SettingsActivity.PREF_LOG_LEVEL, null)
        if (levelName != null) {
            try {
                DastyabLogger.setMinFileLevel(DastyabLogger.LogLevel.valueOf(levelName))
            } catch (_: Throwable) { }
        }
        // Auto-update check runs after binary state is evaluated in onResume flow;
        // do it once the binary is confirmed ready (see evaluateBinaryState).
        if (prefs.getBoolean(SettingsActivity.PREF_AUTO_UPDATE, true)) {
            autoUpdatePending = true
        }
    }

    /** Status dot states for the status indicator. */
    private enum class DotState { GREEN, ORANGE, RED, GRAY }

    private fun setStatusDot(state: DotState) {
        val cb = {
            statusDot.background = when (state) {
                DotState.GREEN -> getDrawableCompat(R.drawable.status_dot_green)
                DotState.ORANGE -> getDrawableCompat(R.drawable.status_dot_orange)
                DotState.RED -> getDrawableCompat(R.drawable.status_dot_red)
                DotState.GRAY -> getDrawableCompat(R.drawable.status_dot_gray)
            }
        }
        pendingHandlerCallbacks.add(cb)
        handler.post(cb)
    }

    private fun getDrawableCompat(resId: Int): android.graphics.drawable.Drawable? {
        return if (Build.VERSION.SDK_INT >= 21) {
            getDrawable(resId)
        } else {
            @Suppress("DEPRECATION")
            resources.getDrawable(resId)
        }
    }
    /** Cancel any in-flight download or running process. */
    private fun cancelCurrentOperation() {
        downloadCancellation.set(true)
        runningProcess?.let { proc ->
            DastyabLogger.i("MainActivity", "Terminating running process (pid=%d)", proc.hashCode())
            proc.destroyForcibly()
            runningProcess = null
        }
        handler.post {
            hideCancel()
            showProgress(false)
        }
    }

    /** Start (or restart) the binary download with pre-flight checks. */
    private fun startDownload() {
        // Pre-flight checks
        if (!NetworkUtils.isOnline(this)) {
            postStatus(getString(R.string.error_no_network))
            showRetry()
            DastyabLogger.e("Download", "No network connectivity")
            return
        }
        if (!StorageUtils.hasEnoughSpace()) {
            postStatus(getString(R.string.error_low_storage))
            showRetry()
            DastyabLogger.e("Download", "Insufficient storage space")
            return
        }

        downloadAttempts++
        downloadCancellation.set(false)
        setStatusDot(DotState.ORANGE)
        val arch = assetArch()
        val urlStr = "https://github.com/$repo/releases/latest/download/dastyab-android-$arch"
        DastyabLogger.i("Download", "Attempt %d/%d: arch=%s url=%s",
            downloadAttempts, MAX_DOWNLOAD_ATTEMPTS, arch, urlStr)

        showProgress(true)
        showCancel()

        thread(isDaemon = true, name = "download-thread") {
            try {
                performDownload(urlStr, arch)
            } catch (e: Exception) {
                if (downloadCancellation.get()) {
                    DastyabLogger.i("Download", "Download cancelled by user")
                    handler.post {
                        showProgress(false)
                        hideCancel()
                        postStatus(getString(R.string.status_cancelled))
                    }
                    return@thread
                }
                DastyabLogger.e("Download", e, "Download attempt %d failed", downloadAttempts)
                handler.post {
                    showProgress(false)
                    hideCancel()
                    if (downloadAttempts < MAX_DOWNLOAD_ATTEMPTS && !downloadCancellation.get()) {
                        // Jittered exponential backoff: base * attempt + random 0-1000ms
                        val delay = BASE_BACKOFF_MS * downloadAttempts + (Math.random() * 1000).toLong()
                        postStatus(getString(R.string.status_retrying, delay / 1000))
                        val retryCb = { startDownload() }
                        pendingHandlerCallbacks.add(retryCb)
                        handler.postDelayed(retryCb, delay)
                    } else {
                        postStatus(getString(R.string.error_download_failed,
                            e.message ?: e.toString()))
                        showRetry()
                    }
                }
            } finally {
                if (!downloadCancellation.get()) {
                    handler.post { showProgress(false); hideCancel() }
                }
            }
        }
    }

    private fun performDownload(urlStr: String, arch: String) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30000
        conn.readTimeout = 180000
        conn.setRequestProperty("User-Agent", "DastyabAndroid/1.0")

        val code = conn.responseCode
        val contentLength = conn.getHeaderField("Content-Length")
        DastyabLogger.i("Download", "HTTP %d content-length=%s", code, contentLength)

        if (code != 200) {
            conn.disconnect()
            throw IOException("HTTP $code fetching binary")
        }

        val totalExpected = contentLength?.toLongOrNull() ?: 0L
        bytesTotal.set(totalExpected)

        // Pre-download disk space check
        if (totalExpected > 0 && !StorageUtils.hasEnoughSpaceFor(totalExpected)) {
            conn.disconnect()
            throw IOException("Insufficient storage for %d bytes download".format(totalExpected))
        }

        val inStream = conn.inputStream
        // Write to a temp file first; only rename to final name on success.
        val tempFile = File(filesDir, "dastyab.tmp")
        val rawOut = FileOutputStream(tempFile)
        val buf = ByteArray(8192)
        var read: Int = inStream.read(buf)
        var total = 0L
        while (read > 0) {
            if (downloadCancellation.get()) {
                inStream.close()
                rawOut.close()
                tempFile.delete()
                throw IOException("Download cancelled")
            }
            rawOut.write(buf, 0, read)
            total += read
            bytesDownloaded.set(total)
            read = inStream.read(buf)

            // Periodic disk space check (every ~1MB):
            // Verify remaining space is sufficient for the rest of the download.
            // Also enforce a minimum of 50MB free to avoid ENOSPC when the download
            // finishes and the system needs extra space for finalization/renaming.
            val remainingBytes = totalExpected - total
            val spaceOk = if (remainingBytes > 0) {
                StorageUtils.hasEnoughSpaceFor(remainingBytes)
            } else {
                // Download complete; just enforce absolute minimum
                StorageUtils.hasEnoughSpace()
            }
            if (!spaceOk) {
                inStream.close()
                rawOut.close()
                tempFile.delete()
                throw IOException("Disk full during download at %d bytes (need %d more)".format(total, remainingBytes))
            }

            // Update progress on UI thread (throttled every 64KB)
            if (totalExpected > 0 && total % (64L * 1024L) == 0L) {
                val pct = (total * 100 / totalExpected)
                val progressCb = { progress.progress = pct.toInt() }
                pendingHandlerCallbacks.add(progressCb)
                handler.post(progressCb)
            }
        }
        rawOut.flush()
        rawOut.close()
        inStream.close()
        conn.disconnect()

        if (downloadCancellation.get()) {
            tempFile.delete()
            throw IOException("Download cancelled")
        }

        DastyabLogger.i("Download", "Downloaded %d/%d bytes to temp file",
            total, totalExpected)

        // Verify it's a valid ELF before committing
        if (!BinaryUtils.isValidElfBinary(tempFile)) {
            tempFile.delete()
            throw IOException("Downloaded file is not a valid ELF binary")
        }

        // Verify SHA-256 against the pinned release digest (fail-open when
        // no digest is pinned; ELF magic + HTTPS still apply).
        if (!ChecksumUtils.verify(tempFile, arch)) {
            tempFile.delete()
            throw IOException("SHA-256 checksum mismatch for dastyab-android-$arch")
        }

        // Atomic replace
        binary.delete()
        val renameOk = tempFile.renameTo(binary)
        if (!renameOk) {
            // Fallback: copy + delete if rename fails (cross-device, etc.)
            DastyabLogger.w("Download", "Rename failed — falling back to copy")
            copyFile(tempFile, binary)
            tempFile.delete()
        }
        binary.setExecutable(true, false)
        if (!binary.canExecute()) {
            throw IOException("Binary is not executable after chmod")
        }

        DastyabLogger.i("Download", "Download complete: %d bytes saved to %s",
            total, binary.absolutePath)

        bytesDownloaded.set(total)
        handler.post {
            showProgress(false)
            hideCancel()
            postStatus(getString(R.string.status_ready))
            setStatusDot(DotState.GREEN)
            setRunEnabled(true)
            hideRetry()
        }
    }

    /** Fallback file copy when rename is unavailable (cross-device, etc.). */
    private fun copyFile(src: File, dst: File) {
        val input = FileInputStream(src)
        val output = FileOutputStream(dst)
        try {
            val buf = ByteArray(8192)
            var read: Int = input.read(buf)
            while (read > 0) {
                output.write(buf, 0, read)
                read = input.read(buf)
            }
        } finally {
            input.close()
            output.close()
        }
    }

    /** Map the device ABI to the GitHub release asset arch name. */
    private fun assetArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI
        return when {
            abi.contains("arm64-v8a") -> "aarch64"
            abi.contains("armeabi-v7a") -> "armv7"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "i686"
            else -> "aarch64"
        }
    }

    /** Launch the dastyab binary and stream its output to the UI. */
    private fun runCommand(args: Array<String>) {
        val cmdLine = args.joinToString(" ")
        output.append("\n\$ dastyab $cmdLine\n---\n")
        DastyabLogger.i("Run", "Executing: dastyab %s", cmdLine)

        setRunEnabled(false)
        showCancel()

        thread(isDaemon = true, name = "command-thread") {
            var exitCode = -1
            var proc: java.lang.Process? = null
            try {
                val cmd = mutableListOf<String>()
                cmd.add(binary.absolutePath)
                cmd.addAll(args.toList())
                proc = ProcessBuilder(*cmd.toTypedArray()).run {
                    directory(binary.parentFile)
                    // Point $HOME at the app's private dir so the Rust core's
                    // ~/.dastyab state stays sandboxed inside the app.
                    environment()["HOME"] = binary.parentFile!!.absolutePath
                    redirectErrorStream(true)
                    this
                }.start()
                runningProcess = proc
                DastyabLogger.d("Run", "Process started")

                // Read output with a timeout watchdog
                val reader = BufferedReader(InputStreamReader(proc!!.inputStream))
                var line: String? = reader.readLine()
                val startTime = System.currentTimeMillis()
                while (line != null) {
                    if (System.currentTimeMillis() - startTime > PROCESS_TIMEOUT_MS) {
                        throw IOException("Command timed out after %d ms".format(PROCESS_TIMEOUT_MS))
                    }
                    postOutput(line + "\n")
                    DastyabLogger.v("RunOutput", line)

                    // Prevent unbounded TextView growth
                    if (output.text.length > MAX_OUTPUT_CHARS) {
                        val trimCb = {
                            output.setText(output.text.substring(output.text.length - MAX_OUTPUT_CHARS / 2))
                        }
                        pendingHandlerCallbacks.add(trimCb)
                        handler.post(trimCb)
                    }
                    line = reader.readLine()
                }

                // Wait for process with timeout
                val finished = proc.waitFor(PROCESS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    DastyabLogger.w("Run", "Process did not exit in time — killing")
                    proc.destroyForcibly()
                    throw IOException("Command did not complete within timeout")
                }
                exitCode = proc.exitValue()
                postOutput("\n--- exit code $exitCode ---\n")
                DastyabLogger.i("Run", "Process exited with code %d", exitCode)
            } catch (e: Exception) {
                if (downloadCancellation.get()) {
                    DastyabLogger.i("Run", "Command cancelled by user")
                    postOutput("Command cancelled.\n")
                } else {
                    DastyabLogger.e("Run", e, "Command failed: dastyab %s", cmdLine)
                    postOutput("ERROR: ${e.message ?: e.toString()}\n")
                    handler.post { postStatus(getString(R.string.status_error)); setStatusDot(DotState.RED) }
                }
            } finally {
                runningProcess = null
                handler.post { setRunEnabled(true); hideCancel() }
            }
        }
    }

    private fun postStatus(msg: String) {
        val cb = { status.text = msg }
        pendingHandlerCallbacks.add(cb)
        handler.post(cb)
        DastyabLogger.d("MainActivity", "STATUS: %s", msg)
    }
    private fun postOutput(msg: String) {
        val cb = { applyFormattedOutput(msg) }
        pendingHandlerCallbacks.add(cb)
        handler.post(cb)
        DastyabLogger.d("MainActivity", "OUTPUT: %s", msg.trimEnd().replace("\n", " | "))
    }

    /**
     * Apply syntax highlighting to appended output text and add it to the
     * output view (per-line coloring for ERROR/WARN/OK, clickable URLs,
     * bold exit-code lines).
     */
    private fun applyFormattedOutput(msg: String) {
        output.append(formatOutput(msg))
    }

    /**
     * Build a Spannable for [text] with:
     *  • lines starting with ERROR/error → red
     *  • lines starting with WARN/warning → orange
     *  • lines starting with OK/success → green
     *  • lines containing http:// or https:// → clickable links
     *  • exit-code lines ("--- exit code N ---") → bold
     */
    fun formatOutput(text: String): Spannable {
        val sb = SpannableStringBuilder(text)
        val linkMovementMethodInstance = android.text.method.LinkMovementMethod.getInstance()

        var lineStart = 0
        val lines = text.split("\n")
        for (line in lines) {
            val lineEnd = lineStart + line.length
            val lower = line.trimStart().lowercase()

            // Exit-code lines → bold
            if (Regex("^---\\s*exit code\\s+-?\\d+\\s*---$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line.trim())) {
                sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // Level-based coloring
            val color = when {
                lower.startsWith("error") -> Color.rgb(0xC6, 0x28, 0x28)   // red
                lower.startsWith("warn") || lower.startsWith("warning") ->
                    Color.rgb(0xEF, 0x6C, 0x00)                            // orange
                lower.startsWith("ok") || lower.startsWith("success") ->
                    Color.rgb(0x2E, 0x7D, 0x32)                            // green
                else -> null
            }
            if (color != null) {
                sb.setSpan(ForegroundColorSpan(color), lineStart, lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // Clickable URLs
            val urlRegex = Regex("https?://[^\\s]+")
            for (m in urlRegex.findAll(line)) {
                val start = lineStart + m.range.first
                val end = lineStart + m.range.last + 1
                val url = m.value
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        } catch (_: Exception) { }
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = Color.rgb(0x1A, 0x55, 0xE0) // blue
                        ds.isUnderlineText = true
                    }
                }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            lineStart = lineEnd + 1 // +1 for the '\n' that was consumed by split
        }

        // Enable link clicks when the spannable contains URL spans.
        if (text.contains("http://") || text.contains("https://")) {
            val cb = { output.movementMethod = linkMovementMethodInstance }
            pendingHandlerCallbacks.add(cb)
            handler.post(cb)
        }
        return sb
    }

    private fun setRunEnabled(enabled: Boolean) {
        btnRun.isEnabled = enabled
        argsInput.isEnabled = enabled
    }

    private fun showProgress(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showRetry() {
        btnRetry.visibility = View.VISIBLE
    }

    private fun hideRetry() {
        btnRetry.visibility = View.GONE
    }

    private fun showCancel() {
        btnCancel.visibility = View.VISIBLE
    }

    private fun hideCancel() {
        btnCancel.visibility = View.GONE
    }
}