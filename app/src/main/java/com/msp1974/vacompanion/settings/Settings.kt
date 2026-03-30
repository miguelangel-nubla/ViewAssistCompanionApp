package com.msp1974.vacompanion.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Build.UNKNOWN
import android.provider.Settings.Secure
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import com.google.android.gms.common.util.ClientLibraryUtils.getPackageInfo
import com.msp1974.vacompanion.audio.VacaAudioFormat
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventNotifier
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.asIntOrNull
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

enum class BackgroundTaskStatus {
    NOT_STARTED,
    STARTING,
    STARTED,
}

enum class PageLoadingStage {
    NOT_STARTED,
    STARTED,
    AUTHORISING,
    AUTHORISED,
    LOADED,
    AUTH_FAILED,
}

class APPConfig(val context: Context) {
    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val log = Logger()
    private val firebase = FirebaseManager.getInstance(context)
    var eventBroadcaster: EventNotifier
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        onSharedPreferenceChangedListener(prefs, key)
    }

    init {
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        eventBroadcaster = EventNotifier()
    }

    // Constant values
    val name = NAME
    val version = getPackageInfo(context, context.packageName)?.versionName.toString()
    val serverPort = SERVER_PORT

    // Versions
    var integrationVersion: String = "0.0.0"
    var minRequiredApkVersion: String = version


    // In memory only settings
    var initSettings: Boolean = false
    var homeAssistantConnectedIP: String = ""
    var homeAssistantHTTPPort: Int = DEFAULT_HA_HTTP_PORT
    var homeAssistantURL: String = ""
    var homeAssistantDashboard: String = ""

    var sampleRate: Int = VacaAudioFormat.SAMPLE_RATE_HZ
    var audioChannels: Int = VacaAudioFormat.CHANNELS
    var audioWidth: Int = VacaAudioFormat.BYTES_PER_SAMPLE

    //var connectionCount: Int = 0
    var atomicConnectionCount: AtomicInteger = AtomicInteger(0)
    var currentActivity: String = ""
    var backgroundTaskRunning: Boolean = false
    var backgroundTaskStatus: BackgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
    var isRunning: Boolean = false

    var hasRecordAudioPermission: Boolean = false
    var hasPostNotificationPermission: Boolean = false
    var hasWriteExternalStoragePermission: Boolean = false
    var hasCameraPermission: Boolean = false

    var ignoreSSLErrors: Boolean = alwaysIgnoreSSLErrors

    //In memory settings with change notification

    var wakeWordEngine: String by Delegates.observable("openwakeword") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWord: String by Delegates.observable(DEFAULT_WAKE_WORD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordSound: String by Delegates.observable(DEFAULT_WAKE_WORD_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var processingSound: String by Delegates.observable(DEFAULT_PROCESSING_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var errorSound: String by Delegates.observable(DEFAULT_ERROR_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var stopWordSound: String by Delegates.observable(DEFAULT_STOP_WORD_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var micOnSound: String by Delegates.observable(DEFAULT_MIC_ON_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var micOffSound: String by Delegates.observable(DEFAULT_MIC_OFF_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rawProximitySensorThreshold: Int by Delegates.observable(DEFAULT_RAW_PROXIMITY_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordThreshold: Float by Delegates.observable(DEFAULT_WAKE_WORD_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var stopWordThreshold: Float by Delegates.observable(DEFAULT_STOP_WORD_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }


    var voiceVolume: Int by Delegates.observable(DEFAULT_VOICE_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var mediaVolume: Int by Delegates.observable(DEFAULT_MEDIA_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var mediaPlayerGain: Int by Delegates.observable(100) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var alarmVolume: Int by Delegates.observable(DEFAULT_ALARM_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var duckingVolume: Int by Delegates.observable(DEFAULT_DUCKING_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var micMuted: Boolean by Delegates.observable(DEFAULT_MIC_MUTED) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var micGain: Int by Delegates.observable(DEFAULT_MIC_GAIN) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var noiseSuppressionLevel: Int by Delegates.observable(DEFAULT_NOISE_SUPPRESSION_LEVEL) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var micAudioSource: String by Delegates.observable(DEFAULT_MIC_AUDIO_SOURCE) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }
    
    /**
     * Selects the microphone input processing pipeline ("hardware", "webrtc", "speex").
     *
     * This setting exists because audio behavior varies by device age, Android version,
     * and OEM audio stack implementation. Newer devices may perform better with WebRTC APM,
     * while others can still be best with the platform hardware path.
     */
    var audioInputProcessingMode: String by Delegates.observable(DEFAULT_AUDIO_INPUT_PROCESSING_MODE) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenBrightness: Float by Delegates.observable(DEFAULT_SCREEN_BRIGHTNESS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenAutoBrightness: Boolean by Delegates.observable(DEFAULT_SCREEN_AUTO_BRIGHTNESS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var swipeRefresh: Boolean by Delegates.observable(DEFAULT_SWIPE_REFRESH) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenAlwaysOn: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var doNotDisturb: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var darkMode: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var diagnosticsEnabled: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var pairedDeviceID: String by Delegates.observable(pairedDeviceId) { property, oldValue, newValue ->
        pairedDeviceId = newValue
        onValueChangedListener(property, oldValue, newValue)
    }

    var zoomLevel: Int by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnWakeWord: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnBump: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnProximity: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnMotion: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOn: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var enableNetworkRecovery: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var enableMotionDetection: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var motionDetectionSensitivity: Int by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var currentPath: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var lastMotion: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var lastActivity: Long by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenTimeout: Int by Delegates.observable(3000) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var bumpSensitivity: Float by Delegates.observable(0.1f) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenSaver: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOrientationMode: String by Delegates.observable("auto") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }





    // SharedPreferences
    var canSetScreenWritePermission: Boolean
        get() = this.sharedPrefs.getBoolean("can_set_screen_write_permission", true)
        set(value) = this.sharedPrefs.edit { putBoolean("can_set_screen_write_permission", value) }

    var canSetNotificationPolicyAccess: Boolean
        get() = this.sharedPrefs.getBoolean("can_set_notification_policy_access", true)
        set(value) = this.sharedPrefs.edit { putBoolean("can_set_notification_policy_access", value) }

    var startOnBoot: Boolean
        get() = this.sharedPrefs.getBoolean("startOnBoot", false)
        set(value) = this.sharedPrefs.edit { putBoolean("startOnBoot", value) }

    var uuid: String
        get() = this.sharedPrefs.getString("uuid", getUUID()) ?: ""
        set(value) = this.sharedPrefs.edit { putString("uuid", value) }

    var accessToken: String
        get() = this.sharedPrefs.getString("auth_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("auth_token", value) }

    var refreshToken: String
        get() = this.sharedPrefs.getString("refresh_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("refresh_token", value) }

    var tokenExpiry: Long
        get() = this.sharedPrefs.getLong("token_expiry", 0)
        set(value) = this.sharedPrefs.edit { putLong("token_expiry", value) }

    private var pairedDeviceId: String
        get() = this.sharedPrefs.getString("paired_device_id", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("paired_device_id", value) }

    var alwaysIgnoreSSLErrors: Boolean
        get() = this.sharedPrefs.getBoolean("always_ignore_ssl_errors", false)
        set(value) = this.sharedPrefs.edit { putBoolean("always_ignore_ssl_errors", value) }


    fun processSettings(settingString: String) {
        initSettings = true
        val settings = Json.parseToJsonElement(settingString).jsonObject
        
        settings["ha_port"]?.asIntOrNull()?.let { homeAssistantHTTPPort = it }
        settings["ha_url"]?.jsonPrimitive?.contentOrNull?.let { homeAssistantURL = it }
        settings["ha_dashboard"]?.jsonPrimitive?.contentOrNull?.let { homeAssistantDashboard = it }
        settings["wake_word_engine"]?.jsonPrimitive?.contentOrNull?.let { wakeWordEngine = it }
        settings["wake_word"]?.jsonPrimitive?.contentOrNull?.let { wakeWord = it }
        settings["wake_word_sound"]?.jsonPrimitive?.contentOrNull?.let { wakeWordSound = it }
        settings["processing_sound"]?.jsonPrimitive?.contentOrNull?.let { processingSound = it }
        settings["error_sound"]?.jsonPrimitive?.contentOrNull?.let { errorSound = it }
        settings["stop_word_sound"]?.jsonPrimitive?.contentOrNull?.let { stopWordSound = it }
        settings["mic_on_sound"]?.jsonPrimitive?.contentOrNull?.let { micOnSound = it }
        settings["mic_off_sound"]?.jsonPrimitive?.contentOrNull?.let { micOffSound = it }
        settings["wake_word_threshold"]?.jsonPrimitive?.floatOrNull?.let { wakeWordThreshold = it / 100 }
        settings["stop_word_threshold"]?.jsonPrimitive?.floatOrNull?.let { stopWordThreshold = it / 100 }
        settings["raw_proximity_threshold"]?.asIntOrNull()?.let { rawProximitySensorThreshold = it }
        settings["voice_volume"]?.asIntOrNull()?.let { voiceVolume = it }
        settings["media_volume"]?.asIntOrNull()?.let { mediaVolume = it }
        settings["media_player_gain"]?.asIntOrNull()?.let { mediaPlayerGain = it }
        settings["noise_suppression_level"]?.asIntOrNull()?.let { noiseSuppressionLevel = it }
        settings["mic_audio_source"]?.jsonPrimitive?.contentOrNull?.let { micAudioSource = it }
        settings["alarm_volume"]?.asIntOrNull()?.let { alarmVolume = it }
        settings["ducking_volume"]?.asIntOrNull()?.let { duckingVolume = it }
        settings["mic_gain"]?.asIntOrNull()?.let { micGain = it }
        settings["mic_mute"]?.jsonPrimitive?.booleanOrNull?.let { micMuted = it }
        settings["audio_input_processing_mode"]?.jsonPrimitive?.contentOrNull?.let { audioInputProcessingMode = it }
        if (!settings.containsKey("audio_input_processing_mode") &&
            settings.containsKey("echo_cancellation_mode")
        ) {
            log.w(
                "Received legacy setting key echo_cancellation_mode; expected " +
                    "audio_input_processing_mode. Legacy key is ignored."
            )
        }
        settings["screen_brightness"]?.jsonPrimitive?.floatOrNull?.let { screenBrightness = it / 100 }
        settings["screen_auto_brightness"]?.jsonPrimitive?.booleanOrNull?.let { screenAutoBrightness = it }
        settings["swipe_refresh"]?.jsonPrimitive?.booleanOrNull?.let { swipeRefresh = it }
        settings["screen_always_on"]?.jsonPrimitive?.booleanOrNull?.let { screenAlwaysOn = it }
        settings["do_not_disturb"]?.jsonPrimitive?.booleanOrNull?.let { doNotDisturb = it }
        settings["dark_mode"]?.jsonPrimitive?.booleanOrNull?.let { darkMode = it }
        settings["diagnostics_enabled"]?.jsonPrimitive?.booleanOrNull?.let { diagnosticsEnabled = it }
        settings["integration_version"]?.jsonPrimitive?.contentOrNull?.let { integrationVersion = it }
        settings["min_required_apk_version"]?.jsonPrimitive?.contentOrNull?.let { minRequiredApkVersion = it }
        settings["zoom_level"]?.asIntOrNull()?.let { zoomLevel = it }
        settings["screen_on_wake_word"]?.jsonPrimitive?.booleanOrNull?.let { screenOnWakeWord = it }
        settings["screen_on_bump"]?.jsonPrimitive?.booleanOrNull?.let { screenOnBump = it }
        settings["screen_on_proximity"]?.jsonPrimitive?.booleanOrNull?.let { screenOnProximity = it }
        settings["screen_on_motion"]?.jsonPrimitive?.booleanOrNull?.let { screenOnMotion = it }
        settings["screen_on"]?.jsonPrimitive?.booleanOrNull?.let { screenOn = it }
        settings["enable_network_recovery"]?.jsonPrimitive?.booleanOrNull?.let { enableNetworkRecovery = it }
        settings["enable_motion_detection"]?.jsonPrimitive?.booleanOrNull?.let { enableMotionDetection = it }
        settings["motion_detection_sensitivity"]?.asIntOrNull()?.let { motionDetectionSensitivity = it }
        settings["screen_timeout"]?.asIntOrNull()?.let { screenTimeout = it * 1000 }
        settings["bump_sensitivity"]?.jsonPrimitive?.floatOrNull?.let { bumpSensitivity = it / 10 }
        settings["screen_saver"]?.jsonPrimitive?.booleanOrNull?.let { screenSaver = it }
        settings["screen_orientation_mode"]?.jsonPrimitive?.contentOrNull?.let { screenOrientationMode = it }

        firebase.addToCrashLog("Settings update")
    }

    @SuppressLint("HardwareIds")
    private fun getUUID(): String {
        if (Build.SERIAL != UNKNOWN) {
            if (Build.MANUFACTURER.lowercase() != "google") {
                return "${Build.MANUFACTURER}-${Build.SERIAL}".lowercase()
            } else {
                return "${Build.SERIAL}".lowercase()
            }
        }
        val aId = Secure.getString(context.applicationContext.contentResolver, Secure.ANDROID_ID)
        if (aId != null) {
            return aId.slice(0..8)
        }
        val uid = UUID.randomUUID().toString()
        return uid.slice(0..8)

    }

    fun onSharedPreferenceChangedListener(prefs: SharedPreferences, key: String?) {
        log.d("SharedPreference changed: $key")
        val event = Event(key.toString(), "", "")
        firebase.addToCrashLog("${key.toString()} changed")
        eventBroadcaster.notifyEvent(event)
    }

    fun onValueChangedListener(property: KProperty<*>, oldValue: Any, newValue: Any) {
        if (oldValue != newValue) {
            val eventName = when (property.name) {
                "voiceVolume" -> "voice_volume"
                "mediaVolume" -> "media_volume"
                "mediaPlayerGain" -> "media_player_gain"
                "alarmVolume" -> "alarm_volume"
                "duckingVolume" -> "ducking_volume"
                "micGain" -> "mic_gain"
                "micAudioSource" -> "mic_audio_source"
                "audioInputProcessingMode" -> "audio_input_processing_mode"
                "doNotDisturb" -> "do_not_disturb"
                else -> property.name
            }
            val event = Event(eventName, oldValue, newValue)
            firebase.addToCrashLog("${property.name} ($eventName) changed from $oldValue to $newValue")
            eventBroadcaster.notifyEvent(event)
        }
    }

    companion object {
        const val NAME = "VACA"
        const val SERVER_PORT = 10800
        const val DEFAULT_HA_HTTP_PORT = 8123
        const val DEFAULT_RAW_PROXIMITY_THRESHOLD = 300
        const val DEFAULT_WAKE_WORD = "hey_jarvis"
        const val DEFAULT_WAKE_WORD_SOUND = "none"
        const val DEFAULT_PROCESSING_SOUND = "havpe_processing"
        const val DEFAULT_ERROR_SOUND = "generic_error"
        const val DEFAULT_STOP_WORD_SOUND = "generic_stop_word"
        const val DEFAULT_MIC_ON_SOUND = "havpe_mic_on"
        const val DEFAULT_MIC_OFF_SOUND = "havpe_mic_off"
        const val DEFAULT_WAKE_WORD_THRESHOLD = 0.5f
        const val DEFAULT_STOP_WORD_THRESHOLD = 0.5f
        const val DEFAULT_VOICE_VOLUME = 10
        const val DEFAULT_MEDIA_VOLUME = 10
        const val DEFAULT_ALARM_VOLUME = 10
        const val DEFAULT_SCREEN_BRIGHTNESS = 0.5f
        const val DEFAULT_SCREEN_AUTO_BRIGHTNESS = true
        const val DEFAULT_SWIPE_REFRESH = true
        const val DEFAULT_DUCKING_VOLUME = 70
        const val DEFAULT_MIC_MUTED = false
        const val DEFAULT_MIC_GAIN = 0
        const val DEFAULT_NOISE_SUPPRESSION_LEVEL = 50
        const val DEFAULT_MIC_AUDIO_SOURCE = "voice_recognition"
        // Conservative default; remote configuration can switch to WebRTC/Speex per device behavior.
        const val DEFAULT_AUDIO_INPUT_PROCESSING_MODE = "hardware"
        const val GITHUB_API_URL = "https://api.github.com/repos/msp1974/ViewAssist_Companion_App/releases"

        @Volatile
        private var instance: APPConfig? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: APPConfig(context).also { instance = it }
            }
    }
}
