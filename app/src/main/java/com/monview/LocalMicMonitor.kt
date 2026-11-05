package com.monview

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LocalMicMonitor {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true

        val sampleRate = 48000
        val minInput = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minOutput = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minInput <= 0 || minOutput <= 0) return false

        val bufferSize = maxOf(minInput, minOutput, 4096)

        return try {
            val input = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (input.state != AudioRecord.STATE_INITIALIZED) {
                input.release()
                return false
            }

            val output = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (output.state != AudioTrack.STATE_INITIALIZED) {
                input.release()
                output.release()
                return false
            }

            recorder = input
            player = output

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler =
                    AcousticEchoCanceler.create(input.audioSessionId)?.apply {
                        enabled = true
                    }
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor =
                    NoiseSuppressor.create(input.audioSessionId)?.apply {
                        enabled = true
                    }
            }

            input.startRecording()
            output.play()
            running.set(true)

            worker = thread(
                name = "MonViewLocalMic",
                isDaemon = true
            ) {
                Process.setThreadPriority(
                    Process.THREAD_PRIORITY_AUDIO
                )

                val buffer = ShortArray(bufferSize / 2)

                while (running.get()) {
                    val count = try {
                        input.read(buffer, 0, buffer.size)
                    } catch (_: Exception) {
                        -1
                    }

                    if (count > 0 && running.get()) {
                        try {
                            output.write(buffer, 0, count)
                        } catch (_: Exception) {
                            break
                        }
                    } else if (count < 0) {
                        break
                    }
                }

                running.set(false)
            }

            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)

        try { recorder?.stop() } catch (_: Exception) {}
        try { player?.pause() } catch (_: Exception) {}
        try { player?.flush() } catch (_: Exception) {}

        try {
            worker?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        worker = null

        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}

        echoCanceler = null
        noiseSuppressor = null

        try { recorder?.release() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}

        recorder = null
        player = null
    }
}