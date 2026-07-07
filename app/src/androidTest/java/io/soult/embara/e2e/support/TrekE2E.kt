package io.soult.embara.e2e.support

import android.app.Instrumentation
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import io.soult.embara.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared driver for TREK E2E journeys, so the login flow + WebView plumbing live in one place.
 *
 * Provides: reflection into MainActivity's private WebView / SwipeRefreshLayout; a main-thread
 * evaluateJavascript bridge; TREK login (credentials only ever spliced via JSONObject.quote — never
 * logged, returned, or read back); and the app-behavioral "reached the authenticated dashboard" signal
 * (MainActivity enables pull-to-refresh only on dashboard routes, and the login form disappears on a
 * real login). All WebView / SwipeRefreshLayout access is marshalled to the main thread.
 */
class TrekE2E(private val instrumentation: Instrumentation) {

    companion object {
        const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        const val LOGIN_TIMEOUT_MS = 40_000L
        const val JS_RESULT_SECONDS = 10L
        const val POLL_MS = 300L
        // Login attempts before giving up, with a backoff between them — rides out a transient slow or
        // throttled login round-trip against the shared live test server.
        const val LOGIN_ATTEMPTS = 2
        const val LOGIN_RETRY_BACKOFF_MS = 6_000L
    }

    fun clearCookies() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    fun webViewOf(scenario: ActivityScenario<MainActivity>): WebView {
        val holder = arrayOfNulls<WebView>(1)
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
            holder[0] = field.get(activity) as WebView
        }
        assertNotNull("Could not reflectively obtain MainActivity.webView", holder[0])
        return holder[0]!!
    }

    fun swipeRefreshOf(scenario: ActivityScenario<MainActivity>): SwipeRefreshLayout {
        val holder = arrayOfNulls<SwipeRefreshLayout>(1)
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("swipeRefresh").apply { isAccessible = true }
            holder[0] = field.get(activity) as SwipeRefreshLayout
        }
        assertNotNull("Could not reflectively obtain MainActivity.swipeRefresh", holder[0])
        return holder[0]!!
    }

    fun evalJs(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                box[0] = value
                latch.countDown()
            }
        }
        if (!latch.await(JS_RESULT_SECONDS, TimeUnit.SECONDS)) {
            throw AssertionError("Timed out waiting for evaluateJavascript result")
        }
        return box[0] ?: "null"
    }

    /** Whether the login form (a password field) is currently in the DOM. */
    fun loginFormPresent(webView: WebView): Boolean =
        evalJs(webView, "String(!!document.querySelector('input[type=password]'))").trim('"') == "true"

    /** Waits until the login form has rendered. */
    fun waitForLoginForm(webView: WebView): Boolean {
        val deadline = System.currentTimeMillis() + PAGE_LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (loginFormPresent(webView)) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /**
     * Fills the login form and submits via [TrekLoginPage] (Espresso-Web / WebDriver atoms, with
     * built-in synchronization and resilient semantic selectors). Throws if the form can't be driven;
     * the password never appears in any error or log.
     */
    fun signIn(userEmail: String, password: String) = TrekLoginPage.signIn(userEmail, password)

    /** Non-secret: the current route path only (never the full URL / query / token). */
    fun currentPath(webView: WebView): String = evalJs(webView, "String(location.pathname)").trim('"')

    /**
     * Authenticated when BOTH hold: the login form is gone (a failed login stays on the form) AND
     * MainActivity has enabled pull-to-refresh (its dashboard-route gating). Requiring the login form
     * to disappear is what makes this prove real authentication, not merely PTR on a root-path login.
     */
    fun waitForAuthenticatedDashboard(
        webView: WebView,
        swipeRefresh: SwipeRefreshLayout,
        timeoutMs: Long = LOGIN_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val loginGone = !loginFormPresent(webView)
            val enabled = arrayOf(false)
            instrumentation.runOnMainSync { enabled[0] = swipeRefresh.isEnabled }
            if (loginGone && enabled[0]) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Single-shot check: authenticated dashboard == login form gone AND pull-to-refresh enabled. */
    private fun isAuthenticatedDashboard(webView: WebView, swipeRefresh: SwipeRefreshLayout): Boolean {
        if (loginFormPresent(webView)) return false
        val enabled = arrayOf(false)
        instrumentation.runOnMainSync { enabled[0] = swipeRefresh.isEnabled }
        return enabled[0]
    }

    /**
     * Reaches the authenticated dashboard and returns the (WebView, SwipeRefreshLayout) pair.
     *
     * SESSION REUSE: if a prior test in the process already authenticated (its cookies weren't cleared),
     * the app auto-lands on the dashboard and this returns WITHOUT logging in again — keeping the
     * login-per-test count low so the shared live TREK test server doesn't rate-limit the suite. Only
     * when the login form is actually shown does it log in, with a bounded retry to ride out a transient
     * throttled/slow round-trip. Requires [E2EConfig.hasCredentials].
     */
    fun loginAndReachDashboard(
        scenario: ActivityScenario<MainActivity>,
    ): Pair<WebView, SwipeRefreshLayout> {
        val webView = webViewOf(scenario)
        val swipeRefresh = swipeRefreshOf(scenario)

        // Wait for the app to settle into either the dashboard (reused session) or the login form.
        val deadline = System.currentTimeMillis() + PAGE_LOAD_TIMEOUT_MS
        var sawLoginForm = false
        while (System.currentTimeMillis() < deadline) {
            if (isAuthenticatedDashboard(webView, swipeRefresh)) return webView to swipeRefresh
            if (loginFormPresent(webView)) {
                sawLoginForm = true
                break
            }
            Thread.sleep(POLL_MS)
        }
        assertTrue(
            "App neither reached the dashboard (reused session) nor showed a login form to sign in.",
            sawLoginForm,
        )

        var lastPath = ""
        repeat(LOGIN_ATTEMPTS) { attempt ->
            signIn(E2EConfig.userEmail!!, E2EConfig.password!!)
            if (waitForAuthenticatedDashboard(webView, swipeRefresh)) return webView to swipeRefresh
            lastPath = currentPath(webView)
            if (attempt < LOGIN_ATTEMPTS - 1) {
                Thread.sleep(LOGIN_RETRY_BACKOFF_MS) // let a transient throttle window pass
                waitForLoginForm(webView) // ensure the form is back before re-submitting
            }
        }
        throw AssertionError(
            "Login did not reach the authenticated dashboard after $LOGIN_ATTEMPTS attempts " +
                "(last path=$lastPath). The live test server may be throttling repeated logins.",
        )
    }

    /**
     * SwipeRefreshLayout.canChildScrollUp() on the main thread — the pull-to-refresh guard's decision:
     * false = "at top, a refresh may trigger"; true = "child can scroll up, so PTR must NOT hijack".
     */
    fun canChildScrollUp(swipeRefresh: SwipeRefreshLayout): Boolean {
        val result = arrayOf(false)
        instrumentation.runOnMainSync { result[0] = swipeRefresh.canChildScrollUp() }
        return result[0]
    }

    /**
     * Invokes the app's pull-to-refresh handler — the wired SwipeRefreshLayout.OnRefreshListener — on
     * the main thread, exactly what a real pull triggers (MainActivity reloads the WebView). Reached by
     * reflection because there is no public API to fire onRefresh() programmatically; fails loudly if no
     * listener is wired.
     */
    fun triggerRefresh(swipeRefresh: SwipeRefreshLayout) {
        instrumentation.runOnMainSync {
            val field = SwipeRefreshLayout::class.java.getDeclaredField("mListener").apply {
                isAccessible = true
            }
            val listener = field.get(swipeRefresh) as? SwipeRefreshLayout.OnRefreshListener
                ?: throw AssertionError("No OnRefreshListener is wired on the SwipeRefreshLayout.")
            listener.onRefresh()
        }
    }
}
