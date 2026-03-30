package com.msp1974.vacompanion.satellite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.wakeword.microwakeword.MicroWakeWordAuthorDefaults
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingPipelineStage
import com.msp1974.vacompanion.wyoming.WyomingClient
import com.msp1974.vacompanion.wyoming.WyomingMessenger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import io.github.z4kn4fein.semver.toVersion
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concrete implementation of a Wyoming client using TCP transport.
 * Orchestrates the connection, handles Wyoming protocol messaging, 
 * and delegates voice interaction logic to the SatelliteSessionCoordinator.
 */
class SatelliteClientHandler(
    private val context: Context,
    private val server: SatelliteServer,
    private val client: Socket,
    private val log: Logger = Logger(),
    private val config: APPConfig = APPConfig.getInstance(context),
    providedMessenger: WyomingMessenger? = null,
    private val mediaHandler: SatelliteMediaHandler = SatelliteMediaHandler(context),
    private val actionHandler: SatelliteActionHandler = SatelliteActionHandler(context, config, mediaHandler, log),
    private val infoBuilder: SatelliteInfoBuilder = SatelliteInfoBuilder(context, config, server.getDeviceInfo()),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SatelliteClient-${client.port}")
    },
    private val broadcastExecutor: ExecutorService = Executors.newSingleThreadExecutor()
) : WyomingClient {

    private val connectionId = client.inetAddress.hostAddress ?: "unknown"
    override val clientId: Int = client.port
    
    // State Management
    private val isRunning = AtomicBoolean(true)

    private val messenger: WyomingMessenger = providedMessenger ?: WyomingMessenger(
        client.port,
        DataInputStream(client.getInputStream()),
        DataOutputStream(client.getOutputStream()),
        config.version,
        log,
        checkRunning = { isRunning.get() }
    )
    @Volatile private var satelliteState = SatelliteState.STOPPED
    private val missedPongs = AtomicInteger(0)
    
    private val sessionCoordinator: SatelliteSessionCoordinator
    
    // Health Tracking
    private companion object {
        const val PING_INTERVAL_MS = 2000L
        const val MAX_MISSED_PONGS = 3
    }

    init {
        sessionCoordinator = SatelliteSessionCoordinator(log, object : SatelliteVoiceSession.Callback {
            override fun sendEvent(packet: WyomingPacket) = this@SatelliteClientHandler.sendRawEvent(packet)

            override fun onRequestAudioStream() {
                log.d("Requesting audio input stream for $clientId")
                server.requestInputAudioStream()
            }

            override fun onReleaseAudioStream() {
                log.d("Releasing audio input stream for $clientId")
                server.releaseInputAudioStream()
            }

            override fun onStartMediaPlayback(sampleRateHz: Int, channels: Int, bytesPerSample: Int) {
                val resolvedRate = sampleRateHz.takeIf { it > 0 } ?: config.sampleRate
                val resolvedChannels = channels.takeIf { it > 0 } ?: config.audioChannels
                val resolvedBytes = bytesPerSample.takeIf { it > 0 } ?: config.audioWidth
                mediaHandler.voicePlayer.configureAudioFormat(
                    sampleRateHz = resolvedRate,
                    channels = resolvedChannels,
                    bytesPerSample = resolvedBytes,
                )
                mediaHandler.voicePlayer.play()
                server.notifyAudioOutputPlaybackChanged(true)
            }
            
            override fun onWriteMediaChunk(payload: ByteArray) {
                if (mediaHandler.voicePlayer.isPlaying) {
                    mediaHandler.voicePlayer.writeAudio(payload)
                }
            }

            override fun onStopMediaPlayback() {
                mediaHandler.voicePlayer.stop(force = true)
                server.notifyAudioOutputPlaybackChanged(false)
            }

            override fun onUpdateVolumeDucking(key: String, duck: Boolean) = mediaHandler.updateVolumeDucking(key, duck)

            override fun notifyContinueConversation(phrase: String) {
                config.eventBroadcaster.notifyEvent(Event("continue_conversation_start", "", phrase))
                sessionCoordinator.startContinueConversation()
            }

            override fun notifyRecognitionError(code: String, text: String) {
                config.eventBroadcaster.notifyEvent(Event("recognition_error", text, code))
            }

            override fun setPipelineTimeout(seconds: Int) {
                cancelPipelineTimeout()
                mainHandler.postDelayed(pipelineTimeoutRunnable, seconds * 1000L)
            }

            override fun cancelPipelineTimeout() {
                mainHandler.removeCallbacks(pipelineTimeoutRunnable)
            }

            override fun initiatePipeline(session: SatelliteVoiceSession, isContinue: Boolean) {
                initiateInteraction(session, precedeWithWakeDetection = !isContinue)
            }
            
            override fun onSessionFinalized(session: SatelliteVoiceSession) {
                this@SatelliteClientHandler.sessionCoordinator.onSessionFinalized(session)
                if (session.needsContinue) {
                    notifyContinueConversation(phrase = "")
                }
            }
        })
    }

    val pipelineStage: WyomingPipelineStage
        get() = sessionCoordinator.activeSession?.stage ?: WyomingPipelineStage.IDLE
    
    override fun isActive(): Boolean = 
        sessionCoordinator.isActive() || mediaHandler.mediaPlayer.isPlaying || mediaHandler.alarmPlayer.isSounding || mediaHandler.voicePlayer.isPlaying

    private var pingTimer: Timer? = null
    private val pipelineTimeoutRunnable = Runnable { 
        log.d("Pipeline timed out (Session ${sessionCoordinator.activeSession?.id})")
        sessionCoordinator.reset() 
    }

    // Broadcasts
    private val intentFilter = IntentFilter().apply {
        addAction(BroadcastSender.WAKE_WORD_DETECTED)
        addAction(BroadcastSender.STOP_WORD_DETECTED)
    }

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (satelliteState != SatelliteState.RUNNING) return
            broadcastExecutor.execute { handleBroadcastIntent(intent) }
        }
    }

    // region Lifecycle

    override fun start() {
        val totalConnections = config.atomicConnectionCount.incrementAndGet()
        log.d("Client $clientId connected from $connectionId. Total connections: $totalConnections")
        startPingTimer()
        
        try {
            while (isRunning.get() && !client.isClosed) {
                val packet = messenger.readEvent() ?: break
                // Any successfully read packet proves the link is alive; resets ping watchdog (not pong-specific).
                missedPongs.set(0)
                processPacket(packet)
            }
        } catch (_: EOFException) {
            log.d("Connection $clientId closed by peer.")
        } catch (ex: IOException) {
            log.e("Connection $clientId lost: ${ex.message}")
        } catch (ex: Exception) {
            if (isRunning.get()) log.e("Connection $clientId terminated unexpectedly: $ex")
        } finally {
            stop()
        }
    }

    override fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        
        log.d("Stopping client $clientId connection handler")
        stopPingTimer()

        if (satelliteState == SatelliteState.RUNNING) {
            stopSatelliteService()
        }
        
        cleanupResources()
    }

    private fun startSatelliteService() {
        if (config.version.toVersion() < config.minRequiredApkVersion.toVersion()) {
            log.d("Update required. App version: ${config.version}, Minimum: ${config.minRequiredApkVersion}")
            BroadcastSender.sendBroadcast(context, BroadcastSender.VERSION_MISMATCH)
            return
        }

        synchronized(server) {
            if (config.pairedDeviceID.isEmpty()) config.pairedDeviceID = connectionId
            if (config.pairedDeviceID != connectionId) {
                log.i("Unauthorized connection attempt from $connectionId:$clientId. Aborting.")
                stop()
                return
            }

            performTakeoverIfNeeded()
            
            log.d("Starting satellite service for $clientId")
            LocalBroadcastManager.getInstance(context).registerReceiver(wakeWordReceiver, intentFilter)
            config.homeAssistantConnectedIP = connectionId

            handleAlarmAction(enable = false)
            mediaHandler.mediaPlayer.unDuckVolume()
            mediaHandler.alarmPlayer.unDuckVolume()
            mediaHandler.mediaPlayer.pause()
            sessionCoordinator.reset()

            server.pipelineClient = this
            satelliteState = SatelliteState.RUNNING
            server.onSatelliteStarted()
            config.isRunning = true
            log.d("Satellite session started for $clientId")
        }
    }

    private fun performTakeoverIfNeeded() {
        val oldClient = server.pipelineClient as? SatelliteClientHandler
        if (oldClient != null && oldClient != this) {
            log.d("Satellite session takeover by $clientId from ${oldClient.clientId}")
            oldClient.stop()
        }
    }

    private fun stopSatelliteService() {
        log.d("Stopping satellite service for $clientId")
        LocalBroadcastManager.getInstance(context).unregisterReceiver(wakeWordReceiver)
        
        synchronized(server) {
            if (server.pipelineClient == this) {
                if (pipelineStage == WyomingPipelineStage.LISTENING) server.releaseInputAudioStream()
                handleAlarmAction(enable = false)
                mediaHandler.mediaPlayer.stop()
                server.pipelineClient = null
                server.onSatelliteStopped()
                config.homeAssistantConnectedIP = ""
            }
            sessionCoordinator.reset()
            satelliteState = SatelliteState.STOPPED
            config.isRunning = false
        }
    }

    private fun cleanupResources() {
        mediaHandler.release()
        server.notifyAudioOutputPlaybackChanged(false)
        sendExecutor.shutdown()
        runCatching { sendExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
        broadcastExecutor.shutdown()
        runCatching { broadcastExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { client.close() }
        
        val remaining = config.atomicConnectionCount.decrementAndGet()
        log.w("$connectionId:$clientId disconnected. Remaining connections: $remaining")
    }

    // endregion

    // region Event Processing

    override fun processPacket(packet: WyomingPacket) {
        if (packet.type !in listOf("ping", "pong", "audio-chunk")) {
            log.d("Event received - $clientId: ${packet.toMap()}")
        }

        try {
            when (packet.type) {
                "ping" -> sendPong()
                "pong" -> {} // No-op: liveness is tracked via read-loop reset for every packet type
                "describe" -> sendInfo()
                "capabilities" -> sendCapabilities()
                "run-satellite" -> startSatelliteService()
                "pause-satellite" -> if (satelliteState == SatelliteState.RUNNING) stopSatelliteService()
                "settings" -> processSettingsPacket(packet)
                "action" -> handleActionPacket(packet)
                "custom-vaca" -> handleCustomVacaEventPacket(packet)
                "timer-finished" -> if (satelliteState == SatelliteState.RUNNING) handleAlarmAction(enable = true)
                else -> {
                    // All other packets are potentially interaction-related
                    if (satelliteState == SatelliteState.RUNNING) {
                        sessionCoordinator.processPacket(packet)
                    }
                }
            }
        } catch (ex: Exception) {
            log.e("Error processing event ${packet.type}: $ex")
        }
    }

    private fun handleActionPacket(packet: WyomingPacket) {
        actionHandler.handleAction(packet.getProp("action"), packet.getProp("payload")) { enable, url ->
            handleAlarmAction(enable, url)
        }
    }

    private fun handleCustomVacaEventPacket(packet: WyomingPacket) {
        val eventType = packet.getProp("event_type")
        val eventData = packet.getJsonObject("data") ?: packet.data

        val innerPacket = WyomingPacket(eventType, eventData)
        when (eventType) {
            "action" -> handleActionPacket(innerPacket)
            "settings" -> processSettingsPacket(innerPacket)
            "capabilities" -> sendCapabilities()
            else -> {
                actionHandler.handleAction(eventType, eventData.toString()) { enable, url ->
                    handleAlarmAction(enable, url)
                }
            }
        }
    }

    override fun onWakeWordDetected() {
        sessionCoordinator.onWakeWordDetected()
    }

    private fun handleBroadcastIntent(intent: Intent) {
        if (mediaHandler.voicePlayer.isPlaying) {
            mediaHandler.voicePlayer.stop()
            mediaHandler.updateVolumeDucking("music", false)
        }
        handleAlarmAction(false)

        when (intent.action) {
            BroadcastSender.WAKE_WORD_DETECTED -> onWakeWordDetected()
            BroadcastSender.STOP_WORD_DETECTED -> {
                log.d("Stop word detected - resetting coordinator")
                sessionCoordinator.reset()
            }
        }
    }

    override fun updateVolume() {
        mediaHandler.mediaPlayer.updatePlayerVolume()
    }

    private fun processSettingsPacket(packet: WyomingPacket) {
        val settingsStr = packet.getProp("settings").ifEmpty { packet.data.toString() }
        config.processSettings(settingsStr)
    }


    private fun handleAlarmAction(enable: Boolean, url: String = "") {
        if (enable) {
            mediaHandler.updateVolumeDucking("music", true)
            mediaHandler.alarmPlayer.startAlarm(url)
            config.eventBroadcaster.notifyEvent(Event("screen_wake", "", ""))
        } else {
            mediaHandler.alarmPlayer.stopAlarm()
            mediaHandler.updateVolumeDucking("music", false)
        }
        sendSettingChange("alarm", enable)
    }

    // endregion

    // region Outbound

    private fun startPingTimer() {
        stopPingTimer()
        pingTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (missedPongs.get() >= MAX_MISSED_PONGS) {
                        log.w("Client $clientId: No response for ${MAX_MISSED_PONGS * PING_INTERVAL_MS / 1000}s. Terminating connection.")
                        stop()
                        return
                    }
                    missedPongs.incrementAndGet()
                    sendRawEvent(WyomingPacket("ping", buildJsonObject { put("text", "") }))
                }
            }, 0, PING_INTERVAL_MS)
        }
    }

    private fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    fun sendPong() = sendRawEvent(WyomingPacket("pong", buildJsonObject { put("text", "") }))

    fun sendInfo() = sendRawEvent(WyomingPacket("info", infoBuilder.buildInfo()))

    fun initiateInteraction(session: SatelliteVoiceSession? = null, precedeWithWakeDetection: Boolean = true) {
        val targetSession = session ?: sessionCoordinator.activeSession ?: return
        
        val runPipelinePacket = WyomingPacket(
            "run-pipeline",
            buildJsonObject {
                put("name", "VACA ${config.uuid}")
                put("start_stage", "asr")
                put("end_stage", "tts")
                put("restart_on_end", false)
                putJsonObject("snd_format") {
                    put("rate", config.sampleRate)
                    put("width", config.audioWidth)
                    put("channels", config.audioChannels)
                }
            },
            sessionId = targetSession.id
        )

        // sendRawEvent also posts to sendExecutor; nested tasks run after this block returns, preserving
        // order: detection (if any) is queued before run-pipeline on the single-thread executor.
        sendExecutor.execute {
            if (precedeWithWakeDetection) {
                val detectionPacket = WyomingPacket(
                    "detection",
                    buildJsonObject {
                        put("name", config.wakeWord)
                        put("timestamp", WyomingPacket.isoNow())
                        put("speaker", "")
                    },
                    sessionId = targetSession.id
                )
                sendRawEvent(detectionPacket)
            }
            sendRawEvent(runPipelinePacket)
        }
    }

    override fun sendAudio(audio: ByteArray) {
        val session = sessionCoordinator.activeSession ?: return
        if (session.stage != WyomingPipelineStage.LISTENING) return

        val data = buildJsonObject {
            put("rate", config.sampleRate)
            put("width", config.audioWidth)
            put("channels", config.audioChannels)
        }
        sendRawEvent(WyomingPacket("audio-chunk", data, audio, sessionId = session.id))
    }

    override fun sendStatus(data: JsonObject) = sendCustomVacaEvent("status", data)

    override fun sendSetting(name: String, value: Any) = sendSettingChange(name, value)

    fun sendCapabilities() {
        val deviceRoot = DeviceCapabilitiesManager.toJson(server.getDeviceInfo())
        val innerCaps = deviceRoot["capabilities"]?.jsonObject ?: buildJsonObject { }
        val authorDefaults = MicroWakeWordAuthorDefaults.buildJsonObject(context.assets)
        val mergedCaps = buildJsonObject {
            innerCaps.forEach { (k, v) -> put(k, v) }
            put(MicroWakeWordAuthorDefaults.CAPABILITIES_KEY, authorDefaults)
        }
        sendCustomVacaEvent(
            "capabilities",
            buildJsonObject { put("capabilities", mergedCaps) },
        )
    }

    fun sendSettingChange(name: String, value: Any) {
        val settings = buildJsonObject {
            putJsonObject("settings") {
                when (value) {
                    is String -> put(name, value)
                    is Boolean -> put(name, value)
                    is Number -> put(name, value)
                    is JsonElement -> put(name, value)
                }
            }
        }
        sendCustomVacaEvent("settings", settings)
    }

    fun sendCustomVacaEvent(type: String, data: JsonObject) {
        sendRawEvent(WyomingPacket("custom-vaca", buildJsonObject {
            put("event_type", type)
            put("data", data)
            put("timestamp", WyomingPacket.isoNow())
        }))
    }

    fun sendCustomEvent(type: String, data: JsonObject) {
        sendRawEvent(WyomingPacket("custom-event", buildJsonObject {
            put("event_type", type)
            put("data", data)
        }))
    }

    fun sendRawEvent(packet: WyomingPacket) {
        if (!isRunning.get() || client.isClosed) return

        sendExecutor.execute {
            // --- Transport Filtering ---
            val currentSessionId = sessionCoordinator.activeSession?.id
            if (currentSessionId != null && 
                packet.sessionId != null && 
                packet.sessionId != currentSessionId
            ) {
                log.d("Dropping packet ${packet.type} for session ${packet.sessionId} (active session: $currentSessionId)")
                return@execute
            }
            
            if (packet.type == "audio-chunk" && pipelineStage != WyomingPipelineStage.LISTENING) {
                return@execute
            }
            // ---------------------------
            
            runCatching {
                messenger.sendEvent(packet)
            }.onFailure { if (isRunning.get()) log.e("Failed to send event ${packet.type}: ${it.message}") }
        }
    }

    // endregion
}
