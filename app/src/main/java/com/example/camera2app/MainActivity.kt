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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller
    private lateinit var scaleDetector: ScaleGestureDetector

    // ✅ AI 시스템 (OnnxInferenceManager → PoseEstimationService + RealtimeAnalyzer)
    private var poseEstimationService: PoseEstimationService? = null
    private var realtimeAnalyzer: RealtimeAnalyzer? = null
    private var isAIInitialized = false

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

        // ✅ AI 시스템 초기화
        initAISystem()

        initPinchZoom()
        initButtons()
        requestPermissionsIfNeeded()

        setAspectText(Camera2Controller.AspectMode.RATIO_9_16)
    }

    // ✅ AI 시스템 초기화
    private fun initAISystem() {
        Thread {
            try {
                println("========================================")
                println("🤖 AI 시스템 초기화 시작")
                println("========================================")

                val startTime = System.currentTimeMillis()

                // 1. PoseEstimationService 초기화 (2-3초 소요)
                poseEstimationService = PoseEstimationService(applicationContext)
                poseEstimationService?.initialize()

                // 2. RealtimeAnalyzer 초기화 (✅ poseEstimationService 전달!)
                realtimeAnalyzer = RealtimeAnalyzer(poseEstimationService!!)

                val elapsed = System.currentTimeMillis() - startTime
                isAIInitialized = true

                runOnUiThread {
                    println("========================================")
                    println("✅ AI 시스템 초기화 완료 (${elapsed}ms)")
                    println("========================================")
                    Toast.makeText(this, "✅ AI 시스템 준비 완료", Toast.LENGTH_SHORT).show()

                    // ✅ 피드백 UI 업데이트 시작
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
                // ✅ 촬영 완료 → 포즈 분석
                val bitmap = uriToBitmap(uri)
                if (bitmap != null) {
                    processCapturedPhoto(bitmap)
                }
            },
            previewContainer = binding.previewContainer
        ) { fps ->
            runOnUiThread {
                binding.fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
            }
        }

        // ★ 타이머 카운트다운 콜백 설정
        controller.setTimerCountdownCallback { remaining ->
            runOnUiThread {
                if (remaining > 0) {
                    binding.timerCountdownText.text = remaining.toString()
                    binding.timerCountdownText.visibility = View.VISIBLE
                    // 애니메이션 효과
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
                android.graphics.ImageDecoder.decodeBitmap(source)
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
    private fun processCapturedPhoto(bitmap: Bitmap) {
        if (!isAIInitialized || poseEstimationService == null) {
            runOnUiThread {
                Toast.makeText(this, "AI 시스템 로딩 중...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        Thread {
            try {
                println("📸 촬영 사진 분석 중...")
                val result = poseEstimationService?.detectPose(bitmap)

                if (result == null) {
                    runOnUiThread {
                        Toast.makeText(this, "포즈를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                println("✅ 포즈 검출 완료: ${result.keypoints.size}개 키포인트")

                val highConf = result.keypoints.count { it.confidence > 0.5f }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "포즈 감지: ${highConf}개 키포인트 (신뢰도 >0.5)",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "포즈 분석 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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

                // URI → Bitmap
                val bitmap = uriToBitmap(uri) ?: return@Thread
                referenceBitmap = bitmap

                if (!isAIInitialized || realtimeAnalyzer == null) {
                    runOnUiThread {
                        Toast.makeText(this, "AI 시스템이 아직 준비되지 않았습니다", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // ✅ analyzeReference는 suspend 함수이므로 코루틴 사용
                lifecycleScope.launch(Dispatchers.IO) {
                    realtimeAnalyzer?.analyzeReference(bitmap)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "레퍼런스 포즈 설정 완료 ✅", Toast.LENGTH_SHORT).show()

                        // ✅ 실시간 분석 시작
                        startRealtimeAnalysis()
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

    // ✅ 실시간 포즈 분석 시작
    private fun startRealtimeAnalysis() {
        if (realtimeAnalysisJob?.isActive == true) {
            println("⚠️ 실시간 분석이 이미 실행 중입니다")
            return
        }

        println("🎬 실시간 포즈 분석 시작")

        realtimeAnalysisJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()

                    // 프레임 스킵 (30fps 유지)
                    if (currentTime - lastAnalysisTime < analysisIntervalMs) {
                        delay(10)
                        continue
                    }
                    lastAnalysisTime = currentTime

                    // TextureView에서 Bitmap 추출
                    val bitmap = withContext(Dispatchers.Main) {
                        binding.textureView.bitmap
                    } ?: continue

                    // ✅ analyzeFrame() 호출 (비-suspend 함수)
                    realtimeAnalyzer?.analyzeFrame(
                        bitmap = bitmap,
                        isFrontCamera = false,  // TODO: 카메라 상태에 따라 변경
                        currentAspectRatio = CameraAspectRatio.RATIO_16_9  // TODO: 현재 비율
                    )

                    // 메모리 정리
                    bitmap.recycle()

                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        println("⚠️ 실시간 분석 오류: ${e.message}")
                    }
                }
            }
        }
    }

    // ✅ 실시간 포즈 분석 중지
    private fun stopRealtimeAnalysis() {
        realtimeAnalysisJob?.cancel()
        realtimeAnalysisJob = null
        println("🛑 실시간 포즈 분석 중지")
    }

    // ✅ 피드백 UI 업데이트
    private fun startFeedbackUIUpdates() {
        lifecycleScope.launch {
            // ✅ instantFeedback 사용!
            realtimeAnalyzer?.instantFeedback?.collectLatest { feedback ->
                updateFeedbackUI(feedback)
            }
        }
    }

    private fun updateFeedbackUI(feedback: List<FeedbackItem>) {
        if (feedback.isEmpty()) {
            // 피드백 없음 → UI 숨기기
            binding.overlayView.visibility = View.VISIBLE  // overlayView는 항상 표시 (줌, EV 등)
            return
        }

        // 가장 우선순위 높은 피드백 1개만 표시
        val topFeedback = feedback.firstOrNull() ?: return

        // TODO: 실제 UI 컴포넌트로 표시
        // 현재는 임시로 로그 출력
        println("📢 피드백: [${topFeedback.category}] ${topFeedback.message}")

        // 예시: Toast로 표시 (실제로는 Custom View 사용)
        // runOnUiThread {
        //     Toast.makeText(this, topFeedback.message, Toast.LENGTH_SHORT).show()
        // }
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
        // ✅ AI 시스템 리소스 정리
        stopRealtimeAnalysis()
        realtimeAnalyzer?.cleanup()
        poseEstimationService?.cleanup()
        referenceBitmap?.recycle()
        referenceBitmap = null
        println("🧹 MainActivity 정리 완료")
    }

    // ---------------------------
    // 버튼들
    // ---------------------------
    private fun initButtons() {
        // 촬영
        binding.btnShutter.setOnClickListener { controller.takePictureWithTimer() }

        // 카메라 전환
        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()
            controller.setFlashMode(Camera2Controller.FlashMode.OFF)
        }

        // ★ 옵션 버튼
        binding.btnOptions.setOnClickListener {
            toggleOptionBar()
        }

        // ★ 옵션 닫기
        binding.btnCloseOption.setOnClickListener {
            toggleOptionBar()
        }

        // 플래시
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

        // EXP
        binding.btnExp.setOnClickListener {
            showTapEvSliderCenter()
        }

        // Timer
        binding.btnTimer.setOnClickListener { toggleTimer() }

        // ratio 변경
        binding.btnRatio.setOnClickListener { toggleAspectRatio() }

        // 갤러리
        binding.menuGallery.setOnClickListener {
            val intent = Intent(this, GalleryActivity::class.java)
            startActivity(intent)
        }

        // ✅ 레퍼런스 (이미지 선택 Activity 실행)
        binding.menuReference.setOnClickListener {
            val intent = Intent(this, com.example.camera2app.reference.ReferenceActivity::class.java)
            startActivityForResult(intent, REQUEST_REFERENCE_IMAGE)
        }
    }

    // ---------------------------
    // EV 슬라이더
    // ---------------------------
    private fun showTapEvSliderCenter() {
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

        // 위쪽 라인
        val topLine = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val topLineLp = FrameLayout.LayoutParams(lineWidth, 0).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = padding
        }
        container.addView(topLine, topLineLp)

        // 아래쪽 라인
        val bottomLine = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val bottomLineLp = FrameLayout.LayoutParams(lineWidth, 0).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = padding
        }
        container.addView(bottomLine, bottomLineLp)

        // 태양 아이콘
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

        // SeekBar
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
        controller.setAllAuto()
        isAllAuto = true

        // ✅ 실시간 분석 재시작 (레퍼런스가 설정되어 있으면)
        if (realtimeAnalyzer?.referenceAnalysis != null && isAIInitialized) {
            startRealtimeAnalysis()
        }
    }

    override fun onPause() {
        controller.cancelTimer()
        controller.onPause()

        // ✅ 실시간 분석 일시 중지
        stopRealtimeAnalysis()

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
        // 블러 ON
        setPreviewBlur(true)

        // 비율 전환
        val next = when (binding.btnRatio.text) {
            "1:1" -> "4:3"
            "4:3" -> "16:9"
            else -> "1:1"
        }
        binding.btnRatio.text = next
        controller.setAspectRatio(next)

        // 0.3초 후 블러 해제
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