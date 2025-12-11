package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class DepthAnythingONNX(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    // ✅ DepthAnything Small 모델 기준
    private val inputSize = 256

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isRunning = AtomicBoolean(false)

    var isSessionLoaded: Boolean = false
        private set

    init {
        loadModel()
    }

    // -------------------------------------------------
    // ✅ 1. 모델 로드
    // -------------------------------------------------
    private fun loadModel() {
        try {
            env = OrtEnvironment.getEnvironment()

            val modelFile = File(context.filesDir, "depth_anything.onnx")

            // ✅ assets → filesDir 로 복사
            if (!modelFile.exists()) {
                context.assets.open("depth_anything.onnx").use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }

            session = env!!.createSession(modelFile.absolutePath, opts)
            isSessionLoaded = true

            android.util.Log.e("DEPTH_ONNX", "✅ DepthAnything ONNX Loaded")

        } catch (e: Exception) {
            android.util.Log.e("DEPTH_ONNX", "❌ DepthAnything Load Failed", e)
            isSessionLoaded = false
        }
    }

    // -------------------------------------------------
    // ✅ 2. Bitmap → FloatTensor (CHW)
    // -------------------------------------------------
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = FloatArray(3 * inputSize * inputSize)

        var rIdx = 0
        var gIdx = inputSize * inputSize
        var bIdx = 2 * inputSize * inputSize

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val p = resized.getPixel(x, y)

                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f

                input[rIdx++] = r
                input[gIdx++] = g
                input[bIdx++] = b
            }
        }
        return input
    }

    // -------------------------------------------------
    // ✅ ✅ ✅ 3. 비동기 Depth 추론
    // -------------------------------------------------
    fun estimateDepthAsync(
        bitmap: Bitmap,
        onResult: (FloatArray?) -> Unit
    ) {
        if (!isSessionLoaded || session == null) {
            android.util.Log.e("DEPTH_ONNX", "❌ Session not loaded")
            onResult(null)
            return
        }

        if (isRunning.get()) return
        isRunning.set(true)

        scope.launch {

            val result = try {
                android.util.Log.e("DEPTH_ONNX", "✅ Depth Inference Start")

                val inputData = preprocess(bitmap)

                val inputTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(inputData),
                    longArrayOf(
                        1,
                        3,
                        inputSize.toLong(),
                        inputSize.toLong()
                    )
                )

                // ⚠️ 모델에 따라 input 이름이 다를 수 있음
                val outputs = session!!.run(
                    mapOf("input" to inputTensor)
                )

                val depthMap = outputs[0].value as Array<FloatArray>

                android.util.Log.e("DEPTH_ONNX", "✅ Depth Inference Done")

                depthMap[0]   // ✅ (H*W) 1채널 depth

            } catch (e: Exception) {
                android.util.Log.e("DEPTH_ONNX", "❌ Depth Inference Failed", e)
                null
            }

            withContext(Dispatchers.Main) {
                isRunning.set(false)
                onResult(result)
            }
        }
    }

    fun isBusy(): Boolean = isRunning.get()
}
