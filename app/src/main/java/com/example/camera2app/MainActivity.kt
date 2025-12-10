package com.example.camera2app

import android.provider.MediaStore
import android.Manifest
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.example.camera2app.ai.RTMPoseEstimator
import com.example.camera2app.ai.YoloXDetector
import com.example.camera2app.camera.Camera2Controller
import com.example.camera2app.databinding.ActivityMainBinding
import com.example.camera2app.gallery.GalleryActivity
import com.example.camera2app.util.Permissions
import kotlinx.coroutines.*
import java.util.*
import android.util.Log
import android.content.pm.PackageManager
import com.example.camera2app.gallery.FeedbackScoreActivity
import com.example.camera2app.ai.TryAngleOnDeviceAnalyzer
import com.example.camera2app.ai.GateSystem
import com.example.camera2app.ai.TryAngleFeedback
import com.example.camera2app.ai.GroundingDinoONNX





class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller

    // ✅ AI 시스템
    private lateinit var yoloxDetector: YoloXDetector
    private lateinit var poseEstimator: RTMPoseEstimator
    private var isAIInitialized = false

    // ✅ v1.5 온디바이스 통합 분석기
    private lateinit var tryAngleAnalyzer: TryAngleOnDeviceAnalyzer
    // ✅ Grounding DINO
    private lateinit var groundingDino: GroundingDinoONNX




    // ✅ 마지막 캡쳐
    private var lastCapturedBitmap: Bitmap? = null
    private var lastCapturedUri: Uri? = null

    // ✅ 로딩 오버레이
    private var loadingOverlay: View? = null

    // ✅ EV 슬라이더 / 옵션바
    private var tapEvSlider: View? = null
    private lateinit var rootFrame: FrameLayout
    private var isAllAuto = true
    private var optionVisible = false

    private var referenceUri: Uri? = null
    private var referenceBitmap: Bitmap? = null

    private var isReferenceMode = false

    private var isAnalyzing = false




    companion object {
        var aiAnalysisStarted = false
        val feedbackMap: MutableMap<String, TryAngleFeedback> = mutableMapOf()

        const val REQUEST_REFERENCE_IMAGE = 2001
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 루트 뷰 (EV 슬라이더 붙일 곳)
        rootFrame = findViewById(android.R.id.content)

        // 기본 비율 텍스트
        binding.btnRatio.text = "16:9"

        initAISystem()
        initButtons()

        // ✅ 반드시 권한부터 요청
        requestPermissionsIfNeeded()

        // ✅ TextureView 준비되었을 때만 카메라 열기
        binding.textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    if (
                        checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        initCameraController()   // ✅ 권한 있을 때만 실행
                    }
                }


                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }


        // ✅ 앱 최초 실행 시 → 피드백 UI 전부 숨김
        binding.feedbackMessageContainer.visibility = View.GONE
        binding.feedbackStatusContainer.visibility = View.GONE
        isReferenceMode = false





    }

    // ----------------------------------------------------
    // 권한 콜백
    // ----------------------------------------------------
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (binding.textureView.isAvailable) {
                initCameraController()
            }
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show()
        }
    }

    // ----------------------------------------------------
    // AI 초기화 (YOLOX + RTMPose)
    // ----------------------------------------------------
    private fun initAISystem() {
        Thread {
            try {
                val start = System.currentTimeMillis()

                yoloxDetector = YoloXDetector(this)
                poseEstimator = RTMPoseEstimator(this)

//                groundingDino = GroundingDinoONNX(this)
                Log.e("DINO", "✅ Grounding DINO ONNX 초기화 완료")


                tryAngleAnalyzer = TryAngleOnDeviceAnalyzer(
                    context = this,
                    enableLegacySystem = false
                )

                Log.e("REALTIME_AI", "✅ tryAngleAnalyzer 초기화 완료")
                isAIInitialized = true

                val time = System.currentTimeMillis() - start

                runOnUiThread {
                    Toast.makeText(this, "✅ AI 로딩 완료 (${time}ms)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "❌ AI 로딩 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ----------------------------------------------------
    // 카메라 컨트롤러
    // ----------------------------------------------------
    private fun initCameraController() {
        if (::controller.isInitialized) return   // ✅ 이거 없으면 무조건 또 터진다
        Log.e("CAMERA_FLOW", "✅ initCameraController() 진입")

        controller = Camera2Controller(
            context = this,
            overlayView = binding.overlayView,
            textureView = binding.textureView,
            onFrameLevelChanged = {},
            onSaved = { uri ->
                val bmp = uriToBitmap(uri)
                if (bmp != null) {
                    lastCapturedBitmap = bmp
                    lastCapturedUri = uri

                    runOnUiThread {
                        binding.lastThumbnail.setImageBitmap(bmp)
                        binding.lastThumbnail.visibility = View.VISIBLE
                    }

                    lifecycleScope.launch {
                        delay(500)

                        if (isReferenceMode.not()) return@launch   // ✅ 레퍼런스 모드 아닐 땐 자동 분석 금지
                        if (bmp.isRecycled) return@launch

                        processCapturedPhotoV15(bmp, uri)
                    }


                }
            },
            previewContainer = binding.previewContainer
        ) { fps ->
            runOnUiThread {
                binding.fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
            }
        }

        // ✅ 타이머 카운트다운 (3s / 10s)
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

        // ✅ 여기서 프리뷰 시작
        controller.onResume()
    }

    // ----------------------------------------------------
    // 촬영 → AI 분석
    // ----------------------------------------------------

    private fun processCapturedPhotoV15(bitmap: Bitmap, uri: Uri) {
        MainActivity.aiAnalysisStarted = true


        if (isAnalyzing) return
        isAnalyzing = true

        if (bitmap.isRecycled) {
            Toast.makeText(this, "이미지가 손상되었습니다", Toast.LENGTH_SHORT).show()
            isAnalyzing = false
            return
        }

        if (!isAIInitialized) {
            Toast.makeText(this, "AI 로딩 중...", Toast.LENGTH_SHORT).show()
            isAnalyzing = false
            return
        }

        showLoadingOverlay()



        try {
            tryAngleAnalyzer.analyzeFrame(bitmap) { feedback ->

                // ✅ ✅ ✅ 메인에서 직접 저장 (FeedbackCache 완전 대체)
                MainActivity.feedbackMap[uri.toString()] = feedback

                runOnUiThread {
                    try {
                        hideLoadingOverlay()

                        val gateEvaluation = try {
                            GateSystem.fromFeedback(feedback)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }

                        val finalScore =
                            if (gateEvaluation == null || feedback.isPersonDetected == false)
                                1.0f
                            else
                                (gateEvaluation.overallScore * 10f).coerceIn(1f, 10f)

                        val finalMessage =
                            feedback.primary ?: "분석 결과를 불러올 수 없습니다"

                        val intent = Intent(
                            this,
                            FeedbackScoreActivity::class.java
                        ).apply {
                            putExtra(
                                FeedbackScoreActivity.EXTRA_CAPTURED_URI,
                                uri.toString()
                            )
                            putExtra(
                                FeedbackScoreActivity.EXTRA_REFERENCE_URI,
                                referenceUri?.toString()   // ✅ null-safe
                            )
                            putExtra(
                                FeedbackScoreActivity.EXTRA_SCORE,
                                finalScore
                            )
                            putExtra(
                                FeedbackScoreActivity.EXTRA_FEEDBACK_MESSAGE,
                                finalMessage
                            )
                        }

                        startActivity(intent)

                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(
                            this,
                            "분석 처리 중 오류 발생",
                            Toast.LENGTH_SHORT
                        ).show()
                    } finally {
                        isAnalyzing = false
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            hideLoadingOverlay()
            isAnalyzing = false
        }
    }






    fun showThumbnailInstant(bitmap: Bitmap) {
        runOnUiThread {
            binding.lastThumbnail.setImageBitmap(bitmap)
        }
    }



    // ✅ 포즈 유사도 계산
    private fun calculatePoseSimilarity(
        p1: List<Pair<PointF, Float>>,
        p2: List<Pair<PointF, Float>>
    ): Float {
        val minSize = minOf(p1.size, p2.size)
        if (minSize == 0) return 0f

        var total = 0f
        var count = 0

        for (i in 0 until minSize) {
            val a = p1[i]
            val b = p2[i]

            if (a.second < 0.5f || b.second < 0.5f) continue

            val dx = a.first.x - b.first.x
            val dy = a.first.y - b.first.y
            total += kotlin.math.sqrt(dx * dx + dy * dy)
            count++
        }

        if (count == 0) return 0f

        val avg = total / count
        val norm = (avg / 300f).coerceIn(0f, 1f)
        return ((1f - norm) * 10f).coerceIn(0f, 10f)
    }

    private fun generateFeedbackMessage(score: Float): String {
        return when {
            score >= 9f -> "완벽해요!"
            score >= 7f -> "거의 일치해요!"
            score >= 5f -> "조금 더 맞춰보세요!"
            else -> "포즈 차이가 큽니다"
        }
    }

    private var lastRealtimeAnalyzeTime = 0L

    fun analyzeRealtimeFrame(bitmap: Bitmap) {
        if (!isReferenceMode) return
        if (!isAIInitialized) return

        val now = System.currentTimeMillis()
        if (now - lastRealtimeAnalyzeTime < 400) return
        lastRealtimeAnalyzeTime = now

        // ✅ ✅ ✅ 1단계: Grounding DINO (사람 존재 확인용)
        if (!groundingDino.isBusy()) {
            groundingDino.detectOneAsync(bitmap) { dinoBox ->

                // 사람 없으면 포즈 분석도 하지 않음 (부하 차단)
                if (dinoBox == null) {
                    runOnUiThread {
                        binding.feedbackMessageContainer.visibility = View.VISIBLE
                        binding.feedbackMessage.text = "사람이 인식되지 않습니다"
                    }
                    return@detectOneAsync
                }

                // ✅ ✅ ✅ 2단계: 기존 TryAngle 분석
                tryAngleAnalyzer.analyzeFrame(bitmap) { feedback ->

                    runOnUiThread {
                        val score = feedback.compressionInfo?.index ?: 0f

                        binding.feedbackMessageContainer.visibility = View.VISIBLE
                        binding.feedbackMessage.text = feedback.primary

                        val personDetected = score > 1f

                        binding.iconPose.setImageResource(
                            if (personDetected) R.drawable.ic_select_checked
                            else R.drawable.ic_select_empty
                        )

                        binding.iconComposition.setImageResource(
                            if (personDetected) R.drawable.ic_select_checked
                            else R.drawable.ic_select_empty
                        )
                    }
                }
            }
        }
    }






    // ----------------------------------------------------
    // 버튼들 (UI 전체)
    // ----------------------------------------------------
    private fun initButtons() {
        // 촬영 버튼 (타이머 포함)
        binding.btnShutter.setOnClickListener {
            controller.takePictureWithTimer()
        }

        // 옵션바 열기 / 닫기
        binding.btnOptions.setOnClickListener { toggleOptionBar() }
        binding.btnCloseOption.setOnClickListener { toggleOptionBar() }

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

        // EV 슬라이더 버튼
        binding.btnExp.setOnClickListener {
            showTapEvSliderCenter()
        }

        // 타이머
        binding.btnTimer.setOnClickListener {
            toggleTimer()
        }

        // 비율 변경
        binding.btnRatio.setOnClickListener {
            toggleAspectRatio()
        }

        // 카메라 전/후면 전환
        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()
        }

        // 레퍼런스 선택
        binding.menuReference.setOnClickListener {
            val intent =
                Intent(this, com.example.camera2app.reference.ReferenceActivity::class.java)
            startActivityForResult(intent, REQUEST_REFERENCE_IMAGE)
        }

        // 마지막 썸네일 → 분석
        binding.lastThumbnail.setOnClickListener {

            val bmp = lastCapturedBitmap
            val uri = lastCapturedUri

            if (bmp == null || uri == null) {
                Toast.makeText(this, "아직 저장되지 않았습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (bmp.isRecycled) {
                Toast.makeText(this, "이미지가 손상되었습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            processCapturedPhotoV15(bmp, uri)
        }



        // 갤러리
        binding.menuGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }



    // ----------------------------------------------------
    // 레퍼런스 처리
    // ----------------------------------------------------
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_REFERENCE_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return

            referenceUri = uri
            referenceBitmap = uriToBitmap(uri)

            // ✅ 레퍼런스 모드 ON
            isReferenceMode = true

            // ✅ 피드백 UI 활성화
            binding.feedbackMessageContainer.visibility = View.VISIBLE
            binding.feedbackStatusContainer.visibility = View.VISIBLE
            binding.feedbackMessage.text = "얼굴을 화면에 보여주세요"

            Toast.makeText(this, "✅ 레퍼런스 설정 완료", Toast.LENGTH_SHORT).show()
        }
    }




    // Bitmap 로딩
    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // ✅ 핵심
                    decoder.isMutableRequired = true                     // ✅ 핵심
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    // ----------------------------------------------------
    // 로딩 오버레이
    // ----------------------------------------------------
    private fun showLoadingOverlay() {
        if (loadingOverlay != null) return
        loadingOverlay = layoutInflater.inflate(R.layout.loading_overlay, null)
        findViewById<ViewGroup>(android.R.id.content).addView(loadingOverlay)
    }

    private fun hideLoadingOverlay() {
        loadingOverlay?.let {
            findViewById<ViewGroup>(android.R.id.content).removeView(it)
            loadingOverlay = null
        }
    }

    // ----------------------------------------------------
    // 권한
    // ----------------------------------------------------
    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            needs += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            needs += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        Permissions.requestIfNeeded(this, needs.toTypedArray())
    }

    // ----------------------------------------------------
    // EV 슬라이더 / 옵션바 / 타이머 / 비율
    // ----------------------------------------------------
    private fun dp(i: Int) = (resources.displayMetrics.density * i + 0.5f).toInt()

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

    // 중앙에 EV 슬라이더 표시
    private fun showTapEvSliderCenter() {
        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
            isAllAuto = true
            controller.applyEv(0.0)   // 다시 0으로
            return
        }

        isAllAuto = false
        tapEvSlider = createTapEvSlider()
        rootFrame.addView(tapEvSlider)
        tapEvSlider?.bringToFront()
    }

    // 실제 슬라이더 View 생성
    private fun createTapEvSlider(): View {
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

    // 타이머 모드
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

    // 비율 변경 + 블러 효과
    private fun setPreviewBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                binding.textureView.setRenderEffect(
                    RenderEffect.createBlurEffect(
                        100f, 100f,
                        Shader.TileMode.CLAMP
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

    // ----------------------------------------------------
    // 라이프사이클
    // ----------------------------------------------------
    override fun onResume() {
        super.onResume()

        if (binding.textureView.isAvailable && ::controller.isInitialized) {
            binding.textureView.post {
                controller.onResume()
            }
        }
    }




    override fun onPause() {
        if (::controller.isInitialized) {
            controller.cancelTimer()
            controller.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        lastCapturedBitmap?.recycle()
        lastCapturedBitmap = null
    }
}
