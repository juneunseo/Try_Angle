package com.example.camera2app.ai

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class GazeTracker {

    private val horizontalThreshold = 0.15f
    private val verticalThreshold = 0.15f
    private val cameraLookThreshold = 0.10f

    // ✅ iOS: trackGaze(from: VNFaceObservation)
    // ✅ Android: Pose 눈 + 코 기반 시선 추정
    fun trackGazeFromPose(
        leftEye: Pair<PointF, Float>?,
        rightEye: Pair<PointF, Float>?,
        nose: Pair<PointF, Float>?,
        yaw: Float? = null,
        pitch: Float? = null
    ): GazeResult? {

        // ✅ 1. 눈 기반 시선 추정 (iOS leftPupil/rightPupil 대응)
        val gazeFromEyes = estimateGazeFromEyes(leftEye, rightEye, nose)
        if (gazeFromEyes != null) {
            return gazeFromEyes
        }

        // ✅ 2. 얼굴 회전(yaw, pitch) 기반 fallback
        return estimateGazeFromFaceAngles(yaw, pitch)
    }

    // ✅ iOS compareGaze()
    fun compareGaze(reference: GazeResult, current: GazeResult): Boolean {
        return reference.direction == current.direction
    }

    // ✅ iOS generateGazeFeedback()
    fun generateGazeFeedback(reference: GazeResult, current: GazeResult): String? {
        if (reference.direction == current.direction) return null

        return when (reference.direction) {
            GazeDirection.LOOKING_AT_CAMERA -> "카메라를 바라봐주세요"
            GazeDirection.LOOKING_LEFT -> "시선을 왼쪽으로"
            GazeDirection.LOOKING_RIGHT -> "시선을 오른쪽으로"
            GazeDirection.LOOKING_UP -> "시선을 위로"
            GazeDirection.LOOKING_DOWN -> "시선을 아래로"
            GazeDirection.LOOKING_LEFT_UP -> "시선을 왼쪽 위로"
            GazeDirection.LOOKING_LEFT_DOWN -> "시선을 왼쪽 아래로"
            GazeDirection.LOOKING_RIGHT_UP -> "시선을 오른쪽 위로"
            GazeDirection.LOOKING_RIGHT_DOWN -> "시선을 오른쪽 아래로"
            GazeDirection.UNKNOWN -> null
        }
    }

    // =========================================
    // ✅ 눈 기반 시선 추정 (핵심)
    // =========================================
    private fun estimateGazeFromEyes(
        leftEye: Pair<PointF, Float>?,
        rightEye: Pair<PointF, Float>?,
        nose: Pair<PointF, Float>?
    ): GazeResult? {

        if (leftEye == null || rightEye == null || nose == null) return null
        if (leftEye.second < 0.3f || rightEye.second < 0.3f || nose.second < 0.3f) return null

        val left = leftEye.first
        val right = rightEye.first
        val nosePt = nose.first

        // ✅ 눈 중심
        val eyeCenter = PointF(
            (left.x + right.x) / 2f,
            (left.y + right.y) / 2f
        )

        // ✅ 코 기준 오프셋
        val dx = nosePt.x - eyeCenter.x
        val dy = nosePt.y - eyeCenter.y

        // ✅ 정규화
        val eyeDist = abs(right.x - left.x).coerceAtLeast(0.01f)

        val horizontalAngle = (dx / eyeDist).coerceIn(-1f, 1f)
        val verticalAngle = (dy / eyeDist).coerceIn(-1f, 1f)

        val direction = classifyGazeDirection(horizontalAngle, verticalAngle)

        val distance = sqrt(horizontalAngle * horizontalAngle + verticalAngle * verticalAngle)
        val confidence = max(0f, min(1f, 1f - distance / 2f))

        return GazeResult(
            direction = direction,
            horizontalAngle = horizontalAngle,
            verticalAngle = verticalAngle,
            confidence = confidence
        )
    }

    // =========================================
    // ✅ 얼굴 각도 기반 fallback (yaw/pitch)
    // =========================================
    private fun estimateGazeFromFaceAngles(
        yaw: Float?,
        pitch: Float?
    ): GazeResult? {

        if (yaw == null || pitch == null) return null

        val horizontalAngle = (yaw * 2f).coerceIn(-1f, 1f)
        val verticalAngle = (pitch * 2f).coerceIn(-1f, 1f)

        val direction = classifyGazeDirection(horizontalAngle, verticalAngle)

        return GazeResult(
            direction = direction,
            horizontalAngle = horizontalAngle,
            verticalAngle = verticalAngle,
            confidence = 0.7f
        )
    }

    // =========================================
    // ✅ 8방향 분류 로직 (iOS와 동일)
    // =========================================
    private fun classifyGazeDirection(
        horizontal: Float,
        vertical: Float
    ): GazeDirection {

        if (abs(horizontal) < cameraLookThreshold && abs(vertical) < cameraLookThreshold) {
            return GazeDirection.LOOKING_AT_CAMERA
        }

        val isLeft = horizontal < -horizontalThreshold
        val isRight = horizontal > horizontalThreshold
        val isUp = vertical > verticalThreshold
        val isDown = vertical < -verticalThreshold

        return when {
            isLeft && isUp -> GazeDirection.LOOKING_LEFT_UP
            isLeft && isDown -> GazeDirection.LOOKING_LEFT_DOWN
            isRight && isUp -> GazeDirection.LOOKING_RIGHT_UP
            isRight && isDown -> GazeDirection.LOOKING_RIGHT_DOWN
            isLeft -> GazeDirection.LOOKING_LEFT
            isRight -> GazeDirection.LOOKING_RIGHT
            isUp -> GazeDirection.LOOKING_UP
            isDown -> GazeDirection.LOOKING_DOWN
            else -> GazeDirection.LOOKING_AT_CAMERA
        }
    }
}
