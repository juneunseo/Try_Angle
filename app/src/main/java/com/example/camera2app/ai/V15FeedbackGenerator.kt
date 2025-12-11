package com.example.camera2app.ai

import kotlin.math.roundToInt
import com.example.camera2app.ai.models.*

// =========================================================
// ✅ v1.5 피드백 생성기 (Gate System 기반)
// iOS: V15FeedbackGenerator.swift 완전 대응
// =========================================================
class V15FeedbackGenerator private constructor() {

    companion object {
        val shared: V15FeedbackGenerator by lazy { V15FeedbackGenerator() }
    }

    // =========================================================
    // ✅ Gate 평가 → FeedbackItem 변환
    // =========================================================
    fun generateFeedbackItems(evaluation: GateEvaluation): List<FeedbackItem> {
        val items = mutableListOf<FeedbackItem>()

        // Gate 1: 여백 균형
        if (!evaluation.gate1.passed) {
            items.add(
                FeedbackItem(
                    priority = 1,
                    icon = "↔️",
                    message = evaluation.gate1.feedback,
                    category = "v15_margin_balance",
                    currentValue = evaluation.gate1.score.toDouble(),
                    targetValue = evaluation.gate1.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        // Gate 2: 프레이밍
        if (!evaluation.gate2.passed) {
            items.add(
                FeedbackItem(
                    priority = 2,
                    icon = "📐",
                    message = evaluation.gate2.feedback,
                    category = "v15_framing",
                    currentValue = evaluation.gate2.score.toDouble(),
                    targetValue = evaluation.gate2.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        // Gate 3: 구도
        if (!evaluation.gate3.passed) {
            items.add(
                FeedbackItem(
                    priority = 3,
                    icon = "🎯",
                    message = evaluation.gate3.feedback,
                    category = "v15_composition",
                    currentValue = evaluation.gate3.score.toDouble(),
                    targetValue = evaluation.gate3.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        // Gate 4: 압축감
        if (!evaluation.gate4.passed) {
            items.add(
                FeedbackItem(
                    priority = 4,
                    icon = "🔭",
                    message = evaluation.gate4.feedback,
                    category = "v15_compression",
                    currentValue = evaluation.gate4.score.toDouble(),
                    targetValue = evaluation.gate4.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        return items
    }

    // =========================================================
    // ✅ Legacy Gate 평가 → FeedbackItem 변환 (Overload)
    // =========================================================
    fun generateFeedbackItems(evaluation: LegacyGateEvaluation): List<FeedbackItem> {
        val items = mutableListOf<FeedbackItem>()

        // Gate 1: 여백 균형
        if (!evaluation.gate1.passed) {
            items.add(
                FeedbackItem(
                    priority = 1,
                    icon = "↔️",
                    message = evaluation.gate1.feedback,
                    category = "v15_margin_balance",
                    currentValue = evaluation.gate1.score.toDouble(),
                    targetValue = evaluation.gate1.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        // Gate 2: 프레이밍
        if (!evaluation.gate2.passed) {
            items.add(
                FeedbackItem(
                    priority = 2,
                    icon = "📐",
                    message = evaluation.gate2.feedback,
                    category = "v15_framing",
                    currentValue = evaluation.gate2.score.toDouble(),
                    targetValue = evaluation.gate2.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        // Gate 3: 구도
        if (!evaluation.gate3.passed) {
            items.add(
                FeedbackItem(
                    priority = 3,
                    icon = "🎯",
                    message = evaluation.gate3.feedback,
                    category = "v15_composition",
                    currentValue = evaluation.gate3.score.toDouble(),
                    targetValue = evaluation.gate3.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        // Gate 4: 압축감
        if (!evaluation.gate4.passed) {
            items.add(
                FeedbackItem(
                    priority = 4,
                    icon = "🔭",
                    message = evaluation.gate4.feedback,
                    category = "v15_compression",
                    currentValue = evaluation.gate4.score.toDouble(),
                    targetValue = evaluation.gate4.threshold.toDouble(),
                    tolerance = 0.1,
                    unit = null
                )
            )
        }

        return items
    }

    // =========================================================
    // ✅ 대표 피드백 하나만
    // =========================================================
    fun generatePrimaryFeedback(evaluation: GateEvaluation): String {
        return evaluation.primaryFeedback
    }

    // =========================================================
    // ✅ 전체 피드백 문자열 배열
    // =========================================================
    fun generateAllFeedbacks(evaluation: GateEvaluation): List<String> {
        return evaluation.allFeedbacks
    }

    // =========================================================
    // ✅ 상태 요약 메시지
    // =========================================================
    fun generateStatusMessage(evaluation: GateEvaluation): String {
        val passedCount = evaluation.passedCount
        val totalCount = 4

        return when {
            evaluation.allPassed ->
                "완벽한 구도입니다!"

            passedCount >= 3 ->
                "거의 다 됐어요! ($passedCount/$totalCount)"

            passedCount >= 2 ->
                "조금만 더 조정하세요 ($passedCount/$totalCount)"

            else ->
                "구도를 맞춰주세요 ($passedCount/$totalCount)"
        }
    }

    // =========================================================
    // ✅ Movement Guide
    // =========================================================
    data class MovementGuide(
        val arrow: String,
        val direction: String,
        val amount: String
    )

    fun generateMovementGuide(
        margins: MarginAnalysisResult
    ): MovementGuide? {

        val movement = margins.movementDirection ?: return null

        return MovementGuide(
            arrow = movement.primaryArrow,
            direction = movement.description,
            amount = "${(movement.amount * 100).roundToInt()}%"
        )
    }

    // =========================================================
    // ✅ 점수 기반 격려 메시지
    // =========================================================
    fun generateEncouragement(score: Float): String {
        return when {
            score >= 0.9f -> "완벽해요!"
            score >= 0.8f -> "아주 좋아요!"
            score >= 0.7f -> "좋아요, 조금만 더!"
            score >= 0.5f -> "잘하고 있어요"
            else -> "조정이 필요해요"
        }
    }
}

// =========================================================
// ✅ FeedbackItem 확장
// =========================================================
val FeedbackItem.isV15Category: Boolean
    get() = category.startsWith("v15_")
