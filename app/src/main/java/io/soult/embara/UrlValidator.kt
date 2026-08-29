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
     * Whether [url] is served by the configured TREK server — the same host, or a subdomain of it,
     * over http/https.
     *
     * This is a HOST check, not a web-origin check: scheme and port are deliberately not compared,
     * because it has to give the same answer as the navigation gate in MainActivity's
     * shouldOverrideUrlLoading, which is host-only. A second service on another port of the same
     * host therefore counts as the server. Named for what it does so the weaker guarantee is not
     * mistaken for an origin comparison.
     *
     * [url] may be a full URL or the scheme://host[:port] form WebKit passes to
     * onGeolocationPermissionsShowPrompt. A blank server host or an unparseable url never matches.
     */
    fun isSameServerHost(serverHost: String, url: String?): Boolean {
        if (serverHost.isBlank() || url.isNullOrBlank()) return false
        val uri = Uri.parse(url)
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
