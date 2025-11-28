package com.example.camera2app.ai

import android.graphics.PointF
import kotlin.math.*

// MARK: - 샷 타입 (사진학 기준)

enum class ShotType(
    val displayName: String,
    val userFriendlyDescription: String
) {
    EXTREME_CLOSE_UP("익스트림 클로즈업", "얼굴 일부만"),
    CLOSE_UP("클로즈업", "얼굴 중심"),
    MEDIUM_CLOSE_UP("미디엄 클로즈업", "어깨 위까지"),
    MEDIUM_SHOT("미디엄샷", "허리 위까지"),
    AMERICAN_SHOT("아메리칸샷", "무릎 위까지"),
    MEDIUM_FULL_SHOT("미디엄 풀샷", "무릎 아래까지"),
    FULL_SHOT("풀샷", "전신"),
    LONG_SHOT("롱샷", "전신 + 배경");

    /**
     * 샷 타입별 권장 헤드룸 범위
     */
    val headroomRange: ClosedFloatingPointRange<Float>
        get() = when (this) {
            EXTREME_CLOSE_UP -> 0.02f..0.08f
            CLOSE_UP -> 0.05f..0.15f
            MEDIUM_CLOSE_UP -> 0.08f..0.18f
            MEDIUM_SHOT -> 0.10f..0.20f
            AMERICAN_SHOT -> 0.08f..0.15f
            MEDIUM_FULL_SHOT -> 0.05f..0.12f
            FULL_SHOT -> 0.03f..0.10f
            LONG_SHOT -> 0.02f..0.08f
        }
}

// MARK: - 카메라 앵글 (사진학 기준)

enum class PhotoCameraAngle(val displayName: String, val description: String) {
    HIGH_ANGLE("하이앵글", "위에서 촬영 (부드러운 느낌)"),
    EYE_LEVEL("아이레벨", "눈높이 촬영 (자연스러운 느낌)"),
    LOW_ANGLE("로우앵글", "아래에서 촬영 (강인한 느낌)"),
    BIRDS_EYE("버즈아이", "직하방 촬영"),
    DUTCH_ANGLE("더치앵글", "기울임 촬영 (역동적)")
}

// MARK: - 여백 상태

enum class SpaceStatus(val displayName: String) {
    TOO_MUCH("과다"),
    OPTIMAL("적정"),
    TOO_LITTLE("부족"),
    NONE("없음")
}

// MARK: - 프레이밍용 시선 방향 (단순화)

enum class FramingGazeDirection(val displayName: String) {
    LEFT("왼쪽"),
    RIGHT("오른쪽"),
    CENTER("정면"),
    UP("위"),
    DOWN("아래")
}

// MARK: - 잘림 규칙 위반

data class CroppingViolation(
    val jointName: String,              // 잘린 관절명
    val position: Float,                // 위치 (정규화)
    val severity: ViolationSeverity
)

enum class ViolationSeverity(val displayName: String) {
    CRITICAL("심각"),      // 관절에서 직접 잘림
    WARNING("경고"),       // 관절 근처에서 잘림
    MINOR("경미")          // 약간 어색함
}

// MARK: - 프레이밍 분석 결과

data class PhotographyFramingResult(
    // 샷 타입
    val shotType: ShotType,
    val shotTypeConfidence: Float,

    // 헤드룸 (정규화된 값: 0.0~1.0)
    val headroom: Float,                // 머리 위 여백
    val optimalHeadroom: Float,         // 샷 타입에 맞는 최적 헤드룸
    val headroomStatus: SpaceStatus,

    // 리드룸 (시선 방향 여백)
    val leadRoom: Float?,               // 시선 방향 여백
    val gazeDirection: FramingGazeDirection,
    val leadRoomStatus: SpaceStatus?,

    // 카메라 앵글
    val cameraAngle: PhotoCameraAngle,
    val cameraAngleValue: Float,        // 실제 각도 (도)

    // 잘림 체크 (사진 규칙 위반)
    val croppingViolations: List<CroppingViolation>,

    // 신체 점유율 (0.0~1.0)
    val bodyCoverage: Float,            // 구조적 키포인트가 차지하는 프레임 비율

    // 코(nose) 위치 (정규화된 좌표: 0.0~1.0)
    val nosePosition: PointF,           // 인물 위치 비교용

    // 전체 점수 (0.0~1.0)
    val overallScore: Float
) {
    /**
     * 한국어 피드백 메시지 생성
     */
    fun generateFeedback(): String? {
        val feedbacks = mutableListOf<String>()

        // 1. 헤드룸 피드백
        when (headroomStatus) {
            SpaceStatus.TOO_LITTLE -> feedbacks.add("머리 위 여백이 부족해요. 카메라를 살짝 위로 올리거나 뒤로 물러서세요.")
            SpaceStatus.TOO_MUCH -> feedbacks.add("머리 위 공간이 너무 많아요. 인물을 프레임 위쪽으로 이동하거나 줌인하세요.")
            else -> {}
        }

        // 2. 리드룸 피드백
        leadRoomStatus?.let { status ->
            when (status) {
                SpaceStatus.TOO_LITTLE -> feedbacks.add("시선 방향(${gazeDirection.displayName})에 여백을 더 주세요.")
                SpaceStatus.TOO_MUCH -> {
                    val direction = if (gazeDirection == FramingGazeDirection.LEFT) "오른쪽" else "왼쪽"
                    feedbacks.add("시선 방향에 여백이 너무 많아요. 인물을 ${direction}으로 이동하세요.")
                }
                else -> {}
            }
        }

        // 3. 잘림 위반 피드백
        val criticalViolations = croppingViolations.filter { it.severity == ViolationSeverity.CRITICAL }
        if (criticalViolations.isNotEmpty()) {
            val jointNames = criticalViolations.joinToString(", ") { it.jointName }
            feedbacks.add("관절($jointNames)이 프레임 경계에서 잘려요. 조금 물러서세요.")
        }

        return feedbacks.firstOrNull()
    }

    /**
     * 레퍼런스와 비교하여 피드백 생성
     */
    fun generateFeedbackComparedTo(reference: PhotographyFramingResult, isFrontCamera: Boolean = false): String? {
        val feedbacks = mutableListOf<String>()

        // 1. 샷 타입 비교
        val currentLevel = ShotType.values().indexOf(this.shotType)
        val refLevel = ShotType.values().indexOf(reference.shotType)
        val levelDiff = currentLevel - refLevel

        if (abs(levelDiff) >= 2) {
            if (levelDiff > 0) {
                feedbacks.add("더 가까이 오세요 (${reference.shotType.userFriendlyDescription} 구도)")
            } else {
                feedbacks.add("조금 뒤로 가세요 (${reference.shotType.userFriendlyDescription} 구도)")
            }
        }

        // 2. 헤드룸 비교
        val headroomDiff = this.headroom - reference.headroom
        if (abs(headroomDiff) > 0.10f) {
            if (headroomDiff > 0) {
                feedbacks.add("머리 위 공간을 줄여주세요.")
            } else {
                feedbacks.add("머리 위 공간을 늘려주세요.")
            }
        }

        // 3. 시선 방향 비교
        if (this.gazeDirection != reference.gazeDirection &&
            this.gazeDirection != FramingGazeDirection.CENTER &&
            reference.gazeDirection != FramingGazeDirection.CENTER
        ) {
            val targetDirection = when (reference.gazeDirection) {
                FramingGazeDirection.LEFT -> if (isFrontCamera) "오른쪽" else "왼쪽"
                FramingGazeDirection.RIGHT -> if (isFrontCamera) "왼쪽" else "오른쪽"
                FramingGazeDirection.UP -> "위쪽"
                FramingGazeDirection.DOWN -> "아래쪽"
                FramingGazeDirection.CENTER -> "정면"
            }
            feedbacks.add("고개를 ${targetDirection}으로 돌려주세요")
        }

        // 4. 카메라 앵글 비교
        if (this.cameraAngle != reference.cameraAngle) {
            when (reference.cameraAngle) {
                PhotoCameraAngle.HIGH_ANGLE -> feedbacks.add("카메라를 위에서 아래로 향하게 하세요.")
                PhotoCameraAngle.LOW_ANGLE -> feedbacks.add("카메라를 아래에서 위로 향하게 하세요.")
                PhotoCameraAngle.EYE_LEVEL -> feedbacks.add("카메라를 눈높이에 맞춰주세요.")
                PhotoCameraAngle.BIRDS_EYE -> feedbacks.add("바로 위에서 아래로 촬영하세요.")
                PhotoCameraAngle.DUTCH_ANGLE -> feedbacks.add("카메라를 살짝 기울여주세요.")
            }
        }

        // 5. 잘림 규칙 위반
        val criticalViolations = croppingViolations.filter { it.severity == ViolationSeverity.CRITICAL }
        if (criticalViolations.isNotEmpty()) {
            feedbacks.add("신체 일부가 잘려요. 조금 뒤로 가세요.")
        }

        return feedbacks.firstOrNull()
    }
}

// MARK: - 전문 사진 프레이밍 분석기

/**
 * RTMPose 133개 키포인트를 분석하여 전문적인 프레이밍 정보 제공
 */
class PhotographyFramingAnalyzer {

    private val confidenceThreshold = 0.3f

    /**
     * 구조적 키포인트 (프레이밍 분석용)
     * 손가락, 얼굴 랜드마크를 제외한 신체 구조 키포인트
     */
    object StructuralKeypoints {
        val head = listOf(0, 1, 2, 3, 4)        // 코, 눈, 귀
        val shoulders = listOf(5, 6)            // 어깨
        val elbows = listOf(7, 8)               // 팔꿈치
        val wrists = listOf(9, 10)              // 손목
        val hips = listOf(11, 12)               // 엉덩이
        val knees = listOf(13, 14)              // 무릎
        val ankles = listOf(15, 16)             // 발목
        val all = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

        /**
         * 샷 타입별 중요 키포인트 반환
         */
        fun importantKeypoints(shotType: ShotType): List<Int> {
            return when (shotType) {
                ShotType.EXTREME_CLOSE_UP, ShotType.CLOSE_UP ->
                    head + shoulders

                ShotType.MEDIUM_CLOSE_UP, ShotType.MEDIUM_SHOT ->
                    head + shoulders + elbows + wrists + hips

                ShotType.AMERICAN_SHOT ->
                    head + shoulders + elbows + wrists + hips + knees

                ShotType.FULL_SHOT, ShotType.MEDIUM_FULL_SHOT, ShotType.LONG_SHOT ->
                    all
            }
        }
    }

    /**
     * 메인 분석 함수
     *
     * @param keypoints RTMPose 133개 키포인트 (정규화된 좌표: 0.0~1.0)
     * @return 사진학 기반 프레이밍 분석 결과
     */
    fun analyze(keypoints: List<KeypointWithConfidence>): PhotographyFramingResult? {
        if (keypoints.size < 133) {
            println("⚠️ 키포인트 수 부족: ${keypoints.size}/133")
            return null
        }

        // 1. 샷 타입 결정
        val (shotType, shotConfidence) = determineShotType(keypoints)

        // 2. 헤드룸 계산
        val (headroom, headroomStatus) = calculateHeadroom(keypoints, shotType)
        val optimalHeadroom = (shotType.headroomRange.start + shotType.headroomRange.endInclusive) / 2

        // 3. 시선 방향 및 리드룸 계산
        val gazeDirection = detectGazeDirection(keypoints)
        val (leadRoom, leadRoomStatus) = calculateLeadRoom(keypoints, gazeDirection)

        // 4. 카메라 앵글 분석
        val (cameraAngle, angleValue) = analyzeCameraAngle(keypoints)

        // 5. 잘림 규칙 체크
        val violations = checkCroppingViolations(keypoints)

        // 6. 신체 점유율 계산
        val bodyCoverage = calculateBodyCoverage(keypoints, shotType)

        // 7. 전체 점수 계산
        val overallScore = calculateOverallScore(headroomStatus, leadRoomStatus, violations, shotConfidence)

        // 코(nose) 위치 추출
        val nosePosition = PointF(keypoints[0].x, keypoints[0].y)

        return PhotographyFramingResult(
            shotType = shotType,
            shotTypeConfidence = shotConfidence,
            headroom = headroom,
            optimalHeadroom = optimalHeadroom,
            headroomStatus = headroomStatus,
            leadRoom = leadRoom,
            gazeDirection = gazeDirection,
            leadRoomStatus = leadRoomStatus,
            cameraAngle = cameraAngle,
            cameraAngleValue = angleValue,
            croppingViolations = violations,
            bodyCoverage = bodyCoverage,
            nosePosition = nosePosition,
            overallScore = overallScore
        )
    }

    // MARK: - 샷 타입 결정

    private fun determineShotType(keypoints: List<KeypointWithConfidence>): Pair<ShotType, Float> {
        val hasHead = keypoints[KeypointIndex.NOSE].confidence > confidenceThreshold
        val hasShoulders = keypoints[KeypointIndex.LEFT_SHOULDER].confidence > confidenceThreshold ||
                keypoints[KeypointIndex.RIGHT_SHOULDER].confidence > confidenceThreshold
        val hasHips = keypoints[KeypointIndex.LEFT_HIP].confidence > confidenceThreshold ||
                keypoints[KeypointIndex.RIGHT_HIP].confidence > confidenceThreshold
        val hasKnees = keypoints[KeypointIndex.LEFT_KNEE].confidence > confidenceThreshold ||
                keypoints[KeypointIndex.RIGHT_KNEE].confidence > confidenceThreshold
        val hasAnkles = keypoints[KeypointIndex.LEFT_ANKLE].confidence > confidenceThreshold ||
                keypoints[KeypointIndex.RIGHT_ANKLE].confidence > confidenceThreshold
        val hasFeet = keypoints[KeypointIndex.LEFT_FOOT_START].confidence > confidenceThreshold ||
                keypoints[KeypointIndex.RIGHT_FOOT_START].confidence > confidenceThreshold

        val faceSize = calculateFaceSize(keypoints)

        val shotType: ShotType
        var confidence = 0.8f

        shotType = when {
            !hasHead -> {
                confidence = 0.5f
                ShotType.EXTREME_CLOSE_UP
            }
            !hasShoulders -> {
                confidence = 0.85f
                if (faceSize > 0.35f) ShotType.EXTREME_CLOSE_UP else ShotType.CLOSE_UP
            }
            !hasHips -> {
                confidence = 0.85f
                if (faceSize > 0.25f) ShotType.CLOSE_UP else ShotType.MEDIUM_CLOSE_UP
            }
            !hasKnees -> {
                confidence = 0.9f
                ShotType.MEDIUM_SHOT
            }
            !hasAnkles -> {
                confidence = 0.9f
                ShotType.AMERICAN_SHOT
            }
            !hasFeet -> {
                confidence = 0.85f
                ShotType.MEDIUM_FULL_SHOT
            }
            else -> {
                val bodyHeight = calculateBodyHeight(keypoints)
                confidence = if (bodyHeight < 0.6f) 0.8f else 0.9f
                if (bodyHeight < 0.6f) ShotType.LONG_SHOT else ShotType.FULL_SHOT
            }
        }

        return Pair(shotType, confidence)
    }

    // MARK: - 헤드룸 계산

    private fun calculateHeadroom(keypoints: List<KeypointWithConfidence>, shotType: ShotType): Pair<Float, SpaceStatus> {
        var topY = 1.0f

        // 얼굴 키포인트에서 최상단 찾기
        for (i in KeypointIndex.FACE_START..KeypointIndex.FACE_END) {
            if (keypoints[i].confidence > confidenceThreshold) {
                topY = min(topY, keypoints[i].y)
            }
        }

        // 코로 보정
        if (keypoints[KeypointIndex.NOSE].confidence > confidenceThreshold) {
            val noseY = keypoints[KeypointIndex.NOSE].y
            val faceHeight = calculateFaceSize(keypoints)
            val estimatedTop = noseY - faceHeight * 0.7f
            topY = min(topY, max(0f, estimatedTop))
        }

        val headroom = topY
        val optimalRange = shotType.headroomRange

        val status = when {
            headroom < optimalRange.start * 0.5f -> SpaceStatus.TOO_LITTLE
            headroom < optimalRange.start -> SpaceStatus.TOO_LITTLE
            headroom > optimalRange.endInclusive * 1.5f -> SpaceStatus.TOO_MUCH
            headroom > optimalRange.endInclusive -> SpaceStatus.TOO_MUCH
            else -> SpaceStatus.OPTIMAL
        }

        return Pair(headroom, status)
    }

    // MARK: - 시선 방향 감지

    private fun detectGazeDirection(keypoints: List<KeypointWithConfidence>): FramingGazeDirection {
        val leftEye = keypoints[KeypointIndex.LEFT_EYE]
        val rightEye = keypoints[KeypointIndex.RIGHT_EYE]
        val nose = keypoints[KeypointIndex.NOSE]
        val leftEar = keypoints[KeypointIndex.LEFT_EAR]
        val rightEar = keypoints[KeypointIndex.RIGHT_EAR]

        if (leftEye.confidence <= confidenceThreshold ||
            rightEye.confidence <= confidenceThreshold ||
            nose.confidence <= confidenceThreshold
        ) {
            return FramingGazeDirection.CENTER
        }

        val eyeCenterX = (leftEye.x + rightEye.x) / 2
        val horizontalOffset = nose.x - eyeCenterX
        val eyeDistance = abs(rightEye.x - leftEye.x)
        val normalizedOffset = horizontalOffset / max(eyeDistance, 0.01f)

        val leftEarVisible = leftEar.confidence > confidenceThreshold
        val rightEarVisible = rightEar.confidence > confidenceThreshold

        return when {
            !leftEarVisible && rightEarVisible -> FramingGazeDirection.LEFT
            leftEarVisible && !rightEarVisible -> FramingGazeDirection.RIGHT
            normalizedOffset < -0.15f -> FramingGazeDirection.LEFT
            normalizedOffset > 0.15f -> FramingGazeDirection.RIGHT
            else -> FramingGazeDirection.CENTER
        }
    }

    // MARK: - 리드룸 계산

    private fun calculateLeadRoom(
        keypoints: List<KeypointWithConfidence>,
        gazeDirection: FramingGazeDirection
    ): Pair<Float?, SpaceStatus?> {
        if (gazeDirection != FramingGazeDirection.LEFT && gazeDirection != FramingGazeDirection.RIGHT) {
            return Pair(null, null)
        }

        val nose = keypoints[KeypointIndex.NOSE]
        if (nose.confidence <= confidenceThreshold) {
            return Pair(null, null)
        }

        val faceX = nose.x
        val leadRoom = if (gazeDirection == FramingGazeDirection.LEFT) faceX else (1.0f - faceX)

        val status = when {
            leadRoom < 0.10f -> SpaceStatus.TOO_LITTLE
            leadRoom < 0.15f -> SpaceStatus.TOO_LITTLE
            leadRoom > 0.45f -> SpaceStatus.TOO_MUCH
            leadRoom > 0.35f -> SpaceStatus.TOO_MUCH
            else -> SpaceStatus.OPTIMAL
        }

        return Pair(leadRoom, status)
    }

    // MARK: - 카메라 앵글 분석

    private fun analyzeCameraAngle(keypoints: List<KeypointWithConfidence>): Pair<PhotoCameraAngle, Float> {
        val leftShoulder = keypoints[KeypointIndex.LEFT_SHOULDER]
        val rightShoulder = keypoints[KeypointIndex.RIGHT_SHOULDER]
        val leftEye = keypoints[KeypointIndex.LEFT_EYE]
        val rightEye = keypoints[KeypointIndex.RIGHT_EYE]

        if ((leftShoulder.confidence <= confidenceThreshold && rightShoulder.confidence <= confidenceThreshold) ||
            (leftEye.confidence <= confidenceThreshold && rightEye.confidence <= confidenceThreshold)
        ) {
            return Pair(PhotoCameraAngle.EYE_LEVEL, 0f)
        }

        val shoulderY = when {
            leftShoulder.confidence > confidenceThreshold && rightShoulder.confidence > confidenceThreshold ->
                (leftShoulder.y + rightShoulder.y) / 2
            leftShoulder.confidence > confidenceThreshold -> leftShoulder.y
            else -> rightShoulder.y
        }

        val eyeY = when {
            leftEye.confidence > confidenceThreshold && rightEye.confidence > confidenceThreshold ->
                (leftEye.y + rightEye.y) / 2
            leftEye.confidence > confidenceThreshold -> leftEye.y
            else -> rightEye.y
        }

        val eyeShoulderDist = shoulderY - eyeY
        val faceSize = calculateFaceSize(keypoints)
        val normalizedDist = eyeShoulderDist / max(faceSize, 0.1f)
        val estimatedAngle = (normalizedDist - 1.0f) * 30

        val cameraAngle = when {
            normalizedDist < 0.5f -> PhotoCameraAngle.BIRDS_EYE
            normalizedDist < 0.8f -> PhotoCameraAngle.HIGH_ANGLE
            normalizedDist > 1.5f -> PhotoCameraAngle.LOW_ANGLE
            else -> PhotoCameraAngle.EYE_LEVEL
        }

        val shoulderTilt = calculateShoulderTilt(keypoints)
        if (abs(shoulderTilt) > 10) {
            return Pair(PhotoCameraAngle.DUTCH_ANGLE, shoulderTilt)
        }

        return Pair(cameraAngle, estimatedAngle)
    }

    // MARK: - 잘림 규칙 체크

    private fun checkCroppingViolations(keypoints: List<KeypointWithConfidence>): List<CroppingViolation> {
        val violations = mutableListOf<CroppingViolation>()

        val jointChecks = listOf(
            KeypointIndex.LEFT_ANKLE to "왼쪽 발목",
            KeypointIndex.RIGHT_ANKLE to "오른쪽 발목",
            KeypointIndex.LEFT_KNEE to "왼쪽 무릎",
            KeypointIndex.RIGHT_KNEE to "오른쪽 무릎",
            KeypointIndex.LEFT_HIP to "왼쪽 엉덩이",
            KeypointIndex.RIGHT_HIP to "오른쪽 엉덩이",
            KeypointIndex.LEFT_WRIST to "왼쪽 손목",
            KeypointIndex.RIGHT_WRIST to "오른쪽 손목",
            KeypointIndex.LEFT_ELBOW to "왼쪽 팔꿈치",
            KeypointIndex.RIGHT_ELBOW to "오른쪽 팔꿈치"
        )

        for ((index, name) in jointChecks) {
            val joint = keypoints[index]

            if (joint.confidence > confidenceThreshold) {
                val x = joint.x
                val y = joint.y

                // 하단 경계 근처
                if (y > 0.92f && y <= 1.0f) {
                    val severity = if (y > 0.98f) ViolationSeverity.CRITICAL else ViolationSeverity.WARNING
                    violations.add(CroppingViolation(name, y, severity))
                }

                // 좌우 경계 근처
                if (x < 0.05f || x > 0.95f) {
                    val severity = if (x < 0.02f || x > 0.98f) ViolationSeverity.CRITICAL else ViolationSeverity.WARNING
                    violations.add(CroppingViolation(name, x, severity))
                }
            }
        }

        return violations
    }

    // MARK: - 신체 점유율 계산

    private fun calculateBodyCoverage(keypoints: List<KeypointWithConfidence>, shotType: ShotType): Float {
        val importantIndices = StructuralKeypoints.importantKeypoints(shotType)

        val validPoints = importantIndices.mapNotNull { idx ->
            if (idx < keypoints.size && keypoints[idx].confidence > 0.3f) {
                PointF(keypoints[idx].x, keypoints[idx].y)
            } else null
        }

        if (validPoints.size < 3) return 0.5f

        val minX = validPoints.minOf { it.x }
        val maxX = validPoints.maxOf { it.x }
        val minY = validPoints.minOf { it.y }
        val maxY = validPoints.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY
        return width * height
    }

    // MARK: - 전체 점수 계산

    private fun calculateOverallScore(
        headroomStatus: SpaceStatus,
        leadRoomStatus: SpaceStatus?,
        violations: List<CroppingViolation>,
        shotConfidence: Float
    ): Float {
        var score = 1.0f

        when (headroomStatus) {
            SpaceStatus.OPTIMAL -> {}
            SpaceStatus.TOO_MUCH -> score -= 0.15f
            SpaceStatus.TOO_LITTLE -> score -= 0.25f
            SpaceStatus.NONE -> score -= 0.1f
        }

        leadRoomStatus?.let { status ->
            when (status) {
                SpaceStatus.OPTIMAL -> {}
                SpaceStatus.TOO_MUCH -> score -= 0.1f
                SpaceStatus.TOO_LITTLE -> score -= 0.2f
                SpaceStatus.NONE -> {}
            }
        }

        for (violation in violations) {
            when (violation.severity) {
                ViolationSeverity.CRITICAL -> score -= 0.2f
                ViolationSeverity.WARNING -> score -= 0.1f
                ViolationSeverity.MINOR -> score -= 0.05f
            }
        }

        score *= shotConfidence

        return max(0f, min(1f, score))
    }

    // MARK: - 헬퍼 함수들

    private fun calculateFaceSize(keypoints: List<KeypointWithConfidence>): Float {
        val leftEye = keypoints[KeypointIndex.LEFT_EYE]
        val rightEye = keypoints[KeypointIndex.RIGHT_EYE]

        if (leftEye.confidence <= confidenceThreshold || rightEye.confidence <= confidenceThreshold) {
            return 0.1f
        }

        val eyeDistance = abs(rightEye.x - leftEye.x)
        return eyeDistance * 3.0f
    }

    private fun calculateBodyHeight(keypoints: List<KeypointWithConfidence>): Float {
        var topY = 1.0f
        var bottomY = 0.0f

        for (kp in keypoints.take(133)) {
            if (kp.confidence > confidenceThreshold) {
                topY = min(topY, kp.y)
                bottomY = max(bottomY, kp.y)
            }
        }

        return bottomY - topY
    }

    private fun calculateShoulderTilt(keypoints: List<KeypointWithConfidence>): Float {
        val leftShoulder = keypoints[KeypointIndex.LEFT_SHOULDER]
        val rightShoulder = keypoints[KeypointIndex.RIGHT_SHOULDER]

        if (leftShoulder.confidence <= confidenceThreshold || rightShoulder.confidence <= confidenceThreshold) {
            return 0f
        }

        val dx = rightShoulder.x - leftShoulder.x
        val dy = rightShoulder.y - leftShoulder.y

        val angleRadians = atan2(dy, dx)
        return angleRadians * 180f / PI.toFloat()
    }
}

