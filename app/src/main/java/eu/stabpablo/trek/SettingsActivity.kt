package eu.stabpablo.trek

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val RESULT_URL_CHANGED = 100
        const val RESULT_CACHE_CLEARED = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val currentUrl = TrekPrefs.getServerUrl(this) ?: getString(R.string.settings_no_server)
        findViewById<TextView>(R.id.settings_current_url).text = currentUrl

        findViewById<LinearLayout>(R.id.settings_change_server).setOnClickListener {
            confirmChangeServer()
        }

        findViewById<LinearLayout>(R.id.settings_clear_cache).setOnClickListener {
            confirmClearCache()
        }

        val version = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (_: Exception) {
            null
        } ?: "unknown"
        findViewById<TextView>(R.id.settings_version_value).text = version
    }

    private fun confirmChangeServer() {
        MaterialAlertDialogBuilder(this, R.style.Theme_Trek_Dialog)
            .setTitle(R.string.settings_change_server_title)
            .setMessage(R.string.settings_change_server_confirm)
            .setPositiveButton(R.string.settings_change) { _, _ ->
                TrekPrefs.clearServerUrl(this)
                setResult(RESULT_URL_CHANGED)
                finish()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(this, R.style.Theme_Trek_Dialog)
            .setTitle(R.string.settings_clear_cache_title)
            .setMessage(R.string.settings_clear_cache_confirm)
            .setPositiveButton(R.string.settings_clear) { _, _ ->
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                setResult(RESULT_CACHE_CLEARED)
                finish()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }
}
