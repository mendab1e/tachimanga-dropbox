package eu.kanade.tachiyomi.extension.all.dropbox

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen

/** Builds host-compatible settings and reports library changes through explicit callbacks. */
internal class DropboxPreferences(
    private val screen: PreferenceScreen,
    private val settings: Settings,
    private val api: DropboxApi,
    private val invalidateLibrary: () -> Unit,
    private val refreshLibrary: () -> Unit,
) {
    private var androidSignInPreference: Preference? = null
    private val textPreferences = mutableMapOf<String, EditTextPreference>()

    fun setup() {
        api.signInLink() // Reloading settings must not rotate the manual verifier.
        val androidCallback = AndroidSignIn.available(screen.context)
        addBuildLabel()
        if (BuildConfig.DROPBOX_APP_KEY.isBlank()) {
            addText(SettingKey.APP_KEY, "Dropbox app key", "Public app key from your Dropbox developer app. No app secret is needed.")
        } else {
            addText(SettingKey.APP_KEY, "Custom Dropbox app (optional)", "Leave blank to use the included Dropbox connection. Changing apps requires reconnecting.")
        }
        addText(SettingKey.LIBRARY_PATH, "Library folder", "Default: /Manga. One subfolder per series; CBZ/ZIP files are chapters. Use / for the Dropbox root.")
        addLibraryActions()
        if (androidCallback) addAndroidSignIn()
        addText(SettingKey.AUTHORIZATION_URL, if (androidCallback) "Dropbox sign-in (manual fallback)" else "Dropbox sign-in",
            "Open this automatically prepared link in your browser to authorize Dropbox, then paste the returned code below.", readOnly = true)
        addText(SettingKey.AUTH_CODE, "Dropbox authorization code", "Paste the code from Dropbox. Open the source to finish sign-in; the code is then cleared.", secret = true)
    }

    private fun updateAndroidSignInPreference() {
        val preference = androidSignInPreference ?: return
        val connected = api.isConnected()
        preference.isEnabled = !connected
        preference.summary = if (connected) "Connected. Disconnect to connect another account." else
            "Authorize in your browser and return here automatically."
    }

    private fun addBuildLabel() {
        // The executable source reports its own build, independently of the
        // host's extension-list metadata or any persisted preference value.
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = SettingKey.EXTENSION_BUILD
            title = "Dropbox extension ${BuildConfig.VERSION_NAME}"
            summary = "Installed source build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
            setDefaultValue("${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            isEnabled = false
        })
    }

    private fun addText(key: String, title: String, summary: String, secret: Boolean = false, readOnly: Boolean = false) {
        screen.addPreference(EditTextPreference(screen.context).apply {
            this.key = key
            this.title = title
            this.summary = summary
            dialogTitle = title
            val current = settings.get(key).ifBlank { if (key == SettingKey.LIBRARY_PATH) "/Manga" else "" }
            // Tachimanga's Android compatibility layer has no setPersistent().
            // A typed default is also required when it serializes preferences.
            setDefaultValue(current)
            this.text = current
            textPreferences[key] = this
            if (secret) setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, value ->
                if (readOnly) return@setOnPreferenceChangeListener false
                val text = (value as String).trim()
                if (key == SettingKey.LIBRARY_PATH && runCatching { Library.root(text) }.isFailure) return@setOnPreferenceChangeListener false
                api.updateSetting(key, text)
                if (key == SettingKey.APP_KEY) updateAndroidSignInPreference()
                invalidateLibrary()
                setDefaultValue(text)
                false // Already applied; do not let the preference persist it a second time.
            }
        })
    }

    private fun addLibraryActions() {
        // Tachimanga ignores click listeners on plain Preference rows. List
        // selections reach its supported preference-change endpoint instead.
        screen.addPreference(ListPreference(screen.context).apply {
            key = SettingKey.CONNECTION_ACTION
            title = "Dropbox library actions"
            summary = "Refresh the library or disconnect. Use the sign-in settings below to connect."
            entries = arrayOf("Choose an action", "Refresh Dropbox library", "Disconnect Dropbox")
            entryValues = arrayOf("", "refresh", "disconnect")
            setDefaultValue("")
            setOnPreferenceChangeListener { _, value ->
                when (value as String) {
                    "refresh" -> refreshLibrary()
                    "disconnect" -> {
                        clearConnection()
                        updateAndroidSignInPreference()
                    }
                }
                false // Commands execute once and are never persisted as a selection.
            }
        })
    }

    private fun addAndroidSignIn() {
        screen.addPreference(Preference(screen.context).apply {
            androidSignInPreference = this
            key = SettingKey.ANDROID_SIGN_IN
            title = "Sign in to Dropbox"
            setDefaultValue("")
            updateAndroidSignInPreference()
            setOnPreferenceClickListener {
                AndroidSignIn.start(screen.context, api, ::onConnected)
                true
            }
        })
    }

    private fun onConnected() {
        invalidateLibrary()
        listOf(SettingKey.AUTHORIZATION_URL, SettingKey.AUTH_CODE).forEach { key ->
            textPreferences[key]?.apply {
                text = ""
                setDefaultValue("")
            }
        }
        updateAndroidSignInPreference()
    }

    private fun clearConnection() {
        api.disconnect()
        invalidateLibrary()
    }
}
