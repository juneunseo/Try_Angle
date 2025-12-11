package com.example.camera2app.ai

import android.graphics.RectF
import android.util.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// -------------------------
// Gate 개별 결과
// -------------------------
data class LegacyGateResult(
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
data class LegacyGateEvaluation(
    val gate1: LegacyGateResult, // 여백 균형
    val gate2: LegacyGateResult, // 프레이밍
    val gate3: LegacyGateResult, // 구도
    val gate4: LegacyGateResult  // 압축감
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
            overallScore < 0.9f -> "구도가 전반적으로 좋습니다"
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
    ): LegacyGateEvaluation {

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

        return LegacyGateEvaluation(gate1, gate2, gate3, gate4)
    }

    // =====================================================
    // Gate 1 — 여백 균형
    // =====================================================
    private fun evaluateMarginBalance(
        bbox: RectF,
        imageSize: Size,
        referenceBBox: RectF?,
        referenceImageSize: Size?
    ): LegacyGateResult {

        return if (referenceBBox != null && referenceImageSize != null) {
            // Reference와 비교
            val result = marginAnalyzer.compareWithReference(
                current = bbox,
                reference = referenceBBox,
                currentImageSize = android.util.SizeF(imageSize.width.toFloat(), imageSize.height.toFloat()),
                referenceImageSize = android.util.SizeF(referenceImageSize.width.toFloat(), referenceImageSize.height.toFloat())
            )

            LegacyGateResult(
                name = "여백 균형",
                score = result.overallMatch,
                threshold = thresholds.marginBalance,
                feedback = if (result.isMatched) "여백 균형 일치" else result.adjustmentFeedback
            )

        } else {
            // 절대 평가
            val margins = marginAnalyzer.analyze(bbox, android.util.SizeF(imageSize.width.toFloat(), imageSize.height.toFloat()))
            LegacyGateResult(
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
    ): LegacyGateResult {

        val currentRatio = bbox.width() * bbox.height()
        val targetRatio = referenceBBox?.let { it.width() * it.height() } ?: 0.35f

        val ratioDiff = abs(currentRatio - targetRatio)
        val score = max(0f, 1f - (ratioDiff / 0.4f))

        val feedback = when {
            currentRatio < targetRatio * 0.7f -> "더 가까이 접근하세요"
            currentRatio > targetRatio * 1.4f -> "조금 뒤로 물러나세요"
            else -> "프레이밍 적절"
        }

        return LegacyGateResult(
            name = "프레이밍",
            score = score,
            threshold = thresholds.framing,
            feedback = feedback
        )
    }

    // =====================================================
    // Gate 3 — 구도 (3분할선)
    // =====================================================
    private fun evaluateComposition(bbox: RectF): LegacyGateResult {

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

        return LegacyGateResult(
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
    ): LegacyGateResult {

        val current = currentIndex
            ?: return LegacyGateResult(
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

            LegacyGateResult("압축감", score, thresholds.compression, feedback)

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

            LegacyGateResult("압축감", score, thresholds.compression, feedback)
        }
    }


    companion object {

        val shared = GateSystem()

        fun fromFeedback(feedback: TryAngleFeedback): LegacyGateEvaluation {

            // ✅ ✅ ✅ [최종 버그 봉쇄 코드 — 이거 하나로 모든 문제 끝]
            if (!feedback.isPersonDetected) {
                return LegacyGateEvaluation(
                    gate1 = LegacyGateResult("여백 균형", 0.1f, 1f, "사람이 감지되지 않았습니다"),
                    gate2 = LegacyGateResult("프레이밍", 0.1f, 1f, "사람이 감지되지 않았습니다"),
                    gate3 = LegacyGateResult("구도", 0.1f, 1f, "사람이 감지되지 않았습니다"),
                    gate4 = LegacyGateResult("압축감", 0.1f, 1f, "사람이 감지되지 않았습니다")
                )
            }

            // ✅ 1. 여백 Gate (margin)
            val gate1 = feedback.marginInfo?.let { margin ->
                LegacyGateResult(
                    name = "여백 균형",
                    score = margin.balanceScore,
                    threshold = 0.7f,
                    feedback = if (margin.balanceScore >= 0.7f)
                        "여백 균형이 안정적입니다"
                    else
                        "여백을 조정하세요"
                )
            } ?: LegacyGateResult(
                name = "여백 균형",
                score = 0.4f,
                threshold = 0.7f,
                feedback = "여백 분석 중"
            )


            // ✅ 2. 프레이밍 Gate
            val framingScore = feedback.compressionInfo?.index ?: 0.45f

            val gate2 = LegacyGateResult(
                name = "프레이밍",
                score = framingScore,
                threshold = 0.65f,
                feedback = if (framingScore >= 0.65f)
                    "프레이밍이 안정적입니다"
                else
                    "조금 더 인물 중심으로 이동하세요"
            )


            // ✅ 3. 구도 Gate (movement 존재 여부만 체크)
            val gate3 = if (feedback.movement == null) {
                LegacyGateResult(
                    name = "구도",
                    score = 0.6f,              // ✅ 기본값은 부분 성공
                    threshold = 0.75f,         // ✅ 합격선 상향
                    feedback = "구도 분석 중"
                )
            } else {
                LegacyGateResult(
                    name = "구도",
                    score = 0.4f,
                    threshold = 0.75f,
                    feedback = "카메라 위치를 조금 조정하세요"
                )
            }



            // ✅ 4. 압축감 Gate
            val compressionScore = feedback.compressionInfo?.index ?: 0.5f


            val gate4 = LegacyGateResult(
                name = "압축감",
                score = compressionScore,
                threshold = 0.75f,
                feedback = if (compressionScore >= 0.75f)
                    "압축감이 안정적입니다"
                else
                    "카메라와의 거리를 조절해보세요"
            )

            return LegacyGateEvaluation(
                gate1 = gate1,
                gate2 = gate2,
                gate3 = gate3,
                gate4 = gate4
            )

        }
    }


}
