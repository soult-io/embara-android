package eu.stabpablo.trek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    // -- normalize --

    @Test
    fun `normalize adds https when no scheme`() {
        assertEquals("https://trek.example.com", UrlValidator.normalize("trek.example.com"))
    }

    @Test
    fun `normalize preserves https scheme`() {
        assertEquals("https://trek.example.com", UrlValidator.normalize("https://trek.example.com"))
    }

    @Test
    fun `normalize preserves http scheme`() {
        assertEquals("http://192.168.1.100:3000", UrlValidator.normalize("http://192.168.1.100:3000"))
    }

    @Test
    fun `normalize strips trailing slash`() {
        assertEquals("https://trek.example.com", UrlValidator.normalize("https://trek.example.com/"))
    }

    @Test
    fun `normalize strips multiple trailing slashes`() {
        assertEquals("https://trek.example.com", UrlValidator.normalize("trek.example.com///"))
    }

    @Test
    fun `normalize trims whitespace`() {
        assertEquals("https://trek.example.com", UrlValidator.normalize("  trek.example.com  "))
    }

    @Test
    fun `normalize handles IP with port`() {
        assertEquals("https://10.0.0.5:8910", UrlValidator.normalize("10.0.0.5:8910"))
    }

    @Test
    fun `normalize handles subdomain`() {
        assertEquals("https://trek.stabpablo.eu", UrlValidator.normalize("trek.stabpablo.eu"))
    }

    // -- isEmpty --

    @Test
    fun `isEmpty returns true for null`() {
        assertTrue(UrlValidator.isEmpty(null))
    }

    @Test
    fun `isEmpty returns true for empty string`() {
        assertTrue(UrlValidator.isEmpty(""))
    }

    @Test
    fun `isEmpty returns true for whitespace only`() {
        assertTrue(UrlValidator.isEmpty("   "))
    }

    @Test
    fun `isEmpty returns false for valid input`() {
        assertFalse(UrlValidator.isEmpty("trek.example.com"))
    }
}
