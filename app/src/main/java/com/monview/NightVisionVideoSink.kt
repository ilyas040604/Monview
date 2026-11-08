package com.monview

import android.graphics.Bitmap
import android.graphics.Matrix
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NightVisionVideoSink(
    private val output: VideoSink,
    private val onFirstFrame: () -> Unit = {}
) : VideoSink {

    @Volatile
    private var nightVisionEnabled = false

    private val firstFrameReported = AtomicBoolean(false)

    private val pendingPhoto =
        AtomicReference<((Bitmap?) -> Unit)?>(null)

    fun setNightVisionEnabled(enabled: Boolean) {
        nightVisionEnabled = enabled
    }

    fun resetFirstFrameReported() {
        firstFrameReported.set(false)
    }

    fun requestPhoto(callback: (Bitmap?) -> Unit) {
        pendingPhoto.set(callback)
    }

    fun cancelPhotoRequest() {
        pendingPhoto.getAndSet(null)?.let { callback ->
            try {
                callback(null)
            } catch (_: Exception) {
            }
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (firstFrameReported.compareAndSet(false, true)) {
            onFirstFrame()
        }

        if (!nightVisionEnabled) {
            capturePendingPhoto(frame)
            output.onFrame(frame)
            return
        }

        val input = frame.buffer.toI420() ?: run {
            capturePendingPhoto(frame)
            output.onFrame(frame)
            return
        }

        var outputBuffer: JavaI420Buffer? = null

        try {
            val width = input.width
            val height = input.height

            outputBuffer = JavaI420Buffer.allocate(width, height)

            enhanceLuma(
                input.dataY,
                input.strideY,
                outputBuffer.dataY,
                outputBuffer.strideY,
                width,
                height
            )

            fillNeutralChroma(
                outputBuffer.dataU,
                outputBuffer.strideU,
                (width + 1) / 2,
                (height + 1) / 2
            )

            fillNeutralChroma(
                outputBuffer.dataV,
                outputBuffer.strideV,
                (width + 1) / 2,
                (height + 1) / 2
            )

            val processed = VideoFrame(
                outputBuffer,
                frame.rotation,
                frame.timestampNs
            )

            outputBuffer = null

            try {
                capturePendingPhoto(processed)
                output.onFrame(processed)
            } finally {
                processed.release()
            }

        } catch (_: Exception) {
            capturePendingPhoto(frame)
            output.onFrame(frame)
        } finally {
            outputBuffer?.release()
            input.release()
        }
    }

    private fun capturePendingPhoto(frame: VideoFrame) {
        val callback = pendingPhoto.getAndSet(null) ?: return

        val bitmap = try {
            frameToBitmap(frame)
        } catch (_: Exception) {
            null
        }

        try {
            callback(bitmap)
        } catch (_: Exception) {
            bitmap?.recycle()
        }
    }

    private fun frameToBitmap(frame: VideoFrame): Bitmap? {
        val i420 = frame.buffer.toI420() ?: return null

        try {
            val width = i420.width
            val height = i420.height

            if (width <= 0 || height <= 0) return null

            val yPlane = i420.dataY
            val uPlane = i420.dataU
            val vPlane = i420.dataV

            val pixels = IntArray(width * height)

            for (row in 0 until height) {
                for (column in 0 until width) {
                    val yIndex =
                        row * i420.strideY + column

                    val chromaIndex =
                        (row / 2) * i420.strideU + (column / 2)

                    val y = (
                        (yPlane.get(yIndex).toInt() and 0xFF) - 16
                    ).coerceAtLeast(0)

                    val u =
                        (uPlane.get(chromaIndex).toInt() and 0xFF) - 128

                    val v =
                        (vPlane.get(
                            (row / 2) * i420.strideV + (column / 2)
                        ).toInt() and 0xFF) - 128

                    val yValue = 298 * y

                    val red =
                        ((yValue + 409 * v + 128) shr 8)
                            .coerceIn(0, 255)

                    val green =
                        ((yValue - 100 * u - 208 * v + 128) shr 8)
                            .coerceIn(0, 255)

                    val blue =
                        ((yValue + 516 * u + 128) shr 8)
                            .coerceIn(0, 255)

                    pixels[row * width + column] =
                        (0xFF shl 24) or
                            (red shl 16) or
                            (green shl 8) or
                            blue
                }
            }

            val bitmap = Bitmap.createBitmap(
                pixels,
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

            val rotation =
                ((frame.rotation % 360) + 360) % 360

            if (rotation == 0) return bitmap

            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
            }

            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                width,
                height,
                matrix,
                true
            )

            if (rotated !== bitmap) {
                bitmap.recycle()
            }

            return rotated

        } finally {
            i420.release()
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
                plane.put(
                    row * stride + column,
                    128.toByte()
                )
            }
        }
    }
}