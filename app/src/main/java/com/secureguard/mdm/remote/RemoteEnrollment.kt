package com.secureguard.mdm.remote

import android.content.Context
import android.os.Build
import com.secureguard.mdm.data.local.PreferencesManager
import com.secureguard.mdm.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * רישום הטאבלט מול הפורטל באמצעות קוד רישום חד-פעמי.
 * הפורטל מחזיר טוקן קבוע שמשמש את סוכן השליטה מרחוק להתחברות.
 *
 * משתמש ב-OkHttp עם אימות TLS רגיל (ולא ב-HttpsURLConnection, שהאפליקציה מבטלת בו את בדיקת התעודות),
 * כי הטוקן מעניק שליטה מלאה על המכשיר.
 */
@Singleton
class RemoteEnrollment @Inject constructor(
    private val preferencesManager: PreferencesManager
) {
    val serverUrl: String?
        get() = preferencesManager.loadString(PreferencesManager.KEY_REMOTE_SERVER_URL, null)

    val token: String?
        get() = preferencesManager.loadString(PreferencesManager.KEY_REMOTE_TOKEN, null)

    val tabletId: Int
        get() = preferencesManager.loadInt(PreferencesManager.KEY_REMOTE_TABLET_ID, 0)

    var configVersion: Int
        get() = preferencesManager.loadInt(PreferencesManager.KEY_REMOTE_CONFIG_VERSION, 0)
        set(value) = preferencesManager.saveInt(PreferencesManager.KEY_REMOTE_CONFIG_VERSION, value)

    val isEnrolled: Boolean
        get() = !serverUrl.isNullOrBlank() && !token.isNullOrBlank()

    suspend fun enroll(context: Context, rawServerUrl: String, code: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val server = normalizeServerUrl(rawServerUrl)
            val body = JSONObject().apply {
                put("code", code.trim())
                put("serial", deviceSerial(context))
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("androidVersion", Build.VERSION.RELEASE)
                put("appVersion", appVersion(context))
            }
            val request = Request.Builder()
                .url("$server/api/tablet-agent/enroll")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful || json?.optBoolean("success") != true) {
                    throw IllegalStateException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP ${response.code}")
                }
                val data = json.getJSONObject("data")
                val tabletId = data.getInt("tabletId")
                preferencesManager.saveString(PreferencesManager.KEY_REMOTE_SERVER_URL, server)
                preferencesManager.saveString(PreferencesManager.KEY_REMOTE_TOKEN, data.getString("token"))
                preferencesManager.saveInt(PreferencesManager.KEY_REMOTE_TABLET_ID, tabletId)
                preferencesManager.saveInt(PreferencesManager.KEY_REMOTE_CONFIG_VERSION, 0)
                AppLogger.i(TAG, "Enrolled as tablet $tabletId on $server")
                tabletId
            }
        }
    }

    fun unenroll() {
        preferencesManager.saveString(PreferencesManager.KEY_REMOTE_TOKEN, null)
        preferencesManager.saveInt(PreferencesManager.KEY_REMOTE_TABLET_ID, 0)
    }

    companion object {
        private const val TAG = "RemoteEnrollment"

        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // websocket
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        fun normalizeServerUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s.removeSuffix("/api")
        }

        @Suppress("DEPRECATION", "HardwareIds")
        fun deviceSerial(context: Context): String {
            val serial = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
            }.getOrNull()
            if (!serial.isNullOrBlank() && serial != Build.UNKNOWN) return serial
            return android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        }

        fun appVersion(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
}
