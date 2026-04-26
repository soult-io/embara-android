package eu.stabpablo.trek

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity(), SettingsBottomSheet.Listener {

    private lateinit var webView: WebView
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        serverUrl = TrekPrefs.getServerUrl(this) ?: run {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        val rootLayout = createLayout()
        setContentView(rootLayout)

        setupBackNavigation()

        // C1: restoreState doesn't restore the page after process death — fall back to loadUrl
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
    private fun createLayout(): FrameLayout {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(false)
            // H5: Explicitly disable file/content access to prevent local file reads via XSS
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            webViewClient = createWebViewClient()
            webChromeClient = WebChromeClient()
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // H8: Explicitly disable third-party cookies
            setAcceptThirdPartyCookies(webView, false)
        }

        val density = resources.displayMetrics.density
        val touchTargetWidth = (80 * density).toInt()
        val touchTargetHeight = (48 * density).toInt()

        val handleTouchTarget = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.settings)
            setOnClickListener { showSettings() }

            val barWidth = (40 * density).toInt()
            val barHeight = (5 * density).toInt()
            val barRadius = (3 * density)

            val bar = View(this@MainActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x99555555.toInt())
                    cornerRadius = barRadius
                }
                elevation = 4 * density
            }

            addView(bar, FrameLayout.LayoutParams(barWidth, barHeight).apply {
                gravity = Gravity.CENTER
            })
        }

        return FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            addView(handleTouchTarget, FrameLayout.LayoutParams(touchTargetWidth, touchTargetHeight).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            })

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

    // M6: Guard against IllegalStateException after onSaveInstanceState
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
        TrekPrefs.clearServerUrl(this)
        startActivity(Intent(this, SetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // H2: Load server URL directly instead of reload() which might reload the error page
    override fun onClearCache() {
        android.webkit.WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webView.loadUrl(serverUrl)
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val url = uri.toString()
            return if (url.startsWith(serverUrl)) {
                false
            } else {
                if (uri.scheme == "http" || uri.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                true
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                // C2: Escape single quotes to prevent XSS breakout from JS onclick handler
                // M4: Add viewport meta tag for proper scaling on high-DPI devices
                val safeUrl = serverUrl
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;")
                view.loadDataWithBaseURL(
                    serverUrl,
                    """
                    <html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    </head><body style="display:flex;justify-content:center;align-items:center;
                    height:100vh;margin:0;font-family:sans-serif;background:#1a1a2e;color:#e0e0e0">
                    <div style="text-align:center;padding:20px">
                        <h2>No Connection</h2>
                        <p>Can't reach your Trek server.</p>
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
        if (::webView.isInitialized) webView.saveState(outState)
    }

    // M1: Pause/resume WebView to stop JS timers when backgrounded
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
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
