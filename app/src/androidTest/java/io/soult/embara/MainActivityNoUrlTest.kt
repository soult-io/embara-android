package io.soult.embara

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MainActivity doesn't crash when no server URL is configured.
 * Regression test for: lateinit property not initialized in onDestroy
 * when onCreate returns early (redirects to SetupActivity).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNoUrlTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearServerUrl() {
        EmbaraPrefs.clearServerUrl(context)
    }

    @After
    fun cleanup() {
        EmbaraPrefs.clearServerUrl(context)
    }

    @Test
    fun mainActivity_noServerUrl_doesNotCrashOnDestroy() {
        // Launch MainActivity with no server URL configured.
        // onCreate should redirect to SetupActivity and call finish().
        // onDestroy must not crash on uninitialized lateinit properties.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.close() // triggers onDestroy
    }
}
