package com.example.camera2app.ai

import android.graphics.PointF
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


// ✅ Camera Aspect Ratio
enum class CameraAspectRatio(val displayName: String) {
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    RATIO_1_1("1:1");

    val ratio: Float
        get() = when (this) {
            RATIO_16_9 -> 16f / 9f
            RATIO_4_3 -> 4f / 3f
            RATIO_1_1 -> 1f
        }

    companion object {
        fun detect(width: Int, height: Int): CameraAspectRatio {
            val longSide = max(width, height).toFloat()
            val shortSide = min(width, height).toFloat()
            val ratio = longSide / shortSide

            val diff16_9 = abs(ratio - 16f / 9f)
            val diff4_3 = abs(ratio - 4f / 3f)
            val diff1_1 = abs(ratio - 1f)

            return listOf(
                RATIO_16_9 to diff16_9,
                RATIO_4_3 to diff4_3,
                RATIO_1_1 to diff1_1
            ).minByOrNull { it.second }?.first ?: RATIO_4_3
        }

        fun from(mode: Int): CameraAspectRatio {
            return when (mode) {
                0 -> RATIO_4_3
                1 -> RATIO_16_9
                2 -> RATIO_1_1
                else -> RATIO_4_3
            }
        }
    }
}

// ✅ Feedback Category System
enum class FeedbackCategory(
    val priority: Int,
    val displayName: String,
    val icon: String
) {
    POSE(1, "포즈", "💪"),
    POSITION(2, "인물 위치", "📍"),
    FRAMING(3, "프레이밍", "🔍"),
    ANGLE(4, "카메라 앵글", "📷"),
    COMPOSITION(5, "구도", "🎨"),
    GAZE(6, "시선", "👀");

    companion object {
        fun from(categoryString: String): FeedbackCategory? {
            return when {
                categoryString.startsWith("pose_") || categoryString == "pose" -> POSE
                categoryString == "position_x" || categoryString == "position_y" -> POSITION
                categoryString in listOf(
                    "distance",
                    "aspect_ratio",
                    "padding",
                    "framing",
                    "photography_framing"
                ) -> FRAMING
                categoryString == "camera_angle" || categoryString == "tilt" -> ANGLE
                categoryString == "composition" -> COMPOSITION
                categoryString == "gaze" || categoryString == "face_yaw" -> GAZE
                else -> null
            }
        }
    }
}

// ✅ Category Status (UI 체크용)
data class CategoryStatus(
    val category: FeedbackCategory,
    val isSatisfied: Boolean,
    val activeFeedbacks: List<FeedbackItem>
) {
    val id: String get() = category.name
    val priority: Int get() = category.priority
    val primaryMessage: String? get() = activeFeedbacks.firstOrNull()?.message
}

// ✅ Feedback Item
data class FeedbackItem(
    val priority: Int,
    val icon: String,
    val message: String,
    val category: String,

    val currentValue: Double? = null,
    val targetValue: Double? = null,
    val tolerance: Double? = null,
    val unit: String? = null
) {
    val id: String get() = category

    val progress: Double
        get() {
            if (currentValue == null || targetValue == null) return 0.0
            val range = tolerance ?: 1.0
            val diff = abs(targetValue - currentValue)
            return max(0.0, min(1.0, 1.0 - (diff / range)))
        }


    val isCompleted: Boolean
        get() {
            if (currentValue == null || targetValue == null || tolerance == null) return false
            return abs(currentValue - targetValue) <= tolerance
        }


    val isOvershot: Boolean
        get() {
            if (currentValue == null || targetValue == null) return false
            return (targetValue >= 0 && currentValue > targetValue) ||
                    (targetValue < 0 && currentValue < targetValue)
        }
}

// ✅ API Response Models
data class AnalysisResponse(
    val userFeedback: List<FeedbackItem>,
    val cameraSettings: CameraSettings,
    val processingTime: String,
    val timestamp: Double
)

data class CameraSettings(
    val iso: Int?,
    val wbKelvin: Int?,
    val evCompensation: Double?
)

// ✅ Completed Feedback Tracking
data class CompletedFeedback(
    val item: FeedbackItem,
    val completedAt: Date
) {
    val id: String get() = item.id

    val elapsedTime: Double
        get() = (System.currentTimeMillis() - completedAt.time) / 1000.0

    val shouldDisplay: Boolean
        get() = elapsedTime < 2.0

    val fadeProgress: Double
        get() = if (elapsedTime < 1.5) {
            1.0
        } else {
            val fadeTime = elapsedTime - 1.5
            max(0.0, 1.0 - (fadeTime / 0.5))
        }
}
