package com.example.camera2app.ai.models

import android.graphics.PointF
import android.graphics.RectF

/**
 * RTMPose Result - 133 keypoints output from RTMPose ONNX model
 * Ported from iOS TryAngle v1.5
 */
data class RTMPoseResult(
    val keypoints: List<Keypoint>,  // 133 keypoints (WholeBody format)
    val boundingBox: RectF?,        // Person detection box (normalized 0.0~1.0)
    val confidence: Float = 0.0f     // Overall detection confidence
) {
    data class Keypoint(
        val point: PointF,           // Normalized coordinates (0.0~1.0)
        val confidence: Float        // Keypoint confidence (0.0~1.0)
    )

    companion object {
        // Keypoint indices (RTMPose WholeBody - 133 points)
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

        // Feet keypoints: 17-22
        const val FEET_START = 17
        const val FEET_END = 22

        // Face landmarks: 23-90
        const val FACE_START = 23
        const val FACE_END = 90

        // Hand keypoints: 91-132 (21 per hand)
        const val LEFT_HAND_START = 91
        const val LEFT_HAND_END = 111
        const val RIGHT_HAND_START = 112
        const val RIGHT_HAND_END = 132

        const val MIN_CONFIDENCE = 0.3f
    }
}

/**
 * Shot Type Enum - 8 framing types based on body visibility
 * Ported from iOS GateSystem.swift ShotTypeGate
 */
enum class ShotType(val displayName: String, val koreanName: String) {
    EXTREME_CLOSE_UP("Extreme Close-Up", "익스트림 클로즈업"),  // 0: 눈만
    CLOSE_UP("Close-Up", "클로즈업"),                         // 1: 얼굴
    MEDIUM_CLOSE_UP("Medium Close-Up", "미디엄 클로즈업"),     // 2: 바스트샷
    MEDIUM_SHOT("Medium Shot", "미디엄샷"),                    // 3: 허리
    AMERICAN_SHOT("American Shot", "아메리칸샷"),              // 4: 허벅지
    MEDIUM_FULL_SHOT("Medium Full Shot", "미디엄 풀샷"),       // 5: 무릎
    FULL_SHOT("Full Shot", "풀샷"),                           // 6: 전신
    LONG_SHOT("Long Shot", "롱샷");                           // 7: 전신+배경

    companion object {
        fun fromInt(value: Int): ShotType? {
            return entries.getOrNull(value)
        }
    }
}

/**
 * Body Structure - extracted keypoint structure for analysis
 * Ported from iOS GateSystem.swift (lines 1387-1472)
 */
data class BodyStructure(
    val face: PointF?,              // Average of face keypoints
    val shoulders: Pair<PointF, PointF>?,  // Left, Right
    val elbows: Pair<PointF, PointF>?,
    val wrists: Pair<PointF, PointF>?,
    val hips: Pair<PointF, PointF>?,
    val knees: Pair<PointF, PointF>?,
    val ankles: Pair<PointF, PointF>?,
    val feet: List<PointF>,         // 6 foot keypoints

    val centroid: PointF,           // Body center point
    val verticalSpan: Float,        // Height in normalized coords
    val horizontalSpan: Float,      // Width in normalized coords

    val visibleParts: Set<BodyPart>,
    val tier: BodyTier              // Full body vs upper body
) {
    enum class BodyPart {
        FACE, SHOULDERS, ELBOWS, WRISTS, HIPS, KNEES, ANKLES, FEET
    }

    enum class BodyTier {
        FULL_BODY,      // Ankles or feet visible
        UPPER_BODY      // Only upper body visible
    }
}

/**
 * Pose Comparison Result
 * Ported from iOS AdaptivePoseComparator.swift
 */
data class PoseComparisonResult(
    val poseType: PoseType,
    val visibleGroups: List<KeypointGroup>,
    val missingGroups: List<KeypointGroup>,
    val comparableKeypoints: List<Int>,        // Indices to compare
    val angleDifferences: Map<String, Float>,  // Body part -> angle diff (+: raise, -: lower)
    val angleDirections: Map<String, String>,  // Body part -> Korean feedback
    val overallAccuracy: Double                // 0.0 ~ 1.0
) {
    enum class PoseType {
        STANDING, SITTING, LYING, CROUCHING, UNKNOWN
    }

    enum class KeypointGroup {
        FACE, SHOULDERS, ARMS, HANDS, TORSO, LEGS, FEET
    }
}
