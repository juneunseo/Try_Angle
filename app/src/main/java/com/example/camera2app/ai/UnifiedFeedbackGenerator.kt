package com.example.camera2app.ai

import android.graphics.RectF
import android.util.SizeF
import com.example.camera2app.ai.models.*
import kotlin.math.abs

/**
 * Unified Feedback Generator - Smart single-action feedback system
 * Ported from iOS TryAngle v1.5 UnifiedFeedbackGenerator.swift (1108 lines)
 *
 * USAGE:
 * Copy this file to: camera2app_new/app/src/main/java/com/example/camera2app/ai/
 *
 * Core Logic:
 * - "One action → Solve multiple gates simultaneously"
 * - Gate 0 failed → Aspect ratio change (absolute priority)
 * - Compression OK → Use Case A: distance/position only (no zoom)
 * - Compression NG → Use Case B: zoom + distance compound actions
 * - Stabilization: debouncing, consecutive frame check, stuck prevention
 *
 * Decision Tree:
 * 1. All passed → return null
 * 2. Gate 0 failed → return aspect ratio warning
 * 3. Gate 3 passed (compression OK) → generateDistanceOnlyFeedback()
 * 4. Gate 3 failed (compression NG) → generateZoomAndDistanceFeedback()
 */
class UnifiedFeedbackGenerator private constructor() {

    companion object {
        @Volatile
        private var instance: UnifiedFeedbackGenerator? = null

        fun getInstance(): UnifiedFeedbackGenerator {
            return instance ?: synchronized(this) {
                instance ?: UnifiedFeedbackGenerator().also { instance = it }
            }
        }

        // Stabilization settings
        const val MIN_FEEDBACK_INTERVAL_MS = 300L  // 0.3 seconds
        const val STABILITY_THRESHOLD = 3          // 3 consecutive frames
        const val MAX_SAME_ACTION_COUNT = 30       // Force reset after 30 frames (stuck prevention)
    }

    // Stabilization state
    private var lastFeedback: UnifiedFeedback? = null
    private var lastFeedbackTime: Long = 0L
    private var sameActionCount: Int = 0
    private var consecutiveSameAction: Int = 0
    private var lastCameraIsFront: Boolean = false

    /**
     * Reset feedback cache (call when camera switches)
     */
    fun resetCache() {
        lastFeedback = null
        lastFeedbackTime = 0L
        sameActionCount = 0
        consecutiveSameAction = 0
        println("🔄 [UnifiedFeedback] Cache reset")
    }

    // MARK: - Main Feedback Generation

    /**
     * Generate unified feedback from GateEvaluation
     * @param evaluation Gate evaluation result
     * @param isFrontCamera Front camera flag
     * @param currentZoom Current zoom factor (1.0 = 24mm)
     * @param targetZoom Target zoom factor (from reference)
     * @param currentSubjectSize Current person coverage (0.0 ~ 1.0)
     * @param targetSubjectSize Target person coverage
     * @return Unified feedback or null if all passed
     */
    fun generateUnifiedFeedback(
        evaluation: GateEvaluation,
        isFrontCamera: Boolean = false,
        currentZoom: Float = 1.0f,
        targetZoom: Float? = null,
        currentSubjectSize: Float? = null,
        targetSubjectSize: Float? = null
    ): UnifiedFeedback? {

        // Camera switch detection → reset cache
        if (isFrontCamera != lastCameraIsFront) {
            println("📷 [UnifiedFeedback] Camera switched: ${if (lastCameraIsFront) "front" else "back"} → ${if (isFrontCamera) "front" else "back"}")
            resetCache()
            lastCameraIsFront = isFrontCamera
        }

        // 1. All gates passed → return null + reset state
        if (evaluation.allPassed) {
            lastFeedback = null
            sameActionCount = 0
            consecutiveSameAction = 0
            return null
        }

        // ============================================
        // 🔒 Gate 0 (Aspect Ratio) - Absolute Priority!
        // ============================================
        if (!evaluation.gate0.passed) {
            val aspectFeedback = UnifiedFeedback(
                primaryAction = AdjustmentAction.ZOOM_OUT,  // Placeholder (aspect change is not an action)
                magnitude = "",
                affectedGates = listOf(0),
                expectedResults = emptyList(),
                priority = 0
            )
            return stabilizeFeedback(aspectFeedback)
        }

        // ============================================
        // 🆕 Smart Feedback Logic based on Compression
        // ============================================

        val compressionOK = evaluation.gate3.passed

        // 🔑 Key decision: feedback strategy based on compression state
        return if (compressionOK) {
            // ============================================
            // Case A: Compression OK → Distance/Position only (no zoom!)
            // ============================================
            generateDistanceOnlyFeedback(evaluation, isFrontCamera)
        } else {
            // ============================================
            // Case B: Compression NG → Zoom + Distance compound actions
            // ============================================
            generateZoomAndDistanceFeedback(
                evaluation,
                isFrontCamera,
                currentZoom,
                targetZoom ?: currentZoom,
                currentSubjectSize,
                targetSubjectSize
            )
        }
    }

    // MARK: - 🆕 Case A: Compression OK - Distance Only

    private fun generateDistanceOnlyFeedback(
        evaluation: GateEvaluation,
        isFrontCamera: Boolean
    ): UnifiedFeedback? {

        // Analyze Gate 1, 2 only (Gate 3 compression is OK, so exclude it)
        var problems = analyzeProblems(evaluation).filter { it.gate in 1..2 }

        // 🆕 Gate 1 priority rule (Sequential Feedback)
        // If shot type (Gate 1) is wrong, ignore position (Gate 2) feedback
        // Reason: Position will change anyway while adjusting distance
        if (problems.any { it.gate == 1 }) {
            problems = problems.filter { it.gate == 1 }
        }

        if (problems.isEmpty()) {
            lastFeedback = null
            sameActionCount = 0
            return null
        }

        // 🔑 Key: Calculate distance-only actions (exclude zoom)
        val possibleActions = calculateDistanceOnlyActions(problems)

        // Select best action
        val bestAction = selectBestAction(
            possibleActions,
            problems,
            evaluation.gate1.score
        ) ?: return stabilizeFeedback(createFallbackFeedback(evaluation, isFrontCamera))

        // Create feedback
        val newFeedback = createUnifiedFeedback(
            bestAction,
            problems,
            isFrontCamera
        )

        return stabilizeFeedback(newFeedback)
    }

    // MARK: - 🆕 Case B: Compression NG - Zoom + Distance

    private fun generateZoomAndDistanceFeedback(
        evaluation: GateEvaluation,
        isFrontCamera: Boolean,
        currentZoom: Float,
        targetZoom: Float,
        currentSubjectSize: Float?,
        targetSubjectSize: Float?
    ): UnifiedFeedback? {

        val zoomRatio = targetZoom / currentZoom
        val needZoomIn = zoomRatio > 1.1f   // 10% difference
        val needZoomOut = zoomRatio < 0.9f

        // No zoom change needed → distance only
        if (!needZoomIn && !needZoomOut) {
            return generateDistanceOnlyFeedback(evaluation, isFrontCamera)
        }

        // Calculate predicted person size after zoom
        val curSize = currentSubjectSize ?: 0.5f
        val tgtSize = targetSubjectSize ?: 0.4f
        val predictedSizeAfterZoom = curSize * zoomRatio

        // Decide zoom + distance action
        val action: AdjustmentAction
        val magnitude: String
        val expectedResults: MutableList<String> = mutableListOf()

        action = if (needZoomIn) {
            // Need zoom in
            when {
                predictedSizeAfterZoom > tgtSize * 1.15f -> {
                    // After zoom in, too large → need to move back
                    val sizeRatio = predictedSizeAfterZoom / tgtSize
                    magnitude = calculateDistanceMagnitude(sizeRatio)
                    expectedResults.add("압축감이 맞춰집니다")
                    expectedResults.add("인물 크기가 조정됩니다")
                    AdjustmentAction.ZOOM_IN_THEN_MOVE_BACK
                }
                predictedSizeAfterZoom < tgtSize * 0.85f -> {
                    // After zoom in, too small → need to move forward
                    val sizeRatio = tgtSize / predictedSizeAfterZoom
                    magnitude = calculateDistanceMagnitude(sizeRatio)
                    expectedResults.add("압축감이 맞춰집니다")
                    expectedResults.add("인물 크기가 조정됩니다")
                    AdjustmentAction.ZOOM_IN_THEN_MOVE_FORWARD
                }
                else -> {
                    // Zoom in only is OK
                    magnitude = ""
                    expectedResults.add("압축감이 맞춰집니다")
                    expectedResults.add("인물 크기도 맞아집니다")
                    AdjustmentAction.ZOOM_IN
                }
            }
        } else {
            // Need zoom out
            when {
                predictedSizeAfterZoom < tgtSize * 0.85f -> {
                    // After zoom out, too small → need to move forward
                    val sizeRatio = tgtSize / predictedSizeAfterZoom
                    magnitude = calculateDistanceMagnitude(sizeRatio)
                    expectedResults.add("압축감이 맞춰집니다")
                    expectedResults.add("인물 크기가 조정됩니다")
                    AdjustmentAction.ZOOM_OUT_THEN_MOVE_FORWARD
                }
                predictedSizeAfterZoom > tgtSize * 1.15f -> {
                    // After zoom out, too large → need to move back
                    val sizeRatio = predictedSizeAfterZoom / tgtSize
                    magnitude = calculateDistanceMagnitude(sizeRatio)
                    expectedResults.add("압축감이 맞춰집니다")
                    expectedResults.add("인물 크기가 조정됩니다")
                    AdjustmentAction.ZOOM_OUT_THEN_MOVE_BACK
                }
                else -> {
                    // Zoom out only is OK
                    magnitude = ""
                    expectedResults.add("압축감이 맞춰집니다")
                    expectedResults.add("인물 크기도 맞아집니다")
                    AdjustmentAction.ZOOM_OUT
                }
            }
        }

        val feedback = UnifiedFeedback(
            primaryAction = action,
            magnitude = magnitude,
            affectedGates = listOf(1, 2, 3),
            expectedResults = expectedResults,
            priority = 3,  // Compression priority
            targetZoom = targetZoom,
            zoomFirst = true
        )

        return stabilizeFeedback(feedback)
    }

    // 🆕 Calculate distance magnitude from size ratio
    private fun calculateDistanceMagnitude(sizeRatio: Float): String {
        return when {
            sizeRatio < 1.2f -> "반 걸음"
            sizeRatio < 1.5f -> "한 걸음"
            sizeRatio < 2.0f -> "두 걸음"
            else -> "세 걸음"
        }
    }

    // 🆕 Calculate distance-only actions (exclude zoom)
    private fun calculateDistanceOnlyActions(problems: List<GateProblem>): List<AdjustmentAction> {
        val actions = mutableSetOf<AdjustmentAction>()

        val problemTypes = problems.map { it.type }.toSet()

        // Smart correlation analysis
        val hasShotTypeWide = problemTypes.contains(GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE) ||
                problemTypes.contains(GateProblem.ProblemType.COVERAGE_TOO_LOW)
        val hasShotTypeNarrow = problemTypes.contains(GateProblem.ProblemType.SHOT_TYPE_TOO_NARROW) ||
                problemTypes.contains(GateProblem.ProblemType.COVERAGE_TOO_HIGH)
        val hasTopMarginHigh = problemTypes.contains(GateProblem.ProblemType.MARGIN_TOP_HIGH)
        val hasBottomMarginHigh = problemTypes.contains(GateProblem.ProblemType.MARGIN_BOTTOM_HIGH)
        val hasTopMarginLow = problemTypes.contains(GateProblem.ProblemType.MARGIN_TOP_LOW)
        val hasBottomMarginLow = problemTypes.contains(GateProblem.ProblemType.MARGIN_BOTTOM_LOW)

        // Smart inference
        if (hasShotTypeWide && hasTopMarginHigh) {
            actions.add(AdjustmentAction.TILT_DOWN)
        }
        if (hasShotTypeWide && hasBottomMarginHigh) {
            actions.add(AdjustmentAction.TILT_UP)
        }
        if (hasShotTypeNarrow && (hasTopMarginLow || hasBottomMarginLow)) {
            actions.add(AdjustmentAction.MOVE_BACKWARD)
        }

        for (problem in problems) {
            when (problem.type) {
                // 🔑 Key: Shot type problems solved by distance only (exclude zoom!)
                GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE,
                GateProblem.ProblemType.COVERAGE_TOO_LOW -> {
                    actions.add(AdjustmentAction.MOVE_FORWARD)
                    actions.add(AdjustmentAction.TILT_DOWN)
                    actions.add(AdjustmentAction.TILT_UP)
                    // ❌ Exclude zoomIn!
                }
                GateProblem.ProblemType.SHOT_TYPE_TOO_NARROW,
                GateProblem.ProblemType.COVERAGE_TOO_HIGH -> {
                    actions.add(AdjustmentAction.MOVE_BACKWARD)
                    // ❌ Exclude zoomOut!
                }

                // Horizontal margin
                GateProblem.ProblemType.MARGIN_LEFT_HIGH -> actions.add(AdjustmentAction.MOVE_RIGHT)
                GateProblem.ProblemType.MARGIN_RIGHT_HIGH -> actions.add(AdjustmentAction.MOVE_LEFT)

                // Vertical margin
                GateProblem.ProblemType.MARGIN_TOP_HIGH -> actions.add(AdjustmentAction.TILT_DOWN)
                GateProblem.ProblemType.MARGIN_BOTTOM_HIGH -> actions.add(AdjustmentAction.TILT_UP)
                GateProblem.ProblemType.MARGIN_TOP_LOW -> {
                    actions.add(AdjustmentAction.TILT_UP)
                    actions.add(AdjustmentAction.MOVE_BACKWARD)
                }
                GateProblem.ProblemType.MARGIN_BOTTOM_LOW -> {
                    actions.add(AdjustmentAction.TILT_DOWN)
                    actions.add(AdjustmentAction.MOVE_BACKWARD)
                }

                // Compression problems handled in Case B
                GateProblem.ProblemType.COMPRESSION_TOO_LOW,
                GateProblem.ProblemType.COMPRESSION_TOO_HIGH -> {
                    // Skip
                }

                // Pose
                GateProblem.ProblemType.POSE_ANGLE_DIFF -> {
                    // Skip (can't solve with movement)
                }
                else -> {}
            }
        }

        return actions.toList()
    }

    // MARK: - 🆕 Feedback Stabilization (prevent flickering)

    private fun stabilizeFeedback(newFeedback: UnifiedFeedback?): UnifiedFeedback? {
        if (newFeedback == null) {
            lastFeedback = null
            sameActionCount = 0
            consecutiveSameAction = 0
            return null
        }

        val now = System.currentTimeMillis()

        // Check if same as previous feedback
        lastFeedback?.let { last ->
            val isSameAction = (last.primaryAction == newFeedback.primaryAction)

            if (isSameAction) {
                // Same feedback
                sameActionCount++
                consecutiveSameAction++

                // 🆕 Force reset after too many same feedback (stuck prevention)
                if (consecutiveSameAction >= MAX_SAME_ACTION_COUNT) {
                    println("⚠️ [UnifiedFeedback] Force reset after $consecutiveSameAction same actions")
                    consecutiveSameAction = 0
                    // Allow new feedback update (continue below)
                } else {
                    // Update if magnitude changed
                    if (last.magnitude != newFeedback.magnitude) {
                        lastFeedback = newFeedback
                        lastFeedbackTime = now
                        return newFeedback
                    }
                    return last  // Keep same feedback
                }
            } else {
                // 🔧 Different feedback detected - reflect immediately!
                println("🔄 [UnifiedFeedback] Action changed: ${last.primaryAction.rawValue} → ${newFeedback.primaryAction.rawValue}")
                consecutiveSameAction = 0
                sameActionCount = 1
            }
        }

        // Replace with new feedback
        lastFeedback = newFeedback
        lastFeedbackTime = now
        sameActionCount = 1
        return newFeedback
    }

    // MARK: - Problem Analysis

    private fun analyzeProblems(evaluation: GateEvaluation): List<GateProblem> {
        val problems = mutableListOf<GateProblem>()

        // Gate 1: Framing analysis
        if (!evaluation.gate1.passed) {
            problems.addAll(analyzeFramingProblems(evaluation.gate1))
        }

        // Gate 2: Position/Margin analysis
        if (!evaluation.gate2.passed) {
            problems.addAll(analyzePositionProblems(evaluation.gate2))
        }

        // Gate 3: Compression analysis
        if (!evaluation.gate3.passed) {
            problems.addAll(analyzeCompressionProblems(evaluation.gate3))
        }

        // Gate 4: Pose analysis (reference only)
        if (!evaluation.gate4.passed) {
            problems.addAll(analyzePoseProblems(evaluation.gate4))
        }

        return problems
    }

    private fun analyzeFramingProblems(gate: GateResult): List<GateProblem> {
        val problems = mutableListOf<GateProblem>()
        val severity = 1.0f - gate.score
        val feedback = gate.feedback

        // 🔧 Improved pattern matching: order matters! (more specific first)

        // "너무 가까워요" - need to move back (shotTypeTooNarrow)
        // ⚠️ Prevent confusion with "가까이 가세요": "가까워요" = back, "가까이" = forward
        val needsBackward = feedback.contains("뒤로") ||
                feedback.contains("물러") ||
                feedback.contains("작게") || feedback.contains("조금 더 작게") ||
                feedback.contains("멀리") ||
                feedback.contains("가까워요") ||
                feedback.contains("너무 가까") ||
                feedback.contains("잘렸어요")

        val needsForward = feedback.contains("앞으로") ||
                feedback.contains("가까이 가") ||
                feedback.contains("가까이 하") ||
                feedback.contains("다가가") ||
                feedback.contains("더 크게") ||
                feedback.contains("작아요")

        // 🔧 Key: When both needsBackward and needsForward are true
        // "뒤로" takes priority (safer - prevents cropping)
        // But if there's clear "앞으로 다가" instruction, use forward

        when {
            needsForward && !needsBackward -> {
                // Clearly need forward only
                problems.add(GateProblem(
                    gate = 1,
                    type = GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE,
                    currentValue = gate.score,
                    targetValue = 1.0f,
                    severity = severity
                ))
            }
            needsBackward -> {
                // Need backward (or both matched, backward takes priority)
                problems.add(GateProblem(
                    gate = 1,
                    type = GateProblem.ProblemType.SHOT_TYPE_TOO_NARROW,
                    currentValue = gate.score,
                    targetValue = 1.0f,
                    severity = severity
                ))
            }
            !gate.passed -> {
                // 🆕 No pattern matched but Gate 1 failed
                // Score-based inference: low score = big difference = problem
                // Default: shotTypeTooWide (move forward) - safer side
                println("⚠️ [UnifiedFeedback] Gate1 failed but no pattern matched: \"$feedback\"")
                problems.add(GateProblem(
                    gate = 1,
                    type = GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE,  // Default: forward
                    currentValue = gate.score,
                    targetValue = 1.0f,
                    severity = severity * 0.5f  // Lower severity (uncertain)
                ))
            }
        }

        return problems
    }

    private fun analyzePositionProblems(gate: GateResult): List<GateProblem> {
        val problems = mutableListOf<GateProblem>()
        val severity = 1.0f - gate.score
        val feedback = gate.feedback

        // Horizontal analysis (GateSystem: "오른쪽으로 한 걸음 이동", "왼쪽으로 이동")
        if (feedback.contains("오른쪽으로")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_LEFT_HIGH,  // Left margin high → move right
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }
        if (feedback.contains("왼쪽으로")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_RIGHT_HIGH,  // Right margin high → move left
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }

        // 🆕 Vertical analysis - Match GateSystem actual feedback patterns
        // "카메라를 5° 아래로 틸트" → top margin high (person is low in frame)
        if (feedback.contains("아래로 틸트") || feedback.contains("아래로 내리")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_TOP_HIGH,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }
        // "카메라를 5° 위로 틸트" → bottom margin high (person is high in frame)
        if (feedback.contains("위로 틸트") || feedback.contains("위로 올리")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_BOTTOM_HIGH,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }
        // "하단 여백이 너무 많아요" → bottom margin high
        if (feedback.contains("하단 여백") && feedback.contains("많")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_BOTTOM_HIGH,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }
        // "하단이 잘렸어요" → bottom cropped
        if (feedback.contains("하단") && feedback.contains("잘")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_BOTTOM_LOW,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }
        // "상단이 잘렸어요" or "머리가 잘렸어요"
        if ((feedback.contains("상단") || feedback.contains("머리")) && feedback.contains("잘")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_TOP_LOW,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }
        // "하단 여백이 부족해요"
        if (feedback.contains("하단 여백") && feedback.contains("부족")) {
            problems.add(GateProblem(
                gate = 2,
                type = GateProblem.ProblemType.MARGIN_BOTTOM_LOW,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }

        return problems
    }

    private fun analyzeCompressionProblems(gate: GateResult): List<GateProblem> {
        val problems = mutableListOf<GateProblem>()
        val severity = 1.0f - gate.score
        val feedback = gate.feedback

        if (feedback.contains("줌인") || feedback.contains("가까이")) {
            problems.add(GateProblem(
                gate = 3,
                type = GateProblem.ProblemType.COMPRESSION_TOO_LOW,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        } else if (feedback.contains("줌아웃") || feedback.contains("뒤로")) {
            problems.add(GateProblem(
                gate = 3,
                type = GateProblem.ProblemType.COMPRESSION_TOO_HIGH,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = severity
            ))
        }

        return problems
    }

    private fun analyzePoseProblems(gate: GateResult): List<GateProblem> {
        // Pose is hard to solve with movement
        return if (!gate.passed) {
            listOf(GateProblem(
                gate = 4,
                type = GateProblem.ProblemType.POSE_ANGLE_DIFF,
                currentValue = gate.score,
                targetValue = 1.0f,
                severity = 1.0f - gate.score
            ))
        } else emptyList()
    }

    // MARK: - Best Action Selection

    private fun selectBestAction(
        actions: List<AdjustmentAction>,
        problems: List<GateProblem>,
        gate1Score: Float = 0f  // 🆕 Shot type score
    ): AdjustmentAction? {

        if (actions.isEmpty()) {
            return null
        }

        // 🆕 Check if shot type is roughly OK (70% or more)
        val shotTypeOK = gate1Score >= 0.7f

        // 🆕 Margin-related actions (Gate 2 priority)
        val marginActions = setOf(
            AdjustmentAction.MOVE_LEFT,
            AdjustmentAction.MOVE_RIGHT,
            AdjustmentAction.TILT_UP,
            AdjustmentAction.TILT_DOWN
        )

        // Calculate how many problems each action solves
        data class ActionScore(
            val action: AdjustmentAction,
            val score: Int,
            val minGate: Int,
            val isMargin: Boolean
        )

        val actionScores = mutableListOf<ActionScore>()

        for (action in actions) {
            var solvedCount = 0
            var minGateIndex = 5

            for (problem in problems) {
                if (canSolveProblem(action, problem)) {
                    solvedCount++
                    minGateIndex = minOf(minGateIndex, problem.gate)
                }
            }

            if (solvedCount > 0) {
                val isMarginAction = marginActions.contains(action)
                actionScores.add(ActionScore(action, solvedCount, minGateIndex, isMarginAction))
            }
        }

        // 🆕 Improved sorting logic
        actionScores.sortWith(compareBy(
            // If shot type OK, prioritize margin actions
            { if (shotTypeOK) !it.isMargin else false },
            // More solved problems first
            { -it.score },
            // Lower gate number first
            { it.minGate }
        ))

        return actionScores.firstOrNull()?.action
    }

    private fun canSolveProblem(action: AdjustmentAction, problem: GateProblem): Boolean {
        return when (action to problem.type) {
            // Move forward
            AdjustmentAction.MOVE_FORWARD to GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE,
            AdjustmentAction.MOVE_FORWARD to GateProblem.ProblemType.COVERAGE_TOO_LOW,
            AdjustmentAction.MOVE_FORWARD to GateProblem.ProblemType.COMPRESSION_TOO_LOW -> true

            // Move backward
            AdjustmentAction.MOVE_BACKWARD to GateProblem.ProblemType.SHOT_TYPE_TOO_NARROW,
            AdjustmentAction.MOVE_BACKWARD to GateProblem.ProblemType.COVERAGE_TOO_HIGH,
            AdjustmentAction.MOVE_BACKWARD to GateProblem.ProblemType.COMPRESSION_TOO_HIGH,
            AdjustmentAction.MOVE_BACKWARD to GateProblem.ProblemType.MARGIN_TOP_LOW,
            AdjustmentAction.MOVE_BACKWARD to GateProblem.ProblemType.MARGIN_BOTTOM_LOW -> true

            // Horizontal movement
            AdjustmentAction.MOVE_RIGHT to GateProblem.ProblemType.MARGIN_LEFT_HIGH,
            AdjustmentAction.MOVE_LEFT to GateProblem.ProblemType.MARGIN_RIGHT_HIGH -> true

            // 🆕 Tilt - can solve margin + shot type simultaneously
            AdjustmentAction.TILT_UP to GateProblem.ProblemType.MARGIN_BOTTOM_HIGH,
            AdjustmentAction.TILT_UP to GateProblem.ProblemType.MARGIN_TOP_LOW,
            AdjustmentAction.TILT_UP to GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE,
            AdjustmentAction.TILT_DOWN to GateProblem.ProblemType.MARGIN_TOP_HIGH,
            AdjustmentAction.TILT_DOWN to GateProblem.ProblemType.MARGIN_BOTTOM_LOW,
            AdjustmentAction.TILT_DOWN to GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE -> true

            // Zoom
            AdjustmentAction.ZOOM_IN to GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE,
            AdjustmentAction.ZOOM_IN to GateProblem.ProblemType.COMPRESSION_TOO_LOW,
            AdjustmentAction.ZOOM_OUT to GateProblem.ProblemType.SHOT_TYPE_TOO_NARROW,
            AdjustmentAction.ZOOM_OUT to GateProblem.ProblemType.COMPRESSION_TOO_HIGH -> true

            else -> false
        }
    }

    // MARK: - Feedback Creation

    private fun createUnifiedFeedback(
        action: AdjustmentAction,
        problems: List<GateProblem>,
        isFrontCamera: Boolean
    ): UnifiedFeedback {

        // Calculate magnitude (based on problem severity)
        val maxSeverity = problems.maxOfOrNull { it.severity } ?: 0.5f
        val magnitude = calculateMagnitude(action, maxSeverity)

        // Calculate affected gates
        val affectedGates = problems
            .filter { canSolveProblem(action, it) }
            .map { it.gate }
            .distinct()
            .sorted()

        // Generate expected results
        val expectedResults = mutableListOf<String>()
        for (problem in problems) {
            if (canSolveProblem(action, problem)) {
                getExpectedResult(problem)?.let { expectedResults.add(it) }
            }
        }

        // Mirror action for front camera
        var finalAction = action
        if (isFrontCamera && action.needsMirrorForFrontCamera) {
            finalAction = mirrorAction(action)
        }

        return UnifiedFeedback(
            primaryAction = finalAction,
            magnitude = magnitude,
            affectedGates = affectedGates,
            expectedResults = expectedResults,
            priority = affectedGates.firstOrNull() ?: 5
        )
    }

    private fun calculateMagnitude(action: AdjustmentAction, severity: Float): String {
        return when (action) {
            AdjustmentAction.MOVE_FORWARD,
            AdjustmentAction.MOVE_BACKWARD -> {
                when {
                    severity < 0.2f -> "반 걸음"
                    severity < 0.4f -> "한 걸음"
                    severity < 0.6f -> "두 걸음"
                    else -> "세 걸음"
                }
            }

            AdjustmentAction.MOVE_LEFT,
            AdjustmentAction.MOVE_RIGHT -> {
                val percent = (severity * 30).toInt()
                if (severity < 0.3f) {
                    "조금 (${percent}%)"
                } else {
                    "한 걸음 (${percent}%)"
                }
            }

            AdjustmentAction.TILT_UP,
            AdjustmentAction.TILT_DOWN -> {
                val angle = (severity * 15).toInt() + 2
                "${angle}°"
            }

            AdjustmentAction.ZOOM_IN,
            AdjustmentAction.ZOOM_OUT -> {
                if (severity < 0.3f) "약간" else "한 단계"
            }

            // 🆕 Compound actions - magnitude already set
            AdjustmentAction.ZOOM_IN_THEN_MOVE_BACK,
            AdjustmentAction.ZOOM_IN_THEN_MOVE_FORWARD,
            AdjustmentAction.ZOOM_OUT_THEN_MOVE_BACK,
            AdjustmentAction.ZOOM_OUT_THEN_MOVE_FORWARD -> ""  // Calculated separately
            else -> ""
        }
    }

    private fun getExpectedResult(problem: GateProblem): String? {
        return when (problem.type) {
            GateProblem.ProblemType.SHOT_TYPE_TOO_WIDE -> "샷 타입이 좁아집니다"
            GateProblem.ProblemType.SHOT_TYPE_TOO_NARROW -> "샷 타입이 넓어집니다"
            GateProblem.ProblemType.COVERAGE_TOO_LOW -> "인물이 더 크게 보입니다"
            GateProblem.ProblemType.COVERAGE_TOO_HIGH -> "인물이 더 작게 보입니다"
            GateProblem.ProblemType.MARGIN_LEFT_HIGH -> "좌우 균형이 맞춰집니다"
            GateProblem.ProblemType.MARGIN_RIGHT_HIGH -> "좌우 균형이 맞춰집니다"
            GateProblem.ProblemType.MARGIN_TOP_HIGH -> "상단 여백이 줄어듭니다"
            GateProblem.ProblemType.MARGIN_BOTTOM_HIGH -> "하단 여백이 줄어듭니다"
            GateProblem.ProblemType.MARGIN_TOP_LOW -> "상단 잘림이 해결됩니다"
            GateProblem.ProblemType.MARGIN_BOTTOM_LOW -> "하단 잘림이 해결됩니다"
            GateProblem.ProblemType.COMPRESSION_TOO_LOW -> "배경 압축이 자연스러워집니다"
            GateProblem.ProblemType.COMPRESSION_TOO_HIGH -> "배경 압축이 완화됩니다"
            GateProblem.ProblemType.POSE_ANGLE_DIFF -> null  // Can't solve with movement
            else -> null
        }
    }

    private fun mirrorAction(action: AdjustmentAction): AdjustmentAction {
        return when (action) {
            AdjustmentAction.MOVE_LEFT -> AdjustmentAction.MOVE_RIGHT
            AdjustmentAction.MOVE_RIGHT -> AdjustmentAction.MOVE_LEFT
            else -> action
        }
    }

    // MARK: - Fallback Feedback

    private fun createFallbackFeedback(
        evaluation: GateEvaluation,
        isFrontCamera: Boolean
    ): UnifiedFeedback? {

        // Use feedback from first failed gate
        val feedback = evaluation.primaryFeedback

        if (feedback.isEmpty() || feedback.contains("완벽")) {
            return null
        }

        return UnifiedFeedback(
            primaryAction = AdjustmentAction.MOVE_FORWARD,  // Default
            magnitude = "",
            affectedGates = listOf(evaluation.currentFailedGate ?: 1),
            expectedResults = listOf(feedback),
            priority = evaluation.currentFailedGate ?: 1
        )
    }
}
