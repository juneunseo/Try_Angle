package com.example.camera2app.ai

data class CalibrationFactor(
    val topRatio: Float,
    val bottomRatio: Float,
    val leftRatio: Float,
    val rightRatio: Float
) {
    companion object {
        val IDENTITY = CalibrationFactor(
            topRatio = 1.0f,
            bottomRatio = 1.0f,
            leftRatio = 1.0f,
            rightRatio = 1.0f
        )
    }
}
