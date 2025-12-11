package com.example.camera2app.ai


import android.graphics.PointF

data class FaceLandmarks(
    val leftEye: List<PointF>?,
    val rightEye: List<PointF>?,
    val mouth: List<PointF>?
)
