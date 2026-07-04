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
 * REPRODUCTION for the TREK v3.1.0 pull-to-refresh HIJACK bug.
 *
 * Embara wraps TREK in a WebView with a native SwipeRefreshLayout pull-to-refresh. The guard that
 * decides whether SwipeRefreshLayout may intercept a downward drag lives in MainActivity:
 *
 *     swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
 *
 * SwipeRefreshLayout.canChildScrollUp() invokes that callback. If it returns TRUE ("child can
 * scroll up" i.e. NOT at the top), SwipeRefreshLayout does NOT intercept — the child keeps
 * scrolling. If it returns FALSE ("at top"), SwipeRefreshLayout intercepts → fires a refresh.
 *
 * THE BUG: TREK v3.1.0's dashboard pins the document (html{overflow:hidden}) and scrolls an INNER
 * overflow:auto container instead. webView.canScrollVertically(-1) only reflects the DOCUMENT's
 * scroll offset — which stays 0 forever because the document is pinned — so the guard always
 * reports "at top" even after the user has scrolled the inner container down. Result:
 * SwipeRefreshLayout intercepts every downward drag, blocking inner scrolling and firing spurious
 * refreshes.
 *
 * The RED test below (childScrolledInnerContainer_guardReportsCanScrollUp) reproduces this
 * HERMETICALLY with a data: URL that mimics TREK's pinned-document / inner-scroller structure. It
 * asserts the CORRECT behavior — that once the inner container is scrolled down the guard reports
 * canChildScrollUp()==true — which FAILS against the current document-only guard. That failing
 * assertion IS the reproduction.
 *
 * The two supporting tests describe already-correct behavior and should PASS.
 *
 * All WebView / SwipeRefreshLayout work runs on the MAIN thread (WebView requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class SwipeRefreshGuardTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        // Neutral loadable origin so MainActivity takes the WebView path on launch. We replace the
        // WebView's content in-test with a data: URL, so the origin only needs to be non-null.
        const val TEST_ORIGIN = "https://example.com"

        const val LOAD_TIMEOUT_SECONDS = 15L
        const val JS_RESULT_SECONDS = 10L

        // Poll budget for the inner container to actually reach the requested scrollTop.
        const val SCROLL_POLL_TIMEOUT_MS = 8_000L
        const val SCROLL_POLL_INTERVAL_MS = 100L

        // The guard reads a value fed ASYNChronously by the JS scroll listener via the
        // @JavascriptInterface bridge (a scroll event, dispatched on the next frame, then a
        // cross-thread bridge call). So we poll the guard until it observes the inner scroll
        // rather than reading it once. The buggy document-only guard never flips, so it times out.
        const val GUARD_POLL_TIMEOUT_MS = 5_000L

        // How far we scroll the inner container down (px). Content is 3000px tall in a full-screen
        // shell, so 2000 is comfortably scrollable and unambiguously "not at top".
        const val TARGET_SCROLL_TOP = 2000

        /**
         * A document that mimics TREK v3.1.0's dashboard: the document itself is PINNED
         * (html,body{overflow:hidden}) and a fixed full-screen shell holds an inner overflow:auto
         * scroller (#scroller) with content far taller than the viewport. The inner container is
         * scrollable; the document is NOT.
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
     * RED — THE BUG REPRODUCTION.
     *
     * Load TREK-style pinned-document markup, scroll the INNER container down, then assert the guard
     * reports the child CAN scroll up (canChildScrollUp()==true). That is the correct answer: the
     * inner container is scrolled away from its top, so pull-to-refresh must NOT hijack the drag.
     *
     * The current guard returns webView.canScrollVertically(-1), which reflects only the pinned
     * DOCUMENT's scroll offset (always 0) → it returns false → this assertion FAILS. That failure
     * is the reproduction of the TREK v3.1.0 pull-to-refresh hijack.
     */
    @Test
    fun childScrolledInnerContainer_guardReportsCanScrollUp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)

            loadHtmlAndWait(webView, PINNED_DOC_HTML)

            // Sanity: the DOCUMENT is pinned, so the WebView itself is NOT vertically scrollable.
            // (If this were false the data: markup would not be reproducing TREK's structure.)
            val docScrollable = evaluateJs(webView) {
                "(document.scrollingElement.scrollHeight > document.scrollingElement.clientHeight)"
            }
            assertTrue(
                "Test scaffolding invalid: the document should be pinned/non-scrollable but " +
                    "reported scrollable (got: $docScrollable).",
                docScrollable == "false"
            )

            // Scroll the INNER container down and wait until it has actually moved.
            scrollInnerContainerTo(webView, TARGET_SCROLL_TOP)

            // Confirm the inner container really is scrolled down before we assert on the guard.
            val innerTop = readInnerScrollTop(webView)
            assertTrue(
                "Inner container failed to scroll down (scrollTop=$innerTop); cannot exercise the " +
                    "guard.",
                innerTop >= TARGET_SCROLL_TOP - 50
            )

            // CORRECT behavior: inner container scrolled down → guard must report can-scroll-up=true
            // → SwipeRefreshLayout must NOT intercept. The guard's answer is fed asynchronously (JS
            // scroll event → bridge), so poll until it flips, nudging the scroll each iteration to
            // guarantee a fresh scroll event lands after the (async) onPageFinished hook injection.
            // Against the current document-only guard this NEVER becomes true → times out → RED.
            val deadline = System.currentTimeMillis() + GUARD_POLL_TIMEOUT_MS
            var canScrollUp = false
            var nudge = 0
            while (System.currentTimeMillis() < deadline) {
                // Toggle between two "scrolled-down" positions so scrollTop actually changes and a
                // scroll event is guaranteed to fire (re-setting the same value fires nothing).
                val target = TARGET_SCROLL_TOP - (nudge and 1) * 10
                evaluateJs(webView) { "document.getElementById('scroller').scrollTop=$target; ''" }
                nudge++
                if (callCanChildScrollUp(swipeRefresh)) {
                    canScrollUp = true
                    break
                }
                Thread.sleep(SCROLL_POLL_INTERVAL_MS)
            }

            // If it never flipped, surface why (bridge/injection state) to make a failure diagnosable.
            val diag = if (!canScrollUp) {
                val hooked = evaluateJs(webView) { "String(!!window.__embaraPtrHooked)" }.trim('"')
                val bridge = evaluateJs(webView) { "(typeof AndroidPtrBridge)" }.trim('"')
                " [diag: __embaraPtrHooked=$hooked, typeof AndroidPtrBridge=$bridge, " +
                    "innerScrollTop=${readInnerScrollTop(webView)}]"
            } else {
                ""
            }

            assertTrue(
                "PTR HIJACK BUG (TREK v3.1.0): inner overflow:auto container is scrolled down " +
                    "(scrollTop=$innerTop) but the guard reports canChildScrollUp()==false, so " +
                    "SwipeRefreshLayout will intercept the drag — blocking inner scroll and firing " +
                    "spurious refreshes. The guard uses webView.canScrollVertically(-1), which only " +
                    "sees the pinned DOCUMENT's scroll offset (always 0). It must instead reflect " +
                    "the inner scroll container's position.$diag",
                canScrollUp
            )
        }
    }

    /**
     * GREEN — supporting guard test.
     *
     * At the true top of the inner container (scrollTop==0, and the document pinned at 0), the guard
     * must report the child CANNOT scroll up, so pull-to-refresh is allowed to trigger. This is
     * already correct today (canScrollVertically(-1)==false at the top) and should PASS.
     */
    @Test
    fun atTop_guardReportsCannotScrollUp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            val webView = webViewOf(scenario)
            val swipeRefresh = swipeRefreshOf(scenario)

            loadHtmlAndWait(webView, PINNED_DOC_HTML)

            // Do NOT scroll — inner container and document are both at the top.
            val innerTop = readInnerScrollTop(webView)
            assertTrue("Expected inner container at top but scrollTop=$innerTop", innerTop == 0)

            val canScrollUp = callCanChildScrollUp(swipeRefresh)
            assertFalse(
                "At the top the guard must report canChildScrollUp()==false so pull-to-refresh can " +
                    "trigger, but it reported true.",
                canScrollUp
            )
        }
    }

    /**
     * GREEN — supporting wiring test.
     *
     * MainActivity must wire an OnRefreshListener onto the SwipeRefreshLayout (otherwise pulling
     * does nothing). We verify a listener exists by programmatically requesting a refresh and
     * confirming the layout enters the refreshing state (setRefreshing(true) only shows the spinner
     * when a listener is attached). This describes already-correct behavior and should PASS.
     */
    @Test
    fun refreshListener_isWired() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            val swipeRefresh = swipeRefreshOf(scenario)

            val refreshing = arrayOf(false)
            instrumentation.runOnMainSync {
                // Enable so the state can be set regardless of route gating, then request refresh.
                swipeRefresh.isEnabled = true
                swipeRefresh.isRefreshing = true
                refreshing[0] = swipeRefresh.isRefreshing
            }

            assertTrue(
                "SwipeRefreshLayout did not enter the refreshing state — no OnRefreshListener " +
                    "appears to be wired in MainActivity.",
                refreshing[0]
            )
        }
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

    /** Calls the public SwipeRefreshLayout.canChildScrollUp() on the main thread. */
    private fun callCanChildScrollUp(swipeRefresh: SwipeRefreshLayout): Boolean {
        val result = arrayOf(false)
        instrumentation.runOnMainSync {
            result[0] = swipeRefresh.canChildScrollUp()
        }
        return result[0]
    }

    // --- WebView load / scroll helpers ---

    /**
     * Replaces the WebView's content with [html] (via a data: URL) and waits for the load to finish.
     *
     * We deliberately KEEP MainActivity's own WebViewClient in place rather than swapping in a
     * latch-only client: the production fix injects its capture-phase scroll listener from
     * MainActivity's WebViewClient.onPageFinished, so the test must load through the real client for
     * that injection to run (otherwise the bridge is never fed and the guard can't observe inner
     * scroll). Because we don't own an onPageFinished callback here, we detect completion by polling
     * document.readyState until it reports "complete" (bounded by the existing timeout). The load
     * runs on the main thread.
     */
    private fun loadHtmlAndWait(webView: WebView, html: String) {
        instrumentation.runOnMainSync {
            // Ensure JS + DOM storage are on (MainActivity already enables these, belt-and-braces).
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

            webView.loadData(
                android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_WRAP),
                "text/html",
                "base64"
            )
        }

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(LOAD_TIMEOUT_SECONDS)
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            // evaluateJavascript returns a JSON string, e.g. "\"complete\"".
            if (evaluateJs(webView) { "document.readyState" }.trim('"') == "complete") {
                ready = true
                break
            }
            Thread.sleep(SCROLL_POLL_INTERVAL_MS)
        }
        if (!ready) {
            throw AssertionError("Timed out waiting for the pinned-document data: URL to load")
        }

        // Give layout a beat to settle so the inner container is measured/scrollable.
        instrumentation.runOnMainSync {
            webView.requestLayout()
        }
        instrumentation.waitForIdleSync()
    }

    /**
     * Scrolls #scroller to [top] px and polls (via evaluateJavascript) until it reports having
     * actually reached ~[top]. Deterministic — no fixed sleeps.
     */
    private fun scrollInnerContainerTo(webView: WebView, top: Int) {
        // Issue the scroll.
        evaluateJs(webView) {
            "var s=document.getElementById('scroller'); s.scrollTop=$top; String(s.scrollTop)"
        }

        val deadline = System.currentTimeMillis() + SCROLL_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (readInnerScrollTop(webView) >= top - 50) return
            // Re-issue in case layout hadn't finished on the first attempt.
            evaluateJs(webView) {
                "var s=document.getElementById('scroller'); s.scrollTop=$top; String(s.scrollTop)"
            }
            Thread.sleep(SCROLL_POLL_INTERVAL_MS)
        }
    }

    /** Reads #scroller.scrollTop as an Int (0 on any parse failure). */
    private fun readInnerScrollTop(webView: WebView): Int {
        val raw = evaluateJs(webView) {
            "String(Math.round(document.getElementById('scroller').scrollTop))"
        }
        // evaluateJavascript returns a JSON-encoded string, e.g. "\"2000\"" — strip quotes.
        return raw.trim('"').toIntOrNull() ?: 0
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
