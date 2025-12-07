package com.example.camera2app.ai

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs

class OnDeviceFeedbackGenerator(
    private val useLegacySystem: Boolean
) {

    // =====================================================
    // ✅ 메인 피드백 생성
    // =====================================================
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

        // ✅ 1차 bbox 선택 (Legacy 우선)
        var bbox: RectF? = legacyBBox ?: pose?.boundingBox

        // ✅ ❌ bbox가 프레임 전체면 무효 → 키포인트로 재생성
        if (pose != null && bbox != null) {
            val w = bbox.width()
            val h = bbox.height()

            if (w > image.width * 0.95f || h > image.height * 0.95f) {
                Log.e("TryAngleScore", "❌ bbox == full frame → keypoint bbox 재생성")
                bbox = buildBBoxFromKeypoints(pose)
            }
        }

        if (bbox == null) {
            primary = "인물을 찾을 수 없습니다"
        } else {
            marginInfo = calculateMargins(bbox, image)
        }

        // ✅ 포즈 이동 분석
        pose?.let {
            val poseFeedback = analyzePose(it)
            if (primary.isEmpty()) primary = poseFeedback.first ?: ""
            suggestions.addAll(poseFeedback.second)
            movement = poseFeedback.third
        }

        // 사람 없음 → 바로 1점 고정
        if (pose == null || pose.keypoints.count { it.second > 0.5f } < 5) {
            Log.e("TryAngleScore", "❌ No person detected → score = 1")
            return TryAngleFeedback(
                primary = "사람을 찾을 수 없습니다",
                suggestions = listOf("화면에 전신이 보이게 촬영하세요"),
                movement = null,
                marginInfo = null,
                processingTime = processingTime,
                isOnDevice = true,
                compressionInfo = CompressionInfo(index = 1.0f),
                usedLegacySystem = legacyBBox != null
            )
        }


        // =====================================================
        // ✅ ✅ ✅ 진짜 점수 계산 (여기서 모든 점수 결정)
        // =====================================================
        val score: Float = when {
            pose == null || bbox == null -> {
                Log.e("TryAngleScore", "❌ pose or bbox null → score = 1")
                1.0f
            }

            marginInfo == null -> {
                Log.e("TryAngleScore", "❌ marginInfo null → score = 3")
                3.0f
            }

            else -> {
                val cx = bbox.centerX() / image.width
                val cy = bbox.centerY() / image.height

                val dx = abs(cx - 0.5f)
                val dy = abs(cy - 0.5f)

                val centerPenalty = (dx + dy) * 1.5f
                val balancePenalty = (1f - marginInfo.balanceScore) * 5f

                val visible = pose.keypoints.count { it.second > 0.5f }

                val keypointBonus = when {
                    visible > 90 -> 1.0f
                    visible > 70 -> 0.5f
                    else -> 0.0f
                }

                val rawScore =
                    10f - (centerPenalty * 10f) - balancePenalty + keypointBonus

                val finalScore = rawScore.coerceIn(1f, 10f)

                // ✅ ✅ ✅ 디버그 로그 (네가 지금 보고 있는 로그랑 동일한 형식)
                Log.d(
                    "TryAngleScore",
                    """
                    ===== TryAngle Debug =====
                    poseDetected = ${pose != null}
                    bbox = $bbox
                    cx = $cx
                    cy = $cy
                    balance = ${marginInfo.balanceScore}
                    visibleKeypoints = $visible
                    normalizedScore = $rawScore
                    finalScore(×10) = $finalScore
                    =========================
                    """.trimIndent()
                )

                finalScore
            }
        }

        val compression = CompressionInfo(index = score)

        return TryAngleFeedback(
            primary = if (primary.isEmpty()) "카메라 위치 조정 중..." else primary,
            suggestions = suggestions.take(3),
            movement = movement,
            marginInfo = marginInfo,
            processingTime = processingTime,
            isOnDevice = true,
            compressionInfo = compression,   // ✅ 항상 실제 점수 존재
            usedLegacySystem = legacyBBox != null
        )


    }

    // =====================================================
    // ✅ ✅ ✅ 키포인트 기반 bbox 재생성 (핵심 해결 코드)
    // =====================================================
    private fun buildBBoxFromKeypoints(pose: PoseResult): RectF? {
        val valid = pose.keypoints.filter { it.second > 0.4f }
        if (valid.isEmpty()) return null

        val xs = valid.map { it.first.x }
        val ys = valid.map { it.first.y }

        val minX = xs.minOrNull() ?: return null
        val maxX = xs.maxOrNull() ?: return null
        val minY = ys.minOrNull() ?: return null
        val maxY = ys.maxOrNull() ?: return null

        return RectF(minX, minY, maxX, maxY)
    }

    // =====================================================
    // ✅ 여백 계산
    // =====================================================
    private fun calculateMargins(
        bbox: RectF,
        image: Bitmap
    ): MarginInfo {

        val left = bbox.left
        val right = image.width - bbox.right
        val top = bbox.top
        val bottom = image.height - bbox.bottom

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

    // =====================================================
    // ✅ 포즈 이동 분석
    // =====================================================
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
