package com.example.camera2app.ai

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs

class OnDeviceFeedbackGenerator(
    private val useLegacySystem: Boolean
) {

    fun generateFeedback(
        pose: PoseResult?,
        legacyBBox: RectF?,
        image: Bitmap,
        processingTime: Double
    ): TryAngleFeedback {

        var primary = ""
        val suggestions = mutableListOf<String>()
        var movement: MovementGuide? = null
        var marginInfo: MarginInfo? = null

        // ✅ BBox 선택
        val bbox: RectF? = legacyBBox ?: pose?.boundingBox

        if (bbox == null) {
            primary = "인물을 찾을 수 없습니다"
        } else {
            marginInfo = calculateMargins(bbox, image)
        }

        // ✅ 포즈 기반 이동 피드백
        pose?.let {
            val poseFeedback = analyzePose(it)
            if (primary.isEmpty()) primary = poseFeedback.first ?: ""
            suggestions.addAll(poseFeedback.second)
            movement = poseFeedback.third
        }

        return TryAngleFeedback(
            primary = if (primary.isEmpty()) "카메라 위치 조정 중..." else primary,
            suggestions = suggestions.take(3),
            movement = movement,
            marginInfo = marginInfo,
            processingTime = processingTime,
            isOnDevice = true,
            compressionInfo = null,
            usedLegacySystem = legacyBBox != null
        )
    }

    // =========================================
    // ✅ 여백 계산
    // =========================================
    private fun calculateMargins(
        bbox: RectF,
        image: Bitmap
    ): MarginInfo {

        val left = bbox.left * image.width
        val right = image.width - (bbox.right * image.width)
        val top = bbox.top * image.height
        val bottom = image.height - (bbox.bottom * image.height)

        val lr = left / image.width
        val rr = right / image.width
        val tr = top / image.height
        val br = bottom / image.height

        val hBalance = 1f - abs(lr - rr)
        val vBalance = 1f - abs(tr - (br * 0.5f))
        val balance = (hBalance + vBalance) / 2f

        return MarginInfo(
            left, right, top, bottom,
            lr, rr, tr, br, balance
        )
    }

    // =========================================
    // ✅ 포즈 분석
    // =========================================
    private fun analyzePose(
        pose: PoseResult
    ): Triple<String?, List<String>, MovementGuide?> {

        val bbox = pose.boundingBox ?: return Triple(null, emptyList(), null)

        val cx = bbox.centerX()
        val cy = bbox.centerY()

        val dx = cx - 0.5f
        val dy = cy - 0.5f

        var primary: String? = null
        val suggestions = mutableListOf<String>()
        var movement: MovementGuide? = null

        if (abs(dx) > 0.1f || abs(dy) > 0.1f) {
            val (dir, arrow) =
                if (abs(dx) > abs(dy)) {
                    if (dx > 0) "왼쪽" to "←" else "오른쪽" to "→"
                } else {
                    if (dy > 0) "위" to "↑" else "아래" to "↓"
                }

            primary = "카메라를 $dir 으로 이동"
            movement = MovementGuide(dir, arrow)
        }

        val visible = pose.keypoints.count { it.second > 0.5f }

        if (visible < 50) {
            suggestions.add("전신이 보이도록 조정하세요")
        }

        return Triple(primary, suggestions, movement)
    }
}
