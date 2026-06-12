package io.soult.embara

import android.database.sqlite.SQLiteDatabase
import android.webkit.CookieManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CHARACTERIZATION TESTS for WebView cookie -> on-disk Chromium cookie store timing.
 *
 * Embara is a WebView wrapper. The TREK backend authenticates with a PERSISTENT cookie
 * (`trek_session`, is_persistent=1, has_expires=1). A persistent cookie survives process death
 * ONLY if it has been written to the on-disk Chromium store at
 * <app dataDir>/app_webview/Default/Cookies. The WebView holds cookies in memory; only
 * CookieManager.getInstance().flush() (or certain lifecycle/page events) writes them to disk.
 *
 * These tests deliberately probe EXACTLY WHEN a cookie reaches disk across different flush
 * triggers (explicit flush, onPause, onStop, no-flush) and cookie types (persistent vs session).
 * They localize the auth-persistence bug: if a persistent cookie is NOT on disk at process death,
 * the user is forced to re-login on cold start.
 *
 * Each test seeds a UNIQUE cookie name so concurrent/leftover cookies never interfere.
 * The on-disk read approach is borrowed from CookieFlushPersistenceTest: copy the live Cookies DB
 * (plus -wal/-journal sidecars) to a temp file to dodge the WAL lock, then open OPEN_READONLY and
 * query the `cookies` table.
 *
 * All CookieManager work runs on the MAIN thread (it requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class CookieFlushScenariosTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        // Neutral placeholder origin. The cookie DOMAIN is irrelevant to the flush mechanism under
        // test, so any valid https origin works. We avoid hardcoding infra/trek hostnames.
        const val TEST_ORIGIN = "https://example.com"
        const val AWAIT_SECONDS = 10L
        // Disk presence poll window (~8s) and interval.
        const val DISK_POLL_MS = 8_000L
        const val DISK_POLL_INTERVAL_MS = 100L
        // Short window used by the "no eager flush" crux test: long enough to catch an immediate
        // flush, short enough that a background auto-flush timer is unlikely to mask absence.
        const val NO_FLUSH_WINDOW_MS = 1_000L
        const val MAX_AGE_PERSISTENT = "Max-Age=86400"
    }

    // Names of cookies seeded during a test, expired+flushed best-effort in tearDown.
    private val seededCookieNames = mutableListOf<String>()

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setAcceptCookie(true)
        }
    }

    @After
    fun tearDown() {
        // Best-effort: expire every seeded cookie (Max-Age=0) and flush so disk reflects removal.
        // Never fail teardown.
        try {
            for (name in seededCookieNames) {
                val cleared = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(
                        TEST_ORIGIN,
                        "$name=; Max-Age=0; Path=/; Secure"
                    ) { cleared.countDown() }
                }
                cleared.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            }
            instrumentation.runOnMainSync { CookieManager.getInstance().flush() }
        } catch (_: Exception) {
            // Best-effort only.
        }
        try {
            EmbaraPrefs.clearServerUrl(targetContext)
        } catch (_: Exception) {
            // Best-effort only.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test cases
    // ---------------------------------------------------------------------------------------------

    /**
     * BASELINE. A persistent cookie explicitly flushed via CookieManager.flush() must be on disk.
     * If this fails, the on-disk read mechanism or flush() itself is broken — nothing else is
     * meaningful until it passes.
     */
    @Test
    fun persistentCookie_afterExplicitFlush_isOnDisk() {
        val name = uniqueName("flush_a1")
        seedCookie(name, persistent = true)

        instrumentation.runOnMainSync { CookieManager.getInstance().flush() }

        assertTrue(
            "BASELINE FAILED: persistent cookie '$name' was not on disk after an explicit " +
                "CookieManager.getInstance().flush(). Either flush() is broken or the on-disk read " +
                "helper cannot see ${cookieStoreFile().absolutePath}.",
            cookieOnDisk(name, persistentOnly = true)
        )
    }

    /**
     * onPause must flush. Launch MainActivity (WebView path), drive through onPause via
     * moveToState(CREATED), and assert the persistent cookie reached disk. Guards
     * MainActivity.onPause() calling CookieManager.flush().
     */
    @Test
    fun persistentCookie_afterOnPause_isOnDisk() {
        val name = uniqueName("flush_pause")
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)
        seedCookie(name, persistent = true)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            // moveToState(CREATED) passes through onPause (and onStop).
            scenario.moveToState(Lifecycle.State.CREATED)

            assertTrue(
                "Persistent cookie '$name' was NOT on disk after MainActivity moved through " +
                    "onPause. MainActivity.onPause() likely no longer calls " +
                    "CookieManager.getInstance().flush() — the auth-persistence fix has regressed " +
                    "and TREK sessions will be lost on process death. Checked: " +
                    cookieStoreFile().absolutePath,
                cookieOnDisk(name, persistentOnly = true)
            )
        }
    }

    /**
     * onStop path. Same as above but framed around the STOPPED transition. ActivityScenario does
     * not expose a STOPPED state directly; moveToState(CREATED) drives the activity through
     * onPause -> onStop, so this asserts the cookie is on disk once the activity has stopped and
     * documents that, in practice, the flush is observed by the time onStop completes (the flush
     * is performed in onPause, which onStop is always preceded by).
     */
    @Test
    fun persistentCookie_afterOnStop_isOnDisk() {
        val name = uniqueName("flush_stop")
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)
        seedCookie(name, persistent = true)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            // CREATED is the lowest non-destroyed state; the activity passes through onStop to
            // reach it. We assert disk presence after the stop transition.
            scenario.moveToState(Lifecycle.State.CREATED)

            assertTrue(
                "Persistent cookie '$name' was NOT on disk after MainActivity reached the stopped " +
                    "state (onPause -> onStop). NOTE: the flush is performed in onPause; onStop alone " +
                    "does not add a separate flush in MainActivity. If this fails, the onPause flush " +
                    "is gone. Checked: " + cookieStoreFile().absolutePath,
                cookieOnDisk(name, persistentOnly = true)
            )
        }
    }

    /**
     * CRUX of the process-death gap. A resumed activity that is NEVER paused should not eagerly
     * flush a freshly-seeded persistent cookie. We seed, launch, move to RESUMED only (no pause),
     * and check disk PROMPTLY (~1s). We assert the cookie is NOT yet on disk: this documents that
     * nothing flushes eagerly, so a process kill while resumed loses the cookie.
     *
     * If the cookie IS already on disk, the build is exhibiting eager-flush behavior — the assert
     * fails with a clear message recording that observation (which would actually be GOOD for
     * auth persistence, but contradicts the documented gap and must be investigated).
     */
    @Test
    fun persistentCookie_resumedNoFlush_isNotReliablyOnDisk() {
        val name = uniqueName("flush_resumed")
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)
        seedCookie(name, persistent = true)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            // Do NOT pause. Check disk promptly within a short window — long enough to catch an
            // eager flush, short enough that a background auto-flush timer is unlikely to fire.
            val onDiskEarly = cookieOnDiskWithin(name, persistentOnly = true, windowMs = NO_FLUSH_WINDOW_MS)

            assertFalse(
                "OBSERVED EAGER FLUSH: persistent cookie '$name' reached disk within " +
                    "${NO_FLUSH_WINDOW_MS}ms while the activity was RESUMED and never paused. The " +
                    "documented process-death gap assumes NOTHING flushes eagerly (only onPause / " +
                    "page events do). If this is consistently reproducible the persistence model has " +
                    "changed and the gap analysis must be revisited. Checked: " +
                    cookieStoreFile().absolutePath,
                onDiskEarly
            )
        }
    }

    /**
     * Session cookies are never persisted. A cookie set WITHOUT Max-Age/Expires is a session
     * cookie (is_persistent=0). Even after an explicit flush(), it must NOT appear as a persistent
     * row on disk. Characterizes the "every-open session cookie" hypothesis: such cookies cannot
     * survive process death by design.
     */
    @Test
    fun sessionCookie_afterFlush_isNotOnDisk() {
        val name = uniqueName("flush_session")
        seedCookie(name, persistent = false)

        instrumentation.runOnMainSync { CookieManager.getInstance().flush() }

        // Give any (incorrect) persistence a chance to surface before asserting absence.
        val onDisk = cookieOnDisk(name, persistentOnly = true)
        assertFalse(
            "Session cookie '$name' (no Max-Age/Expires) appeared on disk as a PERSISTENT row " +
                "after flush(). Session cookies must NOT be persisted — if this shows up persistent, " +
                "either the cookie was miscategorized or the store is mislabeling persistence. " +
                "Checked: " + cookieStoreFile().absolutePath,
            onDisk
        )
    }

    /**
     * In-process restore proxy for process death. Seed + flush a persistent cookie, launch
     * MainActivity, recreate the activity (config-change style restart), and assert the cookie is
     * still readable via CookieManager.getCookie(). This proves the in-memory/on-disk cookie store
     * survives an activity recreate (a weaker proxy than full process death, but the strongest
     * ActivityScenario offers without a network).
     */
    @Test
    fun persistentCookie_survivesActivityRecreate() {
        val name = uniqueName("flush_recreate")
        EmbaraPrefs.setServerUrl(targetContext, TEST_ORIGIN)
        seedCookie(name, persistent = true)
        instrumentation.runOnMainSync { CookieManager.getInstance().flush() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.RESUMED)

            val cookieHeader = getCookieHeader(TEST_ORIGIN + "/")
            assertNotNull(
                "CookieManager.getCookie returned null after activity recreate — cookie store " +
                    "is empty for $TEST_ORIGIN.",
                cookieHeader
            )
            assertTrue(
                "Persistent cookie '$name' was NOT present in CookieManager.getCookie after " +
                    "MainActivity.recreate(). The cookie store did not survive the in-process " +
                    "restart. Observed header: $cookieHeader",
                cookieHeader != null && cookieHeader.contains(name)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Unique cookie name per test invocation so tests never interfere on disk or in memory. */
    private fun uniqueName(prefix: String): String = "${prefix}_${System.nanoTime()}"

    /**
     * Seeds a cookie in CookieManager memory on the given origin. Persistent cookies use Max-Age
     * (is_persistent=1); session cookies omit it. Registers the name for tearDown cleanup.
     * Does NOT flush — callers control when/if a flush happens.
     */
    private fun seedCookie(name: String, persistent: Boolean) {
        seededCookieNames += name
        val value = "scenario_${System.nanoTime()}"
        val maxAge = if (persistent) "; $MAX_AGE_PERSISTENT" else ""
        val set = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(
                TEST_ORIGIN,
                "$name=$value$maxAge; Path=/; Secure"
            ) { set.countDown() }
        }
        assertTrue(
            "setCookie callback timed out — could not seed cookie '$name' (persistent=$persistent).",
            set.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )
    }

    /** Reads the cookie header string for [url] on the main thread. */
    private fun getCookieHeader(url: String): String? {
        val holder = arrayOfNulls<String>(1)
        instrumentation.runOnMainSync {
            holder[0] = CookieManager.getInstance().getCookie(url)
        }
        return holder[0]
    }

    /** Path to the Chromium on-disk cookie store for this app's WebView. */
    private fun cookieStoreFile(): File =
        File(targetContext.dataDir, "app_webview/Default/Cookies")

    /**
     * Polls the on-disk cookie store for a row named [name], up to ~8s. When [persistentOnly] is
     * true the row must have is_persistent=1; otherwise any persistence value matches. Returns true
     * as soon as the row appears.
     */
    private fun cookieOnDisk(name: String, persistentOnly: Boolean = true): Boolean =
        cookieOnDiskWithin(name, persistentOnly, DISK_POLL_MS)

    /**
     * Like [cookieOnDisk] but with a caller-supplied poll [windowMs]. Used by the no-eager-flush
     * crux test which needs a short window.
     */
    private fun cookieOnDiskWithin(name: String, persistentOnly: Boolean, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (cookieRowExists(name, persistentOnly)) return true
            Thread.sleep(DISK_POLL_INTERVAL_MS)
        }
        // One final check after the window.
        return cookieRowExists(name, persistentOnly)
    }

    /**
     * Opens a READ-ONLY copy of the Cookies SQLite DB and checks for a row named [name].
     *
     * We COPY the DB file (plus any -wal/-journal sidecars) to a temp location first, because the
     * live WebView holds a WAL lock on the original; opening the copy read-only avoids that lock.
     */
    private fun cookieRowExists(name: String, persistentOnly: Boolean): Boolean {
        val source = cookieStoreFile()
        if (!source.exists()) return false

        val tempDir = File(targetContext.cacheDir, "cookie_scenarios").apply { mkdirs() }
        val copy = File(tempDir, "Cookies_copy_${System.nanoTime()}")
        return try {
            source.copyTo(copy, overwrite = true)
            // Copy WAL/journal sidecars if present so the copy is internally consistent.
            File(source.parentFile, "${source.name}-wal").takeIf { it.exists() }
                ?.copyTo(File(tempDir, "${copy.name}-wal"), overwrite = true)
            File(source.parentFile, "${source.name}-journal").takeIf { it.exists() }
                ?.copyTo(File(tempDir, "${copy.name}-journal"), overwrite = true)

            queryCount(copy, name, persistentOnly) >= 1
        } catch (_: Exception) {
            // If the copy/read transiently fails, treat as "not yet present" and let polling retry.
            false
        } finally {
            copy.delete()
            File(tempDir, "${copy.name}-wal").delete()
            File(tempDir, "${copy.name}-journal").delete()
        }
    }

    /**
     * Returns the count of rows named exactly [name] in [dbFile], optionally restricted to
     * persistent rows. Uses parameterized name + is_persistent per the spec.
     */
    private fun queryCount(dbFile: File, name: String, persistentOnly: Boolean): Long {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            val persistentFlag = if (persistentOnly) "1" else "0"
            // When persistentOnly is false we still want to detect the row regardless of
            // persistence, so widen the predicate accordingly.
            val (sql, args) = if (persistentOnly) {
                "SELECT count(*) FROM cookies WHERE name = ? AND is_persistent = ?" to
                    arrayOf(name, persistentFlag)
            } else {
                "SELECT count(*) FROM cookies WHERE name = ?" to arrayOf(name)
            }
            db.rawQuery(sql, args).use { cursor ->
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
