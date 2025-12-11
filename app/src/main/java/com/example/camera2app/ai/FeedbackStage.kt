package com.example.camera2app.ai

enum class FeedbackStage(val displayName: String, val description: String) {
    ASPECT_RATIO("비율", "카메라 비율을 맞추세요"),
    SHOT_TYPE("샷 타입", "전신/상반신/얼굴 구도를 맞추세요"),
    COVERAGE("점유율", "프레임 내 점유율을 조정하세요"),
    POSITION("인물 위치", "인물의 좌우/상하 위치를 맞추세요"),
    FRAMING("프레이밍", "머리 위 공간, 시선 방향 여백을 조정하세요"),
    POSE("포즈", "신체 포즈를 맞추세요"),
    COMPLETE("완벽", "완벽합니다!")
}
