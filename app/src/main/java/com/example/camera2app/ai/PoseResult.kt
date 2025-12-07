package com.example.camera2app.ai

import android.graphics.PointF
import android.graphics.RectF

data class PoseResult(
    val keypoints: List<Pair<PointF, Float>>,
    val boundingBox: RectF
)
