package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
import kotlin.math.max
import kotlin.math.min
import java.nio.FloatBuffer
import java.io.File
import java.io.FileOutputStream


class YoloXDetector(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val inputSize = 640

    init {
        val modelPath = copyAssetToFile("yolox_int8.onnx")
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        session = env.createSession(modelPath, opts)
    }

    private fun copyAssetToFile(filename: String): String {
        val file = File(context.filesDir, filename)
        if (file.exists()) return file.absolutePath

        context.assets.open(filename).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }


    /**
     * 결과 박스 중 class=0(person)인 것만 선택
     */
    private fun parseBoxes(
        dets: Array<FloatArray>,
        labels: LongArray,
        imgW: Int,
        imgH: Int
    ): RectF? {

        var bestScore = 0.35f
        var bestBox: RectF? = null

        val scaleX = imgW / inputSize.toFloat()
        val scaleY = imgH / inputSize.toFloat()

        for (i in dets.indices) {
            if (labels[i] != 0L) continue  // 0 = person

            val det = dets[i]
            val x1 = det[0] * scaleX
            val y1 = det[1] * scaleY
            val x2 = det[2] * scaleX
            val y2 = det[3] * scaleY
            val score = det[4]

            if (score > bestScore) {
                bestScore = score
                bestBox = RectF(
                    max(0f, x1),
                    max(0f, y1),
                    min(imgW.toFloat(), x2),
                    min(imgH.toFloat(), y2)
                )
            }
        }

        return bestBox
    }


    /**
     * Bitmap → FloatArray (CHW)
     * mean/std normalization 포함
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val output = FloatArray(3 * inputSize * inputSize)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        var rIndex = 0
        var gIndex = inputSize * inputSize
        var bIndex = 2 * inputSize * inputSize

        for (i in pixels.indices) {
            val c = pixels[i]

            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f

            output[rIndex++] = (r - mean[0]) / std[0]
            output[gIndex++] = (g - mean[1]) / std[1]
            output[bIndex++] = (b - mean[2]) / std[2]
        }

        return output
    }

    // ✅ MainActivity에서 호출하는 진짜 API 함수
    fun detectPerson(bitmap: Bitmap): RectF? {

        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val inputCHW = preprocess(resized)
        val fb = FloatBuffer.wrap(inputCHW)

        val tensor = OnnxTensor.createTensor(
            env,
            fb,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )

        val output = session.run(mapOf(session.inputNames.iterator().next() to tensor))

        val dets = output[0].value as Array<FloatArray>
        val labels = output[1].value as LongArray

        return parseBoxes(dets, labels, bitmap.width, bitmap.height)
    }

}
