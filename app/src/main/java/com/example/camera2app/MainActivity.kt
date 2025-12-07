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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
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
import androidx.core.view.doOnLayout



import android.content.pm.PackageManager


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller

    // ✅ AI 시스템
    private lateinit var yoloxDetector: YoloXDetector
    private lateinit var poseEstimator: RTMPoseEstimator
    private var isAIInitialized = false

    // ✅ 레퍼런스
    private var referenceBitmap: Bitmap? = null
    private var isReferenceSet = false

    // ✅ 마지막 캡쳐
    private var lastCapturedBitmap: Bitmap? = null
    private var lastCapturedUri: Uri? = null

    // ✅ 로딩 오버레이
    private var loadingOverlay: View? = null

    companion object {
        private const val REQUEST_REFERENCE_IMAGE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                    initCameraController()
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

        binding.textureView.doOnLayout {
            Log.e("CAMERA_FLOW", "✅ TextureView 실제 크기: ${it.width} x ${it.height}")
            initCameraController()
        }


    }

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

    private fun setupTextureListener() {
        binding.textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                    initCameraController()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
    }



    // ✅ AI 초기화 (YOLOX + RTMPose)
    private fun initAISystem() {
        Thread {
            try {
                val start = System.currentTimeMillis()

                yoloxDetector = YoloXDetector(this)
                poseEstimator = RTMPoseEstimator(this)

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

    // ✅ 카메라 컨트롤러
    private fun initCameraController() {

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
                }
            },
            previewContainer = binding.previewContainer
        ) { fps ->
            runOnUiThread {
                binding.fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
            }
        }

        // ✅✅✅ 여기서 바로 카메라 시작 (핵심)
        controller.onResume()
    }


    // ✅ 촬영 → AI 분석
    private fun processCapturedPhoto(bitmap: Bitmap, uri: Uri) {

        if (!isAIInitialized) {
            Toast.makeText(this, "AI 로딩 중...", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isReferenceSet || referenceBitmap == null) {
            Toast.makeText(this, "레퍼런스를 먼저 설정하세요", Toast.LENGTH_SHORT).show()
            return
        }

        showLoadingOverlay()

        Thread {
            try {
                // ✅ 1. 사람 검출
                val bbox = yoloxDetector.detectPerson(bitmap)
                val refBbox = yoloxDetector.detectPerson(referenceBitmap!!)

                if (bbox == null || refBbox == null) {
                    runOnUiThread {
                        hideLoadingOverlay()
                        Toast.makeText(this, "사람 인식 실패", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // ✅ 2. 포즈 추정
                val pose1 = poseEstimator.estimatePose(bitmap, bbox)
                val pose2 = poseEstimator.estimatePose(referenceBitmap!!, refBbox)

                if (pose1 == null || pose2 == null) {
                    runOnUiThread {
                        hideLoadingOverlay()
                        Toast.makeText(this, "포즈 추정 실패", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // ✅ 3. 유사도 점수
                val score = calculatePoseSimilarity(pose1, pose2)
                val msg = generateFeedbackMessage(score)

                runOnUiThread {
                    hideLoadingOverlay()

                    val intent = Intent(this,
                        com.example.camera2app.gallery.FeedbackScoreActivity::class.java).apply {

                        putExtra(
                            com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_CAPTURED_URI,
                            uri.toString()
                        )
                        putExtra(
                            com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_SCORE,
                            score
                        )
                        putExtra(
                            com.example.camera2app.gallery.FeedbackScoreActivity.EXTRA_FEEDBACK_MESSAGE,
                            msg
                        )
                    }
                    startActivity(intent)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    hideLoadingOverlay()
                    Toast.makeText(this, "분석 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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

    // ✅ 버튼들
    private fun initButtons() {

        binding.btnShutter.setOnClickListener {
            controller.takePictureWithTimer()
        }

        binding.menuReference.setOnClickListener {
            val intent =
                Intent(this, com.example.camera2app.reference.ReferenceActivity::class.java)
            startActivityForResult(intent, REQUEST_REFERENCE_IMAGE)
        }

        binding.lastThumbnail.setOnClickListener {
            val bmp = lastCapturedBitmap
            val uri = lastCapturedUri
            if (bmp != null && uri != null) {
                processCapturedPhoto(bmp, uri)
            }
        }

        binding.menuGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    // ✅ 레퍼런스 선택 결과
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_REFERENCE_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            referenceBitmap = uriToBitmap(uri)
            isReferenceSet = true
            Toast.makeText(this, "✅ 레퍼런스 설정 완료", Toast.LENGTH_SHORT).show()
        }
    }

    // ✅ Bitmap 로딩
    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ✅ 로딩 오버레이
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

    // ✅ 권한
    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            needs += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            needs += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        Permissions.requestIfNeeded(this, needs.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        lastCapturedBitmap?.recycle()
        lastCapturedBitmap = null
    }

    override fun onResume() {
        super.onResume()
        // ❌ 여기서 controller 건드리지 마
    }



    override fun onPause() {
        if (::controller.isInitialized) {
            controller.onPause()
        }
        super.onPause()
    }


}
