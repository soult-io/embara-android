package io.soult.embara.e2e

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.soult.embara.EmbaraPrefs
import io.soult.embara.MainActivity
import io.soult.embara.e2e.support.E2EConfig
import io.soult.embara.e2e.support.ServerHealthCheck
import io.soult.embara.e2e.support.TrekDashboardPage
import io.soult.embara.e2e.support.TrekE2E
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T3 E2E — dashboard SPA navigation (plan group C) against the real TREK server. Clicking an in-app nav
 * link must client-side navigate the SPA — exactly how embara's doUpdateVisitedHistory detects a route
 * change (the mechanism the pull-to-refresh gating and scroll-reset depend on). The nav target is
 * DISCOVERED from the live DOM (a real same-origin `<a>` route), not a hard-coded label, and clicked via
 * Espresso-Web. Skips (not fails) when no authenticated session can be established (shared-server login
 * throttling) or when the dashboard exposes no anchor nav; skips without credentials.
 */
@RunWith(AndroidJUnit4::class)
class EmbaraNavigationE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    private companion object {
        const val NAV_TIMEOUT_MS = 10_000L
        const val POLL_MS = 250L
    }

    @Before
    fun setUp() {
        ServerHealthCheck.assumeReachable()
        assumeTrue("E2E nav skipped: no test credentials injected.", E2EConfig.hasCredentials)
        // Reuse the authenticated session across the suite (see TrekE2E.loginAndReachDashboard).
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    /**
     * C — clicking an in-app nav link client-side navigates the SPA: the route changes, proving embara's
     * WebView drives real SPA navigation (and thus doUpdateVisitedHistory fires). The link is discovered
     * from the live DOM, so the test adapts to TREK's real nav rather than assuming label text.
     */
    @Test
    fun navLink_changesTheSpaRoute() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val (webView, _) = trek.loginAndReachDashboardOrSkip(scenario)

            val before = trek.currentPath(webView)
            val target = trek.firstInAppNavTarget(webView)
            assumeTrue(
                "Nav skipped: the dashboard exposed no in-app <a> nav link to a different route " +
                    "(button-driven nav?).",
                target.isNotEmpty(),
            )

            TrekDashboardPage.navigateToRoute(target)
            val after = waitForPathChange(webView, before)
            assertNotEquals(
                "Clicking the in-app nav link to '$target' did not change the SPA route within " +
                    "${NAV_TIMEOUT_MS}ms (still '$before'). SPA navigation didn't fire in the WebView.",
                before,
                after,
            )
        }
    }

    /** Polls location.pathname until it differs from [from] (a client-side route change), or times out. */
    private fun waitForPathChange(webView: WebView, from: String): String {
        val deadline = System.currentTimeMillis() + NAV_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val now = trek.currentPath(webView)
            if (now != from) return now
            Thread.sleep(POLL_MS)
        }
        return from
    }
}
