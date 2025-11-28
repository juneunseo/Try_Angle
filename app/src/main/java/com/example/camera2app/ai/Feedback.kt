package com.example.camera2app.ai

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// MARK: - Pose Keypoint

/**
 * 포즈 키포인트 (위치 + 신뢰도)
 */
data class PoseKeypoint(
    val location: PointF,
    val confidence: Float
)

// MARK: - Camera Aspect Ratio

/**
 * 카메라 비율
 */
enum class CameraAspectRatio(val displayName: String, val ratio: Float) {
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_1_1("1:1", 1f);

    companion object {
        /**
         * 레퍼런스 이미지로부터 비율 감지
         */
        fun detect(width: Float, height: Float): CameraAspectRatio {
            // 세로/가로 무관하게 긴 변 / 짧은 변으로 비율 계산
            val longSide = max(width, height)
            val shortSide = min(width, height)
            val ratio = longSide / shortSide

            // 가장 가까운 비율 찾기
            return values().minByOrNull { abs(ratio - it.ratio) } ?: RATIO_4_3
        }
    }
}

// MARK: - Feedback Category System

/**
 * 피드백 카테고리 (우선순위 순서)
 */
enum class FeedbackCategory(val priority: Int, val displayName: String, val icon: String) {
    POSE(1, "포즈", "💪"),
    POSITION(2, "인물 위치", "📍"),
    FRAMING(3, "프레이밍", "🔍"),
    ANGLE(4, "카메라 앵글", "📷"),
    COMPOSITION(5, "구도", "🎨"),
    GAZE(6, "시선", "👀");

    companion object {
        fun fromCategoryString(categoryString: String): FeedbackCategory? {  // 🆕 추가
            return when {
                categoryString.startsWith("pose_") -> POSE
                categoryString.startsWith("position_") -> POSITION
                categoryString.startsWith("framing_") -> FRAMING
                categoryString.startsWith("angle_") -> ANGLE
                categoryString.startsWith("composition_") -> COMPOSITION
                categoryString.startsWith("gaze_") -> GAZE
                categoryString.contains("aspect") -> FRAMING
                categoryString.contains("shot_type") -> FRAMING
                categoryString.contains("headroom") -> FRAMING
                categoryString.contains("leadroom") -> FRAMING
                categoryString.contains("coverage") -> POSITION
                categoryString.contains("nose") -> POSITION
                categoryString.contains("camera_angle") -> ANGLE
                categoryString.contains("gaze") -> GAZE
                else -> null
            }
        }
    }
}

/**
 * 카테고리별 상태 (UI 체크 표시용)
 */

// MARK: - API Response Models

data class AnalysisResponse(
    val userFeedback: List<FeedbackItem>,
    val cameraSettings: CameraSettings,
    val processingTime: String,
    val timestamp: Double
)

/**
 * 개별 피드백 아이템
 */
data class FeedbackItem(
    val priority: Int,
    val icon: String,
    val message: String,
    val category: String,
    val currentValue: Double?,
    val targetValue: Double?,
    val tolerance: Double?,
    val unit: String?
) {
    val id: String = "${category}_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"  // 🆕 추가

    val progress: Double
        get() = when {
            currentValue == null || targetValue == null -> 0.0
            tolerance == null -> {
                val diff = abs((currentValue - targetValue) / max(abs(targetValue), 0.01))
                max(0.0, 1.0 - diff)
            }
            else -> {
                val diff = abs(currentValue - targetValue)
                max(0.0, 1.0 - (diff / tolerance))
            }
        }

    val isCompleted: Boolean
        get() = progress >= 0.95

    val isOvershot: Boolean
        get() = when {
            currentValue == null || targetValue == null -> false
            currentValue > targetValue -> (currentValue - targetValue) > (tolerance ?: 0.0) * 1.5
            else -> false
        }
}

data class CategoryStatus(
    val category: FeedbackCategory,
    val isSatisfied: Boolean,
    val activeFeedbacks: List<FeedbackItem>
) {
    val priority: Int get() = category.priority  // 🆕 추가
}

data class CameraSettings(
    val iso: Int? = null,
    val wbKelvin: Int? = null,
    val evCompensation: Double? = null
)

// MARK: - Completed Feedback Tracking

/**
 * 완료된 피드백 (사라지는 애니메이션용)
 */
data class CompletedFeedback(
    val item: FeedbackItem,
    val completedAt: Long  // System.currentTimeMillis()
) {
    /**
     * 완료된 지 얼마나 지났는지 (초)
     */
    val elapsedTime: Double
        get() = (System.currentTimeMillis() - completedAt) / 1000.0

    /**
     * 아직 표시되어야 하는지 (2초 동안 표시)
     */
    val shouldDisplay: Boolean
        get() = elapsedTime < 2.0

    /**
     * 페이드아웃 진행도 (0.0 ~ 1.0, 1.5초부터 페이드 시작)
     */
    val fadeProgress: Double
        get() {
            return if (elapsedTime < 1.5) {
                1.0  // 완전히 보임
            } else {
                // 1.5초 ~ 2.0초 사이에 페이드아웃
                val fadeTime = elapsedTime - 1.5
                max(0.0, 1.0 - (fadeTime / 0.5))
            }
        }
}