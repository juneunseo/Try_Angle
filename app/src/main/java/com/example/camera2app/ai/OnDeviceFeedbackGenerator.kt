package com.example.camera2app.ai

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs

/**
 * ✅ TryAngle v1.5 — 최종 안정판
 * - 좌표계 정규화 오류 수정 ✅
 * - full-frame bbox 잘못 판정되는 문제 수정 ✅
 * - keypointBonus 현실 기준으로 수정 ✅
 * - 사람 없을 때만 1점 고정 ✅
 * - GateSystem 오염 완전 차단 ✅
 */
class OnDeviceFeedbackGenerator(
    private val useLegacySystem: Boolean
) {

    // =====================================================
    // ✅ 메인 피드백 + 점수 생성
    // =====================================================
    fun generateFeedback(
        pose: PoseResult?,
        legacyBBox: RectF?,
        image: Bitmap,
        processingTime: Double
    ): TryAngleFeedback {

        Log.e("TryAngleScore", "✅ generateFeedback() 진입")

        var primary = ""
        val suggestions = mutableListOf<String>()
        var movement: MovementGuide? = null
        var marginInfo: MarginInfo? = null

        //--------------------------------------------------------
        // ✅ 1️⃣ BBox 선택 (Legacy > Pose)
        //--------------------------------------------------------
        var bbox: RectF? = legacyBBox ?: pose?.boundingBox

        // ✅ full-frame 판정 기준 수정
        if (pose != null && bbox != null) {
            val w = bbox.width()
            val h = bbox.height()

            if (w > image.width * 0.98f && h > image.height * 0.98f) {
                Log.e("TryAngleScore", "❌ bbox == full frame → keypoint bbox 재생성")
                bbox = buildBBoxFromKeypoints(pose)
            }
        }

        if (bbox == null) {
            primary = "인물을 찾을 수 없습니다"
        } else {
            marginInfo = calculateMargins(bbox, image)
        }

        //--------------------------------------------------------
        // ✅ 2️⃣ 포즈 이동 분석
        //--------------------------------------------------------
        pose?.let {
            val poseFeedback = analyzePose(it, image)
            if (primary.isEmpty()) primary = poseFeedback.first ?: ""
            suggestions.addAll(poseFeedback.second)
            movement = poseFeedback.third
        }

        //--------------------------------------------------------
        // ✅ 3️⃣ ✅ 사람 없음 → 여기서 완전 조기 종료 (GateSystem 절대 안 탐)
        //--------------------------------------------------------
        if (bbox == null || pose == null) {
            Log.e("TryAngleScore", "❌ No person detected → score = 1")

            return TryAngleFeedback(
                primary = "사람을 찾을 수 없습니다",
                suggestions = listOf("화면에 전신이 보이도록 촬영하세요"),
                movement = null,
                marginInfo = null,
                compressionInfo = CompressionInfo(index = 1.0f),
                processingTime = processingTime,
                isOnDevice = true,
                usedLegacySystem = legacyBBox != null,
                isPersonDetected = false
            )
        }

        //--------------------------------------------------------
        // ✅ 4️⃣ Raw Score 계산
        //--------------------------------------------------------
        val cx = bbox.centerX() / image.width
        val cy = bbox.centerY() / image.height

        val dx = abs(cx - 0.5f)
        val dy = abs(cy - 0.5f)

        val centerPenalty = (dx + dy) * 1.5f
        val balancePenalty = (1f - marginInfo!!.balanceScore) * 5f

        val visible = pose.keypoints.count { it.second > 0.5f }

        val keypointBonus = when {
            visible > 60 -> 1.0f
            visible > 40 -> 0.5f
            else -> 0.0f
        }

        val rawScore =
            10f - (centerPenalty * 10f) - balancePenalty + keypointBonus

        //--------------------------------------------------------
        // ✅ 5️⃣ 임시 Feedback 생성 (GateSystem용)
        //--------------------------------------------------------
        val tempCompression = CompressionInfo(index = rawScore.coerceIn(1f, 10f))

        val tempFeedback = TryAngleFeedback(
            primary = if (primary.isEmpty()) "구도를 조정해 주세요" else primary,
            suggestions = suggestions.take(3),
            movement = movement,
            marginInfo = marginInfo,
            compressionInfo = tempCompression,
            processingTime = processingTime,
            isOnDevice = true,
            usedLegacySystem = legacyBBox != null,
            isPersonDetected = true   // ✅ 여기까지 왔으면 무조건 사람 있음
        )

        //--------------------------------------------------------
        // ✅ 6️⃣ ✅ 최종 점수 (GateSystem + ×10)
        //--------------------------------------------------------
        val finalScore =
            GateSystem.fromFeedback(tempFeedback).overallScore * 10f

        //--------------------------------------------------------
        // ✅ 디버그 로그
        //--------------------------------------------------------
        Log.d(
            "TryAngleScore",
            """
            ===== TryAngle Debug =====
            poseDetected = true
            bbox = $bbox
            cx = $cx
            cy = $cy
            balance = ${marginInfo.balanceScore}
            visibleKeypoints = $visible
            rawScore = $rawScore
            finalScore(×10) = $finalScore
            =========================
            """.trimIndent()
        )

        val finalCompression = CompressionInfo(index = finalScore)

        //--------------------------------------------------------
        // ✅ 7️⃣ 최종 Feedback 반환
        //--------------------------------------------------------
        return TryAngleFeedback(
            primary = if (primary.isEmpty()) "구도를 조정해 주세요" else primary,
            suggestions = suggestions.take(3),
            movement = movement,
            marginInfo = marginInfo,
            compressionInfo = finalCompression,
            processingTime = processingTime,
            isOnDevice = true,
            usedLegacySystem = legacyBBox != null,
            isPersonDetected = true
        )
    }

    // =====================================================
    // ✅ 키포인트 기반 BBox 재생성
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
    // ✅ 이동 방향 분석
    // =====================================================
    private fun analyzePose(
        pose: PoseResult,
        image: Bitmap
    ): Triple<String?, List<String>, MovementGuide?> {

        val bbox = pose.boundingBox ?: return Triple(null, emptyList(), null)

        val cx = bbox.centerX() / image.width
        val cy = bbox.centerY() / image.height

        val dx = cx - 0.5f
        val dy = cy - 0.5f

        var primary: String? = null
        val suggestions = mutableListOf<String>()
        var movement: MovementGuide? = null

        if (abs(dx) > 0.08f || abs(dy) > 0.08f) {
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

        if (visible < 40) {
            suggestions.add("전신이 보이도록 조정하세요")
        }

        if (primary == null && bbox != null) {
            primary = "구도를 조정해 주세요"
        }

        return Triple(primary, suggestions, movement)
    }
}
