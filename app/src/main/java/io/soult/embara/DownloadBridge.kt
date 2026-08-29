package io.soult.embara

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Saves files the WebView wants to hand to the user.
 *
 * TREK 4.0.0's mobile shell downloads things Embara previously never saw. Both of the ones reachable
 * on a phone build a `blob:` object url and click a synthetic `<a download>`: the MFA backup codes
 * in mobile Settings (`trek-mfa-backup-codes.txt`) and the trip calendar/GPX export in the mobile
 * trip shell. With no `DownloadListener` at all, WebKit drops every one of them silently — no file,
 * no error, no notification.
 *
 * A `DownloadListener` alone is not enough, because [DownloadManager] cannot fetch a `blob:` url:
 * the bytes only exist inside the renderer. So there are two paths:
 *
 *  - **http(s)** — handed to [DownloadManager]. Only downloads from the configured TREK server are
 *    saved; anything else is handed to the system browser, matching how MainActivity already treats
 *    off-server navigation.
 *  - **blob: / data:** — the hook from [hookJs] intercepts the click in the page, reads the blob out
 *    through a FileReader and posts it back here as base64, which is written directly.
 *
 * ## Why the JS path is gated three ways
 *
 * `WebView.addJavascriptInterface` installs the named object into **every frame** of the WebView,
 * not just the main frame and not just same-origin frames, and a JS call needs no user gesture.
 * Left open, [JsInterface.saveBase64] would be an unauthenticated write primitive that any
 * third-party iframe on a TREK page could use to fill the device, or to drop a plausible-looking
 * `.apk` into Downloads with a "Saved" toast and no interaction at all. So a call is only honoured
 * when all three hold:
 *
 *  1. it carries the per-document nonce, which is minted natively and injected into the **main
 *     frame only** — a subframe is never handed it;
 *  2. the main frame is on the configured TREK server;
 *  3. the document has not already written [MAX_BYTES_PER_PAGE].
 */
class DownloadBridge(
    private val activity: ComponentActivity,
    private val serverHost: () -> String,
    private val pageUrl: () -> String?,
) {

    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingLegacySave: (() -> Unit)? = null

    /** Read on the JavaBridge thread, written on the UI thread. */
    @Volatile
    private var pageNonce: String = ""

    @Volatile
    private var bytesWrittenThisPage: Long = 0

    fun register() {
        if (!needsLegacyStoragePermission()) return
        storagePermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val save = pendingLegacySave
            pendingLegacySave = null
            if (granted && save != null) save() else toast(R.string.download_failed)
        }
    }

    /**
     * Called from `onPageStarted`: a new document invalidates the old nonce and resets the write
     * budget. Deliberately NOT called on a SPA route change — that keeps the same document, so the
     * hook already injected there keeps the nonce it was given.
     */
    fun onNewDocument() {
        pageNonce = ByteArray(NONCE_BYTES)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        bytesWrittenThisPage = 0
    }

    /**
     * The hook to inject into the current document, carrying that document's nonce.
     *
     * Mints one if there is none: `WebView.restoreState` can bring a page back without
     * `onPageStarted` ever firing for it, and a hook injected with an empty nonce would have every
     * call rejected — downloads silently broken again, which is the exact failure this fixes.
     */
    fun currentHookJs(): String {
        if (pageNonce.isEmpty()) onNewDocument()
        return hookJs(pageNonce)
    }

    /** Wired to `WebView.setDownloadListener`. */
    fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        @Suppress("UNUSED_PARAMETER") contentLength: Long,
    ) {
        if (!URLUtil.isHttpUrl(url) && !URLUtil.isHttpsUrl(url)) {
            // blob:/data: never reach DownloadManager — the injected hook handles those before
            // WebKit gets here. Anything else is not something we can save.
            toast(R.string.download_failed)
            return
        }

        if (!UrlValidator.isSameServerHost(serverHost(), url)) {
            // Off-server: hand it to the browser rather than downloading it under Embara's identity.
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                toast(R.string.download_failed)
            }
            return
        }

        val name = DownloadNaming.sanitize(
            URLUtil.guessFileName(url, contentDisposition, mimeType),
            extensionFor(mimeType),
        )

        withStoragePermission {
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                userAgent?.let { request.addRequestHeader("User-Agent", it) }
                // The session cookie is deliberately NOT attached. DownloadManager re-applies its
                // request headers on every connection attempt, redirects included, and does not drop
                // them on a cross-origin hop — so an open redirect anywhere on the TREK server would
                // hand the HttpOnly session cookie to the redirect target. Nothing TREK 4.0.0 offers
                // on a phone needs it: every mobile export is built in the page as a blob and takes
                // the hook path above, which never leaves the renderer.
                val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                toast(R.string.download_started)
            } catch (_: Exception) {
                toast(R.string.download_failed)
            }
        }
    }

    /** Called when the activity goes away, so a queued legacy save can't fire into a dead window. */
    fun cancelPending() {
        pendingLegacySave = null
    }

    /**
     * The JS side of the blob path. Exposed to the page as [BRIDGE_NAME]; see the class doc for why
     * every call is checked against the document nonce, the main-frame host and a page budget before
     * a single byte is written.
     */
    inner class JsInterface {
        @JavascriptInterface
        fun saveBase64(nonce: String?, suggestedName: String?, mimeType: String?, base64: String?) {
            if (!nonceMatches(nonce)) {
                // A subframe, or a hook left over from a previous document. Silent on purpose: a
                // caller that was never handed the nonce should not learn whether one exists.
                return
            }
            if (base64.isNullOrEmpty()) {
                fail(R.string.download_failed)
                return
            }
            if (base64.length > MAX_BASE64_LENGTH) {
                fail(R.string.download_too_large)
                return
            }
            // Base64.decode can OOM on a large payload, and OutOfMemoryError is an Error, not an
            // Exception — an uncaught one on the JavaBridge thread takes the process down.
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (_: Throwable) {
                fail(R.string.download_failed)
                return
            }
            if (bytesWrittenThisPage + bytes.size > MAX_BYTES_PER_PAGE) {
                fail(R.string.download_too_large)
                return
            }
            bytesWrittenThisPage += bytes.size

            val name = DownloadNaming.sanitize(suggestedName, extensionFor(mimeType))
            activity.runOnUiThread {
                if (!UrlValidator.isSameServerHost(serverHost(), pageUrl())) {
                    toast(R.string.download_failed)
                    return@runOnUiThread
                }
                withStoragePermission { writeToDownloads(name, mimeType, bytes) }
            }
        }

        @JavascriptInterface
        fun reportFailure(nonce: String?) {
            if (!nonceMatches(nonce)) return
            fail(R.string.download_failed)
        }

        private fun nonceMatches(nonce: String?): Boolean =
            pageNonce.isNotEmpty() && nonce == pageNonce

        private fun fail(messageId: Int) {
            activity.runOnUiThread { toast(messageId) }
        }
    }

    private fun writeToDownloads(name: String, mimeType: String?, bytes: ByteArray) {
        // Up to MAX_BYTES_PER_PAGE of disk I/O, and on the legacy path a directory scan — never on
        // the main thread.
        thread(name = "embara-download") {
            val saved = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeViaMediaStore(name, mimeType, bytes)
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    uniqueFile(dir, name).writeBytes(bytes)
                }
                true
            } catch (_: Throwable) {
                false
            }
            activity.runOnUiThread {
                toast(if (saved) R.string.download_saved else R.string.download_failed)
            }
        }
    }

    private fun writeViaMediaStore(name: String, mimeType: String?, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            mimeType?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore rejected the insert")
        resolver.openOutputStream(uri).use { it?.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /** Never clobber an existing download; MediaStore does this for us on Q+. */
    private fun uniqueFile(dir: File, name: String): File {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists() && n < MAX_NAME_COLLISIONS) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            candidate = File(dir, "$stem ($n)$suffix")
            n++
        }
        return candidate
    }

    /**
     * Runs [save] once public-Downloads access is available. On API 29+ scoped storage means that
     * is always; below it the legacy write needs WRITE_EXTERNAL_STORAGE at runtime.
     */
    private fun withStoragePermission(save: () -> Unit) {
        if (!needsLegacyStoragePermission()) {
            save()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            save()
            return
        }
        val launcher = storagePermissionLauncher
        if (launcher == null || pendingLegacySave != null) {
            toast(R.string.download_failed)
            return
        }
        pendingLegacySave = save
        try {
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } catch (_: Exception) {
            pendingLegacySave = null
            toast(R.string.download_failed)
        }
    }

    private fun needsLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun extensionFor(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    private fun toast(messageId: Int) {
        Toast.makeText(activity, messageId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val BRIDGE_NAME = "AndroidDownloadBridge"

        private const val NONCE_BYTES = 16

        /** ~24 MB of payload once base64-decoded. Guards the single-string JS bridge hop. */
        const val MAX_BASE64_LENGTH = 32 * 1024 * 1024

        /** Matched by the JS-side blob.size check, so an oversized blob is refused before it is read. */
        const val MAX_BLOB_BYTES = 24 * 1024 * 1024

        /** Total a single document may write, however many downloads it splits it across. */
        const val MAX_BYTES_PER_PAGE = 128L * 1024 * 1024

        private const val MAX_NAME_COLLISIONS = 100

        /**
         * The hook injected into the main frame on every page load. TREK's mobile exports (MFA
         * backup codes, trip .ics/.gpx) build a `blob:` url and click a synthetic `<a download>`;
         * WebKit's DownloadListener is never told about those, and DownloadManager could not fetch
         * them anyway.
         *
         * The bytes are taken from the Blob the page already holds, captured as it mints the object
         * url. Reading them back with `fetch(blobUrl)` looks simpler and does not work: TREK serves
         * a `connect-src` Content-Security-Policy that does not list `blob:`, so the document
         * refuses its own object url ("Refused to connect because it violates the document's Content
         * Security Policy") and the download fails silently a second way. Nothing here touches the
         * network.
         *
         * [nonce] is what proves a call came from this hook, in this document's main frame, rather
         * than from a subframe WebKit also handed the bridge object to. It is interpolated as a JSON
         * string literal.
         *
         * Only `blob:`/`data:` anchors are intercepted — ordinary links and http(s) downloads take
         * their normal path into the DownloadListener. Idempotent per document via
         * window.__embaraDlHooked, and wrapped so a page quirk can never break the click.
         */
        fun hookJs(nonce: String): String = """
            (function(){
              if (window.__embaraDlHooked) return; window.__embaraDlHooked = true;
              var NONCE = ${JSONObject.quote(nonce)};
              var MAX_BYTES = $MAX_BLOB_BYTES;

              // Bounded so a page that mints object urls freely (the map layers do) cannot make this
              // retain blobs indefinitely; revoking drops the entry immediately, and anything over
              // the size limit is never tracked because it could not be saved anyway.
              var MAX_TRACKED = 8;
              var blobs = new Map();
              try {
                var createUrl = URL.createObjectURL.bind(URL);
                var revokeUrl = URL.revokeObjectURL.bind(URL);
                URL.createObjectURL = function(obj){
                  var u = createUrl(obj);
                  try {
                    if (typeof Blob !== 'undefined' && obj instanceof Blob && obj.size <= MAX_BYTES) {
                      blobs.set(u, obj);
                      while (blobs.size > MAX_TRACKED) { blobs.delete(blobs.keys().next().value); }
                    }
                  } catch (_) {}
                  return u;
                };
                URL.revokeObjectURL = function(u){
                  try { blobs.delete(u); } catch (_) {}
                  return revokeUrl(u);
                };
              } catch (_) {}

              function fail(){ try { AndroidDownloadBridge.reportFailure(NONCE); } catch (_) {} }

              function deliver(name, blob){
                try {
                  if (!blob || blob.size > MAX_BYTES) { fail(); return; }
                  var fr = new FileReader();
                  fr.onloadend = function(){
                    var s = String(fr.result), i = s.indexOf(',');
                    if (i < 0) { fail(); return; }
                    AndroidDownloadBridge.saveBase64(NONCE, name, blob.type || '', s.slice(i + 1));
                  };
                  fr.onerror = fail;
                  fr.readAsDataURL(blob);
                } catch (_) { fail(); }
              }

              function isDownloadAnchor(el){
                return !!(el && el.tagName && String(el.tagName).toUpperCase() === 'A' &&
                          el.hasAttribute && el.hasAttribute('download'));
              }

              function anchorFor(e){
                // composedPath sees through a shadow root, where e.target is retargeted to the host.
                try {
                  var path = e.composedPath ? e.composedPath() : null;
                  if (path) {
                    for (var i = 0; i < path.length; i++) {
                      if (isDownloadAnchor(path[i])) return path[i];
                    }
                  }
                } catch (_) {}
                var n = e.target;
                while (n && n !== document) {
                  if (isDownloadAnchor(n)) return n;
                  n = n.parentNode;
                }
                return null;
              }

              document.addEventListener('click', function(e){
                try {
                  var a = anchorFor(e);
                  if (!a) return;
                  var href = a.getAttribute('href') || '';
                  var name = a.getAttribute('download') || '';
                  if (/^blob:/i.test(href)) {
                    e.preventDefault();
                    deliver(name, blobs.get(href));
                  } else if (/^data:/i.test(href)) {
                    e.preventDefault();
                    var comma = href.indexOf(',');
                    if (comma < 0) { fail(); return; }
                    var meta = href.slice(5, comma);
                    var mime = meta.split(';')[0] || 'application/octet-stream';
                    var body = href.slice(comma + 1);
                    if (/;base64/i.test(meta)) {
                      if (body.length > MAX_BYTES) { fail(); return; }
                      AndroidDownloadBridge.saveBase64(NONCE, name, mime, body);
                    } else {
                      var text;
                      try { text = decodeURIComponent(body); } catch (_) { fail(); return; }
                      deliver(name, new Blob([text], {type: mime}));
                    }
                  }
                } catch (_) { fail(); }
              }, true);
            })();
        """
    }
}
