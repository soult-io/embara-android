package io.soult.embara

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Guards that MainActivity actually opts into the three WebView surfaces TREK 4.0.0's mobile shell
 * needs. Each one is off by default in WebKit and fails silently when it is missing — verified on a
 * device against trek-test on 4.0.0 before the fix: geolocation answered PERMISSION_DENIED in ~1ms
 * with no prompt, clicking an `<input type=file>` started no activity at all, and a blob download
 * produced no file and no error.
 *
 * "Silently" is why these are asserted structurally rather than left to a manual pass: a removed
 * override produces no crash, no log and no failing behaviour test anywhere else.
 */
@RunWith(AndroidJUnit4::class)
class WebViewCapabilityWiringTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        const val TEST_ORIGIN = "https://example.com"
        const val JS_RESULT_SECONDS = 10L
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

    @Test
    fun webChromeClientOverridesTheGeolocationPrompt() {
        assertOverridden("onGeolocationPermissionsShowPrompt")
    }

    @Test
    fun webChromeClientOverridesTheFileChooser() {
        assertOverridden("onShowFileChooser")
    }

    @Test
    fun theDownloadBridgeIsReachableFromThePage() {
        withMainActivityWebView { webView ->
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(DownloadBridge.hookJs("test-nonce")) { loaded.countDown() }
            }
            assertTrue(loaded.await(JS_RESULT_SECONDS, TimeUnit.SECONDS))

            val type = evaluateJs(webView, "typeof ${DownloadBridge.BRIDGE_NAME}.saveBase64")
            assertEquals(
                "the blob-download bridge is not exposed to the page",
                "\"function\"",
                type,
            )
        }
    }

    /**
     * The invariant that keeps a file input usable: WebKit raises at most one chooser per input and
     * will not raise another until the callback is answered, so any path that cannot open a chooser
     * must still answer it with null. Exercised here on a bridge with no launcher registered, which
     * is the un-openable case.
     */
    @Test
    fun anUnopenableFileChooserStillAnswersItsCallback() {
        val bridge = FileChooserBridge()
        val received = arrayOfNulls<Array<Uri>>(1)
        var invocations = 0
        val callback = ValueCallback<Array<Uri>> { value ->
            invocations++
            received[0] = value
        }

        val raised = bridge.onShowFileChooser(targetContext, callback, StubParams())

        assertEquals("chooser could not be raised, so it must report false", false, raised)
        assertEquals("the callback must be answered exactly once", 1, invocations)
        assertNull("an unopenable chooser answers with null, not an empty array", received[0])
    }

    @Test
    fun aNullCallbackIsIgnoredRatherThanCrashing() {
        val bridge = FileChooserBridge()
        assertEquals(false, bridge.onShowFileChooser(targetContext, null, StubParams()))
    }

    @Test
    fun cancelPendingIsSafeWhenNothingIsPending() {
        val bridge = FileChooserBridge()
        bridge.cancelPending()
        bridge.cancelPending()
        assertTrue(true)
    }

    // -- scaffolding --

    /** Minimal FileChooserParams; only createIntent/isCaptureEnabled are read by the bridge. */
    private class StubParams : WebChromeClient.FileChooserParams() {
        override fun getAcceptTypes(): Array<String> = arrayOf("image/*")
        override fun isCaptureEnabled(): Boolean = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun getMode(): Int = MODE_OPEN
        override fun createIntent(): android.content.Intent =
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*")
    }

    private fun assertOverridden(methodName: String) {
        withMainActivityWebView { webView ->
            // WebView is thread-affine: every one of its methods, getters included, must be called
            // on the thread that created it.
            val holder = arrayOfNulls<android.webkit.WebChromeClient>(1)
            instrumentation.runOnMainSync { holder[0] = webView.webChromeClient }
            val client = holder[0]
            assertNotNull("MainActivity installed no WebChromeClient", client)
            val declared = client!!.javaClass.declaredMethods.any { it.name == methodName }
            assertTrue(
                "MainActivity's WebChromeClient does not override $methodName — the matching " +
                    "TREK 4.0.0 surface will fail silently",
                declared,
            )
        }
    }

    private fun withMainActivityWebView(block: (WebView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val holder = arrayOfNulls<WebView>(1)
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
                holder[0] = field.get(activity) as WebView
            }
            assertNotNull("Could not reflectively obtain MainActivity.webView", holder[0])
            block(holder[0]!!)
        }
    }

    private fun evaluateJs(webView: WebView, script: String): String {
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
}
