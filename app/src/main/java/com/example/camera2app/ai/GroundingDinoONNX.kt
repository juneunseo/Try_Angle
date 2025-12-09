package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class GroundingDinoONNX(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val inputSize = 800

    // ✅ Coroutine 비동기 스코프
    private val dinoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ✅ FP32 모델 중복 실행 방지
    private val isRunning = AtomicBoolean(false)

    // [CLS] person [SEP]
    private val personTokenIds = longArrayOf(101, 2711, 102)

    var isSessionLoaded: Boolean = false
        private set

    init {
        loadModel()
    }

    // ---------------------------------------------------------
    // 1. 모델 로드
    // ---------------------------------------------------------
    private fun loadModel() {
        try {
            env = OrtEnvironment.getEnvironment()

            val modelFile = File(context.filesDir, "grounding_dino.onnx")

            // ✅ 항상 새로 복사 (깨진 파일 방지)
            context.assets.open("grounding_dino.onnx").use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
            }

            session = env!!.createSession(modelFile.absolutePath, sessionOptions)
            isSessionLoaded = true

            android.util.Log.e("DINO", "✅ Grounding DINO ONNX Loaded (OK)")

        } catch (e: Exception) {
            android.util.Log.e("DINO", "❌ Failed to load Grounding DINO", e)

            // ✅ ✅ ✅ 여기 중요
            session = null
            isSessionLoaded = false
        }
    }

    // ---------------------------------------------------------
    // 2. 전처리 (Bitmap -> FloatArray CHW)
    // ---------------------------------------------------------
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val target = inputSize

        val scale = target.toFloat() / max(bitmap.width, bitmap.height)
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        val offsetX = max(0, (newW - target) / 2)
        val offsetY = max(0, (newH - target) / 2)
        val cropped = Bitmap.createBitmap(scaled, offsetX, offsetY, target, target)

        val pixels = IntArray(target * target)
        cropped.getPixels(pixels, 0, target, 0, 0, target, target)

        val floatArray = FloatArray(3 * target * target)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        var indexR = 0
        var indexG = target * target
        var indexB = 2 * target * target

        for (c in pixels.indices) {
            val color = pixels[c]

            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f

            floatArray[indexR++] = (r - mean[0]) / std[0]
            floatArray[indexG++] = (g - mean[1]) / std[1]
            floatArray[indexB++] = (b - mean[2]) / std[2]
        }

        return floatArray
    }

    // ---------------------------------------------------------
    // 3. 텍스트 입력 생성
    // ---------------------------------------------------------
    private fun createTextInputs(): Map<String, OnnxTensor> {
        val envLocal = env!!
        val seqLen = personTokenIds.size

        val inputIds = OnnxTensor.createTensor(envLocal, arrayOf(personTokenIds))
        val attentionMask = OnnxTensor.createTensor(envLocal, arrayOf(LongArray(seqLen) { 1L }))
        val tokenTypeIds = OnnxTensor.createTensor(envLocal, arrayOf(LongArray(seqLen) { 0L }))

        return mapOf(
            "input_ids" to inputIds,
            "attention_mask" to attentionMask,
            "token_type_ids" to tokenTypeIds
        )
    }

    // ---------------------------------------------------------
    // 4. 추론
    // ---------------------------------------------------------
    private fun runInference(pre: FloatArray): Pair<FloatArray, FloatArray> {
        val envLocal = env!!
        val sess = session ?: throw IllegalStateException("ONNX session not loaded.")

        val pixelValues = OnnxTensor.createTensor(
            envLocal,
            FloatBuffer.wrap(pre),
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )

        val mask = LongArray(inputSize * inputSize) { 1L }
        val pixelMask = OnnxTensor.createTensor(
            envLocal,
            LongBuffer.wrap(mask),
            longArrayOf(1, inputSize.toLong(), inputSize.toLong())
        )

        val textInputs = createTextInputs()

        val inputs = hashMapOf(
            "pixel_values" to pixelValues,
            "pixel_mask" to pixelMask,
            "input_ids" to textInputs["input_ids"]!!,
            "attention_mask" to textInputs["attention_mask"]!!,
            "token_type_ids" to textInputs["token_type_ids"]!!
        )

        val outputs = sess.run(inputs)

        val logitsRaw = outputs[0].value as Array<Array<FloatArray>>
        val boxesRaw = outputs[1].value as Array<Array<FloatArray>>

        val logitsTensor = FloatArray(900)
        val boxesTensor = FloatArray(900 * 4)

        for (i in 0 until 900) {
            logitsTensor[i] = logitsRaw[0][i][0]
            System.arraycopy(boxesRaw[0][i], 0, boxesTensor, i * 4, 4)
        }

        return Pair(logitsTensor, boxesTensor)
    }

    // ---------------------------------------------------------
    // 5. Postprocess (단일)
    // ---------------------------------------------------------
    private fun postprocess(
        logitsTensor: FloatArray,
        boxesTensor: FloatArray,
        scoreThreshold: Float = 0.5f
    ): RectF? {

        var bestScore = scoreThreshold
        var bestBox: RectF? = null

        for (i in 0 until 900) {
            val score = 1f / (1f + exp(-logitsTensor[i]))

            if (score > bestScore) {
                bestScore = score

                val cx = boxesTensor[i * 4]
                val cy = boxesTensor[i * 4 + 1]
                val w = boxesTensor[i * 4 + 2]
                val h = boxesTensor[i * 4 + 3]

                val x = cx - w / 2f
                val y = cy - h / 2f

                bestBox = RectF(x, y, x + w, y + h)
            }
        }

        return bestBox
    }

    // ---------------------------------------------------------
    // ✅ ✅ ✅ 6. 비동기 단일 감지 (핵심)
    // ---------------------------------------------------------
    fun detectOneAsync(
        bitmap: Bitmap,
        onResult: (RectF?) -> Unit
    ) {
        if (!isSessionLoaded || session == null) {
            android.util.Log.e("DINO", "❌ Session not loaded. Skip inference.")
            onResult(null)
            return
        }

        if (isRunning.get()) return
        isRunning.set(true)

        dinoScope.launch {

            val result = try {
                val pre = preprocess(bitmap)
                val (logitsTensor, boxesTensor) = runInference(pre)
                postprocess(logitsTensor, boxesTensor)
            } catch (e: Exception) {
                android.util.Log.e("DINO", "❌ Async inference failed", e)
                null
            }

            withContext(Dispatchers.Main) {
                isRunning.set(false)
                onResult(result)
            }
        }
    }


    // ---------------------------------------------------------
    // ✅ 실행 중 여부 체크
    // ---------------------------------------------------------
    fun isBusy(): Boolean = isRunning.get()
}
