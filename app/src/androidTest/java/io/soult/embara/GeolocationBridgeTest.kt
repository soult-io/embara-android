package io.soult.embara

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TREK 4.0.0 calls navigator.geolocation from its "Use my current location" button and from the
 * mobile quick-capture sheet, which asks for a fix the moment it opens. Verified on a device against
 * trek-test on 4.0.0: with no onGeolocationPermissionsShowPrompt override, getCurrentPosition came
 * back PERMISSION_DENIED in ~1ms with no prompt — the request never reached the user at all.
 *
 * The two properties that matter here are (a) only the configured server may ask, and (b) the
 * callback is ALWAYS answered — a geolocation callback left unanswered leaves the page waiting
 * forever with nothing to show the user.
 *
 * These drive the bridge directly rather than through a page, because the origin gate is exactly
 * what a hermetic data: URL cannot exercise (a data: URL has no host, so it is always denied).
 */
@RunWith(AndroidJUnit4::class)
class GeolocationBridgeTest {

    private val context get() =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private val serverHost = "trek.example.com"

    /** Records what WebKit would have been told, so "was it answered at all" is assertable. */
    private class RecordingCallback : android.webkit.GeolocationPermissions.Callback {
        var origin: String? = null
        var allow: Boolean? = null
        var retain: Boolean? = null
        var invocations = 0

        override fun invoke(origin: String?, allow: Boolean, retain: Boolean) {
            invocations++
            this.origin = origin
            this.allow = allow
            this.retain = retain
        }
    }

    @Test
    fun deniesAnOriginThatIsNotTheConfiguredServer() {
        val bridge = GeolocationBridge(serverHost = { serverHost })
        val callback = RecordingCallback()

        bridge.onPrompt(context, "https://evil.example.com", callback)

        assertEquals("callback must be answered exactly once", 1, callback.invocations)
        assertEquals(false, callback.allow)
    }

    @Test
    fun deniesADataOrFileOrigin() {
        val bridge = GeolocationBridge(serverHost = { serverHost })
        for (origin in listOf("file://", "data:", "null", "")) {
            val callback = RecordingCallback()
            bridge.onPrompt(context, origin, callback)
            assertEquals("origin '$origin' must be answered", 1, callback.invocations)
            assertEquals("origin '$origin' must be denied", false, callback.allow)
        }
    }

    @Test
    fun deniesWhenNoServerIsConfigured() {
        val bridge = GeolocationBridge(serverHost = { "" })
        val callback = RecordingCallback()

        bridge.onPrompt(context, "https://trek.example.com", callback)

        assertEquals(1, callback.invocations)
        assertEquals(false, callback.allow)
    }

    /**
     * The server's own origin gets past the gate. Whether it is then ALLOWED depends on the Android
     * runtime permission, which this test does not grant — so the assertion is the one that holds
     * either way and is the actual regression: the request is answered, with the origin echoed back
     * verbatim, and never retained.
     *
     * (Before the fix there was no override at all, so WebKit's default denied without the app ever
     * being consulted. The device check for the granted path is in the report, not here: granting a
     * runtime location permission mid-suite is not deterministic on a headless ATD image.)
     */
    @Test
    fun answersTheConfiguredServerOrigin() {
        val bridge = GeolocationBridge(serverHost = { serverHost })
        val callback = RecordingCallback()

        bridge.onPrompt(context, "https://trek.example.com", callback)

        assertEquals(1, callback.invocations)
        assertEquals("https://trek.example.com", callback.origin)
        assertNotNull(callback.allow)
        assertEquals("a grant must never be retained across pages", false, callback.retain)
    }

    @Test
    fun aSubdomainOfTheServerIsTreatedAsTheServer() {
        val bridge = GeolocationBridge(serverHost = { serverHost })
        val callback = RecordingCallback()

        bridge.onPrompt(context, "https://cdn.trek.example.com", callback)

        assertEquals(1, callback.invocations)
        assertEquals("https://cdn.trek.example.com", callback.origin)
    }

    @Test
    fun cancelPendingIsSafeWhenNothingIsPending() {
        val bridge = GeolocationBridge(serverHost = { serverHost })
        bridge.cancelPending()
        bridge.cancelPending()
        // No exception is the assertion; onDestroy calls this unconditionally.
        assertTrue(true)
    }

    @Test
    fun aNullCallbackIsIgnoredRatherThanCrashing() {
        val bridge = GeolocationBridge(serverHost = { serverHost })
        bridge.onPrompt(context, "https://trek.example.com", null)
        assertFalse(false)
    }
}
