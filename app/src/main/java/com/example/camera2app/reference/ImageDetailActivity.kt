package com.example.camera2app.reference
import com.example.camera2app.MainActivity


import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.camera2app.R
import com.example.camera2app.ai.PoseEstimationService
import com.example.camera2app.ai.RealtimeAnalyzer
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import android.view.ViewGroup
import android.graphics.Bitmap



class ImageDetailActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnSelect: MaterialButton
    private lateinit var btnBack: ImageView
    private var loadingView: View? = null


    private var imageResId: Int = 0
    private var imageUri: Uri? = null

    private var poseService: PoseEstimationService? = null
    private var analyzer: RealtimeAnalyzer? = null

    private val uiScope = MainScope()

    companion object {
        const val EXTRA_IMAGE_RES_ID = "image_res_id"
        const val EXTRA_IMAGE_URI = "image_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_detail)

        initViews()
        loadImage()
        setupListeners()
    }

    private fun initViews() {
        imageView = findViewById(R.id.imageDetail)
        btnSelect = findViewById(R.id.btnSelectImage)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun createLoadingOverlay() {
        val inflater = layoutInflater
        loadingView = inflater.inflate(R.layout.loading_overlay2, null)

        addContentView(
            loadingView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }



    private fun showLoading() {
        if (loadingView == null) {
            createLoadingOverlay()
        }
        loadingView?.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingView?.visibility = View.GONE
    }


    private fun loadImage() {
        imageResId = intent.getIntExtra(EXTRA_IMAGE_RES_ID, 0)

        if (imageResId != 0) {
            imageView.setImageResource(imageResId)
            return
        }

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString != null) {
            imageUri = Uri.parse(uriString)
            imageView.setImageURI(imageUri)
            return
        }

        Toast.makeText(this, "이미지를 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnSelect.setOnClickListener {
            showLoading()
            handleSelectImage()
        }
    }

    private fun handleSelectImage() {
        showLoading()

        uiScope.launch(Dispatchers.IO) {
            try {
                initializeAI()

                val resultIntent = Intent(this@ImageDetailActivity, MainActivity::class.java)

                // 분석 모드 ON
                resultIntent.putExtra("analysis_mode", true)

                var finalUri: Uri? = null

                if (imageResId != 0) {
                    // 리소스 → Bitmap 로드
                    val bitmap = BitmapFactory.decodeResource(resources, imageResId)

                    // 파일로 저장
                    val file = File(cacheDir, "ref_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }

                    // 파일 URI 생성
                    finalUri = FileProvider.getUriForFile(
                        this@ImageDetailActivity,
                        "${packageName}.fileprovider",
                        file
                    )

                } else if (imageUri != null) {
                    finalUri = imageUri
                }

                if (finalUri == null) {
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        Toast.makeText(this@ImageDetailActivity, "이미지 처리 실패", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 레퍼런스 URI 전달
                resultIntent.putExtra("reference_uri", finalUri.toString())

                // 권한 유지
                resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                withContext(Dispatchers.Main) {
                    hideLoading()
                    startActivity(resultIntent)
                    finishAffinity()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    Toast.makeText(
                        this@ImageDetailActivity,
                        "AI 준비 실패: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }



    /** AI 초기화 (무거운 작업) → 여기서 미리 다 끝내버림 */
    private fun initializeAI() {
        if (poseService == null) {
            poseService = PoseEstimationService(this)
            poseService?.initialize()
        }

        if (analyzer == null) {
            analyzer = RealtimeAnalyzer(poseService!!)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}
