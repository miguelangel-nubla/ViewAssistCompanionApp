package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.audio.MediaPlayer
import com.msp1974.vacompanion.audio.AlarmPlayer
import com.msp1974.vacompanion.audio.VoicePlayer
import com.msp1974.vacompanion.audio.EffectsPlayer

/**
 * Handles audio output and system-level media state for the satellite.
 * Manages PCM playback for TTS, music, alarms, and volume ducking.
 */
class SatelliteMediaHandler(context: Context) {
    val mediaPlayer = MediaPlayer.getInstance(context)
    val alarmPlayer = AlarmPlayer(context)
    val voicePlayer = VoicePlayer(context)
    val effectsPlayer = EffectsPlayer(context)

    fun release() {
        mediaPlayer.pause()
        mediaPlayer.unDuckVolume()
        alarmPlayer.stopAlarm()
        alarmPlayer.unDuckVolume()
        voicePlayer.stop(force = false)
        effectsPlayer.release()
    }

    /**
     * Ducks or unducks other audio streams to prioritize voice interaction.
     */
    @Synchronized
    fun updateVolumeDucking(key: String, duck: Boolean) {
        when (key) {
            "music" -> {
                if (duck) mediaPlayer.duckVolume() 
                else if (!alarmPlayer.isSounding) mediaPlayer.unDuckVolume()
            }
            "alarm" -> if (duck) alarmPlayer.duckVolume() else alarmPlayer.unDuckVolume()
            "all" -> {
                if (duck) {
                    mediaPlayer.duckVolume()
                    alarmPlayer.duckVolume()
                } else {
                    if (!alarmPlayer.isSounding) mediaPlayer.unDuckVolume()
                    alarmPlayer.unDuckVolume()
                }
            }
        }
    }
}
