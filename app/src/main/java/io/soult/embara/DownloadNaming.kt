package io.soult.embara

/**
 * Filename handling for downloads saved out of the WebView.
 *
 * Everything here is pure string work so it is unit-testable on the JVM (the project's convention:
 * anything touching android.net.Uri goes in an instrumented test instead — see UrlValidatorTest vs
 * UrlValidatorInstrumentedTest).
 *
 * The name reaching [sanitize] is attacker-influenced: it comes from a page's `download` attribute
 * or a Content-Disposition header, so it must never be able to escape the Downloads directory.
 */
object DownloadNaming {

    private const val FALLBACK = "download"

    // Bytes, not characters: the limit that actually bites is the filesystem's 255-byte name limit,
    // and 120 CJK or emoji characters is 360-480 bytes of UTF-8.
    private const val MAX_BYTES = 200

    /**
     * Reduces [raw] to a single safe filename segment: no path separators, no traversal, no
     * control or format characters, never blank, never longer than [MAX_BYTES] of UTF-8.
     *
     * Any extension is preserved when one survives; [fallbackExtension] (without a dot) supplies
     * one otherwise, so a nameless `blob:` download still lands as something openable.
     */
    fun sanitize(raw: String?, fallbackExtension: String? = null): String {
        // Take the last path-ish segment first, so "../../etc/passwd" and "a\b\c.txt" collapse to
        // their leaf before any other cleaning.
        val leaf = (raw ?: "")
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()

        val cleaned = buildString {
            for (c in leaf) {
                when {
                    // isISOControl covers only C0/C1. The Cf (format) category is the one that
                    // matters here: U+202E RIGHT-TO-LEFT OVERRIDE lets a page render
                    // "photo<RLO>gnp.apk" as a .png in the Files app and the download notification,
                    // and zero-width characters hide differences between two names entirely.
                    c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() -> Unit
                    c in ILLEGAL -> append('_')
                    else -> append(c)
                }
            }
        }
            // A leading dot would make the file hidden; a trailing dot/space is invalid on some
            // filesystems. "." and ".." are stripped to nothing by this and fall through to FALLBACK.
            .trim()
            .trim('.')
            .trim()

        val named = cleaned.ifBlank { FALLBACK }
        val withExtension = if (named.contains('.') || fallbackExtension.isNullOrBlank()) {
            named
        } else {
            "$named.${sanitizeExtension(fallbackExtension)}"
        }

        return truncate(withExtension)
    }

    /** Keeps the extension intact while trimming an over-long stem, measured in UTF-8 bytes. */
    private fun truncate(name: String): String {
        if (utf8Length(name) <= MAX_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && utf8Length(name.substring(dot)) <= MAX_EXTENSION_BYTES) {
            name.substring(dot)
        } else {
            ""
        }
        val stem = if (extension.isEmpty()) name else name.substring(0, dot)
        return takeBytes(stem, MAX_BYTES - utf8Length(extension)) + extension
    }

    /** Takes as much of [text] as fits in [limit] UTF-8 bytes, never splitting a code point. */
    private fun takeBytes(text: String, limit: Int): String {
        var bytes = 0
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val count = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) 2 else 1
            val chunk = text.substring(i, i + count)
            val size = utf8Length(chunk)
            if (bytes + size > limit) break
            out.append(chunk)
            bytes += size
            i += count
        }
        return out.toString().ifBlank { FALLBACK }
    }

    private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun sanitizeExtension(extension: String): String =
        extension.trimStart('.').filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }

    private const val MAX_EXTENSION_BYTES = 12

    /** Conservative set — covers Windows-illegal characters plus the ones that confuse shells. */
    private val ILLEGAL = charArrayOf('/', ' ', ':', '*', '?', '"', '<', '>', '|')
}
