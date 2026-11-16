package com.dastyab.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import com.dastyab.app.DastyabLogger

/**
 * Dastyab for Android — fastest possible implementation.
 *
 * Strategy: reuse the existing Rust `dastyab` CLI unchanged. On first launch the
 * app downloads the pre-built `dastyab-android-<arch>` binary from GitHub
 * Releases into the app's private files dir, chmods it executable, and shells
 * out to it via ProcessBuilder, streaming stdout/stderr into a scrollable view.
 *
 * All state (~/.dastyab) lands under the app's files dir (we override $HOME), so
 * everything stays sandboxed — tokens, profiles, clean IPs, etc.
 *
 * No runtime permissions are required: INTERNET is a normal manifest permission,
 * and the app only touches its own private storage.
 *
 * Build:
 *   cd android
 *   ./gradlew assembleDebug   (needs Android SDK + Gradle; or open in Android Studio)
 *
 * Prerequisite for the runtime download to succeed: the dastyab-android-<arch>
 * asset must exist on a GitHub Release (pushing a `v*` tag triggers CI which
 * builds all four Android ABIs). Until then, first launch reports "not ready."
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var argsInput: EditText
    private lateinit var btnRun: Button
    private lateinit var btnClear: Button

    private val handler = Handler(Looper.getMainLooper())
    private val repo = "DastYab/dastyab"
    private lateinit var binary: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        output = findViewById(R.id.output)
        argsInput = findViewById(R.id.argsInput)
        btnRun = findViewById(R.id.btnRun)
        btnClear = findViewById(R.id.btnClear)

        binary = File(filesDir, "dastyab")

        DastyabLogger.init(filesDir)
        DastyabLogger.i("MainActivity", "onCreate — binary=%s, exists=%s", binary.absolutePath, binary.exists())

        buildQuickButtons()

        btnRun.setOnClickListener { runTyped() }
        btnClear.setOnClickListener { output.setText("") }

        if (binary.exists() && binary.canExecute()) {
            status.text = getString(R.string.status_ready)
        } else {
            status.text = getString(R.string.status_downloading)
            downloadBinary()
        }
    }

    /** Read the free-form args field and run the binary. */
    private fun runTyped() {
        val s = argsInput.text.toString().trim()
        if (s.isEmpty()) return
        if (!(binary.exists() && binary.canExecute())) {
            status.text = getString(R.string.status_binary_not_ready)
            return
        }
        runCommand(s.splitWhitespace().toTypedArray())
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
                if (binary.exists() && binary.canExecute()) runCommand(arrayOf(c))
                else status.text = getString(R.string.status_binary_not_ready)
            }
            container.addView(b)
        }
    }
    /** Download the arch-specific dastyab Android binary from GitHub Releases. */
    private fun downloadBinary() {
        val arch = assetArch()
        val urlStr = "https://github.com/$repo/releases/latest/download/dastyab-android-$arch"
        DastyabLogger.i("Download", "Starting binary download: arch=%s url=%s", arch, urlStr)
        thread(isDaemon = true) {
            try {
                postStatus("Downloading dastyab-android-$arch …")
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.setRequestProperty("User-Agent", "DastyabAndroid/1.0")
                val code = conn.responseCode
                val contentLength = conn.headerField("Content-Length")
                DastyabLogger.i("Download", "HTTP %d content-length=%s", code, contentLength)
                if (code != 200) {
                    postStatus("HTTP $code fetching binary")
                    DastyabLogger.e("Download", "HTTP %d fetching binary from %s", code, urlStr)
                    conn.disconnect()
                    return@thread
                }
                val in = conn.inputStream
                val out = FileOutputStream(binary)
                val buf = ByteArray(8192)
                var read = in.read(buf)
                var total = 0L
                while (read > 0) {
                    out.write(buf, 0, read)
                    total += read
                    read = in.read(buf)
                }
                out.flush()
                out.close()
                in.close()
                conn.disconnect()
                binary.setExecutable(true, false)
                DastyabLogger.i("Download", "Download complete: %d bytes saved to %s", total, binary.absolutePath)
                postStatus(getString(R.string.status_ready))
            } catch (e: Exception) {
                DastyabLogger.e("Download", e, "Download failed for %s", urlStr)
                postStatus("Download failed: ${e.message ?: e.toString()}")
            }
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
        thread(isDaemon = true) {
            try {
                val cmd = mutableListOf<String>()
                cmd.add(binary.absolutePath)
                cmd.addAll(args.toList())
                val proc = ProcessBuilder(*cmd.toTypedArray()).run {
                    directory(binary.parentFile)
                    // Point $HOME at the app's private dir so the Rust core's
                    // ~/.dastyab state stays sandboxed inside the app.
                    environment()["HOME"] = binary.parentFile!!.absolutePath
                    redirectErrorStream(true)
                    this
                }.start()
                DastyabLogger.d("Run", "Process started: pid=%d", proc.hashCode())
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    postOutput(line + "\n")
                    DastyabLogger.v("RunOutput", line)
                }
                proc.waitFor()
                val exitCode = proc.exitValue()
                postOutput("\n--- exit code $exitCode ---\n")
                DastyabLogger.i("Run", "Process exited with code %d", exitCode)
            } catch (e: Exception) {
                DastyabLogger.e("Run", e, "Command failed: dastyab %s", cmdLine)
                postOutput("ERROR: ${e.message ?: e.toString()}\n")
            }
        }
    }

    private fun postStatus(msg: String) {
        handler.post { status.text = msg }
        DastyabLogger.d("MainActivity", "STATUS: %s", msg)
    }
    private fun postOutput(msg: String) {
        handler.post { output.append(msg) }
        DastyabLogger.d("MainActivity", "OUTPUT: %s", msg.trimEnd().replace("\n", " | "))
    }
}