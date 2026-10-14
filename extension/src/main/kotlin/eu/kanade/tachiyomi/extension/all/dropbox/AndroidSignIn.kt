package eu.kanade.tachiyomi.extension.all.dropbox

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Keep Android-only APIs behind this gate; Tachimanga uses a JVM compatibility runtime. */
internal object AndroidSignIn {
    fun available(context: Context): Boolean {
        if (System.getProperty("java.vm.name") != "Dalvik") return false
        return runCatching {
            val activity = context.packageManager.getActivityInfo(component(), 0)
            activity.enabled && activity.exported && activity.applicationInfo.enabled
        }.getOrDefault(false)
    }

    private fun component() = ComponentName(BuildConfig.APPLICATION_ID, OAuthContract.ACTIVITY)

    fun start(context: Context, api: DropboxApi, onConnected: () -> Unit) {
        val link = try { api.beginAndroidSignIn() } catch (_: Exception) {
            toast(context, "Unable to start sign-in. Check the app key, or disconnect before changing accounts.")
            return
        }
        val expectedState = link.toHttpUrl().queryParameter("state")!!
        val main = Handler(Looper.getMainLooper())
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultData?.getString(OAuthContract.STATE) != expectedState) return
                if (resultCode != Activity.RESULT_OK) {
                    api.cancelAndroidSignIn(expectedState)
                    toast(context, "Dropbox sign-in canceled. You can also use the manual sign-in link.")
                    return
                }
                val code = resultData.getString(OAuthContract.CODE).orEmpty()
                Thread {
                    try {
                        api.completeAndroidSignIn(expectedState, code)
                        main.post {
                            onConnected()
                            toast(context, "Dropbox connected. Browse the source to read your library.")
                        }
                    } catch (_: Exception) {
                        main.post { toast(context, "Dropbox sign-in failed or expired. Try again or use the manual sign-in link.") }
                    }
                }.start()
            }
        }
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val returnToReader = launch?.let {
                PendingIntent.getActivity(context, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            context.startActivity(Intent(OAuthContract.START).setComponent(component())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(OAuthContract.URL, link).putExtra(OAuthContract.RECEIVER, receiver)
                .putExtra(OAuthContract.RETURN_TO_READER, returnToReader))
        } catch (_: Exception) {
            api.cancelAndroidSignIn(expectedState)
            toast(context, "Unable to open Dropbox sign-in. Use the manual sign-in link below.")
        }
    }

    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
