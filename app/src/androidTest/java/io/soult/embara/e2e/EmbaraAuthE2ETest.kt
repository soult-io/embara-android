package io.soult.embara.e2e

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.soult.embara.EmbaraPrefs
import io.soult.embara.MainActivity
import io.soult.embara.e2e.support.E2EConfig
import io.soult.embara.e2e.support.ServerHealthCheck
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T3 E2E — authentication journeys (plan group B) against the real test TREK server.
 *
 * These need the seeded test-account credentials, injected via instrumentation args (E2EConfig); they
 * skip when the server or credentials aren't present, so they never fail a fork/dev run.
 *
 * SUCCESS SIGNAL — deliberately app-behavioral, not TREK-DOM: MainActivity enables pull-to-refresh
 * ONLY on dashboard routes (updateSwipeRefreshForUrl), so SwipeRefreshLayout.isEnabled flipping true
 * after login IS "reached the authenticated dashboard". That regression-locks embara's own routing
 * and avoids brittle assumptions about TREK's markup.
 *
 * SECRET HYGIENE: the password is written into the login form via a JS value-setter and is NEVER put
 * into a log line, assertion message, screenshot, or read back from the DOM. Only non-secret signals
 * (the fill outcome token, location.pathname, isEnabled) are ever surfaced.
 */
@RunWith(AndroidJUnit4::class)
class EmbaraAuthE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private companion object {
        const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        const val LOGIN_TIMEOUT_MS = 40_000L
        // Shorter budget for the negative test: long enough for a rejected login to round-trip, short
        // enough not to bloat the suite. If not authenticated within this window, it won't be.
        const val NEGATIVE_TIMEOUT_MS = 15_000L
        const val JS_RESULT_SECONDS = 10L
        const val POLL_MS = 300L
        // A deliberately wrong password — a fixed literal, NOT derived from the real one, so nothing
        // sensitive is constructed for the negative test.
        const val WRONG_PASSWORD = "e2e-intentionally-wrong-password"
    }

    @Before
    fun setUp() {
        // Auth journeys require credentials; skip cleanly otherwise (fork/dev/PR runs).
        ServerHealthCheck.assumeReachable()
        assumeTrue(
            "E2E auth skipped: no test credentials injected.",
            E2EConfig.hasCredentials,
        )
        // Start from a clean session so the login form is actually shown — a stale trek_session cookie
        // would auto-authenticate and defeat the point of the login test.
        clearCookies()
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    @After
    fun tearDown() {
        try {
            clearCookies()
            EmbaraPrefs.clearServerUrl(context)
        } catch (_: Exception) {
            // Best-effort — never fail the suite on cleanup.
        }
    }

    private fun clearCookies() {
        val cm = android.webkit.CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    /**
     * B5 — logging in through the TREK web UI reaches the dashboard (pull-to-refresh becomes enabled).
     */
    @Test
    fun login_reachesDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)

            assertTrue("Login page never finished loading.", waitForLoginForm(webView))

            val outcome = submitLogin(webView, E2EConfig.userEmail!!, E2EConfig.password!!)
            assertTrue(
                "Could not drive the TREK login form (outcome=$outcome). The form's email/password " +
                    "inputs weren't found by the heuristic selectors.",
                outcome == "SUBMITTED" || outcome == "FORM_SUBMIT",
            )

            // Authenticated iff we LEFT the login page (its password field is gone — a failed login
            // stays put) AND embara enabled pull-to-refresh (its dashboard-route gating). Requiring
            // both makes this fail if authentication didn't actually happen — it is not satisfied by
            // merely sitting on a root-path login page.
            assertTrue(
                "Login did not reach the authenticated dashboard within ${LOGIN_TIMEOUT_MS}ms " +
                    "(login form still present or pull-to-refresh never enabled; last path=" +
                    "${currentPath(webView)}). Check the seeded credentials / TREK login flow.",
                waitForAuthenticatedDashboard(webView, swipeRefresh),
            )
        }
    }

    /**
     * B (negative) — WRONG credentials must NOT reach the dashboard. This proves login_reachesDashboard
     * isn't a tautology: the identical success signal, fed a bad password, must stay on the login page.
     */
    @Test
    fun wrongCredentials_doNotReachDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)

            assertTrue("Login page never finished loading.", waitForLoginForm(webView))

            val outcome = submitLogin(webView, E2EConfig.userEmail!!, WRONG_PASSWORD)
            assertTrue(
                "Could not drive the login form (outcome=$outcome).",
                outcome == "SUBMITTED" || outcome == "FORM_SUBMIT",
            )

            assertFalse(
                "Wrong credentials reached the authenticated dashboard — authentication isn't being " +
                    "enforced (or the success signal is a tautology).",
                waitForAuthenticatedDashboard(webView, swipeRefresh, NEGATIVE_TIMEOUT_MS),
            )
            assertTrue(
                "After a rejected login the app should remain on the login form (last path=" +
                    "${currentPath(webView)}).",
                loginFormPresent(webView),
            )
        }
    }

    /**
     * B6 — the session survives an app relaunch: after login, a fresh MainActivity (new WebView) lands
     * on the dashboard directly, no re-login, from the persisted trek_session cookie (regression lock
     * for the cookie-flush-to-disk fix). NOTE: exercises an ACTIVITY relaunch within the same process —
     * a full process kill isn't simulable from an in-process instrumented test.
     */
    @Test
    fun session_survivesRelaunch() {
        // 1. Log in.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)
            assertTrue("Login page never finished loading.", waitForLoginForm(webView))
            val outcome = submitLogin(webView, E2EConfig.userEmail!!, E2EConfig.password!!)
            assertTrue(
                "Could not drive the login form (outcome=$outcome).",
                outcome == "SUBMITTED" || outcome == "FORM_SUBMIT",
            )
            assertTrue(
                "Precondition failed: the initial login didn't reach the dashboard.",
                waitForAuthenticatedDashboard(webView, swipeRefresh),
            )
        }

        // 2. Relaunch — a NEW activity/WebView must reach the dashboard with NO login form.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)
            assertTrue(
                "Session did not survive relaunch — the login form reappeared / dashboard PTR never " +
                    "enabled. The trek_session cookie wasn't persisted (cookie-flush regression).",
                waitForAuthenticatedDashboard(webView, swipeRefresh),
            )
        }
    }

    // --- login form driving (password never logged) ---

    /** Whether the login form (a password field) is currently in the DOM. */
    private fun loginFormPresent(webView: WebView): Boolean =
        evalJs(webView, "String(!!document.querySelector('input[type=password]'))").trim('"') == "true"

    /** Waits until the login form has rendered. */
    private fun waitForLoginForm(webView: WebView): Boolean {
        val deadline = System.currentTimeMillis() + PAGE_LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (loginFormPresent(webView)) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /**
     * Fills the seeded credentials into the login form and submits. Uses the native value setter +
     * input/change events so SPA frameworks register the change. Returns a NON-secret outcome token
     * ("SUBMITTED" / "FORM_SUBMIT" / "NO_FORM" / "NO_SUBMIT"); the credential values never appear in
     * the return value or any log.
     */
    private fun submitLogin(webView: WebView, userEmail: String, password: String): String {
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

    /**
     * Authenticated when BOTH hold: the login form is gone (its password field is no longer in the DOM
     * — a failed login stays on the form) AND MainActivity has enabled pull-to-refresh (its
     * dashboard-route gating). Requiring the login form to disappear is what makes this prove real
     * authentication rather than merely observing PTR on a root-path login page.
     */
    private fun waitForAuthenticatedDashboard(
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

    /** Non-secret: the current route path only (never the full URL / query / token). */
    private fun currentPath(webView: WebView): String = evalJs(webView, "String(location.pathname)").trim('"')

    // --- reflection + JS helpers ---

    private fun webViewOf(scenario: ActivityScenario<MainActivity>): WebView {
        val holder = arrayOfNulls<WebView>(1)
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
            holder[0] = field.get(activity) as WebView
        }
        assertNotNull("Could not reflectively obtain MainActivity.webView", holder[0])
        return holder[0]!!
    }

    private fun swipeRefreshOf(scenario: ActivityScenario<MainActivity>): SwipeRefreshLayout {
        val holder = arrayOfNulls<SwipeRefreshLayout>(1)
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("swipeRefresh").apply { isAccessible = true }
            holder[0] = field.get(activity) as SwipeRefreshLayout
        }
        assertNotNull("Could not reflectively obtain MainActivity.swipeRefresh", holder[0])
        return holder[0]!!
    }

    private fun evalJs(webView: WebView, script: String): String {
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
}
