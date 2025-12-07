package com.example.camera2app.ai

import android.graphics.*
import kotlin.math.min

object ImageProcessor {

    // ---------------------------------------------------------
    // 1) Bitmap Crop
    // ---------------------------------------------------------
    fun crop(src: Bitmap, rect: RectF): Bitmap? {
        val left = rect.left.coerceAtLeast(0f).toInt()
        val top = rect.top.coerceAtLeast(0f).toInt()
        val right = min(rect.right, src.width.toFloat()).toInt()
        val bottom = min(rect.bottom, src.height.toFloat()).toInt()

        if (left >= right || top >= bottom) return null

        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    // ---------------------------------------------------------
    // 2) Bitmap Resize
    // ---------------------------------------------------------
    fun resize(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    // ---------------------------------------------------------
    // 3) Bitmap → FloatArray (CHW, normalized)
    // ---------------------------------------------------------
    fun toCHWFloatArray(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height

        val out = FloatArray(3 * size)

        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        var rIndex = 0
        var gIndex = size
        var bIndex = size * 2

        for (i in 0 until size) {
            val c = pixels[i]

            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f

            out[rIndex++] = (r - mean[0]) / std[0]
            out[gIndex++] = (g - mean[1]) / std[1]
            out[bIndex++] = (b - mean[2]) / std[2]
        }

        return out
    }

    // ---------------------------------------------------------
    // 4) YOLOX 입력 생성 (Bitmap → FloatArray(CHW))
    // ---------------------------------------------------------
    fun prepareYoloInput(
        bitmap: Bitmap,
        inputSize: Int
    ): FloatArray {
        val resized = resize(bitmap, inputSize, inputSize)
        return toCHWFloatArray(resized)
    }

    // ---------------------------------------------------------
    // 5) RTMPose 입력 생성 (Bitmap → FloatArray(CHW))
    // ---------------------------------------------------------
    fun preparePoseInput(
        bitmap: Bitmap,
        w: Int,
        h: Int
    ): FloatArray {
        val resized = resize(bitmap, w, h)
        return toCHWFloatArray(resized)
    }
}
