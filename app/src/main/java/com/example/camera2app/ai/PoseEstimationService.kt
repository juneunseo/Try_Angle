package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap

/**
 * RTMPoseRunner를 래핑하는 Facade 클래스
 * MainActivity에서 쉽게 사용할 수 있도록 단순화
 */
class PoseEstimationService(private val context: Context) {

    private var rtmPoseRunner: RTMPoseRunner? = null
    private var isInitialized = false

    /**
     * 백그라운드 스레드에서 호출해야 함!
     * ONNX 모델 로딩은 시간이 걸림 (2-3초)
     */
    fun initialize() {
        if (isInitialized) {
            println("⚠️ PoseEstimationService already initialized")
            return
        }

        println("========================================")
        println("🔥 PoseEstimationService 초기화 시작")
        println("========================================")

        val startTime = System.currentTimeMillis()

        // RTMPoseRunner 생성 (내부에서 ONNX Runtime 초기화)
        rtmPoseRunner = RTMPoseRunner(context)

        val elapsed = System.currentTimeMillis() - startTime
        isInitialized = true

        println("========================================")
        println("✅ PoseEstimationService 초기화 완료 (${elapsed}ms)")
        println("========================================")
    }

    /**
     * 포즈 검출
     *
     * @param bitmap 입력 이미지 (RGB)
     * @return 133개 키포인트 (COCO 17 + Face 68 + Hands 42 + Feet 6)
     *         실패 시 null
     */
    fun detectPose(bitmap: Bitmap): RTMPoseResult? {
        if (!isInitialized || rtmPoseRunner == null) {
            println("❌ PoseEstimationService not initialized!")
            return null
        }

        return try {
            rtmPoseRunner!!.detectPose(bitmap)
        } catch (e: Exception) {
            println("❌ Pose detection error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 리소스 정리
     * Activity onDestroy()에서 호출
     */
    fun cleanup() {
        println("🧹 PoseEstimationService cleanup")
        rtmPoseRunner?.close()
        rtmPoseRunner = null
        isInitialized = false
    }

    /**
     * 초기화 상태 확인
     */
    fun isReady(): Boolean = isInitialized
}