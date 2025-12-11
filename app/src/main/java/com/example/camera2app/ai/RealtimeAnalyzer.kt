package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Size
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors

import android.graphics.*
import android.media.Image
import java.io.ByteArrayOutputStream
import com.example.camera2app.ai.models.GateEvaluation




class RealtimeAnalyzer(
    private val context: Context
) {

    // LiveData
    val instantFeedback = MutableLiveData<List<FeedbackItem>>(emptyList())
    val isPerfect = MutableLiveData(false)
    val perfectScore = MutableLiveData(0.0)
    val categoryStatuses = MutableLiveData<List<CategoryStatus>>(emptyList())

    val gateEvaluation = MutableLiveData<LegacyGateEvaluation?>()
    val v15Feedback = MutableLiveData("")

    // Reference
    var referenceAnalysis: FrameAnalysis? = null
    var cachedReference: CachedReference? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastAnalysisTime = 0L
    private var isAnalyzing = false

    private val perfectThreshold = 5
    private var perfectFrameCount = 0

    // Core analyzers
    private val poseEstimator = PoseEstimator(context)
    private val gateSystem = GateSystem.shared


    // ======================================================================
    // MAIN LOOP
    // ======================================================================
    fun analyzeFrame(
        bitmap: Bitmap,
        isFrontCamera: Boolean,
        currentAspectRatio: CameraAspectRatio
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisTime < 50) return
        if (isAnalyzing) return

        val reference = referenceAnalysis ?: run {
            instantFeedback.postValue(emptyList())
            perfectScore.postValue(0.0)
            isPerfect.postValue(false)
            return
        }

        lastAnalysisTime = now
        isAnalyzing = true

        analysisExecutor.execute {
            try {

                // 1) 전체 포즈 분석
                val result = poseEstimator.estimate(bitmap)

                val faceRect = result?.faceRect
                if (faceRect == null) {
                    instantFeedback.postValue(
                        listOf(
                            FeedbackItem(
                                priority = 1,
                                icon = "👤",
                                message = "얼굴을 화면에 보여주세요",
                                category = "no_face"
                            )
                        )
                    )
                    perfectScore.postValue(0.0)
                    isPerfect.postValue(false)
                    isAnalyzing = false
                    return@execute
                }

                // 2) 전신 박스 추정
                val bodyRect = poseEstimator.estimateBodyRect(faceRect)

                // 3) 현재 BBox
                val currentBBox = bodyRect ?: RectF(0.3f, 0.2f, 0.7f, 0.8f)

                // 4) GateSystem 평가 (v1.5 핵심)
                val evaluation = cachedReference?.let { cached ->
                    gateSystem.evaluate(
                        currentBBox = currentBBox,
                        referenceBBox = cached.bbox,
                        currentImageSize = Size(bitmap.width, bitmap.height),
                        referenceImageSize = cached.imageSize.let { Size(it.width.toInt(), it.height.toInt()) },
                        compressionIndex = null,
                        referenceCompressionIndex = cached.compressionIndex
                    )
                }

                gateEvaluation.postValue(evaluation)
                v15Feedback.postValue(evaluation?.primaryFeedback ?: "")

                // 5) v1.5 피드백 생성
                val v15Items = evaluation?.let {
                    V15FeedbackGenerator.shared.generateFeedbackItems(it)
                } ?: emptyList()


                // 6) 카테고리 중복 삭제 + 안정화 출력
                val stableFeedback = mutableListOf<FeedbackItem>()
                val usedCategories = mutableSetOf<String>()

                v15Items.forEach { fb ->
                    val categoryKey = fb.category   // 이미 String임

                    if (!usedCategories.contains(categoryKey)) {
                        stableFeedback.add(fb)
                        usedCategories.add(categoryKey)
                    }
                }

                instantFeedback.postValue(stableFeedback)


                // 7) Perfect 판정
                val v15Perfect = evaluation?.allPassed ?: false
                val v15Score = evaluation?.overallScore?.toDouble() ?: 0.0

                perfectFrameCount = if (v15Perfect) perfectFrameCount + 1 else 0

                perfectScore.postValue(v15Score)
                isPerfect.postValue(perfectFrameCount >= perfectThreshold)

            } catch (e: Exception) {
                e.printStackTrace()
                instantFeedback.postValue(
                    listOf(
                        FeedbackItem(
                            priority = 1,
                            icon = "⚠️",
                            message = "분석 중 오류 발생",
                            category = "analysis_error"
                        )
                    )
                )
                perfectScore.postValue(0.0)
                isPerfect.postValue(false)
            } finally {
                isAnalyzing = false
            }
        }

    }

    // ✅ YUV_420_888 → Bitmap 변환 함수 (실시간 분석용)
    fun yuvToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, image.width, image.height),
            90,
            out
        )

        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }



}
