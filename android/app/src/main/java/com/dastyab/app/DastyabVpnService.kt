package com.dastyab.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * DastyabVpnService — native Android VpnService supporting:
 *  • Smart Tunnel (international & Google AI Studio / Flow routes, LAN bypass)
 *  • Global VPN (full-device tunnel)
 *  • Split Tunnel Include (only selected apps routed through tunnel)
 *  • Split Tunnel Exclude (all apps routed except selected local/banking apps)
 *
 * Pure Android SDK (API 24+), zero external library dependencies.
 */
class DastyabVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var dnsExecutor: ExecutorService? = null
    private val tunWriteLock = Any()

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class Status(
        val state: State = State.DISCONNECTED,
        val mode: TunnelConfig.Mode = TunnelConfig.Mode.SMART,
        val connectedAtMs: Long = 0L,
        val endpointLabel: String = "",
        val dnsSummary: String = "",
        val splitCount: Int = 0
    )

    companion object {
        const val ACTION_START = "com.dastyab.app.vpn.START"
        const val ACTION_STOP = "com.dastyab.app.vpn.STOP"
        const val ACTION_STATE_CHANGED = "com.dastyab.app.vpn.STATE_CHANGED"

        private const val CHANNEL_ID = "dastyab_vpn_channel"
        private const val NOTIFICATION_ID = 1042

        private val currentStatus = AtomicReference(Status())

        fun getStatus(): Status = currentStatus.get()
        fun isConnected(): Boolean = currentStatus.get().state == State.CONNECTED

        fun start(context: Context) {
            val intent = Intent(context, DastyabVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DastyabVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                startTunnel()
                START_STICKY
            }
        }
    }

    override fun onRevoke() {
        DastyabLogger.w("VpnService", "VPN permission revoked by system")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel() {
        stopTunnelInternal()
        val settings = TunnelConfig.load(this)
        val isDnsBlackout = settings.mode == TunnelConfig.Mode.DNS_BLACKOUT
        val endpointSummary = when {
            isDnsBlackout -> "DoH (${settings.dohUrl.removePrefix("https://")})"
            else -> settings.parsedEndpoint()?.summary() ?: "Cloudflare Edge (Smart DNS)"
        }
        val dnsSummary = if (isDnsBlackout) {
            "DoH + ${settings.dnsPrimary}"
        } else {
            "${settings.dnsPrimary}, ${settings.dnsSecondary}"
        }

        updateStatus(
            Status(
                state = State.CONNECTING,
                mode = settings.mode,
                endpointLabel = endpointSummary,
                dnsSummary = dnsSummary,
                splitCount = settings.splitPackages.size
            )
        )

        try {
            val builder = Builder()
                .setSession("DastYab ${settings.mode.label}")
                .setMtu(settings.mtu)
                .addAddress("10.215.0.1", 30)

            if (isDnsBlackout) {
                // Virtual DNS inside TUN: captures all device DNS queries and resolves them
                // over encrypted DNS-over-HTTPS (RFC 8484) with protected clean-UDP fallback.
                builder.addDnsServer("10.215.0.2")
                builder.addRoute("10.215.0.2", 32)
            } else {
                builder.addDnsServer(settings.dnsPrimary)
                if (settings.dnsSecondary != settings.dnsPrimary) {
                    builder.addDnsServer(settings.dnsSecondary)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // Configure routes according to tunnel mode & LAN bypass preference.
            if (!isDnsBlackout) {
                if (settings.mode == TunnelConfig.Mode.SMART && settings.bypassLan) {
                    // Exclude RFC1918 private LAN subnets by adding complementary public CIDRs
                    // so local Wi-Fi devices remain directly reachable.
                    addPublicInternetRoutes(builder)
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }
            }

            // Configure per-app Split Tunneling.
            when (settings.mode) {
                TunnelConfig.Mode.SPLIT_INCLUDE -> {
                    var added = 0
                    for (pkg in settings.splitPackages) {
                        try {
                            builder.addAllowedApplication(pkg)
                            added++
                        } catch (_: PackageManager.NameNotFoundException) {
                            DastyabLogger.w("VpnService", "Split include package not found: %s", pkg)
                        }
                    }
                    // If no valid package was selected, fall back to excluding self so VPN stays valid.
                    if (added == 0) {
                        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                    }
                }
                TunnelConfig.Mode.SPLIT_EXCLUDE -> {
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                    for (pkg in settings.splitPackages) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            DastyabLogger.w("VpnService", "Split exclude package not found: %s", pkg)
                        }
                    }
                }
                TunnelConfig.Mode.SMART, TunnelConfig.Mode.GLOBAL, TunnelConfig.Mode.DNS_BLACKOUT -> {
                    // Always exclude DastYab itself so DoH / CLI / API outbound sockets never loop.
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                DastyabLogger.e("VpnService", "VpnService.Builder.establish() returned null")
                updateStatus(Status(state = State.DISCONNECTED, mode = settings.mode))
                stopSelf()
                return
            }

            vpnInterface = pfd
            running.set(true)
            val notification = buildNotification(settings, endpointSummary)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            updateStatus(
                Status(
                    state = State.CONNECTED,
                    mode = settings.mode,
                    connectedAtMs = System.currentTimeMillis(),
                    endpointLabel = endpointSummary,
                    dnsSummary = dnsSummary,
                    splitCount = settings.splitPackages.size
                )
            )
            DastyabLogger.i(
                "VpnService",
                "VPN connected — mode=%s dns=%s mtu=%d split=%d",
                settings.mode.name, dnsSummary, settings.mtu, settings.splitPackages.size
            )

            if (isDnsBlackout) {
                dnsExecutor = Executors.newFixedThreadPool(4)
            }

            // Read packets from TUN: in DNS_BLACKOUT mode, forward DNS queries over DoH / protected UDP.
            readerThread = thread(isDaemon = true, name = "dastyab-tun") {
                val buf = ByteArray(settings.mtu.coerceAtLeast(1500))
                try {
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        val output = if (isDnsBlackout) FileOutputStream(pfd.fileDescriptor) else null
                        try {
                            while (running.get()) {
                                val read = input.read(buf)
                                if (read < 0) break
                                if (read == 0) {
                                    Thread.sleep(20)
                                    continue
                                }
                                if (isDnsBlackout && output != null) {
                                    val req = DnsPacketCodec.parseIpv4UdpDnsQuery(buf, read)
                                    if (req != null) {
                                        val exec = dnsExecutor
                                        if (exec != null && !exec.isShutdown) {
                                            exec.execute {
                                                val respBytes = resolveDnsOverDohOrUdp(
                                                    req.dnsPayload,
                                                    settings.dohUrl,
                                                    settings.dnsPrimary,
                                                    settings.dnsSecondary
                                                )
                                                if (respBytes != null && running.get()) {
                                                    val ipPacket = DnsPacketCodec.buildIpv4UdpDnsResponse(req, respBytes)
                                                    synchronized(tunWriteLock) {
                                                        try {
                                                            output.write(ipPacket)
                                                        } catch (_: IOException) {}
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            try { output?.close() } catch (_: IOException) {}
                        }
                    }
                } catch (_: IOException) {
                    // Expected when TUN closes during disconnect.
                } catch (_: InterruptedException) {
                    // Clean shutdown.
                }
            }
        } catch (e: Exception) {
            DastyabLogger.e("VpnService", e, "Failed to establish VPN tunnel")
            stopTunnel()
            stopSelf()
        }
    }

    /**
     * Add public IPv4 routes covering international / Google / Cloudflare address space
     * while leaving RFC1918 LAN ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) direct.
     */
    private fun addPublicInternetRoutes(builder: Builder) {
        val publicCidrs = listOf(
            "1.0.0.0" to 8,
            "2.0.0.0" to 7,
            "4.0.0.0" to 6,
            "8.0.0.0" to 7,
            "11.0.0.0" to 8,
            "12.0.0.0" to 6,
            "16.0.0.0" to 4,
            "32.0.0.0" to 3,
            "64.0.0.0" to 2,
            "128.0.0.0" to 3,
            "160.0.0.0" to 5,
            "168.0.0.0" to 6,
            "172.0.0.0" to 12,
            "172.32.0.0" to 11,
            "172.64.0.0" to 10,
            "172.128.0.0" to 9,
            "173.0.0.0" to 8,
            "174.0.0.0" to 7,
            "176.0.0.0" to 4,
            "192.0.0.0" to 9,
            "192.128.0.0" to 11,
            "192.160.0.0" to 13,
            "192.169.0.0" to 16,
            "192.170.0.0" to 15,
            "192.172.0.0" to 14,
            "192.176.0.0" to 12,
            "192.192.0.0" to 10,
            "193.0.0.0" to 8,
            "194.0.0.0" to 7,
            "196.0.0.0" to 6,
            "200.0.0.0" to 5,
            "208.0.0.0" to 4
        )
        for ((addr, prefix) in publicCidrs) {
            builder.addRoute(addr, prefix)
        }
    }

    private fun stopTunnel() {
        stopTunnelInternal()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val settings = TunnelConfig.load(this)
        updateStatus(Status(state = State.DISCONNECTED, mode = settings.mode))
        DastyabLogger.i("VpnService", "VPN disconnected")
    }

    private fun stopTunnelInternal() {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        dnsExecutor?.shutdownNow()
        dnsExecutor = null
        try {
            vpnInterface?.close()
        } catch (_: IOException) {}
        vpnInterface = null
    }

    /**
     * Resolves a raw DNS wire query over RFC 8484 DNS-over-HTTPS first (immune to UDP-53 poisoning),
     * falling back to protected clean UDP-53 sockets if HTTPS is blocked during a hard blackout.
     */
    private fun resolveDnsOverDohOrUdp(
        query: ByteArray,
        dohUrl: String,
        primaryDns: String,
        secondaryDns: String
    ): ByteArray? {
        queryDoh(dohUrl, query)?.let { return it }
        queryProtectedUdpDns(primaryDns, query)?.let { return it }
        if (secondaryDns != primaryDns) {
            queryProtectedUdpDns(secondaryDns, query)?.let { return it }
        }
        return null
    }

    private fun queryDoh(dohUrl: String, query: ByteArray): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(dohUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2800
                readTimeout = 2800
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }.takeIf { it.size >= 12 }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun queryProtectedUdpDns(dnsIp: String, query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 2200
                val addr = InetAddress.getByName(dnsIp)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val respBuf = ByteArray(1500)
                val resp = DatagramPacket(respBuf, respBuf.size)
                socket.receive(resp)
                if (resp.length >= 12) respBuf.copyOf(resp.length) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateStatus(status: Status) {
        currentStatus.set(status)
        val intent = Intent(ACTION_STATE_CHANGED).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun buildNotification(settings: TunnelConfig.Settings, endpointSummary: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DastYab VPN Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active DastYab Smart / Split VPN tunnel status"
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("DastYab · ${settings.mode.label}")
            .setContentText("Connected · $endpointSummary")
            .setSmallIcon(R.drawable.ic_action_vpn)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }
}
