package com.example.camera2app.ai

data class TryAngleFeedback(
    val primary: String,
    val suggestions: List<String>,
    val movement: MovementGuide?,
    val compressionInfo: CompressionInfo?,
    val marginInfo: MarginInfo?,
    val processingTime: Double,
    val isOnDevice: Boolean,
    val usedLegacySystem: Boolean
)

data class MovementGuide(
    val direction: String,
    val arrow: String
)

data class CompressionInfo(
    val index: Float
)

data class MarginInfo(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val leftRatio: Float,
    val rightRatio: Float,
    val topRatio: Float,
    val bottomRatio: Float,
    val balanceScore: Float
)

data class ReferenceAnalysis(
    val pose: PoseResult?,
    val depth: DepthResult?,
    val timestamp: Long
)

data class PerformanceStats(
    var totalFrames: Int = 0,
    var averageTime: Double = 0.0,
    var minTime: Double = Double.MAX_VALUE,
    var maxTime: Double = 0.0
) {
    fun update(time: Double) {
        totalFrames++
        averageTime = ((averageTime * (totalFrames - 1)) + time) / totalFrames
        minTime = minOf(minTime, time)
        maxTime = maxOf(maxTime, time)
    }

    val fps: Double
        get() = if (averageTime > 0) 1.0 / averageTime else 0.0
}
