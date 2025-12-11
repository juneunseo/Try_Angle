package com.example.camera2app.ai.models

/**
 * Unified Feedback Generator Models
 * Ported from iOS TryAngle v1.5 UnifiedFeedbackGenerator.swift
 */

/**
 * Adjustment Action - user-facing instructions
 */
enum class AdjustmentAction(
    val rawValue: String,  // iOS compatibility
    val korean: String = rawValue,
    val needsMirrorForFrontCamera: Boolean = false,
    val involvesZoom: Boolean = false
) {
    MOVE_FORWARD("앞으로 이동", "앞으로 이동", false, false),
    MOVE_BACKWARD("뒤로 이동", "뒤로 이동", false, false),
    MOVE_LEFT("왼쪽으로 이동", "왼쪽으로 이동", true, false),
    MOVE_RIGHT("오른쪽으로 이동", "오른쪽으로 이동", true, false),
    TILT_UP("카메라를 위로", "카메라를 위로", false, false),
    TILT_DOWN("카메라를 아래로", "카메라를 아래로", false, false),
    ZOOM_IN("줌인", "줌인", false, true),
    ZOOM_OUT("줌아웃", "줌아웃", false, true),
    ZOOM_IN_THEN_MOVE_BACK("줌인 후 뒤로", "줌인 후 뒤로", false, true),
    ZOOM_IN_THEN_MOVE_FORWARD("줌인 후 앞으로", "줌인 후 앞으로", false, true),
    ZOOM_OUT_THEN_MOVE_BACK("줌아웃 후 뒤로", "줌아웃 후 뒤로", false, true),
    ZOOM_OUT_THEN_MOVE_FORWARD("줌아웃 후 앞으로", "줌아웃 후 앞으로", false, true),
    CHANGE_ASPECT_RATIO("화면 비율 변경", "화면 비율 변경", false, false),
    ADJUST_POSE("자세 조정", "자세 조정", false, false),
    MAINTAIN_POSITION("현재 위치 유지", "현재 위치 유지", false, false);

    companion object {
        fun fromString(value: String): AdjustmentAction? {
            return entries.find { it.name == value || it.korean == value }
        }
    }

    val displayName: String
        get() = korean
}

/**
 * Unified Feedback - single actionable instruction from multi-gate analysis
 */
data class UnifiedFeedback(
    val primaryAction: AdjustmentAction,
    val magnitude: String,                   // "반 걸음", "한 걸음", "5°", etc.
    val affectedGates: List<Int>,            // Which gates this solves [0-4]
    val expectedResults: List<String>,       // Predicted improvements (Korean)
    val priority: Int,                       // Execution order (1=highest)
    val targetZoom: Float? = null,           // For zoom actions
    val zoomFirst: Boolean = false,          // Zoom before distance adjustment?
    val detailedFeedback: String? = null     // Additional context
) {
    /**
     * Full message for UI display
     */
    fun getFullMessage(): String {
        val base = "${primaryAction.korean} $magnitude"
        return if (detailedFeedback != null) {
            "$base ($detailedFeedback)"
        } else {
            base
        }
    }

    /**
     * Main message (iOS compatibility)
     */
    fun mainMessage(): String {
        if (targetZoom != null && primaryAction.involvesZoom) {
            val zoomText = "%.1fx".format(targetZoom)
            return when (primaryAction) {
                AdjustmentAction.ZOOM_IN -> "${zoomText}로 줌인"
                AdjustmentAction.ZOOM_OUT -> "${zoomText}로 줌아웃"
                AdjustmentAction.ZOOM_IN_THEN_MOVE_BACK ->
                    "${zoomText}로 줌인 후, $magnitude 뒤로 (배경 압축)"
                AdjustmentAction.ZOOM_IN_THEN_MOVE_FORWARD ->
                    "${zoomText}로 줌인 후, $magnitude 앞으로"
                AdjustmentAction.ZOOM_OUT_THEN_MOVE_BACK ->
                    "${zoomText}로 줌아웃 후, $magnitude 뒤로"
                AdjustmentAction.ZOOM_OUT_THEN_MOVE_FORWARD ->
                    "${zoomText}로 줌아웃 후, $magnitude 앞으로 (원근감 강조)"
                else -> "$magnitude ${primaryAction.rawValue}"
            }
        }
        return "$magnitude ${primaryAction.rawValue}"
    }
}

/**
 * Gate Problem - specific issue identified in gate evaluation
 */
data class GateProblem(
    val gate: Int,                  // Gate number 0-4
    val type: ProblemType,
    val severity: Float,            // 0.0 ~ 1.0 (how bad is it)
    val currentValue: Float,        // Actual measured value
    val targetValue: Float          // Desired value
) {
    enum class ProblemType {
        // Gate 1: Framing
        SHOT_TYPE_TOO_WIDE,         // Person too small
        SHOT_TYPE_TOO_NARROW,       // Person too large
        PERSON_CROPPED,             // Body parts cut off

        // Gate 2: Position
        MARGIN_LEFT_HIGH,           // Too much space on left
        MARGIN_RIGHT_HIGH,
        MARGIN_TOP_HIGH,
        MARGIN_BOTTOM_HIGH,
        HIGH_ANGLE,                 // Camera too high
        LOW_ANGLE,                  // Camera too low

        MARGIN_TOP_LOW,
        MARGIN_BOTTOM_LOW,
        COVERAGE_TOO_LOW,
        COVERAGE_TOO_HIGH,

        // Gate 3: Compression
        COMPRESSION_TOO_LOW,        // Too wide angle
        COMPRESSION_TOO_HIGH,       // Too telephoto
        DISTANCE_MISMATCH,          // Wrong distance for framing

        // Gate 4: Pose
        SHOULDER_TILT_OFF,
        ARM_POSITION_OFF,
        LEG_POSITION_OFF,
        HEAD_DIRECTION_OFF,
        POSE_ANGLE_DIFF,    // Added

        // Gate 0: Aspect Ratio
        ASPECT_RATIO_MISMATCH
    }
}

/**
 * Stabilization State - for debouncing feedback changes
 */
data class StabilizationState(
    var lastAction: AdjustmentAction? = null,
    var consecutiveCount: Int = 0,
    var lastUpdateTime: Long = 0,
    var sameActionCount: Int = 0,
    var lastCameraSwitch: Long = 0
) {
    companion object {
        const val MIN_UPDATE_INTERVAL_MS = 300L     // 0.3 seconds
        const val REQUIRED_CONSECUTIVE_FRAMES = 3    // Require 3 consistent frames
        const val MAX_SAME_ACTION_COUNT = 30        // Force reset after 30 identical actions
        const val CAMERA_SWITCH_COOLDOWN_MS = 1000L // 1 second after camera switch
    }

    fun shouldUpdate(newAction: AdjustmentAction, currentTime: Long): Boolean {
        // Cooldown after camera switch
        if (currentTime - lastCameraSwitch < CAMERA_SWITCH_COOLDOWN_MS) {
            return false
        }

        // Minimum time interval
        if (currentTime - lastUpdateTime < MIN_UPDATE_INTERVAL_MS) {
            return false
        }

        // Check if action changed
        if (newAction != lastAction) {
            consecutiveCount = 1
            return false
        }

        // Increment consecutive count
        consecutiveCount++

        // Require consistent action for multiple frames
        if (consecutiveCount < REQUIRED_CONSECUTIVE_FRAMES) {
            return false
        }

        // Force reset if stuck
        if (sameActionCount >= MAX_SAME_ACTION_COUNT) {
            return true
        }

        return true
    }

    fun update(action: AdjustmentAction, currentTime: Long) {
        if (action == lastAction) {
            sameActionCount++
        } else {
            sameActionCount = 1
            consecutiveCount = 1
        }

        lastAction = action
        lastUpdateTime = currentTime
    }

    fun reset() {
        lastAction = null
        consecutiveCount = 0
        sameActionCount = 0
    }

    fun onCameraSwitch(currentTime: Long) {
        lastCameraSwitch = currentTime
        reset()
    }
}
