package eu.kanade.tachiyomi.extension.all.dropbox

internal interface Settings {
    fun get(key: String): String
    fun put(values: Map<String, String>)
}

/** Persisted names are part of the host settings contract; keep their values stable. */
internal object SettingKey {
    const val APP_KEY = "app_key"
    const val LIBRARY_PATH = "library_path"
    const val ACCESS_TOKEN = "access_token"
    const val REFRESH_TOKEN = "refresh_token"
    const val EXPIRES_AT = "expires_at"
    const val AUTH_CODE = "auth_code"
    const val VERIFIER = "verifier"
    const val AUTHORIZATION_URL = "authorization_url"
    const val CALLBACK_STATE = "callback_state"
    const val CALLBACK_VERIFIER = "callback_verifier"
    const val CALLBACK_EXPIRES_AT = "callback_expires_at"
    const val CALLBACK_CLAIM = "callback_claim"
    const val EXTENSION_BUILD = "extension_build"
    const val CONNECTION_ACTION = "connection_action"
    const val ANDROID_SIGN_IN = "android_sign_in"
}
