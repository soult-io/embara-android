package io.soult.embara.e2e.support

import android.app.Instrumentation
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import io.soult.embara.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.AssumptionViolatedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared driver for TREK E2E journeys, so the login flow + WebView plumbing live in one place.
 *
 * Provides: reflection into MainActivity's private WebView / SwipeRefreshLayout; a main-thread
 * evaluateJavascript bridge for state polling; TREK login delegated to [TrekLoginPage] (Espresso-Web /
 * WebDriver atoms — the password is never logged, returned, or read back); and the app-behavioral
 * "reached the authenticated dashboard" signal (MainActivity enables pull-to-refresh only on dashboard
 * routes, and the login form disappears on a real login). WebView / SwipeRefreshLayout access is
 * marshalled to the main thread.
 */
class TrekE2E(private val instrumentation: Instrumentation) {

    companion object {
        const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        const val LOGIN_TIMEOUT_MS = 40_000L
        const val JS_RESULT_SECONDS = 10L
        const val POLL_MS = 300L
        // Login attempts before giving up, with a backoff between them — rides out a transient slow or
        // throttled login round-trip against the shared live test server.
        const val LOGIN_ATTEMPTS = 3
        const val LOGIN_RETRY_BACKOFF_MS = 8_000L
        // The TREK login route. "Authenticated" REQUIRES having left it: during a login submit TREK
        // briefly unmounts the password field while still on /login, which would otherwise read as a
        // false "dashboard reached". Being off /login closes that hole.
        const val LOGIN_PATH = "/login"
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

    /** Whether the WebView is currently on the TREK login route. */
    private fun onLoginRoute(webView: WebView): Boolean = currentPath(webView).startsWith(LOGIN_PATH)

    /**
     * Authenticated when ALL hold: the WebView is OFF the login route AND the login form is gone AND
     * MainActivity has enabled pull-to-refresh (its dashboard-route gating). The off-/login clause is
     * load-bearing: during a login submit the password field briefly unmounts while still on /login, so
     * form-gone + PTR alone would false-positive; requiring a real (non-login) route rules that out.
     */
    fun waitForAuthenticatedDashboard(
        webView: WebView,
        swipeRefresh: SwipeRefreshLayout,
        timeoutMs: Long = LOGIN_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val offLogin = !onLoginRoute(webView)
            val loginGone = !loginFormPresent(webView)
            val enabled = arrayOf(false)
            instrumentation.runOnMainSync { enabled[0] = swipeRefresh.isEnabled }
            if (offLogin && loginGone && enabled[0]) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Single-shot check: authenticated dashboard == off /login AND login form gone AND PTR enabled. */
    private fun isAuthenticatedDashboard(webView: WebView, swipeRefresh: SwipeRefreshLayout): Boolean {
        if (onLoginRoute(webView) || loginFormPresent(webView)) return false
        val enabled = arrayOf(false)
        instrumentation.runOnMainSync { enabled[0] = swipeRefresh.isEnabled }
        return enabled[0]
    }

    /**
     * Reaches the authenticated dashboard and returns the (WebView, SwipeRefreshLayout) pair, or `null`
     * if it couldn't within the attempts (no login form ever appeared, or login never reached an
     * off-/login dashboard). Callers pick the failure policy: [loginAndReachDashboard] asserts,
     * [loginAndReachDashboardOrSkip] skips.
     *
     * SESSION REUSE: if a prior test in the process already authenticated (its cookies weren't cleared),
     * the app auto-lands on the dashboard and this returns WITHOUT logging in again — keeping the
     * login-per-test count low so the shared live TREK test server doesn't rate-limit the suite. Only
     * when the login form is actually shown does it log in, with a bounded retry to ride out a transient
     * throttled/slow round-trip. Requires [E2EConfig.hasCredentials].
     */
    private fun tryReachDashboard(
        scenario: ActivityScenario<MainActivity>,
    ): Pair<WebView, SwipeRefreshLayout>? {
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
        // Neither an authenticated dashboard nor a login form to act on — can't establish a session.
        if (!sawLoginForm) return null

        repeat(LOGIN_ATTEMPTS) { attempt ->
            val lastAttempt = attempt == LOGIN_ATTEMPTS - 1
            try {
                signIn(E2EConfig.userEmail!!, E2EConfig.password!!)
            } catch (e: Throwable) {
                // A transient Espresso element-resolution hiccup shouldn't abort the flow; retry unless
                // this was the last attempt, in which case surface the real failure.
                if (lastAttempt) throw e
                Thread.sleep(LOGIN_RETRY_BACKOFF_MS)
                waitForLoginForm(webView)
                return@repeat
            }
            if (waitForAuthenticatedDashboard(webView, swipeRefresh)) return webView to swipeRefresh
            if (!lastAttempt) {
                Thread.sleep(LOGIN_RETRY_BACKOFF_MS) // let a transient throttle window pass
                waitForLoginForm(webView) // ensure the form is back before re-submitting
            }
        }
        // Submitted the credentials but never reached an off-/login dashboard within the attempts.
        return null
    }

    /**
     * Reaches the authenticated dashboard or FAILS the test. Use when authentication is the subject
     * under test (see [tryReachDashboard] for the reuse/retry behavior).
     */
    fun loginAndReachDashboard(
        scenario: ActivityScenario<MainActivity>,
    ): Pair<WebView, SwipeRefreshLayout> =
        tryReachDashboard(scenario) ?: throw AssertionError(
            "Login did not reach the authenticated dashboard after $LOGIN_ATTEMPTS attempts. The live " +
                "TREK test server may be throttling repeated logins, or no login form appeared.",
        )

    /**
     * Reaches the authenticated dashboard or SKIPS the test (JUnit assumption) — for tests whose subject
     * is NOT authentication (e.g. navigation). Login throttling on the shared live TREK server then greys
     * the test out instead of redding the build; the auth tests ([EmbaraAuthE2ETest]) stay the hard
     * guardians of login itself, so a genuine login regression is still caught there.
     */
    fun loginAndReachDashboardOrSkip(
        scenario: ActivityScenario<MainActivity>,
    ): Pair<WebView, SwipeRefreshLayout> =
        tryReachDashboard(scenario) ?: throw AssumptionViolatedException(
            "Skipped: could not establish an authenticated TREK session (likely login throttling on the " +
                "shared test server).",
        )

    /**
     * The route (pathname[+hash]) of the first visible, same-origin in-app `<a>` nav link whose target
     * differs from the current route — discovered from the live DOM so no nav label is hard-coded — or
     * "" when the dashboard exposes no such anchor (e.g. button-driven nav). Structure only: a route
     * path, never a full URL / query / token.
     */
    fun firstInAppNavTarget(webView: WebView): String {
        val js = """
            (function(){
              try {
                var here = location.pathname + location.hash;
                var as = document.querySelectorAll('a[href]');
                for (var i=0;i<as.length;i++){
                  var a=as[i];
                  if (a.offsetParent===null) continue;
                  var u=new URL(a.href, location.origin);
                  if (u.origin!==location.origin) continue;
                  var t=u.pathname+u.hash;
                  if (t===here) continue;
                  if (t.indexOf('$LOGIN_PATH')===0) continue;
                  return t;
                }
                return '';
              } catch(e){ return ''; }
            })()
        """.trimIndent()
        return evalJs(webView, js).trim('"')
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
