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

    /** ✅ assets → 내부파일로 복사 */
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
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

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

    /** ✅✅✅ 핵심: ONNX 실행 + float[][][] 정상 처리 */
    fun estimatePose(bitmap: Bitmap, bbox: RectF): List<Pair<PointF, Float>>? {

        val crop = cropBitmap(bitmap, bbox) ?: return null
        val inputData = preprocess(crop)

        val inputTensor =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), longArrayOf(1, 3, 256, 192))

        val results = session.run(mapOf("input" to inputTensor))

        results.forEachIndexed { index, value ->
            val tensor = value.value
            val shape = (tensor as? Array<*>)?.let { arr ->
                fun deepSize(obj: Any?): String {
                    return when (obj) {
                        is Array<*> -> obj.size.toString() + " x " + deepSize(obj.firstOrNull())
                        is FloatArray -> obj.size.toString()
                        else -> "?"
                    }
                }
                deepSize(arr)
            } ?: "UNKNOWN"

            android.util.Log.e(
                "ONNX_OUTPUT",
                "Output[$index] runtimeType = ${tensor::class.java.name}, deepShape = $shape"
            )
        }


        // ✅ ONNX 출력 안전 타입 파악
        val out0 = results[0].value
        val out1 = results[1].value

        android.util.Log.e("ONNX_SAFE", "out0 type = ${out0::class.java}")
        android.util.Log.e("ONNX_SAFE", "out1 type = ${out1::class.java}")

// ✅✅✅ 여기서 3차원이 아니라 "2차원"으로 받는다
        val simccX = when (out0) {
            is Array<*> -> out0 as Array<Array<FloatArray>>
            else -> return null
        }

        val simccY = when (out1) {
            is Array<*> -> out1 as Array<Array<FloatArray>>
            else -> return null
        }

// ✅ 배치 1 기준 → [0]
        val xArr = simccX[0]
        val yArr = simccY[0]




        val output = mutableListOf<Pair<PointF, Float>>()

        for (i in xArr.indices) {
            val xBins = xArr[i]
            val yBins = yArr[i]

            val maxXIdx = xBins.indices.maxBy { xBins[it] }
            val maxYIdx = yBins.indices.maxBy { yBins[it] }

            val px = bbox.left + (maxXIdx.toFloat() / xBins.size) * bbox.width()
            val py = bbox.top + (maxYIdx.toFloat() / yBins.size) * bbox.height()

            val conf = (xBins[maxXIdx] + yBins[maxYIdx]) / 2f

            output.add(PointF(px, py) to conf)
        }

        return output
    }


        // ✅ MainActivity에서 Any로 넘어온 bbox 대응용
    fun estimatePose(bitmap: Bitmap, bbox: Any?): List<Pair<PointF, Float>>? {
        val rect = bbox as? RectF ?: return null
        return estimatePose(bitmap, rect)
    }
}
