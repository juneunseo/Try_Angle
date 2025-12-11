package com.example.camera2app.ai.models

/**
 * Gate Evaluation System - 5-stage photo composition evaluation
 * Ported from iOS TryAngle v1.5 GateSystem.swift
 */

/**
 * Individual Gate Result
 */
data class GateResult(
    val name: String,
    val score: Float,              // 0.0 ~ 1.0
    val threshold: Float,          // Pass criterion
    val passed: Boolean,           // score >= threshold
    val feedback: String,          // User-facing message (Korean)
    val feedbackIcon: String,      // e.g., "📸", "🔭"
    val category: String,          // e.g., "framing", "compression"
    val debugInfo: String? = null  // Technical info
) {
    companion object {
        fun create(
            name: String,
            score: Float,
            threshold: Float,
            feedback: String,
            icon: String = "",
            category: String = "",
            debugInfo: String? = null
        ): GateResult {
            return GateResult(
                name = name,
                score = score,
                threshold = threshold,
                passed = score >= threshold,
                feedback = feedback,
                feedbackIcon = icon,
                category = category,
                debugInfo = debugInfo
            )
        }
    }
}

/**
 * Complete Gate Evaluation Result
 */
data class GateEvaluation(
    val gate0: GateResult,  // Aspect Ratio
    val gate1: GateResult,  // Framing
    val gate2: GateResult,  // Position
    val gate3: GateResult,  // Compression
    val gate4: GateResult,  // Pose

    val allPassed: Boolean,
    val passedCount: Int,
    val overallScore: Float,
    val primaryFeedback: String,      // First failed gate's feedback
    val allFeedbacks: List<String>,   // All failed gates' feedback
    val currentFailedGate: Int?       // Gate number (0-4) or null if all passed
) {
    companion object {
        fun from(gates: List<GateResult>): GateEvaluation {
            require(gates.size == 5) { "Must have exactly 5 gates" }

            val allPassed = gates.all { it.passed }
            val passedCount = gates.count { it.passed }
            val overallScore = gates.map { it.score }.average().toFloat()

            val failedGates = gates.filter { !it.passed }
            val primaryFeedback = failedGates.firstOrNull()?.feedback ?: "모든 조건을 만족합니다! ✅"
            val allFeedbacks = failedGates.map { it.feedback }
            val currentFailedGate = gates.indexOfFirst { !it.passed }.takeIf { it >= 0 }

            return GateEvaluation(
                gate0 = gates[0],
                gate1 = gates[1],
                gate2 = gates[2],
                gate3 = gates[3],
                gate4 = gates[4],
                allPassed = allPassed,
                passedCount = passedCount,
                overallScore = overallScore,
                primaryFeedback = primaryFeedback,
                allFeedbacks = allFeedbacks,
                currentFailedGate = currentFailedGate
            )
        }
    }
}

/**
 * Margin Analysis Result
 * Ported from iOS MarginAnalyzer.swift
 */
data class MarginAnalysisResult(
    val left: Float,               // Absolute (pixels)
    val right: Float,
    val top: Float,
    val bottom: Float,

    val leftRatio: Float,          // Relative (-0.5 ~ 0.5)
    val rightRatio: Float,
    val topRatio: Float,
    val bottomRatio: Float,

    val horizontalBalance: Float,  // 0.0 ~ 1.0
    val verticalBalance: Float,
    val overallBalance: Float,

    val personVerticalPosition: Float,  // 0=top, 1=bottom
    val isHighAngle: Boolean,
    val isLowAngle: Boolean,
    val movementDirection: MovementDirection? = null,
    val outOfFrameWarning: String?      // If any part outside frame
) {
    companion object {
        const val IDEAL_TOP_BOTTOM_RATIO = 2.0f / 1.0f  // 2:1 (top:bottom)
    }
}

/**
 * Focal Length Info
 * Ported from iOS FocalLengthEstimator.swift
 */
data class FocalLengthInfo(
    val focalLength35mm: Int,           // e.g., 24, 50, 85
    val source: FocalLengthSource,
    val confidence: Float,              // 0.0 ~ 1.0

    val lensType: LensType              // Derived from focal length
) {
    enum class FocalLengthSource {
        EXIF,              // From reference image metadata
        DEPTH_ESTIMATE,    // From Depth Anything model
        ZOOM_CALCULATION,  // From camera zoom factor
        FALLBACK           // Default 50mm
    }

    enum class LensType(val displayName: String, val minMm: Int, val maxMm: Int) {
        WIDE("광각", 0, 35),
        NORMAL("표준", 36, 50),
        SEMI_TELEPHOTO("준망원", 51, 85),
        TELEPHOTO("망원", 86, 200);

        companion object {
            fun from(focalLength: Int): LensType {
                return entries.find { focalLength in it.minMm..it.maxMm } ?: NORMAL
            }
        }
    }

    companion object {
        const val DEFAULT_FOCAL_LENGTH = 50  // Standard lens
        const val IPHONE_BASE_FOCAL_LENGTH = 24  // 1x zoom = 24mm (35mm equivalent)
    }
}

/**
 * Depth Estimation Result
 * Ported from iOS DepthAnythingCoreML.swift
 */
data class DepthResult(
    val compressionIndex: Float,       // 0=wide, 1=telephoto
    val cameraType: CameraType,
    val foregroundDepth: Float,        // Bottom 1/4 average
    val backgroundDepth: Float,        // Top 1/3 average
    val depthDifference: Float         // bg - fg
) {
    enum class CameraType(val displayName: String) {
        WIDE("광각"),              // <0.3
        NORMAL("표준"),            // 0.3-0.5
        SEMI_TELEPHOTO("준망원"),   // 0.5-0.7
        TELEPHOTO("망원");         // >0.7

        companion object {
            fun from(compressionIndex: Float): CameraType {
                return when {
                    compressionIndex < 0.3f -> WIDE
                    compressionIndex < 0.5f -> NORMAL
                    compressionIndex < 0.7f -> SEMI_TELEPHOTO
                    else -> TELEPHOTO
                }
            }
        }
    }
}

/**
 * Movement Direction helper (for UI arrows)
 */
data class MovementDirection(
    val horizontal: HorizontalDirection?,
    val vertical: VerticalDirection?,
    val amount: Float,
    val tiltDirection: TiltDirection? = null,
    val tiltAngle: Int = 0
) {
    enum class HorizontalDirection(val korean: String, val arrow: String) {
        LEFT("왼쪽", "←"),
        RIGHT("오른쪽", "→")
    }

    enum class VerticalDirection(val korean: String, val arrow: String) {
        UP("위", "↑"),
        DOWN("아래", "↓")
    }

    enum class TiltDirection(val korean: String) {
        TILT_UP("위로 틸트"),
        TILT_DOWN("아래로 틸트"),
        LOWER_CAMERA("카메라 낮추기")
    }

    val primaryArrow: String
        get() = when {
            amount > 0.1f && horizontal != null -> horizontal.arrow
            vertical != null -> vertical.arrow
            else -> ""
        }

    val description: String
        get() = getDescriptionInternal()

    private fun getDescriptionInternal(): String {
        val parts = mutableListOf<String>()
        horizontal?.let { parts.add("${it.arrow} ${it.korean}") }
        vertical?.let { parts.add("${it.arrow} ${it.korean}") }
        if (tiltDirection != null && tiltAngle > 0) {
            parts.add("${tiltDirection.korean} ${tiltAngle}°")
        }
        return parts.joinToString(" | ")
    }
}
