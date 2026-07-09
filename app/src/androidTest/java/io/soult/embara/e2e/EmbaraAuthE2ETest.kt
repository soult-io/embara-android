package io.soult.embara.e2e

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.soult.embara.EmbaraPrefs
import io.soult.embara.MainActivity
import io.soult.embara.e2e.support.E2EConfig
import io.soult.embara.e2e.support.ServerHealthCheck
import io.soult.embara.e2e.support.TrekE2E
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T3 E2E — authentication journeys (plan group B) against the real test TREK server. The shared login /
 * WebView plumbing lives in [TrekE2E]; this class holds only the journeys. Skips without credentials.
 *
 * SUCCESS SIGNAL — app-behavioral, not TREK-DOM: authenticated == off /login AND the login form is gone
 * AND real content rendered AND MainActivity's dashboard pull-to-refresh is enabled.
 *
 * LOGIN BUDGET: the whole E2E suite shares ONE authenticated session (the live CookieManager cookie), so
 * this class does NOT clear cookies — the first authenticated test logs in and every other test reuses
 * it, keeping login-endpoint hits far under the shared server's rate limit. The non-tautology proof (the
 * success signal reads false while the login form shows) is folded into [TrekE2E.loginAndReachDashboard];
 * a wrong-PASSWORD test is intentionally omitted (that's TREK's server behavior, not embara's, and it
 * would cost an extra login round-trip). SECRET HYGIENE: the password is only ever typed via Espresso-Web
 * webKeys ([TrekLoginPage]) — never logged, returned, or read back.
 */
@RunWith(AndroidJUnit4::class)
class EmbaraAuthE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        // Auth journeys require credentials; skip cleanly otherwise (fork/dev/PR runs).
        ServerHealthCheck.assumeReachable()
        assumeTrue("E2E auth skipped: no test credentials injected.", E2EConfig.hasCredentials)
        // Do NOT clear cookies: the suite shares one login (see class doc). setServerUrl is idempotent.
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    /**
     * B5 — logging in through the TREK web UI reaches the authenticated dashboard. On the first
     * authenticated test this performs the one real login (asserting the success signal is false on the
     * login form first — the non-tautology proof); later it reuses the shared session.
     */
    @Test
    fun login_reachesDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            // loginAndReachDashboard asserts the whole flow: form loads -> signal-false -> submit ->
            // dashboard reached (or reuses an already-live session).
            trek.loginAndReachDashboard(scenario)
        }
    }

    /**
     * B6 — the session survives an app relaunch: after login, a fresh MainActivity (new WebView) lands
     * on the dashboard directly, no re-login, from the retained trek_session cookie. NOTE: this is an
     * ACTIVITY relaunch within the SAME process (a new WebView reading the shared CookieManager). A full
     * process kill — which is what the cookie-flush-TO-DISK fix actually guards — isn't simulable from an
     * in-process instrumented test, so this locks the activity-relaunch case only, not the disk-flush.
     */
    @Test
    fun session_survivesRelaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            trek.loginAndReachDashboard(scenario)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val webView = trek.webViewOf(scenario)
            val swipeRefresh = trek.swipeRefreshOf(scenario)
            assertTrue(
                "The session did not survive the activity relaunch — the login form reappeared / " +
                    "dashboard pull-to-refresh never enabled after re-launching MainActivity.",
                trek.waitForAuthenticatedDashboard(webView, swipeRefresh),
            )
        }
    }
}
