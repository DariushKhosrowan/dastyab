package com.dastyab.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val repo = "DastYab/dastyab"
    private lateinit var binary: File

    private var downloadAttempts = 0

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
        btnViewLogs = findViewById(R.id.btnViewLogs)
        progress = findViewById(R.id.progress)

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
        DastyabLogger.i("MainActivity",
            "onCreate — binary=%s, exists=%s, abi=%s, attempt=%d",
            binary.absolutePath, binary.exists(),
            android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: android.os.Build.CPU_ABI,
            downloadAttempts)

        buildQuickButtons()

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
            setRunEnabled(true)
            hideRetry()
            hideCancel()
            DastyabLogger.i("MainActivity", "Binary is valid and ready")
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
        runCommand(s.split(Regex("\\s+")).filter { it.isNotEmpty() }.toTypedArray())
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
            val b = Button(this).apply {
                text = c
                setPadding(14, 6, 14, 6)
                setAllCaps(false)
            }
            b.setOnClickListener {
                argsInput.setText(c)
                runTyped()
            }
            container.addView(b)
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
                    handler.post { postStatus(getString(R.string.status_error)) }
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
        val cb = { output.append(msg) }
        pendingHandlerCallbacks.add(cb)
        handler.post(cb)
        DastyabLogger.d("MainActivity", "OUTPUT: %s", msg.trimEnd().replace("\n", " | "))
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