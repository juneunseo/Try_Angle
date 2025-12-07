package com.example.camera2app.ai

import android.graphics.RectF
import android.util.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// -------------------------
// Gate 개별 결과
// -------------------------
data class GateResult(
    val name: String,
    val score: Float,          // 0.0 ~ 1.0
    val threshold: Float,      // 통과 기준
    val feedback: String
) {
    val passed: Boolean = score >= threshold
}

// -------------------------
// Gate 전체 평가 결과
// -------------------------
data class GateEvaluation(
    val gate1: GateResult, // 여백 균형
    val gate2: GateResult, // 프레이밍
    val gate3: GateResult, // 구도
    val gate4: GateResult  // 압축감
) {
    val allPassed: Boolean
        get() = gate1.passed && gate2.passed && gate3.passed && gate4.passed

    val passedCount: Int
        get() = listOf(gate1, gate2, gate3, gate4).count { it.passed }

    val overallScore: Float
        get() = listOf(gate1.score, gate2.score, gate3.score, gate4.score)
            .average().toFloat()

    val primaryFeedback: String
        get() = when {
            !gate1.passed -> gate1.feedback
            !gate2.passed -> gate2.feedback
            !gate3.passed -> gate3.feedback
            !gate4.passed -> gate4.feedback
            else -> "✓ 완벽한 구도입니다!"
        }

    val allFeedbacks: List<String>
        get() = listOf(gate1, gate2, gate3, gate4)
            .filter { !it.passed }
            .map { it.feedback }
}

// -------------------------
// Gate System
// -------------------------
class GateSystem private constructor() {

    // Gate 통과 기준값
    private val thresholds = GateThresholds()

    private data class GateThresholds(
        val marginBalance: Float = 0.70f,
        val framing: Float = 0.65f,
        val composition: Float = 0.70f,
        val compression: Float = 0.60f
    )

    private val marginAnalyzer = MarginAnalyzer()

    // =====================================================
    // 전체 평가 함수
    // =====================================================
    fun evaluate(
        currentBBox: RectF,
        referenceBBox: RectF?,
        currentImageSize: Size,
        referenceImageSize: Size?,
        compressionIndex: Float?,
        referenceCompressionIndex: Float?
    ): GateEvaluation {

        val gate1 = evaluateMarginBalance(
            currentBBox, currentImageSize, referenceBBox, referenceImageSize
        )

        val gate2 = evaluateFraming(
            currentBBox, currentImageSize, referenceBBox
        )

        val gate3 = evaluateComposition(currentBBox)

        val gate4 = evaluateCompression(
            compressionIndex, referenceCompressionIndex
        )

        return GateEvaluation(gate1, gate2, gate3, gate4)
    }

    // =====================================================
    // Gate 1 — 여백 균형
    // =====================================================
    private fun evaluateMarginBalance(
        bbox: RectF,
        imageSize: Size,
        referenceBBox: RectF?,
        referenceImageSize: Size?
    ): GateResult {

        return if (referenceBBox != null && referenceImageSize != null) {
            // Reference와 비교
            val result = marginAnalyzer.compareWithReference(
                current = bbox,
                reference = referenceBBox,
                currentImageSize = imageSize,
                referenceImageSize = referenceImageSize
            )

            GateResult(
                name = "여백 균형",
                score = result.overallMatch,
                threshold = thresholds.marginBalance,
                feedback = if (result.isMatched) "여백 균형 일치" else result.adjustmentFeedback
            )

        } else {
            // 절대 평가
            val margins = marginAnalyzer.analyze(bbox, imageSize)
            GateResult(
                name = "여백 균형",
                score = margins.overallBalance,
                threshold = thresholds.marginBalance,
                feedback = margins.movementDirection?.description ?: "여백 균형 양호"
            )
        }
    }

    // =====================================================
    // Gate 2 — 프레이밍
    // =====================================================
    private fun evaluateFraming(
        bbox: RectF,
        imageSize: Size,
        referenceBBox: RectF?
    ): GateResult {

        val currentRatio = bbox.width() * bbox.height()
        val targetRatio = referenceBBox?.let { it.width() * it.height() } ?: 0.35f

        val ratioDiff = abs(currentRatio - targetRatio)
        val score = max(0f, 1f - (ratioDiff / 0.4f))

        val feedback = when {
            currentRatio < targetRatio * 0.7f -> "더 가까이 접근하세요"
            currentRatio > targetRatio * 1.4f -> "조금 뒤로 물러나세요"
            else -> "프레이밍 적절"
        }

        return GateResult(
            name = "프레이밍",
            score = score,
            threshold = thresholds.framing,
            feedback = feedback
        )
    }

    // =====================================================
    // Gate 3 — 구도 (3분할선)
    // =====================================================
    private fun evaluateComposition(bbox: RectF): GateResult {

        val centerX = bbox.centerX()
        val centerY = bbox.centerY()

        val thirds = listOf(1f / 3f, 0.5f, 2f / 3f)

        val minX = thirds.minOf { abs(it - centerX) }
        val minY = thirds.minOf { abs(it - centerY) }

        val horizontalScore = max(0f, 1f - (minX / 0.2f))
        val verticalScore = max(0f, 1f - (minY / 0.2f))
        val score = (horizontalScore + verticalScore) / 2f

        val feedback =
            if (score >= thresholds.composition) {
                "구도 양호"
            } else {
                val targetX = thirds.minBy { abs(it - centerX) }
                val targetY = thirds.minBy { abs(it - centerY) }

                val moves = mutableListOf<String>()

                if (centerX < targetX - 0.05f) moves.add("→ 오른쪽")
                else if (centerX > targetX + 0.05f) moves.add("← 왼쪽")

                if (centerY < targetY - 0.05f) moves.add("↓ 아래")
                else if (centerY > targetY + 0.05f) moves.add("↑ 위")

                if (moves.isEmpty()) "3분할 선에 맞추세요"
                else moves.joinToString(" | ")
            }

        return GateResult(
            name = "구도",
            score = score,
            threshold = thresholds.composition,
            feedback = feedback
        )
    }

    // =====================================================
    // Gate 4 — 압축감
    // =====================================================
    private fun evaluateCompression(
        currentIndex: Float?,
        referenceIndex: Float?
    ): GateResult {

        val current = currentIndex
            ?: return GateResult(
                name = "압축감",
                score = 1f,
                threshold = thresholds.compression,
                feedback = "깊이 분석 대기 중"
            )

        return if (referenceIndex != null) {

            val diff = abs(current - referenceIndex)
            val score = max(0f, 1f - (diff / 0.5f))

            val feedback = when {
                diff < 0.15f -> "압축감 일치"
                current < referenceIndex -> "줌인 또는 더 가까이"
                else -> "줌아웃 또는 더 멀리"
            }

            GateResult("압축감", score, thresholds.compression, feedback)

        } else {
            val ideal = 0.3f..0.7f
            val score = when {
                current in ideal -> 1f
                current < 0.3f -> current / 0.3f
                else -> (1f - current) / (1f - 0.7f)
            }

            val feedback = when {
                score >= thresholds.compression -> "압축감 적절"
                current < 0.3f -> "배경이 너무 넓음 - 줌인 권장"
                else -> "배경이 너무 압축됨 - 줌아웃 권장"
            }

            GateResult("압축감", score, thresholds.compression, feedback)
        }
    }

    companion object {
        val shared = GateSystem()
    }
}
