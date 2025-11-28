package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer

data class BoundingBox(val x: Float, val y: Float, val width: Float, val height: Float)
data class Keypoint(val x: Float, val y: Float, val confidence: Float)

class OnnxInferenceManager(private val context: Context) {

    private var ortEnvironment: OrtEnvironment? = null
    private var poseSession: OrtSession? = null

    // RTMPose 전처리 상수
    private val POSE_WIDTH = 384
    private val POSE_HEIGHT = 288
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun initialize() {
        try {
            println("🔵 1. OrtEnvironment 생성 시작")
            ortEnvironment = OrtEnvironment.getEnvironment()
            println("✅ 1. OrtEnvironment 생성 완료")

            println("🔵 2. RTMPose 모델 파일 준비 중...")
            val rtmposeFile = copyAssetToCache("rtmpose.ort")
            println("✅ 2. RTMPose 모델 파일 준비 완료")

            println("🔵 3. RTMPose 세션 생성 시작")
            poseSession = ortEnvironment?.createSession(rtmposeFile.absolutePath)
            println("✅ 3. RTMPose 세션 생성 완료")

            println("✅✅✅ ONNX 모델 로드 성공")
        } catch (e: Exception) {
            println("❌❌❌ ONNX 초기화 실패: ${e.javaClass.simpleName}")
            println("❌ 에러 메시지: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private fun copyAssetToCache(fileName: String): File {
        val cacheFile = File(context.cacheDir, fileName)

        if (!cacheFile.exists()) {
            println("📂 $fileName 복사 중... (처음 한 번만)")
            val startTime = System.currentTimeMillis()

            context.assets.open(fileName).use { input ->
                cacheFile.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalRead % (50 * 1024 * 1024) == 0L) {
                            println("📊 ${totalRead / 1024 / 1024}MB 복사 완료")
                        }
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            println("✅ $fileName 복사 완료 (${String.format(java.util.Locale.US, "%.1f", elapsed)}초)")
        } else {
            println("✅ $fileName 캐시 사용 (복사 건너뜀)")
        }

        return cacheFile
    }

    // ✅ 단순화: YOLOX 없이 전체 이미지에서 포즈 추정
    fun detectPose(bitmap: Bitmap): List<Keypoint> {
        val session = poseSession ?: return emptyList()

        try {
            // 1. 이미지 전처리 (384x288, mean/std 정규화)
            val inputTensor = preprocessImageForPose(bitmap)

            // 2. 추론 실행
            val output = session.run(mapOf("input" to inputTensor))

            // 3. 후처리: simcc_x, simcc_y → 키포인트 좌표
            val keypoints = postprocessPose(output)

            // 4. 리소스 정리
            inputTensor.close()
            output.close()

            return keypoints
        } catch (e: Exception) {
            println("❌ 포즈 추정 실패: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    // ✅ RTMPose 전처리 (문서 기반)
    private fun preprocessImageForPose(bitmap: Bitmap): OnnxTensor {
        // 1. 리사이즈: 384x288
        val resized = Bitmap.createScaledBitmap(bitmap, POSE_WIDTH, POSE_HEIGHT, true)

        // 2. RGB → Float 배열 (NCHW 순서: [1, 3, 288, 384])
        val floatBuffer = FloatBuffer.allocate(1 * 3 * POSE_HEIGHT * POSE_WIDTH)

        for (c in 0..2) {  // 채널: R, G, B
            for (h in 0 until POSE_HEIGHT) {
                for (w in 0 until POSE_WIDTH) {
                    val pixel = resized.getPixel(w, h)
                    val value = when (c) {
                        0 -> Color.red(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.blue(pixel)
                    } / 255.0f

                    // ✅ mean/std 정규화 적용
                    val normalized = (value - MEAN[c]) / STD[c]
                    floatBuffer.put(normalized)
                }
            }
        }

        floatBuffer.rewind()
        val shape = longArrayOf(1, 3, POSE_HEIGHT.toLong(), POSE_WIDTH.toLong())
        return OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
    }

    // ✅ RTMPose 후처리 (문서 기반)
    private fun postprocessPose(output: OrtSession.Result): List<Keypoint> {
        try {
            // 출력 텐서 가져오기
            val simccX = output[0]?.value as? Array<*> ?: return emptyList()
            val simccY = output[1]?.value as? Array<*> ?: return emptyList()

            // Shape: simcc_x [1, 133, 384], simcc_y [1, 133, 288]
            val simccXArray = simccX[0] as Array<FloatArray>  // [133, 384]
            val simccYArray = simccY[0] as Array<FloatArray>  // [133, 288]

            val keypoints = mutableListOf<Keypoint>()

            // 133개 키포인트 추출
            for (i in 0 until 133) {
                val xHeatmap = simccXArray[i]  // [384]
                val yHeatmap = simccYArray[i]  // [288]

                // argmax: 최대값의 인덱스 찾기
                val xIndex = xHeatmap.argMax()
                val yIndex = yHeatmap.argMax()

                // 좌표 정규화 (0.0 ~ 1.0)
                val x = xIndex / POSE_WIDTH.toFloat()
                val y = yIndex / POSE_HEIGHT.toFloat()

                // 신뢰도: 두 히트맵 최대값의 평균
                val confidence = (xHeatmap[xIndex] + yHeatmap[yIndex]) / 2.0f

                keypoints.add(Keypoint(x, y, confidence))
            }

            println("✅ 키포인트 추출 완료: ${keypoints.size}개")
            return keypoints

        } catch (e: Exception) {
            println("❌ postprocessPose 실패: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    // Helper: FloatArray에서 최대값의 인덱스 찾기
    private fun FloatArray.argMax(): Int {
        var maxIdx = 0
        var maxVal = this[0]
        for (i in 1 until size) {
            if (this[i] > maxVal) {
                maxVal = this[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    fun release() {
        poseSession?.close()
        ortEnvironment?.close()
        poseSession = null
        ortEnvironment = null
    }
}