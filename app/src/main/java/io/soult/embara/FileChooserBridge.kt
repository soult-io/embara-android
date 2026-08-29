package io.soult.embara

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves `<input type="file">` inside the WebView.
 *
 * TREK 4.0.0 puts file inputs on mobile surfaces that did not have them before: the quick-capture
 * sheet ships two — an image input with a `capture` hint for the camera and a plain one for the
 * gallery — plus batch photo upload and the external photo providers in the entry editor. WebKit
 * routes all of them through `WebChromeClient.onShowFileChooser`, which does nothing unless the app
 * overrides it, so today every one of those buttons is inert.
 *
 * The chooser intent comes from [WebChromeClient.FileChooserParams.createIntent], which already
 * honours the input's `accept` and `multiple`. `capture` is honoured by adding a camera intent
 * alongside it rather than replacing it, so the user keeps the gallery either way.
 *
 * The pending [ValueCallback] MUST be answered exactly once. Dropping it leaves the input jammed —
 * WebKit will not raise another chooser for that page — so every exit path here calls it, including
 * cancellation and a failure to launch.
 *
 * [register] must be called before the activity reaches STARTED, so it belongs in onCreate.
 */
class FileChooserBridge {

    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraOutput: Uri? = null
    private var pendingCameraFile: File? = null
    private var appContext: Context? = null

    fun register(activity: ComponentActivity) {
        appContext = activity.applicationContext
        // Captures from an earlier run: the camera writes into our cache, and nothing else ever
        // removes them. Off the main thread — this is a directory listing plus deletes.
        val context = activity.applicationContext
        Thread({ sweepStaleCaptures(context) }, "embara-capture-sweep").start()
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cameraOutput = pendingCameraOutput
            val data = result.data
            val uris = when {
                result.resultCode != Activity.RESULT_OK -> null
                // The camera app signals success with an empty result; the image is at the
                // EXTRA_OUTPUT uri we handed it.
                data == null && cameraOutput != null -> arrayOf(cameraOutput)
                else -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            }
            if (pendingCallback == null && result.resultCode == Activity.RESULT_OK) {
                // The activity was recreated (rotation, or a low-memory kill while the camera app
                // was foregrounded) so the WebView that asked is gone and was already answered with
                // null. Keyed on the RESULT CODE, not on `uris`: after recreation the camera branch
                // cannot resolve its EXTRA_OUTPUT either, so `uris` is null on exactly the path
                // where the user did take a photo and most needs telling.
                appContext?.let { Toast.makeText(it, R.string.file_pick_lost, Toast.LENGTH_LONG).show() }
            }
            deliver(uris?.let(::keepTrustworthy))
        }
    }

    /** Wired to `WebChromeClient.onShowFileChooser`. Returns whether the chooser was raised. */
    fun onShowFileChooser(
        context: Context,
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        if (callback == null) return false
        val target = launcher
        if (target == null || params == null) {
            callback.onReceiveValue(null)
            return false
        }

        // A second chooser request supersedes the first; release the old callback so its input is
        // not left waiting forever.
        deliver(null)
        pendingCallback = callback

        val contentIntent = params.createIntent()
        val camera = if (params.isCaptureEnabled) createCameraIntent(context) else null
        pendingCameraOutput = camera?.output
        pendingCameraFile = camera?.file

        val chooser = Intent.createChooser(contentIntent, context.getString(R.string.file_chooser_title))
        if (camera != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera.intent))
        }

        return try {
            target.launch(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            deliver(null)
            false
        }
    }

    /**
     * Releases an in-flight chooser when the activity goes away. WebKit's callback cannot outlive
     * the WebView, and leaving it unanswered is what jams the input.
     */
    fun cancelPending() = deliver(null)

    /**
     * Drops anything a chooser app returns that is not a `content:` uri, and any uri claiming to be
     * from our own FileProvider other than the capture we asked for.
     *
     * `FileChooserParams.parseResult` returns whatever the chosen app put in the intent, unfiltered.
     * A co-installed app registered for `ACTION_GET_CONTENT` can answer with `file:///data/data/...`,
     * which the browser process would then read as Embara's own uid — `allowFileAccess = false`
     * gates page-initiated `file://` loads, not this path.
     */
    private fun keepTrustworthy(uris: Array<Uri>): Array<Uri>? {
        val ownAuthority = appContext?.let { "${it.packageName}.fileprovider" }
        val kept = uris.filter { uri ->
            val scheme = uri.scheme?.lowercase()
            // ContentResolver strips a leading "<userId>@" before it looks the provider up, so the
            // comparison has to strip it too. "content://0@io.soult.embara.fileprovider/..." is not
            // equal to our authority as a string but resolves to our provider all the same.
            val authority = uri.authority?.substringAfterLast('@')
            when {
                scheme != ContentResolver.SCHEME_CONTENT -> false
                authority == ownAuthority -> uri == pendingCameraOutput
                else -> true
            }
        }
        return kept.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun deliver(uris: Array<Uri>?) {
        val cameraOutput = pendingCameraOutput
        val cameraFile = pendingCameraFile
        pendingCameraOutput = null
        pendingCameraFile = null
        // The capture file is created before the chooser opens, so it is left behind whenever the
        // user picks the gallery instead or cancels. Only keep it when it IS the answer.
        if (cameraFile != null && uris?.contains(cameraOutput) != true) {
            runCatching { cameraFile.delete() }
        }
        val callback = pendingCallback ?: return
        pendingCallback = null
        callback.onReceiveValue(uris)
    }

    /**
     * Removes captures left behind by an earlier run — a process death between the camera app
     * returning and the upload finishing leaves a full-resolution photo in the cache, and only
     * storage pressure would ever reclaim it.
     *
     * Only files older than [CAPTURE_STALE_MS] are touched. The activity can be recreated *while*
     * the camera app is foregrounded holding a write descriptor for a capture; sweeping
     * indiscriminately on the next onCreate would delete the photo out from under it.
     */
    private fun sweepStaleCaptures(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - CAPTURE_STALE_MS
            File(context.cacheDir, CAPTURE_DIR).listFiles()
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    private class CameraCapture(val intent: Intent, val output: Uri, val file: File)

    /**
     * ACTION_IMAGE_CAPTURE writing into our own cache via a FileProvider uri. No CAMERA permission
     * is declared or needed: the picture is taken by the camera app, not by us. Package visibility
     * for the resolve is declared in the manifest's `<queries>` block.
     */
    private fun createCameraIntent(context: Context): CameraCapture? = try {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File.createTempFile("capture_", ".jpg", dir)
        val output = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, output)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (intent.resolveActivity(context.packageManager) == null) {
            file.delete()
            null
        } else {
            CameraCapture(intent, output, file)
        }
    } catch (_: Exception) {
        // No camera app, no room in the cache, or a misconfigured provider — fall back to the
        // content chooser alone rather than failing the whole request.
        null
    }

    companion object {
        const val CAPTURE_DIR = "webview_captures"

        /** A capture older than this cannot still belong to a camera app that is running. */
        private const val CAPTURE_STALE_MS = 60L * 60 * 1000
    }
}
