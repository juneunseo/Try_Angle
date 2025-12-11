package com.example.camera2app.ai

import android.graphics.PointF
import kotlin.math.*

enum class PoseType(val description: String) {
    FULL_BODY("전신"),
    UPPER_BODY("상반신"),
    PORTRAIT("흉상"),
    UNKNOWN("알 수 없음")
}

enum class KeypointGroup(val keypointIndices: List<Int>, val displayName: String) {
    HEAD(listOf(0, 1, 2, 3, 4), "머리"),
    SHOULDERS(listOf(5, 6), "어깨"),
    ARMS(listOf(7, 8, 9, 10), "팔"),
    TORSO(listOf(11, 12), "몸통"),
    LEGS(listOf(13, 14, 15, 16), "다리"),
    FEET((17..22).toList(), "발"),
    FACE((23..90).toList(), "얼굴"),
    LEFT_HAND((91..111).toList(), "왼손"),
    RIGHT_HAND((112..132).toList(), "오른손")
}

data class PoseComparisonResult(
    val poseType: PoseType,
    val visibleGroups: List<KeypointGroup>,
    val missingGroups: List<KeypointGroup>,
    val comparableKeypoints: List<Int>,
    val angleDifferences: Map<String, Float>,
    val angleDirections: Map<String, String>,
    val overallAccuracy: Double
)

class AdaptivePoseComparator {

    private val confidenceThreshold = 0.5f
    private val handConfidenceThreshold = 0.3f
    private val faceConfidenceThreshold = 0.4f

    fun comparePoses(
        reference: List<PoseKeypoint>,
        current: List<PoseKeypoint>
    ): PoseComparisonResult {

        val visibleRef = filterVisibleKeypointsAdaptive(reference).toSet()
        val visibleCur = filterVisibleKeypointsAdaptive(current).toSet()
        val comparable = visibleRef.intersect(visibleCur)

        val poseType = detectPoseType(comparable.toList())
        val visibleGroups = classifyVisibleGroups(comparable.toList())
        val missingGroups = KeypointGroup.values().toSet().subtract(visibleGroups).toList()

        val diffs = mutableMapOf<String, Float>()
        val dirs = mutableMapOf<String, String>()

        if (canCompareLeftArm(comparable)) {
            diffs["left_arm"] = armDiff(reference, current, 5, 7, 9, "left_arm", dirs)
        }

        if (canCompareRightArm(comparable)) {
            diffs["right_arm"] = armDiff(reference, current, 6, 8, 10, "right_arm", dirs)
        }

        if (canCompareLeftLeg(comparable)) {
            diffs["left_leg"] = legDiff(reference, current, 11, 13, 15)
        }

        if (canCompareRightLeg(comparable)) {
            diffs["right_leg"] = legDiff(reference, current, 12, 14, 16)
        }

        val accuracy = calculateOverallAccuracy(diffs)

        return PoseComparisonResult(
            poseType,
            visibleGroups,
            missingGroups,
            comparable.sorted(),
            diffs,
            dirs,
            accuracy
        )
    }

    private fun filterVisibleKeypointsAdaptive(
        keypoints: List<PoseKeypoint>
    ): List<Int> {
        return keypoints.mapIndexedNotNull { index, kp ->
            val threshold = when {
                index >= 91 -> handConfidenceThreshold
                index in 23..90 -> faceConfidenceThreshold
                else -> confidenceThreshold
            }
            if (kp.confidence >= threshold) index else null
        }
    }

    private fun detectPoseType(indices: List<Int>): PoseType {
        val hasHead = indices.any { it in listOf(0, 1, 2, 3, 4) }
        val hasShoulder = indices.any { it == 5 || it == 6 }
        val hasTorso = indices.any { it == 11 || it == 12 }
        val hasLegs = indices.any { it in listOf(13, 14, 15, 16) }

        return when {
            hasHead && hasShoulder && hasTorso && hasLegs -> PoseType.FULL_BODY
            hasHead && hasShoulder && hasTorso -> PoseType.UPPER_BODY
            hasHead && hasShoulder -> PoseType.PORTRAIT
            else -> PoseType.UNKNOWN
        }
    }

    private fun classifyVisibleGroups(indices: List<Int>): List<KeypointGroup> {
        return KeypointGroup.values().filter { group ->
            val visible = group.keypointIndices.count { indices.contains(it) }
            val threshold = when (group) {
                KeypointGroup.FACE -> 0.3
                KeypointGroup.LEFT_HAND, KeypointGroup.RIGHT_HAND -> 0.5
                KeypointGroup.FEET -> 0.6
                else -> 0.5
            }
            visible.toDouble() / group.keypointIndices.size >= threshold
        }
    }

    private fun canCompareLeftArm(set: Set<Int>) = set.containsAll(listOf(5, 7, 9))
    private fun canCompareRightArm(set: Set<Int>) = set.containsAll(listOf(6, 8, 10))
    private fun canCompareLeftLeg(set: Set<Int>) = set.containsAll(listOf(11, 13, 15))
    private fun canCompareRightLeg(set: Set<Int>) = set.containsAll(listOf(12, 14, 16))

    private fun calculateOverallAccuracy(diffs: Map<String, Float>): Double {
        if (diffs.isEmpty()) return 1.0
        return diffs.values.map { max(0.0, 1.0 - it / 180.0) }.average()
    }

    private fun calculateAngle(p1: PointF, p2: PointF, p3: PointF): Float {
        val v1 = PointF(p1.x - p2.x, p1.y - p2.y)
        val v2 = PointF(p3.x - p2.x, p3.y - p2.y)
        val dot = v1.x * v2.x + v1.y * v2.y
        val mag1 = hypot(v1.x, v1.y)
        val mag2 = hypot(v2.x, v2.y)
        if (mag1 < 1e-4 || mag2 < 1e-4) return 0f
        val cos = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun normalizeVector(p1: PointF, p2: PointF): PointF {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val mag = hypot(dx, dy)
        return if (mag < 1e-4) PointF(0f, 0f) else PointF(dx / mag, dy / mag)
    }

    private fun cosineSimilarity(v1: PointF, v2: PointF): Double {
        val dot = v1.x * v2.x + v1.y * v2.y
        val mag1 = hypot(v1.x, v1.y)
        val mag2 = hypot(v2.x, v2.y)
        if (mag1 < 1e-4 || mag2 < 1e-4) return 0.0
        return ((dot / (mag1 * mag2)) + 1.0) / 2.0
    }

    private fun armDiff(
        ref: List<PoseKeypoint>,   // ✅ ref 다시 복구
        cur: List<PoseKeypoint>,
        s: Int, e: Int, w: Int,
        key: String,
        dirs: MutableMap<String, String>
    ): Float {

        val refAngle = calculateAngle(
            p1 = ref[s].point,
            p2 = ref[e].point,
            p3 = ref[w].point
        )

        val curAngle = calculateAngle(
            p1 = cur[s].point,
            p2 = cur[e].point,
            p3 = cur[w].point
        )

        val v1 = normalizeVector(
            p1 = ref[s].point,
            p2 = ref[e].point
        )

        val v2 = normalizeVector(
            p1 = cur[s].point,
            p2 = cur[e].point
        )

        val similarity = cosineSimilarity(v1, v2)
        val penalty = (1.0 - similarity) * 30.0
        val diff = abs(refAngle - curAngle) + penalty.toFloat()

        val yDiff = cur[w].point.y - ref[w].point.y
        val labelText = key.replace("_", " ")

        dirs[key] = if (abs(yDiff) > 0.05f) {
            if (yDiff > 0)
                "${labelText}을 위로 올리세요"
            else
                "${labelText}을 아래로 내리세요"
        } else {
            "${labelText} 각도를 조정하세요"
        }

        return diff
    }


    private fun legDiff(
        ref: List<PoseKeypoint>,
        cur: List<PoseKeypoint>,
        h: Int, k: Int, a: Int
    ): Float {
        val refAngle = calculateAngle(ref[h].point, ref[k].point, ref[a].point)
        val curAngle = calculateAngle(cur[h].point, cur[k].point, cur[a].point)
        return abs(refAngle - curAngle)
    }
}
