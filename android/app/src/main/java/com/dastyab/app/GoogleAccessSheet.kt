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
        val targetService = targetUrl?.let { GeoBypassEngine.TargetService.fromUrl(it) }
        val route = GeoBypassEngine.planRoute(
            settings = settings,
            externalVpnActive = externalVpn,
            dastyabVpnActive = DastyabVpnService.isConnected(),
            runningBridgePort = bridgePort,
            detectedListeningHttpPort = null,
            targetService = targetService
        )

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 1. Auto Smart Mode
        val autoMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.AUTO_SMART) "● " else ""
        options.add("$autoMark${activity.getString(R.string.access_sheet_opt_auto)}")
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
            Toast.makeText(activity, activity.getString(R.string.access_sheet_toast_auto), Toast.LENGTH_SHORT).show()
            onConfigChanged()
        }

        // 2. External VPN / Lantern Pass-Through
        val extMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.EXTERNAL_VPN) "● " else ""
        val extBadge = if (externalVpn) {
            activity.getString(R.string.access_sheet_ext_active)
        } else {
            activity.getString(R.string.access_sheet_ext_inactive)
        }
        options.add("$extMark${activity.getString(R.string.access_sheet_opt_external)} $extBadge")
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
                Toast.makeText(activity, activity.getString(R.string.access_sheet_toast_external_active), Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Internal App Proxy (scan & pick local proxy port while leaving OS VPN slot free!)
        val inAppMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.IN_APP_PROXY) "● " else ""
        options.add("$inAppMark${activity.getString(R.string.access_sheet_opt_inapp)}")
        actions.add {
            showLocalProxyPortPicker(activity, onConfigChanged)
        }

        // 4. Start / Manage DastYab Local Proxy Port (127.0.0.1:10850 for v2rayNG & multi-layering)
        val bridgeStatus = if (bridgePort != null) {
            activity.getString(R.string.access_sheet_bridge_running_tag, bridgePort)
        } else {
            activity.getString(R.string.access_sheet_bridge_stopped_tag, settings.localBridgePort)
        }
        options.add(activity.getString(R.string.access_sheet_opt_bridge, bridgeStatus))
        actions.add {
            showLocalBridgeManagerDialog(activity, onConfigChanged)
        }

        // 5. DastYab VPN + Local Proxy Chain
        val chainMark = if (settings.googleAccessMode == TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY) "● " else ""
        options.add("$chainMark${activity.getString(R.string.access_sheet_opt_chain)}")
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
            Toast.makeText(activity, activity.getString(R.string.access_sheet_toast_chain), Toast.LENGTH_SHORT).show()
        }

        // 6. Auto-Chain Layers for Google AI Studio
        options.add(activity.getString(R.string.access_sheet_opt_chain_aistudio))
        actions.add {
            runAutoChainDialog(activity, GeoBypassEngine.TargetService.AI_STUDIO, onConfigChanged)
        }

        // 7. Auto-Chain Layers for Google Flow (stricter / separate chain)
        options.add(activity.getString(R.string.access_sheet_opt_chain_flow))
        actions.add {
            runAutoChainDialog(activity, GeoBypassEngine.TargetService.GOOGLE_FLOW, onConfigChanged)
        }

        // 8. Live Geo & Flow Country Check (tests both services separately)
        options.add(activity.getString(R.string.access_sheet_opt_geocheck))
        actions.add {
            runLiveGeoCheckDialog(activity, targetUrl)
        }

        // 7. Launch Lantern directly (if installed)
        val lanternInstalled = ConfigHarvester.detectFallbackTools(activity)
            .firstOrNull { it.packageNames.contains("org.getlantern.lantern") && it.isInstalled }
        if (lanternInstalled != null) {
            options.add(activity.getString(R.string.access_sheet_opt_open_lantern))
            actions.add {
                activity.packageManager.getLaunchIntentForPackage(lanternInstalled.installedPackage!!)?.let {
                    UiSafety.safeStartActivity(activity, it)
                }
            }
        }

        val headerText = buildString {
            append(activity.getString(R.string.access_sheet_header_route, route.routeLabel))
            append("\n")
            append(
                activity.getString(
                    R.string.access_sheet_header_ext_vpn,
                    if (externalVpn) activity.getString(R.string.access_sheet_state_active)
                    else activity.getString(R.string.access_sheet_state_inactive)
                )
            )
            if (targetTitle != null && targetUrl != null) {
                append(" · $targetTitle")
            }
        }
        val titleView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.access_sheet_title)
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
        builder.setNeutralButton(activity.getString(R.string.access_sheet_full_settings)) { _, _ ->
            UiSafety.safeStartActivity(activity, Intent(activity, TunnelSettingsActivity::class.java))
        }

        UiSafety.safeShowDialog(activity, builder)
    }

    fun showLocalProxyPortPicker(activity: Activity, onApplied: () -> Unit = {}) {
        if (!UiSafety.isActivityAlive(activity)) return
        val settings = TunnelConfig.load(activity)
        Toast.makeText(activity, activity.getString(R.string.access_sheet_scanning_ports), Toast.LENGTH_SHORT).show()

        thread(isDaemon = true, name = "dastyab-proxy-scan") {
            val active = GeoBypassEngine.discoverListeningProxies(timeoutMs = 150)
            val activePorts = active.associateBy { it.preset.port }
            activity.runOnUiThread {
                if (!UiSafety.isActivityAlive(activity)) return@runOnUiThread

                val items = mutableListOf<String>()
                val presets = GeoBypassEngine.KNOWN_LOCAL_PROXY_PRESETS
                for (p in presets) {
                    val live = activePorts[p.port]
                    val status = if (live != null) " [${activity.getString(R.string.access_sheet_listening_tag, live.latencyMs.toInt())}]" else ""
                    items.add("${p.label}$status")
                }
                items.add(activity.getString(R.string.access_sheet_custom_host_port))

                UiSafety.safeShowDialog(
                    activity,
                    AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.access_sheet_pick_port_title))
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
                                    activity.getString(R.string.access_sheet_toast_inapp_set, picked.scheme.name, picked.host, picked.port),
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
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, schemes.map { it.localizedLabel(activity) }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(if (settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5) 1 else 0)
        }
        val etHost = EditText(activity).apply {
            hint = activity.getString(R.string.access_sheet_hint_host)
            setText(settings.upstreamProxyHost)
            setSingleLine(true)
        }
        val etPort = EditText(activity).apply {
            hint = activity.getString(R.string.access_sheet_hint_port)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.upstreamProxyPort.toString())
            setSingleLine(true)
        }
        val tvNote = TextView(activity).apply {
            text = activity.getString(R.string.access_sheet_custom_tip)
            textSize = 12f
        }

        layout.addView(schemeSpinner)
        layout.addView(etHost)
        layout.addView(etPort)
        layout.addView(tvNote)

        UiSafety.safeShowDialog(
            activity,
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.access_sheet_custom_title))
                .setView(layout)
                .setPositiveButton(activity.getString(R.string.access_sheet_btn_apply)) { _, _ ->
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
                    Toast.makeText(activity, activity.getString(R.string.access_sheet_toast_applied, scheme.name, host, port), Toast.LENGTH_SHORT).show()
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
            text = activity.getString(
                R.string.access_sheet_bridge_info,
                if (running) activity.getString(R.string.access_sheet_bridge_running_state, port)
                else activity.getString(R.string.access_sheet_bridge_stopped_state),
                stats.upstreamLabel,
                stats.activeConnections,
                stats.totalConnections,
                port,
                socksUri
            )
            textSize = 13f
        }
        val etBridgePort = EditText(activity).apply {
            hint = activity.getString(R.string.access_sheet_bridge_port_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(port.toString())
            setSingleLine(true)
        }

        layout.addView(tvInfo)
        layout.addView(etBridgePort)

        val toggleLabel = if (running) {
            activity.getString(R.string.access_sheet_bridge_stop_btn)
        } else {
            activity.getString(R.string.access_sheet_bridge_start_btn, port)
        }
        UiSafety.safeShowDialog(
            activity,
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.access_sheet_bridge_title))
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
                        Toast.makeText(activity, activity.getString(R.string.tunnel_local_bridge_stopped), Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(activity, activity.getString(R.string.tunnel_local_bridge_started, chosenPort.toString()), Toast.LENGTH_LONG).show()
                    }
                    onApplied()
                }
                .setNeutralButton(activity.getString(R.string.tunnel_btn_copy_v2rayng)) { _, _ ->
                    val chosenPort = TunnelConfig.clampPort(
                        etBridgePort.text.toString().trim().toIntOrNull() ?: port,
                        TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT,
                        TunnelConfig.MIN_LOCAL_PORT
                    )
                    val uri = GeoBypassEngine.formatV2rayNgSocksUri("127.0.0.1", chosenPort)
                    UiSafety.copyTextSafely(activity, "DastYab Local Proxy URI", uri)
                    Toast.makeText(activity, activity.getString(R.string.tunnel_copied_prefix, uri), Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(android.R.string.ok, null)
        )
    }

    fun runAutoChainDialog(
        activity: Activity,
        targetService: GeoBypassEngine.TargetService,
        onConfigChanged: () -> Unit = {}
    ) {
        if (!UiSafety.isActivityAlive(activity)) return
        val statusTv = TextView(activity).apply {
            val pad = (18 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            text = activity.getString(R.string.access_sheet_chain_running)
            textSize = 13f
        }
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.access_sheet_chain_progress_title, targetService.displayName))
            .setView(statusTv)
            .setCancelable(false)
            .create()
        runCatching { progressDialog.show() }

        val steps = mutableListOf<String>()
        thread(isDaemon = true, name = "dastyab-sheet-autochain") {
            val result = GeoBypassEngine.discoverAndChainBestRoute(
                context = activity,
                targetService = targetService,
                allowHarvest = true,
                maxCandidates = if (targetService == GeoBypassEngine.TargetService.GOOGLE_FLOW) 44 else 36
            ) { line ->
                steps.add(line)
                activity.runOnUiThread {
                    if (UiSafety.isActivityAlive(activity)) {
                        statusTv.text = steps.takeLast(8).joinToString("\n")
                    }
                }
            }
            activity.runOnUiThread {
                runCatching { if (progressDialog.isShowing) progressDialog.dismiss() }
                if (!UiSafety.isActivityAlive(activity)) return@runOnUiThread
                val ccTag = result.report.egress?.countryCode ?: "??"
                val ipTag = result.report.egress?.ip ?: "-"
                val msg = buildString {
                    append(result.summary).append("\n\n")
                    append("Exit IP : $ipTag ($ccTag)\n")
                    append("Attempts: ${result.attempts.size}\n\n")
                    append(result.report.recommendation)
                }
                UiSafety.safeShowDialog(
                    activity,
                    AlertDialog.Builder(activity)
                        .setTitle(if (result.success) "${targetService.displayName} Unlocked ✓" else "${targetService.displayName} Geo-Restricted")
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                )
                onConfigChanged()
            }
        }
    }

    fun runLiveGeoCheckDialog(activity: Activity, targetUrl: String? = null) {
        if (!UiSafety.isActivityAlive(activity)) return
        Toast.makeText(activity, activity.getString(R.string.access_sheet_geocheck_running), Toast.LENGTH_SHORT).show()

        thread(isDaemon = true, name = "dastyab-geo-check") {
            val settings = TunnelConfig.load(activity)
            val aiRoute = GeoBypassEngine.resolveCurrentRoute(activity, settings, GeoBypassEngine.TargetService.AI_STUDIO)
            val flowRoute = GeoBypassEngine.resolveCurrentRoute(activity, settings, GeoBypassEngine.TargetService.GOOGLE_FLOW)
            val aiReport = GeoBypassEngine.runAccessProbe(aiRoute, timeoutMs = 4200)
            val flowReport = if (flowRoute.upstreamChainSpec == aiRoute.upstreamChainSpec && flowRoute.javaNetProxy == aiRoute.javaNetProxy) {
                aiReport
            } else {
                GeoBypassEngine.runAccessProbe(flowRoute, timeoutMs = 4200)
            }

            activity.runOnUiThread {
                if (!UiSafety.isActivityAlive(activity)) return@runOnUiThread
                val egress = flowReport.egress ?: aiReport.egress
                val geoLine = if (egress != null) {
                    val flag = if (flowReport.flowCountrySupported) {
                        activity.getString(R.string.browser_geo_supported)
                    } else {
                        activity.getString(R.string.browser_geo_blocked_short)
                    }
                    activity.getString(R.string.access_sheet_geo_line_ok, egress.ip, egress.countryCode, egress.countryName, flag)
                } else {
                    activity.getString(R.string.access_sheet_geo_line_unavailable, flowReport.routeLabel)
                }
                val primaryTarget = targetUrl?.let { GeoBypassEngine.TargetService.fromUrl(it) }
                    ?: if (!flowReport.readyForFlow) GeoBypassEngine.TargetService.GOOGLE_FLOW else GeoBypassEngine.TargetService.AI_STUDIO

                val dualBody = activity.getString(
                    R.string.access_sheet_geocheck_dual,
                    aiReport.routeLabel,
                    "${if (aiReport.readyForAiStudio) "READY ✓" else "BLOCKED ✗"} · ${aiReport.aiStudio.detail}",
                    flowReport.routeLabel,
                    "${if (flowReport.readyForFlow) "READY ✓" else "BLOCKED ✗"} · ${flowReport.flow.detail}",
                    if (flowReport.flow.appShellOk) "AiSandboxAngularFrontend ✓" else "Missing ✗",
                    if (flowReport.accountsReachable) "Reachable ✓" else "Blocked ✗"
                )
                val msg = "$geoLine\n\n$dualBody\n\n${flowReport.recommendation}"
                val title = when {
                    aiReport.readyForAiStudio && flowReport.readyForFlow -> "AI Studio ✓ & Flow ✓ (${egress?.countryCode ?: "?"})"
                    aiReport.readyForAiStudio -> "AI Studio ✓ · Flow Geo-Blocked ✗"
                    else -> flowReport.headline
                }
                UiSafety.safeShowDialog(
                    activity,
                    AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton(activity.getString(R.string.access_sheet_switch_route_btn)) { _, _ ->
                            show(activity, targetUrl = targetUrl)
                        }
                        .setNeutralButton(activity.getString(R.string.browser_btn_auto_chain)) { _, _ ->
                            runAutoChainDialog(activity, primaryTarget)
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
                    .setTitle(activity.getString(R.string.access_sheet_ext_dialog_title))
                    .setMessage(activity.getString(R.string.access_sheet_ext_dialog_msg, target.name))
                    .setPositiveButton(activity.getString(R.string.access_sheet_ext_dialog_open, target.name)) { _, _ ->
                        activity.packageManager.getLaunchIntentForPackage(target.installedPackage)?.let {
                            UiSafety.safeStartActivity(activity, it)
                        }
                    }
                    .setNegativeButton(android.R.string.ok, null)
            )
        } else {
            Toast.makeText(
                activity,
                activity.getString(R.string.access_sheet_ext_toast_connect),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
