package io.soult.embara.e2e.support

import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.URI

/**
 * Gate for server-dependent E2E journeys. Call from `@Before`: it SKIPS (not fails) the test when the
 * test server is unconfigured or unreachable, so a failed E2E run always means the app under test
 * broke — not that the secret wasn't injected or the test instance had a hiccup. Real app failures
 * still fail normally.
 *
 * Runs on the instrumentation thread (not the app UI thread), so the network call here is allowed.
 */
object ServerHealthCheck {
    private const val TIMEOUT_MS = 8_000

    fun assumeReachable() {
        val url = E2EConfig.serverUrl
        assumeTrue(
            "E2E skipped: no e2eServerUrl injected (expected on PR / non-secret runners).",
            url != null,
        )
        assumeTrue("E2E skipped: test server $url is unreachable.", isReachable(url!!))
    }

    private fun isReachable(url: String): Boolean = try {
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            // Any HTTP status means the server answered — 401/403/404 still count as "up".
            conn.responseCode in 200..499
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }
}
