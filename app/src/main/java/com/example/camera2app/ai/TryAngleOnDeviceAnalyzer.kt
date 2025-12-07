package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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

    init {
        android.util.Log.d("TryAngle", "✅ TryAngle On-Device Analyzer Initialized")
    }

    // =========================================
    // ✅ 메인 프레임 분석
    // =========================================
    fun analyzeFrame(image: Bitmap, callback: (TryAngleFeedback) -> Unit) {

        val startTime = SystemClock.elapsedRealtime()

        var poseResult: PoseResult? = null
        var depthResult: DepthResult? = null
        var legacyBBox: RectF? = null

        val latch = CountDownLatch(
            if (enableLegacySystem) 3 else 2
        )

        // 1️⃣ RTMPose
        executor.execute {
            poseResult = rtmposeRunner.detect(image)
            latch.countDown()
        }

        // 2️⃣ Depth
        executor.execute {
            depthResult =
                poseResult?.boundingBox?.let { faceRect ->
                    depthEstimator.estimateDistance(
                        faceRect = faceRect,
                        imageWidth = image.width,
                        zoomFactor = 1.0f
                    )
                }
            latch.countDown()
        }

        // 3️⃣ Grounding DINO (Legacy)
        groundingDino?.let { dino ->
            executor.execute {
                // ✅ detectPerson ❌ → detectOne ✅
                legacyBBox = dino.detectOne(image)
                latch.countDown()
            }
        }

        // ✅ 모든 분석 종료 후 피드백 생성
        executor.execute {
            latch.await()

            val processingTime =
                (SystemClock.elapsedRealtime() - startTime) / 1000.0

            performanceStats.update(processingTime)

            val feedback = feedbackGenerator.generateFeedback(
                pose = poseResult,
                legacyBBox = legacyBBox,
                image = image,
                processingTime = processingTime
            )

            callback(feedback)
        }
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

    fun getPerformanceStats(): PerformanceStats {
        return performanceStats
    }
}
