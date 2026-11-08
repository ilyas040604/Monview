
package com.monview

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class NightVisionVideoSink(
    private val output: VideoSink,
    private val onFirstFrame: () -> Unit = {}
) : VideoSink {

    @Volatile
    private var nightVisionEnabled = false

    private val firstFrameReported = AtomicBoolean(false)

    fun setNightVisionEnabled(enabled: Boolean) {
        nightVisionEnabled = enabled
    }

    override fun onFrame(frame: VideoFrame) {
        if (firstFrameReported.compareAndSet(false, true)) {
            onFirstFrame()
        }

        if (!nightVisionEnabled) {
            output.onFrame(frame)
            return
        }

        val input = frame.buffer.toI420() ?: return
        var outputBuffer: JavaI420Buffer? = null

        try {
            val width = input.width
            val height = input.height
            outputBuffer = JavaI420Buffer.allocate(width, height)

            enhanceLuma(
                input.dataY, input.strideY,
                outputBuffer.dataY, outputBuffer.strideY,
                width, height
            )

            fillNeutralChroma(
                outputBuffer.dataU, outputBuffer.strideU,
                (width + 1) / 2, (height + 1) / 2
            )

            fillNeutralChroma(
                outputBuffer.dataV, outputBuffer.strideV,
                (width + 1) / 2, (height + 1) / 2
            )

            val processed = VideoFrame(
                outputBuffer,
                frame.rotation,
                frame.timestampNs
            )

            output.onFrame(processed)
            processed.release()
            outputBuffer = null

        } catch (_: Exception) {
            output.onFrame(frame)
        } finally {
            outputBuffer?.release()
            input.release()
        }
    }

    private fun enhanceLuma(
        source: ByteBuffer,
        sourceStride: Int,
        destination: ByteBuffer,
        destinationStride: Int,
        width: Int,
        height: Int
    ) {
        for (row in 0 until height) {
            for (column in 0 until width) {
                val sample = source.get(
                    row * sourceStride + column
                ).toInt() and 0xFF

                val enhanced = (((sample - 16) * 1.30f) + 28f)
                    .toInt()
                    .coerceIn(16, 235)

                destination.put(
                    row * destinationStride + column,
                    enhanced.toByte()
                )
            }
        }
    }

    private fun fillNeutralChroma(
        plane: ByteBuffer,
        stride: Int,
        width: Int,
        height: Int
    ) {
        for (row in 0 until height) {
            for (column in 0 until width) {
                plane.put(row * stride + column, 128.toByte())
            }
        }
    }
}
