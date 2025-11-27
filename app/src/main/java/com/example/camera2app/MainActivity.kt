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

        // 촬영
        binding.btnShutter.setOnClickListener { controller.takePicture() }

        // 카메라 전환
        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()
            controller.setFlashMode(Camera2Controller.FlashMode.OFF)
        }

        binding.btnOptions.setOnClickListener {
            toggleOptionBar()
        }

        // 플래시 (AUTO → ON → OFF)
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

        // 옵션 닫기
        binding.btnCloseOption.setOnClickListener {
            binding.optionBar.visibility = View.GONE
        }

        binding.menuGallery.setOnClickListener {
            val intent = Intent(this, GalleryActivity::class.java)
            startActivity(intent)
        }

        binding.menuReference.setOnClickListener {
            startActivity(Intent(this, com.example.camera2app.reference.ReferenceActivity::class.java))
        }




    }

    // ---------------------------
    // EV 슬라이더 (EXP or tap)
    // ---------------------------

    private fun showTapEvSliderCenter() {
        val x = binding.previewContainer.width * 0.8f
        val y = binding.previewContainer.height * 0.5f
        showTapEvSlider(x, y)
    }

    private fun showTapEvSlider(x: Float, y: Float) {
        // 이미 존재하면 제거
        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
            return
        }

        isAllAuto = false

        tapEvSlider = createTapEvSlider(x, y)
        rootFrame.addView(tapEvSlider)
        tapEvSlider?.bringToFront()
    }

    private var optionVisible = false

    private fun toggleOptionBar() {
        optionVisible = !optionVisible

        if (optionVisible) {
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
                }
                .start()
        }
    }



    private fun createTapEvSlider(tapX: Float, tapY: Float): View {
        val container = FrameLayout(this)

        val seek = SeekBar(this).apply {
            max = 800
            rotation = -90f
            progress = 400
            thumb = resources.getDrawable(R.drawable.ic_ev_thumb, null)
            progressDrawable = resources.getDrawable(R.drawable.ev_slider_progress, null)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val ev = (p - 400) / 100.0
                controller.applyEv(ev)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val height = dp(200)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            height
        ).apply {
            gravity = Gravity.END
            rightMargin = dp(20)

            val half = height / 2
            val t = (tapY - half).toInt()

            topMargin = t.coerceIn(
                dp(60),
                binding.previewContainer.height - height - dp(60)
            )
        }

        container.addView(
            seek,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        container.layoutParams = lp

        container.setOnClickListener {
            rootFrame.removeView(container)
            tapEvSlider = null
        }

        return container
    }

    // ---------------------------
    // Timer
    // ---------------------------
    private var timerSec = 3

    private fun toggleTimer() {
        timerSec = when (timerSec) {
            3 -> 5
            5 -> 10
            else -> 3
        }

        binding.btnTimer.setImageResource(R.drawable.ic_time_3s)
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
