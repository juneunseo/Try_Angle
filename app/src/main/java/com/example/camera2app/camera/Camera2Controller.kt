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

    // JPEG 저장 처리 리스너 (재사용)

    private val onImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader ->
            val img = reader.acquireNextImage() ?: return@OnImageAvailableListener

            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).apply { buf.get(this) }
            img.close()

            // 1) JPEG → Bitmap 로드
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // 2) 회전 적용
            val rotated = rotateBitmap(bmp, lastJpegOrientation)

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
        if (!flashAvailable()) return

        when (flashMode) {

            FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            }

            FlashMode.AUTO -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_OFF
                    else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
            }

            FlashMode.ON -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_TORCH   // ⭐ 이것만 바꾸면 해결!
                    else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                )
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


    }

    fun onPause() {
        closeSession()
        stopBackground()
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        cameraId = findCameraId(lensFacing)
        cameraDevice = null

        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!



        Log.d(TAG, "openCamera: aspect=$aspectMode, previewSize=$previewSize")

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
        val captureSize = jpegSizes.maxBy { it.width * it.height }

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

            // 2) 회전 적용
            val rotated = rotateBitmap(bmp, lastJpegOrientation)

            // 3) aspectMode 에 맞춰 중앙 크롭
            val cropped = cropToAspect(rotated, aspectMode)

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
                        applyCommonControls(this, preview = true)
                        applyColorAuto(this)
                        applyFlash(this, true)
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
        playShutterFlash()

        val device = cameraDevice ?: return
        val jpegSurface = imageReader?.surface ?: return

        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        lastJpegOrientation = getJpegOrientation(chars, rotation)

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegSurface)

            set(CaptureRequest.JPEG_ORIENTATION, lastJpegOrientation)
            applyCommonControls(this, preview = false)
            applyColorAuto(this)
            applyFlash(this, false)
            applyZoomAndAspect(this)

            // quality boost for still capture
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
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
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)

            val safeExp = currentExposureNs.coerceAtMost(frameNs - 300_000L)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)

            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(targetFps, targetFps)
            )
            builder.set(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            )
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            if (preview) {
                builder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                )
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            }
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExp)
            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(targetFps, targetFps)
            )
        }

        applyZoomAndAspect(builder)
    }



    private fun applyZoomAndAspect(builder: CaptureRequest.Builder) {
        if (!::sensorArray.isInitialized) return

        val base = sensorArray
        var zoom = currentZoom

        val targetRatio = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
        }

        val sensorRatio = base.width().toFloat() / base.height().toFloat()

        val fillZoom = if (sensorRatio > targetRatio) {
            sensorRatio / targetRatio
        } else {
            targetRatio / sensorRatio
        }

        zoom = max(zoom, fillZoom)

        val cropW = (base.width() / zoom).toInt()
        val cropH = (base.height() / zoom).toInt()

        val cx = base.centerX()
        val cy = base.centerY()

        val rect = Rect(
            cx - cropW / 2,
            cy - cropH / 2,
            cx + cropW / 2,
            cy + cropH / 2
        )

        builder.set(CaptureRequest.SCALER_CROP_REGION, rect)
    }



    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0 || vh <= 0) return

        // 카메라 프리뷰 버퍼 크기
        val bw = previewSize.width.toFloat()
        val bh = previewSize.height.toFloat()

        val cx = vw / 2f
        val cy = vh / 2f

        // === 1) TextureView는 그대로 "화면 꽉 채우기" ===
        val scale = max(vw / bw, vh / bh)
        val m = Matrix().apply {
            setScale(scale, scale, cx, cy)
        }
        textureView.setTransform(m)

        // === 2) 화면비에 맞는 "실제 프리뷰 영역 Rect" 계산 ===
        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
        }

        val viewAspect = vw / vh

        val targetRect = if (viewAspect < targetAspect) {
            // 화면이 더 세로로 길다 → 위/아래 레터박스
            val activeHeight = vw / targetAspect
            val top = (vh - activeHeight) / 2f
            RectF(
                0f,
                top,
                vw,
                top + activeHeight
            )
        } else {
            // 화면이 더 가로로 넓다 → 좌우 레터박스
            val activeWidth = vh * targetAspect
            val left = (vw - activeWidth) / 2f
            RectF(
                left,
                0f,
                left + activeWidth,
                vh
            )
        }

        // === 3) 레터박스 Rect를 애니메이션으로 보간 ===
        val startRect = currentVisibleRect ?: targetRect

        // 처음 한 번은 바로 세팅 (튀지 않게)
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
                fun lerp(a: Float, b: Float): Float = a + (b - a) * t

                val r = RectF(
                    lerp(startRect.left,   targetRect.left),
                    lerp(startRect.top,    targetRect.top),
                    lerp(startRect.right,  targetRect.right),
                    lerp(startRect.bottom, targetRect.bottom)
                )
                currentVisibleRect = r
                overlayView.setVisibleRect(r)
                overlayView.invalidate()
            }
        }
        rectAnimator?.start()
    }









    // =========================================================================================
    // Size selection
    // =========================================================================================
    // 내가 원하는 비율에 맞는 "목표" 해상도 (preset)
    private fun fixedPreviewSizeFor(mode: AspectMode): Size {
        return when (mode) {
            AspectMode.RATIO_1_1 -> Size(1440, 1440)   // 1:1
            AspectMode.RATIO_3_4 -> Size(1440, 1920)   // 3:4  (0.75)
            AspectMode.RATIO_9_16 -> Size(1440, 2560)  // 9:16 (0.5625)

        }
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
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix()
        m.postRotate(degrees.toFloat())
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

            applyCommonControls(this, preview = true)
            applyColorAuto(this)
            applyFlash(this, true)
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









}