package com.example.camera2app.ai

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

// ✅ 거리 측정 방법 (Swift enum 대응)
enum class DepthMethod {
    LIDAR,        // Android에서는 미사용
    ARCORE,       // 향후 확장용
    FACE_SIZE,    // 얼굴 크기 기반
    UNAVAILABLE
}

// ✅ 거리 측정 결과 (Swift DepthResult 대응)
data class DepthResult(
    val distance: Float?,        // meters
    val method: DepthMethod,
    val confidence: Float,
    val isZoomDetected: Boolean,
    val zoomFactor: Float?
)

// ✅ 깊이 추정기 (Swift DepthEstimator 1:1 대응)
class DepthEstimator {

    // 평균 얼굴 폭 (미터)
    private val averageFaceWidth = 0.14f  // 14cm

    /**
     * 얼굴까지의 거리 추정 (Android에서는 face size 기반)
     */
    fun estimateDistance(
        faceRect: RectF,
        imageWidth: Int,
        zoomFactor: Float = 1.0f
    ): DepthResult {

        // ✅ 현재 Android는 얼굴 크기 기반만 사용
        return estimateFromFaceSize(
            faceRect = faceRect,
            imageWidth = imageWidth,
            zoomFactor = zoomFactor
        )
    }

    /**
     * 거리 변화 감지 (줌 vs 실제 이동)
     */
    fun detectDistanceChange(
        reference: DepthResult,
        current: DepthResult
    ): Pair<Boolean, Boolean> {

        // ✅ 줌 변화 감지
        val isZoomChange =
            reference.zoomFactor != null &&
                    current.zoomFactor != null &&
                    abs(reference.zoomFactor!! - current.zoomFactor!!) > 0.1f

        // ✅ 실제 거리 변화 감지
        val isDistanceChange =
            reference.distance != null &&
                    current.distance != null &&
                    abs(reference.distance!! - current.distance!!) > 0.2f

        return Pair(isZoomChange, isDistanceChange)
    }

    /**
     * 거리 조정 피드백 생성
     */
    fun generateDistanceFeedback(
        reference: DepthResult,
        current: DepthResult
    ): Pair<String, Boolean>? {

        val refDist = reference.distance ?: return null
        val curDist = current.distance ?: return null

        val distDiff = curDist - refDist

        // ✅ 20cm 이내면 무시
        if (abs(distDiff) < 0.2f) return null

        val (isZoomChange, _) = detectDistanceChange(reference, current)

        // ✅ Case 1: 줌으로 조정 중
        if (isZoomChange) {
            return if (distDiff > 0) {
                "줌 인하여 가까이" to true
            } else {
                "줌 아웃하여 멀리" to true
            }
        }

        // ✅ Case 2: 실제 이동 필요
        val clampedDiff = max(0.2f, min(3.0f, abs(distDiff)))

        return if (clampedDiff < 0.5f) {
            val cm = (clampedDiff * 100).toInt()
            if (distDiff > 0) {
                "약 ${cm}cm 앞으로 이동하세요" to false
            } else {
                "약 ${cm}cm 뒤로 이동하세요" to false
            }
        } else {
            val steps = max(1, (clampedDiff / 0.7f).toInt())
            if (distDiff > 0) {
                "${steps}걸음 앞으로 이동하세요" to false
            } else {
                "${steps}걸음 뒤로 이동하세요" to false
            }
        }
    }

    // =====================================================
    // ✅ 얼굴 크기 기반 거리 추정 (Swift estimateFromFaceSize 1:1 대응)
    // =====================================================

    private fun estimateFromFaceSize(
        faceRect: RectF,
        imageWidth: Int,
        zoomFactor: Float
    ): DepthResult {

        val faceWidthPixels = faceRect.width() * imageWidth

        val fovDegrees = 60.0f
        val fovRadians = fovDegrees * Math.PI.toFloat() / 180f

        val distance =
            (averageFaceWidth * imageWidth) /
                    (2f * faceWidthPixels * tan(fovRadians / 2f))

        val correctedDistance = distance / zoomFactor

        val isZoomDetected = zoomFactor > 1.05f

        return DepthResult(
            distance = correctedDistance,
            method = DepthMethod.FACE_SIZE,
            confidence = 0.6f,
            isZoomDetected = isZoomDetected,
            zoomFactor = zoomFactor
        )
    }

    // ✅ Android에서는 항상 false (LiDAR, ARKit 없음)
    fun checkLiDARAvailability(): Boolean = false
    fun checkARCoreDepthAvailability(): Boolean = false
}