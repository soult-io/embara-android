package io.soult.embara

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name reaching DownloadNaming comes from a page's `download` attribute or a Content-Disposition
 * header, so these are as much a security boundary as a formatting one: nothing that gets through
 * may contain a path separator or escape the Downloads directory.
 */
class DownloadNamingTest {

    // -- the names TREK 4.0.0 actually produces --

    @Test
    fun `keeps a plain export filename intact`() {
        assertEquals("trek-mfa-backup-codes.txt", DownloadNaming.sanitize("trek-mfa-backup-codes.txt"))
    }

    @Test
    fun `keeps a dotted trip export name intact`() {
        assertEquals("Interrail-2026.ics", DownloadNaming.sanitize("Interrail-2026.ics"))
    }

    @Test
    fun `replaces spaces in a trip title`() {
        assertEquals("Summer_in_Spain.gpx", DownloadNaming.sanitize("Summer in Spain.gpx"))
    }

    // -- traversal / separators --

    @Test
    fun `strips a parent-directory traversal`() {
        assertEquals("passwd", DownloadNaming.sanitize("../../etc/passwd"))
    }

    @Test
    fun `keeps only the leaf of an absolute path`() {
        assertEquals("codes.txt", DownloadNaming.sanitize("/data/data/io.soult.embara/codes.txt"))
    }

    @Test
    fun `treats a backslash as a separator too`() {
        assertEquals("evil.txt", DownloadNaming.sanitize("..\\..\\windows\\evil.txt"))
    }

    @Test
    fun `never returns a name containing a separator`() {
        val hostile = listOf("../a", "a/b", "a\\b", "/", "//", "..", ".", "....//....//x")
        for (input in hostile) {
            val result = DownloadNaming.sanitize(input)
            assertFalse("'$input' produced '$result'", result.contains('/') || result.contains('\\'))
            assertTrue("'$input' produced a blank name", result.isNotBlank())
            assertFalse("'$input' produced '$result'", result == "." || result == "..")
        }
    }

    // -- fallbacks --

    @Test
    fun `falls back to a default name when empty`() {
        assertEquals("download", DownloadNaming.sanitize(""))
        assertEquals("download", DownloadNaming.sanitize(null))
        assertEquals("download", DownloadNaming.sanitize("   "))
    }

    @Test
    fun `appends the fallback extension only when there is none`() {
        assertEquals("notes.txt", DownloadNaming.sanitize("notes", "txt"))
        assertEquals("notes.ics", DownloadNaming.sanitize("notes.ics", "txt"))
        assertEquals("download.gpx", DownloadNaming.sanitize(null, "gpx"))
    }

    @Test
    fun `ignores a blank fallback extension`() {
        assertEquals("notes", DownloadNaming.sanitize("notes", ""))
        assertEquals("notes", DownloadNaming.sanitize("notes", null))
    }

    @Test
    fun `sanitizes the fallback extension itself`() {
        assertEquals("notes.txt", DownloadNaming.sanitize("notes", ".txt"))
        assertEquals("notes.bin", DownloadNaming.sanitize("notes", "//"))
    }

    // -- hostile characters --

    @Test
    fun `drops a leading dot so the file is not hidden`() {
        assertEquals("bashrc", DownloadNaming.sanitize(".bashrc"))
    }

    @Test
    fun `strips control characters`() {
        assertEquals("codes.txt", DownloadNaming.sanitize("codes\u0007\n.txt"))
    }

    @Test
    fun `replaces filesystem-illegal characters`() {
        assertEquals("a_b_c_d.txt", DownloadNaming.sanitize("a:b?c*d.txt"))
    }

    // -- length --

    @Test
    fun `truncates an over-long name but keeps its extension`() {
        val result = DownloadNaming.sanitize("x".repeat(400) + ".ics")
        assertTrue(result.length <= 120)
        assertTrue("expected .ics to survive, got '$result'", result.endsWith(".ics"))
    }

    @Test
    fun `truncates an over-long name with no usable extension`() {
        val result = DownloadNaming.sanitize("y".repeat(400))
        assertEquals(120, result.length)
    }
}
