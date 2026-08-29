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
 * Hermetic test for [DownloadBridge.hookJs(NONCE)] — the riskiest new code, because it runs
 * inside every TREK page and has to catch a synthetic click without disturbing anything else.
 *
 * Both downloads TREK 4.0.0 exposes on a phone go through the same shape: build a Blob, call
 * URL.createObjectURL, append an `<a download>`, click it, remove it. Verified on a device against
 * trek-test on 4.0.0 that this shape currently vanishes — nothing lands in /sdcard/Download, no
 * activity starts, nothing appears in logcat — because DownloadManager cannot fetch a blob: url and
 * Embara had no DownloadListener at all.
 *
 * The markup here reproduces that shape rather than TREK's own code, so the test stays hermetic
 * (no server, no login) while exercising the exact path — including TREK's `connect-src` CSP, which
 * is what makes the obvious implementation (fetch the object url back) fail on the real page.
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
        const val NONCE = "0123456789abcdef0123456789abcdef"

        val HTML = """
            <!doctype html><html><head><meta charset="utf-8">
            <!--
              TREK serves a connect-src CSP that does NOT list blob:. Reproduce it here, because a
              hook that reads the blob back with fetch() passes without it and is refused by the
              real page ("Refused to connect because it violates the document's Content Security
              Policy") — which is exactly how this was missed the first time.
            -->
            <meta http-equiv="Content-Security-Policy" content="connect-src 'self'">
            </head><body>
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
              function blobDownloadOfSize(name, size) {
                var b = new Blob([new Uint8Array(1)], {type: 'application/octet-stream'});
                // Report an oversized blob without allocating one: the hook reads .size, and a real
                // 24 MB allocation in a test is slow and flaky on a headless emulator.
                Object.defineProperty(b, 'size', {value: size});
                var u = URL.createObjectURL(b);
                var a = document.createElement('a');
                a.href = u; a.download = name;
                document.body.appendChild(a);
                a.click();
                a.remove();
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

        @Volatile var nonce: String? = null

        @JavascriptInterface
        fun saveBase64(nonce: String?, suggestedName: String?, mimeType: String?, base64: String?) {
            deliveries++
            this.nonce = nonce
            this.name = suggestedName
            this.mimeType = mimeType
            this.base64 = base64
            delivered.countDown()
        }

        @JavascriptInterface
        fun reportFailure(nonce: String?) {
            failures++
            this.nonce = nonce
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
            assertEquals("every call must carry this document's nonce", NONCE, recorder.nonce)
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
            runJs(webView, DownloadBridge.hookJs(NONCE))
            runJs(webView, DownloadBridge.hookJs(NONCE))
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

    /**
     * The size limit has to bite in JS, before FileReader materialises the whole blob as a base64
     * data url. Checking it only on the native side means the renderer has already made two full
     * copies of the payload by the time anything can refuse it.
     */
    @Test
    fun refusesAnOversizedBlobWithoutReadingIt() {
        val recorder = Recorder()
        val webView = createHookedWebView(recorder)
        try {
            val oversized = DownloadBridge.MAX_BLOB_BYTES + 1024
            runJs(webView, "blobDownloadOfSize('big.bin', $oversized);")
            assertTrue(
                "an oversized blob must be refused, not silently dropped",
                recorder.awaitDelivery(DELIVERY_TIMEOUT_SECONDS),
            )
            assertEquals("it must be refused, not delivered", 0, recorder.deliveries)
            assertEquals(1, recorder.failures)
        } finally {
            destroy(webView)
        }
    }

    /**
     * onPageStarted rotates the nonce, so a hook still holding the previous one would silently
     * reject every download for the rest of that document — the exact silent failure this fixes.
     * Re-injection must therefore update the nonce the already-attached listener uses.
     *
     * The nonce lives on `window`, not in the closure, precisely so that can happen. That does not
     * weaken the gate: it exists to stop OTHER frames, and a cross-origin frame can no more read
     * this window's properties than its closures. Same-origin script is inside the trust boundary
     * by design — it is the TREK page itself.
     */
    @Test
    fun aReinjectionUpdatesTheNonceTheHookSends() {
        val recorder = Recorder()
        val webView = createHookedWebView(recorder)
        try {
            val rotated = "fedcba9876543210fedcba9876543210"
            runJs(webView, DownloadBridge.hookJs(rotated))

            runJs(webView, "blobDownload('$FILENAME', '$PAYLOAD', 'text/plain');")
            assertTrue(recorder.awaitDelivery(DELIVERY_TIMEOUT_SECONDS))
            assertEquals("the hook must send the CURRENT nonce, not the one it was built with",
                rotated, recorder.nonce)
            assertEquals(PAYLOAD, String(Base64.decode(recorder.base64!!, Base64.DEFAULT)))
        } finally {
            destroy(webView)
        }
    }

    @Test
    fun interpolatesTheNonceAsAQuotedLiteral() {
        // A nonce spliced in raw would let a quote or a backslash close the string and run as code.
        val js = DownloadBridge.hookJs("a\"b\\c")
        assertTrue(
            "expected a JSON-quoted literal, got: " + js.substringAfter("var NONCE = ").take(40),
            js.contains("""var NONCE = "a\"b\\c";"""),
        )
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
                        view.evaluateJavascript(DownloadBridge.hookJs(NONCE)) { loaded.countDown() }
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
