package com.example.camera2app.ai

import android.graphics.RectF

/**
 * 한 프레임에 대한 얼굴 + 포즈 분석 결과
 */
data class FrameAnalysis(
    val faceRect: RectF?,                          // 얼굴 박스
    val poseKeypoints: List<PoseKeypoint>?,        // ✅ 반드시 PoseKeypoint 기반
    val boundingBox: RectF?,                       // 전신 박스
    val brightness: Float? = null,                 // 옵션: 밝기
    val compositionType: CompositionType? = null, // ✅ FeedbackGenerator에서 사용됨
    val depth: Float? = null,                      // ✅ 거리 피드백용
    val gaze: GazeResult? = null
)
