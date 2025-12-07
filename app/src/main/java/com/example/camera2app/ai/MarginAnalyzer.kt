package com.example.camera2app.ai

import android.graphics.RectF
import android.util.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// -------------------------------------------------------------
// 여백 분석 결과
// -------------------------------------------------------------
data class MarginAnalysisResult(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,

    val leftRatio: Float,
    val rightRatio: Float,
    val topRatio: Float,
    val bottomRatio: Float,

    val horizontalBalance: Float,
    val verticalBalance: Float,
    val overallBalance: Float,

    val horizontalFeedback: String?,
    val verticalFeedback: String?,
    val movementDirection: MovementDirection?
)

// -------------------------------------------------------------
// 이동 방향 정보
// -------------------------------------------------------------
data class MovementDirection(
    val horizontal: HorizontalDirection?,
    val vertical: VerticalDirection?,
    val amount: Float    // 이동량 0.0~1.0
) {
    enum class HorizontalDirection(val label: String, val arrow: String) {
        Left("왼쪽", "←"),
        Right("오른쪽", "→")
    }

    enum class VerticalDirection(val label: String, val arrow: String) {
        Up("위", "↑"),
        Down("아래", "↓")
    }

    val primaryArrow: String
        get() = when {
            horizontal != null -> horizontal.arrow
            vertical != null -> vertical.arrow
            else -> ""
        }

    val description: String
        get() {
            val parts = mutableListOf<String>()
            horizontal?.let { parts.add("${it.arrow} ${it.label}") }
            vertical?.let { parts.add("${it.arrow} ${it.label}") }
            return parts.joinToString(" | ")
        }
}

// -------------------------------------------------------------
// 비교 결과
// -------------------------------------------------------------
data class MarginComparisonResult(
    val currentMargins: MarginAnalysisResult,
    val referenceMargins: MarginAnalysisResult,
    val horizontalMatch: Float,
    val verticalMatch: Float,
    val overallMatch: Float,
    val adjustmentFeedback: String
) {
    val isMatched: Boolean get() = overallMatch > 0.85f
}

// -------------------------------------------------------------
// MarginAnalyzer 본체
// -------------------------------------------------------------
class MarginAnalyzer {

    private val minMarginRatio = 0.03f   // 3%
    private val maxMarginRatio = 0.35f   // 35%
    private val balanceThreshold = 0.08f // 8%
    private val idealBottomRatio = 2.0f  // bottom = top * 2

    // ---------------------------------------------------------
    // 메인 분석 함수
    // ---------------------------------------------------------
    fun analyze(bbox: RectF, imageSize: Size, isNormalized: Boolean = true): MarginAnalysisResult {

        val pixelBBox = if (isNormalized) {
            RectF(
                bbox.left * imageSize.width,
                bbox.top * imageSize.height,
                bbox.width() * imageSize.width,
                bbox.height() * imageSize.height
            )
        } else {
            bbox
        }

        val left = pixelBBox.left
        val top = pixelBBox.top
        val right = imageSize.width - (pixelBBox.right)
        val bottom = imageSize.height - (pixelBBox.bottom)

        val leftRatio = left / imageSize.width
        val rightRatio = right / imageSize.width
        val topRatio = top / imageSize.height
        val bottomRatio = bottom / imageSize.height

        val horizontalBalance = calculateHorizontalBalance(leftRatio, rightRatio)
        val verticalBalance = calculateVerticalBalance(topRatio, bottomRatio)
        val overallBalance = (horizontalBalance + verticalBalance) / 2f

        val (horizontalFeedback, horizontalDir) =
            generateHorizontalFeedback(leftRatio, rightRatio)

        val (verticalFeedback, verticalDir) =
            generateVerticalFeedback(topRatio, bottomRatio)

        val movementAmount = max(abs(leftRatio - rightRatio), abs(topRatio - bottomRatio))

        val movement =
            if (horizontalDir != null || verticalDir != null)
                MovementDirection(horizontalDir, verticalDir, movementAmount)
            else null

        return MarginAnalysisResult(
            left, right, top, bottom,
            leftRatio, rightRatio, topRatio, bottomRatio,
            horizontalBalance, verticalBalance, overallBalance,
            horizontalFeedback, verticalFeedback, movement
        )
    }

    // ---------------------------------------------------------
    // 레퍼런스와 비교
    // ---------------------------------------------------------
    fun compareWithReference(
        current: RectF,
        reference: RectF,
        currentImageSize: Size,
        referenceImageSize: Size
    ): MarginComparisonResult {

        val curr = analyze(current, currentImageSize)
        val ref = analyze(reference, referenceImageSize)

        val leftDiff = curr.leftRatio - ref.leftRatio
        val rightDiff = curr.rightRatio - ref.rightRatio
        val topDiff = curr.topRatio - ref.topRatio
        val bottomDiff = curr.bottomRatio - ref.bottomRatio

        val horizontalMatch = 1f - min(abs(leftDiff) + abs(rightDiff), 1f)
        val verticalMatch = 1f - min(abs(topDiff) + abs(bottomDiff), 1f)
        val overallMatch = (horizontalMatch + verticalMatch) / 2f

        val adjustmentFeedback = generateAdjustmentFeedback(
            leftDiff, rightDiff, topDiff, bottomDiff
        )

        return MarginComparisonResult(
            curr, ref, horizontalMatch, verticalMatch, overallMatch, adjustmentFeedback
        )
    }

    // ---------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------

    private fun calculateHorizontalBalance(leftRatio: Float, rightRatio: Float): Float {
        val diff = abs(leftRatio - rightRatio)
        return max(0f, 1f - (diff / 0.5f))
    }

    private fun calculateVerticalBalance(topRatio: Float, bottomRatio: Float): Float {
        val idealBottom = topRatio * idealBottomRatio
        val diff = abs(bottomRatio - idealBottom)
        return max(0f, 1f - (diff / 0.3f))
    }

    private fun generateHorizontalFeedback(
        leftRatio: Float,
        rightRatio: Float
    ): Pair<String?, MovementDirection.HorizontalDirection?> {

        val diff = leftRatio - rightRatio

        if (abs(diff) < balanceThreshold) {
            return null to null
        }

        val percent = (abs(diff) * 100).toInt()

        return if (diff > 0) {
            // left > right → 카메라를 오른쪽으로
            "카메라를 오른쪽으로 ${percent}%" to MovementDirection.HorizontalDirection.Right
        } else {
            "카메라를 왼쪽으로 ${percent}%" to MovementDirection.HorizontalDirection.Left
        }
    }

    private fun generateVerticalFeedback(
        topRatio: Float,
        bottomRatio: Float
    ): Pair<String?, MovementDirection.VerticalDirection?> {

        val idealBottom = topRatio * idealBottomRatio
        val diff = bottomRatio - idealBottom

        if (abs(diff) < balanceThreshold) {
            return null to null
        }

        if (topRatio < minMarginRatio)
            return "상단 여백이 부족합니다" to MovementDirection.VerticalDirection.Down

        if (bottomRatio < minMarginRatio)
            return "하단 여백이 부족합니다" to MovementDirection.VerticalDirection.Up

        return if (diff > 0) {
            "카메라를 아래로 이동" to MovementDirection.VerticalDirection.Down
        } else {
            "카메라를 위로 이동" to MovementDirection.VerticalDirection.Up
        }
    }

    private fun generateAdjustmentFeedback(
        leftDiff: Float,
        rightDiff: Float,
        topDiff: Float,
        bottomDiff: Float
    ): String {

        val list = mutableListOf<String>()
        val threshold = 0.05f

        val horizontalShift = (leftDiff - rightDiff) / 2f
        if (abs(horizontalShift) > threshold) {
            if (horizontalShift > 0) list.add("← 왼쪽으로")
            else list.add("→ 오른쪽으로")
        }

        val verticalShift = (topDiff - bottomDiff) / 2f
        if (abs(verticalShift) > threshold) {
            if (verticalShift > 0) list.add("↑ 위로")
            else list.add("↓ 아래로")
        }

        if (list.isEmpty()) return "✓ 레퍼런스와 일치"

        return list.joinToString(" | ")
    }
}
