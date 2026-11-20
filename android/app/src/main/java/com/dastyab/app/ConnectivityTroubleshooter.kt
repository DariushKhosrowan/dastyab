package com.dastyab.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * ConnectivityTroubleshooter — pre-planned 7-layer censorship & blackout diagnostic engine.
 *
 * Classifies the exact restriction scenario (open internet, Google/AI Studio block,
 * Telegram-only block, GitHub + Telegram block, DNS poisoning / blackout, or offline)
 * and produces a prioritized, step-by-step rescue plan for the user.
 */
object ConnectivityTroubleshooter {

    enum class Diagnosis {
        OPEN_INTERNET,
        GOOGLE_RESTRICTED,
        TELEGRAM_BLOCKED_GITHUB_OK,
        GITHUB_AND_TELEGRAM_BLOCKED,
        DNS_POISONED_OR_BLACKOUT,
        NO_NETWORK
    }

    enum class RescueActionId {
        CONNECT_SMART_VPN,
        ENABLE_DNS_BLACKOUT_VPN,
        HARVEST_CONFIGS,
        RUN_IP_SCOUT,
        SET_BOOTSTRAP_CONFIG,
        LAUNCH_FALLBACK_VPN,
        OPEN_TUNNEL_SETTINGS
    }

    data class RescueStep(
        val actionId: RescueActionId,
        val title: String,
        val detail: String
    )

    data class ProbeSnapshot(
        val hasNetwork: Boolean,
        val vpnActive: Boolean,
        val cleanDnsMs: Long,
        val dohMs: Long,
        val githubDirectMs: Long,
        val githubMirrorMs: Long,
        val telegramMs: Long,
        val aiStudioMs: Long,
        val flowMs: Long,
        val installedFallbackCount: Int
    ) {
        val dnsOk: Boolean get() = cleanDnsMs >= 0 || dohMs >= 0
        val githubOk: Boolean get() = githubDirectMs >= 0 || githubMirrorMs >= 0
        val telegramOk: Boolean get() = telegramMs >= 0
        val googleOk: Boolean get() = aiStudioMs >= 0 || flowMs >= 0
    }

    data class DiagnosticReport(
        val snapshot: ProbeSnapshot,
        val diagnosis: Diagnosis,
        val headline: String,
        val summary: String,
        val rescueSteps: List<RescueStep>
    )

    /** Pure-Kotlin diagnostic classifier (unit-testable on the JVM). */
    fun diagnose(s: ProbeSnapshot): DiagnosticReport {
        if (!s.hasNetwork) {
            return DiagnosticReport(
                snapshot = s,
                diagnosis = Diagnosis.NO_NETWORK,
                headline = "No Active Network Interface",
                summary = "Wi-Fi and Mobile Data appear disconnected. Enable connectivity first, then re-run Troubleshooter.",
                rescueSteps = listOf(
                    RescueStep(RescueActionId.OPEN_TUNNEL_SETTINGS, "Check Tunnel Settings", "Verify DNS and offline saved configs once network is back.")
                )
            )
        }

        if (s.googleOk && s.githubOk) {
            return DiagnosticReport(
                snapshot = s,
                diagnosis = Diagnosis.OPEN_INTERNET,
                headline = "Internet & Google Services Reachable",
                summary = "GitHub, Clean DNS, and Google AI Studio / Flow are reachable. You can harvest fresh configs or launch AI Studio / Flow right now.",
                rescueSteps = listOf(
                    RescueStep(RescueActionId.HARVEST_CONFIGS, "Harvest Fresh Configs", "Cache fresh GitHub/Telegram configs now for future offline blackouts."),
                    RescueStep(RescueActionId.CONNECT_SMART_VPN, "Connect Smart VPN", "Route AI Studio & Flow cleanly while bypassing local LAN.")
                )
            )
        }

        if (s.githubOk && !s.telegramOk && !s.googleOk) {
            return DiagnosticReport(
                snapshot = s,
                diagnosis = Diagnosis.TELEGRAM_BLOCKED_GITHUB_OK,
                headline = "Telegram & Google Blocked · GitHub Reachable",
                summary = "GitHub (or jsDelivr mirror) is still accessible! DastYab can harvest free configs from GitHub immediately and use them to unlock AI Studio & Flow.",
                rescueSteps = listOf(
                    RescueStep(RescueActionId.HARVEST_CONFIGS, "Harvest from GitHub First", "Fetch free VLESS/Trojan configs from GitHub + jsDelivr mirror."),
                    RescueStep(RescueActionId.RUN_IP_SCOUT, "Run Clean IP & DNS Scout", "Find the fastest clean Cloudflare edge IP for your harvested config."),
                    RescueStep(RescueActionId.CONNECT_SMART_VPN, "Connect Smart VPN", "Activate Smart Tunnel for AI Studio and Google Flow.")
                )
            )
        }

        if (s.githubOk && !s.googleOk) {
            return DiagnosticReport(
                snapshot = s,
                diagnosis = Diagnosis.GOOGLE_RESTRICTED,
                headline = "Google AI Studio / Flow Restricted",
                summary = "Your ISP restricts direct access to Google AI Studio or Flow, while GitHub/DNS remain reachable.",
                rescueSteps = listOf(
                    RescueStep(RescueActionId.CONNECT_SMART_VPN, "Activate Smart Tunnel VPN", "Enable Smart Tunnel or Split Tunnel for your browser before opening AI Studio / Flow."),
                    RescueStep(RescueActionId.HARVEST_CONFIGS, "Harvest Fresh Configs", "Grab fresh working VLESS configs from GitHub & Telegram."),
                    RescueStep(RescueActionId.RUN_IP_SCOUT, "Find Clean Edge IP", "Patch your VLESS config with a low-latency clean IP.")
                )
            )
        }

        if (!s.githubOk && !s.telegramOk && s.dnsOk) {
            val steps = mutableListOf(
                RescueStep(RescueActionId.ENABLE_DNS_BLACKOUT_VPN, "Activate Emergency DNS Blackout VPN", "DNS/DoH still works! Enable WhiteDNS/DoH Blackout mode to bypass DNS poisoning."),
                RescueStep(RescueActionId.RUN_IP_SCOUT, "Run Clean IP & DNS Scout", "Discover unblocked edge IPs and fastest DoH/UDP DNS resolvers.")
            )
            if (s.installedFallbackCount > 0) {
                steps.add(RescueStep(RescueActionId.LAUNCH_FALLBACK_VPN, "Connect via Installed Fallback VPN", "Temporarily connect Lantern / Tor / WhiteVPN / SlipNet, then harvest free configs."))
            }
            steps.add(RescueStep(RescueActionId.SET_BOOTSTRAP_CONFIG, "Import Temporary Base Config", "Paste one working config manually so DastYab can connect and harvest a full pool."))
            return DiagnosticReport(
                snapshot = s,
                diagnosis = Diagnosis.GITHUB_AND_TELEGRAM_BLOCKED,
                headline = "GitHub & Telegram Blocked · Clean DNS Reachable",
                summary = "Direct access to GitHub and Telegram is blocked, but Clean DNS / DoH is reachable. Use DNS Blackout VPN, an installed fallback VPN, or a temporary base config.",
                rescueSteps = steps
            )
        }

        // Hard blackout / DNS poisoning
        val blackoutSteps = mutableListOf(
            RescueStep(RescueActionId.ENABLE_DNS_BLACKOUT_VPN, "Last-Resort: Full DNS Blackout VPN", "Enable WhiteDNS / DoH / SlipNet DNS tunnel mode for severe blackout recovery."),
            RescueStep(RescueActionId.SET_BOOTSTRAP_CONFIG, "Configure Temporary Base Config", "Paste a working temporary vless:// / trojan:// / DNS config to bootstrap harvesting."),
            RescueStep(RescueActionId.RUN_IP_SCOUT, "Scout Clean IPv4 Ranges", "Probe clean Cloudflare/CDN ranges to locate any open IPv4 edge.")
        )
        if (s.installedFallbackCount > 0) {
            blackoutSteps.add(0, RescueStep(RescueActionId.LAUNCH_FALLBACK_VPN, "Try Installed Fallback VPN", "Launch Tor/Orbot, Lantern, SlipNet, or WhiteVPN to bootstrap internet access."))
        }
        return DiagnosticReport(
            snapshot = s,
            diagnosis = Diagnosis.DNS_POISONED_OR_BLACKOUT,
            headline = "Severe Restriction / Blackout Detected",
            summary = "Direct HTTPS to GitHub, Telegram, and Google failed. Use Emergency DNS Blackout VPN (WhiteDNS/DoH/SlipNet), a fallback VPN, or a temporary base config to bootstrap.",
            rescueSteps = blackoutSteps
        )
    }

    /** Executes all 7 live network probes on a background thread. */
    fun runLiveDiagnostics(context: Context, onProgress: (String) -> Unit = {}): DiagnosticReport {
        val settings = TunnelConfig.load(context)
        val hasNet = hasActiveNetwork(context)
        val vpnActive = ConfigHarvester.isAnyVpnActive(context)
        val installedFallbacks = ConfigHarvester.detectFallbackTools(context).count { it.isInstalled }

        if (!hasNet) {
            return diagnose(
                ProbeSnapshot(
                    hasNetwork = false,
                    vpnActive = false,
                    cleanDnsMs = -1,
                    dohMs = -1,
                    githubDirectMs = -1,
                    githubMirrorMs = -1,
                    telegramMs = -1,
                    aiStudioMs = -1,
                    flowMs = -1,
                    installedFallbackCount = installedFallbacks
                )
            )
        }

        onProgress("Probing Clean UDP DNS (${settings.dnsPrimary})…")
        val cleanDnsMs = CleanEndpointScout.probeUdpDnsLatency(settings.dnsPrimary, 2200)

        onProgress("Probing DNS-over-HTTPS (${settings.dohUrl})…")
        val dohMs = CleanEndpointScout.probeDohLatency(settings.dohUrl, 2500)

        onProgress("Probing GitHub Raw (raw.githubusercontent.com)…")
        val ghDirectMs = CleanEndpointScout.probeTcpLatency("raw.githubusercontent.com", 443, 2500)

        onProgress("Probing GitHub Mirror (cdn.jsdelivr.net)…")
        val ghMirrorMs = CleanEndpointScout.probeTcpLatency("cdn.jsdelivr.net", 443, 2500)

        onProgress("Probing Telegram Web (t.me)…")
        val tgMs = CleanEndpointScout.probeTcpLatency("t.me", 443, 2500)

        onProgress("Verifying non-geoblocked access for Google AI Studio & Google Flow…")
        val aiRoute = GeoBypassEngine.resolveCurrentRoute(context, settings, GeoBypassEngine.TargetService.AI_STUDIO)
        val flowRoute = GeoBypassEngine.resolveCurrentRoute(context, settings, GeoBypassEngine.TargetService.GOOGLE_FLOW)
        val aiReport = GeoBypassEngine.runAccessProbe(aiRoute, timeoutMs = 3500)
        val flowReport = if (flowRoute.upstreamChainSpec == aiRoute.upstreamChainSpec && flowRoute.javaNetProxy == aiRoute.javaNetProxy) {
            aiReport
        } else {
            GeoBypassEngine.runAccessProbe(flowRoute, timeoutMs = 3500)
        }
        val aiMs = if (aiReport.readyForAiStudio) aiReport.aiStudio.latencyMs.coerceAtLeast(1L) else -1L
        val flowMs = if (flowReport.readyForFlow) flowReport.flow.latencyMs.coerceAtLeast(1L) else -1L

        return diagnose(
            ProbeSnapshot(
                hasNetwork = true,
                vpnActive = vpnActive,
                cleanDnsMs = cleanDnsMs,
                dohMs = dohMs,
                githubDirectMs = ghDirectMs,
                githubMirrorMs = ghMirrorMs,
                telegramMs = tgMs,
                aiStudioMs = aiMs,
                flowMs = flowMs,
                installedFallbackCount = installedFallbacks
            )
        )
    }

    private fun hasActiveNetwork(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(active) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            true
        }
    }
}
