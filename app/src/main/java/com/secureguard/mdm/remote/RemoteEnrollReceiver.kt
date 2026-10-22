package com.secureguard.mdm.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.secureguard.mdm.utils.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * רישום לפורטל דרך ADB:
 *   adb shell am broadcast -a com.secureguard.mdm.ACTION_REMOTE_ENROLL \
 *     -n com.secureguard.mdm/.remote.RemoteEnrollReceiver --es server https://portal.example.com --es code 12345678
 *
 * הרסיבר מוגן בהרשאת DUMP, שרק ה-shell והמערכת מחזיקים, כך שאפליקציה אחרת לא יכולה להפנות את הטאבלט לשרת זר.
 */
@AndroidEntryPoint
class RemoteEnrollReceiver : BroadcastReceiver() {

    @Inject
    lateinit var enrollment: RemoteEnrollment

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMOTE_ENROLL) return
        val server = intent.getStringExtra("server")
        val code = intent.getStringExtra("code")
        if (server.isNullOrBlank() || code.isNullOrBlank()) {
            AppLogger.e(TAG, "Missing server or code extra")
            resultCode = 2
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                enrollment.enroll(context, server, code)
                    .onSuccess { tabletId ->
                        AppLogger.i(TAG, "Enrolled as tablet $tabletId")
                        pending.resultCode = 0
                        pending.resultData = "enrolled:$tabletId"
                        RemoteAgentService.start(context)
                    }
                    .onFailure { e ->
                        AppLogger.e(TAG, "Enrollment failed", e)
                        pending.resultCode = 1
                        pending.resultData = "error:${e.message}"
                    }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "RemoteEnrollReceiver"
        const val ACTION_REMOTE_ENROLL = "com.secureguard.mdm.ACTION_REMOTE_ENROLL"
    }
}
