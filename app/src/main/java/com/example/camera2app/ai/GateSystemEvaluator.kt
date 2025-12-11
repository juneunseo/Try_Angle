package com.example.camera2app.ai

import android.graphics.PointF
import android.graphics.RectF
import com.example.camera2app.ai.models.*
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Gate System Evaluator - 5-stage photo composition evaluation
 * Ported from iOS TryAngle v1.5 GateSystem.swift (1590 lines)
 *
 * USAGE:
 * Copy this file to: camera2app_new/app/src/main/java/com/example/camera2app/ai/
 *
 * Gate 0: Aspect Ratio (비율) - Binary check
 * Gate 1: Framing (프레이밍) - Shot type + person size
 * Gate 2: Position (위치/구도) - Margin balance
 * Gate 3: Compression (압축감) - Focal length matching
 * Gate 4: Pose (포즈) - Body angle comparison
 */
class GateSystemEvaluator {

    /**
     * Main evaluation function
     * @return GateEvaluation with 5 gate results
     */
    fun evaluate(
        currentKeypoints: List<RTMPoseResult.Keypoint>,
        referenceKeypoints: List<RTMPoseResult.Keypoint>,
        currentBBox: RectF,
        referenceBBox: RectF,
        currentAspectRatio: AspectRatio,
        referenceAspectRatio: AspectRatio,
        currentDepth: DepthResult? = null,
        referenceDepth: DepthResult? = null,
        currentFocalLength: FocalLengthInfo? = null,
        referenceFocalLength: FocalLengthInfo? = null
    ): GateEvaluation {

        val gate0 = evaluateAspectRatio(currentAspectRatio, referenceAspectRatio)
        val gate1 = evaluateFraming(currentKeypoints, referenceKeypoints, currentBBox, referenceBBox)
        val gate2 = evaluatePosition(currentKeypoints, referenceKeypoints, currentBBox, referenceBBox)
        val gate3 = evaluateCompression(currentDepth, referenceDepth, currentFocalLength, referenceFocalLength)
        val gate4 = evaluatePose(currentKeypoints, referenceKeypoints)

        return GateEvaluation.from(listOf(gate0, gate1, gate2, gate3, gate4))
    }

    // ===== Gate 0: Aspect Ratio (비율) =====
    // Binary evaluation - must match exactly
    // Threshold: 1.0 (100%)

    private fun evaluateAspectRatio(current: AspectRatio, reference: AspectRatio): GateResult {
        val passed = current == reference
        val score = if (passed) 1.0f else 0.0f
        val feedback = if (passed) {
            "✓ 화면 비율 일치"
        } else {
            "카메라 비율을 ${reference.displayName}(으)로 변경하세요"
        }

        return GateResult.create(
            name = "Aspect Ratio",
            score = score,
            threshold = 1.0f,
            feedback = feedback,
            icon = "📸",
            category = "aspect_ratio"
        )
    }

    // ===== Gate 1: Framing (프레이밍) =====
    // Evaluates shot type and person size in frame
    // Threshold: 0.70 (70%)

    private fun evaluateFraming(
        currentKeypoints: List<RTMPoseResult.Keypoint>,
        referenceKeypoints: List<RTMPoseResult.Keypoint>,
        currentBBox: RectF,
        referenceBBox: RectF
    ): GateResult {

        // Detect shot type from keypoints
        val currentShotType = ShotTypeDetector.fromKeypoints(currentKeypoints)
        val referenceShotType = ShotTypeDetector.fromKeypoints(referenceKeypoints)

        // Calculate person occupation ratio
        val currentSize = currentBBox.width() * currentBBox.height()
        val referenceSize = referenceBBox.width() * referenceBBox.height()
        val sizeRatio = currentSize / referenceSize.coerceAtLeast(0.01f)

        val score: Float
        val feedback: String

        when {
            // Perfect match: same shot type + size within 30%
            currentShotType == referenceShotType && abs(sizeRatio - 1.0f) < 0.3f -> {
                score = 1.0f
                feedback = "✓ 프레이밍 완벽"
            }
            // Same shot type but size mismatch
            currentShotType == referenceShotType -> {
                score = 0.7f
                val sizeDiff = ((abs(sizeRatio - 1.0f) * 100).toInt())
                feedback = if (sizeRatio > 1.3f) {
                    "피사체가 ${sizeDiff}% 크게 보입니다. 반 걸음 뒤로 물러나세요"
                } else {
                    "피사체가 ${sizeDiff}% 작게 보입니다. 반 걸음 앞으로 다가가세요"
                }
            }
            // Different shot type - too close (need to move back)
            currentShotType.ordinal < referenceShotType.ordinal -> {
                score = 0.4f
                feedback = "${referenceShotType.koreanName}을 위해 한 걸음 뒤로 물러나세요\n목표: ${referenceShotType.guideDescription}"
            }
            // Different shot type - too far (need to move forward)
            else -> {
                score = 0.4f
                feedback = "${referenceShotType.koreanName}을 위해 한 걸음 앞으로 다가가세요\n목표: ${referenceShotType.guideDescription}"
            }
        }

        return GateResult.create(
            name = "Framing",
            score = score,
            threshold = 0.70f,
            feedback = feedback,
            icon = "🎯",
            category = "framing",
            debugInfo = "Current: ${currentShotType.koreanName}, Ref: ${referenceShotType.koreanName}, Size: ${(sizeRatio * 100).toInt()}%"
        )
    }

    // ===== Gate 2: Position (위치/구도) =====
    // Evaluates horizontal and vertical balance
    // Threshold: 0.80 (80%)

    private fun evaluatePosition(
        currentKeypoints: List<RTMPoseResult.Keypoint>,
        referenceKeypoints: List<RTMPoseResult.Keypoint>,
        currentBBox: RectF,
        referenceBBox: RectF
    ): GateResult {

        // Calculate margins from bounding box
        val currentMargins = calculateMargins(currentBBox)
        val referenceMargins = calculateMargins(referenceBBox)

        // Horizontal balance (left vs right)
        val horizDiff = abs(currentMargins.leftRatio - referenceMargins.leftRatio)
        val horizScore = (1.0f - horizDiff * 2.0f).coerceIn(0f, 1f)

        // Vertical balance (top vs bottom)
        val vertDiff = abs(currentMargins.topRatio - referenceMargins.topRatio)
        val vertScore = (1.0f - vertDiff * 2.0f).coerceIn(0f, 1f)

        // Combined score (horizontal weighted higher: 60% / 40%)
        val score = (horizScore * 0.6f + vertScore * 0.4f)

        val feedback = when {
            score >= 0.80f -> "✓ 위치 완벽"
            // Horizontal imbalance is larger
            horizDiff > vertDiff -> {
                val shiftPercent = (horizDiff * 100).toInt()
                if (currentMargins.leftRatio > referenceMargins.leftRatio) {
                    "피사체가 왼쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 오른쪽으로 이동하세요"
                } else {
                    "피사체가 오른쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 왼쪽으로 이동하세요"
                }
            }
            // Vertical imbalance is larger
            else -> {
                val shiftPercent = (vertDiff * 100).toInt()
                if (currentMargins.topRatio > referenceMargins.topRatio) {
                    "피사체가 위쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 카메라를 아래로 내리세요"
                } else {
                    "피사체가 아래쪽으로 ${shiftPercent}% 치우쳐 있습니다\n→ 카메라를 위로 올리세요"
                }
            }
        }

        return GateResult.create(
            name = "Position",
            score = score,
            threshold = 0.80f,
            feedback = feedback,
            icon = "📐",
            category = "position",
            debugInfo = "Horiz: ${(horizScore * 100).toInt()}%, Vert: ${(vertScore * 100).toInt()}%"
        )
    }

    // ===== Gate 3: Compression (압축감/초점거리) =====
    // Evaluates focal length matching
    // Threshold: 0.70 (70%)

    private fun evaluateCompression(
        currentDepth: DepthResult?,
        referenceDepth: DepthResult?,
        currentFocal: FocalLengthInfo?,
        referenceFocal: FocalLengthInfo?
    ): GateResult {

        // If no focal length info available, return default pass
        if (currentFocal == null || referenceFocal == null) {
            return GateResult.create(
                name = "Compression",
                score = 0.85f,
                threshold = 0.70f,
                feedback = "✓ 초점거리 적절",
                icon = "🔭",
                category = "compression"
            )
        }

        // Calculate focal length difference (35mm equivalent)
        val focalDiff = abs(currentFocal.focalLength35mm - referenceFocal.focalLength35mm)
        val score = (1.0f - focalDiff / 50.0f).coerceIn(0f, 1f)

        val feedback = when {
            score >= 0.70f -> {
                "✓ 초점거리 일치 (${currentFocal.lensType.displayName} ${currentFocal.focalLength35mm}mm)"
            }
            currentFocal.focalLength35mm < referenceFocal.focalLength35mm -> {
                val diff = referenceFocal.focalLength35mm - currentFocal.focalLength35mm
                "현재 ${currentFocal.lensType.displayName} ${currentFocal.focalLength35mm}mm\n" +
                "→ ${diff}mm 더 망원으로 (${referenceFocal.lensType.displayName} ${referenceFocal.focalLength35mm}mm)\n" +
                "뒤로 물러나서 줌인하세요"
            }
            else -> {
                val diff = currentFocal.focalLength35mm - referenceFocal.focalLength35mm
                "현재 ${currentFocal.lensType.displayName} ${currentFocal.focalLength35mm}mm\n" +
                "→ ${diff}mm 더 광각으로 (${referenceFocal.lensType.displayName} ${referenceFocal.focalLength35mm}mm)\n" +
                "앞으로 다가가거나 줌아웃하세요"
            }
        }

        return GateResult.create(
            name = "Compression",
            score = score,
            threshold = 0.70f,
            feedback = feedback,
            icon = "🔭",
            category = "compression",
            debugInfo = "Current: ${currentFocal.focalLength35mm}mm (${currentFocal.lensType}), Ref: ${referenceFocal.focalLength35mm}mm, Diff: ${focalDiff}mm"
        )
    }

    // ===== Gate 4: Pose (포즈) =====
    // Evaluates body angle matching (primarily shoulder tilt)
    // Threshold: 0.70 (70%)

    private fun evaluatePose(
        currentKeypoints: List<RTMPoseResult.Keypoint>,
        referenceKeypoints: List<RTMPoseResult.Keypoint>
    ): GateResult {

        // Need at least 17 core body keypoints
        if (currentKeypoints.size < 17 || referenceKeypoints.size < 17) {
            return GateResult.create(
                name = "Pose",
                score = 0.5f,
                threshold = 0.70f,
                feedback = "포즈를 감지할 수 없습니다",
                icon = "🤸",
                category = "pose"
            )
        }

        // Compare shoulder angles (primary pose indicator)
        val currentShoulder = calculateShoulderAngle(currentKeypoints)
        val referenceShoulder = calculateShoulderAngle(referenceKeypoints)
        val shoulderDiff = abs(currentShoulder - referenceShoulder)

        // Score based on 15-degree threshold
        val score = (1.0f - shoulderDiff / 15.0f).coerceIn(0f, 1f)

        val feedback = when {
            score >= 0.70f -> "✓ 포즈 일치"
            shoulderDiff > 10f -> {
                val degrees = shoulderDiff.toInt()
                if (currentShoulder > referenceShoulder) {
                    "어깨가 ${degrees}° 왼쪽으로 기울어져 있습니다\n→ 몸을 오른쪽으로 ${degrees}° 기울이세요"
                } else {
                    "어깨가 ${degrees}° 오른쪽으로 기울어져 있습니다\n→ 몸을 왼쪽으로 ${degrees}° 기울이세요"
                }
            }
            else -> {
                val degrees = shoulderDiff.toInt()
                "자세를 ${degrees}° 조정하세요"
            }
        }

        return GateResult.create(
            name = "Pose",
            score = score,
            threshold = 0.70f,
            feedback = feedback,
            icon = "🤸",
            category = "pose",
            debugInfo = "Shoulder angle diff: ${shoulderDiff.toInt()}° (current: ${currentShoulder.toInt()}°, ref: ${referenceShoulder.toInt()}°)"
        )
    }

    // ===== Helper Functions =====

    /**
     * Calculate margins from bounding box
     * Returns normalized ratios (0.0 ~ 1.0)
     */
    private fun calculateMargins(bbox: RectF): SimpleMargins {
        return SimpleMargins(
            leftRatio = bbox.left,
            rightRatio = 1.0f - bbox.right,
            topRatio = bbox.top,
            bottomRatio = 1.0f - bbox.bottom
        )
    }

    /**
     * Calculate shoulder tilt angle in degrees
     * Uses atan2 to get angle between left and right shoulder
     */
    private fun calculateShoulderAngle(keypoints: List<RTMPoseResult.Keypoint>): Float {
        val leftShoulder = keypoints[RTMPoseResult.LEFT_SHOULDER].point
        val rightShoulder = keypoints[RTMPoseResult.RIGHT_SHOULDER].point
        val dx = (rightShoulder.x - leftShoulder.x).toDouble()
        val dy = (rightShoulder.y - leftShoulder.y).toDouble()
        return Math.toDegrees(atan2(dy, dx)).toFloat()
    }

    /**
     * Simple margin structure (normalized ratios)
     */
    data class SimpleMargins(
        val leftRatio: Float,
        val rightRatio: Float,
        val topRatio: Float,
        val bottomRatio: Float
    )

    /**
     * Aspect Ratio enum
     */
    enum class AspectRatio(val displayName: String) {
        RATIO_4_3("4:3"),
        RATIO_16_9("16:9"),
        RATIO_1_1("1:1")
    }
}

/**
 * Shot Type Detector - Determines framing based on visible body parts
 * Ported from iOS GateSystem.swift ShotTypeGate.fromKeypoints() (lines 161-247)
 *
 * Algorithm: Find lowest visible body part
 * - Ankles/Feet visible → Full Shot
 * - Knees visible → Medium Full Shot
 * - Hips + Elbows visible → Medium Shot (허리샷)
 * - Hips only → American Shot (허벅지샷)
 * - Elbows visible → Medium Close-Up (바스트샷)
 * - Shoulders + face landmarks → Close-Up
 * - Shoulders only → Medium Close-Up
 * - Face only → Extreme Close-Up
 */
object ShotTypeDetector {

    private const val MIN_CONFIDENCE = 0.3f
    private const val STRICT_CONFIDENCE = 0.5f

    fun fromKeypoints(keypoints: List<RTMPoseResult.Keypoint>): ShotType {
        if (keypoints.size < 17) return ShotType.MEDIUM_SHOT

        // Helper: Check if keypoint is visible with valid Y coordinate
        fun isVisible(idx: Int, threshold: Float = MIN_CONFIDENCE): Boolean {
            if (idx >= keypoints.size) return false
            val kp = keypoints[idx]
            return kp.confidence > threshold && kp.point.y in 0.0f..1.05f
        }

        // Find lowest visible body part (Python framing_analyzer.py logic)
        var lowestY = 0.0f
        var lowestPart = "face"

        // Check parts in order: face → shoulder → elbow → hip → knee → ankle
        val checkParts = listOf(
            "face" to listOf(0),              // Nose
            "shoulder" to listOf(5, 6),       // Left, Right shoulder
            "elbow" to listOf(7, 8),          // Elbows
            "hip" to listOf(11, 12),          // Hips
            "knee" to listOf(13, 14),         // Knees
            "ankle" to listOf(15, 16)         // Ankles
        )

        for ((partName, indices) in checkParts) {
            for (idx in indices) {
                if (isVisible(idx)) {
                    val y = keypoints[idx].point.y
                    if (y > lowestY) {
                        lowestY = y
                        lowestPart = partName
                    }
                }
            }
        }

        // Check feet keypoints (17-22) with strict threshold
        val hasFeet = keypoints.size > 22 &&
                      (17..22).any { isVisible(it, STRICT_CONFIDENCE) }

        // Count face landmarks (23-90)
        val faceCount = if (keypoints.size > 90) {
            (23..90).count { isVisible(it) }
        } else 0

        // Determine shot type based on lowest visible part
        return when {
            lowestPart == "ankle" || hasFeet -> ShotType.FULL_SHOT

            lowestPart == "knee" -> ShotType.MEDIUM_FULL_SHOT

            lowestPart == "hip" -> {
                val hasElbows = isVisible(7) || isVisible(8)
                if (hasElbows) ShotType.MEDIUM_SHOT else ShotType.AMERICAN_SHOT
            }

            lowestPart == "elbow" -> ShotType.MEDIUM_CLOSE_UP

            lowestPart == "shoulder" -> {
                if (faceCount > 50) ShotType.CLOSE_UP else ShotType.MEDIUM_CLOSE_UP
            }

            else -> ShotType.EXTREME_CLOSE_UP
        }
    }
}
