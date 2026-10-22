package com.secureguard.mdm.remote

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import com.secureguard.mdm.data.local.PreferencesManager
import com.secureguard.mdm.data.model.PresetConfig
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.features.registry.FeatureRegistry
import com.secureguard.mdm.kiosk.manager.KioskLockManager
import com.secureguard.mdm.kiosk.ui.KioskActivity
import com.secureguard.mdm.security.PasswordManager
import com.secureguard.mdm.utils.AppLogger
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * מחיל הגדרות שנשלחו מהפורטל. ההגדרות הן באותו מבנה של PresetConfig,
 * אבל בניגוד ל-PresetReceiver (שמיועד להגדרה ראשונית בלבד) כאן מוחלים רק המפתחות שנשלחו בפועל,
 * וניתן להפעיל ולכבות את הקיוסק שוב ושוב.
 */
@Singleton
class RemoteConfigApplier @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val passwordManager: PasswordManager,
    private val kioskLockManager: KioskLockManager,
    private val preferencesManager: PreferencesManager
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun apply(context: Context, raw: JSONObject) {
        val config = json.decodeFromString<PresetConfig>(raw.toString())
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, SecureGuardDeviceAdminReceiver::class.java)
        val isOwner = dpm.isDeviceOwnerApp(context.packageName)
        fun has(key: String) = raw.has(key) && !raw.isNull(key)

        if (has("adminPassword")) config.adminPassword?.takeIf { it.isNotEmpty() }?.let {
            passwordManager.createAndSavePassword(it)
        }

        if (isOwner) {
            config.features.forEach { (featureId, enabled) ->
                val feature = FeatureRegistry.allFeatures.find { it.id == featureId }
                if (feature == null) {
                    AppLogger.w(TAG, "Unknown feature: $featureId")
                    return@forEach
                }
                runCatching {
                    feature.applyPolicy(context, dpm, admin, enabled)
                    settingsRepository.setFeatureState(featureId, enabled)
                }.onFailure { AppLogger.e(TAG, "Failed to apply feature $featureId", it) }
            }

            if (has("blockedApps")) config.blockedApps?.let { packages ->
                val previous = settingsRepository.getBlockedAppPackages()
                (previous - packages).forEach { runCatching { dpm.setApplicationHidden(admin, it, false) } }
                packages.forEach { runCatching { dpm.setApplicationHidden(admin, it, true) } }
                settingsRepository.setBlockedAppPackages(packages)
            }

            if (has("suspendedApps")) config.suspendedApps?.let { packages ->
                val previous = settingsRepository.getSuspendedAppPackages()
                runCatching {
                    val released = previous - packages
                    if (released.isNotEmpty()) dpm.setPackagesSuspended(admin, released.toTypedArray(), false)
                    if (packages.isNotEmpty()) dpm.setPackagesSuspended(admin, packages.toTypedArray(), true)
                }.onFailure { AppLogger.e(TAG, "Failed to apply suspended apps", it) }
                settingsRepository.setSuspendedAppPackages(packages)
            }
        }

        if (has("customFrpIds")) config.customFrpIds?.let { settingsRepository.setCustomFrpIds(it) }
        if (has("updateChannel")) config.updateChannel?.let {
            preferencesManager.saveString(PreferencesManager.KEY_UPDATE_CHANNEL, it)
        }
        if (has("autoUpdateCheckEnabled")) config.autoUpdateCheckEnabled?.let { settingsRepository.setAutoUpdateCheckEnabled(it) }

        // הגדרות קיוסק
        config.kioskApps?.let { settingsRepository.setKioskAppPackages(it.toSet()) }
        config.kioskTitle?.let { settingsRepository.setKioskTitle(it) }
        config.kioskBackgroundColor?.let { settingsRepository.setKioskBackgroundColor(it) }
        config.kioskPrimaryColor?.let { settingsRepository.setKioskPrimaryColor(it) }
        config.kioskShowUpdate?.let { settingsRepository.setShouldShowKioskSecureUpdate(it) }
        config.kioskActionButtons?.let { settingsRepository.setKioskActionButtons(it) }
        config.kioskLayoutJson?.let { settingsRepository.setKioskLayoutJson(it) }
        config.kioskBlockedLauncherPackage?.let { settingsRepository.setKioskBlockedLauncherPackage(it) }
        config.kioskSettingsInLockTaskEnabled?.let { settingsRepository.setKioskSettingsInLockTaskEnabled(it) }
        config.kioskAppMonitorEnabled?.let { settingsRepository.setKioskAppMonitorEnabled(it) }
        config.kioskSingleWebsiteEnabled?.let { settingsRepository.setSingleWebsiteKioskEnabled(it) }
        config.kioskSingleWebsiteUrl?.let { settingsRepository.setSingleWebsiteKioskUrl(it) }
        config.kioskSingleWebsiteWhitelist?.let { settingsRepository.setSingleWebsiteKioskWhitelist(it) }

        val kioskWanted = if (has("kioskEnabled")) config.kioskEnabled else settingsRepository.isKioskModeEnabled()
        if (isOwner) {
            if (kioskWanted) {
                settingsRepository.setKioskModeEnabled(true)
                kioskLockManager.setLockTaskPackages()
                kioskLockManager.enableKioskModeFeatures()
                kioskLockManager.setKioskAsDefaultLauncher(settingsRepository.isKioskAppMonitorEnabled())
                // הפעלה מחדש של מסך הקיוסק כדי שיטען את ההגדרות החדשות
                context.startActivity(Intent(context, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
            } else if (has("kioskEnabled") && settingsRepository.isKioskModeEnabled()) {
                kioskLockManager.clearLockTaskPackages()
                kioskLockManager.disableKioskModeFeatures()
                val originalLauncher = settingsRepository.getChosenHomeLauncherPackage()
                if (originalLauncher != null) kioskLockManager.setSpecificAsDefaultLauncher(originalLauncher)
                else kioskLockManager.clearKioskAsDefaultLauncher()
                settingsRepository.setKioskModeEnabled(false)
            }
        }
        AppLogger.i(TAG, "Remote config applied (${raw.length()} keys)")
    }

    /** תמונת מצב של ההגדרות הנוכחיות במכשיר, באותו מבנה של PresetConfig (ללא סיסמה). */
    suspend fun snapshot(): JSONObject = JSONObject().apply {
        put("features", JSONObject().apply {
            FeatureRegistry.allFeatures.forEach { put(it.id, settingsRepository.getFeatureState(it.id)) }
        })
        put("blockedApps", JSONArray(settingsRepository.getBlockedAppPackages().toList()))
        put("suspendedApps", JSONArray(settingsRepository.getSuspendedAppPackages().toList()))
        put("customFrpIds", JSONArray(settingsRepository.getCustomFrpIds().toList()))
        put("autoUpdateCheckEnabled", settingsRepository.isAutoUpdateCheckEnabled())
        put("kioskEnabled", settingsRepository.isKioskModeEnabled())
        put("kioskApps", JSONArray(settingsRepository.getKioskAppPackages().toList()))
        put("kioskTitle", settingsRepository.getKioskTitle())
        put("kioskBackgroundColor", settingsRepository.getKioskBackgroundColor())
        put("kioskPrimaryColor", settingsRepository.getKioskPrimaryColor())
        put("kioskActionButtons", JSONArray(settingsRepository.getKioskActionButtons().toList()))
        settingsRepository.getKioskLayoutJson()?.let { put("kioskLayoutJson", it) }
        settingsRepository.getKioskBlockedLauncherPackage()?.let { put("kioskBlockedLauncherPackage", it) }
        put("kioskSettingsInLockTaskEnabled", settingsRepository.isKioskSettingsInLockTaskEnabled())
        put("kioskAppMonitorEnabled", settingsRepository.isKioskAppMonitorEnabled())
        put("kioskSingleWebsiteEnabled", settingsRepository.isSingleWebsiteKioskEnabled())
        put("kioskSingleWebsiteUrl", settingsRepository.getSingleWebsiteKioskUrl())
        put("kioskSingleWebsiteWhitelist", settingsRepository.getSingleWebsiteKioskWhitelist())
    }

    companion object {
        private const val TAG = "RemoteConfigApplier"
    }
}
