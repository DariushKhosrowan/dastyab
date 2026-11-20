package com.dastyab.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/**
 * InAppBrowserActivity — Built-in Royal Gold WebView browser for Google AI Studio & Google Flow.
 *
 * Geo-Bypass & Multi-Layer Proxy Features:
 *  • Per-app `androidx.webkit.ProxyController` proxy override so AI Studio & Flow can route via
 *    any local HTTP or SOCKS5 proxy port (`127.0.0.1:10809`, `:10808`, `:2080`, `:9050`, `:10850`)
 *    WITHOUT stealing Android's single `VpnService` slot — allowing Lantern or another VPN on top!
 *  • Automatic SOCKS5 -> HTTP translation via [LocalProxyService] whenever a SOCKS5 upstream is chosen
 *  • Live Egress IP + Country ISO banner checking Google Flow's 151-country gate & `/unsupported-country`
 *  • 1-tap Access Switcher (`Proxy / VPN`), `Geo Check`, and `AI Studio ↔ Flow` switcher
 */
class InAppBrowserActivity : DastyabActivity() {

    companion object {
        const val EXTRA_URL = "extra_browser_url"
        const val EXTRA_TITLE = "extra_browser_title"

        fun launch(context: Context, title: String, url: String) {
            val intent = Intent(context, InAppBrowserActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvRouteStatus: TextView
    private lateinit var vpnDot: View
    private var initialUrl: String = CommandCompat.AISTUDIO_URL
    private var receiverRegistered = false
    private var lastDomAlertKey: String = ""
    private val autoChainInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainExecutor = Executor { runnable -> runOnUiThread(runnable) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshRouteAndIndicator()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.browser_default_aistudio)
        initialUrl = intent.getStringExtra(EXTRA_URL) ?: CommandCompat.AISTUDIO_URL

        try {
            setContentView(R.layout.activity_in_app_browser)
            webView = findViewById(R.id.browserWebView)
            progress = findViewById(R.id.browserProgress)
            tvTitle = findViewById(R.id.tvBrowserTitle)
            tvRouteStatus = findViewById(R.id.tvBrowserRouteStatus)
            vpnDot = findViewById(R.id.browserVpnDot)
            tvTitle.text = title

            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Strip "; wv" so Google web apps recognize a standard mobile Chrome engine
            val defaultUa = settings.userAgentString ?: ""
            settings.userAgentString = defaultUa.replace("; wv)", ")").replace("Version/4.0 ", "")

            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(webView, true)
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!UiSafety.isActivityAlive(this@InAppBrowserActivity)) return
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }

                override fun onReceivedTitle(view: WebView?, pageTitle: String?) {
                    if (!UiSafety.isActivityAlive(this@InAppBrowserActivity)) return
                    if (!pageTitle.isNullOrBlank()) {
                        tvTitle.text = pageTitle
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (!UiSafety.isActivityAlive(this@InAppBrowserActivity)) return
                    progress.visibility = View.VISIBLE
                    checkUnsupportedRedirect(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!UiSafety.isActivityAlive(this@InAppBrowserActivity)) return
                    progress.visibility = View.GONE
                    checkUnsupportedRedirect(url)
                    inspectWebViewDomGeo(url)
                    // Re-check once SPA hydration completes (e.g. AI Studio / Flow Angular shell)
                    webView.postDelayed({
                        if (UiSafety.isActivityAlive(this@InAppBrowserActivity)) {
                            inspectWebViewDomGeo(webView.url ?: url)
                        }
                    }, 1500L)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && UiSafety.isActivityAlive(this@InAppBrowserActivity)) {
                        Toast.makeText(
                            this@InAppBrowserActivity,
                            getString(R.string.browser_load_error_hint),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            findViewById<Button>(R.id.btnBrowserClose).setOnClickListener { finish() }
            findViewById<Button>(R.id.btnBrowserBack).setOnClickListener {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
            findViewById<Button>(R.id.btnBrowserForward).setOnClickListener {
                if (webView.canGoForward()) webView.goForward()
            }
            findViewById<Button>(R.id.btnBrowserReload).setOnClickListener {
                lastDomAlertKey = ""
                webView.reload()
            }
            findViewById<Button>(R.id.btnBrowserProxyMode).setOnClickListener {
                GoogleAccessSheet.show(this, tvTitle.text.toString(), currentOrInitialUrl()) {
                    applyProxyAndLoad(currentOrInitialUrl())
                }
            }
            tvRouteStatus.setOnClickListener {
                GoogleAccessSheet.show(this, tvTitle.text.toString(), currentOrInitialUrl()) {
                    applyProxyAndLoad(currentOrInitialUrl())
                }
            }
            findViewById<Button>(R.id.btnBrowserGeoCheck).setOnClickListener {
                GoogleAccessSheet.runLiveGeoCheckDialog(this, currentOrInitialUrl())
            }
            findViewById<Button>(R.id.btnBrowserSwitchSite).setOnClickListener {
                val cur = currentOrInitialUrl()
                val nextUrl = if (cur.contains("flow.google.com", ignoreCase = true)) {
                    CommandCompat.AISTUDIO_URL
                } else {
                    CommandCompat.FLOW_URL
                }
                val nextTitle = if (nextUrl == CommandCompat.AISTUDIO_URL) {
                    getString(R.string.browser_default_aistudio)
                } else {
                    getString(R.string.browser_default_flow)
                }
                initialUrl = nextUrl
                tvTitle.text = nextTitle
                lastDomAlertKey = ""
                applyProxyAndLoad(nextUrl)
            }
            findViewById<Button>(R.id.btnBrowserAutoFix).setOnClickListener {
                val target = GeoBypassEngine.TargetService.fromUrl(currentOrInitialUrl())
                autoChainCurrentService(target)
            }
            findViewById<Button>(R.id.btnBrowserOpenChrome).setOnClickListener {
                val currentUrl = currentOrInitialUrl()
                if (!UiSafety.safeStartActivity(this, Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))) {
                    Toast.makeText(this, getString(R.string.aistudio_no_browser), Toast.LENGTH_SHORT).show()
                }
            }

            registerStateReceivers()
            applyProxyAndLoad(initialUrl)
        } catch (t: Throwable) {
            DastyabLogger.e("InAppBrowser", t, "WebView unavailable; falling back to external browser")
            val opened = UiSafety.safeStartActivity(this, Intent(Intent.ACTION_VIEW, Uri.parse(initialUrl)))
            Toast.makeText(
                this,
                if (opened) getString(R.string.browser_webview_fallback) else getString(R.string.aistudio_no_browser),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun currentOrInitialUrl(): String =
        webView.url?.takeIf { it.startsWith("http") } ?: initialUrl

    private fun checkUnsupportedRedirect(url: String?) {
        if (url.isNullOrBlank()) return
        val target = GeoBypassEngine.TargetService.fromUrl(url)
        val blocked = when (target) {
            GeoBypassEngine.TargetService.GOOGLE_FLOW ->
                GeoBypassEngine.isFlowUnsupportedResponse(200, url, "")
            GeoBypassEngine.TargetService.AI_STUDIO ->
                GeoBypassEngine.isAiStudioUnsupportedResponse(200, url, url, "")
        }
        if (blocked) {
            tvRouteStatus.text = getString(R.string.browser_geo_blocked)
            tvRouteStatus.setTextColor(ThemeManager.resolveColor(this, R.attr.dastyabStatusError, R.color.colorStatusError))
        }
    }

    private fun inspectWebViewDomGeo(urlHint: String?) {
        if (!this::webView.isInitialized || !UiSafety.isActivityAlive(this)) return
        val js = "(function(){try{return({href:location.href,text:(document.body&&document.body.innerText?document.body.innerText:'').slice(0,6000),html:(document.documentElement?document.documentElement.innerHTML:'').slice(0,8192)});}catch(e){return null;}})();"
        webView.evaluateJavascript(js) { raw ->
            if (!UiSafety.isActivityAlive(this) || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
            val obj = runCatching {
                val jsonText = if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as? String ?: "" else raw
                org.json.JSONObject(jsonText)
            }.getOrNull() ?: return@evaluateJavascript

            val href = obj.optString("href", urlHint.orEmpty())
            val bodyText = obj.optString("text", "")
            val html = obj.optString("html", "")
            val combined = "$bodyText\n$html"
            val target = GeoBypassEngine.TargetService.fromUrl(href.ifBlank { urlHint })
            val reason = when (target) {
                GeoBypassEngine.TargetService.GOOGLE_FLOW -> when {
                    GeoBypassEngine.isFlowUnsupportedResponse(200, href, combined) ->
                        "Flow unsupported region / geo-restriction"
                    GeoBypassEngine.isFlowAccountGateResponse(combined) ->
                        "Flow account / country access gate"
                    else -> null
                }
                GeoBypassEngine.TargetService.AI_STUDIO -> when {
                    GeoBypassEngine.isAiStudioUnsupportedResponse(200, href, href, combined) ->
                        "AI Studio unsupported region / geo-restriction"
                    else -> null
                }
            } ?: return@evaluateJavascript

            tvRouteStatus.text = getString(R.string.browser_geo_blocked)
            tvRouteStatus.setTextColor(ThemeManager.resolveColor(this, R.attr.dastyabStatusError, R.color.colorStatusError))

            val key = "${target.key}:${href.substringBefore('?')}"
            if (key == lastDomAlertKey || autoChainInFlight.get()) return@evaluateJavascript
            lastDomAlertKey = key

            UiSafety.safeShowDialog(
                this,
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.browser_dom_geo_blocked_title, target.displayName))
                    .setMessage(getString(R.string.browser_dom_geo_blocked_msg, reason, target.displayName))
                    .setPositiveButton(getString(R.string.browser_btn_auto_chain)) { _, _ ->
                        autoChainCurrentService(target)
                    }
                    .setNeutralButton(getString(R.string.access_sheet_switch_route_btn)) { _, _ ->
                        GoogleAccessSheet.show(this, target.displayName, href) {
                            applyProxyAndLoad(currentOrInitialUrl())
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
            )
        }
    }

    private fun autoChainCurrentService(target: GeoBypassEngine.TargetService) {
        if (!autoChainInFlight.compareAndSet(false, true)) return
        Toast.makeText(
            this,
            getString(R.string.browser_auto_chain_running, target.displayName),
            Toast.LENGTH_LONG
        ).show()
        if (this::tvRouteStatus.isInitialized) {
            tvRouteStatus.text = getString(R.string.browser_auto_chain_running, target.displayName)
            tvRouteStatus.setTextColor(ThemeManager.resolveColor(this, R.attr.dastyabStatusWarning, R.color.colorStatusWarning))
        }
        thread(isDaemon = true, name = "dastyab-browser-autochain") {
            val result = try {
                GeoBypassEngine.discoverAndChainBestRoute(
                    context = this,
                    targetService = target,
                    allowHarvest = true,
                    maxCandidates = 18
                ) { step ->
                    runOnUiThread {
                        if (UiSafety.isActivityAlive(this) && this::tvRouteStatus.isInitialized) {
                            tvRouteStatus.text = step
                        }
                    }
                }
            } finally {
                autoChainInFlight.set(false)
            }
            runOnUiThread {
                if (!UiSafety.isActivityAlive(this)) return@runOnUiThread
                Toast.makeText(
                    this,
                    if (result.success) result.summary else getString(R.string.browser_auto_chain_failed, target.displayName),
                    Toast.LENGTH_LONG
                ).show()
                if (result.success) {
                    lastDomAlertKey = ""
                    val reloadTargetUrl = if (target == GeoBypassEngine.TargetService.GOOGLE_FLOW) {
                        CommandCompat.FLOW_URL
                    } else {
                        CommandCompat.AISTUDIO_URL
                    }
                    applyProxyAndLoad(reloadTargetUrl)
                } else {
                    probeBannerEgress(GeoBypassEngine.resolveCurrentRoute(this, targetService = target), currentOrInitialUrl())
                }
            }
        }
    }

    private fun applyProxyAndLoad(urlToLoad: String) {
        thread(isDaemon = true, name = "dastyab-browser-route") {
            val settings = TunnelConfig.load(this)
            val targetService = GeoBypassEngine.TargetService.fromUrl(urlToLoad)
            var route = GeoBypassEngine.resolveCurrentRoute(this, settings, targetService)
            if (route.shouldStartLocalBridge) {
                LocalProxyService.startSafely(this, targetService.key)
                // Wait for LocalProxyServer to bind and switch active chain spec for this targetService
                var waited = 0
                while (
                    (LocalProxyService.activePort() == null ||
                        LocalProxyService.activeChainSpec() != route.upstreamChainSpec) &&
                    waited < 1200
                ) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { break }
                    waited += 60
                }
                route = GeoBypassEngine.resolveCurrentRoute(this, settings, targetService)
            }

            runOnUiThread {
                if (!UiSafety.isActivityAlive(this)) return@runOnUiThread
                applyWebViewProxy(route.webViewHttpProxyPort) {
                    refreshRouteAndIndicator(route)
                    if (UiSafety.isActivityAlive(this)) {
                        lastDomAlertKey = ""
                        webView.loadUrl(urlToLoad)
                    }
                    probeBannerEgress(route, urlToLoad)
                }
            }
        }
    }

    private fun applyWebViewProxy(httpProxyPort: Int?, onApplied: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (httpProxyPort != null) {
                Toast.makeText(
                    this,
                    getString(R.string.browser_proxy_unsupported),
                    Toast.LENGTH_LONG
                ).show()
            }
            onApplied()
            return
        }

        val controller = ProxyController.getInstance()
        if (httpProxyPort != null) {
            val cfg = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$httpProxyPort")
                .build()
            controller.setProxyOverride(cfg, mainExecutor, Runnable { onApplied() })
        } else {
            controller.clearProxyOverride(mainExecutor, Runnable { onApplied() })
        }
    }

    private fun probeBannerEgress(route: GeoBypassEngine.RouteResolution, targetUrl: String = initialUrl) {
        thread(isDaemon = true, name = "dastyab-browser-geo") {
            val report = GeoBypassEngine.runAccessProbe(route, timeoutMs = 4200)
            val target = route.targetService ?: GeoBypassEngine.TargetService.fromUrl(targetUrl)
            val ready = report.targetReady(target)
            runOnUiThread {
                if (!UiSafety.isActivityAlive(this)) return@runOnUiThread
                val egress = report.egress
                if (egress == null) {
                    tvRouteStatus.text = getString(R.string.browser_geo_check_hint, route.routeLabel)
                    tvRouteStatus.setTextColor(ThemeManager.resolveColor(this, R.attr.dastyabStatusWarning, R.color.colorStatusWarning))
                } else {
                    tvRouteStatus.text = if (ready) {
                        getString(R.string.browser_banner_ready, target.displayName, route.routeLabel, egress.countryCode, egress.ip)
                    } else {
                        getString(R.string.browser_banner_blocked, target.displayName, route.routeLabel, egress.countryCode)
                    }
                    tvRouteStatus.setTextColor(
                        if (ready) ThemeManager.resolveColor(this, R.attr.dastyabStatusReady, R.color.colorStatusReady)
                        else ThemeManager.resolveColor(this, R.attr.dastyabStatusError, R.color.colorStatusError)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            runCatching { webView.onResume() }
        }
        refreshRouteAndIndicator()
    }

    override fun onPause() {
        if (this::webView.isInitialized) {
            runCatching { webView.onPause() }
        }
        super.onPause()
    }

    private fun registerStateReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(DastyabVpnService.ACTION_STATE_CHANGED)
            addAction(LocalProxyService.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun refreshRouteAndIndicator(precomputed: GeoBypassEngine.RouteResolution? = null) {
        if (!UiSafety.isActivityAlive(this) || !this::vpnDot.isInitialized) return
        try {
            val dastyabStatus = DastyabVpnService.getStatus()
            val externalVpn = NetworkUtils.hasActiveExternalVpn(this)
            val bridgeRunning = LocalProxyService.isRunning()
            vpnDot.background = getDrawable(
                when {
                    dastyabStatus.state == DastyabVpnService.State.CONNECTED || externalVpn || bridgeRunning ->
                        R.drawable.status_dot_green
                    dastyabStatus.state == DastyabVpnService.State.CONNECTING ->
                        R.drawable.status_dot_orange
                    else ->
                        R.drawable.status_dot_gray
                }
            )
            if (precomputed != null && this::tvRouteStatus.isInitialized) {
                tvRouteStatus.text = getString(R.string.browser_checking_country, precomputed.routeLabel)
            }
        } catch (_: Throwable) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        if (this::webView.isInitialized) {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            }
        }
        super.onDestroy()
    }
}
