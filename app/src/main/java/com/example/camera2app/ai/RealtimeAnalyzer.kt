package com.example.camera2app.ai

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import kotlin.math.*

// MARK: - 실시간 분석을 위한 데이터 구조

data class FrameAnalysis(
    val faceRect: android.graphics.RectF?,                  // 얼굴 위치 (정규화된 좌표)
    val bodyRect: android.graphics.RectF?,                  // 전신 추정 영역
    val brightness: Float,                                   // 평균 밝기
    val tiltAngle: Float,                                    // 기울기 각도
    val faceYaw: Float?,                                     // 얼굴 좌우 회전
    val facePitch: Float?,                                   // 얼굴 상하 각도
    val cameraAngle: PhotoCameraAngle,                       // 카메라 각도
    val poseKeypoints: List<KeypointWithConfidence>?,        // 키포인트 (133개)
    val compositionType: CompositionType?,                   // 구도 타입
    val gaze: FramingGazeDirection?,                         // 시선 방향
    val depth: Float?,                                       // 깊이 추정 (미터)
    val aspectRatio: CameraAspectRatio,                      // 카메라 비율
    val imagePadding: ImagePadding?                          // 여백 정보
)

// 이미지 여백 정보
data class ImagePadding(
    val top: Float,         // 상단 여백 (0.0 ~ 1.0)
    val bottom: Float,      // 하단 여백
    val left: Float,        // 좌측 여백
    val right: Float        // 우측 여백
) {
    val total: Float get() = top + bottom + left + right

    val hasExcessivePadding: Boolean
        get() = top > 0.15f || bottom > 0.15f || left > 0.15f || right > 0.15f
}

// 구도 타입 (간단한 버전)
enum class CompositionType(val description: String) {
    CENTER("중앙"),
    RULE_OF_THIRDS_LEFT("삼분할법 좌"),
    RULE_OF_THIRDS_RIGHT("삼분할법 우"),
    GOLDEN_RATIO("황금비율")
}

// MARK: - 실시간 피드백 생성기

class RealtimeAnalyzer(
    private val poseEstimationService: PoseEstimationService
) {
    // StateFlow for reactive UI updates
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

    // 레퍼런스 분석 결과
    var referenceAnalysis: FrameAnalysis? = null
    var referenceFramingResult: PhotographyFramingResult? = null

    // 분석 제어
    private var lastAnalysisTime = System.currentTimeMillis()
    private val analysisInterval = 50L  // 50ms마다 분석
    private var isAnalyzing = false

    // 코루틴 스코프
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 히스테리시스를 위한 상태 추적
    private val feedbackHistory = mutableMapOf<String, Int>()
    private val historyThreshold = 3  // 3번 연속 감지
    private var perfectFrameCount = 0
    private val perfectThreshold = 5  // 5프레임 연속 완벽

    // 고정 피드백
    private val stickyFeedbacks = mutableMapOf<String, FeedbackItem>()
    private val stickyCategories = setOf(
        "pose_left_arm",
        "pose_right_arm",
        "pose_left_leg",
        "pose_right_leg",
        "pose_missing_parts"
    )

    // 완료 감지
    private var previousFeedbackIds = emptySet<String>()
    private val disappearedFeedbackHistory = mutableMapOf<String, Int>()
    private val disappearedThreshold = 2  // 2번 연속 사라져야 완료

    // 분석 컴포넌트들
    private val photographyFramingAnalyzer = PhotographyFramingAnalyzer()
    private val poseComparator = AdaptivePoseComparator()
    private val gapAnalyzer = GapAnalyzer()
    private val stagedFeedbackGenerator = StagedFeedbackGenerator()

    init {
        println("🎬 RealtimeAnalyzer init()")
    }

    // MARK: - 레퍼런스 이미지 분석

    suspend fun analyzeReference(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        println("========================================")
        println("🎯 레퍼런스 이미지 분석 시작")
        println("========================================")

        println("🎯 레퍼런스 이미지 크기: ${bitmap.width} x ${bitmap.height}")

        // RTMPose로 포즈 검출
        val poseResult = poseEstimationService.detectPose(bitmap)

        if (poseResult == null) {
            println("❌ 포즈 검출 실패")
            return@withContext
        }

        println("🎯 분석 완료:")
        println("   - 포즈: ✅ 검출됨 (${poseResult.keypoints.size}개 키포인트)")

        val visibleCount = poseResult.keypoints.count { it.confidence >= 0.5f }
        println("   - 포즈 신뢰도 ≥ 0.5: $visibleCount/${poseResult.keypoints.size}개")

        // 얼굴/신체 영역 추정 (간단한 버전)
        val faceRect = estimateFaceRect(poseResult.keypoints)
        val bodyRect = estimateBodyRect(poseResult.keypoints)

        // 밝기 계산 (간단한 버전)
        val brightness = calculateBrightness(bitmap)

        // 기울기 계산
        val tiltAngle = calculateShoulderTilt(poseResult.keypoints)

        // 얼굴 각도 추정 (간단한 버전)
        val (faceYaw, facePitch) = estimateFaceAngles(poseResult.keypoints)

        // 카메라 앵글 추정
        val cameraAngle = estimateCameraAngle(poseResult.keypoints)

        // 구도 타입 (간단한 버전)
        val compositionType = faceRect?.let { classifyComposition(it) }

        // 시선 방향
        val gaze = estimateGazeDirection(poseResult.keypoints)

        // 깊이 추정 (간단한 버전)
        val depth = faceRect?.let { estimateDepth(it, bitmap.width) }

        // 비율 감지
        val aspectRatio = CameraAspectRatio.detect(bitmap.width.toFloat(), bitmap.height.toFloat())

        // 여백 계산
        val padding = calculatePaddingFromKeypoints(poseResult.keypoints, bitmap.width, bitmap.height)

        // 사진학 기반 프레이밍 분석 (RTMPose 133개 키포인트)
        if (poseResult.keypoints.size >= 133) {
            val normalizedKeypoints = poseResult.keypoints.map { kp ->
                KeypointWithConfidence(
                    x = kp.x / bitmap.width,
                    y = kp.y / bitmap.height,
                    confidence = kp.confidence
                )
            }
            referenceFramingResult = photographyFramingAnalyzer.analyze(normalizedKeypoints)

            referenceFramingResult?.let { refFraming ->
                println("   - 📸 레퍼런스 샷 타입: ${refFraming.shotType.displayName}")
                println("   - 📸 레퍼런스 헤드룸: ${String.format("%.1f%%", refFraming.headroom * 100)}")
                println("   - 📸 레퍼런스 카메라 앵글: ${refFraming.cameraAngle.displayName}")
            }
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

        println("========================================")
        println("📸 레퍼런스 분석 최종 결과:")
        println("   - 비율: ${aspectRatio.displayName}")
        println("   - 얼굴: ${if (faceRect != null) "✅ 감지됨" else "❌ 없음"}")
        println("   - 포즈 키포인트: ${poseResult.keypoints.size}개 (신뢰도 ≥ 0.5: ${visibleCount}개)")
        println("   - 밝기: $brightness")
        println("   - 기울기: ${tiltAngle}도")
        println("========================================")
    }

    // MARK: - 실시간 프레임 분석

    // MARK: - 실시간 프레임 분석
    fun analyzeFrame(
        bitmap: Bitmap,
        isFrontCamera: Boolean = false,
        currentAspectRatio: CameraAspectRatio = CameraAspectRatio.RATIO_4_3
    ) {
        // 너무 자주 분석하지 않도록 제한
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < analysisInterval) return

        // 이미 분석 중이면 스킵
        if (isAnalyzing) return

        // 레퍼런스가 없으면 분석하지 않음
        val reference = referenceAnalysis ?: run {
            analysisScope.launch(Dispatchers.Main) {
                _instantFeedback.value = listOf(
                    FeedbackItem(
                        priority = 1,
                        icon = "👤",
                        message = "얼굴을 화면에 보여주세요",
                        category = "no_face",
                        currentValue = null,
                        targetValue = null,
                        tolerance = null,
                        unit = null
                    )
                )
                _perfectScore.value = 0.0
                _isPerfect.value = false
            }
            return
        }

        // ⭐ Bitmap 유효성 체크
        if (bitmap.isRecycled) {
            println("⚠️ analyzeFrame: bitmap이 이미 recycle됨")
            return
        }

        // ⭐ Bitmap 복사 (비동기 실행 중 recycle 방지)
        val bitmapCopy: Bitmap
        try {
            bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                ?: run {
                    println("⚠️ analyzeFrame: bitmap 복사 실패")
                    return
                }
        } catch (e: Exception) {
            println("⚠️ analyzeFrame: bitmap 복사 중 오류: ${e.message}")
            return
        }

        lastAnalysisTime = now
        isAnalyzing = true

        // 백그라운드에서 분석 실행
        analysisScope.launch {
            try {
                val analysisStart = System.currentTimeMillis()

                // RTMPose로 분석 (복사본 사용)
                val poseStart = System.currentTimeMillis()
                val poseResult = poseEstimationService.detectPose(bitmapCopy)
                val poseEnd = System.currentTimeMillis()

                val analysisEnd = System.currentTimeMillis()

                // 프로파일링 로그
                val poseTime = poseEnd - poseStart
                val totalTime = analysisEnd - analysisStart
                println("📊 [RealtimeAnalyzer] RTMPose: ${poseTime}ms, 총분석: ${totalTime}ms")

                // 메인 스레드에서 결과 처리
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    processAnalysisResult(
                        poseResult = poseResult,
                        bitmap = bitmapCopy,
                        reference = reference,
                        isFrontCamera = isFrontCamera,
                        currentAspectRatio = currentAspectRatio
                    )
                }
            } catch (e: Exception) {
                println("⚠️ analyzeFrame 코루틴 오류: ${e.message}")
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                }
            } finally {
                // ⭐ 분석 완료 후 복사본 recycle
                if (!bitmapCopy.isRecycled) {
                    bitmapCopy.recycle()
                }
            }
        }
    }

    // MARK: - 분석 결과 처리

    private fun processAnalysisResult(
        poseResult: RTMPoseResult?,
        bitmap: Bitmap,
        reference: FrameAnalysis,
        isFrontCamera: Boolean,
        currentAspectRatio: CameraAspectRatio
    ) {
        // 포즈가 감지되지 않으면 완성도 0
        if (poseResult == null) {
            _instantFeedback.value = listOf(
                FeedbackItem(
                    priority = 1,
                    icon = "👤",
                    message = "얼굴을 화면에 보여주세요",
                    category = "no_face",
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

        // 밝기 및 기울기
        val brightness = calculateBrightness(bitmap)
        val tilt = calculateShoulderTilt(poseResult.keypoints)

        // 얼굴/신체 영역
        val faceRect = estimateFaceRect(poseResult.keypoints)
        val bodyRect = estimateBodyRect(poseResult.keypoints)

        // 카메라 앵글
        val cameraAngle = estimateCameraAngle(poseResult.keypoints)

        // 구도
        val compositionType = faceRect?.let { classifyComposition(it) }

        // 시선
        val gaze = estimateGazeDirection(poseResult.keypoints)

        // 깊이
        val depth = faceRect?.let { estimateDepth(it, bitmap.width) }

        // 여백 계산
        val currentPadding = calculatePaddingFromKeypoints(poseResult.keypoints, bitmap.width, bitmap.height)

        // 현재 프레임 분석
        val (faceYaw, facePitch) = estimateFaceAngles(poseResult.keypoints)

        val currentFrame = FrameAnalysis(
            faceRect = faceRect,
            bodyRect = bodyRect,
            brightness = brightness,
            tiltAngle = tilt,
            faceYaw = faceYaw,
            facePitch = facePitch,
            cameraAngle = cameraAngle,
            poseKeypoints = poseResult.keypoints,
            compositionType = compositionType,
            gaze = gaze,
            depth = depth,
            aspectRatio = currentAspectRatio,
            imagePadding = currentPadding
        )

        // 사진학 기반 프레이밍 분석
        var photographyFramingResult: PhotographyFramingResult? = null
        if (poseResult.keypoints.size >= 133) {
            val normalizedKeypoints = poseResult.keypoints.map { kp ->
                KeypointWithConfidence(
                    x = kp.x / bitmap.width,
                    y = kp.y / bitmap.height,
                    confidence = kp.confidence
                )
            }
            photographyFramingResult = photographyFramingAnalyzer.analyze(normalizedKeypoints)
        }

        // Phase 3: 단계별 피드백 시스템

        // 1. 포즈 비교
        var poseComparison: PoseComparisonResult? = null
        var croppedGroups: List<KeypointGroup> = emptyList()

        val refKeypoints = reference.poseKeypoints
        val curKeypoints = poseResult.keypoints

        if (refKeypoints != null && refKeypoints.size >= 133 && curKeypoints.size >= 133) {
            poseComparison = poseComparator.comparePoses(refKeypoints, curKeypoints)

            // 잘린 그룹 감지
            referenceFramingResult?.let { refFraming ->
                croppedGroups = poseComparator.detectCroppedGroups(
                    refKeypoints,
                    curKeypoints,
                    refFraming.shotType
                )
            }
        }

        // 2. 현재 피드백 단계 결정
        val feedbackStage = stagedFeedbackGenerator.determineFeedbackStage(
            referenceFraming = referenceFramingResult,
            currentFraming = photographyFramingResult,
            referenceAspectRatio = reference.aspectRatio,
            currentAspectRatio = currentAspectRatio,
            poseComparison = poseComparison
        )

        // 3. 단계별 피드백 생성
        val feedbacks = stagedFeedbackGenerator.generateStagedFeedback(
            stage = feedbackStage,
            referenceFraming = referenceFramingResult,
            currentFraming = photographyFramingResult,
            referenceAspectRatio = reference.aspectRatio,
            currentAspectRatio = currentAspectRatio,
            poseComparison = poseComparison,
            croppedGroups = croppedGroups,
            isFrontCamera = isFrontCamera
        )

        // 히스테리시스 적용
        val stableFeedback = mutableListOf<FeedbackItem>()
        val currentCategories = mutableSetOf<String>()

        for (fb in feedbacks) {
            if (currentCategories.contains(fb.category)) continue

            currentCategories.add(fb.category)
            feedbackHistory[fb.category] = (feedbackHistory[fb.category] ?: 0) + 1

            if (feedbackHistory[fb.category]!! >= historyThreshold) {
                stableFeedback.add(fb)

                if (stickyCategories.contains(fb.category)) {
                    stickyFeedbacks[fb.category] = fb
                }
            }
        }

        // 고정 피드백 추가
        for ((category, stickyFb) in stickyFeedbacks) {
            if (!stableFeedback.any { it.category == category }) {
                stableFeedback.add(stickyFb)
            }
        }

        // 사라진 카테고리 히스토리 초기화
        for ((category, _) in feedbackHistory.toMap()) {
            if (!currentCategories.contains(category)) {
                feedbackHistory[category] = 0

                if (stickyCategories.contains(category)) {
                    disappearedFeedbackHistory[category] = (disappearedFeedbackHistory[category] ?: 0) + 1
                    if (disappearedFeedbackHistory[category]!! >= disappearedThreshold) {
                        stickyFeedbacks.remove(category)
                        disappearedFeedbackHistory[category] = 0
                    }
                }
            } else {
                disappearedFeedbackHistory[category] = 0
            }
        }

        // 완벽한 상태 감지
        val isCurrentlyPerfect = stableFeedback.isEmpty() && feedbackStage == FeedbackStage.COMPLETE
        val score = if (isCurrentlyPerfect) 1.0 else (1.0 - stableFeedback.size * 0.1)

        if (isCurrentlyPerfect) {
            perfectFrameCount++
        } else {
            perfectFrameCount = 0
        }

        // 완료된 피드백 감지
        val currentFeedbackIds = stableFeedback.map { it.id }.toSet()
        val disappeared = previousFeedbackIds - currentFeedbackIds

        for (disappearedId in disappeared) {
            disappearedFeedbackHistory[disappearedId] = (disappearedFeedbackHistory[disappearedId] ?: 0) + 1

            if (disappearedFeedbackHistory[disappearedId]!! >= disappearedThreshold) {
                _instantFeedback.value.find { it.id == disappearedId }?.let { completedItem ->
                    val completed = CompletedFeedback(completedItem, System.currentTimeMillis())
                    _completedFeedbacks.value = _completedFeedbacks.value + completed
                }
                disappearedFeedbackHistory[disappearedId] = 0
            }
        }

        for ((feedbackId, _) in disappearedFeedbackHistory.toMap()) {
            if (currentFeedbackIds.contains(feedbackId)) {
                disappearedFeedbackHistory[feedbackId] = 0
            }
        }

        // 2초 지난 완료 피드백 제거
        val now = System.currentTimeMillis()
        _completedFeedbacks.value = _completedFeedbacks.value.filter {
            it.shouldDisplay
        }

        previousFeedbackIds = currentFeedbackIds

        // 카테고리별 상태 계산
        val categoryStatuses = calculateCategoryStatuses(stableFeedback)

        // StateFlow 업데이트
        _instantFeedback.value = stableFeedback
        _perfectScore.value = score
        _isPerfect.value = perfectFrameCount >= perfectThreshold
        _categoryStatuses.value = categoryStatuses


        // ⭐ 디버깅 로그 추가
        println("📢 실시간 피드백 업데이트: ${stableFeedback.size}개")
        if (stableFeedback.isNotEmpty()) {
            stableFeedback.forEach { fb ->
                println("   - [${fb.category}] ${fb.message}")
            }
        } else {
            println("   - ✅ 완벽한 상태!")
        }
    }

    // MARK: - Category Status Calculation

    private fun calculateCategoryStatuses(feedbacks: List<FeedbackItem>): List<CategoryStatus> {
        val statusMap = mutableMapOf<FeedbackCategory, CategoryStatus>()

        // 모든 카테고리 초기화
        for (category in FeedbackCategory.values()) {
            statusMap[category] = CategoryStatus(
                category = category,
                isSatisfied = true,
                activeFeedbacks = emptyList()
            )
        }

        // 피드백이 있는 카테고리는 불만족
        for (feedback in feedbacks) {
            val category = FeedbackCategory.fromCategoryString(feedback.category)
            if (category != null) {
                val activeFeedbacks = (statusMap[category]?.activeFeedbacks ?: emptyList()) + feedback
                statusMap[category] = CategoryStatus(
                    category = category,
                    isSatisfied = false,
                    activeFeedbacks = activeFeedbacks.sortedBy { it.priority }
                )
            }
        }

        return statusMap.values.sortedBy { it.priority }
    }

    // MARK: - Helper Functions

    private fun calculatePaddingFromKeypoints(
        keypoints: List<KeypointWithConfidence>,
        imageWidth: Int,
        imageHeight: Int
    ): ImagePadding? {
        val structuralIndices = PhotographyFramingAnalyzer.StructuralKeypoints.all

        val validPoints = structuralIndices.mapNotNull { idx ->
            if (idx < keypoints.size && keypoints[idx].confidence > 0.3f) {
                PointF(
                    keypoints[idx].x / imageWidth,
                    keypoints[idx].y / imageHeight
                )
            } else null
        }

        if (validPoints.size < 3) return null

        val minX = validPoints.minOf { it.x }
        val maxX = validPoints.maxOf { it.x }
        val minY = validPoints.minOf { it.y }
        val maxY = validPoints.maxOf { it.y }

        return ImagePadding(
            top = 1.0f - maxY,
            bottom = minY,
            left = minX,
            right = 1.0f - maxX
        )
    }

    private fun estimateFaceRect(keypoints: List<KeypointWithConfidence>): android.graphics.RectF? {
        // 얼굴 키포인트 (0-4: 코, 눈, 귀)
        val faceIndices = listOf(0, 1, 2, 3, 4)
        val facePoints = faceIndices.mapNotNull { idx ->
            if (idx < keypoints.size && keypoints[idx].confidence > 0.3f) {
                PointF(keypoints[idx].x, keypoints[idx].y)
            } else null
        }

        if (facePoints.size < 3) return null

        val minX = facePoints.minOf { it.x }
        val maxX = facePoints.maxOf { it.x }
        val minY = facePoints.minOf { it.y }
        val maxY = facePoints.maxOf { it.y }

        return android.graphics.RectF(minX, minY, maxX, maxY)
    }

    private fun estimateBodyRect(keypoints: List<KeypointWithConfidence>): android.graphics.RectF? {
        val bodyIndices = PhotographyFramingAnalyzer.StructuralKeypoints.all
        val bodyPoints = bodyIndices.mapNotNull { idx ->
            if (idx < keypoints.size && keypoints[idx].confidence > 0.3f) {
                PointF(keypoints[idx].x, keypoints[idx].y)
            } else null
        }

        if (bodyPoints.size < 3) return null

        val minX = bodyPoints.minOf { it.x }
        val maxX = bodyPoints.maxOf { it.x }
        val minY = bodyPoints.minOf { it.y }
        val maxY = bodyPoints.maxOf { it.y }

        return android.graphics.RectF(minX, minY, maxX, maxY)
    }

    private fun calculateBrightness(bitmap: Bitmap): Float {
        // 간단한 밝기 계산 (평균 픽셀 값)
        var totalBrightness = 0L
        var pixelCount = 0
        val step = 10  // 샘플링

        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                totalBrightness += (r + g + b) / 3
                pixelCount++
            }
        }

        return if (pixelCount > 0) (totalBrightness.toFloat() / pixelCount / 255f) else 0.5f
    }

    private fun calculateShoulderTilt(keypoints: List<KeypointWithConfidence>): Float {
        val leftShoulder = keypoints.getOrNull(KeypointIndex.LEFT_SHOULDER)
        val rightShoulder = keypoints.getOrNull(KeypointIndex.RIGHT_SHOULDER)

        if (leftShoulder == null || rightShoulder == null ||
            leftShoulder.confidence <= 0.3f || rightShoulder.confidence <= 0.3f
        ) {
            return 0f
        }

        val dx = rightShoulder.x - leftShoulder.x
        val dy = rightShoulder.y - leftShoulder.y

        val angleRadians = atan2(dy, dx)
        return angleRadians * 180f / PI.toFloat()
    }

    private fun estimateFaceAngles(keypoints: List<KeypointWithConfidence>): Pair<Float?, Float?> {
        // 간단한 추정 (실제로는 더 복잡한 계산 필요)
        return Pair(0f, 0f)
    }

    private fun estimateCameraAngle(keypoints: List<KeypointWithConfidence>): PhotoCameraAngle {
        // 간단한 추정
        return PhotoCameraAngle.EYE_LEVEL
    }

    private fun classifyComposition(faceRect: android.graphics.RectF): CompositionType {
        val centerX = faceRect.centerX()
        return when {
            centerX < 0.33f -> CompositionType.RULE_OF_THIRDS_LEFT
            centerX > 0.67f -> CompositionType.RULE_OF_THIRDS_RIGHT
            else -> CompositionType.CENTER
        }
    }

    private fun estimateGazeDirection(keypoints: List<KeypointWithConfidence>): FramingGazeDirection {
        // 간단한 추정
        return FramingGazeDirection.CENTER
    }

    private fun estimateDepth(faceRect: android.graphics.RectF, imageWidth: Int): Float {
        // 간단한 추정 (얼굴 크기 기반)
        val faceWidth = faceRect.width() * imageWidth
        return 1.0f / (faceWidth / 200f)  // 대략적인 거리
    }

    fun cleanup() {
        analysisScope.cancel()
    }

    fun clearReference() {
        referenceAnalysis = null
        println("🗑️ 레퍼런스 분석 결과 초기화")
    }
}