package com.example.camera2app.ai

// MARK: - Gap (차이)
data class Gap(
    val type: GapType,                    // Gap 타입
    val current: Double?,                 // 현재 값
    val target: Double?,                  // 목표 값
    val difference: Double,               // 차이 (절대값)
    val tolerance: Double,                // 허용 오차
    val priority: Int,                    // 우선순위 (1=높음)
    val metadata: Map<String, Any>? = null // 추가 정보
) {
    // ✅ 허용 오차 이내인지
    val isWithinTolerance: Boolean
        get() = difference <= tolerance
}
