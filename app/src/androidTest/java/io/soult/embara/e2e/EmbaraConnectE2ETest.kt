package io.soult.embara.e2e

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.soult.embara.EmbaraPrefs
import io.soult.embara.MainActivity
import io.soult.embara.R
import io.soult.embara.SetupActivity
import io.soult.embara.e2e.support.E2EConfig
import io.soult.embara.e2e.support.ServerHealthCheck
import io.soult.embara.e2e.support.TrekE2E
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T3 E2E — first-run & connection journeys (plan group A) against the REAL test TREK server.
 *
 * This is the harness's walking skeleton: [coldLaunch_showsSetupScreen] needs no server and proves the
 * E2E rig runs on the GMD device; [webView_loadsRealTrekServer] exercises a real connection and skips
 * (via [ServerHealthCheck]) when the server/credentials aren't injected. Authenticated journeys
 * (login → dashboard → PTR → logout) build on this in the next increment.
 */
@RunWith(AndroidJUnit4::class)
class EmbaraConnectE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val trek = TrekE2E(InstrumentationRegistry.getInstrumentation())

    private companion object {
        const val WEBVIEW_LOAD_TIMEOUT_MS = 20_000L
        const val POLL_MS = 200L
    }

    @Before
    fun setUp() {
        EmbaraPrefs.clearServerUrl(context)
    }

    @After
    fun tearDown() {
        try {
            EmbaraPrefs.clearServerUrl(context)
        } catch (_: Exception) {
            // Best-effort cleanup — never fail the suite here.
        }
    }

    /**
     * A1 — cold launch with no server configured lands on the setup screen (server field + CONNECT).
     * Needs no server, so it always runs and is the smoke test for the whole E2E harness.
     */
    @Test
    fun coldLaunch_showsSetupScreen() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.url_input)).check(matches(isDisplayed()))
            onView(withId(R.id.connect_button)).check(matches(isDisplayed()))
        }
    }

    /**
     * A3 — pointing the app at the real test TREK server loads its web app in the WebView. Skips when
     * the server isn't configured/reachable. Validates the injected config end-to-end and real egress
     * from the GMD device to the test instance.
     */
    @Test
    fun webView_loadsRealTrekServer() {
        ServerHealthCheck.assumeReachable()
        val serverUrl = E2EConfig.serverUrl!!
        EmbaraPrefs.setServerUrl(context, serverUrl)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val webView = trek.webViewOf(scenario)
            assertTrue(
                "WebView never loaded the configured test server ($serverUrl).",
                waitForWebViewHost(webView, serverUrl),
            )
        }
    }

    // --- helpers ---

    private fun waitForWebViewHost(webView: WebView, serverUrl: String): Boolean {
        val host = android.net.Uri.parse(serverUrl).host ?: return false
        val deadline = System.currentTimeMillis() + WEBVIEW_LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val url = arrayOfNulls<String>(1)
            instrumentation.runOnMainSync { url[0] = webView.url }
            if (url[0]?.contains(host) == true) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }
}
