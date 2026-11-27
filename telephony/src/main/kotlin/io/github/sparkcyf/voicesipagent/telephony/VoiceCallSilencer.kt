package io.github.sparkcyf.voicesipagent.telephony

import android.media.AudioManager

/**
 * Silences the local voice-call output (earpiece/speaker) for the duration of gateway calls by
 * dropping the voice-call stream *volume* to zero — not by muting the stream.
 *
 * Why volume, not mute: the gateway's tapped audio (Direct telephony) is known to break when the
 * voice-call stream is muted (the existing `muteEarpiece` / LocalOutputMute path must stay OFF
 * for two-way audio). The volume index only changes the output gain to the earpiece/speaker; the
 * stream itself keeps flowing, so the digital bridge (iPhone <-> far end, both ways) is
 * unaffected. The previous volume is restored when the last gateway call ends. If the user
 * raises the volume mid-call, that choice is respected (nothing is restored over it).
 *
 * Only gateway-owned legs engage the silencer; the user's own manual calls are untouched.
 * Reference-counted: one acquire per owned leg, one release per leg removal.
 */
class VoiceCallSilencer(private val volume: VolumeControl) {

    /** Minimal seam over AudioManager so the save/restore logic is unit-testable. */
    interface VolumeControl {
        fun get(): Int
        fun set(value: Int)
    }

    /** Real control over the voice-call stream volume. Null AudioManager = no-op. */
    class AudioManagerControl(private val audio: AudioManager?) : VolumeControl {
        override fun get(): Int = audio?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
        override fun set(value: Int) {
            audio?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, value, 0)
        }
    }

    private var refCount = 0
    private var savedVolume: Int? = null

    /** Silence local output for one more owned call. Idempotent per leg when paired with [release]. */
    fun acquire() {
        if (refCount++ == 0) {
            savedVolume = volume.get()
            volume.set(0)
        }
    }

    /** One owned call ended; restore the previous volume when none remain. */
    fun release() {
        if (refCount <= 0) return
        if (--refCount == 0) restore()
    }

    /** Force-restore (endpoint teardown). Safe to call when nothing is silenced. */
    fun restore() {
        val saved = savedVolume ?: return
        savedVolume = null
        refCount = 0
        // Only restore if the volume is still at the silenced level: the user may have raised
        // it mid-call, and that choice wins.
        if (volume.get() == 0) volume.set(saved)
    }
}
