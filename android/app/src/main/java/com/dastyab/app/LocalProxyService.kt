package com.dastyab.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicReference

/**
 * LocalProxyService — Foreground Android service running [LocalProxyServer] on `127.0.0.1:<port>`.
 *
 * Allows:
 *  • External apps (v2rayNG, Chrome, NekoBox) to point their outbound/proxy config to
 *    DastYab's local port `127.0.0.1:<localBridgePort>` (accepts both HTTP CONNECT and SOCKS5).
 *  • Multi-layer VPN stacking: run Lantern or another system VPN on top while DastYab's
 *    in-app browser (or another proxy chain) rides through this local proxy port cleanly.
 *  • Transparent SOCKS5 -> HTTP translation when upstream tools only expose a SOCKS5 port
 *    (e.g. `127.0.0.1:10808` or Orbot `9050`) so WebView `ProxyController` works effortlessly.
 */
class LocalProxyService : Service() {

    private var server: LocalProxyServer? = null

    companion object {
        const val ACTION_START = "com.dastyab.app.proxy.START"
        const val ACTION_STOP = "com.dastyab.app.proxy.STOP"
        const val ACTION_STATE_CHANGED = "com.dastyab.app.proxy.STATE_CHANGED"
        const val EXTRA_TARGET_SERVICE = "extra_target_service"

        private const val CHANNEL_ID = "dastyab_local_proxy_channel"
        private const val NOTIFICATION_ID = 1043

        private val statsRef = AtomicReference(
            LocalProxyServer.Stats(
                running = false,
                port = -1,
                upstreamLabel = TunnelConfig.ProxyScheme.DIRECT.label,
                activeConnections = 0,
                totalConnections = 0L,
                bytesTransferred = 0L,
                lastTarget = "",
                lastError = null
            )
        )
        private val activeTargetRef = AtomicReference<String?>(null)
        private val activeChainSpecRef = AtomicReference<String>("")

        fun isRunning(): Boolean = statsRef.get().running && statsRef.get().port > 0
        fun activePort(): Int? = statsRef.get().takeIf { it.running && it.port > 0 }?.port
        fun activeTargetService(): String? = activeTargetRef.get()
        fun activeChainSpec(): String = activeChainSpecRef.get()
        fun getStats(): LocalProxyServer.Stats = statsRef.get()

        fun startSafely(context: Context, targetServiceKey: String? = null): Boolean {
            val intent = Intent(context, LocalProxyService::class.java).apply {
                action = ACTION_START
                if (!targetServiceKey.isNullOrBlank()) {
                    putExtra(EXTRA_TARGET_SERVICE, targetServiceKey)
                }
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (t: Throwable) {
                DastyabLogger.w("LocalProxyService", t, "Unable to start local proxy service")
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocalProxyService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                DastyabLogger.w("LocalProxyService", t, "Ignoring stop call while LocalProxyService is inactive")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && server?.isRunning() != true) {
                    enterForegroundCompat("Stopping local proxy…")
                }
                stopBridge()
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                val targetKey = intent?.getStringExtra(EXTRA_TARGET_SERVICE)
                startBridge(targetKey)
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    private fun loadSettingsSafely(): TunnelConfig.Settings {
        if (!UiSafety.isUserUnlockedCompat(this)) return TunnelConfig.Settings()
        return runCatching { TunnelConfig.load(this) }.getOrElse { TunnelConfig.Settings() }
    }

    private fun buildUpstream(
        settings: TunnelConfig.Settings,
        listenPort: Int,
        targetServiceKey: String?
    ): Pair<LocalProxyServer.Upstream, String> {
        val hops = TunnelConfig.effectiveUpstreamHops(settings, targetServiceKey, listenPort)
        val upstream = TunnelConfig.toLocalProxyUpstream(hops, listenPort)
        return upstream to TunnelConfig.formatProxyChain(hops)
    }

    private fun startBridge(targetServiceKey: String? = null) {
        val settings = loadSettingsSafely()
        val desiredPort = TunnelConfig.clampPort(
            settings.localBridgePort,
            TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT,
            TunnelConfig.MIN_LOCAL_PORT
        )
        val (upstream, chainSpec) = buildUpstream(settings, desiredPort, targetServiceKey)

        // If already running with identical upstream chain & port, keep existing sockets undisturbed.
        if (server?.isRunning() == true &&
            activeChainSpecRef.get() == chainSpec &&
            server?.actualPort == desiredPort
        ) {
            activeTargetRef.set(targetServiceKey)
            statsRef.set(server!!.stats())
            return
        }

        enterForegroundCompat("Starting 127.0.0.1:$desiredPort…")

        stopBridgeInternal()
        try {
            val srv = LocalProxyServer(requestedPort = desiredPort, upstream = upstream)
            val boundPort = try {
                srv.start()
            } catch (e: Exception) {
                DastyabLogger.w("LocalProxyService", e, "Port %d busy; trying dynamic fallback", desiredPort)
                runCatching { srv.close() }
                LocalProxyServer(requestedPort = 0, upstream = upstream).also { it.start() }.also {
                    server = it
                }.actualPort
            }
            if (server == null) {
                server = srv
            }
            activeTargetRef.set(targetServiceKey)
            activeChainSpecRef.set(chainSpec)
            val currentStats = (server ?: srv).stats()
            statsRef.set(currentStats)
            enterForegroundCompat("127.0.0.1:$boundPort → ${upstream.describe()}")
            broadcastStateChanged()
            DastyabLogger.i("LocalProxyService", "Local proxy bridge active on 127.0.0.1:%d (%s)", boundPort, upstream.describe())
        } catch (t: Throwable) {
            DastyabLogger.e("LocalProxyService", t, "Failed to bind local proxy server")
            stopBridge()
            stopSelf()
        }
    }

    private fun stopBridge() {
        stopBridgeInternal()
        activeTargetRef.set(null)
        activeChainSpecRef.set("")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        statsRef.set(
            LocalProxyServer.Stats(
                running = false,
                port = -1,
                upstreamLabel = TunnelConfig.ProxyScheme.DIRECT.label,
                activeConnections = 0,
                totalConnections = statsRef.get().totalConnections,
                bytesTransferred = statsRef.get().bytesTransferred,
                lastTarget = statsRef.get().lastTarget,
                lastError = null
            )
        )
        broadcastStateChanged()
    }

    private fun stopBridgeInternal() {
        runCatching { server?.close() }
        server = null
    }

    private fun broadcastStateChanged() {
        val intent = Intent(ACTION_STATE_CHANGED).setPackage(packageName)
        runCatching { sendBroadcast(intent) }
    }

    private fun enterForegroundCompat(summary: String) {
        try {
            val notification = buildNotification(summary)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            DastyabLogger.w("LocalProxyService", t, "Failed to enter foreground notification state")
        }
    }

    private fun buildNotification(summary: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DastYab Local Proxy Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent HTTP & SOCKS5 local proxy port for AI Studio, Flow, and v2rayNG"
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("DastYab Local Proxy (HTTP + SOCKS5)")
            .setContentText(summary)
            .setSmallIcon(R.drawable.ic_notification_vpn)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop Proxy",
                    stopPending
                ).build()
            )
            .build()
    }
}
