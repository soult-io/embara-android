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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

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
 *  - **http(s)** — handed to [DownloadManager] with the WebView's cookies and user-agent, so an
 *    authenticated export downloads as the signed-in user.
 *  - **blob: / data:** — [BLOB_DOWNLOAD_HOOK_JS] intercepts the click in the page, reads the blob
 *    out through a FileReader and posts it back here as base64, which is written directly.
 *
 * Only downloads from the configured TREK server are saved; anything else is handed to the system
 * browser, matching how MainActivity already treats off-server navigation.
 */
class DownloadBridge(
    private val activity: ComponentActivity,
    private val serverHost: () -> String,
) {

    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingLegacySave: (() -> Unit)? = null

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

    /** Wired to `WebView.setDownloadListener`. */
    fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        @Suppress("UNUSED_PARAMETER") contentLength: Long,
    ) {
        if (!URLUtil.isHttpUrl(url) && !URLUtil.isHttpsUrl(url)) {
            // blob:/data: never reach DownloadManager — BLOB_DOWNLOAD_HOOK_JS handles those before
            // WebKit gets here. Anything else is not something we can save.
            toast(R.string.download_failed)
            return
        }

        if (!UrlValidator.isSameServerOrigin(serverHost(), originOf(url))) {
            // Off-server: hand it to the browser rather than downloading it under Embara's identity
            // and cookies. Matches how MainActivity already treats off-server navigation.
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
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { request.addRequestHeader("Cookie", it) }

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
     * The JS side of the blob path. Exposed to the page as [BRIDGE_NAME]; only reachable from a
     * page the WebView itself loaded, and it can do exactly one thing — write bytes the page
     * already had into the user's Downloads folder under a sanitized name.
     */
    inner class JsInterface {
        @JavascriptInterface
        fun saveBase64(suggestedName: String?, mimeType: String?, base64: String?) {
            if (base64.isNullOrEmpty()) {
                activity.runOnUiThread { toast(R.string.download_failed) }
                return
            }
            if (base64.length > MAX_BASE64_LENGTH) {
                activity.runOnUiThread { toast(R.string.download_too_large) }
                return
            }
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                activity.runOnUiThread { toast(R.string.download_failed) }
                return
            }
            val name = DownloadNaming.sanitize(suggestedName, extensionFor(mimeType))
            activity.runOnUiThread {
                withStoragePermission { writeToDownloads(name, mimeType, bytes) }
            }
        }

        @JavascriptInterface
        fun reportFailure() {
            activity.runOnUiThread { toast(R.string.download_failed) }
        }
    }

    private fun writeToDownloads(name: String, mimeType: String?, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                uniqueFile(dir, name).writeBytes(bytes)
            }
            toast(R.string.download_saved)
        } catch (_: Exception) {
            toast(R.string.download_failed)
        }
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
        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun needsLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun extensionFor(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    /** scheme://host[:port] of [url], or null if it has no host — the shape isSameServerOrigin wants. */
    private fun originOf(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://$host$port"
    }

    private fun toast(messageId: Int) {
        Toast.makeText(activity, messageId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val BRIDGE_NAME = "AndroidDownloadBridge"

        /** ~24 MB of payload once base64-decoded. Guards the single-string JS bridge hop. */
        private const val MAX_BASE64_LENGTH = 32 * 1024 * 1024

        private const val MAX_NAME_COLLISIONS = 1000

        /**
         * Injected on every page load. TREK's mobile exports (MFA backup codes, trip .ics/.gpx)
         * build a `blob:` url and click a synthetic `<a download>`; WebKit's DownloadListener is
         * never told about those, and DownloadManager could not fetch them anyway.
         *
         * The bytes are taken from the Blob the page already holds, captured as it mints the object
         * url. Reading them back through `fetch(blobUrl)` looks simpler and does not work: TREK
         * serves a `connect-src` Content-Security-Policy that does not list `blob:`, so the page
         * refuses its own object url ("Refused to connect because it violates the document's
         * Content Security Policy") and the download fails silently a second way. Nothing here
         * touches the network.
         *
         * Only `blob:`/`data:` anchors are intercepted — ordinary links and http(s) downloads take
         * their normal path into the DownloadListener. Idempotent per document via
         * window.__embaraDlHooked, and wrapped so a page quirk can never break the click.
         */
        const val BLOB_DOWNLOAD_HOOK_JS = """
            (function(){
              if (window.__embaraDlHooked) return; window.__embaraDlHooked = true;

              // Bounded so a page that mints object urls freely (the map layers do) cannot make
              // this retain blobs indefinitely; revoking drops the entry immediately.
              var MAX_TRACKED = 16;
              var blobs = new Map();
              try {
                var createUrl = URL.createObjectURL.bind(URL);
                var revokeUrl = URL.revokeObjectURL.bind(URL);
                URL.createObjectURL = function(obj){
                  var u = createUrl(obj);
                  try {
                    if (typeof Blob !== 'undefined' && obj instanceof Blob) {
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

              function deliver(name, blob){
                try {
                  var fr = new FileReader();
                  fr.onloadend = function(){
                    var s = String(fr.result), i = s.indexOf(',');
                    if (i < 0) { AndroidDownloadBridge.reportFailure(); return; }
                    AndroidDownloadBridge.saveBase64(name, blob.type || '', s.slice(i + 1));
                  };
                  fr.onerror = function(){ AndroidDownloadBridge.reportFailure(); };
                  fr.readAsDataURL(blob);
                } catch (_) { AndroidDownloadBridge.reportFailure(); }
              }

              document.addEventListener('click', function(e){
                try {
                  var n = e.target, a = null;
                  while (n && n !== document) {
                    if (n.tagName === 'A' && n.hasAttribute('download')) { a = n; break; }
                    n = n.parentNode;
                  }
                  if (!a) return;
                  var href = a.getAttribute('href') || '';
                  var name = a.getAttribute('download') || '';
                  if (/^blob:/i.test(href)) {
                    e.preventDefault();
                    var b = blobs.get(href);
                    if (!b) { AndroidDownloadBridge.reportFailure(); return; }
                    deliver(name, b);
                  } else if (/^data:/i.test(href)) {
                    e.preventDefault();
                    var comma = href.indexOf(',');
                    if (comma < 0) { AndroidDownloadBridge.reportFailure(); return; }
                    var meta = href.slice(5, comma);
                    var mime = meta.split(';')[0] || 'application/octet-stream';
                    var body = href.slice(comma + 1);
                    if (/;base64/i.test(meta)) {
                      AndroidDownloadBridge.saveBase64(name, mime, body);
                    } else {
                      deliver(name, new Blob([decodeURIComponent(body)], {type: mime}));
                    }
                  }
                } catch (_) {}
              }, true);
            })();
        """
    }
}
