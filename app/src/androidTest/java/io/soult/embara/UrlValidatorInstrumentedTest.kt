package io.soult.embara

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for UrlValidator methods that depend on android.net.Uri.
 */
@RunWith(AndroidJUnit4::class)
class UrlValidatorInstrumentedTest {

    @Test
    fun isValidScheme_acceptsHttps() {
        assertTrue(UrlValidator.isValidScheme("https://trek.example.com"))
    }

    @Test
    fun isValidScheme_rejectsHttp() {
        assertFalse(UrlValidator.isValidScheme("http://192.168.1.100:3000"))
    }

    @Test
    fun isValidScheme_rejectsFtp() {
        assertFalse(UrlValidator.isValidScheme("ftp://files.example.com"))
    }

    @Test
    fun isValidScheme_rejectsNoScheme() {
        assertFalse(UrlValidator.isValidScheme("trek.example.com"))
    }

    @Test
    fun isValidScheme_rejectsJavascript() {
        assertFalse(UrlValidator.isValidScheme("javascript:alert(1)"))
    }
}
