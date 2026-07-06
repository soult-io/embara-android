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
 * T3 E2E — lifecycle journeys (plan group E) against the real test TREK server. Verifies the app
 * survives Android lifecycle events without losing the authenticated session. Skips without credentials.
 */
@RunWith(AndroidJUnit4::class)
class EmbaraLifecycleE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        ServerHealthCheck.assumeReachable()
        assumeTrue("E2E lifecycle skipped: no test credentials injected.", E2EConfig.hasCredentials)
        // Reuse the authenticated session (do NOT clear cookies) so the suite doesn't re-login here —
        // avoids hammering the shared live test server's login endpoint. See TrekE2E.loginAndReachDashboard.
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    /**
     * E14 — a configuration change (rotation) preserves the authenticated dashboard: MainActivity saves
     * + restores WebView state (onSaveInstanceState / restoreState), so after the activity is recreated
     * the app is still on the dashboard, not bounced to a reload or the login page.
     */
    @Test
    fun rotation_preservesAuthenticatedDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            trek.loginAndReachDashboard(scenario)

            // Recreate the activity — the standard stand-in for a rotation / configuration change.
            scenario.recreate()

            val webView = trek.webViewOf(scenario)
            val swipeRefresh = trek.swipeRefreshOf(scenario)
            assertTrue(
                "A configuration change (rotation) lost the authenticated dashboard — the login form " +
                    "returned or pull-to-refresh was not re-enabled after the activity was recreated.",
                trek.waitForAuthenticatedDashboard(webView, swipeRefresh),
            )
        }
    }
}
