package io.soult.embara

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity(), SettingsBottomSheet.Listener {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var serverUrl: String = ""
    private var serverHost: String = ""
    private var isShowingErrorPage = false

    // Timeout failsafe: dismiss spinner if page never finishes loading
    private val refreshTimeout = Runnable {
        if (::swipeRefresh.isInitialized) {
            swipeRefresh.isRefreshing = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        serverUrl = EmbaraPrefs.getServerUrl(this) ?: run {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        serverHost = Uri.parse(serverUrl).host?.lowercase().orEmpty()

        isShowingErrorPage = savedInstanceState?.getBoolean(KEY_ERROR_PAGE, false) ?: false

        val rootLayout = createLayout()
        setContentView(rootLayout)

        setupBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            if (webView.url.isNullOrEmpty()) {
                webView.loadUrl(serverUrl)
            }
        } else {
            webView.loadUrl(serverUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createLayout(): LinearLayout {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            overScrollMode = View.OVER_SCROLL_NEVER

            webViewClient = createWebViewClient()
            webChromeClient = createWebChromeClient()
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        swipeRefresh = SwipeRefreshLayout(this).apply {
            addView(webView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // Only trigger refresh when WebView is scrolled to the top
            setOnChildScrollUpCallback { _, _ ->
                webView.canScrollVertically(-1)
            }

            setOnRefreshListener {
                scheduleRefreshTimeout()
                if (isShowingErrorPage) {
                    webView.loadUrl(serverUrl)
                } else {
                    webView.reload()
                }
            }

            setColorSchemeColors(ContextCompat.getColor(this@MainActivity, R.color.embara_accent))
            setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this@MainActivity, R.color.embara_bg))
            contentDescription = getString(R.string.pull_to_refresh)

            // Disabled by default; enabled only on dashboard pages
            isEnabled = false
        }

        val density = resources.displayMetrics.density
        val bottomBarHeight = (40 * density).toInt()

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.embara_bg))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.settings)
            setOnClickListener { showSettings() }

            val hPad = (12 * density).toInt()
            setPadding(hPad, 0, hPad, 0)

            val icon = ImageView(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_manage)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.embara_accent))
                val iconSize = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            addView(icon)

            val label = TextView(this@MainActivity).apply {
                text = getString(R.string.settings)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.embara_text))
                textSize = 14f
                val labelStart = (8 * density).toInt()
                setPadding(labelStart, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)

            val version = TextView(this@MainActivity).apply {
                text = getString(R.string.settings_about_version, getAppVersion())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.embara_text_secondary))
                textSize = 11f
            }
            addView(version)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(swipeRefresh, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            addView(bottomBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bottomBarHeight
            ))

            ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
                val types = WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
                val insets = windowInsets.getInsets(types)
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                WindowInsetsCompat.Builder(windowInsets)
                    .setInsets(types, Insets.NONE)
                    .build()
            }
        }
    }

    private fun updateSwipeRefreshForUrl(url: String?) {
        if (!::swipeRefresh.isInitialized) return
        val path = url?.let { Uri.parse(it).path }?.trimEnd('/') ?: ""
        swipeRefresh.isEnabled = path.isEmpty() || path == "/dashboard" || path.startsWith("/dashboard/")
    }

    private fun scheduleRefreshTimeout() {
        swipeRefresh.handler?.removeCallbacks(refreshTimeout)
        swipeRefresh.handler?.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MS)
    }

    private fun cancelRefreshTimeout() {
        if (::swipeRefresh.isInitialized) {
            swipeRefresh.handler?.removeCallbacks(refreshTimeout)
        }
    }

    private fun dismissRefreshSpinner() {
        cancelRefreshTimeout()
        if (::swipeRefresh.isInitialized) {
            swipeRefresh.isRefreshing = false
        }
    }

    private fun showSettings() {
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) != null) return
        val sheet = SettingsBottomSheet.newInstance(
            serverUrl = serverUrl,
            appVersion = getAppVersion()
        )
        sheet.show(supportFragmentManager, SettingsBottomSheet.TAG)
    }

    private fun getAppVersion(): String = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            ).versionName ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }
    } catch (_: Exception) {
        "unknown"
    }

    override fun onChangeServer() {
        EmbaraPrefs.clearServerUrl(this)
        startActivity(Intent(this, SetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onClearCache() {
        android.webkit.WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        if (::webView.isInitialized) webView.loadUrl(serverUrl)
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val host = uri.host?.lowercase().orEmpty()
            val scheme = uri.scheme?.lowercase()
            val isInternal = (scheme == "https" || scheme == "http") &&
                (host == serverHost || host.endsWith(".$serverHost"))
            return if (isInternal) {
                false
            } else {
                if (scheme == "http" || scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                true
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Only dismiss when fully loaded (not on intermediate redirects)
            if (view.progress >= 100) {
                dismissRefreshSpinner()
            }
            val pageHost = if (url != null) Uri.parse(url).host?.lowercase().orEmpty() else ""
            if (url != null && (pageHost == serverHost || pageHost.endsWith(".$serverHost")) && !url.startsWith("data:")) {
                isShowingErrorPage = false
            }
            updateSwipeRefreshForUrl(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            updateSwipeRefreshForUrl(url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                isShowingErrorPage = true
                dismissRefreshSpinner()

                val safeUrl = UrlValidator.sanitizeForHtml(serverUrl)
                view.loadDataWithBaseURL(
                    null,
                    """
                    <html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    </head><body style="display:flex;justify-content:center;align-items:center;
                    height:100vh;margin:0;font-family:sans-serif;background:#1a1a2e;color:#e0e0e0">
                    <div style="text-align:center;padding:20px">
                        <h2>No Connection</h2>
                        <p>Can't reach your TREK server.</p>
                        <p style="font-size:13px;opacity:0.6">$safeUrl</p>
                        <button onclick="location.href='$safeUrl'"
                            style="margin-top:16px;padding:12px 24px;border:none;border-radius:8px;
                            background:#4a90d9;color:white;font-size:16px;cursor:pointer">
                            Retry
                        </button>
                    </div></body></html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            isShowingErrorPage = true
            dismissRefreshSpinner()
            handler.cancel()

            val safeUrl = serverUrl
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
            view.loadDataWithBaseURL(
                null,
                """
                <html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                </head><body style="display:flex;justify-content:center;align-items:center;
                height:100vh;margin:0;font-family:sans-serif;background:#1a1a2e;color:#e0e0e0">
                <div style="text-align:center;padding:20px">
                    <h2>SSL Error</h2>
                    <p>Can't establish a secure connection to your TREK server.</p>
                    <p style="font-size:13px;opacity:0.6">$safeUrl</p>
                    <p style="font-size:13px;opacity:0.6">Check the server's SSL certificate.</p>
                    <button onclick="location.href='$safeUrl'"
                        style="margin-top:16px;padding:12px 24px;border:none;border-radius:8px;
                        background:#4a90d9;color:white;font-size:16px;cursor:pointer">
                        Retry
                    </button>
                </div></body></html>
                """.trimIndent(),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    private fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 100) {
                dismissRefreshSpinner()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ERROR_PAGE, isShowingErrorPage)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        cancelRefreshTimeout()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY_ERROR_PAGE = "isShowingErrorPage"
        private const val REFRESH_TIMEOUT_MS = 15_000L
    }
}
