package io.soult.embara

import android.app.Activity
import android.content.ActivityNotFoundException
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
        // removes them.
        sweepCaptures(activity)
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
            if (pendingCallback == null && uris != null) {
                // The activity was recreated (rotation, or a low-memory kill while the camera app
                // was foregrounded) so the WebView that asked is gone and was already answered with
                // null. Say so rather than dropping the user's photo in silence.
                appContext?.let { Toast.makeText(it, R.string.file_pick_lost, Toast.LENGTH_LONG).show() }
            }
            deliver(uris)
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
     */
    private fun sweepCaptures(context: Context) {
        runCatching {
            File(context.cacheDir, CAPTURE_DIR).listFiles()?.forEach { it.delete() }
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
    }
}
