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
import io.soult.embara.e2e.support.TrekE2E
import org.junit.Assert.assertEquals
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
        // Generous: the dashboard's scrollable content may still be rendering when we arrive (more so
        // now that session-reuse skips the login round-trip), so poll for a scrollable container to
        // appear before concluding there's nothing to scroll.
        const val GUARD_POLL_TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 250L
        const val RELOAD_TIMEOUT_MS = 20_000L
    }

    @Before
    fun setUp() {
        ServerHealthCheck.assumeReachable()
        assumeTrue("E2E dashboard-PTR skipped: no test credentials injected.", E2EConfig.hasCredentials)
        // Intentionally do NOT clear cookies: these tests reuse the authenticated session across the
        // class (loginAndReachDashboard reuses it), so the suite logs in once instead of per test —
        // keeping the live test server from rate-limiting repeated logins.
        EmbaraPrefs.setServerUrl(context, E2EConfig.serverUrl!!)
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
            var maxScrolled = 0
            var nudge = 0
            val deadline = System.currentTimeMillis() + GUARD_POLL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val top = trek.evalJs(webView, scrollJs(SCROLL_TARGET - (nudge and 1) * 20)).trim('"')
                maxScrolled = maxOf(maxScrolled, top.toIntOrNull() ?: 0)
                nudge++
                if (trek.canChildScrollUp(swipeRefresh)) {
                    suppressed = true
                    break
                }
                Thread.sleep(POLL_MS)
            }

            // If nothing was scrollable (seeded dashboard short / non-scrollable on this device), there
            // is no suppression to exercise — SKIP rather than fail. The suppression LOGIC is covered
            // deterministically by the hermetic unit test SwipeRefreshGuardTest; this E2E is the
            // best-effort real-dashboard confirmation and must not depend on the account's data volume.
            assumeTrue(
                "Dashboard had no scrollable content to exercise pull-to-refresh suppression " +
                    "(max scrollTop=$maxScrolled) — skipping the real-dashboard check.",
                suppressed || maxScrolled > 0,
            )
            assertTrue(
                "The dashboard content scrolled (max scrollTop=$maxScrolled) but pull-to-refresh was " +
                    "NOT suppressed (canChildScrollUp stayed false) — the inner scroll would be hijacked.",
                suppressed,
            )
        }
    }

    /**
     * D13 — a pull-to-refresh actually RELOADS the dashboard: the app's OnRefreshListener reloads the
     * WebView, producing a fresh document (a JS marker set beforehand is gone), and the session persists
     * so it lands back on the authenticated dashboard rather than the login page.
     */
    @Test
    fun pullToRefresh_reloadsDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val (webView, swipeRefresh) = trek.loginAndReachDashboard(scenario)

            // Tag the current document; a reload creates a new document and clears window globals.
            trek.evalJs(webView, "window.__e2eReloadMarker = '1'; ''")
            assertEquals(
                "Test scaffolding: the reload marker wasn't set on the current document.",
                "1",
                trek.evalJs(webView, "String(window.__e2eReloadMarker || '')").trim('"'),
            )

            trek.triggerRefresh(swipeRefresh)

            assertTrue(
                "Pull-to-refresh did not reload the dashboard within ${RELOAD_TIMEOUT_MS}ms — the JS " +
                    "marker survived, so no fresh document was loaded.",
                pollMarkerGone(webView),
            )
            assertFalse(
                "After a refresh the login form should not reappear — the session must persist.",
                trek.loginFormPresent(webView),
            )
        }
    }

    /** Polls until the reload marker is gone from the document (i.e. a reload produced a fresh page). */
    private fun pollMarkerGone(webView: WebView): Boolean {
        val deadline = System.currentTimeMillis() + RELOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val marker = trek.evalJs(webView, "String(window.__e2eReloadMarker || 'GONE')").trim('"')
            if (marker == "GONE") return true
            Thread.sleep(POLL_MS)
        }
        return false
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
