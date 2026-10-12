package jr.brian.thorhud.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import jr.brian.thorhud.R
import javax.inject.Inject

/**
 * Owns the MediaProjection session. Android only allows screen capture
 * from a foreground service of type `mediaProjection`, and the service
 * must be in the foreground *before* getMediaProjection() is called.
 */
@AndroidEntryPoint
class MirrorService : Service() {

    @Inject lateinit var mirror: ScreenMirror

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            mirror.onProjectionFailed()
            stopSelf()
            return START_NOT_STICKY
        }

        val projection = try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not start screen capture", e)
            null
        }
        if (projection == null) {
            mirror.onProjectionFailed()
            stopSelf()
            return START_NOT_STICKY
        }

        mirror.onProjectionStarted(projection)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mirror.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mirror_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle(getString(R.string.mirror_notification_title))
            .setContentText(getString(R.string.mirror_notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "map_mirror"
        private const val NOTIFICATION_ID = 4701
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }
}
