package io.soult.embara

import android.app.Instrumentation
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * COOKIE ATTRIBUTE / SCHEME / DOMAIN / PATH / EXPIRY MATCHING characterization.
 *
 * Embara is a WebView wrapper; TREK auth rides entirely on a WebView cookie. A leading root-cause
 * family for the "re-login almost every time" bug is: the auth cookie IS on disk / in the store,
 * but CookieManager.getCookie(url) OMITS it from the request string for the URL the WebView loads.
 * That omission can be caused by cookie ATTRIBUTE rules rather than persistence:
 *   - Secure       -> cookie withheld over http
 *   - host-only    -> cookie withheld for a different host
 *   - Domain       -> cookie scope across subdomains
 *   - Path         -> cookie withheld outside the path subtree
 *   - Expires/Max-Age -> expired cookie withheld (TTL)
 *
 * These tests probe each rule in isolation using ONLY CookieManager.setCookie + getCookie — no
 * WebView load, no network. We assert on cookie NAMES only (never values), so the assertions are
 * about RETRIEVABILITY/scope, not about leaking any secret material into logs or failure messages.
 *
 * Each test asserts the DESIRED behavior. A failure therefore LOCALIZES which attribute rule is
 * responsible for the auth cookie being on disk but not sent.
 *
 * SCOPE NOTE — SameSite is intentionally NOT exercised here. SameSite gates whether a cookie is
 * attached to an outgoing REQUEST based on the navigation/initiator context (same-site vs
 * cross-site). CookieManager.getCookie(url) has no notion of an initiator or request context — it
 * returns the cookies stored for the URL regardless of SameSite — so SameSite cannot be observed
 * through getCookie() and is out of scope for this file. It would require a real navigation/network
 * exercise to validate.
 *
 * All CookieManager work runs on the MAIN thread because CookieManager requires a Looper.
 */
@RunWith(AndroidJUnit4::class)
class CookieAttributeMatchingTest {

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var cookieManager: CookieManager

    private companion object {
        // Neutral hosts only — no infra/trek hostnames committed.
        const val HTTPS_ORIGIN = "https://example.com/"
        const val HTTP_ORIGIN = "http://example.com/"
        const val OTHER_HOST = "https://other.example/"
        const val SUBDOMAIN = "https://sub.example.com/"
        const val MAX_AGE = "Max-Age=3600"
    }

    // Unique suffix per instance so cookie names never collide with leftovers from other tests/runs.
    private val n = System.nanoTime().toString()

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
        }
    }

    @After
    fun tearDown() {
        // Best-effort cleanup — never fail the suite on teardown.
        try {
            instrumentation.runOnMainSync {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }
        } catch (_: Exception) {
            // Ignore — cleanup is best-effort only.
        }
    }

    /**
     * 1. A Secure cookie set on an https origin is returned by getCookie() over https.
     *
     * Baseline: the Secure flag must NOT, by itself, hide the cookie from the secure origin it
     * belongs to. If this fails, Secure cookies are unreadable even over https and TREK auth would
     * never be sent at all.
     */
    @Test
    fun secureCookie_isReturnedOverHttps() {
        val name = "sec_$n"
        setCookie(HTTPS_ORIGIN, "$name=v; $MAX_AGE; Secure")

        val names = getCookieNames(HTTPS_ORIGIN)
        assertTrue(
            "Secure cookie '$name' was NOT returned over https. Got names: $names. " +
                "A Secure auth cookie must be readable on its own https origin.",
            names.contains(name)
        )
    }

    /**
     * 2. A Secure cookie is NOT returned over http.
     *
     * The Secure gate: a Secure cookie is withheld from an insecure (http) request. If TREK's
     * WebView ever navigates to an http URL (redirect, misconfigured server URL, downgraded link),
     * the Secure auth cookie is dropped from the request and the user appears logged out even though
     * the cookie is still on disk.
     */
    @Test
    fun secureCookie_isNotReturnedOverHttp() {
        val name = "sec_$n"
        setCookie(HTTPS_ORIGIN, "$name=v; $MAX_AGE; Secure")

        val names = getCookieNames(HTTP_ORIGIN)
        assertFalse(
            "Secure cookie '$name' WAS returned over http. Got names: $names. " +
                "A Secure cookie must be withheld from insecure http requests.",
            names.contains(name)
        )
    }

    /**
     * 3. A host-only cookie (no Domain attribute) is returned for the exact host it was set on.
     */
    @Test
    fun hostOnlyCookie_matchesExactHost() {
        val name = "host_$n"
        // No Domain attribute => host-only cookie, scoped to example.com exactly.
        setCookie(HTTPS_ORIGIN, "$name=v; $MAX_AGE")

        val names = getCookieNames(HTTPS_ORIGIN)
        assertTrue(
            "Host-only cookie '$name' was NOT returned for its exact host. Got names: $names. " +
                "A host-only cookie must be readable on the host that set it.",
            names.contains(name)
        )
    }

    /**
     * 4. A host-only cookie is NOT returned for a different host.
     *
     * Host scoping: a cookie set on example.com must not appear for other.example. If the WebView's
     * configured server URL host differs (even slightly) from the host that set the auth cookie, the
     * cookie is correctly withheld — an on-disk-but-not-sent cause rooted in host mismatch.
     */
    @Test
    fun hostOnlyCookie_notReturnedForDifferentHost() {
        val name = "host_$n"
        setCookie(HTTPS_ORIGIN, "$name=v; $MAX_AGE")

        val names = getCookieNames(OTHER_HOST)
        assertFalse(
            "Host-only cookie '$name' WAS returned for a different host. Got names: $names. " +
                "A host-only cookie must not leak to unrelated hosts.",
            names.contains(name)
        )
    }

    /**
     * 5. A Domain cookie (Domain=example.com) is returned for a subdomain (sub.example.com).
     *
     * Domain scoping: a cookie with an explicit Domain attribute applies to that domain AND its
     * subdomains. This characterizes actual behavior: if TREK sets a Domain cookie on the apex but
     * the WebView loads a subdomain, the cookie SHOULD still be sent. A failure here would point to
     * a domain-scope mismatch as the on-disk-but-not-sent cause.
     */
    @Test
    fun domainCookie_matchesSubdomain() {
        val name = "dom_$n"
        setCookie(HTTPS_ORIGIN, "$name=v; Domain=example.com; $MAX_AGE")

        val names = getCookieNames(SUBDOMAIN)
        assertTrue(
            "Domain cookie '$name' (Domain=example.com) was NOT returned for subdomain " +
                "$SUBDOMAIN. Got names: $names. " +
                "A Domain cookie should apply to subdomains; absence indicates a domain-scope " +
                "mismatch withholding the auth cookie.",
            names.contains(name)
        )
    }

    /**
     * 6. A path-scoped cookie (Path=/app) is returned inside its path subtree but NOT outside it.
     *
     * Path scoping: a cookie with Path=/app is attached to /app and /app/* requests, but withheld
     * from /other. If TREK pins the auth cookie to a specific path and the WebView loads a URL
     * outside that path, the cookie is on disk but not sent — a path-mismatch root cause.
     */
    @Test
    fun pathScopedCookie_notReturnedOutsidePath() {
        val name = "pth_$n"
        setCookie("https://example.com/app", "$name=v; Path=/app; $MAX_AGE")

        val insideNames = getCookieNames("https://example.com/app/x")
        assertTrue(
            "Path-scoped cookie '$name' (Path=/app) was NOT returned inside its path " +
                "(/app/x). Got names: $insideNames. " +
                "A path-scoped cookie must be readable within its own path subtree.",
            insideNames.contains(name)
        )

        val outsideNames = getCookieNames("https://example.com/other")
        assertFalse(
            "Path-scoped cookie '$name' (Path=/app) WAS returned outside its path " +
                "(/other). Got names: $outsideNames. " +
                "A path-scoped cookie must be withheld outside its path subtree.",
            outsideNames.contains(name)
        )
    }

    /**
     * 7. An expired cookie (Max-Age=0) is NOT returned.
     *
     * TTL hypothesis: a cookie whose Max-Age/Expires has passed is dropped from getCookie() even if
     * a stale row briefly remains on disk. If TREK's auth cookie carries a short or already-elapsed
     * expiry, it is correctly withheld and the user is re-prompted to log in.
     */
    @Test
    fun expiredCookie_isNotReturned() {
        val name = "exp_$n"
        // Max-Age=0 expires the cookie immediately.
        setCookie(HTTPS_ORIGIN, "$name=v; Max-Age=0")

        val names = getCookieNames(HTTPS_ORIGIN)
        assertFalse(
            "Expired cookie '$name' (Max-Age=0) WAS returned. Got names: $names. " +
                "An expired cookie must not be returned by getCookie().",
            names.contains(name)
        )
    }

    /**
     * 8. A session cookie and a persistent cookie on the same origin are BOTH returned in memory.
     *
     * Characterization: the difference between a session cookie and a persistent cookie is ONLY
     * about persistence across process death — NOT about retrievability while the cookie is in
     * memory. getCookie() returns both. This isolates the persistence axis from the
     * attribute-matching axis: if both are returned here, then any "missing in-memory" symptom is
     * NOT an attribute/expiry problem but the known flush/persistence gap.
     */
    @Test
    fun persistentVsSession_bothReturnedInMemory() {
        val sessionName = "sess_$n"
        val persistentName = "persist_$n"
        // No Max-Age/Expires => session cookie (in-memory only).
        setCookie(HTTPS_ORIGIN, "$sessionName=v")
        // Max-Age set => persistent cookie.
        setCookie(HTTPS_ORIGIN, "$persistentName=v; $MAX_AGE")

        val names = getCookieNames(HTTPS_ORIGIN)
        assertTrue(
            "Session cookie '$sessionName' was NOT returned in memory. Got names: $names. " +
                "While in memory, a session cookie is just as retrievable as a persistent one.",
            names.contains(sessionName)
        )
        assertTrue(
            "Persistent cookie '$persistentName' was NOT returned in memory. Got names: $names. " +
                "A persistent cookie must be retrievable in memory alongside the session cookie.",
            names.contains(persistentName)
        )
    }

    /**
     * Sets a cookie on the main thread (CookieManager requires a Looper) and blocks until done.
     * runOnMainSync returns after the posted Runnable completes; setCookie's synchronous store write
     * is sufficient for a subsequent getCookie() on the same thread to observe it.
     */
    private fun setCookie(url: String, cookie: String) {
        instrumentation.runOnMainSync {
            cookieManager.setCookie(url, cookie)
        }
    }

    /**
     * Reads CookieManager.getCookie(url) on the main thread and parses the
     * "name=value; name2=value2" string into the list of NAMES only.
     *
     * Values are deliberately discarded — we assert on names so no cookie value can ever appear in
     * a test log or failure message. Returns an empty list when getCookie returns null/blank.
     */
    private fun getCookieNames(url: String): List<String> {
        var raw: String? = null
        instrumentation.runOnMainSync {
            raw = cookieManager.getCookie(url)
        }
        val cookieString = raw ?: return emptyList()
        return cookieString
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
    }
}
