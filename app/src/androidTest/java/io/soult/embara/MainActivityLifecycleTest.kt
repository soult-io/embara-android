package io.soult.embara

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 lifecycle tests for MainActivity.
 * Covers the normal path (URL configured) and edge cases
 * that caused production crashes.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testUrl = "https://trek.example.com"

    @Before
    fun setup() {
        EmbaraPrefs.setServerUrl(context, testUrl)
    }

    @After
    fun cleanup() {
        EmbaraPrefs.clearServerUrl(context)
    }

    @Test
    fun mainActivity_withServerUrl_launchesWithoutCrash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.close()
    }

    @Test
    fun mainActivity_withWebView_destroyDoesNotCrash() {
        // Both production crashes were in onDestroy with initialized WebView.
        // This tests the normal destroy path (not the early-return path).
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.close()
    }

    @Test
    fun mainActivity_recreate_doesNotCrash() {
        // Tests config change (rotation) — onSaveInstanceState + onCreate with bundle
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.recreate()
        scenario.close()
    }

    @Test
    fun mainActivity_pauseResume_doesNotCrash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.moveToState(Lifecycle.State.STARTED)  // triggers onPause
        scenario.moveToState(Lifecycle.State.RESUMED)   // triggers onResume
        scenario.close()
    }

    @Test
    fun mainActivity_noUrl_saveInstanceState_doesNotCrash() {
        // Process death during redirect: onCreate returns early, but system may
        // trigger onSaveInstanceState before onDestroy
        EmbaraPrefs.clearServerUrl(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.close()
    }
}
