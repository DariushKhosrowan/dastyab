package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * NetworkToolboxActivity — 7 on-device anti-censorship & network diagnostic utilities:
 *  1. Exit IP, Country, Colo & TLS Trace
 *  2. DNS Poisoning Detector (System DNS vs DoH)
 *  3. TLS / SNI Handshake & Certificate Inspector
 *  4. TCP Port-443 Latency Ping (3-sample average)
 *  5. Base64 Subscription Encoder / Decoder
 *  6. CIDR IPv4 Candidate Generator
 *  7. VLESS URI Decoder & Clean-IP Rewriter
 */
class NetworkToolboxActivity : Activity() {

    private lateinit var progress: ProgressBar
    private lateinit var etHost: EditText
    private lateinit var tvOutput: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_toolbox)

        progress = findViewById(R.id.toolboxProgress)
        etHost = findViewById(R.id.etToolboxHost)
        tvOutput = findViewById(R.id.tvToolboxOutput)

        findViewById<Button>(R.id.btnPresetAiStudio).setOnClickListener { etHost.setText("aistudio.google.com") }
        findViewById<Button>(R.id.btnPresetFlow).setOnClickListener { etHost.setText("flow.google.com") }
        findViewById<Button>(R.id.btnPresetGithub).setOnClickListener { etHost.setText("raw.githubusercontent.com") }
        findViewById<Button>(R.id.btnPresetTelegram).setOnClickListener { etHost.setText("t.me") }

        findViewById<Button>(R.id.btnToolExitTrace).setOnClickListener { runExitTrace() }
        findViewById<Button>(R.id.btnToolDnsPoison).setOnClickListener { runDnsPoisonCheck() }
        findViewById<Button>(R.id.btnToolTlsInspect).setOnClickListener { runTlsInspector() }
        findViewById<Button>(R.id.btnToolTcpPing).setOnClickListener { runTcpPing() }
        findViewById<Button>(R.id.btnToolBase64).setOnClickListener { showBase64Dialog() }
        findViewById<Button>(R.id.btnToolCidrGen).setOnClickListener { showCidrGeneratorDialog() }
        findViewById<Button>(R.id.btnToolUriInspect).setOnClickListener { showUriInspectorDialog() }
        findViewById<Button>(R.id.btnToolboxBack).setOnClickListener { finish() }
    }

    private fun targetHost(): String = etHost.text.toString().trim().ifEmpty { "aistudio.google.com" }

    private fun runExitTrace() {
        setBusy(true, "Fetching Exit IP & Cloudflare Edge Trace…\n")
        thread(isDaemon = true, name = "dastyab-trace") {
            val trace = NetworkTools.fetchExitTrace()
            handler.post {
                setBusy(false)
                if (trace == null) {
                    tvOutput.text = "ERROR: Could not reach Cloudflare trace.\nTip: Activate Smart VPN or Emergency DNS Blackout VPN."
                } else {
                    tvOutput.text = buildString {
                        append("=== EXIT IP & GEO TRACE ===\n")
                        append("Public IP : ${trace.ip}\n")
                        append("Country   : ${trace.location}\n")
                        append("Edge Colo : ${trace.colo}\n")
                        append("TLS       : ${trace.tls}\n")
                        append("HTTP      : ${trace.http}\n")
                        append("WARP      : ${trace.warp}\n")
                    }
                }
            }
        }
    }

    private fun runDnsPoisonCheck() {
        val host = targetHost()
        val dohUrl = TunnelConfig.load(this).dohUrl
        setBusy(true, "Checking DNS poisoning for $host…\n")
        thread(isDaemon = true, name = "dastyab-dns-check") {
            val res = NetworkTools.checkDnsPoisoning(host, dohUrl)
            handler.post {
                setBusy(false)
                tvOutput.text = buildString {
                    append("=== DNS POISONING DETECTOR ===\n")
                    append("Domain       : ${res.domain}\n")
                    append("System IPs   : ${if (res.systemIps.isEmpty()) "(failed)" else res.systemIps.joinToString()}\n")
                    append("DoH Reachable: ${res.dohReachable} (${if (res.dohLatencyMs >= 0) "${res.dohLatencyMs}ms" else "blocked"})\n")
                    append("Poisoned?    : ${if (res.likelyPoisoned) "LIKELY YES" else "NO"}\n\n")
                    append("Verdict:\n${res.verdict}\n")
                }
            }
        }
    }

    private fun runTlsInspector() {
        val host = targetHost()
        setBusy(true, "Inspecting TLS / SNI handshake & certificate for $host:443…\n")
        thread(isDaemon = true, name = "dastyab-tls") {
            val res = NetworkTools.inspectTlsHandshake(host, 443)
            handler.post {
                setBusy(false)
                tvOutput.text = if (!res.connected) {
                    "=== TLS / SNI INSPECTOR ===\nTarget: ${res.host}:${res.port}\nStatus: FAILED / RESET\nError : ${res.error}\n\nTip: SNI/TLS DPI reset detected. Use Clean IP Scout or DNS Blackout VPN."
                } else {
                    buildString {
                        append("=== TLS / SNI INSPECTOR ===\n")
                        append("Target  : ${res.host}:${res.port}\n")
                        append("Latency : ${res.latencyMs} ms\n")
                        append("Protocol: ${res.protocol}\n")
                        append("Cipher  : ${res.cipherSuite}\n")
                        append("Subject : ${res.certSubject}\n")
                        append("Issuer  : ${res.certIssuer}\n")
                    }
                }
            }
        }
    }

    private fun runTcpPing() {
        val host = targetHost()
        setBusy(true, "Running 3x TCP port-443 latency probes to $host…\n")
        thread(isDaemon = true, name = "dastyab-ping") {
            val samples = (1..3).map { CleanEndpointScout.probeTcpLatency(host, 443, 2500) }
            handler.post {
                setBusy(false)
                val valid = samples.filter { it >= 0 }
                val avg = if (valid.isNotEmpty()) "${valid.average().toLong()} ms" else "FAILED"
                tvOutput.text = buildString {
                    append("=== TCP PING ($host:443) ===\n")
                    samples.forEachIndexed { idx, ms ->
                        append("Probe #${idx + 1}: ${if (ms >= 0) "${ms} ms" else "TIMEOUT"}\n")
                    }
                    append("Average : $avg (${valid.size}/3 succeeded)\n")
                }
            }
        }
    }

    private fun showBase64Dialog() {
        val input = EditText(this).apply {
            hint = "Paste Base64 subscription blob OR plain proxy links"
            setSingleLine(false)
            maxLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle("Base64 Subscription Codec")
            .setView(input)
            .setPositiveButton("Decode") { _, _ ->
                val decoded = NetworkTools.decodeBase64(input.text.toString())
                if (decoded == null) {
                    Toast.makeText(this, "Invalid Base64 text.", Toast.LENGTH_SHORT).show()
                } else {
                    tvOutput.text = "=== DECODED SUBSCRIPTION ===\n$decoded"
                    copyToClipboard("Decoded Sub", decoded)
                }
            }
            .setNeutralButton("Encode") { _, _ ->
                val encoded = NetworkTools.encodeBase64(input.text.toString())
                tvOutput.text = "=== ENCODED BASE64 ===\n$encoded"
                copyToClipboard("Encoded Sub", encoded)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCidrGeneratorDialog() {
        val input = EditText(this).apply {
            hint = "IPv4 CIDR (e.g. 104.16.0.0/16)"
            setText("104.16.0.0/16")
        }
        AlertDialog.Builder(this)
            .setTitle("CIDR IPv4 Candidate Generator")
            .setView(input)
            .setPositiveButton("Generate 15 IPs") { _, _ ->
                val cidr = input.text.toString().trim()
                val ips = CleanEndpointScout.sampleIpsFromCidr(cidr, 15)
                if (ips.isEmpty()) {
                    Toast.makeText(this, "Invalid IPv4 CIDR (prefix must be /8../30).", Toast.LENGTH_SHORT).show()
                } else {
                    val text = ips.joinToString("\n")
                    tvOutput.text = "=== GENERATED IPs FROM $cidr ===\n$text"
                    copyToClipboard("CIDR IPs", text)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUriInspectorDialog() {
        val current = TunnelConfig.load(this).vlessUri
        val input = EditText(this).apply {
            hint = "Paste vless:// URI to inspect"
            setText(current)
            setSingleLine(false)
            maxLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("VLESS URI Inspector")
            .setView(input)
            .setPositiveButton("Inspect") { _, _ ->
                val parsed = TunnelConfig.parseVlessUri(input.text.toString())
                if (parsed == null) {
                    Toast.makeText(this, "Invalid vless:// URI.", Toast.LENGTH_SHORT).show()
                } else {
                    tvOutput.text = buildString {
                        append("=== VLESS URI DECODED ===\n")
                        append("Label    : ${parsed.label.ifBlank { "(none)" }}\n")
                        append("UUID     : ${parsed.uuid}\n")
                        append("Host     : ${parsed.host}\n")
                        append("Port     : ${parsed.port}\n")
                        append("SNI      : ${parsed.sni}\n")
                        append("Path     : ${parsed.path}\n")
                        append("Transport: ${parsed.transport}\n")
                        append("Security : ${parsed.security}\n")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Result copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun setBusy(busy: Boolean, initialMsg: String? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (initialMsg != null) tvOutput.text = initialMsg
    }
}
