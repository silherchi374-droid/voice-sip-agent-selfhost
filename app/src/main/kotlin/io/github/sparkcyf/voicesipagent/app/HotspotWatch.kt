package io.github.sparkcyf.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService

/**
 * Feature 10: hotspot auto-restart. Listens for the Wi-Fi AP state broadcast; when the hotspot
 * switches off and the feature is enabled, asks the privileged (Shizuku) service to start it
 * again with the saved SoftApConfiguration (`cmd wifi start-softap`, no arguments).
 *
 * NOTE: the AP-state constants are hidden APIs (not in android.jar), so they are inlined here
 * as literals — the values are stable AOSP [android.net.wifi.WifiManager] constants:
 * WIFI_AP_STATE_DISABLED = 11, action "android.net.wifi.WIFI_AP_STATE_CHANGED",
 * extra "wifi_state".
 *
 * Honest limits: Android 13+ offers no public API for an app to start tethering with its saved
 * configuration — the privileged shell path is the only reliable route. When Shizuku is not
 * running there is no fallback (`startLocalOnlyHotspot` creates a throwaway network with a
 * random password and no internet sharing, useless for the gateway), so the event is only
 * logged. Restarts are rate-limited to one per minute.
 */
class HotspotWatch(
    private val context: Context,
    private val privilegedService: () -> IPrivilegedService?,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit = { AppLog.i(it) },
) {
    private var receiver: BroadcastReceiver? = null
    private var lastRestartMs = 0L

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_FAILED)
                if (state == WIFI_AP_STATE_DISABLED) onHotspotOff()
            }
        }
        receiver = r
        // A system broadcast, so the receiver must be exported (API 33+); ContextCompat
        // picks the right registerReceiver overload per API level.
        ContextCompat.registerReceiver(context, r, IntentFilter(WIFI_AP_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun onHotspotOff() {
        if (!enabled()) return
        val now = System.currentTimeMillis()
        if (now - lastRestartMs < RESTART_COOLDOWN_MS) return
        lastRestartMs = now
        val svc = privilegedService() ?: run {
            log("hotspot off: cannot restart, privileged service unavailable (is Shizuku running?)")
            return
        }
        val error = runCatching { svc.startTethering() }.getOrElse { it.message ?: it.javaClass.simpleName }
        if (error.isNullOrEmpty()) log("hotspot restarted by gateway")
        else log("hotspot restart failed: $error")
    }

    companion object {
        const val RESTART_COOLDOWN_MS = 60_000L

        /** Hidden AOSP WifiManager constants (see the class doc): do not "fix" to WifiManager.*. */
        const val WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_FAILED = 14
    }
}
