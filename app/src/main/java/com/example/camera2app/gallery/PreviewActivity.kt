package com.example.camera2app.gallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ActivityPreviewBinding
import com.example.camera2app.reference.LikeManager

class PreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val DELETE_REQUEST_CODE = 2001
    }

    private lateinit var binding: ActivityPreviewBinding
    private var currentUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ★ LikeManager 초기화
        LikeManager.init(this)

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        currentUri = uriStr?.let { Uri.parse(it) }

        if (currentUri != null) {
            Glide.with(this)
                .load(currentUri)
                .into(binding.imageFull)

            // ⭐ 하트 아이콘 초기 상태 설정
            updateFavoriteIcon()
        }

        // 🔙 뒤로가기 버튼
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // ⭐ 상단 오른쪽 버튼 → FeedbackActivity 이동
        binding.btnInfo.setOnClickListener {
            val intent = Intent(this, FeedbackActivity::class.java)
            intent.putExtra(FeedbackActivity.EXTRA_IMAGE_URI, uriStr)
            startActivity(intent)
        }

        // ========== 하단 버튼 기능 ==========

        // ⭐ 공유 버튼
        binding.btnShare.setOnClickListener {
            sharePhoto()
        }

        // ⭐ 하트 버튼
        binding.btnFavorite.setOnClickListener {
            toggleFavorite()
        }

        // ⭐ 삭제 버튼
        binding.btnDelete.setOnClickListener {
            confirmAndDeletePhoto()
        }
    }

    // ========== 공유 기능 ==========
    private fun sharePhoto() {
        if (currentUri == null) {
            Toast.makeText(this, "공유할 사진이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, currentUri)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "사진 공유"))

        } catch (e: Exception) {
            Toast.makeText(this, "공유 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("PREVIEW", "Share failed", e)
        }
    }

    // ========== 레퍼런스 추가/제거 기능 ==========
    private fun toggleFavorite() {
        if (currentUri == null) return

        try {
            val uriString = currentUri.toString()
            val isNowLiked = LikeManager.toggleLike(uriString)

            // 토스트 메시지
            val message = if (isNowLiked) {
                "레퍼런스에 추가되었습니다"
            } else {
                "레퍼런스에서 제거되었습니다"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            // 아이콘 업데이트
            updateFavoriteIcon()

        } catch (e: Exception) {
            Toast.makeText(this, "처리 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("PREVIEW", "Toggle favorite failed", e)
        }
    }

    /**
     * 하트 아이콘 상태 업데이트
     */
    private fun updateFavoriteIcon() {
        if (currentUri == null) return

        val isLiked = LikeManager.isLiked(currentUri.toString())

        // 하트 아이콘 변경
        // ic_heart_filled가 있다면 사용, 없으면 ic_heart_empty 유지
        binding.btnFavorite.setImageResource(
            if (isLiked) {
                // ⭐ ic_heart_filled drawable이 있다면 사용
                // 없다면 ic_heart_empty 그대로 사용 (색상으로 구분 가능)
                try {
                    com.example.camera2app.R.drawable.ic_heart_filled
                } catch (e: Exception) {
                    com.example.camera2app.R.drawable.ic_heart_empty
                }
            } else {
                com.example.camera2app.R.drawable.ic_heart_empty
            }
        )
    }

    // ========== 삭제 기능 ==========
    private fun confirmAndDeletePhoto() {
        if (currentUri == null) {
            Toast.makeText(this, "삭제할 사진이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        // 삭제 확인 다이얼로그
        AlertDialog.Builder(this)
            .setTitle("사진 삭제")
            .setMessage("이 사진을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                deletePhoto()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deletePhoto() {
        if (currentUri == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ : MediaStore의 createDeleteRequest 사용
            deletePhotoModern()
        } else {
            // Android 10 이하: 직접 삭제
            deletePhotoLegacy()
        }
    }

    // Android 11+ 삭제 방식
    private fun deletePhotoModern() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && currentUri != null) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(currentUri!!)
                )

                // 시스템 다이얼로그 표시
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    DELETE_REQUEST_CODE,
                    null, 0, 0, 0
                )

            } catch (e: Exception) {
                Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("PREVIEW", "Delete failed", e)
            }
        }
    }

    // Android 10 이하 삭제 방식
    private fun deletePhotoLegacy() {
        if (currentUri == null) return

        try {
            val deleted = contentResolver.delete(currentUri!!, null, null)

            if (deleted > 0) {
                Toast.makeText(this, "사진이 삭제되었습니다", Toast.LENGTH_SHORT).show()

                // 삭제 성공 시 액티비티 종료
                finish()
            } else {
                Toast.makeText(this, "삭제 실패", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("PREVIEW", "Failed to delete", e)
        }
    }

    // 삭제 요청 결과 처리 (Android 11+)
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == DELETE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "사진이 삭제되었습니다", Toast.LENGTH_SHORT).show()

                // 삭제 성공 시 액티비티 종료
                finish()
            } else {
                Toast.makeText(this, "삭제가 취소되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}