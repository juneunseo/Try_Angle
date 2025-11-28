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

    companion object {
        const val EXTRA_IMAGE_RES_ID = "image_res_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        println("🟢🟢🟢 ImageDetailActivity: onCreate 시작!")

        try {
            setContentView(R.layout.activity_image_detail)
            println("🟢 setContentView 성공")

            imageResId = intent.getIntExtra(EXTRA_IMAGE_RES_ID, 0)
            println("🟢 imageResId = $imageResId")

            if (imageResId == 0) {
                println("🔴 imageResId가 0입니다!")
                Toast.makeText(this, "이미지를 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            println("🟢 initViews 시작")
            initViews()
            println("🟢 setupListeners 시작")
            setupListeners()
            println("🟢🟢🟢 ImageDetailActivity: onCreate 완료!")

        } catch (e: Exception) {
            println("🔴🔴🔴 ImageDetailActivity 에러!")
            e.printStackTrace()
            Toast.makeText(this, "에러: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    private fun initViews() {
        imageView = findViewById(R.id.imageDetail)
        btnSelect = findViewById(R.id.btnSelectImage)
        btnBack = findViewById(R.id.btnBack)

        // 이미지 표시
        imageView.setImageResource(imageResId)
    }

    private fun setupListeners() {
        // 뒤로가기 버튼
        btnBack.setOnClickListener {
            finish()
        }

        // "이 사진 선택하기" 버튼
        btnSelect.setOnClickListener {
            selectImage()
        }
    }

    private fun selectImage() {
        try {
            // Drawable 리소스 → Bitmap
            val bitmap = BitmapFactory.decodeResource(resources, imageResId)

            // 임시 파일로 저장
            val file = File(cacheDir, "reference_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            // Uri 생성 (FileProvider 사용)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            // 결과 반환
            val resultIntent = Intent().apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "이미지 처리 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}