package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

// MARK: - RTMPose 결과 구조체

data class RTMPoseResult(
    val keypoints: List<KeypointWithConfidence>,  // 133개 키포인트
    val boundingBox: RectF?                        // 인물 검출 박스
)

data class KeypointWithConfidence(
    val x: Float,
    val y: Float,
    val confidence: Float
)

// MARK: - RTMPose Runner (ONNX Runtime)

class RTMPoseRunner(context: Context) {

    private var detectorSession: OrtSession? = null
    private var poseSession: OrtSession? = null
    private var env: OrtEnvironment? = null

    // 모델 입력 크기
    private val detectorInputSize = Pair(640, 640)  // width, height
    private val poseInputSize = Pair(192, 256)      // width, height

    // 전처리 상수 (ImageNet)
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    init {
        println("🚀 RTMPoseRunner init() 시작")
        setupONNXRuntime(context)
    }

    // MARK: - ONNX Runtime 초기화

    private fun setupONNXRuntime(context: Context) {
        println("🔧 ONNX Runtime 초기화 시작...")

        try {
            // 1. Environment 생성
            env = OrtEnvironment.getEnvironment()
            println("✅ Environment 생성 성공")

            // 2. 모델 파일을 캐시 디렉토리로 복사
            val detectorFile = copyAssetToCache(context, "yolox_int8.onnx")
            val poseFile = copyAssetToCache(context, "rtmpose_int8.onnx")

            println("✅ 모델 파일 준비 완료:")
            println("   Detector (YOLOX): ${detectorFile.absolutePath}")
            println("   Pose (RTMPose): ${poseFile.absolutePath}")

            // 3. YOLOX용 Session Options (NNAPI 가속)
            val detectorOptions = OrtSession.SessionOptions().apply {
                // NNAPI (Android Neural Networks API) 활성화
                try {
                    addNnapi()
                    println("✅ YOLOX: NNAPI GPU 가속 활성화")
                } catch (e: Exception) {
                    println("⚠️ YOLOX NNAPI 활성화 실패, CPU 폴백: ${e.message}")
                }

                setIntraOpNumThreads(6)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // 4. RTMPose용 Session Options (NNAPI 가속)
            val poseOptions = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                    println("✅ RTMPose: NNAPI GPU 가속 활성화")
                } catch (e: Exception) {
                    println("⚠️ RTMPose NNAPI 활성화 실패, CPU 폴백: ${e.message}")
                }

                setIntraOpNumThreads(6)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            println("✅ 최대 성능 최적화 설정 완료 (YOLOX: NNAPI, RTMPose: NNAPI)")

            // 5. 세션 생성
            println("📦 Detector 모델 로딩 중...")
            detectorSession = env!!.createSession(detectorFile.absolutePath, detectorOptions)
            println("✅ YOLOX Detector 로드 성공 (NNAPI)")

            println("📦 Pose 모델 로딩 중...")
            poseSession = env!!.createSession(poseFile.absolutePath, poseOptions)
            println("✅ RTMPose 로드 성공 (NNAPI)")

            println("🔧 ONNX Runtime 초기화 완료")

        } catch (e: Exception) {
            println("❌ ONNX Runtime 초기화 실패: ${e.message}")
            e.printStackTrace()
            env = null
            detectorSession = null
            poseSession = null
        }
    }

    // Assets에서 캐시로 복사
    private fun copyAssetToCache(context: Context, assetName: String): java.io.File {
        val cacheFile = java.io.File(context.cacheDir, assetName)
        if (!cacheFile.exists()) {
            context.assets.open(assetName).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return cacheFile
    }

    // MARK: - 포즈 추정

    fun detectPose(bitmap: Bitmap): RTMPoseResult? {
        val detectorSession = this.detectorSession
        val poseSession = this.poseSession

        if (detectorSession == null || poseSession == null) {
            println("❌ RTMPose 세션이 초기화되지 않음")
            return null
        }

        // 1. YOLOX로 사람 검출
        val boundingBox = detectPerson(bitmap, detectorSession) ?: run {
            // YOLOX가 사람을 검출하지 못하면 전체 이미지 사용
            println("⚠️ YOLOX: 사람을 검출하지 못함 → 전체 이미지로 포즈 추정 시도")
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        }

        println("✅ YOLOX: 사람 검출 - $boundingBox")

        // 2. 검출된 영역으로 포즈 추정
        val keypoints = estimatePose(bitmap, boundingBox, poseSession) ?: run {
            println("❌ RTMPose: 포즈 추정 실패")
            return null
        }

        println("✅ RTMPose: ${keypoints.size}개 키포인트 검출 성공")
        return RTMPoseResult(keypoints, boundingBox)
    }

    // MARK: - YOLOX 사람 검출

    private fun detectPerson(bitmap: Bitmap, session: OrtSession): RectF? {
        // 640x640으로 리사이즈
        val (width, height) = detectorInputSize
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)

        // 전처리
        val inputTensor = preprocessImageForDetector(resizedBitmap)

        try {
            // 추론 실행
            val inputName = session.inputNames.iterator().next()
            val inputs = mapOf(inputName to inputTensor)
            val outputs = session.run(inputs)

            val dets = outputs[0]  // dets: [1, num_boxes, 5]
            val labels = outputs[1]  // labels: [1, num_boxes]

            // 출력 파싱
            return parseYOLOXOutput(
                dets.value as OnnxTensor,
                labels.value as OnnxTensor,
                bitmap.width.toFloat(),
                bitmap.height.toFloat()
            )

        } catch (e: Exception) {
            println("❌ YOLOX 추론 오류: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    // MARK: - RTMPose 포즈 추정

    private fun estimatePose(
        bitmap: Bitmap,
        boundingBox: RectF,
        session: OrtSession
    ): List<KeypointWithConfidence>? {
        // 바운딩 박스 40% 확장 (손 포함)
        val expandedBox = expandBoundingBox(boundingBox, 0.4f, bitmap.width, bitmap.height)

        // 크롭
        val croppedBitmap = cropBitmap(bitmap, expandedBox)

        // 192x256으로 리사이즈
        val (width, height) = poseInputSize
        val resizedBitmap = Bitmap.createScaledBitmap(croppedBitmap, width, height, true)

        // 전처리
        val inputTensor = preprocessImageForPose(resizedBitmap)

        try {
            // 추론 실행
            val inputName = session.inputNames.iterator().next()
            val inputs = mapOf(inputName to inputTensor)
            val outputs = session.run(inputs)

            // SimCC 출력 파싱
            return parseRTMPoseSimCCOutput(
                outputs,
                expandedBox
            )

        } catch (e: Exception) {
            println("❌ RTMPose 추론 오류: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    // MARK: - 전처리

    private fun preprocessImageForDetector(bitmap: Bitmap): OnnxTensor {
        val (width, height) = detectorInputSize
        val buffer = ByteBuffer.allocateDirect(1 * 3 * height * width * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        val floatBuffer = buffer.asFloatBuffer()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // RGB 채널별 정규화
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val pixel = pixels[idx]

                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f  // R
                        1 -> ((pixel shr 8) and 0xFF) / 255f   // G
                        else -> (pixel and 0xFF) / 255f        // B
                    }

                    floatBuffer.put((value - mean[c]) / std[c])
                }
            }
        }

        return OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 3, height.toLong(), width.toLong())
        )
    }

    private fun preprocessImageForPose(bitmap: Bitmap): OnnxTensor {
        val (width, height) = poseInputSize
        val buffer = ByteBuffer.allocateDirect(1 * 3 * height * width * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        val floatBuffer = buffer.asFloatBuffer()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // RGB 채널별 정규화
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val pixel = pixels[idx]

                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f  // R
                        1 -> ((pixel shr 8) and 0xFF) / 255f   // G
                        else -> (pixel and 0xFF) / 255f        // B
                    }

                    floatBuffer.put((value - mean[c]) / std[c])
                }
            }
        }

        return OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 3, height.toLong(), width.toLong())
        )
    }

    // MARK: - 출력 파싱

    private fun parseYOLOXOutput(
        dets: OnnxTensor,
        labels: OnnxTensor,
        imageWidth: Float,
        imageHeight: Float
    ): RectF? {
        val detsBuffer = dets.floatBuffer
        val labelsBuffer = labels.longBuffer

        val shape = dets.info.shape
        val numBoxes = shape[1].toInt()

        if (numBoxes == 0) {
            println("⚠️ YOLOX: 검출된 박스 없음")
            return null
        }

        var bestBox: RectF? = null
        var bestScore = 0.3f  // 최소 임계값

        val scaleX = imageWidth / detectorInputSize.first
        val scaleY = imageHeight / detectorInputSize.second

        for (i in 0 until numBoxes) {
            val label = labelsBuffer.get(i)
            if (label != 0L) continue  // person class = 0

            val offset = i * 5
            val x1 = detsBuffer.get(offset + 0) * scaleX
            val y1 = detsBuffer.get(offset + 1) * scaleY
            val x2 = detsBuffer.get(offset + 2) * scaleX
            val y2 = detsBuffer.get(offset + 3) * scaleY
            val score = detsBuffer.get(offset + 4)

            if (score > bestScore) {
                bestBox = RectF(x1, y1, x2, y2)
                bestScore = score
            }
        }

        return bestBox
    }

    private fun parseRTMPoseSimCCOutput(
        outputs: OrtSession.Result,
        boundingBox: RectF
    ): List<KeypointWithConfidence>? {
        // SimCC 출력: simcc_x [1, 133, 384], simcc_y [1, 133, 512]
        val simccX = outputs[0].value as? OnnxTensor ?: return null
        val simccY = outputs[1].value as? OnnxTensor ?: return null

        val xBuffer = simccX.floatBuffer
        val yBuffer = simccY.floatBuffer

        val xShape = simccX.info.shape
        val yShape = simccY.info.shape

        val numKeypoints = xShape[1].toInt()
        val xBins = xShape[2].toInt()  // 384
        val yBins = yShape[2].toInt()  // 512

        if (numKeypoints != 133) {
            println("⚠️ 예상치 못한 키포인트 수: $numKeypoints")
            return null
        }

        val keypoints = mutableListOf<KeypointWithConfidence>()
        val (poseWidth, poseHeight) = poseInputSize

        for (i in 0 until numKeypoints) {
            // X 좌표: argmax
            val xOffset = i * xBins
            var maxXIdx = 0
            var maxXVal = Float.NEGATIVE_INFINITY
            for (j in 0 until xBins) {
                val value = xBuffer.get(xOffset + j)
                if (value > maxXVal) {
                    maxXVal = value
                    maxXIdx = j
                }
            }

            // Y 좌표: argmax
            val yOffset = i * yBins
            var maxYIdx = 0
            var maxYVal = Float.NEGATIVE_INFINITY
            for (j in 0 until yBins) {
                val value = yBuffer.get(yOffset + j)
                if (value > maxYVal) {
                    maxYVal = value
                    maxYIdx = j
                }
            }

            // SimCC 좌표를 픽셀 좌표로 변환
            val xNorm = (maxXIdx.toFloat() / xBins) * poseWidth
            val yNorm = (maxYIdx.toFloat() / yBins) * poseHeight

            // 바운딩 박스 기준으로 변환
            val x = boundingBox.left + (xNorm / poseWidth) * boundingBox.width()
            val y = boundingBox.top + (yNorm / poseHeight) * boundingBox.height()

            // 신뢰도: 두 확률의 평균
            val confidence = (maxXVal + maxYVal) / 2f

            keypoints.add(KeypointWithConfidence(x, y, confidence))

            // 손 키포인트 디버그 (91-132번)
            if (i in 91..132 && confidence < 0.3f) {
                val handName = if (i <= 111) "왼손" else "오른손"
                val keypointIndex = if (i <= 111) i - 91 else i - 112
                if (keypointIndex % 5 == 0) {
                    println("⚠️ $handName 키포인트 $keypointIndex: 신뢰도 낮음 (${String.format("%.2f", confidence)})")
                }
            }
        }

        // 손 키포인트 요약 통계
        val leftHandConfidences = (91..111).map { keypoints[it].confidence }
        val rightHandConfidences = (112..132).map { keypoints[it].confidence }

        val leftHandAvg = leftHandConfidences.average().toFloat()
        val rightHandAvg = rightHandConfidences.average().toFloat()

        if (leftHandAvg < 0.5f || rightHandAvg < 0.5f) {
            println("📊 손 인식 평균 신뢰도 - 왼손: ${String.format("%.2f", leftHandAvg)}, 오른손: ${String.format("%.2f", rightHandAvg)}")
            if (leftHandAvg < 0.3f || rightHandAvg < 0.3f) {
                println("💡 손이 화면에서 잘렸거나 가려졌을 수 있습니다. 전체 신체가 프레임 안에 들어오도록 조정해보세요.")
            }
        }

        return keypoints
    }

    // MARK: - Helper Functions

    private fun expandBoundingBox(
        box: RectF,
        padding: Float,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        val expandedBox = RectF(
            box.left - box.width() * padding,
            box.top - box.height() * padding,
            box.right + box.width() * padding,
            box.bottom + box.height() * padding
        )

        // 이미지 경계 내로 제한
        return RectF(
            max(0f, expandedBox.left),
            max(0f, expandedBox.top),
            min(imageWidth.toFloat(), expandedBox.right),
            min(imageHeight.toFloat(), expandedBox.bottom)
        )
    }

    private fun cropBitmap(bitmap: Bitmap, rect: RectF): Bitmap {
        val x = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val y = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = (rect.width().toInt()).coerceIn(1, bitmap.width - x)
        val height = (rect.height().toInt()).coerceIn(1, bitmap.height - y)

        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }

    /**
     * ONNX Runtime 세션 정리
     * Activity onDestroy()에서 호출
     */
    fun close() {
        try {
            detectorSession?.close()
            detectorSession = null

            poseSession?.close()
            poseSession = null

            env = null

            println("✅ RTMPoseRunner: ONNX sessions closed")
        } catch (e: Exception) {
            println("⚠️ RTMPoseRunner close error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// Keypoint indices (COCO 17 keypoints)
object KeypointIndex {
    // Body (0-16): COCO 17 keypoints
    const val NOSE = 0
    const val LEFT_EYE = 1
    const val RIGHT_EYE = 2
    const val LEFT_EAR = 3
    const val RIGHT_EAR = 4
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16

    // Face (17-84): 68 facial landmarks
    const val FACE_START = 17
    const val FACE_END = 84

    // Hands (85-126): 21 keypoints per hand
    const val LEFT_HAND_START = 85
    const val LEFT_HAND_END = 105
    const val RIGHT_HAND_START = 106
    const val RIGHT_HAND_END = 126

    // Feet (127-132): 3 keypoints per foot
    const val LEFT_FOOT_START = 127
    const val RIGHT_FOOT_START = 130
}