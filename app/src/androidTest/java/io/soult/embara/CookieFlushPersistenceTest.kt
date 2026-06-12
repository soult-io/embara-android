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
 * REGRESSION GUARD for the WebView auth-persistence fix.
 *
 * Embara is a WebView wrapper. The TREK backend authenticates by setting a PERSISTENT cookie named
 * `trek_session` (is_persistent=1, has_expires=1, ~24h expiry) on the TREK backend host — it
 * is NOT a session cookie and NOT sessionStorage. The WebView holds cookies in memory; only
 * CookieManager.getInstance().flush() writes persistent cookies to the on-disk Chromium cookie
 * store at <app dataDir>/app_webview/Default/Cookies (a SQLite DB with a `cookies` table whose
 * columns include host_key, name, is_persistent, has_expires, expires_utc).
 *
 * MainActivity.onPause() performs that flush:
 *     super.onPause(); webView.onPause(); CookieManager.getInstance().flush()
 *
 * THE REGRESSION THIS GUARDS: if someone removes/breaks the CookieManager.getInstance().flush()
 * call in MainActivity.onPause(), persistent cookies pending in WebView memory stop being written
 * to disk. They can then be lost when the app process is killed, forcing the user to re-login.
 *
 * STRATEGY:
 *  1. Seed a PERSISTENT `trek_session` cookie on a neutral test origin via CookieManager BEFORE
 *     launching the activity. We deliberately do NOT call flush() ourselves — proving onPause does.
 *     (The cookie domain is irrelevant to the flush mechanism, so a placeholder origin is used.)
 *  2. Point EmbaraPrefs at that test server so MainActivity takes the WebView path (not
 *     SetupActivity), launch it, then drive it through onPause deterministically with
 *     moveToState(CREATED) (which passes through onPause/onStop).
 *  3. Assert the cookie reached the ON-DISK store: a row with name LIKE 'trek_session%' AND
 *     is_persistent=1 exists in the Cookies SQLite DB.
 *
 * DETERMINISM NOTE: Chromium may also auto-flush on a timer, so disk presence is not exclusively
 * caused by onPause. To keep this test meaningfully tied to onPause we read the disk PROMPTLY after
 * moveToState(CREATED). If the row is absent at that point, the flush did not happen — we fail with
 * a message naming MainActivity.onPause() / CookieManager.flush(). We intentionally do NOT add a
 * long sleep that would let a background timer flush mask a removed onPause flush().
 *
 * All CookieManager work runs on the MAIN thread (it requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class CookieFlushPersistenceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        // Neutral placeholder origin. The cookie DOMAIN is irrelevant to the flush mechanism this
        // test guards (onPause -> CookieManager.flush() -> on-disk store), so any valid https origin
        // works. We deliberately avoid hardcoding infra/trek hostnames in committed tests.
        const val TEST_ORIGIN = "https://example.com"
        const val COOKIE_NAME = "trek_session"
        const val AWAIT_SECONDS = 10L
        // Short settle after moveToState(CREATED) for the onPause flush to land on disk.
        // Deliberately small so a background auto-flush does NOT mask a removed onPause flush().
        const val SETTLE_MS = 1_500L
        const val DISK_POLL_INTERVAL_MS = 100L
    }

    // Unique value per run so we never collide with a leftover/real cookie on disk.
    private val cookieValue = "regtest_" + System.nanoTime()

    @Before
    fun setUp() {
        // MainActivity reads EmbaraPrefs.server_url; set it so onCreate shows the WebView path.
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)

        // Seed a PERSISTENT cookie in CookieManager memory. NO flush() here on purpose.
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setAcceptCookie(true)
        }
        val set = CountDownLatch(1)
        instrumentation.runOnMainSync {
            // Max-Age makes this a PERSISTENT cookie (is_persistent=1, has_expires=1).
            CookieManager.getInstance().setCookie(
                TEST_ORIGIN,
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
        // Best-effort cleanup of the seeded test cookie and the prefs override.
        try {
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                // Expire the cookie, then flush so the on-disk store reflects the removal.
                CookieManager.getInstance().setCookie(
                    TEST_ORIGIN,
                    "$COOKIE_NAME=; Max-Age=0; Path=/; Secure"
                ) { cleared.countDown() }
            }
            cleared.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { CookieManager.getInstance().flush() }
        } catch (_: Exception) {
            // Best-effort only — never fail the suite on cleanup.
        }
        EmbaraPrefs.clearServerUrl(targetContext)
    }

    /**
     * Launching MainActivity and driving it through onPause must flush the PERSISTENT auth cookie
     * to the on-disk Chromium cookie store.
     *
     * If the on-disk row is missing, MainActivity.onPause() did not call
     * CookieManager.getInstance().flush() — the auth-persistence fix has regressed and the
     * re-login-on-cold-start bug is back.
     */
    @Test
    fun onPause_flushesPersistentTrekSessionCookieToDisk() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            // Drive through onPause/onStop. MainActivity.onPause() calls CookieManager.flush().
            scenario.moveToState(Lifecycle.State.CREATED)

            // Read the disk promptly. Poll briefly (filesystem write can lag the flush call by a
            // few ms) but keep the window short so a background auto-flush can't mask a removed
            // onPause flush().
            val onDisk = pollForPersistentCookieOnDisk()

            assertTrue(
                "Persistent '$COOKIE_NAME' cookie was NOT found on disk (is_persistent=1) after " +
                    "MainActivity moved through onPause. This means " +
                    "MainActivity.onPause() did not call CookieManager.getInstance().flush() — " +
                    "the WebView auth-persistence fix has regressed and TREK sessions will be lost " +
                    "on process death, forcing users to re-login. " +
                    "Checked: ${cookieStoreFile().absolutePath}",
                onDisk
            )
        }
    }

    /** Path to the Chromium on-disk cookie store for this app's WebView. */
    private fun cookieStoreFile(): File =
        File(targetContext.dataDir, "app_webview/Default/Cookies")

    /**
     * Polls the on-disk cookie store for a persistent row named like [COOKIE_NAME], up to a short
     * settle window. Returns true as soon as the row appears.
     */
    private fun pollForPersistentCookieOnDisk(): Boolean {
        val deadline = System.currentTimeMillis() + SETTLE_MS
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

        val tempDir = File(targetContext.cacheDir, "cookie_regtest").apply { mkdirs() }
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
