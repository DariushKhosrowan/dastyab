package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * GoogleAccessSheet — Unified interactive switcher for accessing Google AI Studio & Google Flow
 * from Android without geo-block or single-VPN collisions.
 *
 * Lets the user in 1 tap:
 *  1. Use External VPN (Lantern / system VPN running on top) without killing it
 *  2. Pick a live/preset Local Proxy Port (v2rayNG, NekoBox, Hiddify, Orbot, Clash, or custom)
 *     for Internal App Proxy multi-layering
 *  3. Start/Stop DastYab's built-in Local HTTP+SOCKS5 Proxy Port (`127.0.0.1:<port>`) and copy it
 *     for v2rayNG / Chrome
 *  4. Run a live Egress Geo + 151-Country Flow Gate + HTTPS Reachability test through the active path
 */
object GoogleAccessSheet {

    fun show(
        activity: Activity,
        targetTitle: String? = null,
        targetUrl: String? = null,
        onConfigChanged: () -> Unit = {}
    ) {
        if (!UiSafety.isActivityAlive(activity)) return

        val settings = TunnelConfig.load(activity)
        val externalVpn = NetworkUtils.hasActiveExternalVpn(activity)
        val bridgePort = LocalProxyService.activePort()
        val route = GeoBypassEngine.planRoute(
            settings = settings,
            externalVpnActive = externalVpn,
            dastyabVpnActive = DastyabVpnService.isConnected(),
            runningBridgePort = bridgePort,
            detectedListeningHttpPort = null
        )

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 1. Auto Smart Mode
        val autoMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.AUTO_SMART) "● " else ""
        options.add("${autoMark}1. Auto Smart (Recommended · Lantern / Proxy / VPN)")
        actions.add {
            TunnelConfig.saveGoogleProxyConfig(
                activity,
                TunnelConfig.GoogleAccessMode.AUTO_SMART,
                settings.upstreamProxyScheme,
                settings.upstreamProxyHost,
                settings.upstreamProxyPort,
                settings.localBridgePort,
                settings.autoStartLocalBridge
            )
            Toast.makeText(activity, "Mode: Auto Smart", Toast.LENGTH_SHORT).show()
            onConfigChanged()
        }

        // 2. External VPN / Lantern Pass-Through
        val extMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.EXTERNAL_VPN) "● " else ""
        val extBadge = if (externalVpn) " [VPN ACTIVE ✓]" else " [Turn on Lantern / VPN]"
        options.add("${extMark}2. External VPN (Lantern / System VPN on top)$extBadge")
        actions.add {
            if (DastyabVpnService.isConnected()) {
                DastyabVpnService.stop(activity)
            }
            TunnelConfig.saveGoogleProxyConfig(
                activity,
                TunnelConfig.GoogleAccessMode.EXTERNAL_VPN,
                TunnelConfig.ProxyScheme.DIRECT,
                settings.upstreamProxyHost,
                settings.upstreamProxyPort,
                settings.localBridgePort,
                false
            )
            onConfigChanged()
            if (!externalVpn) {
                promptOpenLanternOrVpnApp(activity)
            } else {
                Toast.makeText(activity, "Using active External VPN (Lantern / System)", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Internal App Proxy (scan & pick local proxy port while leaving OS VPN slot free!)
        val inAppMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.IN_APP_PROXY) "● " else ""
        options.add("${inAppMark}3. Internal App Proxy (Scan & Pick Port: v2rayNG / NekoBox / Custom)")
        actions.add {
            showLocalProxyPortPicker(activity, onConfigChanged)
        }

        // 4. Start / Manage DastYab Local Proxy Port (127.0.0.1:10850 for v2rayNG & multi-layering)
        val bridgeStatus = if (bridgePort != null) "ACTIVE on 127.0.0.1:$bridgePort" else "Stopped (Port ${settings.localBridgePort})"
        options.add("4. DastYab Local Proxy Port [$bridgeStatus] (For v2rayNG / Layered VPN)")
        actions.add {
            showLocalBridgeManagerDialog(activity, onConfigChanged)
        }

        // 5. DastYab VPN + Local Proxy Chain
        val chainMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY) "● " else ""
        options.add("${chainMark}5. DastYab VPN + Local Proxy Chain (Whole-Phone HTTP Proxy)")
        actions.add {
            TunnelConfig.saveGoogleProxyConfig(
                activity,
                TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY,
                if (settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.DIRECT) TunnelConfig.ProxyScheme.HTTP else settings.upstreamProxyScheme,
                settings.upstreamProxyHost,
                settings.upstreamProxyPort,
                settings.localBridgePort,
                settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5
            )
            if (settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5) {
                LocalProxyService.startSafely(activity)
            }
            onConfigChanged()
            Toast.makeText(activity, "Set DastYab VPN + Proxy Chain mode", Toast.LENGTH_SHORT).show()
        }

        // 6. Live Geo & Flow Country Check
        options.add("6. Run Live Geo IP & Flow 151-Country Gate Check")
        actions.add {
            runLiveGeoCheckDialog(activity)
        }

        // 7. Launch Lantern directly (if installed)
        val lanternInstalled = ConfigHarvester.detectFallbackTools(activity)
            .firstOrNull { it.packageNames.contains("org.getlantern.lantern") && it.isInstalled }
        if (lanternInstalled != null) {
            options.add("7. Open Lantern App (Connect VPN & Return)")
            actions.add {
                activity.packageManager.getLaunchIntentForPackage(lanternInstalled.installedPackage!!)?.let {
                    UiSafety.safeStartActivity(activity, it)
                }
            }
        }

        val headerText = buildString {
            append("Current Route: ${route.routeLabel}")
            append("\nExternal VPN (Lantern): ${if (externalVpn) "Active ✓" else "Not active"}")
            if (targetTitle != null && targetUrl != null) {
                append(" · Target: $targetTitle")
            }
        }
        val titleView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
            addView(TextView(activity).apply {
                text = "AI Studio & Flow Access Modes"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = headerText
                textSize = 12f
                setPadding(0, (4 * activity.resources.displayMetrics.density).toInt(), 0, 0)
            })
        }

        val builder = AlertDialog.Builder(activity)
            .setCustomTitle(titleView)
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)

        // Add subtle header note as neutral button to open full TunnelSettingsActivity
        builder.setNeutralButton("Full Settings") { _, _ ->
            UiSafety.safeStartActivity(activity, Intent(activity, TunnelSettingsActivity::class.java))
        }

        UiSafety.safeShowDialog(activity, builder)
    }

    fun showLocalProxyPortPicker(activity: Activity, onApplied: () -> Unit = {}) {
        if (!UiSafety.isActivityAlive(activity)) return
        val settings = TunnelConfig.load(activity)
        Toast.makeText(activity, "Scanning listening local proxy ports…", Toast.LENGTH_SHORT).show()

        thread(isDaemon = true, name = "dastyab-proxy-scan") {
            val active = GeoBypassEngine.discoverListeningProxies(timeoutMs = 150)
            val activePorts = active.associateBy { it.preset.port }
            activity.runOnUiThread {
                if (!UiSafety.isActivityAlive(activity)) return@runOnUiThread

                val items = mutableListOf<String>()
                val presets = GeoBypassEngine.KNOWN_LOCAL_PROXY_PRESETS
                for (p in presets) {
                    val live = activePorts[p.port]
                    val status = if (live != null) " [LISTENING ✓ ${live.latencyMs}ms]" else ""
                    items.add("${p.label}$status")
                }
                items.add("Custom Host : Port (HTTP or SOCKS5)…")

                UiSafety.safeShowDialog(
                    activity,
                    AlertDialog.Builder(activity)
                        .setTitle("Choose Local Proxy Port (Internal Proxy)")
                        .setItems(items.toTypedArray()) { _, idx ->
                            if (idx == presets.size) {
                                showCustomProxyDialog(activity, onApplied)
                            } else {
                                val picked = presets[idx]
                                val needsSocksTranslation = picked.scheme == TunnelConfig.ProxyScheme.SOCKS5
                                TunnelConfig.saveGoogleProxyConfig(
                                    context = activity,
                                    accessMode = TunnelConfig.GoogleAccessMode.IN_APP_PROXY,
                                    scheme = picked.scheme,
                                    host = picked.host,
                                    port = picked.port,
                                    localBridgePort = settings.localBridgePort,
                                    autoStartBridge = needsSocksTranslation
                                )
                                if (DastyabVpnService.isConnected()) {
                                    // Keep external VPN / avoid DastYab VpnService stealing slot if desired
                                    DastyabVpnService.stop(activity)
                                }
                                if (needsSocksTranslation) {
                                    LocalProxyService.startSafely(activity)
                                }
                                Toast.makeText(
                                    activity,
                                    "Internal Proxy set → ${picked.scheme.name} ${picked.host}:${picked.port}",
                                    Toast.LENGTH_LONG
                                ).show()
                                onApplied()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                )
            }
        }
    }

    fun showCustomProxyDialog(activity: Activity, onApplied: () -> Unit = {}) {
        if (!UiSafety.isActivityAlive(activity)) return
        val settings = TunnelConfig.load(activity)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val schemeSpinner = Spinner(activity).apply {
            val schemes = listOf(TunnelConfig.ProxyScheme.HTTP, TunnelConfig.ProxyScheme.SOCKS5)
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, schemes.map { it.label }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(if (settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5) 1 else 0)
        }
        val etHost = EditText(activity).apply {
            hint = "Proxy Host (default 127.0.0.1)"
            setText(settings.upstreamProxyHost)
            setSingleLine(true)
        }
        val etPort = EditText(activity).apply {
            hint = "Port (e.g. 10809, 10808, 2080, 9050)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.upstreamProxyPort.toString())
            setSingleLine(true)
        }
        val tvNote = TextView(activity).apply {
            text = "Tip: Internal App Proxy leaves Android's VPN slot free so Lantern or another VPN can stay active on top."
            textSize = 12f
        }

        layout.addView(schemeSpinner)
        layout.addView(etHost)
        layout.addView(etPort)
        layout.addView(tvNote)

        UiSafety.safeShowDialog(
            activity,
            AlertDialog.Builder(activity)
                .setTitle("Custom Local / Upstream Proxy")
                .setView(layout)
                .setPositiveButton("Apply") { _, _ ->
                    val scheme = if (schemeSpinner.selectedItemPosition == 1) {
                        TunnelConfig.ProxyScheme.SOCKS5
                    } else {
                        TunnelConfig.ProxyScheme.HTTP
                    }
                    val host = TunnelConfig.sanitizeProxyHost(etHost.text.toString())
                    val port = TunnelConfig.clampPort(
                        etPort.text.toString().trim().toIntOrNull() ?: settings.upstreamProxyPort,
                        TunnelConfig.DEFAULT_UPSTREAM_PROXY_PORT,
                        min = 1
                    )
                    TunnelConfig.saveGoogleProxyConfig(
                        context = activity,
                        accessMode = TunnelConfig.GoogleAccessMode.IN_APP_PROXY,
                        scheme = scheme,
                        host = host,
                        port = port,
                        localBridgePort = settings.localBridgePort,
                        autoStartBridge = scheme == TunnelConfig.ProxyScheme.SOCKS5
                    )
                    if (scheme == TunnelConfig.ProxyScheme.SOCKS5) {
                        LocalProxyService.startSafely(activity)
                    }
                    Toast.makeText(activity, "Applied ${scheme.name} $host:$port", Toast.LENGTH_SHORT).show()
                    onApplied()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    fun showLocalBridgeManagerDialog(activity: Activity, onApplied: () -> Unit = {}) {
        if (!UiSafety.isActivityAlive(activity)) return
        val settings = TunnelConfig.load(activity)
        val running = LocalProxyService.isRunning()
        val stats = LocalProxyService.getStats()
        val port = stats.port.takeIf { running && it > 0 } ?: settings.localBridgePort
        val socksUri = GeoBypassEngine.formatV2rayNgSocksUri("127.0.0.1", port)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val tvInfo = TextView(activity).apply {
            text = buildString {
                append(if (running) "Status: RUNNING ✓ on 127.0.0.1:$port\n" else "Status: Stopped\n")
                append("Protocols: HTTP CONNECT + SOCKS5 (NOAUTH) on the SAME port\n")
                append("Upstream: ${stats.upstreamLabel}\n")
                append("Active / Total Conns: ${stats.activeConnections} / ${stats.totalConnections}\n\n")
                append("For v2rayNG (add Socks outbound or proxy):\n")
                append("  Server: 127.0.0.1   Port: $port\n")
                append("  URI: $socksUri")
            }
            textSize = 13f
        }
        val etBridgePort = EditText(activity).apply {
            hint = "Local Proxy Port (1024–65535, default 10850)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(port.toString())
            setSingleLine(true)
        }

        layout.addView(tvInfo)
        layout.addView(etBridgePort)

        val toggleLabel = if (running) "Stop Bridge" else "Start Bridge ($port)"
        UiSafety.safeShowDialog(
            activity,
            AlertDialog.Builder(activity)
                .setTitle("DastYab Local Proxy Port")
                .setView(layout)
                .setPositiveButton(toggleLabel) { _, _ ->
                    val chosenPort = TunnelConfig.clampPort(
                        etBridgePort.text.toString().trim().toIntOrNull() ?: port,
                        TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT,
                        TunnelConfig.MIN_LOCAL_PORT
                    )
                    if (running) {
                        LocalProxyService.stop(activity)
                        TunnelConfig.saveGoogleProxyConfig(
                            activity,
                            settings.googleAccessMode,
                            settings.upstreamProxyScheme,
                            settings.upstreamProxyHost,
                            settings.upstreamProxyPort,
                            chosenPort,
                            false
                        )
                        Toast.makeText(activity, "Local proxy port stopped", Toast.LENGTH_SHORT).show()
                    } else {
                        TunnelConfig.saveGoogleProxyConfig(
                            activity,
                            TunnelConfig.GoogleAccessMode.LOCAL_BRIDGE,
                            settings.upstreamProxyScheme,
                            settings.upstreamProxyHost,
                            settings.upstreamProxyPort,
                            chosenPort,
                            true
                        )
                        LocalProxyService.startSafely(activity)
                        Toast.makeText(activity, "Local proxy port started on 127.0.0.1:$chosenPort", Toast.LENGTH_LONG).show()
                    }
                    onApplied()
                }
                .setNeutralButton("Copy URI for v2rayNG") { _, _ ->
                    val chosenPort = TunnelConfig.clampPort(
                        etBridgePort.text.toString().trim().toIntOrNull() ?: port,
                        TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT,
                        TunnelConfig.MIN_LOCAL_PORT
                    )
                    val uri = GeoBypassEngine.formatV2rayNgSocksUri("127.0.0.1", chosenPort)
                    UiSafety.copyTextSafely(activity, "DastYab Local Proxy URI", uri)
                    Toast.makeText(activity, "Copied: $uri", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(android.R.string.ok, null)
        )
    }

    fun runLiveGeoCheckDialog(activity: Activity) {
        if (!UiSafety.isActivityAlive(activity)) return
        Toast.makeText(activity, "Checking exit IP, country & Google Flow gate…", Toast.LENGTH_SHORT).show()

        thread(isDaemon = true, name = "dastyab-geo-check") {
            val route = GeoBypassEngine.resolveCurrentRoute(activity)
            val report = GeoBypassEngine.runAccessProbe(route, timeoutMs = 4200)
            activity.runOnUiThread {
                if (!UiSafety.isActivityAlive(activity)) return@runOnUiThread
                val egress = report.egress
                val geoLine = if (egress != null) {
                    val flag = if (report.flowCountrySupported) "Supported ✓" else "BLOCKED ✗"
                    "Exit IP : ${egress.ip}\nCountry : ${egress.countryCode} (${egress.countryName}) — Flow $flag"
                } else {
                    "Exit IP : Unavailable via ${report.routeLabel}"
                }
                val msg = buildString {
                    append("Route   : ${report.routeLabel}\n")
                    append(geoLine).append("\n\n")
                    append("AI Studio : ${report.aiStudio.detail}\n")
                    append("Flow      : ${report.flow.detail}\n\n")
                    append(report.recommendation)
                }
                UiSafety.safeShowDialog(
                    activity,
                    AlertDialog.Builder(activity)
                        .setTitle(report.headline)
                        .setMessage(msg)
                        .setPositiveButton("Switch Route / Proxy") { _, _ ->
                            show(activity)
                        }
                        .setNegativeButton(android.R.string.ok, null)
                )
            }
        }
    }

    private fun promptOpenLanternOrVpnApp(activity: Activity) {
        val tools = ConfigHarvester.detectFallbackTools(activity).filter { it.isInstalled }
        val lantern = tools.firstOrNull { it.packageNames.contains("org.getlantern.lantern") }
        val target = lantern ?: tools.firstOrNull()
        if (target?.installedPackage != null) {
            UiSafety.safeShowDialog(
                activity,
                AlertDialog.Builder(activity)
                    .setTitle("External VPN Mode Selected")
                    .setMessage("DastYab freed the Android VPN slot so ${target.name} (or any VPN) can run on top cleanly.\n\nOpen ${target.name} to connect?")
                    .setPositiveButton("Open ${target.name}") { _, _ ->
                        activity.packageManager.getLaunchIntentForPackage(target.installedPackage)?.let {
                            UiSafety.safeStartActivity(activity, it)
                        }
                    }
                    .setNegativeButton(android.R.string.ok, null)
            )
        } else {
            Toast.makeText(
                activity,
                "External VPN mode set — connect Lantern or your VPN app, then open AI Studio / Flow.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
