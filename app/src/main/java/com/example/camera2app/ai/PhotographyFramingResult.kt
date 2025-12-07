package com.example.camera2app.ai

import android.graphics.PointF

data class PhotographyFramingResult(
    val shotType: ShotType,
    val shotTypeConfidence: Float,

    val headroom: Float,
    val optimalHeadroom: Float,
    val headroomStatus: SpaceStatus,

    val leadRoom: Float?,
    val gazeDirection: FramingGazeDirection,
    val leadRoomStatus: SpaceStatus?,

    val cameraAngle: PhotoCameraAngle,
    val cameraAngleValue: Float,

    val croppingViolations: List<CroppingViolation>,

    val bodyCoverage: Float,
    val nosePosition: PointF,

    val overallScore: Float
)
