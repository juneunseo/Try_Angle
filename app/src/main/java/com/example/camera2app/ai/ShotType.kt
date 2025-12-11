package com.example.camera2app.ai

enum class ShotType(
    val label: String,
    val koreanName: String = label,  // iOS compatibility
    val guideDescription: String = ""  // iOS compatibility
) {
    EXTREME_CLOSE_UP("익스트림 클로즈업", "익스트림 클로즈업", "얼굴 일부만"),
    CLOSE_UP("클로즈업", "클로즈업", "얼굴 중심"),
    MEDIUM_CLOSE_UP("미디엄 클로즈업", "미디엄 클로즈업", "어깨 위까지"),
    MEDIUM_SHOT("미디엄샷", "미디엄샷", "허리 위까지"),
    AMERICAN_SHOT("아메리칸샷", "아메리칸샷", "무릎 위까지"),
    MEDIUM_FULL_SHOT("미디엄 풀샷", "미디엄 풀샷", "무릎 아래까지"),
    FULL_SHOT("풀샷", "풀샷", "전신"),
    LONG_SHOT("롱샷", "롱샷", "전신 + 배경");

    val userFriendlyDescription: String
        get() = when (this) {
            EXTREME_CLOSE_UP -> "얼굴 일부만"
            CLOSE_UP -> "얼굴 중심"
            MEDIUM_CLOSE_UP -> "어깨 위까지"
            MEDIUM_SHOT -> "허리 위까지"
            AMERICAN_SHOT -> "무릎 위까지"
            MEDIUM_FULL_SHOT -> "무릎 아래까지"
            FULL_SHOT -> "전신"
            LONG_SHOT -> "전신 + 배경"
        }

    val headroomRange: ClosedFloatingPointRange<Float>
        get() = when (this) {
            EXTREME_CLOSE_UP -> 0.02f..0.08f
            CLOSE_UP -> 0.05f..0.15f
            MEDIUM_CLOSE_UP -> 0.08f..0.18f
            MEDIUM_SHOT -> 0.10f..0.20f
            AMERICAN_SHOT -> 0.08f..0.15f
            MEDIUM_FULL_SHOT -> 0.05f..0.12f
            FULL_SHOT -> 0.03f..0.10f
            LONG_SHOT -> 0.02f..0.08f
        }
}
