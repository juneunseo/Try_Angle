package com.example.camera2app

import android.net.Uri
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Bitmap
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.example.camera2app.camera.Camera2Controller
import com.example.camera2app.databinding.ActivityMainBinding
import com.example.camera2app.gallery.GalleryActivity
import com.example.camera2app.util.Permissions
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

// ✅ AI 시스템 import
import com.example.camera2app.ai.*

import android.os.Handler
import android.os.Looper


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller
    private lateinit var scaleDetector: ScaleGestureDetector

    // ✅ AI 시스템
    private var poseEstimationService: PoseEstimationService? = null
    private var realtimeAnalyzer: RealtimeAnalyzer? = null
    private var isAIInitialized = false

    // ✅ 레퍼런스 설정 여부
    private var isReferenceSet = false

    // ✅ 레퍼런스 이미지
    private var referenceBitmap: Bitmap? = null

    // ✅ 실시간 분석 Job
    private var realtimeAnalysisJob: Job? = null
    private var lastAnalysisTime = 0L
    private val analysisIntervalMs = 33L  // 30fps

    // EV 슬라이더
    private var tapEvSlider: View? = null
    private lateinit var rootFrame: FrameLayout
    private var isAllAuto = true
    private var optionVisible = false

    // ✅ 피드백 UI
    private lateinit var feedbackStatusContainer: LinearLayout
    private lateinit var feedbackMessageContainer: LinearLayout
    private lateinit var feedbackMessage: TextView
    private lateinit var feedbackIcon: ImageView
    private lateinit var iconPose: ImageView
    private lateinit var iconPosition: ImageView
    private lateinit var iconFraming: ImageView
    private lateinit var iconAngle: ImageView
    private lateinit var iconComposition: ImageView
    private lateinit var iconGaze: ImageView
    private var isCapturing = false


    companion object {
        private const val REQUEST_REFERENCE_IMAGE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rootFrame = findViewById(android.R.id.content)

        applyWindowInset()
        initCameraController()
        initAISystem()
        initPinchZoom()
        initButtons()
        initFeedbackUI()
        requestPermissionsIfNeeded()

        setAspectText(Camera2Controller.AspectMode.RATIO_9_16)


        // -----------------------------------------
        // 🔥 분석 모드 진입 (ImageDetailActivity → MainActivity)
        // -----------------------------------------
        // -----------------------------------------
// 🔥 분석 모드 진입
// -----------------------------------------
        val isAnalysisMode = intent.getBooleanExtra("analysis_mode", false)
        val refUriString = intent.getStringExtra("reference_uri")

        if (isAnalysisMode && refUriString != null) {

            println("🔥 MainActivity: 분석모드로 진입함")

            showLoadingOverlay()

            val refUri = Uri.parse(refUriString)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // -------------------------------
                    // 1) 레퍼런스 비트맵 로드
                    // -------------------------------
                    val bitmap = uriToBitmap(refUri)

                    if (bitmap == null) {
                        withContext(Dispatchers.Main) {
                            hideLoadingOverlay()
                            Toast.makeText(
                                this@MainActivity,
                                "레퍼런스 이미지를 불러올 수 없습니다",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }

                    referenceBitmap = bitmap


                    // -------------------------------
                    // 2) AI 준비 완료까지 대기
                    // -------------------------------
                    while (!isAIInitialized) {
                        delay(20)
                    }


                    // -------------------------------
                    // 3) 레퍼런스 분석 (suspend 함수)
                    // -------------------------------
                    realtimeAnalyzer?.analyzeReference(bitmap)


                    // -------------------------------
                    // 4) 분석 준비 완료 → UI 업데이트
                    // -------------------------------
                    withContext(Dispatchers.Main) {
                        isReferenceSet = true
                        hideLoadingOverlay()
                        showFeedbackUI()

                        binding.textureView.postDelayed({
                            startRealtimeAnalysis()
                        }, 500)
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        hideLoadingOverlay()
                        Toast.makeText(
                            this@MainActivity,
                            "레퍼런스 분석 실패: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    e.printStackTrace()
                }
            }
        }

    }

        // ✅ 피드백 UI 초기화
    private fun initFeedbackUI() {
        feedbackStatusContainer = findViewById(R.id.feedbackStatusContainer)
        feedbackMessageContainer = findViewById(R.id.feedbackMessageContainer)
        feedbackMessage = findViewById(R.id.feedbackMessage)
        feedbackIcon = findViewById(R.id.feedbackIcon)
        iconPose = findViewById(R.id.iconPose)
        iconPosition = findViewById(R.id.iconPosition)
        iconFraming = findViewById(R.id.iconFraming)
        iconAngle = findViewById(R.id.iconAngle)
        iconComposition = findViewById(R.id.iconComposition)
        iconGaze = findViewById(R.id.iconGaze)

        // ✅ 초기에는 피드백 UI 숨김
        hideFeedbackUI()
    }

    // ✅ 피드백 UI 숨기기
    private fun hideFeedbackUI() {
        feedbackStatusContainer.visibility = View.GONE
        feedbackMessageContainer.visibility = View.GONE
        // ✅ 옵션 버튼 다시 보이기
        binding.btnOptions.visibility = View.VISIBLE
    }

    // ✅ 피드백 UI 보이기 - 초기 상태는 회색
    private fun showFeedbackUI() {
        feedbackStatusContainer.visibility = View.VISIBLE

        // 옵션바가 열려있으면 닫기
        if (optionVisible) {
            binding.optionBar.visibility = View.GONE
            optionVisible = false
        }
        // ✅ EV 슬라이더가 열려있으면 닫기
        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
        }

        // ⭐ 초기 상태: 모든 아이콘 회색 (분석 대기 중)
        setAllStatusGray()
        feedbackMessageContainer.visibility = View.VISIBLE
        feedbackMessage.text = "포즈 분석 중..."
    }

    // ⭐ 모든 아이콘 회색으로 (분석 실패 또는 대기 중)
    private fun setAllStatusGray() {
        val grayDrawable = resources.getDrawable(R.drawable.ic_select_empty, null)
        iconPose.setImageDrawable(grayDrawable)
        iconPosition.setImageDrawable(grayDrawable)
        iconFraming.setImageDrawable(grayDrawable)
        iconAngle.setImageDrawable(grayDrawable)
        iconComposition.setImageDrawable(grayDrawable)
        iconGaze.setImageDrawable(grayDrawable)
    }

    // ✅ AI 시스템 초기화
    private fun initAISystem() {
        Thread {
            try {
                println("========================================")
                println("🤖 AI 시스템 초기화 시작")
                println("========================================")

                val startTime = System.currentTimeMillis()

                // 1. PoseEstimationService 초기화
                poseEstimationService = PoseEstimationService(applicationContext)
                poseEstimationService?.initialize()

                // 2. RealtimeAnalyzer 초기화
                realtimeAnalyzer = RealtimeAnalyzer(poseEstimationService!!)

                val elapsed = System.currentTimeMillis() - startTime
                isAIInitialized = true

                runOnUiThread {
                    println("========================================")
                    println("✅ AI 시스템 초기화 완료 (${elapsed}ms)")
                    println("========================================")
                    Toast.makeText(this, "✅ AI 시스템 준비 완료", Toast.LENGTH_SHORT).show()

                    startFeedbackUIUpdates()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "❌ AI 시스템 초기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------------------
    // Window Insets
    // ---------------------------
    private fun applyWindowInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewContainer) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = status
            }
            binding.fpsText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = status + dp(56 + 8)
            }
            insets
        }
    }

    // ---------------------------
    // Camera Controller
    // ---------------------------
    private fun initCameraController() {
        controller = Camera2Controller(
            context = this,
            overlayView = binding.overlayView,
            textureView = binding.textureView,
            onFrameLevelChanged = {},
            onSaved = { uri ->
                val bitmap = uriToBitmap(uri)
                if (bitmap != null) {
                    processCapturedPhoto(bitmap, uri)
                }
            },
            previewContainer = binding.previewContainer
        ) { fps ->
            runOnUiThread {
                binding.fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
            }
        }

        controller.setTimerCountdownCallback { remaining ->
            runOnUiThread {
                if (remaining > 0) {
                    binding.timerCountdownText.text = remaining.toString()
                    binding.timerCountdownText.visibility = View.VISIBLE
                    binding.timerCountdownText.scaleX = 1.5f
                    binding.timerCountdownText.scaleY = 1.5f
                    binding.timerCountdownText.alpha = 1f
                    binding.timerCountdownText.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(0.8f)
                        .setDuration(800)
                        .start()
                } else {
                    binding.timerCountdownText.visibility = View.GONE
                }
            }
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ✅ 촬영된 사진 처리
    private fun processCapturedPhoto(bitmap: Bitmap, uri: Uri) {
        try {
            println("📸 processCapturedPhoto 진입")
            println("📸 현재 스레드: ${Thread.currentThread().name}")
            println("📸 bitmap null?: ${bitmap == null}")

            if (bitmap == null) {
                println("❌ bitmap이 null!")
                return
            }

            println("📸 bitmap isRecycled?: ${bitmap.isRecycled}")

            if (bitmap.isRecycled) {
                println("❌ bitmap이 이미 recycle됨!")
                runOnUiThread {
                    Toast.makeText(this, "이미지 처리 오류", Toast.LENGTH_SHORT).show()
                }
                return
            }

            println("📸 bitmap 크기: ${bitmap.width}x${bitmap.height}")
            println("📸 uri: $uri")
            println("📸 isAIInitialized: $isAIInitialized")
            println("📸 poseEstimationService null?: ${poseEstimationService == null}")
            println("📸 isReferenceSet: $isReferenceSet")
            println("📸 referenceBitmap null?: ${referenceBitmap == null}")

            if (!isAIInitialized || poseEstimationService == null) {
                println("❌ AI 시스템 미초기화")
                runOnUiThread {
                    Toast.makeText(this, "AI 시스템 로딩 중...", Toast.LENGTH_SHORT).show()
                }
                return
            }

            if (!isReferenceSet || referenceBitmap == null) {
                println("❌ 레퍼런스 미설정")
                runOnUiThread {
                    Toast.makeText(this, "레퍼런스를 먼저 선택해주세요", Toast.LENGTH_SHORT).show()
                }
                return
            }

            // referenceBitmap도 체크
            if (referenceBitmap!!.isRecycled) {
                println("❌ referenceBitmap이 이미 recycle됨!")
                runOnUiThread {
                    Toast.makeText(this, "레퍼런스 이미지 오류", Toast.LENGTH_SHORT).show()
                }
                return
            }

            println("📸 모든 체크 통과, 로딩 오버레이 표시")

            // ⭐ 1. 로딩 오버레이 표시
            runOnUiThread {
                showLoadingOverlay()
            }

            Thread {
                try {
                    println("📸 백그라운드 스레드 시작")
                    println("📸 촬영 사진 분석 시작...")

                    // ⭐ 2. AI 분석
                    val capturedResult = poseEstimationService?.detectPose(bitmap)
                    println("📸 capturedResult: ${capturedResult?.keypoints?.size ?: "null"}")

                    if (capturedResult == null) {
                        println("❌ capturedResult가 null")
                        runOnUiThread {
                            hideLoadingOverlay()
                            Toast.makeText(this, "포즈를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }

                    // ⭐ 유효한 키포인트 개수 체크 추가
                    val capturedValidKeypoints =
                        capturedResult.keypoints.count { it.confidence >= 0.5f }
                    println("📸 촬영 사진 유효한 키포인트: $capturedValidKeypoints / ${capturedResult.keypoints.size}")

                    if (capturedValidKeypoints < 10) {
                        println("❌ 촬영 사진: 유효한 키포인트 부족 → 사람 없음 → 0점 처리")

                        val score = 0.0f
                        val feedbackMsg = "사람이 인식되지 않아 0점으로 평가되었어요."

                        runOnUiThread {
                            hideLoadingOverlay()

                            val intent = Intent(
                                this,
                                com.example.camera2app.gallery.FeedbackScoreActivity::class.java
                            ).apply {
                                putExtra(
                                    com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_CAPTURED_URI,
                                    uri.toString()
                                )
                                putExtra(
                                    com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_REFERENCE_URI,
                                    "reference_uri_placeholder"
                                )
                                putExtra(
                                    com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_SCORE,
                                    score
                                )
                                putExtra(
                                    com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_FEEDBACK_MESSAGE,
                                    feedbackMsg
                                )
                            }
                            startActivity(intent)
                        }

                        return@Thread
                    }


                    println("📸 레퍼런스 재분석 시작...")
                    val referenceResult = poseEstimationService?.detectPose(referenceBitmap!!)
                    println("📸 referenceResult: ${referenceResult?.keypoints?.size ?: "null"}")

                    if (referenceResult == null) {
                        println("❌ referenceResult가 null")
                        runOnUiThread {
                            hideLoadingOverlay()
                            Toast.makeText(this, "레퍼런스 포즈를 분석할 수 없습니다", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }

                    // ⭐ 레퍼런스도 유효 키포인트 체크
                    val refValidKeypoints =
                        referenceResult.keypoints.count { it.confidence >= 0.5f }
                    println("📸 레퍼런스 유효한 키포인트: $refValidKeypoints / ${referenceResult.keypoints.size}")

                    if (refValidKeypoints < 10) {
                        println("❌ 레퍼런스: 유효한 키포인트 부족")
                        runOnUiThread {
                            hideLoadingOverlay()
                            Toast.makeText(this, "레퍼런스 이미지의 포즈가 명확하지 않습니다.", Toast.LENGTH_LONG)
                                .show()
                        }
                        return@Thread
                    }

                    println("📊 점수 계산 시작...")
                    val score = calculatePoseSimilarity(capturedResult, referenceResult)
                    println("📊 점수: $score")

                    val feedbackMsg = generateFeedbackMessage(score)
                    println("✅ 분석 완료!")

                    runOnUiThread {
                        println("🚀 FeedbackScoreActivity 이동")
                        hideLoadingOverlay()

                        val intent = Intent(
                            this,
                            com.example.camera2app.gallery.FeedbackScoreActivity::class.java
                        ).apply {
                            putExtra(
                                com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_CAPTURED_URI,
                                uri.toString()
                            )
                            putExtra(
                                com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_REFERENCE_URI,
                                "reference_uri_placeholder"
                            )
                            putExtra(
                                com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_SCORE,
                                score
                            )
                            putExtra(
                                com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_FEEDBACK_MESSAGE,
                                feedbackMsg
                            )
                        }
                        startActivity(intent)
                    }

                } catch (e: Exception) {
                    println("❌ 백그라운드 스레드 크래시: ${e.message}")
                    e.printStackTrace()
                    runOnUiThread {
                        hideLoadingOverlay()
                        Toast.makeText(this, "포즈 분석 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()

        } catch (e: Exception) {
            println("❌❌❌ processCapturedPhoto 최상위 크래시: ${e.message}")
            e.printStackTrace()
        }

        // ⭐ 촬영 종료 – UI 업데이트는 500ms 뒤에 허용
        Handler(Looper.getMainLooper()).postDelayed({
            isCapturing = false
        }, 500)


    }

    // ⭐ 로딩 오버레이 변수
    private var loadingOverlay: View? = null

    // ⭐ 로딩 오버레이 표시
    private fun showLoadingOverlay() {
        if (loadingOverlay != null) return

        val inflater = LayoutInflater.from(this)
        loadingOverlay = inflater.inflate(R.layout.loading_overlay, null)

        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(loadingOverlay)
    }

    // ⭐ 로딩 오버레이 숨기기
    private fun hideLoadingOverlay() {
        loadingOverlay?.let {
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            rootView.removeView(it)
            loadingOverlay = null
        }
    }

    // ⭐ 포즈 유사도 계산 (0.0 ~ 10.0) - 엄격한 버전
    private fun calculatePoseSimilarity(
        captured: RTMPoseResult,
        reference: RTMPoseResult
    ): Float {
        var totalDistance = 0f
        var count = 0

        val minSize = minOf(captured.keypoints.size, reference.keypoints.size)

        println("📊 키포인트 비교: captured=${captured.keypoints.size}, reference=${reference.keypoints.size}, 비교할 개수=$minSize")

        if (minSize == 0) {
            println("❌ 키포인트가 없음 → 점수 0.0")
            return 0.0f
        }

        // ⭐ 주요 신체 부위만 비교 (더 정확한 평가)
        val importantIndices = listOf(
            0,  // 코
            5, 6,  // 어깨
            7, 8,  // 팔꿈치
            9, 10,  // 손목
            11, 12,  // 엉덩이
            13, 14,  // 무릎
            15, 16   // 발목
        )

        for (i in importantIndices) {
            if (i >= minSize) continue

            val cap = captured.keypoints[i]
            val ref = reference.keypoints[i]

            // ⭐ 신뢰도 임계값 (0.5 이상만)
            if (cap.confidence < 0.5f || ref.confidence < 0.5f) continue

            val dx = cap.x - ref.x
            val dy = cap.y - ref.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            totalDistance += distance
            count++
        }

        println("📊 유효한 키포인트 쌍: ${count}개")

        // ⭐ 유효한 키포인트가 5개 미만이면 낮은 점수
        if (count < 5) {
            println("❌ 유효한 키포인트 부족 (${count}개) → 점수 1.0")
            return 1.0f
        }

        val avgDistance = totalDistance / count

        // ⭐ 거리 기반 점수 계산
        val normalizedDistance = (avgDistance / 0.5f).coerceIn(0f, 1f)
        val score = ((1f - normalizedDistance) * 10f).coerceIn(0f, 10f)

        println("📊 유사도 계산 완료: avgDistance=$avgDistance, score=$score")

        return score
    }

    // ⭐ 피드백 메시지 생성
    private fun generateFeedbackMessage(score: Float): String {
        return when {
            score >= 9.0f -> "완벽해요! 레퍼런스와 거의 동일한 포즈입니다!"
            score >= 7.0f -> "좋아요! 조금만 더 자세를 조정하면 완벽할 것 같아요."
            score >= 5.0f -> "카메라 셔터도를 약간 높이면서,\n가까운 여러 넓은면 더 괜찮은\n비슷한 이미지를 얻을 수 있습니다!"
            else -> "포즈를 다시 한번 확인해보세요.\n레퍼런스와 차이가 많이 나요."
        }
    }

    // ✅ 레퍼런스 이미지 선택 결과 처리
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_REFERENCE_IMAGE && resultCode == RESULT_OK) {
            val imageUri = data?.data
            if (imageUri != null) {
                handleReferenceImage(imageUri)
            } else {
                Toast.makeText(this, "레퍼런스 이미지를 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ✅ 레퍼런스 이미지 처리
    private fun handleReferenceImage(uri: Uri) {
        Thread {
            try {
                println("📸 레퍼런스 이미지 분석 중...")

                val bitmap = uriToBitmap(uri) ?: return@Thread
                referenceBitmap = bitmap

                if (!isAIInitialized || realtimeAnalyzer == null) {
                    runOnUiThread {
                        Toast.makeText(this, "AI 시스템이 아직 준비되지 않았습니다", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    realtimeAnalyzer?.analyzeReference(bitmap)

                    withContext(Dispatchers.Main) {
                        // ✅ 레퍼런스 설정 완료
                        isReferenceSet = true

                        Toast.makeText(this@MainActivity, "레퍼런스 포즈 설정 완료 ✅", Toast.LENGTH_SHORT)
                            .show()

                        // ✅ 피드백 UI 표시
                        showFeedbackUI()

                        // ✅ 실시간 분석 시작
                        startRealtimeAnalysis()

                        println("✅ 레퍼런스 설정 완료, 분석 모드 시작")
                    }
                }

            } catch (e: Exception) {
                println("❌ 레퍼런스 이미지 처리 실패: ${e.message}")
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "이미지 처리 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ✅ 레퍼런스 초기화 (필요시 호출)
    private fun clearReference() {
        isReferenceSet = false
        realtimeAnalyzer?.clearReference()
        stopRealtimeAnalysis()
        hideFeedbackUI()
        referenceBitmap?.recycle()
        referenceBitmap = null
        println("🛑 레퍼런스 해제, 분석 모드 종료")
    }

    // ✅ 실시간 포즈 분석 시작 - 더 안전한 버전
    private fun startRealtimeAnalysis() {
        if (!isReferenceSet) {
            println("⚠️ 레퍼런스가 설정되지 않아 분석을 시작하지 않습니다")
            return
        }

        if (!isAIInitialized || realtimeAnalyzer == null) {
            println("⚠️ AI 시스템이 준비되지 않음")
            return
        }

        if (realtimeAnalysisJob?.isActive == true) {
            println("⚠️ 실시간 분석이 이미 실행 중입니다")
            return
        }

        println("🎬 실시간 포즈 분석 시작")

        // ⭐ 분석 시작 시 초기 상태 표시
        runOnUiThread {
            setAllStatusGray()
            feedbackMessageContainer.visibility = View.VISIBLE
            feedbackMessage.text = "포즈 분석 중..."
        }

        realtimeAnalysisJob = lifecycleScope.launch(Dispatchers.Default) {
            var consecutiveFailures = 0  // ⭐ 연속 실패 카운터

            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()

                    // 분석 간격 300ms (약 3fps) - 안정성 우선
                    if (currentTime - lastAnalysisTime < 300L) {
                        delay(50)
                        continue
                    }
                    lastAnalysisTime = currentTime

                    if (!isReferenceSet) {
                        delay(100)
                        continue
                    }

                    // ✅ Main 스레드에서 안전하게 Bitmap 복사
                    val bitmap = withContext(Dispatchers.Main) {
                        getBitmapFromTextureViewSafely()
                    }

                    if (bitmap == null) {
                        consecutiveFailures++

                        // ⭐ 연속 3회 이상 실패하면 UI 업데이트
                        if (consecutiveFailures >= 3) {
                            withContext(Dispatchers.Main) {
                                setAllStatusGray()
                                feedbackMessageContainer.visibility = View.VISIBLE
                                feedbackMessage.text = "카메라 프리뷰 대기 중..."
                            }
                        }

                        delay(100)
                        continue
                    }

                    // ⭐ 백그라운드에서 분석 수행
                    try {
                        val isFront = controller.isFrontCamera()

                        realtimeAnalyzer?.analyzeFrame(
                            bitmap = bitmap,
                            isFrontCamera = isFront,
                            currentAspectRatio = CameraAspectRatio.RATIO_16_9
                        )

                        // ⭐ 분석 성공 시 실패 카운터 초기화
                        consecutiveFailures = 0

                    } catch (e: Exception) {
                        println("⚠️ 분석 중 오류: ${e.message}")
                        consecutiveFailures++

                        if (consecutiveFailures >= 3) {
                            withContext(Dispatchers.Main) {
                                setAllStatusGray()
                                feedbackMessageContainer.visibility = View.VISIBLE
                                feedbackMessage.text = "포즈 인식 실패"
                            }
                        }
                    }

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    println("⚠️ 실시간 분석 오류: ${e.message}")
                    consecutiveFailures++
                    delay(300)
                }
            }
            println("🛑 실시간 분석 루프 종료")
        }
    }

    // ✅ TextureView에서 안전하게 Bitmap 가져오기
    private fun getBitmapFromTextureViewSafely(): Bitmap? {
        return try {
            // TextureView 상태 체크
            if (!binding.textureView.isAvailable) {
                return null
            }

            val width = binding.textureView.width
            val height = binding.textureView.height

            if (width <= 0 || height <= 0) {
                return null
            }

            // ⭐ 축소된 크기로 직접 Bitmap 생성
            val scale = 0.4f
            val scaledWidth = (width * scale).toInt()
            val scaledHeight = (height * scale).toInt()

            if (scaledWidth <= 0 || scaledHeight <= 0) {
                return null
            }

            // ⭐ 새 Bitmap을 만들어서 TextureView 내용을 그림
            val bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            // 스케일 적용
            canvas.scale(scale, scale)

            // TextureView 내용을 canvas에 그림
            binding.textureView.getBitmap(bitmap)

            bitmap

        } catch (e: Exception) {
            println("⚠️ Bitmap 생성 실패: ${e.message}")
            null
        }
    }

    // ✅ 실시간 포즈 분석 중지
    private fun stopRealtimeAnalysis() {
        realtimeAnalysisJob?.cancel()
        realtimeAnalysisJob = null
        lastAnalysisTime = 0L
        println("🛑 실시간 포즈 분석 중지")
    }

    // ✅ 피드백 UI 업데이트 시작
    private fun startFeedbackUIUpdates() {
        lifecycleScope.launch {
            realtimeAnalyzer?.instantFeedback?.collectLatest { feedback ->
                // 레퍼런스가 설정된 경우에만 UI 업데이트
                if (isReferenceSet) {
                    updateFeedbackUI(feedback)
                }
            }
        }
    }

    // ✅ 피드백 UI 업데이트
    private fun updateFeedbackUI(feedback: List<FeedbackItem>) {

        if (isCapturing) return

        runOnUiThread {
            // 레퍼런스가 설정되지 않았으면 UI 숨김
            if (!isReferenceSet) {
                hideFeedbackUI()
                return@runOnUiThread
            }

            feedbackStatusContainer.visibility = View.VISIBLE

            if (feedback.isEmpty()) {
                // ⭐ 피드백이 비어있으면 = 모든 조건 만족 = 녹색
                feedbackMessageContainer.visibility = View.GONE
                setAllStatusGreen()
            } else {
                feedbackMessageContainer.visibility = View.VISIBLE
                val topFeedback = feedback.first()
                feedbackMessage.text = topFeedback.message
                updateStatusIcons(feedback)
                println("📢 피드백: [${topFeedback.category}] ${topFeedback.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        // 레퍼런스 모드 유지 확인
        if (intent?.getBooleanExtra("KEEP_REFERENCE_MODE", false) == true) {
            println("🔄 레퍼런스 모드로 복귀")
            // 레퍼런스가 설정되어 있으면 피드백 UI 표시
            if (isReferenceSet && isAIInitialized) {
                showFeedbackUI()
                binding.textureView.postDelayed({
                    startRealtimeAnalysis()
                }, 500)
            }
        }
    }

    private fun setAllStatusGreen() {
        val greenDrawable = resources.getDrawable(R.drawable.ic_select_checked, null)
        iconPose.setImageDrawable(greenDrawable)
        iconPosition.setImageDrawable(greenDrawable)
        iconFraming.setImageDrawable(greenDrawable)
        iconAngle.setImageDrawable(greenDrawable)
        iconComposition.setImageDrawable(greenDrawable)
        iconGaze.setImageDrawable(greenDrawable)
    }

    private fun updateStatusIcons(feedback: List<FeedbackItem>) {
        val greenDrawable = resources.getDrawable(R.drawable.ic_select_checked, null)
        val grayDrawable = resources.getDrawable(R.drawable.ic_select_empty, null)

        // 기본값: 모두 녹색
        iconPose.setImageDrawable(greenDrawable)
        iconPosition.setImageDrawable(greenDrawable)
        iconFraming.setImageDrawable(greenDrawable)
        iconAngle.setImageDrawable(greenDrawable)
        iconComposition.setImageDrawable(greenDrawable)
        iconGaze.setImageDrawable(greenDrawable)

        // 피드백이 있는 카테고리는 회색으로
        for (fb in feedback) {
            when {
                fb.category.contains("pose") -> iconPose.setImageDrawable(grayDrawable)
                fb.category.contains("position") || fb.category.contains("distance") -> iconPosition.setImageDrawable(
                    grayDrawable
                )

                fb.category.contains("framing") || fb.category.contains("headroom") -> iconFraming.setImageDrawable(
                    grayDrawable
                )

                fb.category.contains("angle") -> iconAngle.setImageDrawable(grayDrawable)
                fb.category.contains("composition") -> iconComposition.setImageDrawable(grayDrawable)
                fb.category.contains("gaze") || fb.category.contains("look") -> iconGaze.setImageDrawable(
                    grayDrawable
                )

                fb.category == "no_face" -> {
                    // ⭐ 얼굴 없음 = 모든 항목 미확인
                    iconPose.setImageDrawable(grayDrawable)
                    iconPosition.setImageDrawable(grayDrawable)
                    iconFraming.setImageDrawable(grayDrawable)
                    iconAngle.setImageDrawable(grayDrawable)
                    iconComposition.setImageDrawable(grayDrawable)
                    iconGaze.setImageDrawable(grayDrawable)
                }
            }
        }
    }

    // ---------------------------
    // Pinch zoom
    // ---------------------------
    private fun initPinchZoom() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    controller.onPinchScale(detector.scaleFactor)
                    return true
                }
            }
        )

        binding.overlayView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)

            if (ev.actionMasked == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                showTapEvSlider(ev.x, ev.y)
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRealtimeAnalysis()
        realtimeAnalyzer?.cleanup()
        poseEstimationService?.cleanup()
        referenceBitmap?.recycle()
        referenceBitmap = null
        isReferenceSet = false
        println("🧹 MainActivity 정리 완료")
    }

    // ---------------------------
    // 버튼들
    // ---------------------------
    private fun initButtons() {
        binding.btnShutter.setOnClickListener {
            isCapturing = true   // ⭐ 촬영 시작
            realtimeAnalysisJob?.cancel()
            realtimeAnalysisJob = null

            binding.btnShutter.postDelayed({
                controller.takePictureWithTimer()
            }, 150)
        }


        // ⭐ 전면/후면 카메라 전환 버튼 추가
        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()

            // ✅ 레퍼런스가 설정된 경우 분석 재시작
            if (isReferenceSet && isAIInitialized) {
                // 카메라 전환 후 약간의 딜레이를 주고 분석 재시작
                binding.textureView.postDelayed({
                    startRealtimeAnalysis()
                }, 500)
            }
        }

        binding.btnOptions.setOnClickListener {
            toggleOptionBar()
        }

        binding.btnCloseOption.setOnClickListener {
            toggleOptionBar()
        }

        binding.btnFlash.setOnClickListener {
            val next = when (controller.getFlashMode()) {
                Camera2Controller.FlashMode.OFF -> Camera2Controller.FlashMode.AUTO
                Camera2Controller.FlashMode.AUTO -> Camera2Controller.FlashMode.ON
                Camera2Controller.FlashMode.ON -> Camera2Controller.FlashMode.OFF
            }

            controller.setFlashMode(next)

            binding.btnFlash.setImageResource(
                when (next) {
                    Camera2Controller.FlashMode.OFF -> R.drawable.ic_flash_off
                    Camera2Controller.FlashMode.AUTO -> R.drawable.ic_flash_auto
                    Camera2Controller.FlashMode.ON -> R.drawable.ic_flash
                }
            )
        }

        binding.btnExp.setOnClickListener {
            showTapEvSliderCenter()
        }

        binding.btnTimer.setOnClickListener { toggleTimer() }

        binding.btnRatio.setOnClickListener { toggleAspectRatio() }

        binding.menuGallery.setOnClickListener {
            val intent = Intent(this, GalleryActivity::class.java)
            startActivity(intent)
        }

        binding.menuReference.setOnClickListener {
            val intent =
                Intent(this, com.example.camera2app.reference.ReferenceActivity::class.java)
            startActivityForResult(intent, REQUEST_REFERENCE_IMAGE)
        }
    }

    // ---------------------------
    // EV 슬라이더
    // ---------------------------
    private fun showTapEvSliderCenter() {
        // ✅ 분석 모드에서는 EV 슬라이더 열지 않음
        if (isReferenceSet) {
            return
        }

        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
            return
        }

        isAllAuto = false
        tapEvSlider = createTapEvSlider(0f, 0f)
        rootFrame.addView(tapEvSlider)
        tapEvSlider?.bringToFront()
    }

    private fun showTapEvSlider(x: Float, y: Float) {
        // ✅ 분석 모드에서는 EV 슬라이더 열지 않음
        if (isReferenceSet) {
            return
        }

        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
            return
        }
    }

    private fun toggleOptionBar() {
        optionVisible = !optionVisible

        if (optionVisible) {
            binding.btnOptions.visibility = View.GONE
            binding.optionBar.visibility = View.VISIBLE
            animateOptionBar(show = true)
        } else {
            animateOptionBar(show = false)
        }
    }

    private fun animateOptionBar(show: Boolean) {
        val view = binding.optionBar
        if (show) {
            view.alpha = 0f
            view.translationY = 40f
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .translationY(40f)
                .setDuration(200)
                .withEndAction {
                    view.visibility = View.GONE
                    binding.btnOptions.visibility = View.VISIBLE
                }
                .start()
        }
    }

    private fun createTapEvSlider(tapX: Float, tapY: Float): View {
        val container = FrameLayout(this)

        val sliderHeight = dp(280)
        val lineWidth = dp(3)
        val iconSize = dp(32)
        val containerWidth = dp(50)

        val padding = dp(16)
        val trackHeight = sliderHeight - iconSize - (padding * 2)
        val iconGap = dp(8)

        val topLine = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val topLineLp = FrameLayout.LayoutParams(lineWidth, 0).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = padding
        }
        container.addView(topLine, topLineLp)

        val bottomLine = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val bottomLineLp = FrameLayout.LayoutParams(lineWidth, 0).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = padding
        }
        container.addView(bottomLine, bottomLineLp)

        val sunIcon = ImageView(this).apply {
            setImageResource(R.drawable.clear_day)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = dp(4).toFloat()
        }
        val sunLp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = padding + (trackHeight / 2)
        }
        container.addView(sunIcon, sunLp)

        fun updateLines(iconTopMargin: Int) {
            val topLineHeight = iconTopMargin - padding - iconGap
            topLineLp.height = maxOf(0, topLineHeight)
            topLine.layoutParams = topLineLp

            val iconBottom = iconTopMargin + iconSize + iconGap
            val bottomLineHeight = sliderHeight - padding - iconBottom
            bottomLineLp.height = maxOf(0, bottomLineHeight)
            bottomLine.layoutParams = bottomLineLp
        }

        updateLines(sunLp.topMargin)

        val seek = SeekBar(this).apply {
            max = 800
            progress = 400
            rotation = -90f
            thumb = null
            progressDrawable = null
            background = null
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val ev = (p - 400) / 100.0
                controller.applyEv(ev)

                val ratio = 1f - (p / 800f)
                val newTopMargin = padding + (trackHeight * ratio).toInt()
                sunLp.topMargin = newTopMargin
                sunIcon.layoutParams = sunLp

                updateLines(newTopMargin)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val seekLp = FrameLayout.LayoutParams(sliderHeight, containerWidth).apply {
            gravity = Gravity.CENTER
        }
        container.addView(seek, seekLp)

        val lp = FrameLayout.LayoutParams(containerWidth, sliderHeight).apply {
            gravity = Gravity.END
            rightMargin = dp(16)
            val screenHeight = binding.previewContainer.height
            topMargin = (screenHeight - sliderHeight) / 2
        }

        container.layoutParams = lp
        container.isClickable = true
        container.isFocusable = true

        return container
    }

    // ---------------------------
    // Timer
    // ---------------------------
    private fun toggleTimer() {
        val mode = controller.cycleTimerMode()
        updateTimerIcon(mode)
    }

    private fun updateTimerIcon(mode: Camera2Controller.TimerMode) {
        val iconRes = when (mode) {
            Camera2Controller.TimerMode.OFF -> R.drawable.btn_timer
            Camera2Controller.TimerMode.SEC_3 -> R.drawable.ic_time_3s
            Camera2Controller.TimerMode.SEC_10 -> R.drawable.ic_time_10s
        }
        binding.btnTimer.setImageResource(iconRes)
    }

    // ---------------------------
    // Aspect Ratio
    // ---------------------------
    override fun onResume() {
        super.onResume()

        controller.onResume()

        // 🔥 분석모드 자동 복구
        if (referenceBitmap != null && isAIInitialized) {
            isReferenceSet = true
            showFeedbackUI()
            binding.textureView.postDelayed({
                startRealtimeAnalysis()
            }, 500)
        }
    }


    override fun onPause() {
        // ⭐ 먼저 분석 중지
        stopRealtimeAnalysis()
        controller.cancelTimer()
        controller.onPause()
        super.onPause()
    }

    private fun dp(i: Int) = (resources.displayMetrics.density * i + 0.5f).toInt()

    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            needs += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            needs += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        Permissions.requestIfNeeded(this, needs.toTypedArray())
    }

    private fun setAspectText(mode: Camera2Controller.AspectMode) {
        binding.btnRatio.text = when (mode) {
            Camera2Controller.AspectMode.RATIO_1_1 -> "1:1"
            Camera2Controller.AspectMode.RATIO_3_4 -> "4:3"
            Camera2Controller.AspectMode.RATIO_9_16 -> "16:9"
        }
    }

    // ---------------------------
    // 블러 효과
    // ---------------------------
    private fun setPreviewBlur(enabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (enabled) {
                binding.textureView.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        100f, 100f,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                )
            } else {
                binding.textureView.setRenderEffect(null)
            }
        } else {
            binding.textureView.alpha = if (enabled) 0.3f else 1f
        }
    }

    private fun toggleAspectRatio() {
        setPreviewBlur(true)

        val next = when (binding.btnRatio.text) {
            "1:1" -> "4:3"
            "4:3" -> "16:9"
            else -> "1:1"
        }
        binding.btnRatio.text = next
        controller.setAspectRatio(next)

        binding.textureView.postDelayed({
            setPreviewBlur(false)
        }, 300L)
    }

    private fun playAspectTransition(onMidpoint: () -> Unit) {
        val blurOverlay = binding.blurOverlay

        blurOverlay.alpha = 0f
        blurOverlay.visibility = View.VISIBLE
        blurOverlay.animate()
            .alpha(1f)
            .setDuration(150)
            .withEndAction {
                onMidpoint()

                blurOverlay.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .setStartDelay(50)
                    .withEndAction {
                        blurOverlay.visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }
}