package com.example.camera2app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.example.camera2app.R
import com.example.camera2app.ui.OverlayView
import com.example.camera2app.camera.OrientationUtil.getJpegOrientation
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager


// === manual WB ===
private var manualWbGains: RggbChannelVector? = null

class Camera2Controller(




    private val context: Context,
    private val overlayView: OverlayView,
    private val textureView: TextureView,
    private val onFrameLevelChanged: (Float) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val previewContainer: ViewGroup,
    private val onFpsChanged: (Double) -> Unit = {}
) {

    private val TAG = "Camera2Controller"

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size = Size(1920, 1080)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var chars: CameraCharacteristics
    private lateinit var cameraId: String
    private lateinit var sensorArray: Rect
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK

    // exposure
    private var manualEnabled = true
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var currentIso = 200
    private var currentExposureNs = 3_000_000L
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var currentZoom = 1f

    // EV compensation (-4 ~ +4 정도)
    private var expRange: Range<Int> = Range(0, 0)
    private var currentExp = 0

    // ISO 대신 해상도 레벨을 사용
    private var resolutionLevel = 0   // 0 = 최대 해상도


    // FPS
    private var targetFps = 60
    private val frameNs: Long get() = 1_000_000_000L / targetFps
    private val exposureMarginNs = 300_000L

    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // aspect
    enum class AspectMode { RATIO_1_1, RATIO_3_4, RATIO_9_16 }
    private var aspectMode = AspectMode.RATIO_9_16


    // shutter overlay
    private var shutterOverlay: View? = null

    private var currentKelvin = 4400

    // resolution adaptation
    private var adaptiveResolution = false
    private lateinit var sizeLadder: List<Size>
    private var sizeIndex = 0
    private val MAX_W = 4000
    private val MAX_H = 4000

    // flash
    enum class FlashMode { OFF, AUTO, ON}
    private var flashMode = FlashMode.OFF


    private var baseExposureNs: Long? = null
    private var baseIso: Int? = null

    // ★ Preview는 고정, Capture는 선택
    private var captureSize: Size = Size(4000, 3000) // 기본 12M


    // ⭐ 조도 센서 관련 변수 추가
    private val lightSensor by lazy {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    private var currentLux = 50f  // 현재 밝기 (lux)
    private val DARK_THRESHOLD = 70f  // 어두움 판단 기준

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                currentLux = it.values[0]
                println("💡 [LUX] 조도 업데이트: ${currentLux} lux")  // ⭐ 추가
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // JPEG 저장 처리 리스너 (재사용)

    private val onImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader ->
            val img = reader.acquireNextImage() ?: return@OnImageAvailableListener

            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).apply { buf.get(this) }
            img.close()

            // 1) JPEG → Bitmap 로드
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // 2) 회전 + 전면이면 미러링 적용
            val isFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            val rotated = transformBitmap(bmp, lastJpegOrientation, mirror = isFront)

            // 3) 현재 화면비(aspectMode)에 맞춰 중앙 크롭
            val cropped = cropToAspect(rotated, aspectMode)

            // 4) 크롭된 걸 JPEG로 압축
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
            val finalBytes = out.toByteArray()

            // 5) 저장 콜백
            onSaved(saveJpeg(finalBytes))
        }



    // ▼ 레터박스 애니메이션용
    private var currentVisibleRect: RectF? = null
    private var rectAnimator: ValueAnimator? = null

    // === Timer ===
    enum class TimerMode { OFF, SEC_3, SEC_10 }
    private var timerMode = TimerMode.OFF
    private var timerHandler: Handler? = null
    private var timerCountdownCallback: ((Int) -> Unit)? = null


    // Camera2Controller 안에
    fun getAspectMode(): AspectMode = aspectMode

    fun getFlashMode() = flashMode
    fun setFlashMode(m: FlashMode) { flashMode = m; updateRepeating() }

    fun getResolutionPreset() = currentResolutionPreset




    enum class ResolutionPreset(val size: Size) {
        R12MP(Size(4000, 3000)),
        R50MP(Size(8160, 6120))
    }
    private var currentResolutionPreset = ResolutionPreset.R12MP


    private fun flashAvailable() =
        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    private fun applyFlash(builder: CaptureRequest.Builder, forPreview: Boolean) {
        if (!flashAvailable() || !forPreview) return

        when (flashMode) {
            FlashMode.OFF, FlashMode.AUTO -> {
                // ⭐ OFF와 AUTO는 프리뷰에서 플래시 끄기
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }

            FlashMode.ON -> {
                // ON 모드만 프리뷰에서 torch
                if (manualEnabled) {
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                }
            }
        }
    }


    // =========================================================================================
    // Aspect control
    // =========================================================================================
    fun cycleAspectMode(): AspectMode {

        aspectMode = when (aspectMode) {

            AspectMode.RATIO_9_16 -> AspectMode.RATIO_1_1   // 16:9 다음 1:1
            AspectMode.RATIO_1_1 -> AspectMode.RATIO_3_4    // 1:1 다음 4:3
            AspectMode.RATIO_3_4 -> AspectMode.RATIO_9_16   // 4:3 다음 16:9

        }
        Log.d(TAG, "cycleAspectMode -> $aspectMode")

        maybeSwitchPreviewAspect()
        updateRepeating()

        return aspectMode
    }

    fun setAspectMode(m: AspectMode) {
        aspectMode = m
        Log.d(TAG, "setAspectMode -> $aspectMode")
        maybeSwitchPreviewAspect()
        updateRepeating()

    }

    // =========================================================================================
    // Manual / Auto switches
    // =========================================================================================
    fun setManualEnabled(b: Boolean) { manualEnabled = b; updateRepeating() }

    fun setTargetFps(fps: Int) {
        targetFps = if (fps <= 60) 60 else 120
        currentExposureNs = currentExposureNs
            .coerceAtMost(frameNs - exposureMarginNs)
        updateRepeating()
    }

    fun setIso(level: Int) {
        if (!::sizeLadder.isInitialized) return

        // level = 0~(sizeLadder.size - 1)
        resolutionLevel = level.coerceIn(0, sizeLadder.size - 1)

        // 프리뷰 해상도 변경
        previewSize = sizeLadder[resolutionLevel]

        // JPEG 캡쳐 해상도도 통일
        recreateImageReader(previewSize)

        restartPreviewSession()
    }


    fun setExposureTimeNs(ns: Long) {
        val capped = ns.coerceAtMost(frameNs - exposureMarginNs)
        currentExposureNs = capped.coerceIn(exposureRange.lower, exposureRange.upper)

        // EV 기준 노출 초기화 (새로운 base exposure)
        baseExposureNs = currentExposureNs

        updateRepeating()
    }


    fun setAwbMode(mode: Int) {
        currentAwbMode = mode
        updateRepeating()
    }

    // ★ EV 값 설정 (MainActivity 슬라이더에서 호출)
    fun setExposureCompensation(v: Int) {
        currentExp = v.coerceIn(expRange.lower, expRange.upper)
        updateRepeating()
    }

    fun setZoom(z: Float) {
        currentZoom = z.coerceIn(1f, maxZoom())
        updateRepeating()
    }

    fun onPinchScale(scale: Float) {
        if (!::chars.isInitialized) return
        val newZoom = (currentZoom * scale)
            .coerceIn(1f, maxZoom())
        currentZoom = newZoom
        updateRepeating()
    }

    fun setMinIsoFloor(minIso: Int) {
        isoRange = Range(max(minIso, isoRange.lower), isoRange.upper)
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        updateRepeating()
    }

    fun setAdaptiveResolutionEnabled(b: Boolean) {
        adaptiveResolution = b
    }

    fun setFillPreview(b: Boolean) {

    }

    fun setAwbTemperature(kelvin: Int) {
        val rGain = when {
            kelvin < 3500 -> 2.2f
            kelvin < 4500 -> 1.8f
            kelvin < 5500 -> 1.5f
            kelvin < 6500 -> 1.3f
            else -> 1.1f
        }
        val bGain = when {
            kelvin < 3500 -> 1.1f
            kelvin < 4500 -> 1.3f
            kelvin < 5500 -> 1.5f
            kelvin < 6500 -> 1.8f
            else -> 2.2f
        }
        manualEnabled = true
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_OFF
        currentKelvin = kelvin
        manualWbGains = RggbChannelVector(rGain, 1f, 1f, bGain)
        updateRepeating()
    }

    // =========================================================================================
    // Lifecycle
    // =========================================================================================
    fun onResume() {
        startBackground()
        textureView.surfaceTextureListener = surfaceListener
        if (textureView.isAvailable)
            openCamera(textureView.width, textureView.height)

        // ⭐ 조도 센서 시작
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor?.let {
            sensorManager.registerListener(
                lightSensorListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    fun onPause() {
        closeSession()
        stopBackground()

        // ⭐ 조도 센서 중지
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.unregisterListener(lightSensorListener)
    }

    // =========================================================================================
    // Surface listener
    // =========================================================================================
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera(w, h)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            applyCenterCropTransform()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            fpsCounter++
            val now = SystemClock.elapsedRealtime()
            if (lastFpsTickMs == 0L) lastFpsTickMs = now
            val dt = now - lastFpsTickMs
            if (dt >= 500) {
                val inst = fpsCounter * 1000.0 / dt
                fpsSmoothed = if (fpsSmoothed == 0.0) inst else 0.6 * inst + 0.4 * fpsSmoothed
                fpsCounter = 0
                lastFpsTickMs = now
                onFpsChanged(fpsSmoothed)
            }
        }
    }

    // =========================================================================================
    // Camera open
    // =========================================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera(w: Int, h: Int) {


        if (isFrontCamera()) {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            previewSize = sizes.maxByOrNull { it.width * it.height }!!
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        cameraId = findCameraId(lensFacing)
        cameraDevice = null

        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        previewSize = chooseBestPreviewSize(map, aspectMode)

        val desiredSize = fixedPreviewSizeFor(aspectMode)
        previewSize = desiredSize

        Log.d(TAG, ">>> PREVIEW SIZE = ${previewSize.width} x ${previewSize.height}")

        Log.d(TAG, "openCamera: aspect=$aspectMode, previewSize=$previewSize")

        val all = map.getOutputSizes(SurfaceTexture::class.java)
        all.forEach {
            Log.d(TAG, "SUPPORTED PREVIEW SIZE → ${it.width} x ${it.height}")
        }

        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)

        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)

        cameraManager.openCamera(cameraId, deviceCallback, bgHandler)
    }

    private fun findCameraId(facing: Int): String {
        cameraManager.cameraIdList.forEach { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == facing)
                return id
        }
        return cameraManager.cameraIdList.first()
    }

    // =========================================================================================
    // CameraDevice callback
    // =========================================================================================
    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            setupImageReader()
            startPreview()
        }

        override fun onDisconnected(device: CameraDevice) {
            cameraDevice = null
            device.close()
        }

        override fun onError(device: CameraDevice, error: Int) {
            cameraDevice = null
            device.close()
        }
    }

    // =========================================================================================
    // ImageReader (JPEG capture + central crop)
    // =========================================================================================
    private fun setupImageReader() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)

        // 센서 비율 그대로 사용 (캡처에서도 센서 crop 영역만 저장)
        // 비율 보정은 applyZoomAndAspect()의 sensor crop이 담당하므로,
        // JPEG 해상도는 가장 큰 센서 해상도 그대로 사용하면 됨.
        val captureSize = jpegSizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)

        Log.d(
            TAG,
            "setupImageReader: selected JPEG size = ${captureSize.width} x ${captureSize.height}"
        )

        // 기존 ImageReader 제거
        imageReader?.close()

        // 새로운 ImageReader 생성 (crop 없이 전체 이미지 받아옴)
        imageReader = ImageReader.newInstance(
            captureSize.width,
            captureSize.height,
            ImageFormat.JPEG,
            3
        )

// ★ 새로운 저장 리스너: 화면비에 맞춰 크롭하여 저장
        imageReader!!.setOnImageAvailableListener({ reader ->

            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener

            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).apply { buf.get(this) }
            img.close()

            // 1) JPEG → Bitmap 로드
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // 2) 회전 + 전면이면 미러링 적용
            val isFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            val transformed = transformBitmap(bmp, lastJpegOrientation, mirror = isFront)


            // 2) 회전 적용
            val cropped = cropToAspect(transformed, aspectMode)


            // 4) 최종 JPEG로 압축
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 96, out)
            val finalBytes = out.toByteArray()

            // 5) 저장
            onSaved(saveJpeg(finalBytes))

        }, bgHandler)

    }


    // =========================================================================================
    // Preview start
    // =========================================================================================
    private fun startPreview() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return

        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)
        val jpegSurface = imageReader!!.surface

        device.createCaptureSession(
            listOf(previewSurface, jpegSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s

                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)

                        // ⭐ 순서 변경: 플래시를 먼저 적용
                        applyFlash(this, true)
                        applyCommonControls(this, preview = true)
                        applyColorAuto(this)
                    }

                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler
        )
    }


    // =========================================================================================
    // takePicture()
    // =========================================================================================
    private var lastJpegOrientation = 0

    private fun playShutterFlash() {
        if (shutterOverlay == null) {
            shutterOverlay = previewContainer.rootView.findViewById(R.id.shutterFlashView)
        }
        val v = shutterOverlay ?: return
        v.bringToFront()
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.animate()
            .alpha(0.85f)
            .setDuration(40)
            .withEndAction {
                v.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction { v.setLayerType(View.LAYER_TYPE_NONE, null) }
                    .start()
            }
            .start()
    }

    fun takePicture() {
        val device = cameraDevice ?: return
        val jpegSurface = imageReader?.surface ?: return

        playShutterFlash()

        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        lastJpegOrientation = getJpegOrientation(chars, rotation)

        // ⭐ 플래시 필요 여부를 조도로 판단
        val needFlash = when (flashMode) {
            FlashMode.OFF -> false
            FlashMode.ON -> true
            FlashMode.AUTO -> currentLux < DARK_THRESHOLD
        }

        // ⭐ 조도 로그 추가
        Log.d(TAG, "📸 촬영 - 조도: ${currentLux} lux, 임계값: ${DARK_THRESHOLD}, 플래시: $needFlash")

        // ⭐ 플래시가 필요하면 프리플래시 후 촬영
        if (needFlash && flashAvailable()) {
            preFlashThenCapture()
        } else {
            captureStillImage(false)
        }
    }

    private fun preFlashThenCapture() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)

        // ⚠️ 여기서 TORCH 켜는 건 필요 없음 (아래에서 바로 촬영하니까)
        // 바로 TORCH 켠 상태로 촬영!

        captureStillImage(useFlash = true)  // TORCH 모드로 촬영

        // 촬영 후 500ms 뒤 TORCH 끄기
        bgHandler?.postDelayed({
            updateRepeating()  // 프리뷰로 복귀 (TORCH 꺼짐)
        }, 500)
    }

    private fun captureStillImage(useFlash: Boolean = false) {
        val device = cameraDevice ?: return
        val jpegSurface = imageReader?.surface ?: return

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegSurface)

            set(CaptureRequest.JPEG_ORIENTATION, lastJpegOrientation)

            // ⭐ Manual 모드일 때 ISO/Exposure 고정
            if (manualEnabled) {
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

                val safeExp = currentExposureNs.coerceAtMost(frameNs - 300_000L)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
                set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            } else {
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            // ⭐ 플래시만 필요할 때만 ON
            if (flashAvailable()) {
                if (useFlash) {
                    set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)  // ✅ TORCH!
                } else {
                    set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
            }

            // 기본 설정
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

            applyZoomAndAspect(this)

            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        }

        session?.capture(req.build(), null, bgHandler)
    }



    // =========================================================================================
    // Auto / Manual WB / Color controls
    // =========================================================================================
    private fun applyColorAuto(builder: CaptureRequest.Builder) {
        if (currentAwbMode == CameraMetadata.CONTROL_AWB_MODE_OFF && manualWbGains != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, manualWbGains)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            )
        }
    }

    // =========================================================================================
    // Common preview/still controls
    // =========================================================================================
    private fun applyCommonControls(builder: CaptureRequest.Builder, preview: Boolean) {

        // ============================================================
        // ⭐ 전면 카메라는 무조건 완전 자동 모드 (수동 로직 금지)
        //    하지만 마지막의 applyZoomAndAspect()는 반드시 실행해야 함!
        // ============================================================
        if (isFrontCamera()) {

            // 순수 AUTO 설정
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // 고화질 옵션
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)

            // ★★★ 아래 applyZoomAndAspect는 return 전이 아니라 무조건 공통 실행! ★★★
            applyZoomAndAspect(builder)
            return
        }


        // ============================================================
        // ⭐ 후면 카메라는 기존 로직 그대로 유지
        // ============================================================
        // ============================================================
// ⭐ 후면 카메라 로직 - AUTO 플래시는 촬영 시에만!
// ============================================================
        if (manualEnabled) {
            // Manual 모드 - ISO/Exposure 고정
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)

            val safeExp = currentExposureNs.coerceAtMost(frameNs - 300_000L)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        } else {
            // AUTO 모드 - 카메라가 ISO 자동 조절
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExp)
        }

        // 후면 자동 프리뷰 고화질 옵션
        if (preview) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }

        // ⭐ 반드시 마지막에 센서 crop 실행
        applyZoomAndAspect(builder)
    }


    // ★ 센서는 줌만, 비율 crop 안 함
    // ★ 센서 크롭: 줌 + 화면비 모두 적용
    private fun applyZoomAndAspect(builder: CaptureRequest.Builder) {
        if (!::sensorArray.isInitialized) return

        val base = sensorArray
        val sensorW = base.width()
        val sensorH = base.height()
        val sensorRatio = sensorW.toFloat() / sensorH  // 예: 4:3 = 1.33

        // 목표 비율 (landscape 기준)
        val targetRatio = when (aspectMode) {
            AspectMode.RATIO_1_1  -> 1f
            AspectMode.RATIO_3_4  -> 4f / 3f   // landscape = 1.33
            AspectMode.RATIO_9_16 -> 16f / 9f  // landscape = 1.78
        }

        // 1) 먼저 비율에 맞게 크롭 영역 계산
        val (aspectCropW, aspectCropH) = if (sensorRatio > targetRatio) {
            // 센서가 더 넓음 → 좌우 잘라냄
            val newW = (sensorH * targetRatio).toInt()
            newW to sensorH
        } else {
            // 센서가 더 좁음 → 위아래 잘라냄
            val newH = (sensorW / targetRatio).toInt()
            sensorW to newH
        }

        // 2) 줌 적용
        val zoom = currentZoom.coerceAtLeast(1f)
        val finalW = (aspectCropW / zoom).toInt()
        val finalH = (aspectCropH / zoom).toInt()

        val cx = base.centerX()
        val cy = base.centerY()

        val rect = Rect(
            cx - finalW / 2,
            cy - finalH / 2,
            cx + finalW / 2,
            cy + finalH / 2
        )

        builder.set(CaptureRequest.SCALER_CROP_REGION, rect)
    }

    // ============================================================
// 수정된 applyCenterCropTransform()
// ============================================================

    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0 || vh <= 0) return

        val cx = vw / 2f
        val cy = vh / 2f

        val bufferRatio = previewSize.width.toFloat() / previewSize.height
        val matrix = Matrix()

        // 🔥 전면 세로 보정 계수
        // 1.12f = 세로 12% 확대 (너의 사진 비교 기준 가장 자연스러운 값)
        val frontYFix = if (isFrontCamera()) 0.90f else 1f


        // ============================================================
        // ❤️ 기존 화면비 변환 로직 (후면 100% 그대로 유지)
        //    + 전면일 때만 Y축에 frontYFix 곱해줌
        // ============================================================
        when (aspectMode) {

            AspectMode.RATIO_1_1 -> {
                val cropRatio = 1f
                val scaleY = cropRatio / bufferRatio
                matrix.setScale(1f, scaleY * frontYFix, cx, cy)
            }

            AspectMode.RATIO_3_4 -> {
                val cropRatio = 1f
                val scaleY = cropRatio / bufferRatio
                matrix.setScale(1f, scaleY * frontYFix, cx, cy)
            }

            AspectMode.RATIO_9_16 -> {
                val zoomFactor = (4f / 3f) / (16f / 9f)  // 0.75
                val baseScaleY = bufferRatio / (16f / 9f)  // 0.75
                val scale = 1f / zoomFactor  // 1.333

                matrix.setScale(
                    scale,
                    (baseScaleY * scale) * frontYFix,  // ← 전면만 자연스럽게 세로 보정
                    cx, cy
                )
            }
        }

        textureView.setTransform(matrix)


        // ============================================================
        // 기존 레터박스 애니메이션 — 그대로 유지
        // ============================================================
        val targetH = when (aspectMode) {
            AspectMode.RATIO_1_1 -> vw
            AspectMode.RATIO_3_4 -> vw * (4f / 3f)
            AspectMode.RATIO_9_16 -> vw * (16f / 9f)
        }

        val targetRect = RectF(
            0f,
            cy - targetH / 2f,
            vw,
            cy + targetH / 2f
        )

        val startRect = currentVisibleRect ?: targetRect

        if (currentVisibleRect == null) {
            currentVisibleRect = RectF(targetRect)
            overlayView.setVisibleRect(targetRect)
            overlayView.invalidate()
            return
        }

        rectAnimator?.cancel()
        rectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                fun lerp(a: Float, b: Float) = a + (b - a) * t
                val r = RectF(
                    0f,
                    lerp(startRect.top, targetRect.top),
                    vw,
                    lerp(startRect.bottom, targetRect.bottom)
                )
                currentVisibleRect = r
                overlayView.setVisibleRect(r)
                overlayView.invalidate()
            }
        }
        rectAnimator?.start()
    }


    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun findBestPreviewSize(map: StreamConfigurationMap, mode: AspectMode): Size {
        val targetRatio = when (mode) {
            AspectMode.RATIO_1_1  -> 1f
            AspectMode.RATIO_3_4  -> 4f / 3f   // 버퍼는 landscape
            AspectMode.RATIO_9_16 -> 16f / 9f
        }

        val sizes = map.getOutputSizes(SurfaceTexture::class.java)

        // 비율 ±5% 허용, 그 중 최대 해상도
        return sizes
            .filter { abs(it.width.toFloat() / it.height - targetRatio) < 0.05f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxBy { it.width.toLong() * it.height }
    }


    // ★ previewSize를 landscape 4:3로 고정
    private fun fixedPreviewSizeFor(mode: AspectMode): Size {
        // 모든 비율에서 동일한 4:3 landscape 버퍼 사용
        return Size(1920, 1440)  // landscape 4:3
    }



    // =========================================================================================
    // Size selection
    // =========================================================================================
    // 내가 원하는 비율에 맞는 "목표" 해상도 (preset)
    private fun chooseBestPreviewSize(map: StreamConfigurationMap, mode: AspectMode): Size {

        val aspect = when (mode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
        }

        val all = map.getOutputSizes(SurfaceTexture::class.java)

        // aspect ± 1% 허용
        val candidates = all.filter { s ->
            val r = s.width.toFloat() / s.height
            kotlin.math.abs(r - aspect) < 0.01f
        }

        // aspect 맞는 사이즈 중 최대 해상도 선택
        val best = if (candidates.isNotEmpty()) {
            candidates.maxBy { it.width.toLong() * it.height.toLong() }
        } else {
            // 혹시 aspect 딱 맞는 게 없으면 가장 큰 previewSize
            all.maxBy { it.width.toLong() * it.height.toLong() }
        }

        Log.d(TAG, "chooseBestPreviewSize: ${best.width} x ${best.height}")
        return best
    }


    // 위 preset과 가장 가까운, 실제 "지원되는" 프리뷰 사이즈를 선택
    private fun nearestSupportedPreviewSize(
        desiredAspect: Float,
        map: StreamConfigurationMap
    ): Size {

        val all: Array<Size> = map.getOutputSizes(SurfaceTexture::class.java)

        // 1) aspect(±1%) 맞는 후보만 필터
        val candidates = all.filter { s ->
            val r = s.width.toFloat() / s.height
            kotlin.math.abs(r - desiredAspect) < 0.01f
        }

        // 2) 후보가 있으면 candidates, 없으면 전체 all 사용
        val list: List<Size> = if (candidates.isNotEmpty()) candidates else all.toList()

        // 3) 리스트에서 가장 큰 해상도 선택
        return list.maxBy { s -> s.width.toLong() * s.height.toLong() }
    }


    private fun maybeSwitchPreviewAspect() {



    }

    // =========================================================================================
    // Save jpeg
    // =========================================================================================
    private fun saveJpeg(bytes: ByteArray): Uri {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera2App")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!

        resolver.openOutputStream(uri)?.use { it.write(bytes) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }

    // =========================================================================================
    // Background / cleanup
    // =========================================================================================
    private fun startBackground() {
        bgThread = HandlerThread("CameraBG").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackground() {
        bgThread?.quitSafely()
        bgThread?.join()
        bgThread = null
        bgHandler = null
    }

    private fun closeSession() {
        session?.close()
        session = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    // =========================================================================================
    // Utils / getters
    // =========================================================================================
    private fun transformBitmap(src: Bitmap, degrees: Int, mirror: Boolean): Bitmap {
        if (degrees == 0 && !mirror) return src

        val m = Matrix()

        // 1) 먼저 회전
        if (degrees != 0) {
            m.postRotate(degrees.toFloat(), src.width / 2f, src.height / 2f)
        }

        // 2) 회전 후 미러링 (좌우만 반전)
        if (mirror) {
            // 회전 후 이미지 크기 계산
            val rotatedW = if (degrees == 90 || degrees == 270) src.height else src.width
            val rotatedH = if (degrees == 90 || degrees == 270) src.width else src.height
            m.postScale(-1f, 1f, rotatedW / 2f, rotatedH / 2f)
        }

        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun getAppliedExposureNs() = currentExposureNs
    fun getCurrentIso() = currentIso
    fun getCurrentKelvin() = currentKelvin

    // ★ EV 범위 / 현재값 getter (슬라이더 초기 세팅용)
    fun getEvRange(): Range<Int> = expRange
    fun getCurrentEv(): Int = currentExp

    private fun maxZoom(): Float {
        val maxZ = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return max(1f, maxZ)
    }

    private fun updateRepeating() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return

        val previewSurface = Surface(st)

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)

            // ⭐ 순서 변경: 플래시를 먼저 적용
            applyFlash(this, true)
            applyCommonControls(this, preview = true)
            applyColorAuto(this)
        }

        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    // =========================================================================================
    // 전체 Auto / Manual 토글 (MainActivity에서 사용)
    // =========================================================================================
    fun setAllAuto() {
        manualEnabled = false
        // WB 자동
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        manualWbGains = null
        // EV는 0으로 초기화
        currentExp = 0.coerceIn(expRange.lower, expRange.upper)
        updateRepeating()
    }

    fun setAllManual() {
        manualEnabled = true
        // 노출은 수동이지만 WB는 자동 유지해서 초록색 안 뜨게
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        updateRepeating()
    }

    // -------------------------------
    // 🔄 전·후면 카메라 전환
    // -------------------------------
    fun switchCamera() {
        lensFacing =
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
                CameraCharacteristics.LENS_FACING_FRONT
            else
                CameraCharacteristics.LENS_FACING_BACK

        closeSession()
        currentZoom = 1f

        val w = textureView.width
        val h = textureView.height

        if (w > 0 && h > 0) {
            openCamera(w, h)
        } else {
            textureView.surfaceTextureListener = surfaceListener
        }
    }
    fun applyEv(ev: Double) {
        manualEnabled = true

        // ==== 1) EV 기준값 준비 ====
        if (baseExposureNs == null) baseExposureNs = currentExposureNs
        if (baseIso == null) baseIso = currentIso

        val baseExp = baseExposureNs!!
        val baseIsoVal = baseIso!!

        // ==== 2) EV → 밝기 배율 ====
        val factor = Math.pow(2.0, ev)

        // ==== 3) 1차: 셔터 먼저 계산 ====
        var newExp = (baseExp * factor).toLong()
        val maxExp = frameNs - 300_000L
        val minExp = 200_000L

        // 셔터는 이 범위를 벗어나면 고정
        val expClamped = newExp.coerceIn(minExp, maxExp)

        // ==== 4) 셔터의 한계로 인해 잘린 factor 계산 ====
        val usedExpFactor = expClamped.toDouble() / baseExp.toDouble()

        // ==== 5) 2차: 남은 EV만큼 ISO 조절 ====
        val remainingFactor = factor / usedExpFactor
        var newIso = (baseIsoVal * remainingFactor).toInt()

        newIso = newIso.coerceIn(isoRange.lower, isoRange.upper)

        // ==== 6) 최종 값 적용 ====
        currentExposureNs = expClamped
        currentIso = newIso

        updateRepeating()
    }



    private fun recreateImageReader(size: Size) {
        imageReader?.close()

        imageReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.JPEG,
            3
        )

        imageReader!!.setOnImageAvailableListener(onImageAvailableListener, bgHandler)
    }



    private fun restartPreviewSession() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        val jpeg = imageReader?.surface ?: return

        session?.close()
        session = null

        // ★ PreviewSize는 처음 정한 값 그대로 유지
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)

        device.createCaptureSession(
            listOf(previewSurface, jpeg),
            object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(s: CameraCaptureSession) {
                    session = s

                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        applyCommonControls(this, true)
                        applyColorAuto(this)
                        applyFlash(this, true)
                    }

                    s.setRepeatingRequest(req.build(), null, bgHandler)

                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            },
            bgHandler
        )
    }



    fun setResolutionPreset(preset: ResolutionPreset) {
        currentResolutionPreset = preset
        captureSize = preset.size

        // JPEG 리더 새로 만들기
        recreateImageReader(captureSize)

        // 세션 다시 만들기
        restartPreviewSession()
    }

    private fun cropToAspect(src: Bitmap, mode: AspectMode): Bitmap {
        val w = src.width
        val h = src.height
        val srcRatio = w.toFloat() / h.toFloat()

        val targetRatio = when (mode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f     // 0.75
            AspectMode.RATIO_9_16 -> 9f / 16f   // 0.5625
        }

        // 이미 거의 같은 비율이면 그냥 반환
        if (kotlin.math.abs(srcRatio - targetRatio) < 0.01f) {
            return src
        }

        return if (srcRatio > targetRatio) {
            // 이미지가 더 "가로로 넓음" → 좌우 잘라냄
            val newWidth = (h * targetRatio).toInt()
            val x = (w - newWidth) / 2
            Bitmap.createBitmap(src, x, 0, newWidth, h)
        } else {
            // 이미지가 더 "세로로 김" → 위아래 잘라냄
            val newHeight = (w / targetRatio).toInt()
            val y = (h - newHeight) / 2
            Bitmap.createBitmap(src, 0, y, w, newHeight)
        }
    }

    fun setAspectRatio(ratio: String) {
        when (ratio) {
            "1:1" -> aspectMode = AspectMode.RATIO_1_1
            "4:3" -> aspectMode = AspectMode.RATIO_3_4
            "16:9" -> aspectMode = AspectMode.RATIO_9_16
        }

        // 프리뷰 적용
        applyPreviewAspect()
    }

    private fun applyPreviewAspect() {
        // 화면비 변경 시 TextureView transform 업데이트
        applyCenterCropTransform()
    }

    // =========================================================================================
// Timer
// =========================================================================================
    fun getTimerMode() = timerMode

    fun setTimerMode(mode: TimerMode) {
        timerMode = mode
    }

    fun cycleTimerMode(): TimerMode {
        timerMode = when (timerMode) {
            TimerMode.OFF -> TimerMode.SEC_3
            TimerMode.SEC_3 -> TimerMode.SEC_10
            TimerMode.SEC_10 -> TimerMode.OFF
        }
        return timerMode
    }

    fun setTimerCountdownCallback(callback: (Int) -> Unit) {
        timerCountdownCallback = callback
    }

    fun takePictureWithTimer() {
        when (timerMode) {
            TimerMode.OFF -> takePicture()
            TimerMode.SEC_3 -> startTimerCountdown(3)
            TimerMode.SEC_10 -> startTimerCountdown(10)
        }
    }

    private fun startTimerCountdown(seconds: Int) {
        var remaining = seconds

        timerHandler = Handler(context.mainLooper)

        val runnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    timerCountdownCallback?.invoke(remaining)
                    remaining--
                    timerHandler?.postDelayed(this, 1000)
                } else {
                    timerCountdownCallback?.invoke(0)
                    takePicture()
                }
            }
        }

        timerHandler?.post(runnable)
    }

    fun cancelTimer() {
        timerHandler?.removeCallbacksAndMessages(null)
        timerHandler = null
    }

    fun isFrontCamera(): Boolean {
        return lensFacing == CameraCharacteristics.LENS_FACING_FRONT
    }

}