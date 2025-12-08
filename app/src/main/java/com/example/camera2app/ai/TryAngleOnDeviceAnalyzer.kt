package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * TryAngle v1.5 온디바이스 프레임 분석기
 * - RTMPose
 * - DepthEstimator
 * - (옵션) Grounding DINO
 * - OnDeviceFeedbackGenerator 연결
 */
class TryAngleOnDeviceAnalyzer(
    private val context: Context,
    private val enableLegacySystem: Boolean = false
) {

    // ✅ RTMPose
    private val rtmposeRunner = RTMPoseRunner(context)

    // ✅ Depth
    private val depthEstimator = DepthEstimator()

    // ✅ Grounding DINO (Legacy)
    private val groundingDino =
        if (enableLegacySystem) GroundingDinoONNX(context) else null

    // ✅ Feedback Generator
    private val feedbackGenerator =
        OnDeviceFeedbackGenerator(useLegacySystem = enableLegacySystem)

    // ✅ 성능 통계
    private var performanceStats = PerformanceStats()

    // ✅ 병렬 스레드 풀
    private val executor = Executors.newFixedThreadPool(3)

    private val v15Generator = V15FeedbackGenerator.shared


    init {
        Log.d("TryAngle", "✅ TryAngle On-Device Analyzer Initialized")
    }

    // =========================================
    // ✅ 메인 프레임 분석
    // =========================================
    fun analyzeFrame(
        image: Bitmap,
        callback: (TryAngleFeedback) -> Unit
    ) {
        val startTime = SystemClock.elapsedRealtime()

        // ✅ 1️⃣ RTMPose 먼저 실행 → 프리뷰 즉시 반영
        executor.execute {
            val poseResult = try {
                rtmposeRunner.detect(image)
            } catch (e: Exception) {
                Log.e("TryAngle", "❌ RTMPose detect failed", e)
                null
            }

            val processingTime =
                (SystemClock.elapsedRealtime() - startTime) / 1000.0

            // ✅ 2️⃣ Legacy / Depth 없이 빠른 피드백 생성
            val fastFeedback = feedbackGenerator.generateFeedback(
                pose = poseResult,
                legacyBBox = null,           // ✅ 프리뷰에서는 DINO 절대 사용 X
                image = image,
                processingTime = processingTime
            )

            // ✅ 3️⃣ v1.5 Gate 시스템 메시지로 교체
            val gateEvaluation = GateSystem.fromFeedback(fastFeedback)

            val primaryFromV15 =
                V15FeedbackGenerator.shared.generatePrimaryFeedback(gateEvaluation)

            val finalFeedback = fastFeedback.copy(
                primary = primaryFromV15
            )

            // ✅ ✅ ✅ 프리뷰는 여기서 바로 UI 반영 (지연 없음)
            callback(finalFeedback)
        }

        // -------------------------------------------------
        // ⛔ 아래는 "프리뷰"에서는 굳이 안 돌려도 됨
        // -------------------------------------------------

        if (enableLegacySystem) {
            executor.execute {
                try {
                    groundingDino?.detectOne(image)
                } catch (e: Exception) {
                    Log.e("TryAngle", "❌ DINO detect failed", e)
                }
            }
        }

        // Depth도 프리뷰에서는 생략 권장
    }

    // =========================================
    // ✅ 레퍼런스 분석
    // =========================================
    fun analyzeReference(image: Bitmap): ReferenceAnalysis {

        val pose = rtmposeRunner.detect(image)

        val depth = pose?.boundingBox?.let { faceRect ->
            depthEstimator.estimateDistance(
                faceRect = faceRect,
                imageWidth = image.width,
                zoomFactor = 1.0f
            )
        }

        return ReferenceAnalysis(
            pose = pose,
            depth = depth,
            timestamp = System.currentTimeMillis()
        )
    }

    // =========================================
    // ✅ 성능 통계 반환
    // =========================================
    fun getPerformanceStats(): PerformanceStats {
        return performanceStats
    }

    /**
     * v1.6 — reference 포함 분석
     */
    fun analyzeFrameWithReference(
        image: Bitmap,
        referenceImage: Bitmap,
        callback: (TryAngleFeedback) -> Unit
    ) {
        Log.e("TryAngleFlow", "✅ analyzeFrameWithReference() 진입")

        executor.execute {

            val startTime = SystemClock.elapsedRealtime()

            val pose = rtmposeRunner.detect(image)

            val legacyBBox = groundingDino?.detectOne(image)

            val processingTime =
                (SystemClock.elapsedRealtime() - startTime) / 1000.0

            Log.e("TryAngleFlow", "✅ pose = ${pose != null}")
            Log.e("TryAngleFlow", "✅ legacyBBox = ${legacyBBox != null}")

            val feedback = feedbackGenerator.generateFeedback(
                pose = pose,
                legacyBBox = legacyBBox,
                image = image,
                processingTime = processingTime
            )

            Log.e("TryAngleFlow", "✅ feedback.score = ${feedback.compressionInfo?.index}")

            callback(feedback)
        }
    }


    /**
     * v1.6 — 포즈 유사도 계산기 (코사인 기반)
     */
    private fun calculatePoseSimilarityV16(
        p1: PoseResult,
        p2: PoseResult
    ): Float {

        val list1 = p1.keypoints
        val list2 = p2.keypoints

        val min = minOf(list1.size, list2.size)
        if (min == 0) return 1f

        var sum = 0f
        var count = 0

        for (i in 0 until min) {
            val kp1 = list1[i]
            val kp2 = list2[i]

            if (kp1.second < 0.5f || kp2.second < 0.5f) continue

            val dx = kp1.first.x - kp2.first.x
            val dy = kp1.first.y - kp2.first.y
            sum += kotlin.math.sqrt(dx*dx + dy*dy)

            count++
        }

        if (count == 0) return 1f

        val avg = sum / count
        val normalized = (avg / 300f).coerceIn(0f, 1f)

        return ((1f - normalized) * 10f).coerceIn(1f, 10f)
    }

}
