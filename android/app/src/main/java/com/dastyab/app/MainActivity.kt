package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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

/** Runs the APK's bundled CLI with private app storage for its state.
 * Android's sandbox and available system tools limit which CLI features can run.
 *
 * The main screen is simple and modern: big tap buttons for the most common
 * actions, no typing required. The raw command input lives in an "Advanced"
 * section that is hidden by default.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var argsInput: EditText
    private lateinit var btnRun: Button
    private lateinit var btnCancel: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusDot: View
    private lateinit var historySpinner: Spinner
    private lateinit var binary: File

    // Simple action buttons (main screen)
    private lateinit var btnSimpleAistudio: Button
    private lateinit var btnSimpleVerify: Button
    private lateinit var btnSimpleAccounts: Button
    private lateinit var btnSimpleProfiles: Button
    private lateinit var btnSimpleTokens: Button
    private lateinit var btnSimpleScan: Button

    // Advanced section toggle
    private lateinit var btnShowAdvanced: Button
    private lateinit var btnHideAdvanced: Button
    private lateinit var advancedSection: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val uiLock = Any()
    private var destroyed = false // Protected by uiLock, including callback enqueue/removal.
    private val operation = AtomicReference<Operation?>(null)

    private class Operation {
        val cancelled = AtomicBoolean(false)
        val process = AtomicReference<Process?>(null)
    }

    companion object {
        private const val PROCESS_TIMEOUT_MS = 300_000L
        private const val MAX_OUTPUT_CHARS = 50_000
        private const val MAX_ARGS_LENGTH = 4096
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DastyabLogger.init(filesDir)
        CrashHandler.install(applicationContext, filesDir)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        output = findViewById(R.id.output)
        argsInput = findViewById(R.id.argsInput)
        btnRun = findViewById(R.id.btnRun)
        btnCancel = findViewById(R.id.btnCancel)
        progress = findViewById(R.id.progress)
        statusDot = findViewById(R.id.statusDot)
        historySpinner = findViewById(R.id.historySpinner)
        binary = File(applicationInfo.nativeLibraryDir, "libdastyab.so")

        // Simple action buttons
        btnSimpleAistudio = findViewById(R.id.btnSimpleAistudio)
        btnSimpleVerify = findViewById(R.id.btnSimpleVerify)
        btnSimpleAccounts = findViewById(R.id.btnSimpleAccounts)
        btnSimpleProfiles = findViewById(R.id.btnSimpleProfiles)
        btnSimpleTokens = findViewById(R.id.btnSimpleTokens)
        btnSimpleScan = findViewById(R.id.btnSimpleScan)

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

        // ── Simple action taps (no typing) ──
        btnSimpleAistudio.setOnClickListener {
            if (operation.get() == null) handleAistudio("https://aistudio.google.com/")
        }
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

        // ── Advanced toggle ──
        btnShowAdvanced.setOnClickListener { showAdvanced() }
        btnHideAdvanced.setOnClickListener { hideAdvanced() }

        // ── Run / cancel ──
        btnRun.setOnClickListener { runTyped() }
        btnCancel.setOnClickListener { cancelCurrentOperation() }

        findViewById<Button>(R.id.btnClear).setOnClickListener { output.text = "" }
        findViewById<Button>(R.id.btnRetry).visibility = View.GONE

        findViewById<Button>(R.id.btnViewLogs).setOnClickListener {
            openActivity(LogViewerActivity::class.java)
        }
        findViewById<Button>(R.id.btnAbout).setOnClickListener { openActivity(AboutActivity::class.java) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { openActivity(SettingsActivity::class.java) }
        findViewById<Button>(R.id.btnUpdate).apply {
            text = "Releases"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Dastyab/dastyab/releases")))
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

        buildQuickButtons(R.id.quickButtons, listOf("--help", "--version", "verify --help",
            "accounts --help", "scan --help", "deploy --help", "configs --help", "doctor --help"))
        buildQuickButtons(R.id.quickButtonsProfiles, listOf("profiles --help", "profile-create --help",
            "profile-import --help", "profile-export --help", "token-add --help",
            "token-list --help", "backup --help", "export --help"))
        setupHistorySpinner()
        setBusy(false)
        evaluateBinaryState()
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

        // Android compatibility check before running the binary.
        when (val verdict = CommandCompat.check(args)) {
            is CommandCompat.Verdict.OpenAistudio -> {
                handleAistudio(verdict.url)
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

        runCommand(args, label)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingOutput", output.text.toString())
    }

    override fun onDestroy() {
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
        if (!binary.isFile || !binary.canExecute()) {
            evaluateBinaryState()
            return
        }
        // Android compatibility: some CLI subcommands need external helpers
        // (Python, xray, curl) that don't exist on Android. Handle or block
        // them with a friendly message instead of a cryptic "Permission denied".
        when (val verdict = CommandCompat.check(args)) {
            is CommandCompat.Verdict.OpenAistudio -> {
                handleAistudio(verdict.url)
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
        runCommand(args, text)
    }

    /** Open Google AI Studio in the system browser. Works on Android without
     * Python, xray, or any tunnel — AI Studio is reachable directly from most
     * connections (sign in + API all work from your IP). */
    private fun handleAistudio(url: String) {
        postOutput("Opening Google AI Studio in your browser…\n")
        postStatus("Opening AI Studio…")
        setStatusDot(DotState.ORANGE)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            postOutput("✓ AI Studio opened. Sign in with your Google account — no tunnel or Python needed.\n")
            postStatus("AI Studio opened in browser")
            setStatusDot(DotState.GREEN)
        } catch (_: android.content.ActivityNotFoundException) {
            postOutput("✗ No browser available. Open AI Studio manually: $url\n")
            postStatus("No browser available")
            setStatusDot(DotState.RED)
        }
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
        // The worker retains the guard until process cleanup completes.
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
        // Capture all launch inputs on the main thread. Workers never read views.
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
                    // Cover cancellation between start() and publication of the child.
                    if (current.cancelled.get()) terminate(child)
                    child.outputStream.close() // This UI does not provide interactive stdin.
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
                    // Allow final output to drain, but never wait indefinitely for a pipe.
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
                // No further operation can start on a destroyed Activity.
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
                lower.startsWith("error") -> Color.rgb(0xC6, 0x28, 0x28)
                lower.startsWith("warn") -> Color.rgb(0xEF, 0x6C, 0x00)
                lower.startsWith("ok") || lower.startsWith("success") -> Color.rgb(0x2E, 0x7D, 0x32)
                else -> null
            }
            if (color != null) spans.setSpan(ForegroundColorSpan(color), offset, offset + line.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (Regex("^---\\s*exit code\\s+-?\\d+\s*---$", RegexOption.IGNORE_CASE).matches(line.trim())) {
                spans.setSpan(StyleSpan(Typeface.BOLD), offset, offset + line.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
