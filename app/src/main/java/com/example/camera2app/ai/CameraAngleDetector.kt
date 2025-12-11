package com.example.camera2app.ai

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2


// ✅ 카메라 앵글 타입
enum class CameraAngle(val description: String) {
    VERY_LOW("초저각 (극로우앵글)"),
    LOW("로우앵글"),
    EYE_LEVEL("아이레벨 (정면)"),
    HIGH("하이앵글"),
    VERY_HIGH("초고각 (극하이앵글)"),
    DUTCH("더치 틸트"),
    UNKNOWN("알 수 없음")
}

// ✅ 카메라 앵글 감지기
class CameraAngleDetector {

    // ✅ 3가지 방법 융합
    fun detectCameraAngle(
        faceRect: RectF?,          // 정규화 좌표
        facePitch: Float?,
        landmarks: FaceLandmarks? // Android용 얼굴 랜드마크 래퍼
    ): CameraAngle {

        val scores = mutableMapOf<CameraAngle, Double>()

        // ✅ Method 1: 얼굴 위치 기반
        faceRect?.let {
            val result = detectByFacePosition(it)
            scores[result] = (scores[result] ?: 0.0) + 1.0
        }

        // ✅ Method 2: 랜드마크 기반
        landmarks?.let {
            val result = detectByLandmarkRatio(it)
            scores[result] = (scores[result] ?: 0.0) + 2.0
        }

        // ✅ Method 3: Pitch 기반
        facePitch?.let {
            val result = detectByPitchAngle(it)
            scores[result] = (scores[result] ?: 0.0) + 1.5
        }

        return scores.maxByOrNull { it.value }?.key ?: CameraAngle.UNKNOWN
    }

    fun compareAngles(reference: CameraAngle, current: CameraAngle): Boolean {
        return reference == current
    }

    // ✅ 피드백 생성
    fun generateAngleFeedback(reference: CameraAngle, current: CameraAngle): String? {
        if (reference == current) return null

        val refLevel = angleLevel(reference)
        val curLevel = angleLevel(current)

        val diff = abs(curLevel - refLevel)
        val intensity = if (diff >= 2) "많이" else "조금"

        return when {
            curLevel > refLevel -> { // 하이 → 로우로
                when (reference) {
                    CameraAngle.VERY_LOW, CameraAngle.LOW ->
                        "카메라를 ${intensity} 낮추고 위를 향하게 기울여주세요"
                    CameraAngle.EYE_LEVEL ->
                        "카메라를 ${intensity} 눈높이로 낮춰주세요"
                    else ->
                        "카메라를 ${intensity} 낮춰주세요"
                }
            }

            curLevel < refLevel -> { // 로우 → 하이로
                when (reference) {
                    CameraAngle.VERY_HIGH, CameraAngle.HIGH ->
                        "카메라를 ${intensity} 높이고 아래를 향하게 기울여주세요"
                    CameraAngle.EYE_LEVEL ->
                        "카메라를 ${intensity} 눈높이로 올려주세요"
                    else ->
                        "카메라를 ${intensity} 올려주세요"
                }
            }

            reference == CameraAngle.DUTCH && current != CameraAngle.DUTCH ->
                "카메라를 좌우로 기울여주세요 (더치 앵글)"

            else -> null
        }
    }

    private fun angleLevel(angle: CameraAngle): Int {
        return when (angle) {
            CameraAngle.VERY_LOW -> 1
            CameraAngle.LOW -> 2
            CameraAngle.EYE_LEVEL -> 3
            CameraAngle.HIGH -> 4
            CameraAngle.VERY_HIGH -> 5
            CameraAngle.DUTCH, CameraAngle.UNKNOWN -> 3
        }
    }

    // ✅ Method 1: 얼굴 위치 기반
    private fun detectByFacePosition(faceRect: RectF): CameraAngle {
        val faceY = faceRect.centerY()

        return when {
            faceY > 0.8 -> CameraAngle.VERY_LOW
            faceY > 0.6 -> CameraAngle.LOW
            faceY >= 0.4 -> CameraAngle.EYE_LEVEL
            faceY >= 0.2 -> CameraAngle.HIGH
            else -> CameraAngle.VERY_HIGH
        }
    }

    // ✅ Method 2: 랜드마크 비율
    private fun detectByLandmarkRatio(landmarks: FaceLandmarks): CameraAngle {

        val leftEye = landmarks.leftEye ?: return CameraAngle.UNKNOWN
        val rightEye = landmarks.rightEye ?: return CameraAngle.UNKNOWN
        val mouth = landmarks.mouth ?: return CameraAngle.UNKNOWN

        val eyeY = (averageY(leftEye) + averageY(rightEye)) / 2f
        val mouthY = averageY(mouth)

        val ratio = mouthY - eyeY

        return when {
            ratio > 0.15 -> CameraAngle.VERY_LOW
            ratio > 0.08 -> CameraAngle.LOW
            ratio > -0.08 -> CameraAngle.EYE_LEVEL
            ratio > -0.15 -> CameraAngle.HIGH
            else -> CameraAngle.VERY_HIGH
        }
    }

    // ✅ Method 3: Pitch 기반
    private fun detectByPitchAngle(pitchRadians: Float): CameraAngle {
        val degrees = pitchRadians * 180f / Math.PI.toFloat()

        return when {
            degrees > 20 -> CameraAngle.VERY_LOW
            degrees > 10 -> CameraAngle.LOW
            degrees >= -10 -> CameraAngle.EYE_LEVEL
            degrees >= -20 -> CameraAngle.HIGH
            else -> CameraAngle.VERY_HIGH
        }
    }

    // ✅ 더치 틸트 감지
    fun detectDutchTilt(
        landmarks: FaceLandmarks?,
        isFrontCamera: Boolean = false
    ): Float? {

        val leftEye = landmarks?.leftEye ?: return null
        val rightEye = landmarks.rightEye ?: return null

        val left = centroid(leftEye)
        val right = centroid(rightEye)

        val dx = right.x - left.x
        val dy = right.y - left.y

        var angle = atan2(dy, dx) * 180f / Math.PI.toFloat()

        if (isFrontCamera) angle = -angle

        return angle
    }

    private fun averageY(points: List<PointF>): Float {
        return points.map { it.y }.average().toFloat()
    }

    private fun centroid(points: List<PointF>): PointF {
        val x = points.map { it.x }.average().toFloat()
        val y = points.map { it.y }.average().toFloat()
        return PointF(x, y)
    }
}
