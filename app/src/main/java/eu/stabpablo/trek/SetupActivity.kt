package eu.stabpablo.trek

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SetupActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var urlLayout: TextInputLayout
    private lateinit var connectButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        urlInput = findViewById(R.id.url_input)
        urlLayout = findViewById(R.id.url_layout)
        connectButton = findViewById(R.id.connect_button)
        progressBar = findViewById(R.id.progress_bar)
        errorText = findViewById(R.id.error_text)

        // Pre-fill if returning from settings to change URL
        TrekPrefs.getServerUrl(this)?.let { url ->
            urlInput.setText(url)
        }

        connectButton.setOnClickListener { attemptConnect() }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                attemptConnect()
                true
            } else {
                false
            }
        }
    }

    private fun attemptConnect() {
        val rawInput = urlInput.text?.toString().orEmpty()
        if (UrlValidator.isEmpty(rawInput)) {
            showError(getString(R.string.setup_error_empty))
            return
        }

        val url = UrlValidator.normalize(rawInput)
        urlInput.setText(url)

        hideKeyboard()
        setLoading(true)
        clearError()

        thread {
            val result = validateUrl(url)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setLoading(false)
                if (result == null) {
                    TrekPrefs.setServerUrl(this, url)
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                } else {
                    showError(result)
                }
            }
        }
    }

    private fun validateUrl(url: String): String? {
        if (!UrlValidator.isValidScheme(url)) {
            return getString(R.string.setup_error_scheme)
        }
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            connection.disconnect()
            if (code in 200..399) {
                null // success
            } else {
                getString(R.string.setup_error_status, code)
            }
        } catch (e: java.net.UnknownHostException) {
            getString(R.string.setup_error_dns)
        } catch (e: java.net.ConnectException) {
            getString(R.string.setup_error_refused)
        } catch (e: javax.net.ssl.SSLException) {
            getString(R.string.setup_error_ssl)
        } catch (e: java.net.SocketTimeoutException) {
            getString(R.string.setup_error_timeout)
        } catch (e: Exception) {
            getString(R.string.setup_error_generic, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        connectButton.isEnabled = !loading
        urlInput.isEnabled = !loading
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun clearError() {
        errorText.text = ""
        errorText.visibility = View.GONE
    }

    private fun hideKeyboard() {
        currentFocus?.let { view ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
