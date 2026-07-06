package io.soult.embara.e2e.support

import android.app.Instrumentation
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import io.soult.embara.MainActivity
import org.json.JSONObject
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
     * Fills the login form and submits, using the native value setter + input/change events so SPA
     * frameworks register the change (heuristic input selectors). Returns a NON-secret outcome token
     * ("SUBMITTED"/"FORM_SUBMIT"/"NO_FORM"/"NO_SUBMIT"); credential values never appear in the return
     * value or any log.
     */
    fun submitLogin(webView: WebView, userEmail: String, password: String): String {
        val user = JSONObject.quote(userEmail)
        val pass = JSONObject.quote(password)
        val script = """
            (function(){
              var pw = document.querySelector('input[type=password]');
              var user = document.querySelector('input[type=email]')
                       || document.querySelector('input[type=text]')
                       || document.querySelector('input:not([type=password]):not([type=hidden])');
              if (!pw || !user) return 'NO_FORM';
              function setVal(el, v){
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                setter.call(el, v);
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              }
              setVal(user, $user);
              setVal(pw, $pass);
              var btn = document.querySelector('button[type=submit]')
                      || document.querySelector('form button')
                      || document.querySelector('button');
              if (btn) { btn.click(); return 'SUBMITTED'; }
              var form = pw.closest('form');
              if (form) { form.submit(); return 'FORM_SUBMIT'; }
              return 'NO_SUBMIT';
            })()
        """.trimIndent()
        return evalJs(webView, script).trim('"')
    }

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

    /**
     * Logs in with the injected credentials and asserts the authenticated dashboard is reached; returns
     * the (WebView, SwipeRefreshLayout) pair for further assertions. Requires [E2EConfig.hasCredentials].
     */
    fun loginAndReachDashboard(
        scenario: ActivityScenario<MainActivity>,
    ): Pair<WebView, SwipeRefreshLayout> {
        val webView = webViewOf(scenario)
        val swipeRefresh = swipeRefreshOf(scenario)
        assertTrue("Login page never finished loading.", waitForLoginForm(webView))
        val outcome = submitLogin(webView, E2EConfig.userEmail!!, E2EConfig.password!!)
        assertTrue(
            "Could not drive the TREK login form (outcome=$outcome).",
            outcome == "SUBMITTED" || outcome == "FORM_SUBMIT",
        )
        assertTrue(
            "Login did not reach the authenticated dashboard (last path=${currentPath(webView)}).",
            waitForAuthenticatedDashboard(webView, swipeRefresh),
        )
        return webView to swipeRefresh
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
}
