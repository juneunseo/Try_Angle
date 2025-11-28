package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer

data class BoundingBox(val x: Float, val y: Float, val width: Float, val height: Float)
data class Keypoint(val x: Float, val y: Float, val confidence: Float)

class OnnxInferenceManager(private val context: Context) {

    private var ortEnvironment: OrtEnvironment? = null
    private var detectionSession: OrtSession? = null
    private var poseSession: OrtSession? = null

    fun initialize() {
        try {
            println("🔵 1. OrtEnvironment 생성 시작")
            ortEnvironment = OrtEnvironment.getEnvironment()
            println("✅ 1. OrtEnvironment 생성 완료")

            // ✅ 파일을 캐시로 복사
            println("🔵 2. 모델 파일 준비 중...")
            val yoloxFile = copyAssetToCache("yolox.ort")
            val rtmposeFile = copyAssetToCache("rtmpose.ort")
            println("✅ 2. 모델 파일 준비 완료")

            // ✅ 파일 경로로 세션 생성 (메모리 절약!)
            println("🔵 3. YOLOX 세션 생성 시작")
            detectionSession = ortEnvironment?.createSession(yoloxFile.absolutePath)
            println("✅ 3. YOLOX 세션 생성 완료")

            println("🔵 4. RTMPose 세션 생성 시작")
            poseSession = ortEnvironment?.createSession(rtmposeFile.absolutePath)
            println("✅ 4. RTMPose 세션 생성 완료")

            println("✅✅✅ ONNX 모델 로드 성공")
        } catch (e: Exception) {
            println("❌❌❌ ONNX 초기화 실패: ${e.javaClass.simpleName}")
            println("❌ 에러 메시지: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    // ✅ assets → 캐시로 복사 (처음 한 번만)
    private fun copyAssetToCache(fileName: String): File {
        val cacheFile = File(context.cacheDir, fileName)

        if (!cacheFile.exists()) {
            println("📂 $fileName 복사 중... (처음 한 번만)")
            val startTime = System.currentTimeMillis()

            context.assets.open(fileName).use { input ->
                cacheFile.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)  // 1MB 버퍼 (더 빠름)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        // 50MB마다 진행 상황 출력
                        if (totalRead % (50 * 1024 * 1024) == 0L) {
                            println("📊 ${totalRead / 1024 / 1024}MB 복사 완료")
                        }
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            println("✅ $fileName 복사 완료 (${String.format("%.1f", elapsed)}초)")
        } else {
            println("✅ $fileName 캐시 사용 (복사 건너뜀)")
        }

        return cacheFile
    }

    fun detectPersons(bitmap: Bitmap): List<BoundingBox> {
        val session = detectionSession ?: return emptyList()

        // 1. Bitmap → 640x640 리사이즈
        val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)

        // 2. 전처리: RGB → Float 배열 정규화
        val inputTensor = preprocessImage(resized, 640, 640)

        // 3. 추론 실행
        val output = session.run(mapOf("images" to inputTensor))

        // 4. 후처리 (TODO: 실제 구현 필요)
        val result = postprocessDetection(output)

        inputTensor.close()
        output.close()

        return result
    }

    fun estimatePose(bitmap: Bitmap, bbox: BoundingBox): List<Keypoint> {
        val session = poseSession ?: return emptyList()

        // 1. Bounding box 영역 크롭
        val cropped = cropBitmap(bitmap, bbox)

        // 2. 256x192 리사이즈
        val resized = Bitmap.createScaledBitmap(cropped, 192, 256, true)

        // 3. 전처리
        val inputTensor = preprocessImage(resized, 192, 256)

        // 4. 추론 실행
        val output = session.run(mapOf("input" to inputTensor))

        // 5. 후처리 (TODO: 실제 구현 필요)
        val result = postprocessPose(output)

        inputTensor.close()
        output.close()
        cropped.recycle()

        return result
    }

    private fun preprocessImage(bitmap: Bitmap, width: Int, height: Int): OnnxTensor {
        val floatBuffer = FloatBuffer.allocate(3 * width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f

                floatBuffer.put(r)
                floatBuffer.put(g)
                floatBuffer.put(b)
            }
        }

        floatBuffer.rewind()
        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        return OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
    }

    private fun cropBitmap(bitmap: Bitmap, bbox: BoundingBox): Bitmap {
        val x = bbox.x.toInt().coerceIn(0, bitmap.width - 1)
        val y = bbox.y.toInt().coerceIn(0, bitmap.height - 1)
        val w = bbox.width.toInt().coerceAtMost(bitmap.width - x)
        val h = bbox.height.toInt().coerceAtMost(bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    private fun postprocessDetection(output: OrtSession.Result): List<BoundingBox> {
        // TODO: YOLOX 출력 파싱
        println("⚠️ postprocessDetection 미구현 - 빈 리스트 반환")
        return emptyList()
    }

    private fun postprocessPose(output: OrtSession.Result): List<Keypoint> {
        // TODO: RTMPose 출력 파싱
        println("⚠️ postprocessPose 미구현 - 빈 리스트 반환")
        return emptyList()
    }

    fun release() {
        detectionSession?.close()
        poseSession?.close()
        ortEnvironment?.close()

        detectionSession = null
        poseSession = null
        ortEnvironment = null
    }
}