package com.example.camera2app.ai

import kotlin.math.abs
import kotlin.math.sqrt

class StagedFeedbackGenerator {

    // ✅ 임계값 정의
    private val shotTypeDiffThresholdMajor = 2
    private val shotTypeDiffThresholdMinor = 1

    private val coverageDiffThreshold = 0.15f

    private val positionDiffThresholdMajor = 0.15f
    private val positionDiffThresholdMinor = 0.05f

    private val headroomDiffThreshold = 0.05f
    private val leadRoomDiffThreshold = 0.10f

    private val angleDiffThreshold = 15.0f

    // =========================================================
    // ✅ 피드백 단계 결정
    // =========================================================
    fun determineFeedbackStage(
        referenceFraming: PhotographyFramingResult?,
        currentFraming: PhotographyFramingResult?,
        referenceAspectRatio: CameraAspectRatio,
        currentAspectRatio: CameraAspectRatio,
        poseComparison: PoseComparisonResult?
    ): FeedbackStage {

        // 0단계: 비율
        if (referenceAspectRatio != currentAspectRatio) {
            return FeedbackStage.ASPECT_RATIO
        }

        if (referenceFraming == null || currentFraming == null) {
            return FeedbackStage.POSE
        }

        // 1단계: 샷 타입
        val shotTypeDiff = shotTypeDistance(referenceFraming.shotType, currentFraming.shotType)
        if (shotTypeDiff >= shotTypeDiffThresholdMajor) return FeedbackStage.SHOT_TYPE

        // 2단계: 점유율
        if (shotTypeDiff <= shotTypeDiffThresholdMinor) {
            val coverageDiff = abs(referenceFraming.bodyCoverage - currentFraming.bodyCoverage)
            if (coverageDiff > coverageDiffThreshold) return FeedbackStage.COVERAGE
        }

        // 3단계: 위치
        if (shotTypeDiff == 0) {
            val positionDiff = calculatePositionDifference(referenceFraming, currentFraming)
            if (positionDiff > positionDiffThresholdMajor) return FeedbackStage.POSITION

            // 4단계: 프레이밍
            if (positionDiff <= positionDiffThresholdMinor) {
                val headroomDiff = abs(referenceFraming.headroom - currentFraming.headroom)

                val leadRoomDiff = if (
                    referenceFraming.leadRoom != null && currentFraming.leadRoom != null
                ) {
                    abs(referenceFraming.leadRoom!! - currentFraming.leadRoom!!)
                } else 0f

                if (headroomDiff > headroomDiffThreshold || leadRoomDiff > leadRoomDiffThreshold) {
                    return FeedbackStage.FRAMING
                }

                // 5단계: 포즈
                if (poseComparison != null) {
                    val hasAngleDiff =
                        poseComparison.angleDifferences.values.any { it > angleDiffThreshold }
                    if (hasAngleDiff) return FeedbackStage.POSE
                }

                return FeedbackStage.COMPLETE
            }
        }

        return FeedbackStage.POSE
    }

    // =========================================================
    // ✅ 단계별 피드백 생성
    // =========================================================
    fun generateStagedFeedback(
        stage: FeedbackStage,
        referenceFraming: PhotographyFramingResult?,
        currentFraming: PhotographyFramingResult?,
        referenceAspectRatio: CameraAspectRatio,
        currentAspectRatio: CameraAspectRatio,
        poseComparison: PoseComparisonResult?,
        croppedGroups: List<KeypointGroup>,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {

        return when (stage) {
            FeedbackStage.ASPECT_RATIO ->
                listOf(
                    FeedbackItem(
                        priority = -1,
                        icon = "📐",
                        message = "카메라 비율을 ${referenceAspectRatio.displayName}로 변경하세요",
                        category = "aspect_ratio"
                    )
                )

            FeedbackStage.SHOT_TYPE ->
                generateShotTypeFeedback(referenceFraming, currentFraming, isFrontCamera)

            FeedbackStage.COVERAGE ->
                generateCoverageFeedback(referenceFraming, currentFraming, isFrontCamera)

            FeedbackStage.POSITION ->
                generatePositionFeedback(referenceFraming, currentFraming, isFrontCamera)

            FeedbackStage.FRAMING ->
                generateFramingDetailFeedback(referenceFraming, currentFraming, isFrontCamera)

            FeedbackStage.POSE -> {
                val result = mutableListOf<FeedbackItem>()

                if (croppedGroups.isNotEmpty()) {
                    generateCroppingFeedback(croppedGroups, isFrontCamera)?.let {
                        result.add(it)
                    }
                }

                generatePoseFeedback(poseComparison)?.let {
                    result.addAll(it)
                }

                result
            }

            FeedbackStage.COMPLETE -> emptyList()
        }
    }

    // =========================================================
    // ✅ 내부 헬퍼
    // =========================================================
    private fun shotTypeDistance(ref: ShotType, cur: ShotType): Int {
        val levels = listOf(
            ShotType.EXTREME_CLOSE_UP,
            ShotType.CLOSE_UP,
            ShotType.MEDIUM_CLOSE_UP,
            ShotType.MEDIUM_SHOT,
            ShotType.AMERICAN_SHOT,
            ShotType.MEDIUM_FULL_SHOT,
            ShotType.FULL_SHOT,
            ShotType.LONG_SHOT
        )

        val refIndex = levels.indexOf(ref)
        val curIndex = levels.indexOf(cur)
        return if (refIndex >= 0 && curIndex >= 0) abs(refIndex - curIndex) else 0
    }

    private fun calculatePositionDifference(
        reference: PhotographyFramingResult,
        current: PhotographyFramingResult
    ): Float {
        val dx = reference.nosePosition.x - current.nosePosition.x
        val dy = reference.nosePosition.y - current.nosePosition.y
        return sqrt(dx * dx + dy * dy)
    }

    // =========================================================
    // ✅ 하위 단계별 세부 함수 (원본 로직 유지)
    // =========================================================

    private fun generateShotTypeFeedback(
        reference: PhotographyFramingResult?,
        current: PhotographyFramingResult?,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {
        if (reference == null || current == null) return emptyList()

        val message =
            if (reference.shotType == ShotType.FULL_SHOT && current.shotType == ShotType.MEDIUM_SHOT)
                if (isFrontCamera) "뒤로 물러나세요 (전신)" else "카메라를 뒤로"
            else
                if (isFrontCamera) "거리를 조정하세요 (${reference.shotType.userFriendlyDescription})"
                else "카메라 거리를 조정하세요 (${reference.shotType.userFriendlyDescription})"

        return listOf(
            FeedbackItem(
                priority = 0,
                icon = "📸",
                message = message,
                category = "shot_type"
            )
        )
    }

    private fun generateCoverageFeedback(
        reference: PhotographyFramingResult?,
        current: PhotographyFramingResult?,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {
        if (reference == null || current == null) return emptyList()

        val diff = current.bodyCoverage - reference.bodyCoverage

        val message =
            if (diff > 0)
                if (isFrontCamera) "뒤로 물러나세요" else "카메라를 뒤로"
            else
                if (isFrontCamera) "가까이 오세요" else "카메라를 앞으로"

        return listOf(
            FeedbackItem(
                priority = 0,
                icon = "🔍",
                message = message,
                category = "coverage",
                currentValue = current.bodyCoverage * 100.0,
                targetValue = reference.bodyCoverage * 100.0,
                tolerance = 5.0,
                unit = "%"
            )
        )
    }

    private fun generatePositionFeedback(
        reference: PhotographyFramingResult?,
        current: PhotographyFramingResult?,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {
        if (reference == null || current == null) return emptyList()

        val results = mutableListOf<FeedbackItem>()

        val xDiff = current.nosePosition.x - reference.nosePosition.x
        if (abs(xDiff) > positionDiffThresholdMinor) {
            val message =
                if (isFrontCamera)
                    if (xDiff > 0) "왼쪽으로 이동" else "오른쪽으로 이동"
                else
                    if (xDiff > 0) "오른쪽으로 이동" else "왼쪽으로 이동"

            results.add(
                FeedbackItem(
                    priority = 1,
                    icon = "↔️",
                    message = message,
                    category = "position_x"
                )
            )
        }

        return results
    }

    private fun generateFramingDetailFeedback(
        reference: PhotographyFramingResult?,
        current: PhotographyFramingResult?,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {

        if (reference == null || current == null) return emptyList()

        val results = mutableListOf<FeedbackItem>()

        val headroomDiff = current.headroom - reference.headroom
        if (abs(headroomDiff) > headroomDiffThreshold) {
            val message =
                if (headroomDiff > 0) "카메라를 아래로" else "카메라를 위로"

            results.add(
                FeedbackItem(
                    priority = 1,
                    icon = "⬆️",
                    message = message,
                    category = "headroom"
                )
            )
        }

        return results
    }

    private fun generateCroppingFeedback(
        croppedGroups: List<KeypointGroup>,
        isFrontCamera: Boolean
    ): FeedbackItem? {

        if (croppedGroups.isEmpty()) return null

        val message =
            if (croppedGroups.contains(KeypointGroup.LEGS))
                if (isFrontCamera) "다리가 잘림, 뒤로 이동" else "다리가 잘림, 카메라 뒤로"
            else "신체 일부가 잘림"

        return FeedbackItem(
            priority = 0,
            icon = "✂️",
            message = message,
            category = "pose_cropped"
        )
    }

    private fun generatePoseFeedback(
        poseComparison: PoseComparisonResult?
    ): List<FeedbackItem>? {

        if (poseComparison == null) return null

        val results = mutableListOf<FeedbackItem>()

        for ((part, diff) in poseComparison.angleDifferences) {
            if (diff > angleDiffThreshold) {
                val message = poseComparison.angleDirections[part]
                    ?: "$part 각도를 조정하세요"

                results.add(
                    FeedbackItem(
                        priority = results.size + 1,
                        icon = "🤸",
                        message = message,
                        category = "pose_$part"
                    )
                )
            }
        }

        return results.take(3)
    }
}
