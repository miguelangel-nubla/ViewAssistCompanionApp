package com.msp1974.vacompanion.service

import android.Manifest
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.satellite.SatelliteCallback
import com.msp1974.vacompanion.satellite.SatelliteServer
import com.msp1974.vacompanion.satellite.SatelliteZeroconf
import com.msp1974.vacompanion.wyoming.WyomingPacket
import com.msp1974.vacompanion.satellite.DeviceSyncManager
import com.msp1974.vacompanion.audio.EffectsPlayer
import com.msp1974.vacompanion.audio.StreamVolumeManager as AudManager
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.sensors.SensorUpdatesCallback
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.wakeword.WakeWordEngine
import com.msp1974.vacompanion.wakeword.WakeWordEngineModel
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.util.Date
import java.util.LinkedList
import kotlin.collections.set
import kotlin.concurrent.thread

enum class AudioRouteOption { NONE, DETECT, PROCESS_NO_DETECT, STREAM}

internal data class AudioInputDiagnosticsSnapshot(
    val micAudioSource: String,
    val configuredInputProcessingMode: String,
    val activeProcessingPipeline: String,
    val hardwareAecAvailable: Boolean,
    val hardwareAecEnabled: Boolean,
    val activePipelineAecEnabled: Boolean,
    val activePipelineAgcEnabled: Boolean,
    val activePipelineNsEnabled: Boolean,
    val webRtcApmReady: Boolean,
    val currentApmStreamDelayMs: Int?,
    val renderFeedAgeMs: Long?,
    val audioEngine: String,
    val audioEngineStarted: Boolean,
    val audioEngineMuted: Boolean,
    val audioStreamingToServer: Boolean,
    val wakeWordAudioRoute: String,
    val renderTapSinkActive: Boolean,
    val outputPlaybackActive: Boolean,
)

internal fun buildAudioInputDiagnosticsSensors(snapshot: AudioInputDiagnosticsSnapshot): JsonObject =
    buildJsonObject {
        put("mic_audio_source", snapshot.micAudioSource)
        put("configured_input_processing_mode", snapshot.configuredInputProcessingMode)
        put("active_processing_pipeline", snapshot.activeProcessingPipeline)
        put("hardware_aec_available", snapshot.hardwareAecAvailable)
        put("hardware_aec_enabled", snapshot.hardwareAecEnabled)
        put("active_pipeline_aec_enabled", snapshot.activePipelineAecEnabled)
        put("active_pipeline_agc_enabled", snapshot.activePipelineAgcEnabled)
        put("active_pipeline_ns_enabled", snapshot.activePipelineNsEnabled)
        put("webrtc_apm_ready", snapshot.webRtcApmReady)
        snapshot.currentApmStreamDelayMs?.let { put("current_apm_stream_delay_ms", it) }
        snapshot.renderFeedAgeMs?.let { put("render_feed_age_ms", it) }
        put("audio_engine", snapshot.audioEngine)
        put("audio_engine_started", snapshot.audioEngineStarted)
        put("audio_engine_muted", snapshot.audioEngineMuted)
        put("audio_streaming_to_server", snapshot.audioStreamingToServer)
        put("wake_word_audio_route", snapshot.wakeWordAudioRoute)
        put("render_tap_sink_active", snapshot.renderTapSinkActive)
        put("output_playback_active", snapshot.outputPlaybackActive)
    }

internal class BackgroundTaskController (private val context: Context): EventListener {

    private val firebase = FirebaseManager.getInstance(context)
    private var config: APPConfig = APPConfig.getInstance(context)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var wakeWordJob: Job? = null
    private var lastWakeWordDetectionScore = 0f
    private var lastStopWordDetectionScore = 0f

    private var wifiLock: WifiManager.WifiLock? = null

    private val detectionCooldowns = mutableMapOf<String, Long>()
    private val detectionCooldownMs: Long = 2000L
    private val msPerChunk: Long = (MicrophoneInput.BUFFER_SIZE_IN_SHORTS.toLong() * 1000L) / MicrophoneInput.DEFAULT_SAMPLE_RATE_IN_HZ.toLong()

    private val audioHistoryBuffer = LinkedList<WakeWordEngineProvider.AudioResult.Audio>()
    private val historyBufferTargetDurationMs = 1000L
    private val historyBufferMaxSize = (historyBufferTargetDurationMs / msPerChunk).toInt()    
    /**
     * Lookback window (in ms) to include when flushing the history buffer to the server.
     * This 200ms margin ensures we capture the transition between the wake-word and the 
     * user's command. This is crucial because wake-word detections (especially with 
     * sliding windows) often trigger on an average of the last several frames (N-2, N-1, N).
     * By looking back, we avoid clipping the end of the wake-word or the start of 
     * the intent and provide the STT engine with enough acoustic context for a clean start.
     * 
     * Note: This value is an initial estimate and requires empirical testing across different 
     * Android devices. Feedback from actual STT results is necessary to determine if this 
     * value is appropriate or if it results in excessive "pre-speech" noise.
     */
    private val historyBufferLookbackMs = 200L
    private var lastWakeDetectionTimestamp = 0L

    val zeroConf: SatelliteZeroconf = SatelliteZeroconf(context)

    var engine: WakeWordEngine? = null
    var engineStarted: Boolean = false
    var audioRoute: AudioRouteOption = AudioRouteOption.NONE
    @Volatile
    private var audioOutputPlaying: Boolean = false
    private var sensorRunner: Sensors? = null
    lateinit var assetManager: AssetManager
    lateinit var server: SatelliteServer
    private lateinit var deviceSyncManager: DeviceSyncManager
    private val effectsPlayer = EffectsPlayer(context)

    private var motionTask = CameraBackgroundTask(context)

    fun start() {
        assetManager = context.assets

        // wifi lock
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "wallPanel:wifiLock")

        // Start satellite server
        server = SatelliteServer(context, config.serverPort, object : SatelliteCallback {
            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onSatelliteStarted() {
                Timber.i("Background Task - Connection detected")
                deviceSyncManager.onConnected()
                startSensors(context)
                runWakeWordDetection()
                warmUpAudioResources()
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
                zeroConf.unregisterService()
            }

            override fun onSatelliteStopped() {
                Timber.i("Background Task - Disconnection detected")
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
                audioOutputPlaying = false
                if (sensorRunner != null) {
                    sensorRunner!!.stop()
                    sensorRunner = null
                }
                terminateWakeWordDetection()
                stopSensors()
                deviceSyncManager.onDisconnected()
                zeroConf.registerService(config.serverPort)
            }

            override fun onRequestInputAudioStream() {
                audioRoute = AudioRouteOption.STREAM
                var sentChunks = 0

                // Flush history buffer, but only audio from the last wake-up onwards (with a small lookback)
                synchronized(audioHistoryBuffer) {
                    while (audioHistoryBuffer.isNotEmpty()) {
                        val chunk = audioHistoryBuffer.removeFirst()
                        // Keep only chunks that follow the detection point (minus lookback margin for safety)
                        if (chunk.timestamp >= (lastWakeDetectionTimestamp - historyBufferLookbackMs)) {
                            server.sendAudio(chunk.audio.toByteArray())
                            sentChunks++
                        }
                    }
                }

                Timber.i("Streaming audio to server. Sent $sentChunks chunks from history (${sentChunks * msPerChunk}ms).")
                engine?.setStreaming(true)
                sendAudioInputDiagnosticsStatus()
            }

            override fun onReleaseInputAudioStream() {
                Timber.i("Stopped streaming audio to server")
                if (audioRoute == AudioRouteOption.STREAM) {
                    audioRoute = AudioRouteOption.PROCESS_NO_DETECT
                    lastWakeWordDetectionScore = 0f
                    lastStopWordDetectionScore = 0f

                    scope.launch {
                        delay(2000)
                        audioRoute = AudioRouteOption.DETECT
                        sendAudioInputDiagnosticsStatus()
                    }
                }
                if (config.processingSound != "none") {
                    try {
                        val resId = context.resources.getIdentifier(config.processingSound, "raw", context.packageName)
                        if (resId != 0) {
                            effectsPlayer.play(resId)
                        }
                    } catch (e: Exception) {
                        Timber.e("Error playing processing sound: ${e.message.toString()}")
                    }
                }
                engine?.setStreaming(false)
                sendAudioInputDiagnosticsStatus()
            }

            override fun onAudioOutputPlaybackChanged(isPlaying: Boolean) {
                audioOutputPlaying = isPlaying
                sendAudioInputDiagnosticsStatus()
            }
        })
        deviceSyncManager = DeviceSyncManager(context, server)
        thread(name="WyomingServer") { server.start() }

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Start mdns server
        zeroConf.registerService(config.serverPort)

        Timber.d("Background task initialisation completed")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "mic_muted" -> {
                try {
                    val micMuted = event.newValue as Boolean
                    engine?.setMuted(micMuted)
                    if (!micMuted) {
                        if (config.micOnSound != "none") {
                            try {
                                val resId = context.resources.getIdentifier(config.micOnSound, "raw", context.packageName)
                                if (resId != 0) {
                                    effectsPlayer.play(resId)
                                }
                            } catch (e: Exception) {
                                Timber.e("Error playing mic on sound: ${e.message.toString()}")
                            }
                        }
                    } else {
                        if (config.micOffSound != "none") {
                            try {
                                val resId = context.resources.getIdentifier(config.micOffSound, "raw", context.packageName)
                                if (resId != 0) {
                                    effectsPlayer.play(resId)
                                }
                            } catch (e: Exception) {
                                Timber.e("Error playing mic off sound: ${e.message.toString()}")
                            }
                        }
                    }
                    sendDiagnostics(0f,0f)
                    sendAudioInputDiagnosticsStatus()
                } catch (e: Exception) {
                    Timber.e("Error setting muted: ${e.message.toString()}")
                }
            }
            "voice_volume", "media_volume", "media_player_gain", "alarm_volume", "do_not_disturb" -> {
                deviceSyncManager.onSettingChange(event.eventName, event.newValue)
            }
            "continue_conversation_start" -> {
                if (config.wakeWordSound != "none") {
                    try {
                        val resId = context.resources.getIdentifier(
                            config.wakeWordSound,
                            "raw",
                            context.packageName
                        )
                        if (resId != 0) {
                            effectsPlayer.play(resId)
                        }
                    } catch (e: Exception) {
                        Timber.e("Error playing continue listening sound: ${e.message.toString()}")
                    }
                }
            }
            "wake_word", "wake_word_threshold", "stop_word_threshold", "wake_word_engine", "mic_audio_source", "audio_input_processing_mode" -> {
                scope.launch {
                    try {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            if (wakeWordJob != null && wakeWordJob!!.isActive) {
                                restartWakeWordDetection()
                            } else if (server.pipelineClient != null) {
                                runWakeWordDetection()
                            }
                        } else {
                            Timber.w("RECORD_AUDIO permission not granted; cannot start wake-word detection.")
                        }
                    } catch (e: Exception) {
                        Timber.e("Error restarting wake word detection: ${e.message.toString()}")
                    }
                }
            }
            "wake_word_sound", "processing_sound", "error_sound", "stop_word_sound", "mic_on_sound", "mic_off_sound" -> {
                scope.launch {
                    try {
                        warmUpAudioResources()
                    } catch (e: Exception) {
                        Timber.e("Error warming up audio resources: ${e.message.toString()}")
                    }
                }
            }
            "wake_word_trigger" -> {
                wakeWordDetected(WakeWordEngineProvider.WakeWordDetection(
                    wakeWordId =  config.wakeWord,
                    wakeWord = config.wakeWord,
                    detected =  true,
                    score =  config.wakeWordThreshold,
                    timestamp = System.currentTimeMillis()
                ),
                false
                )
            }
            "recognition_error" -> {
                val errorText = event.oldValue as? String ?: ""
                if (errorText.isNotEmpty()) {
                    config.eventBroadcaster.notifyEvent(Event("show_toast_error", "", errorText))
                }

                if (config.errorSound != "none") {
                    try {
                        val resId = context.resources.getIdentifier(config.errorSound, "raw", context.packageName)
                        if (resId != 0) {
                            effectsPlayer.play(resId)
                        }
                    } catch (e: Exception) {
                        Timber.e("Error playing error sound: ${e.message.toString()}")
                    }
                }
                audioRoute = AudioRouteOption.DETECT
                sendDiagnostics(0f, 0f)
                sendAudioInputDiagnosticsStatus()
            }
            "screen_saver" -> {
                server.sendSetting("screen_saver", event.newValue)
            }
            "restart_zeroconf" -> {
                zeroConf.unregisterService()
                scope.launch {
                    delay(2000)
                    zeroConf.registerService(config.serverPort)
                }
            }
            "paired_device_id" -> {
                if (config.pairedDeviceID != "") {
                    Timber.d("Device paired, stopping Zeroconf")
                    zeroConf.unregisterService()
                } else {
                    Timber.d("Device unpaired, starting Zeroconf")
                    zeroConf.registerService(config.serverPort)
                }
            }
            "current_path" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("current_path", event.newValue.toString())
                        })
                    }
                )
            }
            "screen_on" -> {
                val state = event.newValue as Boolean
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("screen_on", state)
                        })
                    }
                )
            }
            "enable_motion_detection" -> {
                val state = event.newValue as Boolean
                if (state) {
                    motionTask.startCamera()
                } else {
                    motionTask.stopCamera()
                }
            }
            "last_motion" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("motion_detected", true)
                            put("last_motion", config.lastMotion)
                        })
                    }
                )
            }
            "last_activity" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("last_activity", config.lastActivity)
                        })
                    }
                )
            }
            "motion_detection_sensitivity" -> {
                motionTask.setSensitivity(event.newValue as Int)
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("BackgroundTask - Event: ${event.eventName} - ${event.newValue}")
        }
    }


    fun startSensors(context: Context) {
        sensorRunner = Sensors(context, object : SensorUpdatesCallback {
            override fun onUpdate(data: MutableMap<String, Any>) {
                val data = buildJsonObject {
                    put("timestamp", Date().toString())
                    putJsonObject("sensors") {
                        // TODO: Use a formalized sensor mapping schema instead of manual type checking and casting.
                        data.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> put(key, value)
                                is Number -> put(key, value.toFloat())
                                else -> {
                                    if (Helpers.isNumber(value.toString())) {
                                        put(key, value.toString().toFloat())
                                    } else {
                                        put(key, value.toString())
                                    }
                                }
                            }
                        }
                    }
                }
                server.sendStatus(data)
            }
        })
        // Start motion sensor
        if (config.enableMotionDetection) {
            motionTask.startCamera()
        }
    }

    fun stopSensors() {
        sensorRunner?.stop()
        motionTask.stopCamera()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun runWakeWordDetection() {
        wakeWordJob = scope.launch {
            delay(1000L)
            engine = WakeWordEngine(context,  if (config.wakeWordEngine == "openwakeword") WakeWordEngineModel.OPENWAKEWORD else WakeWordEngineModel.MICROWAKEWORD)
            engine?.setActiveWakeWords(listOf(config.wakeWord))
            engine?.setActiveStopWords(listOf("stop"))

            lastWakeWordDetectionScore = 0f
            lastStopWordDetectionScore = 0f
            sendDiagnostics(0f, 0f)
            warmUpAudioResources()

            engine!!.start().collect {
                when (it) {
                    is WakeWordEngineProvider.AudioResult.WakeDetected -> {
                        val now = System.currentTimeMillis()
                        val lastDetection = detectionCooldowns[it.detection.wakeWordId]

                        if (lastDetection == null || detectionCooldownMs == 0L || now - lastDetection >= detectionCooldownMs) {
                            Timber.i("Wake word detected: ${it.detection.wakeWord}")
                            wakeWordDetected(it.detection, engine!!.isStreaming())
                            detectionCooldowns[it.detection.wakeWordId] = now
                        }
                    }

                    is WakeWordEngineProvider.AudioResult.StopDetected -> {
                        if (it.detection.detected) {
                            if (it.detection.score > 0.5 && server.pipelineClient?.isActive() == true) {
                                Timber.d("Stop word detected: ${it.detection.wakeWord} (Score: ${it.detection.score})")
                                BroadcastSender.sendBroadcast(
                                    context,
                                    BroadcastSender.STOP_WORD_DETECTED
                                )
                                if (config.stopWordSound != "none") {
                                    try {
                                        val resId = context.resources.getIdentifier(config.stopWordSound, "raw", context.packageName)
                                        if (resId != 0) {
                                            effectsPlayer.play(resId)
                                        }
                                    } catch (e: Exception) {
                                        Timber.e("Error playing stop word sound: ${e.message.toString()}")
                                    }
                                }
                            }
                        }
                    }

                    is WakeWordEngineProvider.AudioResult.Audio -> {
                        if (it.audio.size() > 0) {
                            if (engine!!.isStreaming()) {
                                server.sendAudio(it.audio.toByteArray())
                            } else {
                                // Add to history buffer even if not streaming
                                synchronized(audioHistoryBuffer) {
                                    audioHistoryBuffer.addLast(it)
                                    if (audioHistoryBuffer.size > historyBufferMaxSize) {
                                        audioHistoryBuffer.removeFirst()
                                    }
                                }
                            }
                        }
                    }

                    is WakeWordEngineProvider.AudioResult.WakeWordLiveScore -> {
                        if (config.diagnosticsEnabled) {
                            lastWakeWordDetectionScore = it.score
                        }
                    }
                    is WakeWordEngineProvider.AudioResult.StopWordLiveScore -> {
                        if (config.diagnosticsEnabled) {
                            lastStopWordDetectionScore = it.score
                        }
                    }
                    is WakeWordEngineProvider.AudioResult.AudioLevel -> {
                        if (config.diagnosticsEnabled) {
                            sendDiagnostics(it.level, lastWakeWordDetectionScore, lastStopWordDetectionScore)
                        }
                    }
                    is WakeWordEngineProvider.AudioResult.EngineStatus -> {
                        Timber.i("Engine status: ${it.status}")
                        engineStarted = it.status == "Started"
                        sendAudioInputDiagnosticsStatus()
                    }

                }
            }
        }
    }

    fun terminateWakeWordDetection() {
        if (wakeWordJob != null && wakeWordJob!!.isActive) {
            wakeWordJob?.cancel()
            wakeWordJob = null
        }
        engine = null
        engineStarted = false
        audioRoute = AudioRouteOption.NONE
        lastWakeWordDetectionScore = 0f
        lastStopWordDetectionScore = 0f
        sendDiagnostics(0f, 0f)
        sendAudioInputDiagnosticsStatus()
        Timber.d("Wake word detection terminated")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun restartWakeWordDetection() {
        Timber.d("Restarting wake word detection")
        terminateWakeWordDetection()
        runWakeWordDetection()
    }

    private fun wakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection, isStreaming: Boolean) {
        Timber.i("${detection.wakeWord} wake word detected at ${detection.score}, threshold is ${config.wakeWordThreshold}")
        lastWakeDetectionTimestamp = detection.timestamp
        firebase.logEvent(
            FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                "wake_word" to config.wakeWord,
                "threshold" to config.wakeWordThreshold.toString(),
                "prediction" to detection.score.toString()
            )
        )
        // if wake up on ww, send event
        if (config.screenOnWakeWord) {
            config.eventBroadcaster.notifyEvent(Event("screen_wake", "", ""))
        }

        if (config.wakeWordSound != "none") {
            try {
                effectsPlayer.play(
                    context.resources.getIdentifier(
                        config.wakeWordSound,
                        "raw",
                        context.packageName
                    )
                )
            } catch (e: Exception) {
                Timber.e("Error playing wake word sound: ${e.message.toString()}")
            }
        }
        BroadcastSender.sendBroadcast(context, BroadcastSender.WAKE_WORD_DETECTED)
    }

    fun sendDiagnostics(audioLevel: Float, wakeWordDetectionLevel: Float, stopWordDetectionLevel: Float = 0f) {
        if (config.diagnosticsEnabled) {
            val data = DiagnosticInfo(
                show = config.diagnosticsEnabled,
                engine = config.wakeWordEngine,
                audioLevel = audioLevel * 150,
                wakeWordDetectionLevel = wakeWordDetectionLevel * 100,
                wakeWordThreshold = config.wakeWordThreshold * 100,
                stopWordDetectionLevel = stopWordDetectionLevel * 100,
                stopWordThreshold = config.stopWordThreshold * 100,
                wakeWord = config.wakeWord,
                mode = if (engine == null || !engineStarted || engine!!.isMuted()) AudioRouteOption.NONE else if (engine!!.isStreaming()) AudioRouteOption.STREAM else AudioRouteOption.DETECT
            )
            val event = Event("diagnostic_stats", "", data)
            config.eventBroadcaster.notifyEvent(event)
        }
    }

    private fun sendAudioInputDiagnosticsStatus() {
        val diagnostics = MicrophoneInput.getInputProcessingDiagnostics()
        val wakeWordEngine = engine
        val currentApmStreamDelayMs = MicrophoneInput.getCurrentWebRtcStreamDelayMs()
        val renderFeedAgeMs = MicrophoneInput.getRenderFeedAgeMs()
        val sensors = buildAudioInputDiagnosticsSensors(
            AudioInputDiagnosticsSnapshot(
                micAudioSource = config.micAudioSource,
                configuredInputProcessingMode = diagnostics.configuredInputProcessingMode,
                activeProcessingPipeline = diagnostics.activeProcessingPipeline,
                hardwareAecAvailable = diagnostics.hardwareAecAvailable,
                hardwareAecEnabled = diagnostics.hardwareAecEnabled,
                activePipelineAecEnabled = diagnostics.activePipelineAecEnabled,
                activePipelineAgcEnabled = diagnostics.activePipelineAgcEnabled,
                activePipelineNsEnabled = diagnostics.activePipelineNsEnabled,
                webRtcApmReady = diagnostics.webRtcApmReady,
                currentApmStreamDelayMs = currentApmStreamDelayMs,
                renderFeedAgeMs = renderFeedAgeMs,
                audioEngine = config.wakeWordEngine,
                audioEngineStarted = engineStarted,
                audioEngineMuted = wakeWordEngine?.isMuted() ?: config.micMuted,
                audioStreamingToServer = wakeWordEngine?.isStreaming() ?: false,
                wakeWordAudioRoute = audioRoute.name.lowercase(),
                renderTapSinkActive = MicrophoneInput.renderStreamSink != null,
                outputPlaybackActive = audioOutputPlaying,
            )
        )
        server.sendStatus(
            buildJsonObject {
                put("timestamp", Date().toString())
                putJsonObject("sensors") {
                    sensors.forEach { (k, v) -> put(k, v) }
                }
            }
        )
    }

    private fun warmUpAudioResources() {
        if (config.micOnSound != "none") {
            val resId = context.resources.getIdentifier(config.micOnSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.micOffSound != "none") {
            val resId = context.resources.getIdentifier(config.micOffSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.wakeWordSound != "none") {
            val resId = context.resources.getIdentifier(config.wakeWordSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.processingSound != "none") {
            val resId = context.resources.getIdentifier(config.processingSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.errorSound != "none") {
            val resId = context.resources.getIdentifier(config.errorSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.stopWordSound != "none") {
            val resId = context.resources.getIdentifier(config.stopWordSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
    }


    fun shutdown() {
        Timber.i("Shutting down")
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        config.eventBroadcaster.removeListener(this)
        zeroConf.unregisterService()
        motionTask.stopCamera()
        terminateWakeWordDetection()
        stopSensors()
        effectsPlayer.release()
        server.stop()
    }
}
