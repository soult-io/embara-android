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
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T3 E2E — authentication journeys (plan group B) against the real test TREK server. The shared login /
 * WebView plumbing lives in [TrekE2E]; this class holds only the journeys. Skips without credentials.
 *
 * SUCCESS SIGNAL — app-behavioral, not TREK-DOM: authenticated == the login form is gone AND
 * MainActivity's dashboard-route pull-to-refresh is enabled. SECRET HYGIENE: the password is only ever
 * typed into the form via Espresso-Web webKeys ([TrekLoginPage]) — never logged, returned, or read back.
 */
@RunWith(AndroidJUnit4::class)
class EmbaraAuthE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    private companion object {
        // Shorter budget for the negative test: long enough for a rejected login to round-trip, short
        // enough not to bloat the suite. If not authenticated within this window, it won't be.
        const val NEGATIVE_TIMEOUT_MS = 15_000L
        // A deliberately wrong password — a fixed literal, NOT derived from the real one, so nothing
        // sensitive is constructed for the negative test.
        const val WRONG_PASSWORD = "e2e-intentionally-wrong-password"
    }

    @Before
    fun setUp() {
        // Auth journeys require credentials; skip cleanly otherwise (fork/dev/PR runs).
        ServerHealthCheck.assumeReachable()
        assumeTrue("E2E auth skipped: no test credentials injected.", E2EConfig.hasCredentials)
        // Start from a clean session so the login form is actually shown — a stale trek_session cookie
        // would auto-authenticate and defeat the point of the login test.
        trek.clearCookies()
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    @After
    fun tearDown() {
        try {
            trek.clearCookies()
            EmbaraPrefs.clearServerUrl(context)
        } catch (_: Exception) {
            // Best-effort — never fail the suite on cleanup.
        }
    }

    /** B5 — logging in through the TREK web UI reaches the authenticated dashboard. */
    @Test
    fun login_reachesDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            // loginAndReachDashboard asserts the whole flow: form loads -> submit -> dashboard reached.
            trek.loginAndReachDashboard(scenario)
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
            val webView = trek.webViewOf(scenario)
            val swipeRefresh = trek.swipeRefreshOf(scenario)

            assertTrue("Login page never finished loading.", trek.waitForLoginForm(webView))
            trek.signIn(E2EConfig.userEmail!!, WRONG_PASSWORD)
            assertFalse(
                "Wrong credentials reached the authenticated dashboard — authentication isn't being " +
                    "enforced (or the success signal is a tautology).",
                trek.waitForAuthenticatedDashboard(webView, swipeRefresh, NEGATIVE_TIMEOUT_MS),
            )
            assertTrue(
                "After a rejected login the app should remain on the login form (last path=" +
                    "${trek.currentPath(webView)}).",
                trek.loginFormPresent(webView),
            )
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
