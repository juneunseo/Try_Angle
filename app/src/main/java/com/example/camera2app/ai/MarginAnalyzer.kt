package com.example.camera2app.ai

import android.graphics.RectF
import android.util.SizeF
import com.example.camera2app.ai.models.MarginAnalysisResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Margin Analyzer - Detailed margin and balance analysis
 * Ported from iOS TryAngle v1.5 MarginAnalyzer.swift (503 lines)
 *
 * USAGE:
 * Copy this file to: camera2app_new/app/src/main/java/com/example/camera2app/ai/
 *
 * Features:
 * - Calculates absolute margins (pixels) and relative ratios
 * - Analyzes horizontal and vertical balance
 * - Detects high/low camera angles
 * - Checks if subject is out of frame
 * - Provides step-based feedback ("반 걸음", "한 걸음")
 */
class MarginAnalyzer {

    // Configuration constants
    companion object {
        const val MIN_MARGIN_RATIO = 0.03f      // Minimum margin (3%)
        const val MAX_MARGIN_RATIO = 0.35f      // Maximum margin (35%)
        const val BALANCE_THRESHOLD = 0.08f     // Balance tolerance (8%)
        const val IDEAL_BOTTOM_RATIO = 2.0f     // Ideal bottom:top ratio (2:1)

        // v6 thresholds from Python improved_margin_analyzer.py
        const val HORIZ_PERFECT = 0.05f
        const val HORIZ_GOOD = 0.10f
        const val HORIZ_NEEDS_ADJUST = 0.15f

        const val VERT_PERFECT = 0.05f
        const val VERT_GOOD = 0.10f
        const val VERT_NEEDS_ADJUST = 0.15f

        const val HIGH_ANGLE_THRESHOLD = 0.1f   // Bottom margin < 10% = high angle
        const val LOW_ANGLE_THRESHOLD = 1.5f    // Top margin > 1.5x bottom = low angle
    }

    /**
     * Main analysis function
     * @param bbox Bounding box (normalized 0.0~1.0 or pixel coordinates)
     * @param imageSize Image dimensions
     * @param isNormalized True if bbox is normalized coordinates
     * @return Detailed margin analysis result
     */
    fun analyze(
        bbox: RectF,
        imageSize: SizeF,
        isNormalized: Boolean = true
    ): MarginAnalysisResult {

        // Convert bbox to pixel coordinates if needed
        val pixelBBox = if (isNormalized) {
            RectF(
                bbox.left * imageSize.width,
                bbox.top * imageSize.height,
                bbox.right * imageSize.width,
                bbox.bottom * imageSize.height
            )
        } else {
            bbox
        }

        // Calculate absolute margins (pixels)
        val left = pixelBBox.left
        val right = imageSize.width - pixelBBox.right
        val top = pixelBBox.top
        val bottom = imageSize.height - pixelBBox.bottom

        // Calculate ratios (-0.5 ~ 0.5, negative = out of frame)
        val leftRatioRaw = left / imageSize.width
        val rightRatioRaw = right / imageSize.width
        val topRatioRaw = top / imageSize.height
        val bottomRatioRaw = bottom / imageSize.height

        // Clamp to safe range (-0.5 ~ 0.5)
        val leftRatio = leftRatioRaw.coerceIn(-0.5f, 0.5f)
        val rightRatio = rightRatioRaw.coerceIn(-0.5f, 0.5f)
        val topRatio = topRatioRaw.coerceIn(-0.5f, 0.5f)
        val bottomRatio = bottomRatioRaw.coerceIn(-0.5f, 0.5f)

        // Calculate person's vertical position (0=top, 1=bottom)
        val totalVertical = max(0.001f, topRatio + bottomRatio)
        val personVerticalPosition = topRatio / totalVertical

        // Detect camera angles
        val isHighAngle = bottomRatio > topRatio  // Looking down
        val isLowAngle = topRatio > bottomRatio * LOW_ANGLE_THRESHOLD  // Looking up

        // Check if subject is out of frame
        val outOfFrameWarning = generateOutOfFrameWarning(
            leftRatioRaw, rightRatioRaw, topRatioRaw, bottomRatioRaw
        )

        // Calculate balance scores
        val horizontalBalance = calculateHorizontalBalance(leftRatio, rightRatio)
        val verticalBalance = calculateVerticalBalance(topRatio, bottomRatio)
        val overallBalance = (horizontalBalance * 0.6f + verticalBalance * 0.4f)

        return MarginAnalysisResult(
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            leftRatio = leftRatio,
            rightRatio = rightRatio,
            topRatio = topRatio,
            bottomRatio = bottomRatio,
            horizontalBalance = horizontalBalance,
            verticalBalance = verticalBalance,
            overallBalance = overallBalance,
            personVerticalPosition = personVerticalPosition,
            isHighAngle = isHighAngle,
            isLowAngle = isLowAngle,
            movementDirection = calculateMovementDirection(leftRatio, rightRatio, topRatio, bottomRatio),
            outOfFrameWarning = outOfFrameWarning
        )
    }

    /**
     * Calculate horizontal balance (left vs right)
     * @return Score 0.0 ~ 1.0 (1.0 = perfect)
     */
    private fun calculateHorizontalBalance(leftRatio: Float, rightRatio: Float): Float {
        val diff = abs(leftRatio - rightRatio)

        return when {
            diff <= HORIZ_PERFECT -> 1.0f      // Perfect (within 5%)
            diff <= HORIZ_GOOD -> 0.9f         // Good (within 10%)
            diff <= HORIZ_NEEDS_ADJUST -> 0.7f // Needs adjustment (within 15%)
            else -> (1.0f - diff).coerceAtLeast(0f)
        }
    }

    /**
     * Calculate vertical balance (top vs bottom)
     * Ideal ratio is 2:1 (bottom should be 2x top margin)
     * @return Score 0.0 ~ 1.0 (1.0 = perfect)
     */
    private fun calculateVerticalBalance(topRatio: Float, bottomRatio: Float): Float {
        val diff = abs(topRatio - bottomRatio)

        // Check if ratio is close to ideal 2:1
        val ratio = if (topRatio > 0.001f) bottomRatio / topRatio else 0f
        val ratioDeviation = abs(ratio - IDEAL_BOTTOM_RATIO)

        return when {
            diff <= VERT_PERFECT && ratioDeviation <= 0.3f -> 1.0f  // Perfect
            diff <= VERT_GOOD -> 0.9f                                // Good
            diff <= VERT_NEEDS_ADJUST -> 0.7f                        // Needs adjustment
            else -> (1.0f - diff).coerceAtLeast(0f)
        }
    }

    /**
     * Generate out-of-frame warning message
     * @return Warning string or null if all parts are in frame
     */
    private fun generateOutOfFrameWarning(
        leftRatio: Float,
        rightRatio: Float,
        topRatio: Float,
        bottomRatio: Float
    ): String? {
        val warnings = mutableListOf<String>()

        if (leftRatio < -0.01f) warnings.add("왼쪽이 잘림")
        if (rightRatio < -0.01f) warnings.add("오른쪽이 잘림")
        if (topRatio < -0.01f) warnings.add("위쪽이 잘림")
        if (bottomRatio < -0.01f) warnings.add("아래쪽이 잘림")

        return if (warnings.isNotEmpty()) {
            "⚠️ " + warnings.joinToString(", ")
        } else null
    }

    /**
     * Convert margin difference to step-based feedback
     * @param diff Margin difference (0.0 ~ 1.0)
     * @return Korean feedback ("반 걸음", "한 걸음", etc.)
     */
    fun differenceToSteps(diff: Float): String {
        return when {
            diff <= 0.05f -> "조금"
            diff <= 0.10f -> "반 걸음"
            diff <= 0.20f -> "한 걸음"
            diff <= 0.30f -> "두 걸음"
            else -> "세 걸음"
        }
    }

    /**
     * Generate detailed horizontal feedback
     * @return Korean feedback message
     */
    fun generateHorizontalFeedback(
        leftRatio: Float,
        rightRatio: Float,
        referenceLeftRatio: Float,
        referenceRightRatio: Float
    ): String? {
        val diff = abs(leftRatio - referenceLeftRatio)

        if (diff < HORIZ_PERFECT) return null  // Perfect, no feedback needed

        val steps = differenceToSteps(diff)
        val shiftPercent = (diff * 100).toInt()

        return when {
            leftRatio > referenceLeftRatio -> {
                "피사체가 왼쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 오른쪽으로 ${steps} 이동하세요"
            }
            else -> {
                "피사체가 오른쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 왼쪽으로 ${steps} 이동하세요"
            }
        }
    }

    /**
     * Generate detailed vertical feedback
     * @return Korean feedback message
     */
    fun generateVerticalFeedback(
        topRatio: Float,
        bottomRatio: Float,
        referenceTopRatio: Float,
        referenceBottomRatio: Float,
        isHighAngle: Boolean,
        isLowAngle: Boolean
    ): String? {
        val diff = abs(topRatio - referenceTopRatio)

        if (diff < VERT_PERFECT) return null  // Perfect, no feedback needed

        val shiftPercent = (diff * 100).toInt()

        return when {
            isHighAngle && bottomRatio < HIGH_ANGLE_THRESHOLD -> {
                "하이앵글 (위에서 내려다봄)\n→ 카메라를 ${shiftPercent}% 낮추세요"
            }
            isLowAngle -> {
                "로우앵글 (아래에서 올려다봄)\n→ 카메라를 ${shiftPercent}% 높이세요"
            }
            topRatio > referenceTopRatio -> {
                "피사체가 위쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 카메라를 아래로 내리세요"
            }
            else -> {
                "피사체가 아래쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 카메라를 위로 올리세요"
            }
        }
    }

    /**
     * Generate tilt feedback (camera angle correction)
     * @return Tilt correction message with angle
     */
    fun generateTiltFeedback(
        topRatio: Float,
        bottomRatio: Float,
        isHighAngle: Boolean
    ): Pair<String, Int>? {
        val verticalImbalance = abs(topRatio - bottomRatio)

        if (verticalImbalance < 0.1f) return null  // No significant tilt

        // Estimate tilt angle (rough approximation)
        val tiltAngle = (verticalImbalance * 30).toInt().coerceIn(2, 15)

        val feedback = when {
            isHighAngle && bottomRatio < 0.15f -> "카메라를 ${tiltAngle}° 위로 틸트"
            topRatio < 0.10f -> "카메라를 ${tiltAngle}° 아래로 틸트"
            else -> null
        }

        return feedback?.let { it to tiltAngle }
    }

    /**
     * Comparison Result Data Class
     */
    data class MarginComparisonResult(
        val overallMatch: Float,
        val isMatched: Boolean,
        val adjustmentFeedback: String
    )

    /**
     * Compare current frame with reference
     */
    fun compareWithReference(
        current: RectF,
        reference: RectF,
        currentImageSize: SizeF,
        referenceImageSize: SizeF
    ): MarginComparisonResult {
        val currentResult = analyze(current, currentImageSize)
        val referenceResult = analyze(reference, referenceImageSize)

        // Calculate differences
        val leftDiff = abs(currentResult.leftRatio - referenceResult.leftRatio)
        val rightDiff = abs(currentResult.rightRatio - referenceResult.rightRatio)
        val topDiff = abs(currentResult.topRatio - referenceResult.topRatio)
        val bottomDiff = abs(currentResult.bottomRatio - referenceResult.bottomRatio)
        
        val totalDiff = (leftDiff + rightDiff + topDiff + bottomDiff) / 4f
        val matchScore = (1.0f - totalDiff * 2).coerceIn(0f, 1f)
        
        val isMatched = matchScore >= 0.7f // Threshold

        val feedback = if (isMatched) "Good" else {
             generateHorizontalFeedback(
                 currentResult.leftRatio, currentResult.rightRatio,
                 referenceResult.leftRatio, referenceResult.rightRatio
             ) ?: generateVerticalFeedback(
                 currentResult.topRatio, currentResult.bottomRatio,
                 referenceResult.topRatio, referenceResult.bottomRatio,
                 currentResult.isHighAngle, currentResult.isLowAngle
             ) ?: "조금 더 맞춰보세요"
        }

        return MarginComparisonResult(matchScore, isMatched, feedback)
    }

    private fun calculateMovementDirection(
        left: Float, right: Float, top: Float, bottom: Float
    ): com.example.camera2app.ai.models.MovementDirection? {
        val hDiff = Math.abs(left - right)
        val vDiff = Math.abs(top - bottom)
        
        if (hDiff < HORIZ_PERFECT && vDiff < VERT_PERFECT) return null
        
        var horizDir: com.example.camera2app.ai.models.MovementDirection.HorizontalDirection? = null
        if (hDiff > HORIZ_PERFECT) {
            horizDir = if (left > right) 
                com.example.camera2app.ai.models.MovementDirection.HorizontalDirection.RIGHT 
            else 
                com.example.camera2app.ai.models.MovementDirection.HorizontalDirection.LEFT
        }
        
        var vertDir: com.example.camera2app.ai.models.MovementDirection.VerticalDirection? = null
        if (vDiff > VERT_PERFECT) {
            vertDir = if (top > bottom) 
                com.example.camera2app.ai.models.MovementDirection.VerticalDirection.DOWN 
            else 
                com.example.camera2app.ai.models.MovementDirection.VerticalDirection.UP
        }
        
        return com.example.camera2app.ai.models.MovementDirection(
            horizontal = horizDir,
            vertical = vertDir,
            amount = Math.max(hDiff, vDiff)
        )
    }
}

/**
 * Movement Direction helper (for UI arrows)
 */
