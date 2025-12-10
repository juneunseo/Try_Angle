package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import com.example.camera2app.ai.DepthAnythingONNX


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
    private val depthONNX = DepthAnythingONNX(context)




    // ✅ Grounding DINO (Legacy)

    // ✅ Legacy DINO 비활성화 (현재 구조는 MainActivity에서 Async로만 사용)
    private val groundingDino: GroundingDinoONNX? = null


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

        // ✅ ✅ ✅ 네이티브 안전 복사본
        val safeBitmap = image.copy(Bitmap.Config.ARGB_8888, false)

        executor.execute {

            val poseResult = try {
                rtmposeRunner.detect(safeBitmap)
            } catch (e: Exception) {
                Log.e("TryAngle", "❌ RTMPose detect failed", e)
                null
            }

            val processingTime =
                (SystemClock.elapsedRealtime() - startTime) / 1000.0

            val fastFeedback = feedbackGenerator.generateFeedback(
                pose = poseResult,
                legacyBBox = null,
                image = safeBitmap,
                processingTime = processingTime
            )

            val gateEvaluation = GateSystem.fromFeedback(fastFeedback)

            val primaryFromV15 =
                V15FeedbackGenerator.shared.generatePrimaryFeedback(gateEvaluation)

            val finalFeedback = fastFeedback.copy(
                primary = primaryFromV15,
                isPersonDetected = poseResult?.keypoints
                    ?.count { it.second > 0.5f }
                    ?.let { it >= 6 } ?: false

            )

            // ✅ ✅ ✅ 딱 1번만 호출
            callback(finalFeedback)
        }


    }


    // =========================================
    // ✅ 레퍼런스 분석
    // =========================================
    fun analyzeReferenceAsync(
        image: Bitmap,
        callback: (ReferenceAnalysis) -> Unit
    ) {

        val pose = rtmposeRunner.detect(image)

        // ✅ 포즈 없으면 Depth 없이 바로 반환
        if (pose?.boundingBox == null) {
            callback(
                ReferenceAnalysis(
                    pose = pose,
                    depth = null,
                    timestamp = System.currentTimeMillis()
                )
            )
            return
        }

        // ✅ ✅ ✅ DepthAnything 비동기 실행
        depthONNX.estimateDepthAsync(image) { depthMap ->

            var depthResult: DepthResult? = null

            if (depthMap != null) {
                // ✅ 중앙 픽셀 depth 값 샘플링 (256x256 기준)
                val centerIndex = (256 * 128) + 128
                val rawDepth = depthMap[centerIndex]

                Log.e("DEPTH_ONNX", "✅ Reference Depth Value = $rawDepth")

                // ✅ ✅ ✅ DepthResult 형태로 변환 (기존 시스템과 호환)
                depthResult = DepthResult(
                    distance = rawDepth,
                    method = DepthMethod.UNAVAILABLE,
                    confidence = 0.5f,
                    isZoomDetected = false,
                    zoomFactor = null
                )

            } else {
                Log.e("DEPTH_ONNX", "❌ Depth Map is null")
            }

            // ✅ ✅ ✅ ✅ ✅ ✅ ✅ ✅ ✅
            // ✅ 콜백으로 ReferenceAnalysis 반환 (딱 이 방식이 정답)
            callback(
                ReferenceAnalysis(
                    pose = pose,
                    depth = depthResult,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
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


            val processingTime =
                (SystemClock.elapsedRealtime() - startTime) / 1000.0

            Log.e("TryAngleFlow", "✅ pose = ${pose != null}")


            val feedback = feedbackGenerator.generateFeedback(
                pose = pose,
                legacyBBox = null,
                image = image,
                processingTime = processingTime
            )



            val finalScore: Float = if (!feedback.isPersonDetected) {
                // ✅ 사람 없음 → GateSystem 자체를 타지 않고 강제 1점
                1.0f
            } else {
                val gateEvaluation = GateSystem.fromFeedback(feedback)
                gateEvaluation.overallScore * 10f
            }


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
