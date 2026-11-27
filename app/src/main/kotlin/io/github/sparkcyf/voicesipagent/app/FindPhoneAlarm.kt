package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

/**
 * Feature 11: find-my-Infinix. Plays the default alarm ringtone at max STREAM_ALARM volume
 * for up to [MAX_RING_MS] (30 s) or until [stop] is called (from the "FIND STOP" command or
 * the notification action). The previous alarm volume is restored on stop. Deliberately loud:
 * the point is to hear a lost phone across the house.
 */
class FindPhoneAlarm(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var previousVolume: Int? = null
    private var stopPending: Runnable? = null

    val isRinging: Boolean get() = ringtone != null

    fun start() {
        if (ringtone != null) return
        val am = audio ?: return
        previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val rt = uri?.let { runCatching { RingtoneManager.getRingtone(appContext, it) }.getOrNull() }
        if (rt == null) {
            stop() // restores the previous volume; nothing is ringing
            return
        }
        rt.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone = rt
        runCatching { rt.play() }.onFailure {
            stop()
            return
        }
        val stopRunnable = Runnable { this.stop() }
        stopPending = stopRunnable
        handler.postDelayed(stopRunnable, MAX_RING_MS)
    }

    fun stop() {
        stopPending?.let { handler.removeCallbacks(it) }
        stopPending = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            val am = audio
            val prev = previousVolume
            if (am != null && prev != null) am.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
        }
        previousVolume = null
    }

    companion object {
        const val MAX_RING_MS = 30_000L
    }
}
