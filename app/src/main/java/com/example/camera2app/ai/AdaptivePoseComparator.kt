package com.example.camera2app.ai

import android.graphics.PointF
import kotlin.math.*

// MARK: - 포즈 타입

enum class PoseType(val description: String) {
    FULL_BODY("전신"),
    UPPER_BODY("상반신"),
    PORTRAIT("흉상"),
    UNKNOWN("알 수 없음")
}

// MARK: - 키포인트 그룹

enum class KeypointGroup(val displayName: String) {
    HEAD("머리"),
    SHOULDERS("어깨"),
    ARMS("팔"),
    TORSO("몸통"),
    LEGS("다리"),
    FEET("발"),
    LEFT_HAND("왼손"),
    RIGHT_HAND("오른손");

    val keypointIndices: List<Int>
        get() = when (this) {
            HEAD -> listOf(0, 1, 2, 3, 4)
            SHOULDERS -> listOf(5, 6)
            ARMS -> listOf(7, 8, 9, 10)
            TORSO -> listOf(11, 12)
            LEGS -> listOf(13, 14, 15, 16)
            FEET -> listOf(15, 16)  // 발목
            LEFT_HAND -> (91..111).toList()  // RTMPose 왼손 (91-111)
            RIGHT_HAND -> (112..132).toList()  // RTMPose 오른손 (112-132)
        }
}

// MARK: - 포즈 비교 결과

data class PoseComparisonResult(
    val poseType: PoseType,
    val visibleGroups: List<KeypointGroup>,
    val missingGroups: List<KeypointGroup>,
    val comparableKeypoints: List<Int>,
    val angleDifferences: Map<String, Float>,
    val angleDirections: Map<String, String>,  // 🆕 구체적인 방향 메시지
    val overallAccuracy: Double
)

// MARK: - 적응형 포즈 비교기

class AdaptivePoseComparator {

    private val confidenceThreshold = 0.5f

    /**
     * 레퍼런스와 현재 포즈 적응형 비교
     */
    fun comparePoses(
        referenceKeypoints: List<KeypointWithConfidence>,
        currentKeypoints: List<KeypointWithConfidence>
    ): PoseComparisonResult {

        // 1. 보이는 키포인트 필터링 (Body 17개만)
        val visibleRefIndices = filterVisibleKeypoints(referenceKeypoints.take(17))
        val visibleCurIndices = filterVisibleKeypoints(currentKeypoints.take(17))

        // 2. 공통으로 보이는 키포인트만 추출
        val comparableIndices = visibleRefIndices.intersect(visibleCurIndices.toSet())

        // 3. 포즈 타입 자동 감지
        val currentPoseType = detectPoseType(comparableIndices.toList())

        // 4. 보이는/안 보이는 그룹 분류
        val visibleGroups = classifyVisibleGroups(comparableIndices.toList())
        val allGroups = setOf(
            KeypointGroup.HEAD,
            KeypointGroup.SHOULDERS,
            KeypointGroup.ARMS,
            KeypointGroup.TORSO,
            KeypointGroup.LEGS
        )
        val missingGroups = (allGroups - visibleGroups.toSet()).toList()

        // 5. 각 부위별 각도 차이 계산
        val angleDifferences = mutableMapOf<String, Float>()
        val angleDirections = mutableMapOf<String, String>()

        // 왼팔 각도
        if (canCompareLeftArm(comparableIndices)) {
            val refAngle = calculateArmAngle(
                shoulder = PointF(referenceKeypoints[5].x, referenceKeypoints[5].y),
                elbow = PointF(referenceKeypoints[7].x, referenceKeypoints[7].y),
                wrist = PointF(referenceKeypoints[9].x, referenceKeypoints[9].y)
            )
            val curAngle = calculateArmAngle(
                shoulder = PointF(currentKeypoints[5].x, currentKeypoints[5].y),
                elbow = PointF(currentKeypoints[7].x, currentKeypoints[7].y),
                wrist = PointF(currentKeypoints[9].x, currentKeypoints[9].y)
            )
            val diff = curAngle - refAngle
            angleDifferences["left_arm"] = abs(diff)
            angleDirections["left_arm"] = if (diff > 0) "왼팔을 더 내려주세요" else "왼팔을 더 올려주세요"
        }

        // 오른팔 각도
        if (canCompareRightArm(comparableIndices)) {
            val refAngle = calculateArmAngle(
                shoulder = PointF(referenceKeypoints[6].x, referenceKeypoints[6].y),
                elbow = PointF(referenceKeypoints[8].x, referenceKeypoints[8].y),
                wrist = PointF(referenceKeypoints[10].x, referenceKeypoints[10].y)
            )
            val curAngle = calculateArmAngle(
                shoulder = PointF(currentKeypoints[6].x, currentKeypoints[6].y),
                elbow = PointF(currentKeypoints[8].x, currentKeypoints[8].y),
                wrist = PointF(currentKeypoints[10].x, currentKeypoints[10].y)
            )
            val diff = curAngle - refAngle
            angleDifferences["right_arm"] = abs(diff)
            angleDirections["right_arm"] = if (diff > 0) "오른팔을 더 내려주세요" else "오른팔을 더 올려주세요"
        }

        // 왼다리 각도
        if (canCompareLeftLeg(comparableIndices)) {
            val refAngle = calculateLegAngle(
                hip = PointF(referenceKeypoints[11].x, referenceKeypoints[11].y),
                knee = PointF(referenceKeypoints[13].x, referenceKeypoints[13].y),
                ankle = PointF(referenceKeypoints[15].x, referenceKeypoints[15].y)
            )
            val curAngle = calculateLegAngle(
                hip = PointF(currentKeypoints[11].x, currentKeypoints[11].y),
                knee = PointF(currentKeypoints[13].x, currentKeypoints[13].y),
                ankle = PointF(currentKeypoints[15].x, currentKeypoints[15].y)
            )
            val diff = curAngle - refAngle
            angleDifferences["left_leg"] = abs(diff)
            angleDirections["left_leg"] = if (diff > 0) "왼다리를 더 펴주세요" else "왼다리를 더 굽혀주세요"
        }

        // 오른다리 각도
        if (canCompareRightLeg(comparableIndices)) {
            val refAngle = calculateLegAngle(
                hip = PointF(referenceKeypoints[12].x, referenceKeypoints[12].y),
                knee = PointF(referenceKeypoints[14].x, referenceKeypoints[14].y),
                ankle = PointF(referenceKeypoints[16].x, referenceKeypoints[16].y)
            )
            val curAngle = calculateLegAngle(
                hip = PointF(currentKeypoints[12].x, currentKeypoints[12].y),
                knee = PointF(currentKeypoints[14].x, currentKeypoints[14].y),
                ankle = PointF(currentKeypoints[16].x, currentKeypoints[16].y)
            )
            val diff = curAngle - refAngle
            angleDifferences["right_leg"] = abs(diff)
            angleDirections["right_leg"] = if (diff > 0) "오른다리를 더 펴주세요" else "오른다리를 더 굽혀주세요"
        }

        // 6. 전체 정확도 계산
        val accuracy = calculateOverallAccuracy(angleDifferences)

        return PoseComparisonResult(
            poseType = currentPoseType,
            visibleGroups = visibleGroups,
            missingGroups = missingGroups,
            comparableKeypoints = comparableIndices.sorted(),
            angleDifferences = angleDifferences,
            angleDirections = angleDirections,
            overallAccuracy = accuracy
        )
    }

    /**
     * 잘린 그룹 감지
     */
    fun detectCroppedGroups(
        referenceKeypoints: List<KeypointWithConfidence>,
        currentKeypoints: List<KeypointWithConfidence>,
        shotType: ShotType
    ): List<KeypointGroup> {
        val croppedGroups = mutableListOf<KeypointGroup>()

        // 샷 타입별 중요 그룹 정의
        val importantGroups = when (shotType) {
            ShotType.FULL_SHOT, ShotType.LONG_SHOT ->
                listOf(KeypointGroup.HEAD, KeypointGroup.ARMS, KeypointGroup.LEGS, KeypointGroup.FEET)
            ShotType.AMERICAN_SHOT, ShotType.MEDIUM_FULL_SHOT ->
                listOf(KeypointGroup.HEAD, KeypointGroup.ARMS, KeypointGroup.LEGS)
            ShotType.MEDIUM_SHOT ->
                listOf(KeypointGroup.HEAD, KeypointGroup.ARMS)
            else ->
                listOf(KeypointGroup.HEAD)
        }

        // 레퍼런스에는 있지만 현재에는 없는 그룹 찾기
        for (group in importantGroups) {
            val refVisible = isGroupVisible(referenceKeypoints, group)
            val curVisible = isGroupVisible(currentKeypoints, group)

            if (refVisible && !curVisible) {
                croppedGroups.add(group)
            }
        }

        return croppedGroups
    }

    // MARK: - Private Helpers

    private fun filterVisibleKeypoints(keypoints: List<KeypointWithConfidence>): List<Int> {
        return keypoints.mapIndexedNotNull { index, kp ->
            if (kp.confidence >= confidenceThreshold) index else null
        }
    }

    private fun detectPoseType(visibleIndices: List<Int>): PoseType {
        val hasHead = visibleIndices.any { it in listOf(0, 1, 2, 3, 4) }
        val hasShoulders = visibleIndices.contains(5) || visibleIndices.contains(6)
        val hasTorso = visibleIndices.contains(11) || visibleIndices.contains(12)
        val hasLegs = visibleIndices.any { it in listOf(13, 14, 15, 16) }

        return when {
            hasHead && hasShoulders && hasTorso && hasLegs -> PoseType.FULL_BODY
            hasHead && hasShoulders && hasTorso -> PoseType.UPPER_BODY
            hasHead && hasShoulders -> PoseType.PORTRAIT
            else -> PoseType.UNKNOWN
        }
    }

    private fun classifyVisibleGroups(visibleIndices: List<Int>): List<KeypointGroup> {
        val groups = mutableListOf<KeypointGroup>()

        for (group in listOf(
            KeypointGroup.HEAD,
            KeypointGroup.SHOULDERS,
            KeypointGroup.ARMS,
            KeypointGroup.TORSO,
            KeypointGroup.LEGS
        )) {
            val groupIndices = group.keypointIndices
            val visibleCount = groupIndices.count { it in visibleIndices }

            if (visibleCount.toDouble() / groupIndices.size >= 0.5) {
                groups.add(group)
            }
        }

        return groups
    }

    private fun isGroupVisible(keypoints: List<KeypointWithConfidence>, group: KeypointGroup): Boolean {
        val indices = group.keypointIndices
        val visibleCount = indices.count { idx ->
            idx < keypoints.size && keypoints[idx].confidence >= confidenceThreshold
        }
        return visibleCount.toDouble() / indices.size >= 0.5
    }

    private fun canCompareLeftArm(indices: Set<Int>) = indices.containsAll(listOf(5, 7, 9))
    private fun canCompareRightArm(indices: Set<Int>) = indices.containsAll(listOf(6, 8, 10))
    private fun canCompareLeftLeg(indices: Set<Int>) = indices.containsAll(listOf(11, 13, 15))
    private fun canCompareRightLeg(indices: Set<Int>) = indices.containsAll(listOf(12, 14, 16))

    private fun calculateArmAngle(shoulder: PointF, elbow: PointF, wrist: PointF): Float {
        return calculateAngle(p1 = shoulder, p2 = elbow, p3 = wrist)
    }

    private fun calculateLegAngle(hip: PointF, knee: PointF, ankle: PointF): Float {
        return calculateAngle(p1 = hip, p2 = knee, p3 = ankle)
    }

    private fun calculateAngle(p1: PointF, p2: PointF, p3: PointF): Float {
        val v1 = PointF(p1.x - p2.x, p1.y - p2.y)
        val v2 = PointF(p3.x - p2.x, p3.y - p2.y)

        val dot = v1.x * v2.x + v1.y * v2.y
        val mag1 = sqrt(v1.x * v1.x + v1.y * v1.y)
        val mag2 = sqrt(v2.x * v2.x + v2.y * v2.y)

        if (mag1 == 0f || mag2 == 0f) return 0f

        val cosAngle = dot / (mag1 * mag2)
        val angleRad = acos(cosAngle.coerceIn(-1f, 1f))
        return angleRad * 180f / PI.toFloat()
    }

    private fun calculateOverallAccuracy(angleDifferences: Map<String, Float>): Double {
        if (angleDifferences.isEmpty()) return 1.0

        val maxDiff = 180.0
        var totalAccuracy = 0.0

        for ((_, diff) in angleDifferences) {
            val accuracy = max(0.0, 1.0 - (diff / maxDiff))
            totalAccuracy += accuracy
        }

        return totalAccuracy / angleDifferences.size
    }
}

// MARK: - 피드백 데이터 클래스

data class PoseFeedback(
    val message: String,
    val category: String
)