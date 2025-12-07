package com.example.camera2app.ai

data class GazeResult(
    val direction: GazeDirection,
    val horizontalAngle: Float,   // -1.0 ~ 1.0
    val verticalAngle: Float,     // -1.0 ~ 1.0
    val confidence: Float         // 0 ~ 1
)
