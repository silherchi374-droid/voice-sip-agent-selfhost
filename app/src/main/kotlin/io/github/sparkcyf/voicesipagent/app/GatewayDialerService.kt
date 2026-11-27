package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import io.github.sparkcyf.voicesipagent.telephony.GatewayInCallService
import io.github.sparkcyf.voicesipagent.telephony.telecomState

/**
 * The role-qualified UI service shares call ownership with companion mode.
 *
 * This gateway is meant to run headless on a phone left on a charger: it NEVER shows a
 * full-screen call UI, never wakes the screen and never rings. A gateway call is announced
 * only by a small heads-up banner (this notification) with a Hang up action; tapping the
 * banner opens the dialer activity, but nothing opens by itself.
 */
class GatewayDialerService : GatewayInCallService() {
    override val isDialerService = true
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = updateNotification()
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        updateNotification()
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let(call::unregisterCallback)
        super.onCallRemoved(call)
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            // Hang up from the notification's action: disconnect every live Telecom call; the
            // bridge sees the cellular leg end and hangs up the SIP side too.
            calls.filter { it.telecomState != Call.STATE_DISCONNECTED }
                .forEach { runCatching { it.disconnect() } }
            updateNotification()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Telecom asks the in-call UI to come forward (e.g. when a call is placed). The gateway
     * stays headless: ignore the request. The user opens the app from the notification or the
     * launcher if they want to see it.
     */
    override fun onBringToForeground(showDialpad: Boolean) {
        AppLog.i("gateway stays headless: ignoring bring-to-foreground")
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val live = calls.filter { it.telecomState != Call.STATE_DISCONNECTED }
        if (live.isEmpty()) { nm.cancel(ID); return }
        // Silent channel: a gateway call must not ring, vibrate or play a notification sound.
        // A fresh channel id (the old "cellular_calls" channel keeps its old sound settings on
        // already-installed apps) — the old channel is removed below.
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        val channel = NotificationChannel(CHANNEL, "Gateway call", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
        val first = live.first()
        val number = first.details.handle?.schemeSpecificPart.orEmpty().ifEmpty { "unknown number" }
        val incoming = live.any { it.telecomState == Call.STATE_RINGING }
        val open = PendingIntent.getActivity(this, ID, Intent(this, GatewayDialerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hangup = PendingIntent.getService(this, ID, Intent(this, GatewayDialerService::class.java)
            .setAction(ACTION_HANGUP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle(if (incoming) "Incoming call from $number" else "Gateway call: $number")
            .setContentText(if (incoming) "Ringing on the iPhone — tap to open the gateway" else "Bridged to the iPhone")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hang up", hangup).build())
        // No full-screen intent: the banner must never wake the screen or open the activity.
        runCatching { nm.notify(ID, notification.build()) }
            .onFailure { AppLog.w("Could not post call notification", it) }
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        getSystemService(NotificationManager::class.java).cancel(ID)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "gateway_calls"
        private const val OLD_CHANNEL = "cellular_calls"
        private const val ID = 2
        private const val ACTION_HANGUP = "io.github.sparkcyf.voicesipagent.app.HANGUP_CALL"
    }
}
