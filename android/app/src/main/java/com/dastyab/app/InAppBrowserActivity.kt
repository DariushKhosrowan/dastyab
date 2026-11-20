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
    private lateinit var btnAutoFix: Button
    private var initialUrl: String = CommandCompat.AISTUDIO_URL
    private var receiverRegistered = false
    private var lastDomAlertKey: String = ""
    private val failedEgressIps = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val failedChainSpecs = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile private var currentBannerEgressIp: String? = null
    @Volatile private var currentRouteChainSpec: String = ""
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
            btnAutoFix = findViewById(R.id.btnBrowserAutoFix)
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
                    webView.postDelayed({
                        if (UiSafety.isActivityAlive(this@InAppBrowserActivity)) {
                            inspectWebViewDomGeo(webView.url ?: url)
                        }
                    }, 3500L)
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
            findViewById<Button>(R.id.btnBrowserRotate).setOnClickListener {
                BrutalAntiGeoblockEngine.enforceStrictBypass(this)
                Toast.makeText(this, "Rotated VPN Layer & applied Brutal headers", Toast.LENGTH_SHORT).show()
                webView.reload()
            }
            findViewById<Button>(R.id.btnBrowserToggleDns).setOnClickListener {
                val settings = TunnelConfig.load(this)
                val newMode = if (settings.mode == TunnelConfig.Mode.DNS_BLACKOUT) TunnelConfig.Mode.SMART else TunnelConfig.Mode.DNS_BLACKOUT
                TunnelConfig.saveMode(this, newMode)
                Toast.makeText(this, "Switched mode -> ${newMode.name}", Toast.LENGTH_SHORT).show()
                applyProxyAndLoad(currentOrInitialUrl(), bypassCache = true)
            }
            findViewById<Button>(R.id.btnBrowserDesktop).setOnClickListener {
                val settings = webView.settings
                val ua = settings.userAgentString ?: ""
                if (ua.contains("Windows NT")) {
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                    Toast.makeText(this, "Switched to Mobile User-Agent", Toast.LENGTH_SHORT).show()
                } else {
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                    Toast.makeText(this, "Switched to Desktop User-Agent", Toast.LENGTH_SHORT).show()
                }
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
            btnAutoFix.setOnClickListener {
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
            setBrowserHalo(StatusHaloView.HaloState.ERROR, R.drawable.status_dot_red)
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

            setBrowserHalo(StatusHaloView.HaloState.ERROR, R.drawable.status_dot_red)
            tvRouteStatus.text = getString(R.string.browser_geo_blocked)
            tvRouteStatus.setTextColor(ThemeManager.resolveColor(this, R.attr.dastyabStatusError, R.color.colorStatusError))
            currentBannerEgressIp?.takeIf { it.isNotBlank() }?.let { failedEgressIps.add(it) }
            currentRouteChainSpec.takeIf { it.isNotBlank() }?.let { failedChainSpecs.add(it) }

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
        currentBannerEgressIp?.takeIf { it.isNotBlank() }?.let { failedEgressIps.add(it) }
        currentRouteChainSpec.takeIf { it.isNotBlank() }?.let { failedChainSpecs.add(it) }
        val excludedIpsSnapshot = synchronized(failedEgressIps) { failedEgressIps.toSet() }
        val excludedSpecsSnapshot = synchronized(failedChainSpecs) { failedChainSpecs.toSet() }

        if (this::btnAutoFix.isInitialized) btnAutoFix.isEnabled = false
        Toast.makeText(
            this,
            getString(R.string.browser_auto_chain_running, target.displayName),
            Toast.LENGTH_LONG
        ).show()
        setBrowserHalo(StatusHaloView.HaloState.CONNECTING, R.drawable.status_dot_orange)
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
                    maxCandidates = if (target == GeoBypassEngine.TargetService.GOOGLE_FLOW) 56 else 48,
                    excludedEgressIps = excludedIpsSnapshot,
                    excludeCurrentRoute = true,
                    excludedChainSpecs = excludedSpecsSnapshot,
                    maxRounds = 3
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
                if (this::btnAutoFix.isInitialized) btnAutoFix.isEnabled = true
                Toast.makeText(
                    this,
                    if (result.success) result.summary else getString(R.string.browser_auto_chain_failed, target.displayName),
                    Toast.LENGTH_LONG
                ).show()
                if (result.success) {
                    lastDomAlertKey = ""
                    clearServiceGeoCookiesAndCache(target)
                    val reloadTargetUrl = if (target == GeoBypassEngine.TargetService.GOOGLE_FLOW) {
                        CommandCompat.FLOW_URL
                    } else {
                        CommandCompat.AISTUDIO_URL
                    }
                    applyProxyAndLoad(reloadTargetUrl, bypassCache = true)
                } else {
                    result.report.egress?.ip?.takeIf { it.isNotBlank() }?.let { failedEgressIps.add(it) }
                    probeBannerEgress(GeoBypassEngine.resolveCurrentRoute(this, targetService = target), currentOrInitialUrl())
                    UiSafety.safeShowDialog(
                        this,
                        android.app.AlertDialog.Builder(this)
                            .setTitle(getString(R.string.browser_auto_chain_failed_title))
                            .setMessage("${result.summary}\n\n${result.report.recommendation}")
                            .setPositiveButton(getString(R.string.browser_btn_retry_chain)) { _, _ ->
                                autoChainCurrentService(target)
                            }
                            .setNeutralButton(getString(R.string.access_sheet_switch_route_btn)) { _, _ ->
                                GoogleAccessSheet.show(this, target.displayName, currentOrInitialUrl()) {
                                    applyProxyAndLoad(currentOrInitialUrl(), bypassCache = true)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                    )
                }
            }
        }
    }

    /** Clears cached geo-decision cookies & HTTP cache for AI Studio / Flow without clearing accounts.google.com auth. */
    private fun clearServiceGeoCookiesAndCache(target: GeoBypassEngine.TargetService) {
        runCatching {
            val cm = CookieManager.getInstance()
            val urls = if (target == GeoBypassEngine.TargetService.GOOGLE_FLOW) {
                listOf("https://flow.google.com", "https://labs.google")
            } else {
                listOf("https://aistudio.google.com", "https://ai.google.dev", "https://generativelanguage.googleapis.com")
            }
            for (u in urls) {
                val raw = cm.getCookie(u).orEmpty()
                for (pair in raw.split(';')) {
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(u, "$name=; Max-Age=0; path=/")
                    }
                }
            }
            cm.flush()
        }
        if (this::webView.isInitialized) {
            runCatching { webView.clearCache(true) }
        }
    }

    private fun applyProxyAndLoad(urlToLoad: String, bypassCache: Boolean = false) {
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
                    waited < 2500
                ) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    waited += 50
                }
                route = GeoBypassEngine.resolveCurrentRoute(this, settings, targetService)
            }

            currentRouteChainSpec = route.upstreamChainSpec
            runOnUiThread {
                if (!UiSafety.isActivityAlive(this)) return@runOnUiThread
                applyWebViewProxy(route.webViewHttpProxyPort) {
                    refreshRouteAndIndicator(route)
                    if (UiSafety.isActivityAlive(this)) {
                        lastDomAlertKey = ""
                        if (bypassCache) {
                            webView.loadUrl(
                                urlToLoad,
                                mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")
                            )
                        } else {
                            webView.loadUrl(urlToLoad)
                        }
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
            currentRouteChainSpec = route.upstreamChainSpec
            report.egress?.ip?.takeIf { it.isNotBlank() }?.let { ip ->
                currentBannerEgressIp = ip
                if (!ready) failedEgressIps.add(ip)
            }
            runOnUiThread {
                if (!UiSafety.isActivityAlive(this)) return@runOnUiThread
                val egress = report.egress
                if (egress == null) {
                    setBrowserHalo(StatusHaloView.HaloState.WARNING, R.drawable.status_dot_orange)
                    tvRouteStatus.text = getString(R.string.browser_geo_check_hint, route.routeLabel)
                    tvRouteStatus.setTextColor(ThemeManager.resolveColor(this, R.attr.dastyabStatusWarning, R.color.colorStatusWarning))
                } else {
                    setBrowserHalo(
                        if (ready) StatusHaloView.HaloState.CONNECTED else StatusHaloView.HaloState.ERROR,
                        if (ready) R.drawable.status_dot_green else R.drawable.status_dot_red
                    )
                    tvRouteStatus.text = if (ready) {
                        BypassStateStore.markBypassed(this@InAppBrowserActivity, target.displayName, route.routeLabel)
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

    private fun setBrowserHalo(haloState: StatusHaloView.HaloState, fallbackDrawableRes: Int) {
        if (!this::vpnDot.isInitialized) return
        val halo = vpnDot as? StatusHaloView
        if (halo != null) {
            halo.setHaloState(haloState)
        } else {
            vpnDot.background = getDrawable(fallbackDrawableRes)
        }
    }

    private fun refreshRouteAndIndicator(precomputed: GeoBypassEngine.RouteResolution? = null) {
        if (!UiSafety.isActivityAlive(this) || !this::vpnDot.isInitialized) return
        try {
            val dastyabStatus = DastyabVpnService.getStatus()
            val externalVpn = NetworkUtils.hasActiveExternalVpn(this)
            val bridgeRunning = LocalProxyService.isRunning()
            when {
                dastyabStatus.state == DastyabVpnService.State.CONNECTED || externalVpn || bridgeRunning ->
                    setBrowserHalo(StatusHaloView.HaloState.CONNECTED, R.drawable.status_dot_green)
                dastyabStatus.state == DastyabVpnService.State.CONNECTING ->
                    setBrowserHalo(StatusHaloView.HaloState.CONNECTING, R.drawable.status_dot_orange)
                else ->
                    setBrowserHalo(StatusHaloView.HaloState.IDLE, R.drawable.status_dot_gray)
            }
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
