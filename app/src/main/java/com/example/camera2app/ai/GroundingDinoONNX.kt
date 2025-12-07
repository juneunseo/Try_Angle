package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
import kotlin.math.*
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer




data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class GroundingDinoONNX(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val inputSize = 800                 // 800 x 800 입력

    // [CLS] person [SEP] = [101, 2711, 102]
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

            // 1) assets → 캐시 디렉토리에 복사
            val modelFile = File(context.filesDir, "grounding_dino.onnx")
            if (!modelFile.exists()) {
                context.assets.open("grounding_dino.onnx").use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // 2) SessionOptions 생성
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // 3) 파일 경로로 세션 생성
            session = env!!.createSession(modelFile.absolutePath, sessionOptions)

            isSessionLoaded = true
            println("✅ Grounding DINO ONNX model loaded.")
        } catch (e: Exception) {
            println("❌ Failed to load Grounding DINO: ${e.message}")
            isSessionLoaded = false
        }
    }


    // ---------------------------------------------------------
    // 2. 전처리 (Bitmap -> FloatArray[1,3,800,800])
    // ---------------------------------------------------------
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val target = inputSize

        // 1) 긴 변 기준 스케일
        val scale = target.toFloat() / max(bitmap.width, bitmap.height)
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        // 2) 중앙 크롭 (800x800)
        val offsetX = max(0, (newW - target) / 2)
        val offsetY = max(0, (newH - target) / 2)
        val cropped = Bitmap.createBitmap(scaled, offsetX, offsetY, target, target)

        // 3) 픽셀 추출
        val pixels = IntArray(target * target)
        cropped.getPixels(pixels, 0, target, 0, 0, target, target)

        // 4) CHW float 배열로 변환 + ImageNet 정규화
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
    // 3. 텍스트 입력 (person 토큰)
    // ---------------------------------------------------------
    private fun createTextInputs(): Map<String, OnnxTensor> {
        val envLocal = env!!
        val seqLen = personTokenIds.size

        val inputIds = OnnxTensor.createTensor(envLocal, arrayOf(personTokenIds))
        val attentionMask = OnnxTensor.createTensor(
            envLocal,
            arrayOf(LongArray(seqLen) { 1L })
        )
        val tokenTypeIds = OnnxTensor.createTensor(
            envLocal,
            arrayOf(LongArray(seqLen) { 0L })
        )

        return mapOf(
            "input_ids" to inputIds,
            "attention_mask" to attentionMask,
            "token_type_ids" to tokenTypeIds
        )
    }

    // ---------------------------------------------------------
    // 4. 추론 (전처리 결과 -> logits, boxes)
    //    반환 값: 두 개의 flat FloatArray
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
        val maskBuffer = LongBuffer.wrap(mask)

        val pixelMask = OnnxTensor.createTensor(
            envLocal,
            maskBuffer,
            longArrayOf(1, inputSize.toLong(), inputSize.toLong())
        )

        val textInputs = createTextInputs()

        val inputs = HashMap<String, OnnxTensor>().apply {
            put("images", pixelValues)
            put("masks", pixelMask)
            putAll(textInputs)
        }

        val outputs = sess.run(inputs)

        android.util.Log.e("DINO", "outputs.size = ${outputs.size()}")

        outputs.forEachIndexed { i, it ->
            android.util.Log.e("DINO", "output[$i] type=${it.value::class.java}")

        }

        // ✅ 3차원 출력
        val logitsRaw3D = outputs[0].value as Array<Array<Array<FloatArray>>>
        val boxesRaw3D  = outputs[1].value as Array<Array<Array<FloatArray>>>

        // ✅ batch 0
        val logitsRaw = logitsRaw3D[0]
        val boxesRaw  = boxesRaw3D[0]

        val numQueries = 900
        val hiddenDim = 256

        val logitsTensor = FloatArray(numQueries * hiddenDim)
        val boxesTensor = FloatArray(numQueries * 4)

        for (i in 0 until numQueries) {
            val logitsRow = logitsRaw[i]
            System.arraycopy(logitsRow, 0, logitsTensor, i * hiddenDim, hiddenDim)

            val boxRow = boxesRaw[i]
            System.arraycopy(boxRow, 0, boxesTensor, i * 4, 4)
        }

        // ✅ ✅ ✅ 이게 없어서 지금 에러 난 것
        return Pair(logitsTensor, boxesTensor)
    }



    // ---------------------------------------------------------
    // 5. Postprocess: 가장 높은 스코어 한 개만
    // ---------------------------------------------------------
    private fun postprocess(
        logitsTensor: FloatArray,
        boxesTensor: FloatArray,
        scoreThreshold: Float = 0.5f
    ): RectF? {

        val numQueries = 900
        val hiddenDim = 256

        var bestScore = scoreThreshold
        var bestBox: RectF? = null

        for (i in 0 until numQueries) {
            // logits[i][0] (person score)
            val score = 1f / (1f + exp(-logitsTensor[i * hiddenDim]))

            if (score > bestScore) {
                bestScore = score

                val cx = boxesTensor[i * 4 + 0]
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
    // 6. Postprocess: 여러 개 + NMS
    // ---------------------------------------------------------
    private fun postprocessMultiple(
        logitsTensor: FloatArray,
        boxesTensor: FloatArray,
        threshold: Float = 0.5f
    ): List<Detection> {

        val numQueries = 900
        val hiddenDim = 256
        val results = mutableListOf<Detection>()

        for (i in 0 until numQueries) {
            val score = 1f / (1f + exp(-logitsTensor[i * hiddenDim]))
            if (score < threshold) continue

            val cx = boxesTensor[i * 4 + 0]
            val cy = boxesTensor[i * 4 + 1]
            val w = boxesTensor[i * 4 + 2]
            val h = boxesTensor[i * 4 + 3]

            val x = cx - w / 2f
            val y = cy - h / 2f

            results += Detection(
                label = "person",
                confidence = score,
                boundingBox = RectF(x, y, x + w, y + h)
            )
        }

        return nms(results, 0.5f)
    }

    // ---------------------------------------------------------
    // 7. NMS / IoU
    // ---------------------------------------------------------
    private fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result += best

            val it = sorted.iterator()
            while (it.hasNext()) {
                val d = it.next()
                if (iou(best.boundingBox, d.boundingBox) >= iouThreshold) {
                    it.remove()
                }
            }
        }

        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f

        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        if (unionArea <= 0f) return 0f

        return interArea / unionArea
    }

    // ---------------------------------------------------------
    // 8. Public API
    // ---------------------------------------------------------

    /** 최고 스코어 한 개만 반환 */
    fun detectOne(bitmap: Bitmap): RectF? {
        if (!isSessionLoaded) return null
        val pre = preprocess(bitmap)
        val (logitsTensor, boxesTensor) = runInference(pre)
        return postprocess(logitsTensor, boxesTensor)
    }

    /** 여러 사람 박스 + confidence (필요하면 사용) */
    fun detectAll(bitmap: Bitmap, threshold: Float = 0.5f): List<Detection> {
        if (!isSessionLoaded) return emptyList()
        val pre = preprocess(bitmap)
        val (logitsTensor, boxesTensor) = runInference(pre)
        return postprocessMultiple(logitsTensor, boxesTensor, threshold)
    }
}
