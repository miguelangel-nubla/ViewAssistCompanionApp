package com.msp1974.vacompanion.satellite

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.msp1974.vacompanion.audio.AudioStream
import com.msp1974.vacompanion.audio.StreamVolumeManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.ScreenUtils
import com.msp1974.vacompanion.utils.VolumeObserver
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Keeps the connected server (Home Assistant via Wyoming) aligned with device-mastered state:
 * stream volumes, Do Not Disturb, screen brightness, and auto-brightness. Outbound updates use
 * [SatelliteServer.sendSetting]; inbound changes from HA are applied in [onSettingChange].
 */
class DeviceSyncManager(private val context: Context, private val server: SatelliteServer) {
    private val config = APPConfig.getInstance(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val streamManager = StreamVolumeManager(context)
    private val screenUtils = ScreenUtils(context.applicationContext)

    private val volumeObserver = VolumeObserver(context) { media, voice, alarm ->
        syncVolumeLevelsIfChanged(media, voice, alarm)
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            syncScreenFromHardwareIfChanged()
        }
    }

    fun onConnected() {
        if (!DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)) {
            config.voiceVolume = audioManager.getStreamVolume(AudioStream.Voice.STREAM)
        }
        config.mediaVolume = audioManager.getStreamVolume(AudioStream.Media.STREAM)
        config.alarmVolume = audioManager.getStreamVolume(AudioStream.Alarm.STREAM)
        config.doNotDisturb = DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)

        server.sendSetting("media_volume", config.mediaVolume)
        server.sendSetting("voice_volume", config.voiceVolume)
        server.sendSetting("alarm_volume", config.alarmVolume)
        server.sendSetting("do_not_disturb", config.doNotDisturb)

        val brightness = screenUtils.getScreenBrightness()
        val autoBrightness = screenUtils.getScreenAutoBrightnessMode()
        config.screenBrightness = brightness
        config.screenAutoBrightness = autoBrightness
        server.sendSetting("screen_brightness", brightnessPercent(brightness))
        server.sendSetting("screen_auto_brightness", autoBrightness)

        volumeObserver.register()
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver,
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            brightnessObserver,
        )
    }

    fun onDisconnected() {
        volumeObserver.unregister()
        context.contentResolver.unregisterContentObserver(brightnessObserver)
    }

    /**
     * Re-read system brightness and auto-brightness; push when values differ from config.
     * Invoked from [brightnessObserver] when [Settings.System] brightness settings change.
     */
    private fun syncScreenFromHardwareIfChanged() {
        val brightness = screenUtils.getScreenBrightness()
        val autoBrightness = screenUtils.getScreenAutoBrightnessMode()
        val pct = brightnessPercent(brightness)
        val configPct = brightnessPercent(config.screenBrightness)
        if (configPct != pct) {
            Timber.d("DeviceSync - screen brightness: ${pct}% (was ${configPct}%)")
            config.screenBrightness = brightness
            server.sendSetting("screen_brightness", pct)
        }
        if (config.screenAutoBrightness != autoBrightness) {
            Timber.d("DeviceSync - auto brightness: $autoBrightness (was ${config.screenAutoBrightness})")
            config.screenAutoBrightness = autoBrightness
            server.sendSetting("screen_auto_brightness", autoBrightness)
        }
    }

    private fun brightnessPercent(level: Float): Int =
        (level * 100f).roundToInt().coerceIn(0, 100)

    fun onSettingChange(name: String, value: Any) {
        try {
            when (name) {
                "media_volume" -> {
                    streamManager.setVolume(AudioStream.Media.STREAM, value as Int)
                }
                "voice_volume" -> {
                    if (!DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)) {
                        streamManager.setVolume(AudioStream.Voice.STREAM, value as Int)
                    }
                }
                "alarm_volume" -> {
                    streamManager.setVolume(AudioStream.Alarm.STREAM, value as Int)
                }
                "media_player_gain" -> {
                    server.pipelineClient?.updateVolume()
                }
                "do_not_disturb" -> {
                    setDoNotDisturb(value as Boolean)
                    server.sendSetting("do_not_disturb", value)
                }
            }
        } catch (e: Exception) {
            Timber.e("Error applying setting change $name ($value): ${e.message}")
        }
    }

    private fun setDoNotDisturb(enable: Boolean) {
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val isInDND = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (isInDND != enable) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                Timber.d("Setting do not disturb to $enable")
                if (enable) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } else {
                Timber.w("Unable to set do not disturb, notification policy access not granted")
                config.eventBroadcaster.notifyEvent(
                    Event(
                        "show_toast_message",
                        "",
                        "Unable to set do not disturb. Permission not granted."
                    )
                )
            }
        }
    }

    private fun syncVolumeLevelsIfChanged(media: Int, voice: Int, alarm: Int) {
        if (config.mediaVolume != media) {
            Timber.d("DeviceSync - media volume: $media (was ${config.mediaVolume})")
            config.mediaVolume = media
            server.sendSetting("media_volume", media)
        }

        if (config.voiceVolume != voice) {
            Timber.d("DeviceSync - voice volume: $voice (was ${config.voiceVolume})")
            config.voiceVolume = voice
            server.sendSetting("voice_volume", voice)
        }

        if (config.alarmVolume != alarm) {
            Timber.d("DeviceSync - alarm volume: $alarm (was ${config.alarmVolume})")
            config.alarmVolume = alarm
            server.sendSetting("alarm_volume", alarm)
        }
    }
}
