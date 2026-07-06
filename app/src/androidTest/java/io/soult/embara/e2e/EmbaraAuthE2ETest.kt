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
        const val JS_RESULT_SECONDS = 10L
        const val POLL_MS = 300L
    }

    @Before
    fun setUp() {
        // Auth journeys require credentials; skip cleanly otherwise (fork/dev/PR runs).
        ServerHealthCheck.assumeReachable()
        assumeTrue(
            "E2E auth skipped: no test credentials injected.",
            E2EConfig.hasCredentials,
        )
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    @After
    fun tearDown() {
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            EmbaraPrefs.clearServerUrl(context)
        } catch (_: Exception) {
            // Best-effort — never fail the suite on cleanup.
        }
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

            val outcome = submitLogin(webView)
            assertTrue(
                "Could not drive the TREK login form (outcome=$outcome). The form's email/password " +
                    "inputs weren't found by the heuristic selectors.",
                outcome == "SUBMITTED" || outcome == "FORM_SUBMIT",
            )

            assertTrue(
                "Login did not reach the dashboard within ${LOGIN_TIMEOUT_MS}ms " +
                    "(pull-to-refresh never enabled; last path=${currentPath(webView)}).",
                waitForDashboard(swipeRefresh),
            )
        }
    }

    // --- login form driving (password never logged) ---

    /** Waits until a password field is present in the DOM (the login form has rendered). */
    private fun waitForLoginForm(webView: WebView): Boolean {
        val deadline = System.currentTimeMillis() + PAGE_LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val ready = evalJs(webView, "String(!!document.querySelector('input[type=password]'))")
            if (ready.trim('"') == "true") return true
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
    private fun submitLogin(webView: WebView): String {
        val user = JSONObject.quote(E2EConfig.userEmail!!)
        val pass = JSONObject.quote(E2EConfig.password!!)
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

    /** Dashboard reached when MainActivity enables pull-to-refresh (dashboard-only route gating). */
    private fun waitForDashboard(swipeRefresh: SwipeRefreshLayout): Boolean {
        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val enabled = arrayOf(false)
            instrumentation.runOnMainSync { enabled[0] = swipeRefresh.isEnabled }
            if (enabled[0]) return true
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
