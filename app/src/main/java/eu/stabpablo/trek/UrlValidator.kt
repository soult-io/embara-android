package eu.stabpablo.trek

import android.net.Uri

object UrlValidator {

    // M5: Case-insensitive scheme matching
    fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("https://") -> trimmed
            lower.startsWith("http://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    fun isValidScheme(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    fun isEmpty(input: String?): Boolean =
        input.isNullOrBlank()

    fun sanitizeForHtml(url: String): String =
        url.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
