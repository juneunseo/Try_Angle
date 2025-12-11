package com.example.camera2app.ai

import android.graphics.RectF

data class FaceAnalysisResult(
    val faceRect: RectF?,        // 얼굴 박스
    val yaw: Float? = null,      // 좌우 회전
    val pitch: Float? = null,    // 상하 회전
    val roll: Float? = null     // 기울기
)
