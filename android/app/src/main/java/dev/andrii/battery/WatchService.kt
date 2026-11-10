package dev.andrii.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.text.format.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val STATUS_CHANNEL = "status"
private const val ALERT_CHANNEL = "alerts"
private const val STATUS_ID = 1
private const val ALERT_ID = 2

/** Charge levels that sound an alert, lowest first. */
private val LOW_LEVELS = listOf(10, 20)

/**
 * Keeps [Monitor] polling while the app is closed, shows the charge in an
 * ongoing notification, and sounds an alert as the charge falls past each of
 * [LOW_LEVELS]. Runs while the "Alert me when low" switch is on.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val alerted = mutableSetOf<Int>()
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val openApp by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Battery status", NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Low battery alerts", NotificationManager.IMPORTANCE_HIGH),
        )
        Monitor.watching.value = true
        Monitor.ensureRunning(this)
        // A different battery gets its low alerts afresh.
        scope.launch { Monitor.current.collect { alerted.clear() } }
        scope.launch {
            Monitor.state.collect { state ->
                notifications.notify(STATUS_ID, statusNotification(state))
                state.reading?.takeIf { state.problem == null }?.let(::alertIfLow)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        startForeground(STATUS_ID, statusNotification(Monitor.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // Not sticky: a restart from the background may not start a foreground
        // service; MainActivity starts it again on the next launch instead.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Monitor.watching.value = false
        scope.cancel()
        super.onDestroy()
    }

    private fun statusNotification(state: MonitorState): Notification {
        val reading = state.reading
        val (title, text) = when {
            reading == null -> "Looking for the battery…" to state.problem
            state.problem != null -> "Can't reach the battery" to
                "Last reading at ${DateUtils.formatDateTime(this, reading.at, DateUtils.FORMAT_SHOW_TIME)}: ${reading.status.soc}%"
            else -> "${reading.status.soc}%, ${summary(reading)}" to flowText(reading)
        }
        return Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun alertIfLow(reading: Reading) {
        val soc = reading.status.soc
        if (soc >= LOW_LEVELS.max() + 5) alerted.clear() // charged back up: alert again on the next discharge
        if (reading.mode == Mode.Charging) return
        val level = LOW_LEVELS.firstOrNull { soc <= it && it !in alerted } ?: return
        alerted += LOW_LEVELS.filter { it >= level }
        val left = reading.minutes?.let { "About ${formatMinutes(it)} left at ${reading.watts} W." } ?: "Charge it soon."
        notifications.notify(
            ALERT_ID,
            Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Battery low: $soc%")
                .setContentText(left)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }
}
