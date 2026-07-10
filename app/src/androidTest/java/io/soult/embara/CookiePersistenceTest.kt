package io.soult.embara

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Characterizes the CRUX of the "auth not sticky" bug at the CookieManager level.
 *
 * Embara is a WebView wrapper; the TREK login lives entirely inside the WebView, so the
 * auth lives in WebView cookies (there is no token in SharedPreferences — EmbaraPrefs only
 * stores the server URL). MainActivity.onPause() calls CookieManager.getInstance().flush().
 *
 * Android's CookieManager only persists cookies that carry an Expires/Max-Age across process
 * death. A plain SESSION cookie (no expiry) lives in memory only and is dropped when the
 * process is killed — which is exactly the symptom: open Embara, get re-prompted to log in.
 *
 * These tests run on the MAIN thread because CookieManager needs a Looper.
 */
@RunWith(AndroidJUnit4::class)
class CookiePersistenceTest {

    private lateinit var cookieManager: CookieManager

    private companion object {
        const val ORIGIN = "https://trek.example.test/"
        const val AWAIT_SECONDS = 10L
    }

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // CookieManager work needs a Looper — run on the main thread. Clear ONLY this test's own cookies
        // on its synthetic origin (expire by name). A global removeAllCookies here would wipe the shared
        // E2E TREK session and force a re-login, which the live server's rate limit punishes.
        instrumentation.runOnMainSync {
            cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie(ORIGIN, "auth=; Max-Age=0; Path=/")
            cookieManager.setCookie(ORIGIN, "sess=; Max-Age=0; Path=/")
            cookieManager.flush()
        }
    }

    /**
     * A PERSISTENT cookie (Max-Age set) survives flush() and is retrievable.
     *
     * If this fails, even persistent auth cookies are being dropped — a deeper problem than the
     * known session-cookie gap, and the WebView/CookieManager wiring itself would be suspect.
     */
    @Test
    fun persistentCookie_isRetrievableAfterFlush() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            cookieManager.setCookie(ORIGIN, "auth=persist1; Max-Age=86400; Path=/")
            cookieManager.flush()
        }
        val latch = CountDownLatch(1)
        // Settle the setCookie callback so getCookie reflects the write.
        instrumentation.runOnMainSync {
            cookieManager.setCookie(ORIGIN, "auth=persist1; Max-Age=86400; Path=/") { latch.countDown() }
        }
        assertTrue(
            "setCookie callback timed out — could not confirm persistent cookie was written",
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )

        val cookie = readCookie(instrumentation, ORIGIN)
        assertTrue(
            "Persistent cookie missing after flush(). Got: '$cookie'. " +
                "A persistent (Max-Age) auth cookie MUST survive flush — if it doesn't, " +
                "every app cold-start would force a re-login.",
            cookie.contains("auth=persist1")
        )
    }

    /**
     * A SESSION cookie (no Expires/Max-Age) IS accepted and visible IN MEMORY.
     *
     * THIS IS THE PERSISTENCE GAP that explains the re-login bug:
     *  - The cookie is accepted and works for the current process lifetime (asserted here).
     *  - BUT flush() does NOT write session cookies to disk. On process death they vanish.
     *  - TREK auth that rides on a plain session cookie is therefore lost the next cold start,
     *    forcing the user to log in "almost every time they open Embara".
     *
     * We cannot kill and respawn the process within a single instrumented run, so this test
     * documents (a) that the session cookie is accepted in memory and (b) the gap that follows.
     * The assertion is intentionally scoped to in-memory presence.
     */
    @Test
    fun sessionCookie_isInMemoryButFlagsThePersistenceGap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            // NO Max-Age / Expires => this is a session cookie.
            cookieManager.setCookie(ORIGIN, "sess=session1; Path=/") { latch.countDown() }
            // flush() here would NOT persist this cookie to disk — that is the bug's root cause.
            cookieManager.flush()
        }
        assertTrue(
            "setCookie callback timed out — could not confirm session cookie was accepted",
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )

        val cookie = readCookie(instrumentation, ORIGIN)
        assertTrue(
            "Session cookie not accepted in memory. Got: '$cookie'. " +
                "If even the in-memory write fails, cookie acceptance is broken; otherwise the " +
                "documented gap is: this session cookie is NOT persisted by flush() and is lost " +
                "on process death, causing the re-login symptom.",
            cookie.contains("sess=session1")
        )
    }

    /** Reads getCookie() on the main thread and returns "" instead of null for easy assertions. */
    private fun readCookie(
        instrumentation: android.app.Instrumentation,
        url: String
    ): String {
        var result: String? = null
        instrumentation.runOnMainSync {
            result = cookieManager.getCookie(url)
        }
        return result ?: ""
    }
}
