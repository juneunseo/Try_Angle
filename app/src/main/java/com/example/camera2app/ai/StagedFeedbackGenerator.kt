package com.example.camera2app.ai

import kotlin.math.*



// MARK: - 피드백 단계 (Phase 3)

/**
 * 6단계 피드백 시스템
 */
enum class FeedbackStage(val value: Int, val displayName: String, val description: String) {
    ASPECT_RATIO(0, "비율", "카메라 비율을 맞추세요"),
    SHOT_TYPE(1, "샷 타입", "전신/상반신/얼굴 구도를 맞추세요"),
    COVERAGE(2, "점유율", "프레임 내 점유율을 조정하세요"),
    POSITION(3, "인물 위치", "인물의 좌우/상하 위치를 맞추세요"),
    FRAMING(4, "프레이밍", "머리 위 공간, 시선 방향 여백을 조정하세요"),
    POSE(5, "포즈", "신체 포즈를 맞추세요"),
    COMPLETE(99, "완벽", "완벽합니다!");

    companion object {
        fun fromValue(value: Int): FeedbackStage? {
            return values().find { it.value == value }
        }
    }
}

// MARK: - 단계별 피드백 생성기 (Phase 3)

/**
 * 6단계 우선순위 기반 피드백 생성기
 */
class StagedFeedbackGenerator {

    // MARK: - 임계값 정의

    // 샷 타입
    private val shotTypeDiffThreshold_Major = 2     // 2단계 이상 차이
    private val shotTypeDiffThreshold_Minor = 1     // 1단계 차이

    // 점유율
    private val coverageDiffThreshold = 0.15f       // 15% 차이

    // 위치
    private val positionDiffThreshold_Major = 0.15f // 15% 차이
    private val positionDiffThreshold_Minor = 0.05f // 5% 차이

    // 프레이밍
    private val headroomDiffThreshold = 0.05f       // 5% 차이
    private val leadRoomDiffThreshold = 0.10f       // 10% 차이

    // 포즈
    private val angleDiffThreshold = 15.0f          // 15도

    // MARK: - 단계 결정 로직

    /**
     * 현재 프레임의 피드백 단계 결정
     */
    fun determineFeedbackStage(
        referenceFraming: PhotographyFramingResult?,
        currentFraming: PhotographyFramingResult?,
        referenceAspectRatio: CameraAspectRatio,
        currentAspectRatio: CameraAspectRatio,
        poseComparison: PoseComparisonResult?
    ): FeedbackStage {

        // 0단계: 비율 체크
        if (referenceAspectRatio != currentAspectRatio) {
            return FeedbackStage.ASPECT_RATIO
        }

        val refFraming = referenceFraming
        val curFraming = currentFraming

        if (refFraming == null || curFraming == null) {
            // 프레이밍 정보가 없으면 포즈만 비교
            return FeedbackStage.POSE
        }

        // 1단계: 샷 타입 차이
        val shotTypeDiff = shotTypeDistance(refFraming.shotType, curFraming.shotType)

        if (shotTypeDiff >= shotTypeDiffThreshold_Major) {
            return FeedbackStage.SHOT_TYPE
        }

        // 2단계: 점유율 (샷 타입이 비슷할 때만)
        if (shotTypeDiff <= shotTypeDiffThreshold_Minor) {
            val coverageDiff = abs(refFraming.bodyCoverage - curFraming.bodyCoverage)
            if (coverageDiff > coverageDiffThreshold) {
                return FeedbackStage.COVERAGE
            }
        }

        // 3단계: 인물 위치 (샷 타입이 정확히 맞을 때만)
        if (shotTypeDiff == 0) {
            // 얼굴(코) 위치로 비교
            val positionDiff = calculatePositionDifference(refFraming, curFraming)

            if (positionDiff > positionDiffThreshold_Major) {
                return FeedbackStage.POSITION
            }

            // 4단계: 프레이밍 디테일 (위치가 대략 맞을 때만)
            if (positionDiff <= positionDiffThreshold_Minor) {
                val headroomDiff = abs(refFraming.headroom - curFraming.headroom)

                // leadRoom은 옵셔널이므로 안전하게 처리
                var leadRoomDiffExceedsThreshold = false
                val refLead = refFraming.leadRoom
                val curLead = curFraming.leadRoom
                if (refLead != null && curLead != null) {
                    leadRoomDiffExceedsThreshold = abs(refLead - curLead) > leadRoomDiffThreshold
                }

                if (headroomDiff > headroomDiffThreshold || leadRoomDiffExceedsThreshold) {
                    return FeedbackStage.FRAMING
                }

                // 5단계: 포즈 (프레이밍이 모두 맞을 때)
                poseComparison?.let { poseResult ->
                    // 각도 차이가 임계값을 넘으면 포즈 단계
                    val hasAngleDifference = poseResult.angleDifferences.values.any { it > angleDiffThreshold }
                    if (hasAngleDifference) {
                        return FeedbackStage.POSE
                    }
                }

                // 모두 완벽!
                return FeedbackStage.COMPLETE
            }
        }

        // 기본값: 포즈
        return FeedbackStage.POSE
    }

    // MARK: - 단계별 피드백 생성

    /**
     * 단계에 맞는 피드백 메시지 생성
     */
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
            FeedbackStage.ASPECT_RATIO -> generateAspectRatioFeedback(
                referenceAspectRatio,
                currentAspectRatio
            )

            FeedbackStage.SHOT_TYPE -> generateShotTypeFeedback(
                referenceFraming,
                currentFraming,
                isFrontCamera
            )

            FeedbackStage.COVERAGE -> generateCoverageFeedback(
                referenceFraming,
                currentFraming,
                isFrontCamera
            )

            FeedbackStage.POSITION -> generatePositionFeedback(
                referenceFraming,
                currentFraming,
                isFrontCamera
            )

            FeedbackStage.FRAMING -> generateFramingDetailFeedback(
                referenceFraming,
                currentFraming,
                isFrontCamera
            )

            FeedbackStage.POSE -> {
                val feedbacks = mutableListOf<FeedbackItem>()

                // 잘림 피드백 우선
                if (croppedGroups.isNotEmpty()) {
                    generateCroppingFeedback(croppedGroups, isFrontCamera)?.let {
                        feedbacks.add(it)
                    }
                }

                // 포즈 피드백
                generatePoseFeedback(poseComparison)?.let {
                    feedbacks.addAll(it)
                }

                feedbacks
            }

            FeedbackStage.COMPLETE -> emptyList()  // 완벽한 상태
        }
    }

    // MARK: - Helper Functions

    /**
     * 샷 타입 간 거리 계산 (0~7)
     */
    private fun shotTypeDistance(ref: ShotType, current: ShotType): Int {
        val levels = listOf(
            ShotType.EXTREME_CLOSE_UP,   // 0
            ShotType.CLOSE_UP,            // 1
            ShotType.MEDIUM_CLOSE_UP,     // 2
            ShotType.MEDIUM_SHOT,         // 3
            ShotType.AMERICAN_SHOT,       // 4
            ShotType.MEDIUM_FULL_SHOT,    // 5
            ShotType.FULL_SHOT,           // 6
            ShotType.LONG_SHOT            // 7
        )

        val refIndex = levels.indexOf(ref)
        val curIndex = levels.indexOf(current)

        if (refIndex == -1 || curIndex == -1) return 0

        return abs(refIndex - curIndex)
    }

    /**
     * 인물 위치 차이 계산 (정규화된 거리)
     */
    private fun calculatePositionDifference(
        reference: PhotographyFramingResult,
        current: PhotographyFramingResult
    ): Float {
        // 코(nose) 위치로 비교
        val refNose = reference.nosePosition
        val curNose = current.nosePosition

        val dx = refNose.x - curNose.x
        val dy = refNose.y - curNose.y

        return sqrt(dx * dx + dy * dy)
    }

    // MARK: - 각 단계별 피드백 생성 함수들

    private fun generateAspectRatioFeedback(
        reference: CameraAspectRatio,
        current: CameraAspectRatio
    ): List<FeedbackItem> {
        return listOf(
            FeedbackItem(
                priority = -1,
                icon = "📐",
                message = "카메라 비율을 ${reference.displayName}로 변경하세요",
                category = "aspect_ratio",
                currentValue = null,
                targetValue = null,
                tolerance = null,
                unit = null
            )
        )
    }

    private fun generateShotTypeFeedback(
        reference: PhotographyFramingResult?,
        current: PhotographyFramingResult?,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {
        if (reference == null || current == null) return emptyList()

        val message = when {
            // 전신 → 상반신
            reference.shotType == ShotType.FULL_SHOT && current.shotType == ShotType.MEDIUM_SHOT ->
                if (isFrontCamera) "뒤로 물러나세요 (전신이 보이게)" else "카메라를 뒤로 멀리하세요 (전신이 보이게)"

            // 전신 → 얼굴
            reference.shotType == ShotType.FULL_SHOT && current.shotType == ShotType.CLOSE_UP ->
                if (isFrontCamera) "뒤로 물러나세요 (전신이 보이게)" else "카메라를 뒤로 멀리하세요 (전신이 보이게)"

            // 상반신 → 얼굴
            reference.shotType == ShotType.MEDIUM_SHOT && current.shotType == ShotType.CLOSE_UP ->
                if (isFrontCamera) "뒤로 물러나세요 (상반신이 보이게)" else "카메라를 뒤로 멀리하세요 (상반신이 보이게)"

            // 상반신 → 전신
            reference.shotType == ShotType.MEDIUM_SHOT && current.shotType == ShotType.FULL_SHOT ->
                if (isFrontCamera) "가까이 다가오세요 (상반신만)" else "카메라를 가까이 당기세요 (상반신만)"

            // 얼굴 → 전신
            reference.shotType == ShotType.CLOSE_UP && current.shotType == ShotType.FULL_SHOT ->
                if (isFrontCamera) "가까이 다가오세요 (얼굴 중심)" else "카메라를 가까이 당기세요 (얼굴 중심)"

            else ->
                if (isFrontCamera) "거리를 조정하세요 (${reference.shotType.userFriendlyDescription})"
                else "카메라 거리를 조정하세요 (${reference.shotType.userFriendlyDescription})"
        }

        return listOf(
            FeedbackItem(
                priority = 0,
                icon = "📸",
                message = message,
                category = "shot_type",
                currentValue = null,
                targetValue = null,
                tolerance = null,
                unit = null
            )
        )
    }

    private fun generateCoverageFeedback(
        reference: PhotographyFramingResult?,
        current: PhotographyFramingResult?,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {
        if (reference == null || current == null) return emptyList()

        val coverageDiff = current.bodyCoverage - reference.bodyCoverage

        val message = if (coverageDiff > 0) {
            // 현재가 더 꽉 참 → 전면: 사람 뒤로, 후면: 카메라 뒤로
            if (isFrontCamera) "뒤로 물러나세요" else "카메라를 뒤로 멀리하세요"
        } else {
            // 현재가 더 여유 있음 → 전면: 사람 앞으로, 후면: 카메라 앞으로
            if (isFrontCamera) "가까이 다가오세요" else "카메라를 가까이 당기세요"
        }

        return listOf(
            FeedbackItem(
                priority = 0,
                icon = "🔍",
                message = message,
                category = "coverage",
                currentValue = (current.bodyCoverage * 100).toDouble(),
                targetValue = (reference.bodyCoverage * 100).toDouble(),
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

        val feedbacks = mutableListOf<FeedbackItem>()

        // 좌우 위치 - 사람이 이동
        val xDiff = current.nosePosition.x - reference.nosePosition.x
        if (abs(xDiff) > positionDiffThreshold_Minor) {
            val message = if (isFrontCamera) {
                // 전면 카메라는 좌우 반전
                if (xDiff > 0) "왼쪽으로 이동하세요" else "오른쪽으로 이동하세요"
            } else {
                if (xDiff > 0) "오른쪽으로 이동하세요" else "왼쪽으로 이동하세요"
            }

            feedbacks.add(
                FeedbackItem(
                    priority = 1,
                    icon = "↔️",
                    message = message,
                    category = "position_x",
                    currentValue = (current.nosePosition.x * 100).toDouble(),
                    targetValue = (reference.nosePosition.x * 100).toDouble(),
                    tolerance = 5.0,
                    unit = "%"
                )
            )
        }

        // 상하 위치 - 카메라를 조작
        val yDiff = current.nosePosition.y - reference.nosePosition.y
        if (abs(yDiff) > positionDiffThreshold_Minor) {
            val message = if (yDiff > 0) "카메라를 아래로 내려주세요" else "카메라를 위로 올려주세요"

            feedbacks.add(
                FeedbackItem(
                    priority = 2,
                    icon = "↕️",
                    message = message,
                    category = "position_y",
                    currentValue = (current.nosePosition.y * 100).toDouble(),
                    targetValue = (reference.nosePosition.y * 100).toDouble(),
                    tolerance = 5.0,
                    unit = "%"
                )
            )
        }

        return feedbacks
    }

    private fun generateFramingDetailFeedback(
        reference: PhotographyFramingResult?,
        current: PhotographyFramingResult?,
        isFrontCamera: Boolean
    ): List<FeedbackItem> {
        if (reference == null || current == null) return emptyList()

        val feedbacks = mutableListOf<FeedbackItem>()

        // 헤드룸 - 카메라 수직 조작
        val headroomDiff = current.headroom - reference.headroom
        if (abs(headroomDiff) > headroomDiffThreshold) {
            val message = if (headroomDiff > 0) "카메라를 아래로 내려주세요" else "카메라를 위로 올려주세요"

            feedbacks.add(
                FeedbackItem(
                    priority = 1,
                    icon = "⬆️",
                    message = message,
                    category = "headroom",
                    currentValue = (current.headroom * 100).toDouble(),
                    targetValue = (reference.headroom * 100).toDouble(),
                    tolerance = 5.0,
                    unit = "%"
                )
            )
        }

        // 리드룸 (시선 방향 여백) - 사람이 좌우 이동
        val curLeadRoom = current.leadRoom
        val refLeadRoom = reference.leadRoom
        if (curLeadRoom != null && refLeadRoom != null) {
            val leadRoomDiff = curLeadRoom - refLeadRoom
            if (abs(leadRoomDiff) > leadRoomDiffThreshold) {
                val message = if (leadRoomDiff > 0) {
                    // 시선 방향 여백이 많음 → 시선 반대 방향으로 이동
                    when (current.gazeDirection) {
                        FramingGazeDirection.LEFT ->
                            if (isFrontCamera) "왼쪽으로 이동하세요" else "오른쪽으로 이동하세요"
                        FramingGazeDirection.RIGHT ->
                            if (isFrontCamera) "오른쪽으로 이동하세요" else "왼쪽으로 이동하세요"
                        else -> "시선 방향 여백을 줄이세요"
                    }
                } else {
                    // 시선 방향 여백이 부족 → 시선 방향으로 이동
                    when (current.gazeDirection) {
                        FramingGazeDirection.LEFT ->
                            if (isFrontCamera) "오른쪽으로 이동하세요" else "왼쪽으로 이동하세요"
                        FramingGazeDirection.RIGHT ->
                            if (isFrontCamera) "왼쪽으로 이동하세요" else "오른쪽으로 이동하세요"
                        else -> "시선 방향에 여백을 더 주세요"
                    }
                }

                feedbacks.add(
                    FeedbackItem(
                        priority = 2,
                        icon = "👁️",
                        message = message,
                        category = "leadroom",
                        currentValue = (curLeadRoom * 100).toDouble(),
                        targetValue = (refLeadRoom * 100).toDouble(),
                        tolerance = 10.0,
                        unit = "%"
                    )
                )
            }
        }

        return feedbacks
    }

    private fun generateCroppingFeedback(
        croppedGroups: List<KeypointGroup>,
        isFrontCamera: Boolean
    ): FeedbackItem? {
        if (croppedGroups.isEmpty()) return null

        // 우선순위: legs > feet > arms > hands > head
        val message = when {
            croppedGroups.contains(KeypointGroup.LEGS) ->
                if (isFrontCamera) "다리가 잘렸어요. 뒤로 물러나세요" else "다리가 잘렸어요. 카메라를 뒤로 멀리하세요"
            croppedGroups.contains(KeypointGroup.FEET) ->
                if (isFrontCamera) "발이 잘렸어요. 뒤로 물러나세요" else "발이 잘렸어요. 카메라를 뒤로 멀리하세요"
            croppedGroups.contains(KeypointGroup.ARMS) ->
                if (isFrontCamera) "팔이 잘렸어요. 뒤로 물러나세요" else "팔이 잘렸어요. 카메라를 뒤로 멀리하세요"
            croppedGroups.contains(KeypointGroup.LEFT_HAND) || croppedGroups.contains(KeypointGroup.RIGHT_HAND) ->
                if (isFrontCamera) "손이 잘렸어요. 뒤로 물러나세요" else "손이 잘렸어요. 카메라를 뒤로 멀리하세요"
            croppedGroups.contains(KeypointGroup.HEAD) ->
                "머리가 잘렸어요. 카메라를 아래로 내려주세요"
            else ->
                "${croppedGroups.first().displayName}가 잘렸어요. 카메라를 조정하세요"
        }

        return FeedbackItem(
            priority = 0,
            icon = "✂️",
            message = message,
            category = "pose_cropped",
            currentValue = null,
            targetValue = null,
            tolerance = null,
            unit = null
        )
    }

    private fun generatePoseFeedback(poseComparison: PoseComparisonResult?): List<FeedbackItem>? {
        if (poseComparison == null) return null

        val feedbacks = mutableListOf<FeedbackItem>()

        // 각 부위별 각도 차이 피드백
        for ((part, diff) in poseComparison.angleDifferences) {
            if (diff > angleDiffThreshold) {
                // angleDirections에서 구체적인 메시지 가져오기
                val message = poseComparison.angleDirections[part] ?: run {
                    // fallback: 기존 메시지
                    when (part) {
                        "left_arm" -> "왼팔 각도를 조정하세요"
                        "right_arm" -> "오른팔 각도를 조정하세요"
                        "left_leg" -> "왼다리 각도를 조정하세요"
                        "right_leg" -> "오른다리 각도를 조정하세요"
                        "left_hand" -> "왼손 위치를 조정하세요"
                        "right_hand" -> "오른손 위치를 조정하세요"
                        "shoulder_tilt" -> "몸 기울기를 조정하세요"
                        "face" -> "고개 방향을 조정하세요"
                        else -> "${part}를 조정하세요"
                    }
                }

                // 아이콘 선택
                val icon = when (part) {
                    "shoulder_tilt" -> "↔️"  // 몸통 기울기
                    "face" -> "👤"            // 얼굴 방향
                    else -> "🤸"              // 포즈
                }

                feedbacks.add(
                    FeedbackItem(
                        priority = feedbacks.size + 1,
                        icon = icon,
                        message = message,
                        category = "pose_$part",
                        currentValue = null,
                        targetValue = null,
                        tolerance = angleDiffThreshold.toDouble(),
                        unit = "도"
                    )
                )
            }
        }

        // 최대 3개까지만 표시
        return feedbacks.take(3)
    }
}