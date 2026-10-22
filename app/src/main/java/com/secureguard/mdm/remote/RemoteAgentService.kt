package com.secureguard.mdm.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.secureguard.mdm.R
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.utils.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import javax.inject.Inject

/**
 * Foreground service that keeps a persistent socket.io connection to the portal so
 * support staff can read status, push configuration, run commands, and (with the
 * appropriate screen backend) see and drive the tablet. Runs only when the tablet
 * has been enrolled to a portal.
 */
@AndroidEntryPoint
class RemoteAgentService : Service() {

    @Inject lateinit var enrollment: RemoteEnrollment
    @Inject lateinit var configApplier: RemoteConfigApplier
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var socket: Socket? = null
    private var streamer: ScreenStreamer? = null
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROJECTION_GRANTED -> {
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_OK)
                if (data != null) startProjectionStream(code, data)
            }
            ACTION_PROJECTION_DENIED -> {
                emitScreenState("error", "consent_denied")
            }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        if (socket == null) connect()
        return START_STICKY
    }

    private fun connect() {
        val server = enrollment.serverUrl
        val token = enrollment.token
        if (server.isNullOrBlank() || token.isNullOrBlank()) {
            AppLogger.w(TAG, "Not enrolled; stopping agent")
            stopSelf(); return
        }
        try {
            val opts = IO.Options().apply {
                path = "/socket.io"
                transports = arrayOf("websocket")
                reconnection = true
                reconnectionDelay = 2000
                reconnectionDelayMax = 15000
                auth = mapOf("token" to token)
            }
            val sock = IO.socket(URI.create("$server/tablet-agent"), opts)
            wire(sock)
            socket = sock
            sock.connect()
            AppLogger.i(TAG, "Connecting to $server/tablet-agent")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create socket", e)
            stopSelf()
        }
    }

    private fun wire(sock: Socket) {
        sock.on(Socket.EVENT_CONNECT) {
            AppLogger.i(TAG, "Connected")
            sendStatus()
            scope.launch { reportConfig() }
            startStatusLoop()
        }
        sock.on(Socket.EVENT_DISCONNECT) {
            AppLogger.w(TAG, "Disconnected")
            statusJob?.cancel()
            streamer?.stop()
        }
        sock.on("config:apply") { args -> onConfigApply(args) }
        sock.on("command") { args -> onCommand(args) }
        sock.on("screen:start") { args -> onScreenStart(args) }
        sock.on("screen:stop") { streamer?.stop(); emitScreenState("stopped", null) }
        sock.on("input") { args -> onInput(args) }
    }

    // ---- config ----

    private fun onConfigApply(args: Array<out Any>) {
        val obj = args.firstOrNull() as? JSONObject ?: return
        val version = obj.optInt("version", -1)
        val config = obj.optJSONObject("config") ?: return
        scope.launch {
            runCatching { configApplier.apply(applicationContext, config) }
                .onFailure { AppLogger.e(TAG, "apply config failed", it) }
            if (version >= 0) enrollment.configVersion = version
            reportConfig(version)
        }
    }

    private suspend fun reportConfig(version: Int = -1) {
        val snapshot = runCatching { configApplier.snapshot() }.getOrNull() ?: return
        val payload = JSONObject().apply {
            put("version", if (version >= 0) version else enrollment.configVersion)
            put("config", snapshot)
        }
        socket?.emit("config:report", payload)
    }

    // ---- commands ----

    private fun onCommand(args: Array<out Any>) {
        val obj = args.firstOrNull() as? JSONObject ?: return
        val requestId = obj.optString("requestId")
        val type = obj.optString("type")
        scope.launch {
            val (ok, message) = runCatching { runCommand(type) }.getOrElse { false to (it.message ?: "error") }
            socket?.emit("command:result", JSONObject().apply {
                put("requestId", requestId)
                put("ok", ok)
                put("message", message)
            })
        }
    }

    private suspend fun runCommand(type: String): Pair<Boolean, String> {
        val svc = RemoteInputService.instance
        return when (type) {
            "request_status" -> { sendStatus(); true to "ok" }
            "reboot" -> {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminC = android.content.ComponentName(this, com.secureguard.mdm.SecureGuardDeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(packageName)) { dpm.reboot(adminC); true to "rebooting" } else false to "not_owner"
            }
            "lock_screen" -> { svc?.key("lock_screen"); (svc != null) to if (svc != null) "ok" else "no_accessibility" }
            "restart_kiosk" -> {
                startActivity(Intent(this, com.secureguard.mdm.kiosk.ui.KioskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                true to "ok"
            }
            "reload_website" -> { KioskWebEvents.emit(KioskWebEvents.Event.RELOAD); true to "ok" }
            "clear_webview" -> { KioskWebEvents.emit(KioskWebEvents.Event.CLEAR_DATA); true to "ok" }
            else -> false to "unknown_command"
        }
    }

    // ---- screen ----

    private fun onScreenStart(args: Array<out Any>) {
        val obj = args.firstOrNull() as? JSONObject ?: return
        val requested = obj.optString("mode", "auto")
        val maxWidth = obj.optInt("maxWidth", 720)
        val quality = obj.optInt("quality", 50)
        val fps = obj.optInt("fps", 4)
        ensureStreamer()
        val useAccessibility = (requested == "accessibility") ||
            (requested == "auto" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && RemoteInputService.instance != null)
        if (useAccessibility) {
            streamer?.startAccessibility(maxWidth, quality, fps)
        } else {
            pendingStreamParams = Triple(maxWidth, quality, fps)
            emitScreenState("waiting_consent", "awaiting_user")
            ProjectionConsentActivity.launch(this)
        }
    }

    private var pendingStreamParams: Triple<Int, Int, Int>? = null

    private fun startProjectionStream(resultCode: Int, data: Intent) {
        // On Android 10+ the FGS holding a projection must run as a mediaProjection-typed
        // foreground service; re-assert the type now that consent is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }.onFailure { AppLogger.e(TAG, "startForeground(mediaProjection) failed", it) }
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp: MediaProjection = mgr.getMediaProjection(resultCode, data)
            ?: run { emitScreenState("error", "projection_null"); return }
        ensureStreamer()
        val (w, q, f) = pendingStreamParams ?: Triple(720, 50, 4)
        streamer?.startProjection(mp, w, q, f)
    }

    private fun ensureStreamer() {
        if (streamer == null) {
            streamer = ScreenStreamer(
                context = this,
                scope = scope,
                onFrame = { bytes, width, height, seq ->
                    val meta = JSONObject().apply {
                        put("w", width); put("h", height); put("seq", seq); put("mode", streamer?.mode ?: "auto")
                    }
                    socket?.emit("screen:frame", meta, bytes)
                },
                onState = { state, message -> emitScreenState(state, message) }
            )
        }
    }

    private fun emitScreenState(state: String, message: String?) {
        socket?.emit("screen:state", JSONObject().apply {
            put("state", state); put("mode", streamer?.mode ?: "auto"); message?.let { put("message", it) }
        })
    }

    // ---- input ----

    private fun onInput(args: Array<out Any>) {
        val obj = args.firstOrNull() as? JSONObject ?: return
        val svc = RemoteInputService.instance ?: run { emitScreenState("error", "no_accessibility"); return }
        when (obj.optString("type")) {
            "tap" -> svc.tap(obj.optDouble("x"), obj.optDouble("y"))
            "long_press" -> svc.longPress(obj.optDouble("x"), obj.optDouble("y"), obj.optLong("durationMs", 700))
            "swipe" -> svc.swipe(obj.optDouble("x"), obj.optDouble("y"), obj.optDouble("x2"), obj.optDouble("y2"), obj.optLong("durationMs", 300))
            "key" -> svc.key(obj.optString("key"))
            "text" -> svc.typeText(obj.optString("text"))
        }
    }

    // ---- status ----

    private fun startStatusLoop() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (socket?.connected() == true) {
                sendStatus()
                delay(20_000)
            }
        }
    }

    private fun sendStatus() {
        scope.launch {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val metrics = RemoteScreenInfo.realMetrics(this@RemoteAgentService)
            val status = JSONObject().apply {
                put("battery", battery)
                put("charging", charging)
                put("appVersion", RemoteEnrollment.appVersion(this@RemoteAgentService))
                put("androidVersion", Build.VERSION.RELEASE)
                put("model", Build.MODEL)
                put("kioskEnabled", settingsRepository.isKioskModeEnabled())
                put("singleWebsiteEnabled", settingsRepository.isSingleWebsiteKioskEnabled())
                put("url", settingsRepository.getSingleWebsiteKioskUrl())
                put("screenWidth", metrics.widthPixels)
                put("screenHeight", metrics.heightPixels)
                put("accessibilityEnabled", RemoteInputService.isEnabled(this@RemoteAgentService))
                put("projectionSupported", true)
            }
            socket?.emit("status", status)
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Remote management", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Remote management active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        statusJob?.cancel()
        streamer?.stop()
        socket?.disconnect(); socket?.off(); socket = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RemoteAgentService"
        private const val CHANNEL_ID = "remote_agent"
        private const val NOTIF_ID = 42
        private const val Activity_RESULT_OK = -1

        const val ACTION_PROJECTION_GRANTED = "com.secureguard.mdm.remote.PROJECTION_GRANTED"
        const val ACTION_PROJECTION_DENIED = "com.secureguard.mdm.remote.PROJECTION_DENIED"
        const val ACTION_STOP = "com.secureguard.mdm.remote.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context) {
            val intent = Intent(context, RemoteAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
