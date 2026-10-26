package io.github.divanguz_alt.zenit.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.divanguz_alt.zenit.MainActivity
import io.github.divanguz_alt.zenit.R

/**
 * Foreground service (type location) that runs only while Zenit is open in Android Auto.
 * The Car App Library treats the car screen as background for location, so without it GNSS
 * updates stop once the phone locks. It holds no state; the car session does the listening
 * in the same process.
 */
class CarLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Android Auto", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Zenit is open on the car screen"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zenit)
            .setContentTitle("Zenit is on the car screen")
            .setContentText("Using GPS to show the satellites above you")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // E.g. location permission revoked: run without it rather than crash the car session.
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "CarLocationService"
        private const val CHANNEL = "car"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            // Without location permission startForeground(type=location) would throw after the
            // service was already promised to be foreground; don't get that far.
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, CarLocationService::class.java))
            } catch (e: Exception) {
                // Some OEMs refuse background starts; location then works while the phone is unlocked.
                Log.w(TAG, "could not start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarLocationService::class.java))
        }
    }
}
