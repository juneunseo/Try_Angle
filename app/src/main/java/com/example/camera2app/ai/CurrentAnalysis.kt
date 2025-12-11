package com.example.camera2app.ai

data class CurrentAnalysis(
    val face: FaceAnalysisResult?,
    val depth: DepthResult?,
    val gaze: GazeResult?,
    val pose: PoseAnalysisResult?
)
