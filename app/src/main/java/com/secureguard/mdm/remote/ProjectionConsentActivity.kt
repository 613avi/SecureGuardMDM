package com.secureguard.mdm.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.secureguard.mdm.utils.AppLogger

/**
 * Transparent activity that shows the system MediaProjection consent prompt and
 * hands the granted token back to [RemoteAgentService]. Used for attended
 * support (a person is next to the tablet to approve the screen share).
 */
class ProjectionConsentActivity : Activity() {

    private lateinit var manager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            AppLogger.i(TAG, "Projection consent granted")
            val intent = Intent(this, RemoteAgentService::class.java).apply {
                action = RemoteAgentService.ACTION_PROJECTION_GRANTED
                putExtra(RemoteAgentService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RemoteAgentService.EXTRA_RESULT_DATA, data)
            }
            startService(intent)
        } else {
            AppLogger.w(TAG, "Projection consent denied")
            startService(Intent(this, RemoteAgentService::class.java).apply {
                action = RemoteAgentService.ACTION_PROJECTION_DENIED
            })
        }
        finish()
    }

    companion object {
        private const val TAG = "ProjectionConsent"
        private const val REQUEST_CODE = 7001

        fun launch(context: Context) {
            context.startActivity(Intent(context, ProjectionConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
