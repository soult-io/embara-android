package io.soult.embara

import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * TEMPORARY diagnostic (runs on the FRAME-DESK host JVM, i.e. the runner's WAN egress — the same IP the
 * emulator NATs through). Probes the TREK login endpoint with a DUMMY password to reveal what the runner
 * actually gets: 401 = normal reject (egress NOT rate-limited), 429 = egress rate-limited, timeout/refused
 * = firewall block. Fails on purpose with the status so it prints. Remove after.
 */
class TrekLoginProbeTest {

    @Test
    fun probe_trekLoginEndpointFromRunnerEgress() {
        val url = URL("https://trek-test.stabpablo.eu/api/auth/login")
        // Real creds when forwarded (see build.gradle), else a dummy. The password is NEVER printed or
        // written — only the resulting status/headers and (on non-200) the generic error body.
        val email = System.getProperty("trekUser") ?: "gplay-test-acc@soult.io"
        val pass = System.getProperty("trekPass") ?: "e2e-probe-dummy-not-real"
        val usingReal = System.getProperty("trekPass") != null
        val result = try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            val payload = "{\"email\":\"${esc(email)}\",\"password\":\"${esc(pass)}\"}"
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")
            val rl = conn.getHeaderField("X-RateLimit-Remaining")
            // Redact the success body (it carries the auth token); show only the generic error body.
            val body = if (code in 200..299) {
                "(2xx — authenticated; body redacted)"
            } else {
                conn.errorStream?.bufferedReader()?.readText()?.take(200)
            }
            "usingRealCreds=$usingReal | HTTP $code | Retry-After=$retryAfter | X-RateLimit-Remaining=$rl | body=$body"
        } catch (e: Exception) {
            "EXCEPTION ${e.javaClass.simpleName}: ${e.message}"
        }
        val line = "TREK-LOGIN-PROBE (runner egress): $result"
        // Write to the downloadable artifact dir so the result can be read back via android-download.
        runCatching { java.io.File("/data/builds/trek-login-probe.txt").writeText(line) }
        throw AssertionError(line)
    }
}
