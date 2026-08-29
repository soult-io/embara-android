package io.soult.embara

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
        return scheme == "https"
    }

    fun isEmpty(input: String?): Boolean =
        input.isNullOrBlank()

    /**
     * Whether [origin] belongs to the configured TREK server — the same host, or a subdomain of it,
     * over http/https. Mirrors the host rule MainActivity's shouldOverrideUrlLoading uses, so a
     * capability granted to the page (geolocation, downloads) can never be claimed by a third-party
     * frame or a redirected origin.
     *
     * [origin] is what WebKit hands to onGeolocationPermissionsShowPrompt: a scheme://host[:port]
     * string with no path. A blank server host or an unparseable origin is never a match.
     */
    fun isSameServerOrigin(serverHost: String, origin: String?): Boolean {
        if (serverHost.isBlank() || origin.isNullOrBlank()) return false
        val uri = Uri.parse(origin)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        val expected = serverHost.lowercase()
        return host == expected || host.endsWith(".$expected")
    }

    fun sanitizeForHtml(url: String): String =
        url.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
