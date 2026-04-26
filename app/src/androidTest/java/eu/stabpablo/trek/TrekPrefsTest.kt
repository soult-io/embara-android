package eu.stabpablo.trek

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrekPrefsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        TrekPrefs.clearServerUrl(context)
    }

    @Test
    fun getServerUrl_returnsNull_whenNotSet() {
        TrekPrefs.clearServerUrl(context)
        assertNull(TrekPrefs.getServerUrl(context))
    }

    @Test
    fun setServerUrl_thenGet_returnsUrl() {
        TrekPrefs.setServerUrl(context, "https://trek.example.com")
        assertEquals("https://trek.example.com", TrekPrefs.getServerUrl(context))
    }

    @Test
    fun clearServerUrl_removesStoredUrl() {
        TrekPrefs.setServerUrl(context, "https://trek.example.com")
        TrekPrefs.clearServerUrl(context)
        assertNull(TrekPrefs.getServerUrl(context))
    }

    @Test
    fun setServerUrl_overwrites_previousUrl() {
        TrekPrefs.setServerUrl(context, "https://old.example.com")
        TrekPrefs.setServerUrl(context, "https://new.example.com")
        assertEquals("https://new.example.com", TrekPrefs.getServerUrl(context))
    }
}
