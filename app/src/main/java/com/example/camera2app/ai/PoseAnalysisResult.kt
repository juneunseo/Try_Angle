package com.example.camera2app.ai

data class PoseAnalysisResult(
    val keypoints: List<PoseKeypoint>?,   // ✅ 아까 만든 PoseKeypoint 사용
    val confidence: Float? = null        // 전체 포즈 신뢰도
)
