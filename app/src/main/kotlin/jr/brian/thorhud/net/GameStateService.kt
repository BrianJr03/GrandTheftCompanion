package jr.brian.thorhud.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import jr.brian.thorhud.R
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Long-lived foreground service that owns the TCP connection to ThorBridge
 * and keeps the companion app subscribed even when the user has swiped it
 * away. The service's lifecycle matches the HUD session: start when the
 * user wants the HUD visible on the bottom screen, stop when they dismiss.
 */
@AndroidEntryPoint
class GameStateService : LifecycleService() {

    @Inject lateinit var client: GameStateClient
    @Inject lateinit var repository: GameStateRepository

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        lifecycleScope.launch {
            client.frames().collect { frame ->
                repository.update(frame)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "thorhud_game_state"
        private const val NOTIFICATION_ID = 1
    }
}
