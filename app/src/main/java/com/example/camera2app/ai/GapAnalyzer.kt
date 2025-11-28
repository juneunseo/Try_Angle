package com.example.camera2app.ai

import kotlin.math.abs

// Gap 타입 (16가지)
enum class GapType {
    DISTANCE,           // 거리
    POSITION_X,         // X 위치
    POSITION_Y,         // Y 위치
    TILT,              // 기울기
    FACE_YAW,          // 얼굴 좌우 회전
    FACE_PITCH,        // 얼굴 상하 각도
    CAMERA_ANGLE,      // 카메라 앵글
    GAZE,              // 시선
    COMPOSITION,       // 구도
    LEFT_ARM,          // 왼팔 포즈
    RIGHT_ARM,         // 오른팔 포즈
    LEFT_LEG,          // 왼다리 포즈
    RIGHT_LEG,         // 오른다리 포즈
    MISSING_PARTS,     // 안 보이는 부위
    ASPECT_RATIO,      // 화면 비율
    EXCESSIVE_PADDING  // 과도한 여백
}

data class Gap(
    val type: GapType,
    val current: Double?,
    val target: Double?,
    val difference: Double,
    val tolerance: Double,
    val priority: Int,
    val metadata: Map<String, Any>? = null
) {
    val isWithinTolerance: Boolean
        get() = abs(difference) <= tolerance
}

class GapAnalyzer {

    fun analyzeGaps(
        reference: FrameAnalysis,
        current: FrameAnalysis
    ): List<Gap> {
        // 간단한 구현
        return emptyList()
    }

    fun calculateCompletionScore(gaps: List<Gap>): Double {
        if (gaps.isEmpty()) return 1.0

        val satisfiedGaps = gaps.count { it.isWithinTolerance }
        return satisfiedGaps.toDouble() / gaps.size
    }

    fun sortByPriority(gaps: List<Gap>): List<Gap> {
        return gaps.sortedBy { it.priority }
    }
}