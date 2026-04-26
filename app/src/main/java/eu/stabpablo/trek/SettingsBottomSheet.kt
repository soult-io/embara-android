package eu.stabpablo.trek

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onChangeServer()
        fun onClearCache()
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun getTheme(): Int = R.style.Theme_Trek_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_bottom_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val serverUrl = arguments?.getString(ARG_SERVER_URL) ?: getString(R.string.settings_no_server)
        val appVersion = arguments?.getString(ARG_APP_VERSION) ?: "unknown"

        view.findViewById<TextView>(R.id.sheet_current_url).text = serverUrl
        view.findViewById<TextView>(R.id.sheet_version_value).text = getString(R.string.settings_about_version, appVersion)

        view.findViewById<LinearLayout>(R.id.sheet_change_server).setOnClickListener {
            confirmChangeServer()
        }

        view.findViewById<LinearLayout>(R.id.sheet_clear_cache).setOnClickListener {
            confirmClearCache()
        }
    }

    private fun confirmChangeServer() {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Trek_Dialog)
            .setTitle(R.string.settings_change_server_title)
            .setMessage(R.string.settings_change_server_confirm)
            .setPositiveButton(R.string.settings_change) { _, _ ->
                dismiss()
                listener?.onChangeServer()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Trek_Dialog)
            .setTitle(R.string.settings_clear_cache_title)
            .setMessage(R.string.settings_clear_cache_confirm)
            .setPositiveButton(R.string.settings_clear) { _, _ ->
                dismiss()
                listener?.onClearCache()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val ARG_SERVER_URL = "server_url"
        private const val ARG_APP_VERSION = "app_version"

        fun newInstance(serverUrl: String, appVersion: String): SettingsBottomSheet {
            return SettingsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_URL, serverUrl)
                    putString(ARG_APP_VERSION, appVersion)
                }
            }
        }
    }
}
