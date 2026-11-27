package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process and SIP registration alive while takeover is on, with a status notification.
 * When takeover is off, keeps a reminder visible while this app still holds the dialer role.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private var foreground = false
    // Held while the gateway runs in the foreground, so Doze on an aggressive OEM (ZTE/Nubia,
    // Xiaomi, Transsion…) cannot suspend the SIP reader thread.  Without it the socket backs up
    // and the softphone's registration silently drops until the phone next wakes — the battery
    // allowlist does not help, because it lifts network/alarm limits but does not keep the CPU
    // scheduled.  The gateway sits on a charger in this role, so keeping the CPU awake is the
    // intended trade-off.
    private var wakeLock: PowerManager.WakeLock? = null
    private val runtimeToken = Any()
    private val roleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GatewayRuntime.get(this@GatewayService).refreshCallAccess()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val runtime = GatewayRuntime.get(this)
        runtime.start()
        runtime.onServiceCreated(runtimeToken)
        ContextCompat.registerReceiver(this, roleReceiver,
            IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        runtime.refreshCallAccess()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = GatewayRuntime.get(this)
        if (intent?.action == ACTION_HANGUP) {
            runtime.hangupActiveCall()
            return START_STICKY
        }
        if (!runtime.health.value.needsForegroundService) {
            stopGateway(startId)
            return START_NOT_STICKY
        }
        // Recheck at each start, including sticky restarts and rapid off/on transitions.
        if (!foreground) {
            try {
                promote(runtime.health.value)
            } catch (e: Exception) {
                AppLog.w("could not promote gateway service", e)
                stopGateway(startId)
                return START_NOT_STICKY
            }
        }
        runtime.onForegroundStarted(runtimeToken)
        if (statusJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            statusJob = scope.launch {
                // StateFlow already emits only on change; distinctUntilChanged() on a
                // StateFlow is an error-level deprecation in kotlinx-coroutines 1.11.
                runtime.health.collect { h ->
                    if (h.needsForegroundService) nm.notify(NOTIFICATION_ID, build(summary(h), h.session != null, isShizukuMissing(h)))
                    else stopGateway()
                }
            }
        }
        return START_STICKY
    }

    private fun promote(health: HealthState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gateway status", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, build(summary(health), health.session != null, isShizukuMissing(health)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, build(summary(health), health.session != null, isShizukuMissing(health)))
        }
        foreground = true
        acquireWakeLock()
    }

    private fun stopGateway(startId: Int? = null) {
        statusJob?.cancel()
        statusJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        releaseWakeLock()
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The app was swiped away from recents. A foreground service normally survives that, but
     * aggressive OEMs (Transsion/XOS, Xiaomi…) kill the whole process on swipe. Ask the system
     * to bring the gateway back; if the process is already dead, START_STICKY and the
     * WorkManager watchdog restart it. On XOS the user must also lock the app in recents and
     * enable its Auto-start permission — no code can force that from inside the app.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { startService(Intent(this, GatewayService::class.java)) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        GatewayRuntime.get(this).onForegroundStopped(runtimeToken)
        unregisterReceiver(roleReceiver)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicesipagent:gateway").apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.i("gateway wakelock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun summary(h: HealthState): String = when {
        h.session != null -> "In call · ${h.session}"
        !h.takeover && h.holdsDialer -> "Takeover off. Still your default phone app; change it in Settings."
        !h.takeover -> "Call takeover off"
        h.loopbackTest -> "Loopback test mode"
        isShizukuMissing(h) -> "Shizuku is not running — tap to open Shizuku and start it"
        !h.privilegedReady -> "Setup needed · Shizuku ${h.shizuku.name.lowercase()}"
        !h.sip.ready -> "SIP: ${h.sip.detail}"
        else -> "Ready · ${h.sip.detail}"
    }

    /**
     * Shizuku dies on every reboot and cannot restart itself without root; the gateway cannot
     * work until the user starts it by hand. The ShizukuConnector re-binds on its own as soon
     * as Shizuku is back (sticky binder listener), so this notification only has to get the
     * user to open Shizuku — tapping it does exactly that.
     */
    private fun isShizukuMissing(h: HealthState): Boolean =
        h.takeover && (h.shizuku == ShizukuConnector.State.SHIZUKU_NOT_RUNNING ||
            h.shizuku == ShizukuConnector.State.STOPPED)

    private fun build(text: String, inCall: Boolean = false, shizukuMissing: Boolean = false): Notification {
        val openIntent = if (shizukuMissing) {
            packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: Intent(this, MainActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val open = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
        if (inCall) {
            val hangup = PendingIntent.getService(this, NOTIFICATION_ID,
                Intent(this, GatewayService::class.java).setAction(ACTION_HANGUP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Hang up", hangup).build())
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_HANGUP = "io.github.sparkcyf.voicesipagent.app.HANGUP_ACTIVE_CALL"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun start(context: Context) {
            val runtime = GatewayRuntime.get(context)
            runtime.start()
            sync(context, runtime.health.value)
        }

        /** Used for both explicit launches and runtime switch/role changes. */
        internal fun sync(context: Context, health: HealthState): Boolean {
            if (!health.needsForegroundService) {
                context.stopService(Intent(context, GatewayService::class.java))
                return true
            }
            try {
                context.startForegroundService(Intent(context, GatewayService::class.java))
                return true
            } catch (e: Exception) {
                // Background-start restrictions; the activity or boot receiver will retry.
                AppLog.w("could not start foreground service", e)
                return false
            }
        }
    }
}
