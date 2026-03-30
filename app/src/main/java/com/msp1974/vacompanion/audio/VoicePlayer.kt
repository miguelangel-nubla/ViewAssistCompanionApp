package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.msp1974.vacompanion.utils.Logger

class VoicePlayer(private val context: Context) {
    private val log = Logger()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    
    private var sampleRate = VacaAudioFormat.SAMPLE_RATE_HZ
    private var channelCount = VacaAudioFormat.CHANNELS
    private var bytesPerSample = VacaAudioFormat.BYTES_PER_SAMPLE
    private var audioTrack: AudioTrack? = null
    @Volatile var isPlaying = false
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
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop(force = true)
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

    fun play() {
        if (audioTrack != null) {
            stop(force = true)
        }

        if (requestAudioFocus()) {
            isPlaying = true
            audioTrack = createAudioTrack().apply { 
                // Voice/TTS gain is fixed at 100% (fixed)
                setVolume(1.0f)
                play() 
            }
        } else {
            log.w("Failed to gain audio focus for voice playback")
        }
    }

    fun writeAudio(buffer: ByteArray) {
        // Feed render audio to WebRTC AEC before playing (provides echo reference).
        // Convert to 16kHz mono PCM16 for APM while preserving native speaker playback format.
        MicrophoneInput.renderStreamSink?.let { sink ->
            runCatching {
                tapResampler.process(buffer)?.let { sink(it) }
            }.onFailure { error ->
                // Never allow AEC render-tap failures to block audible playback.
                log.w("Render tap feed failed: ${error.message}")
            }
        }
        val writeResult = this.audioTrack?.write(buffer, 0, buffer.size) ?: 0
        if (writeResult < 0) {
            log.w("AudioTrack write failed with code $writeResult")
        }
    }

    fun stop(force: Boolean = false) {
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
}