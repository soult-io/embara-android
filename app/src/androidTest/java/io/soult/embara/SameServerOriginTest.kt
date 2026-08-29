package io.soult.embara

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UrlValidator.isSameServerOrigin depends on android.net.Uri, so it lives here rather than in the
 * JVM test (the same split as isValidScheme / UrlValidatorInstrumentedTest).
 *
 * This is the gate that decides whether a page may read the device's position and whether a
 * download is fetched with the user's session cookies, so the negative cases matter more than the
 * positive one.
 */
@RunWith(AndroidJUnit4::class)
class SameServerOriginTest {

    private val host = "trek.example.com"

    @Test
    fun acceptsTheServerItself() {
        assertTrue(UrlValidator.isSameServerOrigin(host, "https://trek.example.com"))
    }

    @Test
    fun acceptsASubdomain() {
        assertTrue(UrlValidator.isSameServerOrigin(host, "https://cdn.trek.example.com"))
    }

    @Test
    fun acceptsAnExplicitPort() {
        assertTrue(UrlValidator.isSameServerOrigin(host, "https://trek.example.com:8443"))
    }

    @Test
    fun acceptsPlainHttpForALanServer() {
        assertTrue(UrlValidator.isSameServerOrigin("192.168.1.100", "http://192.168.1.100:3000"))
    }

    @Test
    fun isCaseInsensitive() {
        assertTrue(UrlValidator.isSameServerOrigin("TREK.Example.COM", "https://Trek.Example.com"))
    }

    @Test
    fun rejectsADifferentHost() {
        assertFalse(UrlValidator.isSameServerOrigin(host, "https://evil.example.com"))
    }

    @Test
    fun rejectsASuffixThatIsNotASubdomain() {
        // "nottrek.example.com" ends with "trek.example.com" as a *string* but is a different host.
        assertFalse(UrlValidator.isSameServerOrigin(host, "https://nottrek.example.com"))
    }

    @Test
    fun rejectsTheServerHostAsASubdomainOfSomethingElse() {
        assertFalse(UrlValidator.isSameServerOrigin(host, "https://trek.example.com.evil.test"))
    }

    @Test
    fun rejectsANonWebScheme() {
        assertFalse(UrlValidator.isSameServerOrigin(host, "file://trek.example.com"))
        assertFalse(UrlValidator.isSameServerOrigin(host, "javascript:alert(1)"))
        assertFalse(UrlValidator.isSameServerOrigin(host, "content://trek.example.com/x"))
    }

    @Test
    fun rejectsBlankInput() {
        assertFalse(UrlValidator.isSameServerOrigin(host, null))
        assertFalse(UrlValidator.isSameServerOrigin(host, ""))
        assertFalse(UrlValidator.isSameServerOrigin("", "https://trek.example.com"))
    }
}
