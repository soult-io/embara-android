package io.soult.embara

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Characterizes the DOM-storage side of the "auth not sticky" bug.
 *
 * TREK can stash auth/session state in the WebView's DOM storage. There are two flavours and
 * they behave very differently for persistence:
 *   - localStorage  : keyed per ORIGIN, shared app-wide, survives across WebView instances.
 *   - sessionStorage: scoped to a single WebView/session, gone when that WebView is destroyed.
 *
 * If TREK auth rides on sessionStorage (or a plain cookie), it does not survive — matching the
 * re-login symptom. These tests pin down which storage actually persists.
 *
 * All WebView work runs on the MAIN thread (WebView requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class DomStoragePersistenceTest {

    private companion object {
        // A stable base URL gives DOM storage a stable ORIGIN to key off of.
        const val BASE_URL = "https://trek.example.test"
        const val HTML = "<html><body>ok</body></html>"
        const val PAGE_LOAD_SECONDS = 15L
        const val JS_RESULT_SECONDS = 10L
    }

    /**
     * localStorage written by one WebView is readable by a fresh WebView at the SAME origin.
     *
     * Passing means localStorage persists in-process across WebView instances — so IF TREK used
     * localStorage for auth, navigating/recreating the WebView would NOT lose the session. A
     * failure here would mean even localStorage is being discarded, an aggravating factor.
     */
    @Test
    fun localStorage_persistsAcrossWebViewInstances() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // --- WebView A: write localStorage ---
        val holderA = arrayOfNulls<WebView>(1)
        loadPage(instrumentation) {
            holderA[0] = newWebView(context).also { it.installLoadLatch(this) }
            holderA[0]!!.loadDataWithBaseURL(BASE_URL, HTML, "text/html", "UTF-8", null)
        }
        val writeResult = evaluateJs(instrumentation, holderA[0]!!) {
            "localStorage.setItem('token','tok-xyz'); 'done'"
        }
        assertTrue(
            "localStorage write did not complete (got: $writeResult)",
            writeResult.contains("done")
        )
        instrumentation.runOnMainSync { holderA[0]!!.destroy() }

        // --- WebView B: read localStorage at the same origin ---
        val holderB = arrayOfNulls<WebView>(1)
        loadPage(instrumentation) {
            holderB[0] = newWebView(context).also { it.installLoadLatch(this) }
            holderB[0]!!.loadDataWithBaseURL(BASE_URL, HTML, "text/html", "UTF-8", null)
        }
        val readResult = evaluateJs(instrumentation, holderB[0]!!) {
            "localStorage.getItem('token')"
        }
        instrumentation.runOnMainSync { holderB[0]!!.destroy() }

        // evaluateJavascript returns JSON-encoded values, so a string comes back quoted.
        assertEquals(
            "localStorage did not persist across WebView instances. " +
                "If even localStorage is dropped, any auth kept there would force a re-login.",
            "\"tok-xyz\"",
            readResult
        )
    }

    /**
     * sessionStorage does NOT survive being read by a brand-new WebView instance.
     *
     * This documents that sessionStorage is per-WebView/session and is therefore NOT a viable
     * persistence mechanism for auth. If TREK relied on sessionStorage, that alone would explain
     * the re-login bug. The expected result is null (JSON "null").
     *
     * Note: kept robust — we only assert it is NOT the value we wrote. If this proves flaky on
     * some WebView builds it can be ignored; the localStorage test above is the primary signal.
     */
    @Test
    fun sessionStorage_doesNotPersistAcrossInstances() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // --- WebView A: write sessionStorage ---
        val holderA = arrayOfNulls<WebView>(1)
        loadPage(instrumentation) {
            holderA[0] = newWebView(context).also { it.installLoadLatch(this) }
            holderA[0]!!.loadDataWithBaseURL(BASE_URL, HTML, "text/html", "UTF-8", null)
        }
        val writeResult = evaluateJs(instrumentation, holderA[0]!!) {
            "sessionStorage.setItem('stoken','sess-only'); 'done'"
        }
        assertTrue(
            "sessionStorage write did not complete (got: $writeResult)",
            writeResult.contains("done")
        )
        instrumentation.runOnMainSync { holderA[0]!!.destroy() }

        // --- WebView B: read sessionStorage in a fresh session ---
        val holderB = arrayOfNulls<WebView>(1)
        loadPage(instrumentation) {
            holderB[0] = newWebView(context).also { it.installLoadLatch(this) }
            holderB[0]!!.loadDataWithBaseURL(BASE_URL, HTML, "text/html", "UTF-8", null)
        }
        val readResult = evaluateJs(instrumentation, holderB[0]!!) {
            "sessionStorage.getItem('stoken')"
        }
        instrumentation.runOnMainSync { holderB[0]!!.destroy() }

        // A new WebView is a new session: the value must NOT carry over.
        assertTrue(
            "sessionStorage unexpectedly persisted across instances (got: $readResult). " +
                "It is session-scoped and must not be used for sticky auth.",
            readResult != "\"sess-only\""
        )
    }

    // --- helpers ---

    private fun newWebView(context: android.content.Context): WebView =
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

    /**
     * Installs an onPageFinished latch on this WebView and counts it down once.
     * The receiver block (loadPage's body) creates the WebView, calls this, then loads the page.
     */
    private fun WebView.installLoadLatch(latch: CountDownLatch) {
        webViewClient = object : WebViewClient() {
            private var fired = false
            override fun onPageFinished(view: WebView, url: String?) {
                if (!fired) {
                    fired = true
                    latch.countDown()
                }
            }
        }
    }

    /**
     * Runs [block] on the main thread (block must create the WebView, install the load latch,
     * and start the load) and waits for onPageFinished.
     */
    private fun loadPage(
        instrumentation: android.app.Instrumentation,
        block: CountDownLatch.() -> Unit
    ) {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { latch.block() }
        if (!latch.await(PAGE_LOAD_SECONDS, TimeUnit.SECONDS)) {
            throw AssertionError("Timed out waiting for WebView page to finish loading")
        }
    }

    /** Runs evaluateJavascript on the main thread and returns the JSON-encoded result. */
    private fun evaluateJs(
        instrumentation: android.app.Instrumentation,
        webView: WebView,
        script: () -> String
    ): String {
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
