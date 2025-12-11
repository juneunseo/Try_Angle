package com.example.camera2app.ai

import android.graphics.PointF
import kotlin.math.*

class PhotographyFramingAnalyzer {

    private val confidenceThreshold = 0.3f

    fun analyze(
        keypoints: List<Pair<PointF, Float>>,
        imageWidth: Int,
        imageHeight: Int
    ): PhotographyFramingResult? {

        if (keypoints.size < 133) return null

        val (shotType, shotConfidence) = determineShotType(keypoints)

        val (headroom, headroomStatus) = calculateHeadroom(keypoints, shotType)
        val optimalHeadroom =
            (shotType.headroomRange.start + shotType.headroomRange.endInclusive) / 2f

        val gazeDirection = detectGazeDirection(keypoints)
        val (leadRoom, leadRoomStatus) = calculateLeadRoom(keypoints, gazeDirection)

        val (cameraAngle, angleValue) = analyzeCameraAngle(keypoints)

        val violations = checkCroppingViolations(keypoints)

        val bodyCoverage = calculateBodyCoverage(keypoints, shotType)

        val overallScore = calculateOverallScore(
            headroomStatus,
            leadRoomStatus,
            violations,
            shotConfidence
        )

        val nosePosition = keypoints[KeypointIndex.nose].first

        return PhotographyFramingResult(
            shotType,
            shotConfidence,
            headroom,
            optimalHeadroom,
            headroomStatus,
            leadRoom,
            gazeDirection,
            leadRoomStatus,
            cameraAngle,
            angleValue,
            violations,
            bodyCoverage,
            nosePosition,
            overallScore
        )
    }

    // ✅ ShotType 판별
    private fun determineShotType(
        keypoints: List<Pair<PointF, Float>>
    ): Pair<ShotType, Float> {

        val hasHead = keypoints[KeypointIndex.nose].second > confidenceThreshold
        val hasShoulders =
            keypoints[KeypointIndex.leftShoulder].second > confidenceThreshold ||
                    keypoints[KeypointIndex.rightShoulder].second > confidenceThreshold

        val hasHips =
            keypoints[KeypointIndex.leftHip].second > confidenceThreshold ||
                    keypoints[KeypointIndex.rightHip].second > confidenceThreshold

        val hasKnees =
            keypoints[KeypointIndex.leftKnee].second > confidenceThreshold ||
                    keypoints[KeypointIndex.rightKnee].second > confidenceThreshold

        val hasAnkles =
            keypoints[KeypointIndex.leftAnkle].second > confidenceThreshold ||
                    keypoints[KeypointIndex.rightAnkle].second > confidenceThreshold

        val hasFeet =
            keypoints[KeypointIndex.leftFootStart].second > confidenceThreshold ||
                    keypoints[KeypointIndex.rightFootStart].second > confidenceThreshold

        var shotType = ShotType.MEDIUM_SHOT
        var confidence = 0.85f

        if (!hasHead) shotType = ShotType.EXTREME_CLOSE_UP
        else if (!hasShoulders) shotType = ShotType.CLOSE_UP
        else if (!hasHips) shotType = ShotType.MEDIUM_CLOSE_UP
        else if (!hasKnees) shotType = ShotType.MEDIUM_SHOT
        else if (!hasAnkles) shotType = ShotType.AMERICAN_SHOT
        else if (!hasFeet) shotType = ShotType.MEDIUM_FULL_SHOT
        else shotType = ShotType.FULL_SHOT

        return shotType to confidence
    }

    // ✅ 헤드룸 계산
    private fun calculateHeadroom(
        keypoints: List<Pair<PointF, Float>>,
        shotType: ShotType
    ): Pair<Float, SpaceStatus> {

        var topY = 1f
        for (i in 0 until 133) {
            if (keypoints[i].second > confidenceThreshold) {
                topY = min(topY, keypoints[i].first.y)
            }
        }

        val range = shotType.headroomRange

        val status = when {
            topY < range.start -> SpaceStatus.TOO_LITTLE
            topY > range.endInclusive -> SpaceStatus.TOO_MUCH
            else -> SpaceStatus.OPTIMAL
        }

        return topY to status
    }

    // ✅ 시선 방향
    private fun detectGazeDirection(
        keypoints: List<Pair<PointF, Float>>
    ): FramingGazeDirection {

        val leftEye = keypoints[KeypointIndex.leftEye]
        val rightEye = keypoints[KeypointIndex.rightEye]
        val nose = keypoints[KeypointIndex.nose]

        if (leftEye.second < confidenceThreshold ||
            rightEye.second < confidenceThreshold ||
            nose.second < confidenceThreshold
        ) return FramingGazeDirection.CENTER

        val eyeCenterX = (leftEye.first.x + rightEye.first.x) / 2f
        val offset = nose.first.x - eyeCenterX

        return when {
            offset < -0.05f -> FramingGazeDirection.LEFT
            offset > 0.05f -> FramingGazeDirection.RIGHT
            else -> FramingGazeDirection.CENTER
        }
    }

    // ✅ 리드룸
    private fun calculateLeadRoom(
        keypoints: List<Pair<PointF, Float>>,
        gazeDirection: FramingGazeDirection
    ): Pair<Float?, SpaceStatus?> {

        if (gazeDirection == FramingGazeDirection.CENTER) return null to null

        val nose = keypoints[KeypointIndex.nose]
        if (nose.second < confidenceThreshold) return null to null

        val faceX = nose.first.x

        val leadRoom = if (gazeDirection == FramingGazeDirection.LEFT) faceX else 1f - faceX

        val status = when {
            leadRoom < 0.10f -> SpaceStatus.TOO_LITTLE
            leadRoom > 0.45f -> SpaceStatus.TOO_MUCH
            else -> SpaceStatus.OPTIMAL
        }

        return leadRoom to status
    }

    // ✅ 카메라 앵글
    private fun analyzeCameraAngle(
        keypoints: List<Pair<PointF, Float>>
    ): Pair<PhotoCameraAngle, Float> {

        val leftShoulder = keypoints[KeypointIndex.leftShoulder]
        val rightShoulder = keypoints[KeypointIndex.rightShoulder]
        val leftEye = keypoints[KeypointIndex.leftEye]
        val rightEye = keypoints[KeypointIndex.rightEye]

        if (leftShoulder.second < confidenceThreshold ||
            rightShoulder.second < confidenceThreshold ||
            leftEye.second < confidenceThreshold ||
            rightEye.second < confidenceThreshold
        ) return PhotoCameraAngle.EYE_LEVEL to 0f

        val shoulderY = (leftShoulder.first.y + rightShoulder.first.y) / 2f
        val eyeY = (leftEye.first.y + rightEye.first.y) / 2f

        val diff = shoulderY - eyeY

        return when {
            diff < 0.05f -> PhotoCameraAngle.BIRDS_EYE to diff * 100
            diff < 0.10f -> PhotoCameraAngle.HIGH_ANGLE to diff * 100
            diff > 0.25f -> PhotoCameraAngle.LOW_ANGLE to diff * 100
            else -> PhotoCameraAngle.EYE_LEVEL to diff * 100
        }
    }

    // ✅ 잘림 체크
    private fun checkCroppingViolations(
        keypoints: List<Pair<PointF, Float>>
    ): List<CroppingViolation> {

        val violations = mutableListOf<CroppingViolation>()

        val joints = listOf(
            KeypointIndex.leftAnkle to "왼쪽 발목",
            KeypointIndex.rightAnkle to "오른쪽 발목",
            KeypointIndex.leftKnee to "왼쪽 무릎",
            KeypointIndex.rightKnee to "오른쪽 무릎"
        )

        for ((idx, name) in joints) {
            val joint = keypoints[idx]
            if (joint.second > confidenceThreshold) {
                val y = joint.first.y
                if (y > 0.95f) {
                    violations.add(
                        CroppingViolation(
                            name,
                            y,
                            ViolationSeverity.CRITICAL
                        )
                    )
                }
            }
        }

        return violations
    }

    // ✅ 신체 점유율
    private fun calculateBodyCoverage(
        keypoints: List<Pair<PointF, Float>>,
        shotType: ShotType
    ): Float {

        val valid = keypoints.take(17)
            .filter { it.second > confidenceThreshold }
            .map { it.first }

        if (valid.size < 3) return 0.5f

        val minX = valid.minOf { it.x }
        val maxX = valid.maxOf { it.x }
        val minY = valid.minOf { it.y }
        val maxY = valid.maxOf { it.y }

        return (maxX - minX) * (maxY - minY)
    }

    private fun calculateOverallScore(
        headroomStatus: SpaceStatus,
        leadRoomStatus: SpaceStatus?,
        violations: List<CroppingViolation>,
        shotConfidence: Float
    ): Float {

        var score = 1.0f

        if (headroomStatus == SpaceStatus.TOO_LITTLE) score -= 0.25f
        if (headroomStatus == SpaceStatus.TOO_MUCH) score -= 0.15f

        if (leadRoomStatus == SpaceStatus.TOO_LITTLE) score -= 0.15f
        if (leadRoomStatus == SpaceStatus.TOO_MUCH) score -= 0.1f

        for (v in violations) {
            score -= if (v.severity == ViolationSeverity.CRITICAL) 0.2f else 0.1f
        }

        score *= shotConfidence

        return score.coerceIn(0f, 1f)
    }
}
