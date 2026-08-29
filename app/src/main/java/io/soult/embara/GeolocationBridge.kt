package io.soult.embara

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Bridges WebKit's geolocation prompt to Android's runtime location permission.
 *
 * TREK 4.0.0 calls `navigator.geolocation` from its `useGeolocation` hook — the journey entry
 * editor's "Use my current location" button and the mobile quick-capture sheet, which asks for a
 * fix as soon as it opens. Without an `onGeolocationPermissionsShowPrompt` override WebKit's
 * default denies immediately, so those surfaces fail silently with no prompt and no error the user
 * can act on.
 *
 * Grants are scoped to the configured TREK server origin and are never retained: WebKit is told
 * `retain = false`, so a grant lives only for the current page and every fresh request is
 * re-checked against the Android permission. The app asks the OS at most once per prompt; a denial
 * is reported back to JS as an error rather than left hanging.
 *
 * [register] must be called before the activity reaches STARTED (registerForActivityResult's
 * contract), so it belongs in onCreate.
 */
class GeolocationBridge(private val serverHost: () -> String) {

    private var launcher: ActivityResultLauncher<Array<String>>? = null

    private var pendingOrigin: String? = null
    private var pendingCallback: GeolocationPermissions.Callback? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val granted = grants.values.any { it }
            respond(granted)
        }
    }

    /** Wired to `WebChromeClient.onGeolocationPermissionsShowPrompt`. */
    fun onPrompt(context: Context, origin: String?, callback: GeolocationPermissions.Callback?) {
        if (callback == null) return

        // A frame from anywhere other than the user's own server never gets the device's position,
        // whatever it asks for.
        if (!UrlValidator.isSameServerHost(serverHost(), origin)) {
            callback.invoke(origin.orEmpty(), false, false)
            return
        }
        val allowedOrigin = origin!!

        if (hasLocationPermission(context)) {
            callback.invoke(allowedOrigin, true, false)
            return
        }

        val target = launcher
        if (target == null || pendingCallback != null) {
            // Not registered, or a request is already in flight — deny rather than queue, so the
            // page gets a definite answer instead of a callback that may never fire.
            callback.invoke(allowedOrigin, false, false)
            return
        }

        pendingOrigin = allowedOrigin
        pendingCallback = callback
        try {
            target.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } catch (_: Exception) {
            // An unregistered or torn-down launcher throws. Answer rather than leaving WebKit
            // holding a callback nothing will ever complete.
            respond(granted = false)
        }
    }

    /** Answers (and clears) an in-flight prompt. Safe to call when nothing is pending. */
    fun respond(granted: Boolean) {
        val callback = pendingCallback ?: return
        val origin = pendingOrigin.orEmpty()
        pendingCallback = null
        pendingOrigin = null
        callback.invoke(origin, granted, false)
    }

    /**
     * Releases an in-flight prompt when the activity goes away, so WebKit isn't left holding a
     * callback that can no longer be answered.
     */
    fun cancelPending() = respond(granted = false)

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
