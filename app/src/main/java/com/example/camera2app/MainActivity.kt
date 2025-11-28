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
        initFeedbackUI()
        requestPermissionsIfNeeded()

        setAspectText(Camera2Controller.AspectMode.RATIO_9_16)
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

    // ✅ 피드백 UI 보이기
    private fun showFeedbackUI() {
        feedbackStatusContainer.visibility = View.VISIBLE
        // ✅ 분석 모드에서 옵션 버튼 숨기기
        binding.btnOptions.visibility = View.GONE
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

                    // ✅ 피드백 UI 업데이트 리스너 시작
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
                    processCapturedPhoto(bitmap)
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

                        Toast.makeText(this@MainActivity, "레퍼런스 포즈 설정 완료 ✅", Toast.LENGTH_SHORT).show()

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



    // ✅ 실시간 포즈 분석 시작
    private fun startRealtimeAnalysis() {
        // 레퍼런스가 설정되지 않았으면 시작하지 않음
        if (!isReferenceSet) {
            println("⚠️ 레퍼런스가 설정되지 않아 분석을 시작하지 않습니다")
            return
        }

        if (realtimeAnalysisJob?.isActive == true) {
            println("⚠️ 실시간 분석이 이미 실행 중입니다")
            return
        }

        println("🎬 실시간 포즈 분석 시작")

        realtimeAnalysisJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()

                    if (currentTime - lastAnalysisTime < analysisIntervalMs) {
                        delay(10)
                        continue
                    }
                    lastAnalysisTime = currentTime

                    // ✅ Main 스레드에서 안전하게 Bitmap 복사
                    val bitmap = withContext(Dispatchers.Main) {
                        try {
                            val original = binding.textureView.bitmap
                            if (original != null && !original.isRecycled && original.width > 0 && original.height > 0) {
                                // ARGB_8888로 복사 (color space 문제 해결)
                                Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888).also { copy ->
                                    val canvas = android.graphics.Canvas(copy)
                                    canvas.drawBitmap(original, 0f, 0f, null)
                                }
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            println("⚠️ Bitmap 복사 실패: ${e.message}")
                            null
                        }
                    }

                    if (bitmap == null) {
                        delay(50)
                        continue
                    }

                    val isFront = controller.isFrontCamera()

                    realtimeAnalyzer?.analyzeFrame(
                        bitmap = bitmap,
                        isFrontCamera = isFront,
                        currentAspectRatio = CameraAspectRatio.RATIO_16_9
                    )

                    // 분석 완료 후 recycle
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }

                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        println("⚠️ 실시간 분석 오류: ${e.message}")
                    }
                    delay(100)
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
        runOnUiThread {
            // 레퍼런스가 설정되지 않았으면 UI 숨김
            if (!isReferenceSet) {
                hideFeedbackUI()
                return@runOnUiThread
            }

            feedbackStatusContainer.visibility = View.VISIBLE

            if (feedback.isEmpty()) {
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
                fb.category.contains("position") || fb.category.contains("distance") -> iconPosition.setImageDrawable(grayDrawable)
                fb.category.contains("framing") || fb.category.contains("headroom") -> iconFraming.setImageDrawable(grayDrawable)
                fb.category.contains("angle") -> iconAngle.setImageDrawable(grayDrawable)
                fb.category.contains("composition") -> iconComposition.setImageDrawable(grayDrawable)
                fb.category.contains("gaze") || fb.category.contains("look") -> iconGaze.setImageDrawable(grayDrawable)
                fb.category == "no_face" -> {
                    iconPose.setImageDrawable(grayDrawable)
                    iconPosition.setImageDrawable(grayDrawable)
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
        binding.btnShutter.setOnClickListener { controller.takePictureWithTimer() }

        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()
            controller.setFlashMode(Camera2Controller.FlashMode.OFF)
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
            val intent = Intent(this, com.example.camera2app.reference.ReferenceActivity::class.java)
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
        controller.setAllAuto()
        isAllAuto = true

        // ✅ 레퍼런스가 설정된 경우에만 분석 시작
        if (isReferenceSet && isAIInitialized) {
            showFeedbackUI()
            startRealtimeAnalysis()
        }
    }

    override fun onPause() {
        controller.cancelTimer()
        controller.onPause()
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