package io.soult.embara

import android.database.sqlite.SQLiteDatabase
import android.webkit.CookieManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * REGRESSION GUARD for the POST-LOGIN PERIODIC cookie-flush fix.
 *
 * Embara is a WebView wrapper. TREK authenticates via an XHR login that sets a PERSISTENT cookie
 * named `trek_session` (is_persistent=1, has_expires=1, ~24h expiry). Because login is an XHR,
 * WebViewClient.onPageFinished does NOT fire after it — so a flush keyed only on page-finished
 * (or only on onPause) leaves a window (~30s) where a process kill loses the in-memory cookie,
 * forcing a re-login.
 *
 * THE FIX THIS GUARDS: while the Activity is RESUMED, MainActivity periodically (every ~2s) calls
 * CookieManager.getInstance().flush(), and also flushes on WebViewClient.doUpdateVisitedHistory.
 * This persists the auth cookie to the on-disk Chromium store
 * (<app dataDir>/app_webview/Default/Cookies) shortly after the XHR login WITHOUT needing onPause.
 *
 * STRATEGY (distinct from CookieFlushPersistenceTest, which proves the onPause flush):
 *  1. Seed a PERSISTENT `trek_session` cookie on a neutral test origin via CookieManager BEFORE
 *     launching the activity. We deliberately do NOT call flush() ourselves — proving the periodic
 *     flush does. (The cookie domain is irrelevant to the flush mechanism.)
 *  2. Point EmbaraPrefs at that test server so MainActivity takes the WebView path (not
 *     SetupActivity), launch it, and moveToState(RESUMED). We DELIBERATELY never move to
 *     CREATED/STOPPED and never trigger onPause — the whole point is to prove the PERIODIC flush
 *     persists the cookie while the Activity is STILL RESUMED.
 *  3. Poll the on-disk store for up to ~8s (longer than the ~2s flush interval) for a persistent
 *     `trek_session` row. Assert it appears.
 *
 * On code WITHOUT the periodic flush this FAILS (no onPause happens to write the cookie); once the
 * periodic-flush fix lands it PASSES.
 *
 * All CookieManager work runs on the MAIN thread (it requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class PeriodicFlushPersistenceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        // Neutral placeholder origin. The cookie DOMAIN is irrelevant to the flush mechanism this
        // test guards, so any valid loadable https origin works. We deliberately avoid hardcoding
        // infra/trek hostnames in committed tests.
        const val TEST_ORIGIN = "https://example.com"
        const val COOKIE_NAME = "trek_session"
        const val AWAIT_SECONDS = 10L
        // The periodic flush interval is ~2s. We poll the disk for noticeably longer so a single
        // missed tick can't flake the test, but a code path with NO periodic flush still fails.
        const val DISK_POLL_WINDOW_MS = 8_000L
        const val DISK_POLL_INTERVAL_MS = 200L
    }

    // Unique value per run so we never collide with a leftover/real cookie on disk.
    private val cookieValue = "periodic_" + System.nanoTime()

    @Before
    fun setUp() {
        // MainActivity reads EmbaraPrefs.server_url; set it so onCreate shows the WebView path.
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)

        // Seed a PERSISTENT cookie in CookieManager memory. NO flush() here on purpose — the
        // periodic flush under test is what must write it to disk.
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setAcceptCookie(true)
        }
        val set = CountDownLatch(1)
        instrumentation.runOnMainSync {
            // Max-Age makes this a PERSISTENT cookie (is_persistent=1, has_expires=1).
            CookieManager.getInstance().setCookie(
                "$TEST_ORIGIN/",
                "$COOKIE_NAME=$cookieValue; Max-Age=86400; Path=/; Secure"
            ) { set.countDown() }
        }
        assertTrue(
            "setCookie callback timed out — could not seed the persistent $COOKIE_NAME cookie",
            set.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )
    }

    @After
    fun tearDown() {
        // Best-effort cleanup of the seeded test cookie and the prefs override. Never fail teardown.
        try {
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                // Expire the cookie, then flush so the on-disk store reflects the removal.
                CookieManager.getInstance().setCookie(
                    "$TEST_ORIGIN/",
                    "$COOKIE_NAME=; Max-Age=0; Path=/; Secure"
                ) { cleared.countDown() }
            }
            cleared.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { CookieManager.getInstance().flush() }
        } catch (_: Exception) {
            // Best-effort only — never fail the suite on cleanup.
        }
        try {
            EmbaraPrefs.clearServerUrl(targetContext)
        } catch (_: Exception) {
            // Best-effort only.
        }
    }

    /**
     * While MainActivity is RESUMED (and WITHOUT ever being paused/stopped), the periodic flush must
     * write the PERSISTENT auth cookie to the on-disk Chromium cookie store within the flush
     * interval.
     *
     * This models the TREK XHR-login case: onPageFinished does NOT fire after an XHR login, so the
     * cookie must be persisted by the resumed-activity periodic flush (and/or doUpdateVisitedHistory)
     * — NOT by onPause — to survive a process kill before the user backgrounds the app.
     */
    @Test
    fun resumedActivity_periodicallyFlushesCookieToDisk_withoutOnPause() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // RESUMED only. We intentionally do NOT moveToState(CREATED/STOPPED) and do NOT trigger
            // onPause: the cookie reaching disk here must be caused by the PERIODIC flush.
            scenario.moveToState(Lifecycle.State.RESUMED)

            val onDisk = pollForPersistentCookieOnDisk()

            assertTrue(
                "the resumed-activity periodic flush did not persist the cookie within the " +
                    "interval — MainActivity must flush cookies periodically while RESUMED (and/or " +
                    "on doUpdateVisitedHistory) so an XHR-login cookie survives a process kill " +
                    "before onPause. Checked: ${cookieStoreFile().absolutePath}",
                onDisk
            )
        }
    }

    /** Path to the Chromium on-disk cookie store for this app's WebView. */
    private fun cookieStoreFile(): File =
        File(targetContext.dataDir, "app_webview/Default/Cookies")

    /**
     * Polls the on-disk cookie store for a persistent row named like [COOKIE_NAME], for up to
     * [DISK_POLL_WINDOW_MS] (longer than the ~2s flush interval). Returns true as soon as the row
     * appears.
     */
    private fun pollForPersistentCookieOnDisk(): Boolean {
        val deadline = System.currentTimeMillis() + DISK_POLL_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            if (persistentCookieRowExists()) return true
            Thread.sleep(DISK_POLL_INTERVAL_MS)
        }
        // One final check after the window.
        return persistentCookieRowExists()
    }

    /**
     * Opens a READ-ONLY copy of the Cookies SQLite DB and checks for a persistent row whose name
     * starts with [COOKIE_NAME].
     *
     * We COPY the DB file (plus any -wal/-journal sidecars) to a temp location first, because the
     * live WebView holds a WAL lock on the original; opening the copy read-only avoids that lock.
     */
    private fun persistentCookieRowExists(): Boolean {
        val source = cookieStoreFile()
        if (!source.exists()) return false

        val tempDir = File(targetContext.cacheDir, "cookie_periodic_regtest").apply { mkdirs() }
        val copy = File(tempDir, "Cookies_copy_${System.nanoTime()}")
        return try {
            source.copyTo(copy, overwrite = true)
            // Copy WAL/journal sidecars if present so the copy is internally consistent.
            File(source.parentFile, "${source.name}-wal").takeIf { it.exists() }
                ?.copyTo(File(tempDir, "${copy.name}-wal"), overwrite = true)
            File(source.parentFile, "${source.name}-journal").takeIf { it.exists() }
                ?.copyTo(File(tempDir, "${copy.name}-journal"), overwrite = true)

            queryPersistentCount(copy) >= 1
        } catch (_: Exception) {
            // If the copy/read transiently fails, treat as "not yet present" and let polling retry.
            false
        } finally {
            copy.delete()
            File(tempDir, "${copy.name}-wal").delete()
            File(tempDir, "${copy.name}-journal").delete()
        }
    }

    /** Returns the count of persistent cookie rows named like [COOKIE_NAME] in [dbFile]. */
    private fun queryPersistentCount(dbFile: File): Long {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            db.rawQuery(
                "SELECT count(*) FROM cookies WHERE name LIKE ? AND is_persistent = 1",
                arrayOf("$COOKIE_NAME%")
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (_: Exception) {
            // A locked/partial copy reads as 0; polling will retry against a fresh copy.
            0L
        } finally {
            db?.close()
        }
    }
}
