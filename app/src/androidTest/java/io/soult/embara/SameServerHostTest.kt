package io.soult.embara

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UrlValidator.isSameServerHost depends on android.net.Uri, so it lives here rather than in the
 * JVM test (the same split as isValidScheme / UrlValidatorInstrumentedTest).
 *
 * This is the gate that decides whether a page may read the device's position and whether a
 * download is fetched with the user's session cookies, so the negative cases matter more than the
 * positive one.
 */
@RunWith(AndroidJUnit4::class)
class SameServerHostTest {

    private val host = "trek.example.com"

    @Test
    fun acceptsTheServerItself() {
        assertTrue(UrlValidator.isSameServerHost(host, "https://trek.example.com"))
    }

    @Test
    fun acceptsASubdomain() {
        assertTrue(UrlValidator.isSameServerHost(host, "https://cdn.trek.example.com"))
    }

    /** Deliberate: this is a host check, matching MainActivity's host-only navigation gate. */
    @Test
    fun acceptsAnExplicitPort() {
        assertTrue(UrlValidator.isSameServerHost(host, "https://trek.example.com:8443"))
    }

    @Test
    fun acceptsPlainHttpForALanServer() {
        assertTrue(UrlValidator.isSameServerHost("192.168.1.100", "http://192.168.1.100:3000"))
    }

    @Test
    fun isCaseInsensitive() {
        assertTrue(UrlValidator.isSameServerHost("TREK.Example.COM", "https://Trek.Example.com"))
    }

    @Test
    fun rejectsADifferentHost() {
        assertFalse(UrlValidator.isSameServerHost(host, "https://evil.example.com"))
    }

    @Test
    fun rejectsASuffixThatIsNotASubdomain() {
        // "nottrek.example.com" ends with "trek.example.com" as a *string* but is a different host.
        assertFalse(UrlValidator.isSameServerHost(host, "https://nottrek.example.com"))
    }

    @Test
    fun rejectsTheServerHostAsASubdomainOfSomethingElse() {
        assertFalse(UrlValidator.isSameServerHost(host, "https://trek.example.com.evil.test"))
    }

    @Test
    fun rejectsANonWebScheme() {
        assertFalse(UrlValidator.isSameServerHost(host, "file://trek.example.com"))
        assertFalse(UrlValidator.isSameServerHost(host, "javascript:alert(1)"))
        assertFalse(UrlValidator.isSameServerHost(host, "content://trek.example.com/x"))
    }

    @Test
    fun rejectsBlankInput() {
        assertFalse(UrlValidator.isSameServerHost(host, null))
        assertFalse(UrlValidator.isSameServerHost(host, ""))
        assertFalse(UrlValidator.isSameServerHost("", "https://trek.example.com"))
    }
}
