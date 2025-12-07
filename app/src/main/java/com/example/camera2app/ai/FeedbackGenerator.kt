package com.example.camera2app.ai

class FeedbackGenerator {

    fun generateFeedback(
        gaps: List<Gap>,
        reference: FrameAnalysis,
        current: CurrentAnalysis,
        isFrontCamera: Boolean = false
    ): List<FeedbackItem> {

        val feedbacks = mutableListOf<FeedbackItem>()

        // ✅ Gap 기반 기본 피드백만 유지
        for (gap in gaps) {
            feedbacks.add(
                FeedbackItem(
                    priority = gap.priority,
                    icon = "ℹ️",
                    message = "자세 보정 중...",
                    category = gap.type.name,
                    currentValue = gap.current,
                    targetValue = gap.target,
                    tolerance = gap.tolerance,
                    unit = null
                )
            )
        }

        return feedbacks
    }
}
