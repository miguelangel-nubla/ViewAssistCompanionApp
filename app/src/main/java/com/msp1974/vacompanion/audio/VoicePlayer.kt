package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.msp1974.vacompanion.utils.Logger
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams satellite / pipeline TTS (PCM) to [AudioTrack].
 *
 * All [AudioTrack] and focus work runs on a single background thread so [AudioTrack.write] never
 * blocks the Wyoming TCP reader (see class-level note on [audioExecutor]).
 */
class VoicePlayer(private val context: Context) {
    private val log = Logger()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var sampleRate = VacaAudioFormat.SAMPLE_RATE_HZ
    private var channelCount = VacaAudioFormat.CHANNELS
    private var bytesPerSample = VacaAudioFormat.BYTES_PER_SAMPLE
    private var audioTrack: AudioTrack? = null
    @Volatile var isPlaying = false

    private val released = AtomicBoolean(false)

    /** Set once the worker thread starts; used to avoid self-deadlock on synchronous calls. */
    private val audioWorkerThread = AtomicReference<Thread?>()

    /**
     * Single worker for [AudioTrack]: [AudioTrack.write] may block when the buffer is full.
     * Keeping it off the Wyoming read thread allows control packets (settings, diagnostics, etc.)
     * to be processed while TTS is playing.
     */
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        object : Thread(runnable, THREAD_NAME) {
            override fun run() {
                audioWorkerThread.compareAndSet(null, this)
                super.run()
            }
        }
    }

    private val tapResampler = RenderTapResampler().apply {
        configure(sampleRate, channelCount, bytesPerSample)
    }

    @Synchronized
    fun configureAudioFormat(
        sampleRateHz: Int,
        channels: Int,
        bytesPerSample: Int,
    ) {
        if (sampleRateHz <= 0 || (channels != 1 && channels != 2) || bytesPerSample != 2) {
            log.w(
                "Ignoring unsupported voice format: " +
                    "${sampleRateHz}Hz/${channels}ch/${bytesPerSample}B"
            )
            return
        }
        this.sampleRate = sampleRateHz
        this.channelCount = channels
        this.bytesPerSample = bytesPerSample
        tapResampler.configure(sampleRate, channelCount, bytesPerSample)
        log.d("Configured voice format: ${sampleRate}Hz/${channelCount}ch/${this.bytesPerSample}B")
    }

    fun play() {
        if (released.get()) return
        runOnAudioWorkerSync("play") {
            if (audioTrack != null) {
                tearDownTrack(force = true)
            }
            if (requestAudioFocus()) {
                isPlaying = true
                audioTrack = createAudioTrack().apply {
                    setVolume(1.0f)
                    play()
                }
            } else {
                log.w("Failed to gain audio focus for voice playback")
            }
        }
    }

    /**
     * Enqueues PCM for playback. Copies [buffer] because the caller may reuse the array on the
     * reader thread before this runnable runs.
     */
    fun writeAudio(buffer: ByteArray) {
        if (released.get() || buffer.isEmpty()) return
        val pcm = buffer.copyOf()
        try {
            audioExecutor.execute {
                if (!isPlaying) return@execute
                feedRenderTap(pcm)
                writeToTrackOrLog(pcm)
            }
        } catch (_: RejectedExecutionException) {
            // [release] shut down the executor; drop remaining PCM.
        }
    }

    fun stop(force: Boolean = false) {
        if (released.get()) return
        runOnAudioWorkerSync("stop") {
            tearDownTrack(force)
        }
    }

    /**
     * Stops playback and tears down the worker. Safe to call multiple times (e.g. on satellite
     * disconnect). After this, [play] / [writeAudio] / [stop] are no-ops.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching {
            runOnAudioWorkerSync("release") {
                tearDownTrack(force = true)
            }
        }
        audioExecutor.shutdown()
        try {
            if (!audioExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                audioExecutor.shutdownNow()
                audioExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            audioExecutor.shutdownNow()
        }
    }

    private fun feedRenderTap(pcm: ByteArray) {
        MicrophoneInput.renderStreamSink?.let { sink ->
            runCatching {
                tapResampler.process(pcm)?.let { sink(it) }
            }.onFailure { error ->
                log.w("Render tap feed failed: ${error.message}")
            }
        }
    }

    private fun writeToTrackOrLog(pcm: ByteArray) {
        val writeResult = audioTrack?.write(pcm, 0, pcm.size) ?: 0
        if (writeResult < 0) {
            log.w("AudioTrack write failed with code $writeResult")
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val channels = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = if (bytesPerSample == 2) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioStream.Voice.USAGE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channels)
            .setEncoding(encoding)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    channels,
                    encoding
                )
            )
            .build()
    }

    private fun requestAudioFocus(): Boolean {
        val focusAttributes = AudioAttributes.Builder()
            .setUsage(AudioStream.Voice.USAGE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(focusAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        // Never call [stop] here: it waits on the same worker via [runOnAudioWorkerSync].
                        try {
                            audioExecutor.execute {
                                tearDownTrack(force = true)
                            }
                        } catch (_: RejectedExecutionException) {
                            // Released or shutting down.
                        }
                }
            }
            .build()

        val res = audioManager.requestAudioFocus(audioFocusRequest!!)
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun tearDownTrack(force: Boolean) {
        isPlaying = false
        abandonAudioFocus()
        tapResampler.resetState()
        audioTrack?.let { track ->
            try {
                if (force) {
                    track.pause()
                    track.flush()
                    track.release()
                    audioTrack = null
                } else {
                    track.stop()
                }
            } catch (e: Exception) {
                log.w("Error stopping AudioTrack: ${e.message}")
            }
        }
    }

    /**
     * Runs [block] on the audio worker. If the caller already *is* that thread, runs inline so
     * nested operations cannot deadlock the single-thread executor.
     */
    private fun runOnAudioWorkerSync(label: String, block: () -> Unit) {
        val worker = audioWorkerThread.get()
        if (worker != null && Thread.currentThread() === worker) {
            block()
            return
        }
        try {
            audioExecutor
                .submit {
                    block()
                }
                .get(SYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            // Executor shut down (e.g. during [release]).
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.w("VoicePlayer.$label interrupted while waiting for audio worker")
        } catch (_: TimeoutException) {
            log.w(
                "VoicePlayer.$label timed out after ${SYNC_OPERATION_TIMEOUT_SEC}s waiting for audio worker"
            )
        } catch (e: ExecutionException) {
            log.w("VoicePlayer.$label failed on audio worker: ${e.cause?.message}")
        }
    }

    private companion object {
        const val THREAD_NAME = "VoicePlayer-audio"
        private const val SYNC_OPERATION_TIMEOUT_SEC = 30L
        private const val SHUTDOWN_TIMEOUT_SEC = 2L
    }
}
