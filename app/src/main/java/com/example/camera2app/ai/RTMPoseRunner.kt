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
    val keypoints: List<KeypointWithConfidence>,
    val boundingBox: RectF?
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
            env = OrtEnvironment.getEnvironment()
            println("✅ Environment 생성 성공")

            val detectorFile = copyAssetToCache(context, "yolox.ort")
            val poseFile = copyAssetToCache(context, "rtmpose.ort")

            println("✅ 모델 파일 준비 완료:")
            println("   Detector (YOLOX): ${detectorFile.absolutePath}")
            println("   Pose (RTMPose): ${poseFile.absolutePath}")

            val detectorOptions = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                    println("✅ YOLOX: NNAPI GPU 가속 활성화")
                } catch (e: Exception) {
                    println("⚠️ YOLOX NNAPI 활성화 실패, CPU 폴백: ${e.message}")
                }
                setIntraOpNumThreads(6)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

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

        val workingBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        // 1. YOLOX로 사람 검출
        val boundingBox = detectPerson(workingBitmap, detectorSession) ?: run {
            println("⚠️ YOLOX: 사람을 검출하지 못함 → 전체 이미지로 포즈 추정 시도")
            RectF(0f, 0f, workingBitmap.width.toFloat(), workingBitmap.height.toFloat())
        }

        println("✅ YOLOX: 사람 검출 - $boundingBox")

        // 2. 검출된 영역으로 포즈 추정
        val keypoints = estimatePose(workingBitmap, boundingBox, poseSession) ?: run {
            println("❌ RTMPose: 포즈 추정 실패")
            return null
        }

        println("✅ RTMPose: ${keypoints.size}개 키포인트 검출 성공")
        return RTMPoseResult(keypoints, boundingBox)
    }

    // MARK: - YOLOX 사람 검출

    private fun detectPerson(bitmap: Bitmap, session: OrtSession): RectF? {
        val (width, height) = detectorInputSize
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val inputTensor = preprocessImageForDetector(resizedBitmap)

        try {
            val inputName = session.inputNames.iterator().next()
            val inputs = mapOf(inputName to inputTensor)
            val outputs = session.run(inputs)

            // ✅ 수정: OnnxValue에서 직접 데이터 추출
            val detsValue = outputs[0]
            val labelsValue = outputs[1]

            return parseYOLOXOutput(
                detsValue,
                labelsValue,
                bitmap.width.toFloat(),
                bitmap.height.toFloat()
            )

        } catch (e: Exception) {
            println("❌ YOLOX 추론 오류: ${e.message}")
            e.printStackTrace()
            return null
        } finally {
            inputTensor.close()
        }
    }

    // MARK: - RTMPose 포즈 추정

    private fun estimatePose(
        bitmap: Bitmap,
        boundingBox: RectF,
        session: OrtSession
    ): List<KeypointWithConfidence>? {
        val expandedBox = expandBoundingBox(boundingBox, 0.4f, bitmap.width, bitmap.height)
        val croppedBitmap = cropBitmap(bitmap, expandedBox)

        val (width, height) = poseInputSize
        val resizedBitmap = Bitmap.createScaledBitmap(croppedBitmap, width, height, true)
        val inputTensor = preprocessImageForPose(resizedBitmap)

        try {
            val inputName = session.inputNames.iterator().next()
            val inputs = mapOf(inputName to inputTensor)
            val outputs = session.run(inputs)

            return parseRTMPoseSimCCOutput(outputs, expandedBox)

        } catch (e: Exception) {
            println("❌ Pose detection error: ${e.message}")
            e.printStackTrace()
            return null
        } finally {
            inputTensor.close()
        }
    }

    // MARK: - 전처리

    private fun preprocessImageForDetector(bitmap: Bitmap): OnnxTensor {
        val (width, height) = detectorInputSize

        val resizedBitmap = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val workingBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            && resizedBitmap.config == Bitmap.Config.HARDWARE) {
            resizedBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            resizedBitmap
        }

        println("📐 최종 입력 크기: ${workingBitmap.width}x${workingBitmap.height}, config: ${workingBitmap.config}")

        val floatArray = FloatArray(1 * 3 * height * width)
        val pixels = IntArray(width * height)
        workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var idx = 0
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixelIdx = y * width + x
                    val pixel = pixels[pixelIdx]

                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f
                        1 -> ((pixel shr 8) and 0xFF) / 255f
                        else -> (pixel and 0xFF) / 255f
                    }

                    floatArray[idx++] = (value - mean[c]) / std[c]
                }
            }
        }

        return OnnxTensor.createTensor(
            env,
            java.nio.FloatBuffer.wrap(floatArray),
            longArrayOf(1, 3, height.toLong(), width.toLong())
        )
    }

    // ✅ 수정: FloatArray 방식으로 변경
    private fun preprocessImageForPose(bitmap: Bitmap): OnnxTensor {
        val (width, height) = poseInputSize  // 192, 256

        val resizedBitmap = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val workingBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            && resizedBitmap.config == Bitmap.Config.HARDWARE) {
            resizedBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            resizedBitmap
        }

        val floatArray = FloatArray(1 * 3 * height * width)
        val pixels = IntArray(width * height)
        workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var idx = 0
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixelIdx = y * width + x
                    val pixel = pixels[pixelIdx]

                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f
                        1 -> ((pixel shr 8) and 0xFF) / 255f
                        else -> (pixel and 0xFF) / 255f
                    }

                    floatArray[idx++] = (value - mean[c]) / std[c]
                }
            }
        }

        return OnnxTensor.createTensor(
            env,
            java.nio.FloatBuffer.wrap(floatArray),
            longArrayOf(1, 3, height.toLong(), width.toLong())
        )
    }

    // MARK: - 출력 파싱

    // ✅ 수정: OnnxValue에서 직접 배열 추출
    private fun parseYOLOXOutput(
        detsValue: OnnxValue,
        labelsValue: OnnxValue,
        imageWidth: Float,
        imageHeight: Float
    ): RectF? {
        try {
            // OnnxValue에서 배열 직접 추출
            val detsArray = detsValue.value
            val labelsArray = labelsValue.value

            // 3차원 배열: [1, num_boxes, 5]
            val dets = when (detsArray) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (detsArray as Array<Array<FloatArray>>)[0]
                }
                else -> {
                    println("⚠️ YOLOX dets 타입 불명: ${detsArray?.javaClass}")
                    return null
                }
            }

            // 2차원 배열: [1, num_boxes]
            val labels = when (labelsArray) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (labelsArray as Array<LongArray>)[0]
                }
                else -> {
                    println("⚠️ YOLOX labels 타입 불명: ${labelsArray?.javaClass}")
                    return null
                }
            }

            val numBoxes = dets.size
            println("📦 YOLOX: ${numBoxes}개 박스 검출")

            if (numBoxes == 0) {
                return null
            }

            var bestBox: RectF? = null
            var bestScore = 0.3f

            val scaleX = imageWidth / detectorInputSize.first
            val scaleY = imageHeight / detectorInputSize.second

            for (i in 0 until numBoxes) {
                val label = labels[i]
                if (label != 0L) continue  // person class = 0

                val box = dets[i]
                val x1 = box[0] * scaleX
                val y1 = box[1] * scaleY
                val x2 = box[2] * scaleX
                val y2 = box[3] * scaleY
                val score = box[4]

                if (score > bestScore) {
                    bestBox = RectF(x1, y1, x2, y2)
                    bestScore = score
                }
            }

            if (bestBox != null) {
                println("✅ YOLOX: 최고 점수 person 박스 - score: ${String.format("%.2f", bestScore)}")
            }

            return bestBox

        } catch (e: Exception) {
            println("❌ YOLOX 출력 파싱 오류: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun parseRTMPoseSimCCOutput(
        outputs: OrtSession.Result,
        boundingBox: RectF
    ): List<KeypointWithConfidence>? {
        try {
            val simccXValue = outputs[0].value
            val simccYValue = outputs[1].value

            // 3차원 배열: [1, 133, bins]
            val simccX = when (simccXValue) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (simccXValue as Array<Array<FloatArray>>)[0]
                }
                else -> {
                    println("⚠️ simcc_x 타입 불명: ${simccXValue?.javaClass}")
                    return null
                }
            }

            val simccY = when (simccYValue) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (simccYValue as Array<Array<FloatArray>>)[0]
                }
                else -> {
                    println("⚠️ simcc_y 타입 불명: ${simccYValue?.javaClass}")
                    return null
                }
            }

            val numKeypoints = simccX.size
            val xBins = simccX[0].size
            val yBins = simccY[0].size

            println("📊 RTMPose 출력: ${numKeypoints}개 키포인트, xBins=$xBins, yBins=$yBins")

            if (numKeypoints != 133) {
                println("⚠️ 예상치 못한 키포인트 수: $numKeypoints")
            }

            val keypoints = mutableListOf<KeypointWithConfidence>()
            val (poseWidth, poseHeight) = poseInputSize

            for (i in 0 until numKeypoints) {
                val xArray = simccX[i]
                val yArray = simccY[i]

                // X: argmax
                var maxXIdx = 0
                var maxXVal = Float.NEGATIVE_INFINITY
                for (j in xArray.indices) {
                    if (xArray[j] > maxXVal) {
                        maxXVal = xArray[j]
                        maxXIdx = j
                    }
                }

                // Y: argmax
                var maxYIdx = 0
                var maxYVal = Float.NEGATIVE_INFINITY
                for (j in yArray.indices) {
                    if (yArray[j] > maxYVal) {
                        maxYVal = yArray[j]
                        maxYIdx = j
                    }
                }

                val xNorm = (maxXIdx.toFloat() / xBins) * poseWidth
                val yNorm = (maxYIdx.toFloat() / yBins) * poseHeight

                val x = boundingBox.left + (xNorm / poseWidth) * boundingBox.width()
                val y = boundingBox.top + (yNorm / poseHeight) * boundingBox.height()

                val confidence = (maxXVal + maxYVal) / 2f

                keypoints.add(KeypointWithConfidence(x, y, confidence))
            }

            // 손 인식 통계
            if (keypoints.size >= 133) {
                val leftHandAvg = (91..111).map { keypoints[it].confidence }.average().toFloat()
                val rightHandAvg = (112..132).map { keypoints[it].confidence }.average().toFloat()

                println("📊 손 인식 평균 신뢰도 - 왼손: ${String.format("%.2f", leftHandAvg)}, 오른손: ${String.format("%.2f", rightHandAvg)}")
            }

            return keypoints

        } catch (e: Exception) {
            println("❌ RTMPose 출력 파싱 오류: ${e.message}")
            e.printStackTrace()
            return null
        }
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

// Keypoint indices
object KeypointIndex {
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

    const val FACE_START = 17
    const val FACE_END = 84

    const val LEFT_HAND_START = 85
    const val LEFT_HAND_END = 105
    const val RIGHT_HAND_START = 106
    const val RIGHT_HAND_END = 126

    const val LEFT_FOOT_START = 127
    const val RIGHT_FOOT_START = 130
}