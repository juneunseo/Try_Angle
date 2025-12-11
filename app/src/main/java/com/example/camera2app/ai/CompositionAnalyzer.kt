package com.example.camera2app.ai

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt


// MARK: - 구도 타입
sealed class CompositionType {

    // ✅ 3분할 구도
    object RuleOfThirdsLeftUpper : CompositionType()
    object RuleOfThirdsRightUpper : CompositionType()
    object RuleOfThirdsLeftLower : CompositionType()
    object RuleOfThirdsRightLower : CompositionType()
    object RuleOfThirdsCenter : CompositionType()

    // ✅ 황금비율 구도
    object GoldenRatioLeft : CompositionType()
    object GoldenRatioRight : CompositionType()
    object GoldenRatioUpper : CompositionType()
    object GoldenRatioLower : CompositionType()

    // ✅ 중앙 집중
    object CenterFocus : CompositionType()

    // ✅ 커스텀
    data class Custom(val position: PointF) : CompositionType()

    val description: String
        get() = when (this) {
            RuleOfThirdsLeftUpper -> "왼쪽 위"
            RuleOfThirdsRightUpper -> "오른쪽 위"
            RuleOfThirdsLeftLower -> "왼쪽 아래"
            RuleOfThirdsRightLower -> "오른쪽 아래"
            RuleOfThirdsCenter -> "화면 중앙"

            GoldenRatioLeft -> "조금 왼쪽"
            GoldenRatioRight -> "조금 오른쪽"
            GoldenRatioUpper -> "조금 위쪽"
            GoldenRatioLower -> "조금 아래쪽"

            CenterFocus -> "화면 정중앙"

            is Custom -> "현재 위치"
        }
}

// MARK: - 구도 분석기
class CompositionAnalyzer {

    // ✅ 허용 오차 (%)
    private val ruleOfThirdsTolerance = 0.08f
    private val goldenRatioTolerance = 0.08f
    private val centerTolerance = 0.15f

    // ✅ 황금비율 상수
    private val goldenRatio = 0.618f

    // ✅ 구도 자동 분류
    fun classifyComposition(subjectPosition: PointF): CompositionType {
        val x = subjectPosition.x
        val y = subjectPosition.y

        // 1️⃣ 중앙 집중
        if (isCenterFocus(x, y)) {
            return CompositionType.CenterFocus
        }

        // 2️⃣ 3분할
        checkRuleOfThirds(x, y)?.let { return it }

        // 3️⃣ 황금비율
        checkGoldenRatio(x, y)?.let { return it }

        // 4️⃣ 커스텀
        return CompositionType.Custom(subjectPosition)
    }

    // ✅ 레퍼런스 vs 현재 구도 정확도
    fun compareComposition(reference: PointF, current: PointF): Double {
        val xDiff = abs(reference.x - current.x)
        val yDiff = abs(reference.y - current.y)

        val distance = sqrt(xDiff * xDiff + yDiff * yDiff)

        val maxDistance = sqrt(2f)

        return (1.0 - (distance / maxDistance))
            .coerceIn(0.0, 1.0)
    }

    // ✅ 구도 피드백 생성
    fun generateCompositionFeedback(
        referenceType: CompositionType,
        referencePosition: PointF,
        currentPosition: PointF
    ): Triple<String, String?, String?> {

        val xDiff = currentPosition.x - referencePosition.x
        val yDiff = currentPosition.y - referencePosition.y

        var xDirection: String? = null
        var yDirection: String? = null
        var message = ""

        if (abs(xDiff) > 0.05f) {
            xDirection = if (xDiff > 0) "왼쪽" else "오른쪽"
        }

        if (abs(yDiff) > 0.05f) {
            yDirection = if (yDiff > 0) "아래쪽" else "위쪽"
        }

        message = when {
            xDirection != null && yDirection != null ->
                "${referenceType.description}쪽으로 (${xDirection} + ${yDirection})"

            xDirection != null ->
                "${xDirection}으로 이동"

            yDirection != null ->
                "${yDirection}으로 이동"

            else ->
                "${referenceType.description} 위치 유지"
        }

        return Triple(message, xDirection, yDirection)
    }

    // ✅ 중앙 여부 체크
    private fun isCenterFocus(x: Float, y: Float): Boolean {
        val centerX = 0.5f
        val centerY = 0.5f

        return abs(x - centerX) <= centerTolerance &&
                abs(y - centerY) <= centerTolerance
    }

    // ✅ 3분할 체크
    private fun checkRuleOfThirds(x: Float, y: Float): CompositionType? {
        val leftLine = 1f / 3f
        val rightLine = 2f / 3f
        val upperLine = 1f / 3f
        val lowerLine = 2f / 3f

        return when {
            abs(x - leftLine) <= ruleOfThirdsTolerance &&
                    abs(y - upperLine) <= ruleOfThirdsTolerance ->
                CompositionType.RuleOfThirdsLeftUpper

            abs(x - rightLine) <= ruleOfThirdsTolerance &&
                    abs(y - upperLine) <= ruleOfThirdsTolerance ->
                CompositionType.RuleOfThirdsRightUpper

            abs(x - leftLine) <= ruleOfThirdsTolerance &&
                    abs(y - lowerLine) <= ruleOfThirdsTolerance ->
                CompositionType.RuleOfThirdsLeftLower

            abs(x - rightLine) <= ruleOfThirdsTolerance &&
                    abs(y - lowerLine) <= ruleOfThirdsTolerance ->
                CompositionType.RuleOfThirdsRightLower

            abs(x - 0.5f) <= ruleOfThirdsTolerance &&
                    abs(y - 0.5f) <= ruleOfThirdsTolerance ->
                CompositionType.RuleOfThirdsCenter

            else -> null
        }
    }

    // ✅ 황금비율 체크
    private fun checkGoldenRatio(x: Float, y: Float): CompositionType? {
        val goldenLeft = 1f - goldenRatio
        val goldenRight = goldenRatio
        val goldenUpper = 1f - goldenRatio
        val goldenLower = goldenRatio

        return when {
            abs(x - goldenLeft) <= goldenRatioTolerance ->
                CompositionType.GoldenRatioLeft

            abs(x - goldenRight) <= goldenRatioTolerance ->
                CompositionType.GoldenRatioRight

            abs(y - goldenUpper) <= goldenRatioTolerance ->
                CompositionType.GoldenRatioUpper

            abs(y - goldenLower) <= goldenRatioTolerance ->
                CompositionType.GoldenRatioLower

            else -> null
        }
    }

    // ✅ UI용 3분할 라인
    fun getRuleOfThirdsLines(): Pair<List<Float>, List<Float>> {
        return Pair(
            listOf(1f / 3f, 2f / 3f),
            listOf(1f / 3f, 2f / 3f)
        )
    }

    // ✅ UI용 황금비율 라인
    fun getGoldenRatioLines(): Pair<List<Float>, List<Float>> {
        return Pair(
            listOf(1f - goldenRatio, goldenRatio),
            listOf(1f - goldenRatio, goldenRatio)
        )
    }
}
