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
 * T3 E2E — dashboard SPA navigation (plan group C) against the real TREK server. Clicking TREK's nav
 * links must client-side navigate the SPA — which is exactly how embara's doUpdateVisitedHistory detects
 * a route change (the mechanism the pull-to-refresh gating and scroll-reset depend on). Uses Espresso-Web
 * LINK_TEXT selectors on TREK's real `<a>` nav (from the DOM audit); reuses the session; skips without
 * credentials.
 */
@RunWith(AndroidJUnit4::class)
class EmbaraNavigationE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    private companion object {
        const val NAV_TIMEOUT_MS = 10_000L
        const val POLL_MS = 250L
        val SECTIONS = listOf("Vacay", "Atlas")
    }

    @Before
    fun setUp() {
        ServerHealthCheck.assumeReachable()
        assumeTrue("E2E nav skipped: no test credentials injected.", E2EConfig.hasCredentials)
        // Reuse the authenticated session across the suite (see TrekE2E.loginAndReachDashboard).
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    /**
     * C — clicking TREK's nav links client-side navigates the SPA: the route changes on each click,
     * proving embara's WebView drives real SPA navigation (and thus doUpdateVisitedHistory fires).
     */
    @Test
    fun navLinks_changeTheSpaRoute() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val (webView, _) = trek.loginAndReachDashboard(scenario)

            var path = trek.currentPath(webView)
            for (section in SECTIONS) {
                TrekDashboardPage.navigateTo(section)
                val newPath = waitForPathChange(webView, path)
                assertNotEquals(
                    "Clicking the '$section' nav link did not change the SPA route within " +
                        "${NAV_TIMEOUT_MS}ms (still '$path'). Either the link text changed or SPA " +
                        "navigation didn't fire in the WebView.",
                    path,
                    newPath,
                )
                path = newPath
            }
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
