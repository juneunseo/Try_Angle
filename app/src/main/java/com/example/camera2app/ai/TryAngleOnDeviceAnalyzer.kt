package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.example.camera2app.ai.models.RTMPoseResult
import com.example.camera2app.ai.GateSystemEvaluator.AspectRatio

/**
 * TryAngle On-Device Analyzer
 * Integrates RTMPoseRunner, GateSystemEvaluator, and UnifiedFeedbackGenerator.
 */
class TryAngleOnDeviceAnalyzer(
    private val context: Context,
    private val enableLegacySystem: Boolean = false
) {

    private val rtmPoseRunner = RTMPoseRunnerFull(context)
    private val gateEvaluator = GateSystemEvaluator()
    private val feedbackGenerator = UnifiedFeedbackGenerator.getInstance()

    // Reference cache
    private var referenceKeypoints: List<RTMPoseResult.Keypoint>? = null
    private var referenceBBox: RectF? = null
    private var referenceAspectRatio: AspectRatio = AspectRatio.RATIO_4_3

    init {
        rtmPoseRunner.initialize()
    }

    /**
     * Set reference image for comparison
     */
    fun setReference(bitmap: Bitmap) {
        val result = rtmPoseRunner.detectPose(bitmap) ?: return
        referenceKeypoints = result.keypoints
        referenceBBox = result.boundingBox
        
        // Assume 4:3 for reference by default or calculate from bitmap
        val ratio = calculateAspectRatio(bitmap.width, bitmap.height)
        referenceAspectRatio = ratio
    }

    /**
     * Main analysis function
     */
    fun analyzeFrame(bitmap: Bitmap, callback: (TryAngleFeedback) -> Unit) {
        val start = System.nanoTime()

        // 1. Detect Pose
        val poseResult = rtmPoseRunner.detectPose(bitmap)
        
        if (poseResult == null) {
            callback(createEmptyFeedback("사람을 찾을 수 없습니다"))
            return
        }

        // 2. Check Reference
        val refKps = referenceKeypoints
        val refBox = referenceBBox
        
        if (refKps == null || refBox == null) {
            // No reference set - just return basic detection feedback
            callback(createEmptyFeedback("레퍼런스 이미지가 없습니다", isPersonDetected = true))
            return
        }

        // 3. Evaluate Gates
        val currentRatio = calculateAspectRatio(bitmap.width, bitmap.height)
        
        val evaluation = gateEvaluator.evaluate(
            currentKeypoints = poseResult.keypoints,
            referenceKeypoints = refKps,
            currentBBox = poseResult.boundingBox ?: RectF(0f,0f,1f,1f),
            referenceBBox = refBox,
            currentAspectRatio = currentRatio,
            referenceAspectRatio = referenceAspectRatio
        )

        // 4. Generate Feedback
        val unifiedFeedback = feedbackGenerator.generateUnifiedFeedback(
            evaluation = evaluation,
            isFrontCamera = false // TODO: pass actual camera checking
        )

        // 5. Convert to Legacy TryAngleFeedback
        val processingTimeMs = (System.nanoTime() - start) / 1_000_000.0
        
        val primaryMessage = unifiedFeedback?.expectedResults?.firstOrNull() 
            ?: unifiedFeedback?.magnitude?.let { "${unifiedFeedback.primaryAction.displayName} $it" }
            ?: "완벽합니다!"

        val feedback = TryAngleFeedback(
            primary = primaryMessage,
            suggestions = unifiedFeedback?.expectedResults ?: emptyList(),
            movement = null,
            compressionInfo = CompressionInfo(evaluation.gate3.score),
            marginInfo = null,
            processingTime = processingTimeMs,
            isOnDevice = true,
            usedLegacySystem = false,
            isPersonDetected = true
        )

        callback(feedback)
    }

    private fun calculateAspectRatio(w: Int, h: Int): AspectRatio {
        val ratio = if (w > h) w.toFloat() / h else h.toFloat() / w
        return when {
            kotlin.math.abs(ratio - 1.33f) < 0.1f -> AspectRatio.RATIO_4_3
            kotlin.math.abs(ratio - 1.77f) < 0.1f -> AspectRatio.RATIO_16_9
            kotlin.math.abs(ratio - 1.0f) < 0.1f -> AspectRatio.RATIO_1_1
            else -> AspectRatio.RATIO_4_3
        }
    }

    private fun createEmptyFeedback(msg: String, isPersonDetected: Boolean = false): TryAngleFeedback {
        return TryAngleFeedback(
            primary = msg,
            suggestions = emptyList(),
            movement = null,
            compressionInfo = null,
            marginInfo = null,
            processingTime = 0.0,
            isOnDevice = true,
            usedLegacySystem = false,
            isPersonDetected = isPersonDetected
        )
    }
}
