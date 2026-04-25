package eu.stabpablo.trek

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.text.htmlEncode
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var serverUrl: String = ""

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == SettingsActivity.RESULT_URL_CHANGED) {
            // URL was changed — redirect to setup
            startActivity(Intent(this, SetupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        } else if (result.resultCode == SettingsActivity.RESULT_CACHE_CLEARED) {
            webView.reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirect to setup if no server URL configured
        serverUrl = TrekPrefs.getServerUrl(this) ?: run {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        val rootLayout = createLayout()
        setContentView(rootLayout)

        setupBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
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

            webViewClient = createWebViewClient()
            webChromeClient = WebChromeClient()
        }

        CookieManager.getInstance().setAcceptCookie(true)

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(ContextCompat.getColor(context, R.color.settings_icon_tint))
            background = ContextCompat.getDrawable(context, R.drawable.settings_button_bg)
            contentDescription = getString(R.string.settings)
            alpha = 0.6f

            setOnClickListener {
                settingsLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        return FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            val buttonSize = (48 * resources.displayMetrics.density).toInt()
            val margin = (8 * resources.displayMetrics.density).toInt()

            addView(settingsButton, FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = margin
                marginEnd = margin
            })

            // Adjust settings button for system bars (status bar)
            ViewCompat.setOnApplyWindowInsetsListener(settingsButton) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val params = view.layoutParams as FrameLayout.LayoutParams
                params.topMargin = systemBars.top + margin
                params.marginEnd = systemBars.right + margin
                view.layoutParams = params
                insets
            }
        }
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
                val safeUrl = serverUrl.htmlEncode()
                view.loadData(
                    """
                    <html><body style="display:flex;justify-content:center;align-items:center;
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
                    "UTF-8"
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

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
