package com.example.camera2app.ai

import android.graphics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

//----------------------------------------------------------
// 데이터 구조
//----------------------------------------------------------

data class FrameAnalysis(
    val faceRect: RectF?,
    val bodyRect: RectF?,
    val brightness: Float,
    val tiltAngle: Float,
    val faceYaw: Float?,
    val facePitch: Float?,
    val cameraAngle: PhotoCameraAngle,
    val poseKeypoints: List<KeypointWithConfidence>?,
    val compositionType: CompositionType?,
    val gaze: FramingGazeDirection?,
    val depth: Float?,
    val aspectRatio: CameraAspectRatio,
    val imagePadding: ImagePadding?
)

data class ImagePadding(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float
) {
    val total: Float get() = top + bottom + left + right
    val hasExcessivePadding: Boolean
        get() = top > 0.15f || bottom > 0.15f || left > 0.15f || right > 0.15f
}

data class SingleImageAnalysisResult(
    val score: Float,
    val poseScore: Float?,
    val coverageScore: Float?,
    val framingScore: Float?,
    val shotType: ShotType?,
    val message: String,
    val categoryFeedbacks: Map<String, String>
)

//----------------------------------------------------------
// 구도 타입
//----------------------------------------------------------

enum class CompositionType(val description: String) {
    CENTER("중앙"),
    RULE_OF_THIRDS_LEFT("삼분할법 좌"),
    RULE_OF_THIRDS_RIGHT("삼분할법 우"),
    GOLDEN_RATIO("황금비율")
}

//----------------------------------------------------------
// RealtimeAnalyzer
//----------------------------------------------------------

class RealtimeAnalyzer(
    private val poseEstimationService: PoseEstimationService
) {

    // UI 업데이트용 StateFlow --------------------------------------------------

    private val _instantFeedback = MutableStateFlow<List<FeedbackItem>>(emptyList())
    val instantFeedback: StateFlow<List<FeedbackItem>> = _instantFeedback.asStateFlow()

    private val _isPerfect = MutableStateFlow(false)
    val isPerfect: StateFlow<Boolean> = _isPerfect.asStateFlow()

    private val _perfectScore = MutableStateFlow(0.0)
    val perfectScore: StateFlow<Double> = _perfectScore.asStateFlow()

    private val _categoryStatuses = MutableStateFlow<List<CategoryStatus>>(emptyList())
    val categoryStatuses: StateFlow<List<CategoryStatus>> = _categoryStatuses.asStateFlow()

    private val _completedFeedbacks = MutableStateFlow<List<CompletedFeedback>>(emptyList())
    val completedFeedbacks: StateFlow<List<CompletedFeedback>> = _completedFeedbacks.asStateFlow()


    // 내부 상태 ---------------------------------------------------------------

    var referenceAnalysis: FrameAnalysis? = null
    var referenceFramingResult: PhotographyFramingResult? = null

    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastAnalysisTime = System.currentTimeMillis()
    private val analysisInterval = 50L
    private var isAnalyzing = false

    private var perfectFrameCount = 0
    private val perfectThreshold = 5

    private val feedbackHistory = mutableMapOf<String, Int>()
    private val disappearedFeedbackHistory = mutableMapOf<String, Int>()

    private val stickyCategories = setOf(
        "pose_left_arm", "pose_right_arm",
        "pose_left_leg", "pose_right_leg",
        "pose_missing_parts"
    )
    private val stickyFeedbacks = mutableMapOf<String, FeedbackItem>()

    private var previousFeedbackIds = emptySet<String>()

    // 분석 컴포넌트 -----------------------------------------------------------

    private val photographyFramingAnalyzer = PhotographyFramingAnalyzer()
    private val poseComparator = AdaptivePoseComparator()
    private val gapAnalyzer = GapAnalyzer()
    private val stagedFeedbackGenerator = StagedFeedbackGenerator()

    var isCapturing: Boolean = false


    init {
        println("🎬 RealtimeAnalyzer initialized")
    }

    // -------------------------------------------------------------------------
    // 1) 싱글 이미지 분석 (갤러리 I 버튼)
    // -------------------------------------------------------------------------

    suspend fun analyzeSingleImage(bitmap: Bitmap): SingleImageAnalysisResult =
        withContext(Dispatchers.Default) {

            val poseResult = poseEstimationService.detectPose(bitmap)

            // ❗ 사람 없을 때 → 0점 처리
            if (poseResult == null ||
                poseResult.keypoints.count { it.confidence >= 0.3f } < 10
            ) {
                return@withContext SingleImageAnalysisResult(
                    score = 0f,
                    poseScore = 0f,
                    coverageScore = 0f,
                    framingScore = 0f,
                    shotType = null,
                    message = "사람을 찾을 수 없어요",
                    categoryFeedbacks = mapOf(
                        "pose" to "사람이 감지되지 않았어요",
                        "composition" to "프레이밍을 분석할 수 없어요",
                        "viewpoint" to "사람이 없어 시점을 분석할 수 없어요",
                        "color" to "사진만으로는 분석이 어려워요",
                        "mood" to "사진만으로는 분석이 어려워요"
                    )
                )
            }

            val keypoints = poseResult.keypoints

            val framingResult = photographyFramingAnalyzer.analyze(
                keypoints.map {
                    KeypointWithConfidence(
                        it.x / bitmap.width,
                        it.y / bitmap.height,
                        it.confidence
                    )
                }
            )

            if (framingResult == null) {
                return@withContext SingleImageAnalysisResult(
                    score = 3f,
                    poseScore = 0f,
                    coverageScore = 0f,
                    framingScore = 0f,
                    shotType = null,
                    message = "프레이밍 분석 불가",
                    categoryFeedbacks = emptyMap()
                )
            }

            val poseScore = framingResult.shotTypeConfidence * 10f
            val coverageScore = framingResult.bodyCoverage * 10f
            val framingScore = framingResult.overallScore * 10f

            val finalScore = (
                    poseScore * 0.3f +
                            coverageScore * 0.3f +
                            framingScore * 0.4f
                    ).coerceIn(0f, 10f)

            val categoryFeedbacks = mutableMapOf<String, String>()

            categoryFeedbacks["pose"] =
                if (poseScore > 7f) "포즈가 자연스러워요" else "포즈가 부자연스러워요"

            categoryFeedbacks["composition"] =
                framingResult.generateFeedback() ?: "구도가 자연스러워요"

            categoryFeedbacks["viewpoint"] =
                "카메라 앵글: ${framingResult.cameraAngle.displayName}"

            categoryFeedbacks["color"] = "노출은 전체적으로 양호해요"
            categoryFeedbacks["mood"] = "사진 분위기가 좋아요"

            return@withContext SingleImageAnalysisResult(
                score = finalScore,
                poseScore = poseScore,
                coverageScore = coverageScore,
                framingScore = framingScore,
                shotType = framingResult.shotType,
                message = "사진 분석 완료",
                categoryFeedbacks = categoryFeedbacks
            )
        }
// -------------------------------------------------------------------------
// 2) 레퍼런스 이미지 분석
// -------------------------------------------------------------------------

    suspend fun analyzeReference(bitmap: Bitmap) = withContext(Dispatchers.Default) {

        println("========================================")
        println("🎯 레퍼런스 이미지 분석 시작")
        println("========================================")

        val poseResult = poseEstimationService.detectPose(bitmap)

        if (poseResult == null) {
            println("❌ 포즈 검출 실패 (사람 없음)")
            referenceAnalysis = null
            referenceFramingResult = null
            return@withContext
        }

        val faceRect = estimateFaceRect(poseResult.keypoints)
        val bodyRect = estimateBodyRect(poseResult.keypoints)

        val brightness = calculateBrightness(bitmap)
        val tiltAngle = calculateShoulderTilt(poseResult.keypoints)

        val (faceYaw, facePitch) = estimateFaceAngles(poseResult.keypoints)
        val cameraAngle = estimateCameraAngle(poseResult.keypoints)
        val compositionType = faceRect?.let { classifyComposition(it) }
        val gaze = estimateGazeDirection(poseResult.keypoints)
        val depth = faceRect?.let { estimateDepth(it, bitmap.width) }

        val aspectRatio = CameraAspectRatio.detect(bitmap.width.toFloat(), bitmap.height.toFloat())
        val padding = calculatePaddingFromKeypoints(
            poseResult.keypoints,
            bitmap.width,
            bitmap.height
        )

        // 133 Keypoints일 때 프레이밍 분석
        if (poseResult.keypoints.size >= 133) {
            val normalized = poseResult.keypoints.map {
                KeypointWithConfidence(
                    x = it.x / bitmap.width,
                    y = it.y / bitmap.height,
                    confidence = it.confidence
                )
            }
            referenceFramingResult = photographyFramingAnalyzer.analyze(normalized)
        }

        referenceAnalysis = FrameAnalysis(
            faceRect = faceRect,
            bodyRect = bodyRect,
            brightness = brightness,
            tiltAngle = tiltAngle,
            faceYaw = faceYaw,
            facePitch = facePitch,
            cameraAngle = cameraAngle,
            poseKeypoints = poseResult.keypoints,
            compositionType = compositionType,
            gaze = gaze,
            depth = depth,
            aspectRatio = aspectRatio,
            imagePadding = padding
        )
    }


// -------------------------------------------------------------------------
// 3) 실시간 분석 (카메라 프리뷰)
// -------------------------------------------------------------------------

    fun analyzeFrame(
        bitmap: Bitmap,
        isFrontCamera: Boolean = false,
        currentAspectRatio: CameraAspectRatio = CameraAspectRatio.RATIO_4_3
    ) {
        val now = System.currentTimeMillis()

        if (now - lastAnalysisTime < analysisInterval) return
        if (isAnalyzing) return

        val reference = referenceAnalysis ?: run {
            analysisScope.launch(Dispatchers.Main) {
                _instantFeedback.value = listOf(
                    FeedbackItem(
                        priority = 1,
                        icon = "📸",
                        message = "레퍼런스를 먼저 선택해주세요",
                        category = "no_reference",
                        currentValue = null,
                        targetValue = null,
                        tolerance = null,
                        unit = null
                    )
                )
                _isPerfect.value = false
                _perfectScore.value = 0.0
            }
            return
        }

        if (bitmap.isRecycled) return

        val safeCopy = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            return
        } ?: return

        lastAnalysisTime = now
        isAnalyzing = true

        analysisScope.launch {
            val poseResult = poseEstimationService.detectPose(safeCopy)

            withContext(Dispatchers.Main) {
                isAnalyzing = false
                processAnalysisResult(
                    poseResult = poseResult,
                    bitmap = safeCopy,
                    reference = reference,
                    isFrontCamera = isFrontCamera,
                    currentAspectRatio = currentAspectRatio
                )
            }

            safeCopy.recycle()
        }
    }


// -------------------------------------------------------------------------
// 실시간 분석 결과 처리
// -------------------------------------------------------------------------

    private fun processAnalysisResult(
        poseResult: RTMPoseResult?,
        bitmap: Bitmap,
        reference: FrameAnalysis,
        isFrontCamera: Boolean,
        currentAspectRatio: CameraAspectRatio
    ) {

        // ❗ 사람이 감지되지 않은 경우
        if (poseResult == null ||
            poseResult.keypoints.count { it.confidence >= 0.3f } < 10
        ) {
            _instantFeedback.value = listOf(
                FeedbackItem(
                    priority = 1,
                    icon = "👤",
                    message = "사람이 화면에 보이지 않아요",
                    category = "no_person",
                    currentValue = null,
                    targetValue = null,
                    tolerance = null,
                    unit = null
                )
            )
            _perfectScore.value = 0.0
            _isPerfect.value = false
            return
        }

        // 사람 감지됨 → 계속 분석
        val keypoints = poseResult.keypoints
        val brightness = calculateBrightness(bitmap)
        val tilt = calculateShoulderTilt(keypoints)

        val faceRect = estimateFaceRect(keypoints)
        val bodyRect = estimateBodyRect(keypoints)
        val cameraAngle = estimateCameraAngle(keypoints)
        val compositionType = faceRect?.let { classifyComposition(it) }
        val gaze = estimateGazeDirection(keypoints)
        val depth = faceRect?.let { estimateDepth(it, bitmap.width) }

        val padding =
            calculatePaddingFromKeypoints(keypoints, bitmap.width, bitmap.height)

        val (faceYaw, facePitch) = estimateFaceAngles(keypoints)

        val currentFrame = FrameAnalysis(
            faceRect,
            bodyRect,
            brightness,
            tilt,
            faceYaw,
            facePitch,
            cameraAngle,
            keypoints,
            compositionType,
            gaze,
            depth,
            currentAspectRatio,
            padding
        )

        var framingResult: PhotographyFramingResult? = null
        if (keypoints.size >= 133) {
            val normalized = keypoints.map {
                KeypointWithConfidence(
                    it.x / bitmap.width,
                    it.y / bitmap.height,
                    it.confidence
                )
            }
            framingResult = photographyFramingAnalyzer.analyze(normalized)
        }

        var poseComparison: PoseComparisonResult? = null
        var croppedGroups: List<KeypointGroup> = emptyList()

        val refKeypoints = reference.poseKeypoints
        if (refKeypoints != null && keypoints.size >= 133 && refKeypoints.size >= 133) {

            poseComparison = poseComparator.comparePoses(refKeypoints, keypoints)

            referenceFramingResult?.let { ref ->
                croppedGroups = poseComparator.detectCroppedGroups(
                    refKeypoints,
                    keypoints,
                    ref.shotType
                )
            }
        }

        val stage = stagedFeedbackGenerator.determineFeedbackStage(
            referenceFraming = referenceFramingResult,
            currentFraming = framingResult,
            referenceAspectRatio = reference.aspectRatio,
            currentAspectRatio = currentAspectRatio,
            poseComparison = poseComparison
        )

        val feedbacks = stagedFeedbackGenerator.generateStagedFeedback(
            stage,
            referenceFraming = referenceFramingResult,
            currentFraming = framingResult,
            reference.aspectRatio,
            currentAspectRatio,
            poseComparison,
            croppedGroups,
            isFrontCamera
        )

        val stableFeedback = mutableListOf<FeedbackItem>()
        val currentCategories = mutableSetOf<String>()

        for (fb in feedbacks) {
            if (currentCategories.contains(fb.category)) continue
            currentCategories.add(fb.category)

            feedbackHistory[fb.category] = (feedbackHistory[fb.category] ?: 0) + 1

            if (feedbackHistory[fb.category]!! >= 3) {
                stableFeedback.add(fb)
                if (stickyCategories.contains(fb.category)) {
                    stickyFeedbacks[fb.category] = fb
                }
            }
        }

        // Sticky 유지
        stickyFeedbacks.forEach { (cat, item) ->
            if (!stableFeedback.any { it.category == cat }) {
                stableFeedback.add(item)
            }
        }

        // 사라진 피드백 → completed 처리
        val disappeared = previousFeedbackIds - stableFeedback.map { it.id }.toSet()
        for (id in disappeared) {
            disappearedFeedbackHistory[id] = (disappearedFeedbackHistory[id] ?: 0) + 1
            if (disappearedFeedbackHistory[id]!! >= 2) {
                val old =
                    _instantFeedback.value.find { it.id == id } ?: continue
                _completedFeedbacks.value =
                    _completedFeedbacks.value + CompletedFeedback(old, System.currentTimeMillis())
                disappearedFeedbackHistory[id] = 0
            }
        }

        previousFeedbackIds = stableFeedback.map { it.id }.toSet()

        val isPerfectNow =
            stableFeedback.isEmpty() && stage == FeedbackStage.COMPLETE

        if (isPerfectNow) perfectFrameCount++
        else perfectFrameCount = 0

        _isPerfect.value = perfectFrameCount >= perfectThreshold
        _perfectScore.value =
            if (isPerfectNow) 1.0 else 1.0 - stableFeedback.size * 0.1

        // 🚫 촬영 중이면 UI 업데이트 금지
        if (isCapturing) return

        _instantFeedback.value = stableFeedback
        _categoryStatuses.value = calculateCategoryStatuses(stableFeedback)
    }


// -------------------------------------------------------------------------
// 카테고리 상태 계산
// -------------------------------------------------------------------------

    private fun calculateCategoryStatuses(
        feedbacks: List<FeedbackItem>
    ): List<CategoryStatus> {

        val map = mutableMapOf<FeedbackCategory, CategoryStatus>()

        FeedbackCategory.values().forEach { cat ->
            map[cat] = CategoryStatus(cat, true, emptyList())
        }

        for (fb in feedbacks) {
            val cat = FeedbackCategory.fromCategoryString(fb.category) ?: continue
            val updated =
                (map[cat]?.activeFeedbacks ?: emptyList()) + fb

            map[cat] = CategoryStatus(
                cat,
                isSatisfied = false,
                activeFeedbacks = updated.sortedBy { it.priority }
            )
        }

        return map.values.sortedBy { it.priority }
    }


// -------------------------------------------------------------------------
// Helper functions
// -------------------------------------------------------------------------

    private fun calculatePaddingFromKeypoints(
        keypoints: List<KeypointWithConfidence>,
        w: Int,
        h: Int
    ): ImagePadding? {

        val structural = PhotographyFramingAnalyzer.StructuralKeypoints.all

        val pts = structural.mapNotNull { idx ->
            if (idx < keypoints.size && keypoints[idx].confidence > 0.3f)
                PointF(keypoints[idx].x / w, keypoints[idx].y / h)
            else null
        }

        if (pts.size < 3) return null

        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }

        return ImagePadding(
            top = 1f - maxY,
            bottom = minY,
            left = minX,
            right = 1f - maxX
        )
    }

    private fun estimateFaceRect(keypoints: List<KeypointWithConfidence>): RectF? {
        val indices = listOf(0, 1, 2, 3, 4)

        val pts = indices.mapNotNull { i ->
            if (i < keypoints.size && keypoints[i].confidence > 0.3f)
                PointF(keypoints[i].x, keypoints[i].y)
            else null
        }

        if (pts.size < 3) return null

        return RectF(
            pts.minOf { it.x },
            pts.minOf { it.y },
            pts.maxOf { it.x },
            pts.maxOf { it.y }
        )
    }

    private fun estimateBodyRect(keypoints: List<KeypointWithConfidence>): RectF? {
        val structural = PhotographyFramingAnalyzer.StructuralKeypoints.all

        val pts = structural.mapNotNull { idx ->
            if (idx < keypoints.size && keypoints[idx].confidence > 0.3f)
                PointF(keypoints[idx].x, keypoints[idx].y)
            else null
        }

        if (pts.size < 3) return null

        return RectF(
            pts.minOf { it.x },
            pts.minOf { it.y },
            pts.maxOf { it.x },
            pts.maxOf { it.y }
        )
    }

    private fun calculateBrightness(bitmap: Bitmap): Float {
        var total = 0L
        var count = 0
        val step = 10

        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                total += (r + g + b) / 3
                count++
            }
        }

        return if (count > 0) total.toFloat() / count / 255f else 0.5f
    }

    private fun calculateShoulderTilt(kp: List<KeypointWithConfidence>): Float {

        val L = kp.getOrNull(KeypointIndex.LEFT_SHOULDER)
        val R = kp.getOrNull(KeypointIndex.RIGHT_SHOULDER)

        if (L == null || R == null ||
            L.confidence <= 0.3f || R.confidence <= 0.3f
        ) return 0f

        val dx = R.x - L.x
        val dy = R.y - L.y

        return (atan2(dy, dx) * 180f / PI).toFloat()
    }

    private fun estimateFaceAngles(
        k: List<KeypointWithConfidence>
    ): Pair<Float?, Float?> {
        return Pair(0f, 0f)
    }

    private fun estimateCameraAngle(
        keypoints: List<KeypointWithConfidence>
    ): PhotoCameraAngle {
        return PhotoCameraAngle.EYE_LEVEL
    }

    private fun classifyComposition(faceRect: RectF): CompositionType {
        val cx = faceRect.centerX()
        return when {
            cx < 0.33f -> CompositionType.RULE_OF_THIRDS_LEFT
            cx > 0.67f -> CompositionType.RULE_OF_THIRDS_RIGHT
            else -> CompositionType.CENTER
        }
    }

    private fun estimateGazeDirection(
        kp: List<KeypointWithConfidence>
    ): FramingGazeDirection {
        return FramingGazeDirection.CENTER
    }

    private fun estimateDepth(f: RectF, w: Int): Float {
        val fw = f.width() * w
        return 1.0f / (fw / 200f)
    }


// -------------------------------------------------------------------------
// cleanup
// -------------------------------------------------------------------------

    fun clearReference() {
        referenceAnalysis = null
    }

    fun cleanup() {
        analysisScope.cancel()
    }

}
