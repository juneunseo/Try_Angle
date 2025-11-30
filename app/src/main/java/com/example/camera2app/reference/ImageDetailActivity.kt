package com.example.camera2app.reference

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.camera2app.R
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream

class ImageDetailActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnSelect: MaterialButton
    private lateinit var btnBack: ImageView

    private var imageResId: Int = 0
    private var imageUri: Uri? = null

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

    private fun loadImage() {
        // 1️⃣ 리소스 이미지 먼저 시도
        imageResId = intent.getIntExtra(EXTRA_IMAGE_RES_ID, 0)
        if (imageResId != 0) {
            imageView.setImageResource(imageResId)
            return
        }

        // 2️⃣ URI 이미지 처리
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
            selectImage()
        }
    }

    private fun selectImage() {
        try {
            val resultIntent = Intent()

            // 1️⃣ 리소스 이미지인 경우
            if (imageResId != 0) {
                val bitmap = BitmapFactory.decodeResource(resources, imageResId)

                val file = File(cacheDir, "reference_image_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }

                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )

                resultIntent.data = uri
                resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
                return
            }

            // 2️⃣ URI 이미지인 경우 (그대로 반환)
            if (imageUri != null) {
                resultIntent.data = imageUri
                resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
                return
            }

            Toast.makeText(this, "이미지 처리 실패", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "에러 발생: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
