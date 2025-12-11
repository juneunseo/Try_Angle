package com.example.camera2app.ai

import kotlin.math.abs
import kotlin.math.max

class GapAnalyzer {

    fun analyzeGaps(
        reference: FrameAnalysis,
        current: CurrentAnalysis
    ): List<Gap> {

        val gaps = mutableListOf<Gap>()

        val refFace = reference.faceRect
        val curFace = current.face?.faceRect

        // ✅ 1. 위치 X
        if (refFace != null && curFace != null) {
            val xDiff = abs(curFace.centerX() - refFace.centerX())
            if (xDiff > 0.08f) {
                gaps.add(
                    Gap(
                        type = GapType.positionX,
                        current = curFace.centerX().toDouble() * 100,
                        target = refFace.centerX().toDouble() * 100,
                        difference = (xDiff * 100).toDouble(),
                        tolerance = 8.0,
                        priority = 2
                    )
                )
            }
        }

        // ✅ 2. 위치 Y
        if (refFace != null && curFace != null) {
            val yDiff = abs(curFace.centerY() - refFace.centerY())
            if (yDiff > 0.08f) {
                gaps.add(
                    Gap(
                        type = GapType.positionY,
                        current = curFace.centerY().toDouble() * 100,
                        target = refFace.centerY().toDouble() * 100,
                        difference = (yDiff * 100).toDouble(),
                        tolerance = 8.0,
                        priority = 2
                    )
                )
            }
        }

        // ✅ 3. 거리 (Float depth 기반)
        val refDepth = reference.depth
        val curDepth = current.depth?.distance

        if (refDepth != null && curDepth != null) {
            val diff = abs(curDepth - refDepth)
            if (diff > 0.3f) {
                gaps.add(
                    Gap(
                        type = GapType.distance,
                        current = curDepth.toDouble(),
                        target = refDepth.toDouble(),
                        difference = diff.toDouble(),
                        tolerance = 0.3,
                        priority = 3
                    )
                )
            }
        }

        // ✅ 4. 구도
        if (
            reference.compositionType != null &&
            current.face?.faceRect != null // 현재는 composition 따로 없어서 face 기준만 유지
        ) {
            // 비교 대상이 없으므로 PASS (추후 확장용)
        }

        // ✅ 5. 시선
        if (reference.gaze != null && current.gaze != null) {
            if (reference.gaze != current.gaze) {
                gaps.add(
                    Gap(
                        type = GapType.gaze,
                        current = null,
                        target = null,
                        difference = 1.0,
                        tolerance = 0.0,
                        priority = 6
                    )
                )
            }
        }

        return gaps
    }

    // ✅ 완성도 점수 계산
    fun calculateCompletionScore(gaps: List<Gap>): Double {
        if (gaps.isEmpty()) return 1.0

        var totalScore = 0.0
        var count = 0

        for (gap in gaps) {
            val itemScore =
                if (gap.isWithinTolerance) {
                    1.0
                } else {
                    val excessDiff = gap.difference - gap.tolerance
                    if (gap.current != null && gap.target != null) {
                        val maxDiff = max(abs(gap.target) + gap.tolerance + 50, 100.0)
                        max(0.0, 1.0 - (excessDiff / maxDiff))
                    } else {
                        0.0
                    }
                }

            totalScore += itemScore
            count++
        }

        return (totalScore / count).coerceIn(0.0, 1.0)
    }

    fun sortByPriority(gaps: List<Gap>): List<Gap> {
        return gaps.sortedBy { it.priority }
    }
}
