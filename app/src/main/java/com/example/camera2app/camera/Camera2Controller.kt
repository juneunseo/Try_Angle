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
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator

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

    // =========================================================================================
    // Camera2 Core
    // =========================================================================================
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private lateinit var chars: CameraCharacteristics
    private lateinit var cameraId: String
    private lateinit var sensorArray: Rect
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK

    // =========================================================================================
    // Background Thread
    // =========================================================================================
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    // =========================================================================================
    // Preview & Capture Settings
    // =========================================================================================
    private var previewSize: Size = Size(1920, 1080)
    private var captureSize: Size = Size(4000, 3000)

    enum class AspectMode { RATIO_1_1, RATIO_3_4, RATIO_9_16 }
    private var aspectMode = AspectMode.RATIO_9_16

    enum class ResolutionPreset(val size: Size) {
        R12MP(Size(4000, 3000)),
        R50MP(Size(8160, 6120))
    }
    private var currentResolutionPreset = ResolutionPreset.R12MP

    // =========================================================================================
    // Manual Controls
    // =========================================================================================
    private var manualEnabled = true
    private var currentIso = 200
    private var isoRange: Range<Int> = Range(100, 1600)
    private var currentExposureNs = 3_000_000L
    private var exposureRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var baseExposureNs: Long? = null
    private var baseIso: Int? = null

    // EV Compensation
    private var expRange: Range<Int> = Range(0, 0)
    private var currentExp = 0

    // White Balance
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var currentKelvin = 4400
    private var manualWbGains: RggbChannelVector? = null

    // Zoom
    private var currentZoom = 1f

    // =========================================================================================
    // FPS
    // =========================================================================================
    private var targetFps = 60
    private val frameNs: Long get() = 1_000_000_000L / targetFps
    private val exposureMarginNs = 300_000L

    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // =========================================================================================
    // Flash
    // =========================================================================================
    enum class FlashMode { OFF, AUTO, ON, TORCH }
    private var flashMode = FlashMode.OFF
    private var shutterOverlay: View? = null

    // =========================================================================================
    // UI Animation
    // =========================================================================================
    private var currentVisibleRect: RectF? = null
    private var rectAnimator: ValueAnimator? = null
    private var lastJpegOrientation = 0

    // =========================================================================================
    // Unused (for future)
    // =========================================================================================
    private var adaptiveResolution = false
    private lateinit var sizeLadder: List<Size>
    private var resolutionLevel = 0
    private var sizeIndex = 0
    private val MAX_W = 4000
    private val MAX_H = 4000

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
    // Surface Listener
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
    // Camera Open
    // =========================================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera(w: Int, h: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        cameraId = findCameraId(lensFacing)
        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)

        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)

        Log.d(TAG, "openCamera: aspect=$aspectMode, previewSize=$previewSize")

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
    // ImageReader Setup
    // =========================================================================================
    private fun setupImageReader() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val captureSize = jpegSizes.maxBy { it.width * it.height }

        Log.d(TAG, "setupImageReader: JPEG size = ${captureSize.width} x ${captureSize.height}")

        imageReader?.close()
        imageReader = ImageReader.newInstance(
            captureSize.width,
            captureSize.height,
            ImageFormat.JPEG,
            3
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener

            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).apply { buf.get(this) }
            img.close()

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rotated = rotateBitmap(bmp, lastJpegOrientation)
            val cropped = cropToAspect(rotated, aspectMode)

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 96, out)
            val finalBytes = out.toByteArray()

            onSaved(saveJpeg(finalBytes))
        }, bgHandler)
    }

    private fun recreateImageReader(size: Size) {
        imageReader?.close()
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)

        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).apply { buf.get(this) }
            img.close()

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rotated = rotateBitmap(bmp, lastJpegOrientation)
            val cropped = cropToAspect(rotated, aspectMode)

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 96, out)
            val finalBytes = out.toByteArray()

            onSaved(saveJpeg(finalBytes))
        }, bgHandler)
    }

    // =========================================================================================
    // Preview Session
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
                        applyFlash(this, forPreview = true)
                    }

                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler
        )
    }

    private fun restartPreviewSession() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        val jpeg = imageReader?.surface ?: return

        session?.close()
        session = null

        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)

        device.createCaptureSession(
            listOf(previewSurface, jpeg),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s

                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        applyCommonControls(this, preview = true)
                        applyColorAuto(this)
                        applyFlash(this, forPreview = true)
                    }

                    s.setRepeatingRequest(req.build(), null, bgHandler)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            },
            bgHandler
        )
    }

    private fun updateRepeating() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return

        val previewSurface = Surface(st)

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            applyCommonControls(this, preview = true)
            applyColorAuto(this)
            applyFlash(this, forPreview = true)
        }

        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    // =========================================================================================
    // Capture Request Controls
    // =========================================================================================
    private fun applyCommonControls(builder: CaptureRequest.Builder, preview: Boolean) {
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)

            val safeExp = currentExposureNs.coerceAtMost(frameNs - exposureMarginNs)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)

            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            if (preview) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExp)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
        }

        applyZoomAndAspect(builder)
    }

    private fun applyColorAuto(builder: CaptureRequest.Builder) {
        if (currentAwbMode == CameraMetadata.CONTROL_AWB_MODE_OFF && manualWbGains != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, manualWbGains)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        }
    }

    private fun applyFlash(builder: CaptureRequest.Builder, forPreview: Boolean) {
        if (!flashAvailable()) return

        when (flashMode) {
            FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                if (manualEnabled)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            }
            FlashMode.TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            FlashMode.AUTO -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_OFF else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            FlashMode.ON -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_OFF else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }
        }
    }

    // applyZoomAndAspect 함수를 이렇게 수정하세요:

    private fun applyZoomAndAspect(builder: CaptureRequest.Builder) {
        if (!::sensorArray.isInitialized) return

        val base = sensorArray
        val sensorW = base.width()
        val sensorH = base.height()

        // 타겟 비율 (가로/세로) - 센서는 가로 방향 기준
        val targetRatio = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 4f / 3f      // 센서 기준 (가로/세로)
            AspectMode.RATIO_9_16 -> 16f / 9f    // 센서 기준 (가로/세로)
        }

        val sensorRatio = sensorW.toFloat() / sensorH.toFloat()

        // 1) 비율에 맞는 base crop 영역 계산
        val (baseW, baseH) = if (sensorRatio > targetRatio) {
            // 센서가 더 넓음 → 가로를 줄임 (16:9 등)
            val newW = (sensorH * targetRatio).toInt()
            newW to sensorH
        } else {
            // 센서가 더 좁음 → 세로를 줄임 (1:1 등)
            val newH = (sensorW / targetRatio).toInt()
            sensorW to newH
        }

        // 2) 사용자 줌만 적용 (fillZoom 제거!)
        val zoom = currentZoom.coerceAtLeast(1f)
        val cropW = (baseW / zoom).toInt()
        val cropH = (baseH / zoom).toInt()

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

    // =========================================================================================
    // Take Picture
    // =========================================================================================
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
            applyFlash(this, forPreview = false)

            // Quality boost
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        }

        session?.capture(req.build(), null, bgHandler)
    }

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

    // =========================================================================================
    // Preview Transform & Letterbox
    // =========================================================================================
    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0 || vh <= 0) return

        val bw = previewSize.width.toFloat()
        val bh = previewSize.height.toFloat()

        val cx = vw / 2f
        val cy = vh / 2f

        // TextureView center-crop 스케일
        val scale = max(vw / bw, vh / bh)
        val m = Matrix().apply {
            setScale(scale, scale, cx, cy)
        }
        textureView.setTransform(m)

        // 화면비에 맞는 visible rect 계산
        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
        }

        val viewAspect = vw / vh

        val targetRect = if (viewAspect > targetAspect) {
            // 화면이 더 가로로 넓다 → 좌우 레터박스
            val activeWidth = vh * targetAspect
            val left = (vw - activeWidth) / 2f
            RectF(left, 0f, left + activeWidth, vh)
        } else {
            // 화면이 더 세로로 길다 → 위아래 레터박스
            val activeHeight = vw / targetAspect
            val top = (vh - activeHeight) / 2f
            RectF(0f, top, vw, top + activeHeight)
        }

        // 애니메이션 적용
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
                    lerp(startRect.left, targetRect.left),
                    lerp(startRect.top, targetRect.top),
                    lerp(startRect.right, targetRect.right),
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
    // Public Control Methods
    // =========================================================================================
    fun cycleAspectMode(): AspectMode {
        aspectMode = when (aspectMode) {
            AspectMode.RATIO_9_16 -> AspectMode.RATIO_1_1
            AspectMode.RATIO_1_1 -> AspectMode.RATIO_3_4
            AspectMode.RATIO_3_4 -> AspectMode.RATIO_9_16
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

    fun setManualEnabled(b: Boolean) {
        manualEnabled = b
        updateRepeating()
    }

    fun setTargetFps(fps: Int) {
        targetFps = if (fps <= 60) 60 else 120
        currentExposureNs = currentExposureNs.coerceAtMost(frameNs - exposureMarginNs)
        updateRepeating()
    }

    fun setIso(level: Int) {
        if (!::sizeLadder.isInitialized) return
        resolutionLevel = level.coerceIn(0, sizeLadder.size - 1)
        previewSize = sizeLadder[resolutionLevel]
        recreateImageReader(previewSize)
        restartPreviewSession()
    }

    fun setExposureTimeNs(ns: Long) {
        val capped = ns.coerceAtMost(frameNs - exposureMarginNs)
        currentExposureNs = capped.coerceIn(exposureRange.lower, exposureRange.upper)
        baseExposureNs = currentExposureNs
        updateRepeating()
    }

    fun setAwbMode(mode: Int) {
        currentAwbMode = mode
        updateRepeating()
    }

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
        val newZoom = (currentZoom * scale).coerceIn(1f, maxZoom())
        currentZoom = newZoom
        updateRepeating()
    }

    fun setMinIsoFloor(minIso: Int) {
        isoRange = Range(max(minIso, isoRange.lower), isoRange.upper)
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        updateRepeating()
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

    fun applyEv(ev: Double) {
        manualEnabled = true

        if (baseExposureNs == null) baseExposureNs = currentExposureNs
        if (baseIso == null) baseIso = currentIso

        val baseExp = baseExposureNs!!
        val baseIsoVal = baseIso!!

        val factor = Math.pow(2.0, ev)

        var newExp = (baseExp * factor).toLong()
        val maxExp = frameNs - 300_000L
        val minExp = 200_000L

        val expClamped = newExp.coerceIn(minExp, maxExp)
        val usedExpFactor = expClamped.toDouble() / baseExp.toDouble()

        val remainingFactor = factor / usedExpFactor
        var newIso = (baseIsoVal * remainingFactor).toInt()

        newIso = newIso.coerceIn(isoRange.lower, isoRange.upper)

        currentExposureNs = expClamped
        currentIso = newIso

        updateRepeating()
    }

    fun setFlashMode(m: FlashMode) {
        flashMode = m
        updateRepeating()
    }

    fun setResolutionPreset(preset: ResolutionPreset) {
        currentResolutionPreset = preset
        captureSize = preset.size
        recreateImageReader(captureSize)
        restartPreviewSession()
    }

    fun setAllAuto() {
        manualEnabled = false
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        manualWbGains = null
        currentExp = 0.coerceIn(expRange.lower, expRange.upper)
        updateRepeating()
    }

    fun setAllManual() {
        manualEnabled = true
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        updateRepeating()
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
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

    fun setAdaptiveResolutionEnabled(b: Boolean) {
        adaptiveResolution = b
    }

    fun setFillPreview(b: Boolean) {
        // Reserved for future use
    }

    // =========================================================================================
    // Getters
    // =========================================================================================
    fun getAspectMode(): AspectMode = aspectMode
    fun getFlashMode() = flashMode
    fun getResolutionPreset() = currentResolutionPreset
    fun getAppliedExposureNs() = currentExposureNs
    fun getCurrentIso() = currentIso
    fun getCurrentKelvin() = currentKelvin
    fun getEvRange(): Range<Int> = expRange
    fun getCurrentEv(): Int = currentExp

    private fun maxZoom(): Float {
        val maxZ = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return max(1f, maxZ)
    }

    private fun flashAvailable() =
        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    // =========================================================================================
    // Utility Functions
    // =========================================================================================
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun cropToAspect(src: Bitmap, mode: AspectMode): Bitmap {
        val w = src.width
        val h = src.height
        val srcRatio = w.toFloat() / h.toFloat()

        val targetRatio = when (mode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
        }

        if (abs(srcRatio - targetRatio) < 0.01f) {
            return src
        }

        return if (srcRatio > targetRatio) {
            val newWidth = (h * targetRatio).toInt()
            val x = (w - newWidth) / 2
            Bitmap.createBitmap(src, x, 0, newWidth, h)
        } else {
            val newHeight = (w / targetRatio).toInt()
            val y = (h - newHeight) / 2
            Bitmap.createBitmap(src, 0, y, w, newHeight)
        }
    }

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

    private fun fixedPreviewSizeFor(mode: AspectMode): Size {
        return when (mode) {
            AspectMode.RATIO_1_1 -> Size(1440, 1440)
            AspectMode.RATIO_3_4 -> Size(1440, 1920)
            AspectMode.RATIO_9_16 -> Size(1440, 2560)
        }
    }

    private fun nearestSupportedPreviewSize(
        desiredAspect: Float,
        map: StreamConfigurationMap
    ): Size {
        val all: Array<Size> = map.getOutputSizes(SurfaceTexture::class.java)

        val candidates = all.filter { s ->
            val r = s.width.toFloat() / s.height
            abs(r - desiredAspect) < 0.01f
        }

        val list: List<Size> = if (candidates.isNotEmpty()) candidates else all.toList()
        return list.maxBy { s -> s.width.toLong() * s.height.toLong() }
    }

    private fun maybeSwitchPreviewAspect() {
        // Reserved for future use
    }
}