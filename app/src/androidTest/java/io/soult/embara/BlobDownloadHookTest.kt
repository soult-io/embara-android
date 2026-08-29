package io.soult.embara

import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hermetic test for [DownloadBridge.BLOB_DOWNLOAD_HOOK_JS] — the riskiest new code, because it runs
 * inside every TREK page and has to catch a synthetic click without disturbing anything else.
 *
 * Both downloads TREK 4.0.0 exposes on a phone go through the same shape: build a Blob, call
 * URL.createObjectURL, append an `<a download>`, click it, remove it. Verified on a device against
 * trek-test on 4.0.0 that this shape currently vanishes — nothing lands in /sdcard/Download, no
 * activity starts, nothing appears in logcat — because DownloadManager cannot fetch a blob: url and
 * Embara had no DownloadListener at all.
 *
 * The markup here reproduces that shape rather than TREK's own code, so the test stays hermetic
 * (no server, no login) while exercising the exact path.
 */
@RunWith(AndroidJUnit4::class)
class BlobDownloadHookTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private companion object {
        // A real origin (rather than a null base) so blob: urls behave as they do on a live page.
        const val BASE_URL = "https://trek.test/"
        const val LOAD_TIMEOUT_SECONDS = 15L
        const val JS_RESULT_SECONDS = 10L
        const val DELIVERY_TIMEOUT_SECONDS = 10L
        // Long enough for a click + FileReader round-trip to have happened if it were going to.
        const val NON_DELIVERY_SETTLE_MS = 2_000L

        const val PAYLOAD = "code-1 code-2 code-3"
        const val FILENAME = "trek-mfa-backup-codes.txt"

        val HTML = """
            <!doctype html><html><head><meta charset="utf-8"></head><body>
            <script>
              function blobDownload(name, text, type) {
                var b = new Blob([text], {type: type});
                var u = URL.createObjectURL(b);
                var a = document.createElement('a');
                a.href = u; a.download = name; a.textContent = name;
                document.body.appendChild(a);
                a.click();
                setTimeout(function(){ URL.revokeObjectURL(u); a.remove(); }, 500);
              }
              function httpDownload(name) {
                var a = document.createElement('a');
                a.href = 'https://trek.test/api/export.ics'; a.download = name;
                document.body.appendChild(a);
                a.click();
                a.remove();
              }
            </script>
            </body></html>
        """.trimIndent()
    }

    /** Stands in for DownloadBridge.JsInterface so the test observes what the page hands over. */
    private class Recorder {
        private val delivered = CountDownLatch(1)
        @Volatile var name: String? = null
        @Volatile var mimeType: String? = null
        @Volatile var base64: String? = null
        @Volatile var failures = 0
        @Volatile var deliveries = 0

        @JavascriptInterface
        fun saveBase64(suggestedName: String?, mimeType: String?, base64: String?) {
            deliveries++
            this.name = suggestedName
            this.mimeType = mimeType
            this.base64 = base64
            delivered.countDown()
        }

        @JavascriptInterface
        fun reportFailure() {
            failures++
            delivered.countDown()
        }

        fun awaitDelivery(seconds: Long): Boolean = delivered.await(seconds, TimeUnit.SECONDS)
    }

    @Test
    fun interceptsABlobDownloadAndHandsOverTheBytes() {
        val recorder = Recorder()
        val webView = createHookedWebView(recorder)
        try {
            runJs(webView, "blobDownload('$FILENAME', '$PAYLOAD', 'text/plain');")

            assertTrue(
                "the blob download was never handed to the bridge — the hook did not intercept it",
                recorder.awaitDelivery(DELIVERY_TIMEOUT_SECONDS),
            )
            assertEquals(0, recorder.failures)
            assertEquals(FILENAME, recorder.name)
            assertTrue(
                "expected a text/plain mime type, got '${recorder.mimeType}'",
                recorder.mimeType?.startsWith("text/plain") == true,
            )
            val decoded = String(Base64.decode(recorder.base64!!, Base64.DEFAULT))
            assertEquals(PAYLOAD, decoded)
        } finally {
            destroy(webView)
        }
    }

    @Test
    fun sanitizesTheNameTheBridgeIsHandedBeforeItReachesTheFilesystem() {
        val recorder = Recorder()
        val webView = createHookedWebView(recorder)
        try {
            runJs(webView, "blobDownload('../../evil.txt', 'x', 'text/plain');")
            assertTrue(recorder.awaitDelivery(DELIVERY_TIMEOUT_SECONDS))
            // The hook passes the page's name through verbatim — sanitizing is the native side's
            // job, so assert the boundary it actually has to defend.
            assertEquals("../../evil.txt", recorder.name)
            assertEquals("evil.txt", DownloadNaming.sanitize(recorder.name))
        } finally {
            destroy(webView)
        }
    }

    /**
     * An http(s) `<a download>` must be left alone: those still take WebKit's normal path into the
     * DownloadListener, which fetches them with the session cookies. If the hook grabbed them too,
     * the whole page's bytes would be re-read through a base64 string for no reason.
     */
    @Test
    fun leavesAnHttpDownloadToTheNormalPath() {
        val recorder = Recorder()
        val webView = createHookedWebView(recorder)
        try {
            runJs(webView, "httpDownload('trip.ics');")
            Thread.sleep(NON_DELIVERY_SETTLE_MS)
            assertNull("the hook must not intercept an http(s) download", recorder.base64)
            assertEquals(0, recorder.failures)
        } finally {
            destroy(webView)
        }
    }

    @Test
    fun isIdempotentSoRepeatedInjectionDoesNotDoubleDeliver() {
        val recorder = Recorder()
        val webView = createHookedWebView(recorder)
        try {
            // onPageFinished and doUpdateVisitedHistory can both fire for one view; the hook guards
            // itself with window.__embaraDlHooked so the listener is only ever attached once.
            runJs(webView, DownloadBridge.BLOB_DOWNLOAD_HOOK_JS)
            runJs(webView, DownloadBridge.BLOB_DOWNLOAD_HOOK_JS)
            val listeners = runJs(webView, "String(window.__embaraDlHooked === true)")
            assertEquals("\"true\"", listeners)

            runJs(webView, "blobDownload('$FILENAME', '$PAYLOAD', 'text/plain');")
            assertTrue(recorder.awaitDelivery(DELIVERY_TIMEOUT_SECONDS))
            Thread.sleep(NON_DELIVERY_SETTLE_MS)
            assertEquals(PAYLOAD, String(Base64.decode(recorder.base64!!, Base64.DEFAULT)))
            assertEquals("one click must produce exactly one delivery", 1, recorder.deliveries)
        } finally {
            destroy(webView)
        }
    }

    // -- scaffolding --

    private fun createHookedWebView(recorder: Recorder): WebView {
        val holder = arrayOfNulls<WebView>(1)
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            @Suppress("SetJavaScriptEnabled")
            val webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(recorder, DownloadBridge.BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(DownloadBridge.BLOB_DOWNLOAD_HOOK_JS) { loaded.countDown() }
                    }
                }
            }
            holder[0] = webView
            webView.loadDataWithBaseURL(BASE_URL, HTML, "text/html", "UTF-8", null)
        }
        assertTrue("page never finished loading", loaded.await(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return holder[0]!!
    }

    private fun runJs(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                box[0] = value
                latch.countDown()
            }
        }
        assertTrue("timed out evaluating JS", latch.await(JS_RESULT_SECONDS, TimeUnit.SECONDS))
        return box[0] ?: "null"
    }

    private fun destroy(webView: WebView) {
        instrumentation.runOnMainSync { webView.destroy() }
    }
}
