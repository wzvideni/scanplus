package com.huawei.hms.scankit.util

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/**
 * High-performance image conversion utilities for Camera2 frames.
 */
object ImageConversionUtils {

    /**
     * Converts a Camera2 [Image] in [ImageFormat.YUV_420_888] to an NV21 formatted [ByteArray].
     *
     * In NV21, Y is full plane, followed by interleaved V, U bytes (VU order).
     * This method handles arbitrary row and pixel strides efficiently.
     *
     * @param image Input Camera2 image.
     * @param reusableBuffer Optional pre-allocated buffer with size >= width * height * 3 / 2.
     * @return Byte array containing the NV21 frame data.
     */
    fun yuv420888ToNv21(image: Image, reusableBuffer: ByteArray? = null): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) {
            "Only YUV_420_888 images are supported, got format: ${image.format}"
        }

        val width = image.width
        val height = image.height
        val requiredSize = width * height * 3 / 2

        val nv21 = if (reusableBuffer != null && reusableBuffer.size >= requiredSize) {
            reusableBuffer
        } else {
            ByteArray(requiredSize)
        }

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer: ByteBuffer = yPlane.buffer
        val uBuffer: ByteBuffer = uPlane.buffer
        val vBuffer: ByteBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        // Copy Y plane
        var nv21Index = 0
        if (yRowStride == width && yPixelStride == 1) {
            val length = width * height
            yBuffer.position(0)
            yBuffer.get(nv21, 0, length)
            nv21Index = length
        } else {
            val yRow = ByteArray(yRowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                if (yPixelStride == 1) {
                    yBuffer.get(nv21, nv21Index, width)
                    nv21Index += width
                } else {
                    yBuffer.get(yRow, 0, width * yPixelStride)
                    for (col in 0 until width) {
                        nv21[nv21Index++] = yRow[col * yPixelStride]
                    }
                }
            }
        }

        // Copy UV planes to NV21 (VU interleaved)
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val uvHeight = height / 2
        val uvWidth = width / 2

        // Fast path: V and U buffers are consecutive in memory and pixelStride == 2
        val vPos = vBuffer.position()
        val uPos = uBuffer.position()

        // Standard robust path for interleaved V and U
        val vRow = ByteArray(vRowStride)
        val uRow = ByteArray(uRowStride)

        for (row in 0 until uvHeight) {
            vBuffer.position(row * vRowStride)
            val vLength = Math.min(vRowStride, vBuffer.remaining())
            vBuffer.get(vRow, 0, vLength)

            uBuffer.position(row * uRowStride)
            val uLength = Math.min(uRowStride, uBuffer.remaining())
            uBuffer.get(uRow, 0, uLength)

            for (col in 0 until uvWidth) {
                val vIndex = col * vPixelStride
                val uIndex = col * uPixelStride
                nv21[nv21Index++] = vRow[vIndex]
                nv21[nv21Index++] = uRow[uIndex]
            }
        }

        return nv21
    }
}
