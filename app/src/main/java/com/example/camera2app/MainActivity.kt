package com.example.camera2app

import android.Manifest
import android.annotation.SuppressLint
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
import com.example.camera2app.gallery.GalleryActivity
import com.example.camera2app.ui.OverlayView
import com.example.camera2app.util.Permissions
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var previewContainer: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView

    private lateinit var layoutCameraPreview: ViewGroup
    private lateinit var layoutControls: ViewGroup
    private lateinit var scrollControls: ScrollView
    private lateinit var controlContainer: LinearLayout

    private lateinit var controller: Camera2Controller
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var rootFrame: FrameLayout

    private var isAllAuto = true
    private var tapEvSlider: View? = null

    private val TAG_ISO = "overlayIso"
    private val TAG_SHT = "overlayShutter"
    private val TAG_EV = "overlayEv"
    private val TAG_TAP_EV = "tapEvSlider"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_main)

        // 🔥 최상단 루트
        rootFrame = findViewById(android.R.id.content)

        // 🔥 camera preview (필수)
        previewContainer = findViewById(R.id.previewContainer)
        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.overlayView)

        // 🔥 디자인팀 UI 영역
        layoutCameraPreview = findViewById(R.id.layoutCameraPreview)
        layoutControls = findViewById(R.id.layoutControls)
        scrollControls = findViewById(R.id.scrollControls)
        controlContainer = findViewById(R.id.controlContainer)

        setupCameraController()
        setupPinchZoom()
        setupButtons()
        applyWindowInset()
        requestPermissionsIfNeeded()
    }


    // ======================================================
    // CAMERA SETUP
    // ======================================================
    private fun setupCameraController() {
        controller = Camera2Controller(
            context = this,
            overlayView = overlayView,
            textureView = textureView,
            onFrameLevelChanged = {},
            onSaved = {},
            previewContainer = previewContainer
        ) { fps ->
            // fps 표시 원하면 여기서 처리
        }
    }

    private fun setupPinchZoom() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    controller.onPinchScale(detector.scaleFactor)
                    return true
                }
            }
        )

        overlayView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)

            if (ev.actionMasked == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                toggleTapEvSlider(ev.x, ev.y)
            }
            true
        }
    }


    // ======================================================
    // EV 탭 슬라이더
    // ======================================================
    private fun toggleTapEvSlider(x: Float, y: Float) {
        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
            return
        }

        if (isAllAuto) {
            isAllAuto = false
            controller.setAllManual()
        }

        tapEvSlider = createTapEvSlider(x, y)
        rootFrame.addView(tapEvSlider)
        tapEvSlider?.bringToFront()
    }

    private fun createTapEvSlider(x: Float, y: Float): View {
        val container = FrameLayout(this).apply { tag = TAG_TAP_EV }

        val seek = SeekBar(this).apply {
            max = 800
            rotation = -90f
            progress = 400
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!isAllAuto) controller.applyEv((p - 400) / 100.0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val height = dp(200)

        container.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            height
        ).apply {
            gravity = Gravity.END
            rightMargin = dp(16)
            topMargin = (y - height / 2).toInt()
        }

        container.addView(seek)
        container.setOnClickListener {
            rootFrame.removeView(container)
            tapEvSlider = null
        }

        return container
    }


    // ======================================================
    // BUTTONS
    // ======================================================
    private fun setupButtons() {
        // ✔ 필요한 경우 UI에 버튼 추가해서 bind 하면 됨
        // 예:
        // findViewById<Button>(R.id.btnShutter).setOnClickListener {
        //     controller.takePicture()
        // }
    }


    // ======================================================
    // WINDOW INSETS
    // ======================================================
    private fun applyWindowInset() {
        ViewCompat.setOnApplyWindowInsetsListener(previewContainer) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            layoutCameraPreview.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = status
            }
            insets
        }
    }


    // ======================================================
    // PERMISSIONS
    // ======================================================
    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33)
            needs += Manifest.permission.READ_MEDIA_IMAGES
        else
            needs += Manifest.permission.READ_EXTERNAL_STORAGE

        Permissions.requestIfNeeded(this, needs.toTypedArray())
    }


    // ======================================================
    // LIFECYCLE
    // ======================================================
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


    // ======================================================
    // UTILITY
    // ======================================================
    private fun dp(v: Int) = (resources.displayMetrics.density * v + 0.5f).toInt()
}
