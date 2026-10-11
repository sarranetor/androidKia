package it.ehimacchina.obd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tiene vivo il processo mentre guidi (con la notifica fissa). La lettura vera la fa [ObdEngine]:
 * il servizio si ferma da solo quando l'engine si ferma.
 */
class ObdService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ObdEngine.attach(this)
        if (intent?.action == ACTION_STOP) {
            ObdEngine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(ObdEngine.state.value.status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Se Android rifiuta il servizio in primo piano (permessi mancanti) l'app funziona
            // comunque finché resta aperta; non facciamo crashare niente.
            stopSelf()
            return START_NOT_STICKY
        }
        watcher?.cancel()
        watcher = scope.launch {
            ObdEngine.state.map { it.running to it.status }.distinctUntilChanged().collect { (running, status) ->
                if (!running) {
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lettura OBD", NotificationManager.IMPORTANCE_LOW),
        )
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Ehi macchina")
            .setContentText(status)
            .setContentIntent(open)
            .addAction(0, "Disconnetti", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "it.ehimacchina.obd.STOP"
        private const val CHANNEL_ID = "obd"
        private const val NOTIFICATION_ID = 1
    }
}
