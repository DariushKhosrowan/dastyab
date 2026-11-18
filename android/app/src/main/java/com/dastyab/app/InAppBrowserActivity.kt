package com.dastyab.app

import android.annotation.SuppressLint
import android.app.Activity
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

/**
 * InAppBrowserActivity — Built-in Royal Gold WebView browser for Google AI Studio & Google Flow.
 *
 * Features:
 *  • Modern Chrome-compatible User-Agent (removes `; wv` token) + persistent cookies & DOM storage
 *  • Compact top bar with Back, Forward, Reload, Live VPN Status dot, 1-Tap Auto-Fix, and Open in Chrome
 *  • Automatic OAuth fallback button ("Chrome") in case Google blocks embedded sign-in
 */
class InAppBrowserActivity : Activity() {

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
    private lateinit var vpnDot: View
    private var initialUrl: String = CommandCompat.AISTUDIO_URL
    private var receiverRegistered = false

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshVpnIndicator()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Google AI Studio"
        initialUrl = intent.getStringExtra(EXTRA_URL) ?: CommandCompat.AISTUDIO_URL

        try {
            setContentView(R.layout.activity_in_app_browser)
            webView = findViewById(R.id.browserWebView)
            progress = findViewById(R.id.browserProgress)
            tvTitle = findViewById(R.id.tvBrowserTitle)
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
                    if (UiSafety.isActivityAlive(this@InAppBrowserActivity)) {
                        progress.visibility = View.VISIBLE
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (UiSafety.isActivityAlive(this@InAppBrowserActivity)) {
                        progress.visibility = View.GONE
                    }
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
                webView.reload()
            }
            findViewById<Button>(R.id.btnBrowserAutoFix).setOnClickListener {
                val rescue = Intent(this, BlackoutRescueActivity::class.java).apply {
                    putExtra(BlackoutRescueActivity.EXTRA_AUTO_ACTION, BlackoutRescueActivity.ACTION_DNS_VPN)
                }
                UiSafety.safeStartActivity(this, rescue)
            }
            findViewById<Button>(R.id.btnBrowserOpenChrome).setOnClickListener {
                val currentUrl = webView.url?.takeIf { it.startsWith("http") } ?: initialUrl
                if (!UiSafety.safeStartActivity(this, Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))) {
                    Toast.makeText(this, getString(R.string.aistudio_no_browser), Toast.LENGTH_SHORT).show()
                }
            }

            registerVpnReceiver()
            refreshVpnIndicator()
            webView.loadUrl(initialUrl)
        } catch (t: Throwable) {
            DastyabLogger.e("InAppBrowser", t, "WebView unavailable; falling back to external browser")
            val opened = UiSafety.safeStartActivity(this, Intent(Intent.ACTION_VIEW, Uri.parse(initialUrl)))
            Toast.makeText(
                this,
                if (opened) "System WebView unavailable; opened in browser." else getString(R.string.aistudio_no_browser),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            runCatching { webView.onResume() }
        }
    }

    override fun onPause() {
        if (this::webView.isInitialized) {
            runCatching { webView.onPause() }
        }
        super.onPause()
    }

    private fun registerVpnReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(DastyabVpnService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun refreshVpnIndicator() {
        if (!UiSafety.isActivityAlive(this) || !this::vpnDot.isInitialized) return
        try {
            val status = DastyabVpnService.getStatus()
            vpnDot.background = getDrawable(
                when (status.state) {
                    DastyabVpnService.State.CONNECTED -> R.drawable.status_dot_green
                    DastyabVpnService.State.CONNECTING -> R.drawable.status_dot_orange
                    DastyabVpnService.State.DISCONNECTED -> R.drawable.status_dot_gray
                }
            )
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
            try { unregisterReceiver(vpnReceiver) } catch (_: Exception) {}
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
