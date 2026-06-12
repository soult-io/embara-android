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
 * REGRESSION GUARD for the on-PAGE-LOAD WebView auth-persistence fix.
 *
 * SIBLING TEST: CookieFlushPersistenceTest guards the onPause() flush path. THIS test guards a
 * SEPARATE, stronger guarantee: the cookie must reach disk on PAGE LOAD, WITHOUT relying on onPause.
 *
 * THE BUG THIS GUARDS (reproduced live): TREK auth is a PERSISTENT cookie `trek_session`. The WebView
 * holds cookies in memory; only CookieManager.getInstance().flush() writes them to the on-disk
 * Chromium cookie store at <app dataDir>/app_webview/Default/Cookies. Today flush() runs ONLY in
 * MainActivity.onPause(). A process death that skips onPause therefore loses the not-yet-flushed
 * cookie, forcing a re-login.
 *
 * THE FIX: MainActivity's WebViewClient.onPageFinished must also call
 * CookieManager.getInstance().flush(), so the cookie is persisted right after the login page loads —
 * with no dependence on onPause ever running.
 *
 * STRATEGY:
 *  1. Point EmbaraPrefs at a neutral loadable origin so MainActivity takes the WebView path and the
 *     WebView actually loads a page (onPageFinished fires even if the network errors, which is fine).
 *  2. Seed a PERSISTENT `trek_session` cookie on that origin via CookieManager. We deliberately do
 *     NOT call flush() ourselves — proving onPageFinished does.
 *  3. Launch MainActivity and move ONLY to RESUMED. We never move to CREATED/STOPPED and never
 *     trigger onPause — the whole point is proving persistence happens on page load, not on pause.
 *  4. Assert the cookie reached the ON-DISK store (name LIKE 'trek_session%' AND is_persistent=1).
 *
 * All CookieManager work runs on the MAIN thread (it requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class CookieFlushOnPageLoadTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        // Neutral, loadable origin. The cookie DOMAIN is irrelevant to the flush mechanism this test
        // guards; example.com is reserved/loadable and we avoid hardcoding infra/trek hostnames.
        const val TEST_ORIGIN = "https://example.com"
        const val COOKIE_NAME = "trek_session"
        const val AWAIT_SECONDS = 10L
        // onPageFinished can lag the launch (page load + network) — poll generously.
        const val DISK_POLL_TIMEOUT_MS = 15_000L
        const val DISK_POLL_INTERVAL_MS = 250L
    }

    // Unique value per run so we never collide with a leftover/real cookie on disk.
    private val cookieValue = "regload_" + System.nanoTime()

    @Before
    fun setUp() {
        // MainActivity reads EmbaraPrefs.server_url; set it so onCreate shows the WebView path and the
        // WebView loads a page -> onPageFinished fires.
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)

        // Seed a PERSISTENT cookie in CookieManager memory. NO flush() here on purpose.
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
        // Best-effort cleanup of the seeded test cookie and the prefs override. Never fail in teardown.
        try {
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(
                    "$TEST_ORIGIN/",
                    "$COOKIE_NAME=; Max-Age=0; Path=/; Secure"
                ) { cleared.countDown() }
            }
            cleared.await(AWAIT_SECONDS, TimeUnit.SECONDS)
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
     * Loading the login page in MainActivity's WebView must flush the PERSISTENT auth cookie to the
     * on-disk Chromium cookie store — WITHOUT onPause ever running.
     *
     * We launch and stay at RESUMED (no CREATED/STOPPED, no onPause). If the on-disk row is missing,
     * MainActivity's WebViewClient.onPageFinished did not call CookieManager.getInstance().flush() —
     * meaning a process death that skips onPause loses the TREK session and forces a re-login.
     */
    @Test
    fun pageLoad_flushesPersistentCookieToDisk_withoutOnPause() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // RESUMED only. Deliberately never move to CREATED/STOPPED and never trigger onPause:
            // we are proving persistence happens on page load, not on pause.
            scenario.moveToState(Lifecycle.State.RESUMED)

            val onDisk = pollForPersistentCookieOnDisk()

            assertTrue(
                "Persistent '$COOKIE_NAME' cookie was NOT flushed to disk on page load " +
                    "(is_persistent=1) — and onPause was never triggered in this test. " +
                    "MainActivity's WebViewClient.onPageFinished must call " +
                    "CookieManager.getInstance().flush(); without it, auth is lost on any process " +
                    "death that skips onPause, forcing users to re-login. " +
                    "Checked: ${cookieStoreFile().absolutePath}",
                onDisk
            )
        }
    }

    /** Path to the Chromium on-disk cookie store for this app's WebView. */
    private fun cookieStoreFile(): File =
        File(targetContext.dataDir, "app_webview/Default/Cookies")

    /**
     * Polls the on-disk cookie store for a persistent row named like [COOKIE_NAME], up to
     * [DISK_POLL_TIMEOUT_MS]. Returns true as soon as the row appears.
     */
    private fun pollForPersistentCookieOnDisk(): Boolean {
        val deadline = System.currentTimeMillis() + DISK_POLL_TIMEOUT_MS
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

        val tempDir = File(targetContext.cacheDir, "cookie_pageload_regtest").apply { mkdirs() }
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
