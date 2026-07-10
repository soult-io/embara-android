package io.soult.embara

import android.database.sqlite.SQLiteDatabase
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RESTORE + CLEAR side of WebView cookie persistence.
 *
 * Embara is a WebView wrapper. The TREK backend authenticates with a PERSISTENT cookie. The WebView
 * holds cookies in an in-memory CookieManager store; only CookieManager.getInstance().flush() writes
 * persistent cookies to the on-disk Chromium store at <app dataDir>/app_webview/Default/Cookies.
 *
 * SIBLING TESTS (do NOT duplicate them here):
 *  - CookieFlushPersistenceTest / CookieFlushOnPageLoadTest — the FLUSH-trigger family
 *    (onPause / onPageFinished -> flush -> on-disk store).
 *  - CookieFlushScenariosTest — additional flush-trigger scenarios (being authored separately).
 *  - CookieAttributeMatchingTest — cookie attribute / domain / path matching (being authored
 *    separately).
 *  - DomStoragePersistenceTest — localStorage vs sessionStorage persistence (see test #6 comment).
 *
 * THIS FILE focuses on the complementary concerns:
 *  - RESTORE / availability: a flushed persistent cookie is still readable (the "written to disk but
 *    not reloaded into the live store" failure family).
 *  - CROSS-WEBVIEW-INSTANCE: a persistent cookie survives destroying one WebView and creating a new
 *    one (CookieManager is process-global, a proxy for "a new-process WebView still sees it").
 *  - CLEAR / logout: removeAllCookies actually removes the cookie from memory and disk.
 *  - SESSION vs PERSISTENT clearing: removeSessionCookies drops session cookies but keeps persistent
 *    ones (characterizes what a process restart effectively does to non-persistent cookies).
 *  - IN-PAGE JS cookie set: a cookie written by document.cookie during a page load is stored and
 *    readable (the SPA-style, client-readable, non-HttpOnly path).
 *
 * All CookieManager / WebView work runs on the MAIN thread (it requires a Looper).
 */
@RunWith(AndroidJUnit4::class)
class CookieRestoreAndClearTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    private companion object {
        // Neutral placeholder origin. The cookie domain is irrelevant to the restore/clear mechanisms
        // under test, and we avoid hardcoding infra/trek hostnames in committed tests. example.com is
        // reserved and loadable.
        const val TEST_ORIGIN = "https://example.com"
        const val TEST_URL = "https://example.com/"
        const val AWAIT_SECONDS = 10L
        // Page load (network + onPageFinished) can lag; allow a generous wait for the JS-cookie test.
        const val PAGE_LOAD_AWAIT_SECONDS = 30L
    }

    // Unique suffix per run so we never collide with leftover/real cookies in the shared store.
    private val nonce = System.nanoTime().toString()

    // Names this test instance seeded into the process-global CookieManager, so tearDown can expire
    // exactly those by name instead of a global removeAllCookies. A global wipe in @After would clear
    // the shared E2E TREK login — the whole instrumented suite reuses ONE authenticated session (see
    // io.soult.embara.e2e.support.TrekE2E) and a forced re-login trips the live server's rate limit.
    // Like the sibling cookie tests, this scopes cleanup to its own cookies instead of a global wipe —
    // each in its own way: CookieAttributeMatchingTest relies on nonce'd names with no teardown,
    // CookiePersistenceTest clears its fixed names in @Before, this file expires its nonce'd names here.
    private val seededNames = mutableSetOf<String>()

    @After
    fun tearDown() {
        // Best-effort: expire ONLY this instance's own nonce'd cookies (by name, on TEST_URL) and flush
        // so the on-disk store reflects the removal. Never fail the suite on cleanup, and never touch the
        // shared TREK session. NOTE: removeAllCookies_clearsCookie / removeSessionCookies_keepsPersistent
        // still issue the GLOBAL clear in their own bodies — that global API IS their subject and has no
        // scoped variant — but those two are the ONLY global wipes in this file, a bounded ≤2 that does
        // not rely on test/class ordering to stay under the suite's login budget.
        try {
            val names = seededNames.toList()
            if (names.isEmpty()) return
            instrumentation.runOnMainSync {
                val cookieManager = CookieManager.getInstance()
                for (name in names) {
                    // name+domain+path is the delete key; a past Max-Age expires the row immediately.
                    cookieManager.setCookie(TEST_URL, "$name=; Max-Age=0; Path=/; Secure")
                }
                cookieManager.flush()
            }
        } catch (_: Exception) {
            // Best-effort only.
        }
    }

    // -----------------------------------------------------------------------------------------------
    // 1. Basic restore / availability: a flushed persistent cookie is still readable AND on disk.
    // -----------------------------------------------------------------------------------------------
    @Test
    fun persistentCookie_flushedThenReadByFreshCookieManagerCall_isPresent() {
        val name = "restore_$nonce"
        val value = "v_$nonce"

        setPersistentCookie(name, value)
        flush()

        val read = getCookie(TEST_URL)
        assertTrue(
            "Persistent cookie '$name' was set + flushed but a fresh CookieManager.getCookie did " +
                "NOT return it (got: $read). The cookie was written but is not readable from the " +
                "live store — restore/availability is broken.",
            read.contains("$name=$value")
        )

        // And it must be present on disk as a persistent row.
        assertTrue(
            "Persistent cookie '$name' is readable in memory but is NOT on disk (is_persistent=1) " +
                "after flush(). Checked: ${cookieStoreFile().absolutePath}",
            cookieCountOnDisk(name) >= 1
        )
    }

    // -----------------------------------------------------------------------------------------------
    // 2. Cross-WebView-instance: a persistent cookie survives destroying one WebView and creating a
    //    new one. CookieManager is process-global, so this SHOULD pass; if it FAILS, restore is broken.
    // -----------------------------------------------------------------------------------------------
    @Test
    fun persistentCookie_survivesAcrossTwoWebViewInstances() {
        val name = "crossinst_$nonce"
        val value = "v_$nonce"

        // First WebView instance: set + flush a persistent cookie via the (process-global) manager.
        val first = createWebViewOnMain()
        setPersistentCookie(name, value)
        flush()
        destroyWebViewOnMain(first)

        // Second, fresh WebView instance.
        val second = createWebViewOnMain()
        try {
            val read = getCookie(TEST_URL)
            assertTrue(
                "Persistent cookie '$name' was lost after destroying the first WebView and creating " +
                    "a new one (got: $read). CookieManager is process-global, so a fresh WebView " +
                    "instance must still see it — restore across WebView instances is broken.",
                read.contains("$name=$value")
            )
        } finally {
            destroyWebViewOnMain(second)
        }
    }

    // -----------------------------------------------------------------------------------------------
    // 3. Logout path: removeAllCookies removes the cookie from memory AND from disk after flush.
    // -----------------------------------------------------------------------------------------------
    @Test
    fun removeAllCookies_clearsCookie() {
        val name = "clearall_$nonce"
        val value = "v_$nonce"

        setPersistentCookie(name, value)
        flush()
        // Sanity: it is actually there before we clear.
        assertTrue(
            "Precondition failed: cookie '$name' was not present before removeAllCookies.",
            getCookie(TEST_URL).contains("$name=$value")
        )

        awaitRemoveAllCookies()
        flush()

        val read = getCookie(TEST_URL)
        assertFalse(
            "After removeAllCookies (logout), cookie '$name' is STILL returned by getCookie " +
                "(got: $read). The clear/logout path does not actually remove the cookie from the " +
                "live store.",
            read.contains("$name=")
        )
        assertTrue(
            "After removeAllCookies + flush, cookie '$name' is STILL on disk. The logout clear was " +
                "not persisted — a stale auth cookie survives on disk. " +
                "Checked: ${cookieStoreFile().absolutePath}",
            cookieCountOnDisk(name) == 0L
        )
    }

    // -----------------------------------------------------------------------------------------------
    // 4. removeSessionCookies drops the session cookie but keeps the persistent one. This characterizes
    //    what a process restart effectively does: non-persistent (session) cookies are dropped.
    // -----------------------------------------------------------------------------------------------
    @Test
    fun removeSessionCookies_keepsPersistent() {
        val sessionName = "sess_$nonce"
        val persistentName = "pers_$nonce"
        val sessionValue = "sv_$nonce"
        val persistentValue = "pv_$nonce"

        setSessionCookie(sessionName, sessionValue)
        setPersistentCookie(persistentName, persistentValue)

        // Both present before clearing session cookies.
        val before = getCookie(TEST_URL)
        assertTrue(
            "Precondition failed: session cookie '$sessionName' not present before " +
                "removeSessionCookies (got: $before).",
            before.contains("$sessionName=$sessionValue")
        )
        assertTrue(
            "Precondition failed: persistent cookie '$persistentName' not present before " +
                "removeSessionCookies (got: $before).",
            before.contains("$persistentName=$persistentValue")
        )

        awaitRemoveSessionCookies()

        val after = getCookie(TEST_URL)
        assertFalse(
            "After removeSessionCookies, the SESSION cookie '$sessionName' is still present " +
                "(got: $after) — session cookies should have been dropped.",
            after.contains("$sessionName=")
        )
        assertTrue(
            "After removeSessionCookies, the PERSISTENT cookie '$persistentName' was incorrectly " +
                "removed (got: $after) — only session cookies should be dropped. A process restart " +
                "must keep persistent auth cookies.",
            after.contains("$persistentName=$persistentValue")
        )
    }

    // -----------------------------------------------------------------------------------------------
    // 5. In-page JS cookie set: a cookie written by document.cookie during page load is stored and
    //    readable. This is the SPA-style, client-readable (non-HttpOnly) path TREK uses to set cookies.
    // -----------------------------------------------------------------------------------------------
    @Test
    fun cookieSetDuringPageLoad_viaJsDocumentCookie_isStored() {
        val name = "js_$nonce"
        val value = "v_$nonce"
        seededNames += name

        instrumentation.runOnMainSync { CookieManager.getInstance().setAcceptCookie(true) }

        // Minimal HTML that sets a NON-HttpOnly cookie from in-page JS during load.
        val html = """
            <!doctype html>
            <html>
              <head><meta charset="utf-8"></head>
              <body>
                <script>
                  document.cookie = '$name=$value; Max-Age=3600; Path=/';
                </script>
                ok
              </body>
            </html>
        """.trimIndent()

        val finished = CountDownLatch(1)
        val holder = arrayOfNulls<WebView>(1)
        instrumentation.runOnMainSync {
            val wv = WebView(targetContext)
            wv.settings.javaScriptEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    finished.countDown()
                }
            }
            wv.loadDataWithBaseURL(TEST_URL, html, "text/html", "utf-8", null)
            holder[0] = wv
        }

        try {
            assertTrue(
                "onPageFinished did not fire within $PAGE_LOAD_AWAIT_SECONDS s for the JS-cookie page.",
                finished.await(PAGE_LOAD_AWAIT_SECONDS, TimeUnit.SECONDS)
            )

            val read = getCookie(TEST_URL)
            assertNotNull("getCookie returned null for $TEST_URL", read)
            assertTrue(
                "Cookie '$name' set by in-page document.cookie during page load was NOT stored " +
                    "(getCookie returned: $read). The SPA-style client-set cookie path is broken.",
                read.contains("$name=$value")
            )
        } finally {
            instrumentation.runOnMainSync {
                holder[0]?.let {
                    it.stopLoading()
                    it.destroy()
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------
    // 6. DOM storage local-persists / session-does-not.
    //    INTENTIONALLY NOT IMPLEMENTED HERE. This is already covered by DomStoragePersistenceTest
    //    (localStorage survives across WebView instances; sessionStorage does not). Do not duplicate —
    //    see app/src/androidTest/java/io/soult/embara/DomStoragePersistenceTest.kt.
    // -----------------------------------------------------------------------------------------------

    // ============================ Helpers (cookie store, on main thread) ============================

    /** Sets a PERSISTENT cookie (Max-Age -> is_persistent=1, has_expires=1) and awaits the callback. */
    private fun setPersistentCookie(name: String, value: String) {
        seededNames += name
        instrumentation.runOnMainSync { CookieManager.getInstance().setAcceptCookie(true) }
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(
                TEST_URL,
                "$name=$value; Max-Age=86400; Path=/; Secure"
            ) { latch.countDown() }
        }
        assertTrue(
            "setCookie callback timed out seeding persistent cookie '$name'.",
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )
    }

    /** Sets a SESSION cookie (no Max-Age/Expires -> is_persistent=0) and awaits the callback. */
    private fun setSessionCookie(name: String, value: String) {
        seededNames += name
        instrumentation.runOnMainSync { CookieManager.getInstance().setAcceptCookie(true) }
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(
                TEST_URL,
                "$name=$value; Path=/; Secure"
            ) { latch.countDown() }
        }
        assertTrue(
            "setCookie callback timed out seeding session cookie '$name'.",
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )
    }

    /** Reads cookies for [url] from the live (in-memory) CookieManager store on the main thread. */
    private fun getCookie(url: String): String {
        val holder = arrayOf("")
        instrumentation.runOnMainSync {
            holder[0] = CookieManager.getInstance().getCookie(url) ?: ""
        }
        return holder[0]
    }

    /** Flushes the in-memory store to disk on the main thread. */
    private fun flush() {
        instrumentation.runOnMainSync { CookieManager.getInstance().flush() }
    }

    /** removeAllCookies and await the value callback. */
    private fun awaitRemoveAllCookies() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().removeAllCookies { latch.countDown() }
        }
        assertTrue(
            "removeAllCookies callback timed out.",
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )
    }

    /** removeSessionCookies and await the value callback. */
    private fun awaitRemoveSessionCookies() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().removeSessionCookies { latch.countDown() }
        }
        assertTrue(
            "removeSessionCookies callback timed out.",
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        )
    }

    /** Creates a WebView on the main thread and returns it. */
    private fun createWebViewOnMain(): WebView {
        val holder = arrayOfNulls<WebView>(1)
        instrumentation.runOnMainSync {
            holder[0] = WebView(ApplicationProvider.getApplicationContext())
        }
        return holder[0]!!
    }

    /** Destroys a WebView on the main thread. */
    private fun destroyWebViewOnMain(webView: WebView) {
        instrumentation.runOnMainSync {
            webView.stopLoading()
            webView.destroy()
        }
    }

    // ============================ Helpers (on-disk Cookies DB inspection) ===========================
    // Mirrors the read approach in CookieFlushPersistenceTest: copy the live DB (plus -wal/-journal
    // sidecars) to a temp file to dodge the WebView's WAL lock, then open OPEN_READONLY and count by
    // name. Returns 0 on any transient read failure (caller decides how to interpret).

    /** Path to the Chromium on-disk cookie store for this app's WebView. */
    private fun cookieStoreFile(): File =
        File(targetContext.dataDir, "app_webview/Default/Cookies")

    /** Count of on-disk cookie rows whose name equals [name]. 0 if the store does not exist. */
    private fun cookieCountOnDisk(name: String): Long {
        val source = cookieStoreFile()
        if (!source.exists()) return 0L

        val tempDir = File(targetContext.cacheDir, "cookie_restore_clear_test").apply { mkdirs() }
        val copy = File(tempDir, "Cookies_copy_${System.nanoTime()}")
        return try {
            source.copyTo(copy, overwrite = true)
            File(source.parentFile, "${source.name}-wal").takeIf { it.exists() }
                ?.copyTo(File(tempDir, "${copy.name}-wal"), overwrite = true)
            File(source.parentFile, "${source.name}-journal").takeIf { it.exists() }
                ?.copyTo(File(tempDir, "${copy.name}-journal"), overwrite = true)

            queryCookieCount(copy, name)
        } catch (_: Exception) {
            0L
        } finally {
            copy.delete()
            File(tempDir, "${copy.name}-wal").delete()
            File(tempDir, "${copy.name}-journal").delete()
        }
    }

    /** Returns the count of rows in [dbFile] whose cookie name equals [name]. */
    private fun queryCookieCount(dbFile: File, name: String): Long {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            db.rawQuery(
                "SELECT count(*) FROM cookies WHERE name = ?",
                arrayOf(name)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (_: Exception) {
            0L
        } finally {
            db?.close()
        }
    }
}
