package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.asIntOrNull
import kotlinx.serialization.json.*

/**
 * Dispatches high-level actions (e.g. playing media, showing messages, alarms) received from the server.
 */
class SatelliteActionHandler(
    private val context: Context,
    private val config: APPConfig,
    private val mediaHandler: SatelliteMediaHandler,
    private val log: Logger = Logger()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun handleAction(action: String, payloadStr: String, alarmCallback: (Boolean, String) -> Unit) {
        runCatching {
            val payload = if (payloadStr.isNotEmpty()) {
                json.parseToJsonElement(payloadStr).jsonObject
            } else buildJsonObject {}

            when (action) {
                "play-media" -> executePlayMedia(payload)
                "play" -> {
                    payload["volume"].asIntOrNull()?.let { config.mediaPlayerGain = it }
                    mediaHandler.mediaPlayer.resume()
                }
                "pause" -> mediaHandler.mediaPlayer.pause()
                "stop" -> mediaHandler.mediaPlayer.stop()
                "set-volume" -> executeSetVolume(payload)
                "toast-message" -> executeToast(payload)
                "refresh", "screen-wake", "screen-sleep", "wake" -> executeNotify(action)
                "alarm" -> executeAlarm(payload, alarmCallback)
                else -> log.w("Received unknown action from server: $action")
            }
        }.onFailure { 
            log.e("Failed to handle action $action (payload: $payloadStr): $it")
        }
    }

    private fun executePlayMedia(payload: JsonObject) {
        val url = payload["url"]?.jsonPrimitive?.content ?: ""
        if (url.isNotEmpty()) {
            val volume = payload["volume"].asIntOrNull() ?: 100
            config.mediaPlayerGain = volume
            mediaHandler.mediaPlayer.play(url)
        }
    }

    private fun executeSetVolume(payload: JsonObject) {
        val volume = payload["volume"].asIntOrNull() ?: 100
        config.mediaPlayerGain = volume
        mediaHandler.mediaPlayer.updatePlayerVolume()
    }

    private fun executeToast(payload: JsonObject) {
        val msg = payload["message"]?.jsonPrimitive?.content ?: ""
        BroadcastSender.sendBroadcast(context, BroadcastSender.TOAST_MESSAGE, msg)
    }

    private fun executeNotify(type: String) {
        val eventType = when (type) {
            "refresh" -> "refresh"
            "screen-wake" -> "screen_wake"
            "screen-sleep" -> "screen_sleep"
            "wake" -> "wake_word_trigger"
            else -> return
        }
        config.eventBroadcaster.notifyEvent(Event(eventType, "", ""))
    }

    private fun executeAlarm(payload: JsonObject, callback: (Boolean, String) -> Unit) {
        val activate = payload["activate"]?.jsonPrimitive?.booleanOrNull ?: false
        val url = payload["url"]?.jsonPrimitive?.contentOrNull ?: ""
        callback(activate, url)
    }
}
