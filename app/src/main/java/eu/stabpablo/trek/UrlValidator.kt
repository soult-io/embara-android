package eu.stabpablo.trek

import android.net.Uri

/**
 * URL validation and normalization for TREK server connections.
 * Extracted from SetupActivity for testability.
 */
object UrlValidator {

    fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return when {
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("http://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    fun isValidScheme(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" || uri.scheme == "http"
    }

    fun isEmpty(input: String?): Boolean =
        input.isNullOrBlank()
}
