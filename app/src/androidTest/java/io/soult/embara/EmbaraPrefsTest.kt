package io.soult.embara

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbaraPrefsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        EmbaraPrefs.clearServerUrl(context)
    }

    @Test
    fun getServerUrl_returnsNull_whenNotSet() {
        EmbaraPrefs.clearServerUrl(context)
        assertNull(EmbaraPrefs.getServerUrl(context))
    }

    @Test
    fun setServerUrl_thenGet_returnsUrl() {
        EmbaraPrefs.setServerUrl(context, "https://trek.example.com")
        assertEquals("https://trek.example.com", EmbaraPrefs.getServerUrl(context))
    }

    @Test
    fun clearServerUrl_removesStoredUrl() {
        EmbaraPrefs.setServerUrl(context, "https://trek.example.com")
        EmbaraPrefs.clearServerUrl(context)
        assertNull(EmbaraPrefs.getServerUrl(context))
    }

    @Test
    fun setServerUrl_overwrites_previousUrl() {
        EmbaraPrefs.setServerUrl(context, "https://old.example.com")
        EmbaraPrefs.setServerUrl(context, "https://new.example.com")
        assertEquals("https://new.example.com", EmbaraPrefs.getServerUrl(context))
    }
}
