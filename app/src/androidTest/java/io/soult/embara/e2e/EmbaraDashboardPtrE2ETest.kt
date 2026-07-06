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
 * T3 E2E — pull-to-refresh behavior on the REAL TREK dashboard (plan group D). The on-device,
 * real-server counterpart to the hermetic unit test SwipeRefreshGuardTest: at the true top of the
 * dashboard a refresh is allowed; once the user scrolls into dashboard content, pull-to-refresh is
 * suppressed so it doesn't hijack the inner scroll — the TREK v3.1.0 bug the scroll-bridge fix closed.
 *
 * The guard under test is MainActivity's setOnChildScrollUpCallback
 * (ptrBridge.activeScrollTop > 0 || webView.canScrollVertically(-1)); we read its decision via
 * SwipeRefreshLayout.canChildScrollUp() — false = "at top, a refresh may fire", true = "child can
 * scroll up, so pull-to-refresh must not hijack".
 */
@RunWith(AndroidJUnit4::class)
class EmbaraDashboardPtrE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    private companion object {
        const val SCROLL_TARGET = 600
        const val GUARD_POLL_TIMEOUT_MS = 6_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 250L
    }

    @Before
    fun setUp() {
        ServerHealthCheck.assumeReachable()
        assumeTrue("E2E dashboard-PTR skipped: no test credentials injected.", E2EConfig.hasCredentials)
        trek.clearCookies()
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
    }

    @After
    fun tearDown() {
        try {
            trek.clearCookies()
            EmbaraPrefs.clearServerUrl(context)
        } catch (_: Exception) {
            // Best-effort cleanup.
        }
    }

    /**
     * D11 — at the true top of the dashboard, pull-to-refresh is ALLOWED: the guard reports the child
     * cannot scroll up, so SwipeRefreshLayout may intercept a downward drag and trigger a refresh.
     */
    @Test
    fun dashboardAtTop_allowsPullToRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val (webView, swipeRefresh) = trek.loginAndReachDashboard(scenario)

            // Make sure we're at the top (a scroll to 0 fires an event that resets the bridge to 0).
            trek.evalJs(webView, scrollJs(0))
            Thread.sleep(SETTLE_MS)

            assertFalse(
                "At the top of the dashboard the pull-to-refresh guard must allow a refresh " +
                    "(canChildScrollUp==false), but it reported the child can scroll up.",
                trek.canChildScrollUp(swipeRefresh),
            )
        }
    }

    /**
     * D12 — scrolled into dashboard content, pull-to-refresh is SUPPRESSED: the guard reports the child
     * can scroll up, so SwipeRefreshLayout does NOT intercept and the inner scroll isn't hijacked. This
     * is the exact regression the scroll-bridge fix prevents, verified against the real dashboard.
     */
    @Test
    fun dashboardScrolled_suppressesPullToRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val (webView, swipeRefresh) = trek.loginAndReachDashboard(scenario)

            // Scroll the dashboard's inner scroll container and wait until the guard OBSERVES it — the
            // scroll bridge is fed asynchronously (scroll event -> capture-phase hook -> bridge). Nudge
            // each iteration so a fresh scroll event is guaranteed to fire.
            var suppressed = false
            var lastTop = "0"
            var nudge = 0
            val deadline = System.currentTimeMillis() + GUARD_POLL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                lastTop = trek.evalJs(webView, scrollJs(SCROLL_TARGET - (nudge and 1) * 20)).trim('"')
                nudge++
                if (trek.canChildScrollUp(swipeRefresh)) {
                    suppressed = true
                    break
                }
                Thread.sleep(POLL_MS)
            }

            assertTrue(
                "After scrolling the dashboard content down (scroller top=$lastTop) pull-to-refresh " +
                    "must be suppressed (canChildScrollUp==true) so the inner scroll isn't hijacked, but " +
                    "the guard still reports at-top. (If top stayed 0/NO_SCROLLER the seeded dashboard " +
                    "had no scrollable content to exercise the guard.)",
                suppressed,
            )
        }
    }

    /**
     * JS: scroll the dashboard's most-scrollable inner overflow container (TREK pins the document and
     * scrolls an inner overflow:auto element) to [target]px, returning the resulting scrollTop (or
     * NO_SCROLLER). Setting scrollTop fires a scroll event that the injected capture-phase hook reports.
     */
    private fun scrollJs(target: Int): String = """
        (function(){
          var els = [].slice.call(document.querySelectorAll('*'));
          var c = els.filter(function(e){
            var oy = getComputedStyle(e).overflowY;
            return (oy === 'auto' || oy === 'scroll') && (e.scrollHeight - e.clientHeight) > 40;
          });
          c.sort(function(a,b){ return (b.scrollHeight - b.clientHeight) - (a.scrollHeight - a.clientHeight); });
          var s = c[0] || document.scrollingElement;
          if (!s) return 'NO_SCROLLER';
          s.scrollTop = $target;
          return String(Math.round(s.scrollTop));
        })()
    """.trimIndent()
}
