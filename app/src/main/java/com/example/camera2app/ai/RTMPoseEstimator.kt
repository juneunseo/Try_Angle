package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import ai.onnxruntime.*
import kotlin.math.max
import java.nio.FloatBuffer
import java.io.File
import java.io.FileOutputStream


class RTMPoseEstimator(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val inputWidth = 192
    private val inputHeight = 256

    init {
        val modelPath = copyAssetToFile("rtmpose_int8.onnx")
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        session = env.createSession(modelPath, opts)
    }

    /** ✅ assets → 내부파일로 복사 (OOM 방지 핵심) */
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

    private fun cropBitmap(bitmap: Bitmap, bbox: RectF): Bitmap? {
        val left = max(0f, bbox.left)
        val top = max(0f, bbox.top)
        val w = bbox.width()
        val h = bbox.height()

        if (left + w > bitmap.width || top + h > bitmap.height) return null

        return Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), w.toInt(), h.toInt())
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        val result = FloatArray(3 * inputWidth * inputHeight)

        var idxR = 0
        var idxG = inputWidth * inputHeight
        var idxB = 2 * inputWidth * inputHeight

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f

            result[idxR++] = (r - mean[0]) / std[0]
            result[idxG++] = (g - mean[1]) / std[1]
            result[idxB++] = (b - mean[2]) / std[2]
        }

        return result
    }

    private fun decodeSimCC(
        simccX: FloatArray,
        simccY: FloatArray,
        bbox: RectF
    ): List<Pair<PointF, Float>> {

        val binsX = simccX.size
        val binsY = simccY.size

        var maxXIdx = 0
        var maxXVal = Float.NEGATIVE_INFINITY
        for (i in simccX.indices) {
            if (simccX[i] > maxXVal) {
                maxXVal = simccX[i]
                maxXIdx = i
            }
        }

        var maxYIdx = 0
        var maxYVal = Float.NEGATIVE_INFINITY
        for (i in simccY.indices) {
            if (simccY[i] > maxYVal) {
                maxYVal = simccY[i]
                maxYIdx = i
            }
        }

        val px = bbox.left + (maxXIdx.toFloat() / binsX) * bbox.width()
        val py = bbox.top + (maxYIdx.toFloat() / binsY) * bbox.height()

        val conf = (maxXVal + maxYVal) / 2f

        return listOf(PointF(px, py) to conf)
    }

    // ✅ MainActivity 타입 붕괴(Any) 대비용 브리지
    fun estimatePose(bitmap: Bitmap, bbox: Any?): List<Pair<PointF, Float>>? {
        val rect = bbox as? RectF ?: return null
        return estimatePose(bitmap, rect)
    }

}
