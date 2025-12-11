package com.example.camera2app.ai

import android.graphics.PointF

/**
 * ✅ 단일 포즈 키포인트 (x, y + 신뢰도)
 * Swift의 (Point, confidence) 튜플 대응 구조
 */
data class PoseKeypoint(
    val point: PointF,     // 좌표
    val confidence: Float // 신뢰도 (0~1)
)
