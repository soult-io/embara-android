package io.soult.embara

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DEVICE SANITY for TREK's SPA router against the pull-to-refresh guard.
 *
 * TREK is a single-page app: after the first load, route changes happen client-side via the History
 * API (history.pushState) — WebViewClient.onPageFinished does NOT fire again, only
 * doUpdateVisitedHistory does. The pull-to-refresh fix hangs two behaviors off that callback:
 *
 *   1. ptrBridge.resetToTop() — a client-side route change lands at the top of the new view, so the
 *      scroll guard must forget the previous route's inner scrollTop and allow pull-to-refresh again.
 *   2. updateSwipeRefreshForUrl(url) — pull-to-refresh is only enabled on dashboard routes; the
 *      per-route enable/disable must track SPA navigation, not just full page loads.
 *
 * These tests drive REAL same-origin SPA navigation (loadDataWithBaseURL gives the document a real
 * https origin so history.pushState to same-origin paths is permitted and fires doUpdateVisited
 * History) and assert both behaviors on device. They are the regression guard for "the SPA router
 * still behaves correctly with the pull-to-refresh guard in place."
 *
 * All WebView / SwipeRefreshLayout work runs on the MAIN thread (WebView requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class SpaRouterGuardTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        // Origin the app is configured for; must match the base URL below so route changes are
        // treated as same-origin/internal by MainActivity's WebViewClient.
        const val TEST_ORIGIN = "https://example.com"

        const val LOAD_TIMEOUT_SECONDS = 15L
        const val JS_RESULT_SECONDS = 10L

        const val POLL_INTERVAL_MS = 100L
        // The guard reads a value fed ASYNChronously by the injected JS scroll listener via the
        // @JavascriptInterface bridge, and doUpdateVisitedHistory itself is dispatched off the JS
        // history change — so we poll for the expected state rather than reading once.
        const val GUARD_POLL_TIMEOUT_MS = 5_000L

        // Mid-range scrollTop for the small inner scroller in the reset test (well within its range so
        // it never reaches an edge). Distinct nudge values (this and this-10) guarantee a fresh event.
        const val MINI_SCROLL_TARGET = 200

        // After scrolling to feed the bridge we let the async scroll→bridge pipeline drain before the
        // route change, so the ONLY write to the bridge after the route change is resetToTop(). (A real
        // SPA route change swaps in fresh content that starts at the top, so no late scroll events fire
        // from the old position — settling reproduces that quiescence.)
        const val SETTLE_MS = 750L

        /**
         * TREK-style pinned document: the document is pinned (html,body{overflow:hidden}) and a
         * fixed shell holds an inner overflow:auto scroller far taller than the viewport — the same
         * structure SwipeRefreshGuardTest uses, so the scroll guard is exercised realistically.
         */
        val PINNED_DOC_HTML = """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; height: 100%; overflow: hidden; }
              #shell { position: fixed; top: 0; left: 0; right: 0; bottom: 0; }
              #scroller { height: 100%; overflow: auto; -webkit-overflow-scrolling: touch; }
              #tall { height: 3000px; }
            </style>
            </head><body>
              <div id="shell">
                <div id="scroller">
                  <div id="tall">tall inner content</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        /**
         * A page whose TOTAL content is far shorter than any viewport (a 40px window over 600px of
         * inner content), so the document itself has zero vertical scroll range — canScrollVertically(-1)
         * is structurally false no matter how the inner #mini is scrolled. That lets the reset test feed
         * the JS scroll bridge (via #mini) while the guard's document-scroll component stays false, so
         * only resetToTop() can flip the guard back to "at top". (The full pinned-document / inner-scroll
         * structure is exercised by SwipeRefreshGuardTest; here we isolate the bridge/reset.)
         */
        val SHORT_DOC_INNER_SCROLLER_HTML = """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; padding: 0; }
              #mini { height: 40px; overflow: auto; }
              #inner { height: 600px; }
            </style>
            </head><body>
              <div id="mini"><div id="inner">tall inner content</div></div>
            </body></html>
        """.trimIndent()
    }

    @Before
    fun setUp() {
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)
    }

    @After
    fun tearDown() {
        try {
            EmbaraPrefs.clearServerUrl(targetContext)
        } catch (_: Exception) {
            // Best-effort only — never fail the suite on cleanup.
        }
    }

    /**
     * A client-side route change must RESET the scroll guard to "at top".
     *
     * Scroll the inner container down (the guard reports can-scroll-up=true via the JS bridge →
     * pull-to-refresh suppressed), then fire a same-document history change. doUpdateVisitedHistory
     * calls resetToTop(), so the guard must report can-scroll-up=false again even though no full page
     * load happened. Without the reset the stale scrollTop would keep pull-to-refresh suppressed on the
     * fresh route.
     *
     * The page's total content is shorter than the viewport, so the document has zero vertical scroll
     * range and canScrollVertically(-1) is structurally false (asserted below). Feeding the JS bridge via
     * the small inner #mini scroller therefore makes the guard true ONLY through the bridge, so only
     * resetToTop() can flip it back to "at top". A real https base URL is used so the route change can be
     * a history.pushState — TREK's actual SPA navigation mechanism, which fires doUpdateVisitedHistory
     * (location.hash does not stick on an opaque data: origin).
     */
    @Test
    fun spaRouteChange_resetsScrollGuardToTop() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)

            loadHtmlAndWait(webView, SHORT_DOC_INNER_SCROLLER_HTML, baseUrl = "$TEST_ORIGIN/dashboard")

            // Scroll the small inner container and wait until the guard OBSERVES it (bridge fed
            // asynchronously). Nudge each iteration so a fresh scroll event is guaranteed to fire.
            val scrolledSeen = pollGuard(swipeRefresh, expected = true) { nudge ->
                val target = MINI_SCROLL_TARGET - (nudge and 1) * 10
                evaluateJs(webView) { "document.getElementById('mini').scrollTop=$target; ''" }
            }
            assertTrue(
                "Precondition failed: inner container scrolled but the guard never reported " +
                    "can-scroll-up=true, so the scroll bridge isn't feeding the guard — cannot test " +
                    "the route-change reset.",
                scrolledSeen
            )

            // The guard is true purely because of the bridge: the document is shorter than the viewport,
            // so its canScrollVertically(-1) component must be false. If it ever flips, the scaffolding —
            // not resetToTop — would decide the outcome, so fail loudly rather than pass for a wrong reason.
            assertFalse(
                "Test scaffolding invalid: the document should have no scroll range " +
                    "(canScrollVertically(-1)==false), but the WebView reports it can scroll up.",
                webCanScrollUp(webView)
            )

            // Drain any in-flight scroll events so the only bridge write after the route change is
            // resetToTop() (see SETTLE_MS) — otherwise a late nudge event masks the reset.
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MS)

            // Client-side SPA route change (pushState) → fires doUpdateVisitedHistory → resetToTop().
            pushState(webView, "$TEST_ORIGIN/dashboard/detail")

            // The guard must now report "at top" again: resetToTop() cleared the cached scrollTop and the
            // document has no scroll range (canScrollVertically false), so the bridge is the only input.
            val resetSeen = pollGuard(swipeRefresh, expected = false) { /* no nudge — no scrolling */ }
            val diag = if (!resetSeen) {
                val miniTop = evaluateJs(webView) {
                    "String(Math.round(document.getElementById('mini').scrollTop))"
                }.trim('"')
                val hooked = evaluateJs(webView) { "String(!!window.__embaraPtrHooked)" }.trim('"')
                val loc = evaluateJs(webView) { "String(location.pathname)" }.trim('"')
                " [diag: canScrollVertically(-1)=${webCanScrollUp(webView)}, " +
                    "mini.scrollTop=$miniTop, __embaraPtrHooked=$hooked, location=$loc]"
            } else {
                ""
            }
            assertTrue(
                "SPA route change did not reset the scroll guard: after the history change the guard " +
                    "still reports can-scroll-up=true, so pull-to-refresh stays suppressed on a fresh " +
                    "route that is at the top. doUpdateVisitedHistory must call resetToTop().$diag",
                resetSeen
            )
        }
    }

    /**
     * Pull-to-refresh enable/disable must track SPA route changes.
     *
     * updateSwipeRefreshForUrl enables pull-to-refresh only on dashboard routes. Driving pushState
     * across dashboard and non-dashboard routes must flip SwipeRefreshLayout.isEnabled accordingly —
     * proving the gating follows client-side navigation, not just full page loads.
     */
    @Test
    fun spaRouteChange_togglesPullToRefreshPerRoute() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)

            // Initial load on a NON-dashboard route → pull-to-refresh disabled.
            loadHtmlAndWait(webView, PINNED_DOC_HTML, baseUrl = "$TEST_ORIGIN/settings")
            assertFalse(
                "On a non-dashboard route pull-to-refresh must be disabled after load.",
                isRefreshEnabled(swipeRefresh)
            )

            // Route to a dashboard route → enabled.
            pushState(webView, "$TEST_ORIGIN/dashboard")
            assertTrue(
                "Routing to /dashboard must enable pull-to-refresh, but SwipeRefreshLayout stayed " +
                    "disabled — updateSwipeRefreshForUrl is not tracking SPA navigation.",
                pollEnabled(swipeRefresh, expected = true)
            )

            // Route to a dashboard sub-route → still enabled (startsWith "/dashboard/").
            pushState(webView, "$TEST_ORIGIN/dashboard/library/42")
            assertTrue(
                "A /dashboard/ sub-route must keep pull-to-refresh enabled.",
                pollEnabled(swipeRefresh, expected = true)
            )

            // Route back to a non-dashboard route → disabled again.
            pushState(webView, "$TEST_ORIGIN/settings")
            assertFalse(
                "Routing away from the dashboard must disable pull-to-refresh again.",
                pollEnabled(swipeRefresh, expected = false)
            )
        }
    }

    // --- SPA navigation + polling helpers ---

    /** Client-side route change via the History API (same-origin push). */
    private fun pushState(webView: WebView, url: String) {
        evaluateJs(webView) { "history.pushState({}, '', '$url'); ''" }
    }

    /**
     * Polls SwipeRefreshLayout.canChildScrollUp() until it equals [expected] or the budget expires.
     * [beforeEach] runs each iteration (e.g. to nudge the scroll so a fresh event fires) and receives
     * the iteration index. Returns whether the expected state was observed.
     */
    private fun pollGuard(
        swipeRefresh: SwipeRefreshLayout,
        expected: Boolean,
        beforeEach: (Int) -> Unit,
    ): Boolean {
        val deadline = System.currentTimeMillis() + GUARD_POLL_TIMEOUT_MS
        var i = 0
        while (System.currentTimeMillis() < deadline) {
            beforeEach(i++)
            if (callCanChildScrollUp(swipeRefresh) == expected) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** Polls SwipeRefreshLayout.isEnabled until it equals [expected] or the budget expires. */
    private fun pollEnabled(swipeRefresh: SwipeRefreshLayout, expected: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + GUARD_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRefreshEnabled(swipeRefresh) == expected) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    // --- reflection helpers: reach MainActivity's private lateinit fields ---

    private fun webViewOf(scenario: ActivityScenario<MainActivity>): WebView {
        val holder = arrayOfNulls<WebView>(1)
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("webView").apply {
                isAccessible = true
            }
            holder[0] = field.get(activity) as WebView
        }
        assertNotNull("Could not reflectively obtain MainActivity.webView", holder[0])
        return holder[0]!!
    }

    private fun swipeRefreshOf(scenario: ActivityScenario<MainActivity>): SwipeRefreshLayout {
        val holder = arrayOfNulls<SwipeRefreshLayout>(1)
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("swipeRefresh").apply {
                isAccessible = true
            }
            holder[0] = field.get(activity) as SwipeRefreshLayout
        }
        assertNotNull("Could not reflectively obtain MainActivity.swipeRefresh", holder[0])
        return holder[0]!!
    }

    private fun callCanChildScrollUp(swipeRefresh: SwipeRefreshLayout): Boolean {
        val result = arrayOf(false)
        instrumentation.runOnMainSync {
            result[0] = swipeRefresh.canChildScrollUp()
        }
        return result[0]
    }

    private fun isRefreshEnabled(swipeRefresh: SwipeRefreshLayout): Boolean {
        val result = arrayOf(false)
        instrumentation.runOnMainSync {
            result[0] = swipeRefresh.isEnabled
        }
        return result[0]
    }

    /** The document-scroll component of the guard: webView.canScrollVertically(-1) on the main thread. */
    private fun webCanScrollUp(webView: WebView): Boolean {
        val result = arrayOf(false)
        instrumentation.runOnMainSync {
            result[0] = webView.canScrollVertically(-1)
        }
        return result[0]
    }

    // --- WebView load helper ---

    /**
     * Loads [html] through MainActivity's own WebViewClient (required so the production onPageFinished
     * scroll-hook injection runs) and waits for the load by polling document.readyState.
     *
     * [baseUrl] null → an OPAQUE origin (base64 data:), which genuinely pins the document so the
     * guard's canScrollVertically(-1) component stays false; route changes must then use location.hash
     * (history.pushState is forbidden on an opaque origin). [baseUrl] non-null → that real https origin
     * (via loadDataWithBaseURL), so history.pushState to same-origin paths is permitted.
     */
    private fun loadHtmlAndWait(webView: WebView, html: String, baseUrl: String?) {
        instrumentation.runOnMainSync {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            if (baseUrl == null) {
                webView.loadData(
                    android.util.Base64.encodeToString(
                        html.toByteArray(),
                        android.util.Base64.NO_WRAP,
                    ),
                    "text/html",
                    "base64",
                )
            } else {
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
            }
        }

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(LOAD_TIMEOUT_SECONDS)
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (evaluateJs(webView) { "document.readyState" }.trim('"') == "complete") {
                ready = true
                break
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        if (!ready) throw AssertionError("Timed out waiting for the pinned-document page to load")

        instrumentation.runOnMainSync { webView.requestLayout() }
        instrumentation.waitForIdleSync()
    }

    /** Runs evaluateJavascript on the main thread and returns the JSON-encoded result. */
    private fun evaluateJs(webView: WebView, script: () -> String): String {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script()) { value ->
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
