package com.example.camera2app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.example.camera2app.camera.Camera2Controller
import com.example.camera2app.databinding.ActivityMainBinding
import com.example.camera2app.gallery.GalleryActivity
import com.example.camera2app.util.Permissions
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller
    private lateinit var scaleDetector: ScaleGestureDetector

    // EV 슬라이더
    private var tapEvSlider: View? = null
    private lateinit var rootFrame: FrameLayout
    private var isAllAuto = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rootFrame = findViewById(android.R.id.content)

        applyWindowInset()
        initCameraController()
        initPinchZoom()
        initButtons()
        requestPermissionsIfNeeded()

        setAspectText(Camera2Controller.AspectMode.RATIO_9_16)


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
            onSaved = {},
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

    // ---------------------------
    // 버튼들
    // ---------------------------
    private fun initButtons() {

        // 촬영 - takePicture() → takePictureWithTimer()
        binding.btnShutter.setOnClickListener { controller.takePictureWithTimer() }

        // 카메라 전환
        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()
            controller.setFlashMode(Camera2Controller.FlashMode.OFF)
        }

        // ★ 옵션 버튼 (점 6개) - 옵션바 열기
        binding.btnOptions.setOnClickListener {
            toggleOptionBar()
        }

        // ★ 옵션 닫기 (X 버튼) - 옵션바 닫기
        binding.btnCloseOption.setOnClickListener {
            toggleOptionBar()
        }

        // 플래시 (OFF → AUTO → ON → OFF)
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

        // EXP → EV 슬라이더 중앙 오픈
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

        // 레퍼런스
        binding.menuReference.setOnClickListener {
            startActivity(Intent(this, com.example.camera2app.reference.ReferenceActivity::class.java))
        }
    }

    // ---------------------------
    // EV 슬라이더 (EXP or tap)
    // ---------------------------

    private fun showTapEvSliderCenter() {
        // 이미 열려있으면 닫기
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
        // 프리뷰 탭하면 슬라이더 닫기
        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
            return
        }

        // EXP 버튼으로만 열리게 하려면 여기서 return
        // 탭으로도 열고 싶으면 아래 코드 활성화
        /*
        isAllAuto = false
        tapEvSlider = createTapEvSlider(x, y)
        rootFrame.addView(tapEvSlider)
        tapEvSlider?.bringToFront()
        */
    }

    private var optionVisible = false

    private fun toggleOptionBar() {
        optionVisible = !optionVisible

        if (optionVisible) {
            // 옵션바 열기: 옵션 버튼 숨기고, 옵션바 표시
            binding.btnOptions.visibility = View.GONE
            binding.optionBar.visibility = View.VISIBLE
            animateOptionBar(show = true)
        } else {
            // 옵션바 닫기: 옵션바 숨기고, 옵션 버튼 표시
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
                    // ★ 옵션바 닫힐 때 옵션 버튼 다시 표시
                    binding.btnOptions.visibility = View.VISIBLE
                }
                .start()
        }
    }


    private fun createTapEvSlider(tapX: Float, tapY: Float): View {
        val container = FrameLayout(this)

        // 전체 슬라이더 높이
        val sliderHeight = dp(280)
        val lineWidth = dp(3)
        val iconSize = dp(32)
        val containerWidth = dp(50)

        // 아이콘 이동 가능 범위 (위아래 여백 제외)
        val padding = dp(16)
        val trackHeight = sliderHeight - iconSize - (padding * 2)
        val iconGap = dp(8)  // 아이콘 양옆 빈 공간

        // ─────────────────────────────────────────
        // 1) 위쪽 라인
        // ─────────────────────────────────────────
        val topLine = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val topLineLp = FrameLayout.LayoutParams(lineWidth, 0).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = padding
        }
        container.addView(topLine, topLineLp)

        // ─────────────────────────────────────────
        // 2) 아래쪽 라인
        // ─────────────────────────────────────────
        val bottomLine = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val bottomLineLp = FrameLayout.LayoutParams(lineWidth, 0).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = padding
        }
        container.addView(bottomLine, bottomLineLp)

        // ─────────────────────────────────────────
        // 3) 태양 아이콘 (위아래로 움직임, 줄 위에 표시)
        // ─────────────────────────────────────────
        val sunIcon = ImageView(this).apply {
            setImageResource(R.drawable.clear_day)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = dp(4).toFloat()  // 줄 위에 표시
        }
        val sunLp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = padding + (trackHeight / 2)  // 초기 위치: 중앙
        }
        container.addView(sunIcon, sunLp)

        // 라인 높이 업데이트 함수
        fun updateLines(iconTopMargin: Int) {
            // 위쪽 라인: padding부터 아이콘 위까지
            val topLineHeight = iconTopMargin - padding - iconGap
            topLineLp.height = maxOf(0, topLineHeight)
            topLine.layoutParams = topLineLp

            // 아래쪽 라인: 아이콘 아래부터 끝까지
            val iconBottom = iconTopMargin + iconSize + iconGap
            val bottomLineHeight = sliderHeight - padding - iconBottom
            bottomLineLp.height = maxOf(0, bottomLineHeight)
            bottomLine.layoutParams = bottomLineLp
        }

        // 초기 라인 높이 설정
        updateLines(sunLp.topMargin)

        // ─────────────────────────────────────────
        // 4) 투명 SeekBar (터치 영역)
        // ─────────────────────────────────────────
        val seek = SeekBar(this).apply {
            max = 800
            progress = 400  // 중앙 = EV 0
            rotation = -90f

            // 투명하게
            thumb = null
            progressDrawable = null
            background = null
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                // p: 0~800, 중앙 400 = EV 0
                val ev = (p - 400) / 100.0
                controller.applyEv(ev)

                // 아이콘 위치 업데이트
                // p=0 → 맨 아래 (어두움), p=800 → 맨 위 (밝음)
                val ratio = 1f - (p / 800f)  // 0~1 (위에서 아래로)
                val newTopMargin = padding + (trackHeight * ratio).toInt()
                sunLp.topMargin = newTopMargin
                sunIcon.layoutParams = sunLp

                // 라인 높이 업데이트
                updateLines(newTopMargin)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // SeekBar 레이아웃 (전체 영역 덮음)
        val seekLp = FrameLayout.LayoutParams(sliderHeight, containerWidth).apply {
            gravity = Gravity.CENTER
        }
        container.addView(seek, seekLp)

        // ─────────────────────────────────────────
        // 컨테이너 위치 설정
        // ─────────────────────────────────────────
        val lp = FrameLayout.LayoutParams(containerWidth, sliderHeight).apply {
            gravity = Gravity.END
            rightMargin = dp(16)

            // 화면 중앙에 배치
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
    private var timerSec = 3

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
    }

    override fun onPause() {
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
    // 블러 전환 애니메이션
    // ---------------------------

    // MainActivity.kt

    // ---------------------------
// 블러 효과 (추가)
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
            // Android 11 이하: 알파로 페이드
            binding.textureView.alpha = if (enabled) 0.3f else 1f
        }
    }

    // ---------------------------
// Aspect Ratio (수정)
// ---------------------------
    private fun toggleAspectRatio() {
        // 1) 블러 ON
        setPreviewBlur(true)

        // 2) 비율 전환
        val next = when (binding.btnRatio.text) {
            "1:1" -> "4:3"
            "4:3" -> "16:9"
            else -> "1:1"
        }
        binding.btnRatio.text = next
        controller.setAspectRatio(next)

        // 3) 0.3초 후 블러 해제
        binding.textureView.postDelayed({
            setPreviewBlur(false)
        }, 300L)
    }
    private fun playAspectTransition(onMidpoint: () -> Unit) {
        val blurOverlay = binding.blurOverlay  // ★ XML에 추가 필요

        // 1) 블러 페이드 인
        blurOverlay.alpha = 0f
        blurOverlay.visibility = View.VISIBLE
        blurOverlay.animate()
            .alpha(1f)
            .setDuration(150)
            .withEndAction {
                // 2) 중간 지점에서 비율 변경
                onMidpoint()

                // 3) 블러 페이드 아웃
                blurOverlay.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .setStartDelay(50)  // 비율 변경 적용 대기
                    .withEndAction {
                        blurOverlay.visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }




}
