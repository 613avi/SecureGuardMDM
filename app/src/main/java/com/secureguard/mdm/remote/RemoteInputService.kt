package com.secureguard.mdm.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.secureguard.mdm.utils.AppLogger
import java.util.concurrent.Executors

/**
 * שירות נגישות שמשמש את השליטה מרחוק:
 * - הזרקת מגע (הקשה, החלקה, לחיצה ארוכה) ומקשי מערכת (חזרה, בית וכו').
 * - צילום מסך בלי אישור משתמש (API 30+), למצב "ללא אישור".
 */
class RemoteInputService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screenSize(): Pair<Int, Int> {
        val metrics = RemoteScreenInfo.realMetrics(this)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun dispatch(path: Path, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 59_000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(nx: Double, ny: Double) {
        val (w, h) = screenSize()
        dispatch(Path().apply { moveTo((nx * w).toFloat(), (ny * h).toFloat()) }, 50)
    }

    fun longPress(nx: Double, ny: Double, durationMs: Long) {
        val (w, h) = screenSize()
        dispatch(Path().apply { moveTo((nx * w).toFloat(), (ny * h).toFloat()) }, durationMs.coerceAtLeast(600))
    }

    fun swipe(nx: Double, ny: Double, nx2: Double, ny2: Double, durationMs: Long) {
        val (w, h) = screenSize()
        val path = Path().apply {
            moveTo((nx * w).toFloat(), (ny * h).toFloat())
            lineTo((nx2 * w).toFloat(), (ny2 * h).toFloat())
        }
        dispatch(path, durationMs.coerceIn(50, 3000))
    }

    fun key(key: String): Boolean {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    /** מוסיף טקסט לסוף השדה שבפוקוס. */
    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val current = node.text?.toString().orEmpty()
        val newText = if (node.isShowingHintTextCompat()) text else current + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun AccessibilityNodeInfo.isShowingHintTextCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isShowingHintText

    /** צילום מסך דרך הנגישות. המערכת מגבילה לצילום אחד בערך כל 333ms. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    onResult(soft)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null)
                }
            })
        } catch (e: Exception) {
            AppLogger.e(TAG, "takeScreenshot failed", e)
            onResult(null)
        }
    }

    companion object {
        private const val TAG = "RemoteInputService"

        @Volatile
        var instance: RemoteInputService? = null
            private set

        fun componentName(context: Context) = ComponentName(context, RemoteInputService::class.java)

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == componentName(context) }
        }

        /**
         * מפעיל את השירות בלי מגע יד אדם. דורש הרשאת WRITE_SECURE_SETTINGS, שניתנת פעם אחת בהתקנה:
         *   adb shell pm grant com.secureguard.mdm android.permission.WRITE_SECURE_SETTINGS
         */
        fun ensureEnabled(context: Context): Boolean {
            if (isEnabled(context)) return true
            return try {
                val resolver = context.contentResolver
                val existing = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.takeIf { it.isNotBlank() }
                val mine = componentName(context).flattenToString()
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (existing == null) mine else "$existing:$mine"
                )
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                AppLogger.i(TAG, "Accessibility service enabled via secure settings")
                true
            } catch (e: SecurityException) {
                AppLogger.w(TAG, "No WRITE_SECURE_SETTINGS; accessibility service must be enabled manually")
                false
            }
        }
    }
}
