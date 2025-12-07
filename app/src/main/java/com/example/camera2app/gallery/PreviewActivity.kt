package com.example.camera2app.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.camera2app.R
import com.example.camera2app.databinding.ActivityPreviewBinding
import com.example.camera2app.reference.LikeManager

class PreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_POSITION = "extra_position"
        private const val DELETE_REQUEST_CODE = 2001
    }

    private lateinit var binding: ActivityPreviewBinding
    private var photoList = mutableListOf<Uri>()
    private var currentPosition = 0

    private val currentUri: Uri?
        get() = if (photoList.isNotEmpty() && currentPosition in photoList.indices)
            photoList[currentPosition] else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LikeManager.init(this)

        // ✅ 공유 홀더에서 사진 목록 가져오기
        photoList = PhotoListHolder.photos.toMutableList()
        currentPosition = intent.getIntExtra(EXTRA_POSITION, 0)

        // ✅ 단일 사진 호환
        if (photoList.isEmpty()) {
            val singleUri = intent.getStringExtra(EXTRA_IMAGE_URI)
            singleUri?.let { photoList.add(Uri.parse(it)) }
        }

        if (photoList.isEmpty()) {
            Toast.makeText(this, "표시할 사진이 없습니다", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViewPager()
        setupButtons()
    }

    // -------------------------------
    // ✅ ViewPager
    // -------------------------------
    private fun setupViewPager() {
        binding.viewPager.adapter = PhotoPagerAdapter(photoList)
        binding.viewPager.setCurrentItem(currentPosition, false)

        binding.viewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    currentPosition = position
                    updateFavoriteIcon()
                }
            }
        )

        updateFavoriteIcon()
    }

    // -------------------------------
    // ✅ 버튼들
    // -------------------------------
    private fun setupButtons() {

        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnInfo.setOnClickListener {
            Toast.makeText(
                this,
                "ℹ 현재 Preview 화면에서는 AI 분석이 비활성화 되어 있습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnShare.setOnClickListener { sharePhoto() }
        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnDelete.setOnClickListener { confirmAndDeletePhoto() }
    }

    // -------------------------------
    // ✅ ViewPager Adapter
    // -------------------------------
    inner class PhotoPagerAdapter(private val photos: List<Uri>) :
        RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

        inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.pagerImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pager_image, parent, false)
            return PhotoViewHolder(view)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            Glide.with(holder.itemView.context)
                .load(photos[position])
                .into(holder.imageView)
        }

        override fun getItemCount() = photos.size
    }

    // -------------------------------
    // ✅ Bitmap 로드
    // -------------------------------
    private fun loadBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    // -------------------------------
    // ✅ 공유
    // -------------------------------
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

    // -------------------------------
    // ✅ 즐겨찾기
    // -------------------------------
    private fun toggleFavorite() {
        if (currentUri == null) return

        try {
            val uriString = currentUri.toString()
            val isNowLiked = LikeManager.toggleLike(uriString)

            val message =
                if (isNowLiked) "레퍼런스에 추가되었습니다"
                else "레퍼런스에서 제거되었습니다"

            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            updateFavoriteIcon()
        } catch (e: Exception) {
            Toast.makeText(this, "처리 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("PREVIEW", "Toggle favorite failed", e)
        }
    }

    private fun updateFavoriteIcon() {
        if (currentUri == null) return

        val isLiked = LikeManager.isLiked(currentUri.toString())
        binding.btnFavorite.setImageResource(
            if (isLiked) R.drawable.ic_heart_filled
            else R.drawable.ic_heart_empty
        )
    }

    // -------------------------------
    // ✅ 삭제
    // -------------------------------
    private fun confirmAndDeletePhoto() {
        if (currentUri == null) {
            Toast.makeText(this, "삭제할 사진이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("사진 삭제")
            .setMessage("이 사진을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> deletePhoto() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deletePhoto() {
        if (currentUri == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            deletePhotoModern()
        } else {
            deletePhotoLegacy()
        }
    }

    private fun deletePhotoModern() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && currentUri != null) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(currentUri!!)
                )
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    DELETE_REQUEST_CODE,
                    null,
                    0,
                    0,
                    0
                )
            } catch (e: Exception) {
                Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("PREVIEW", "Delete failed", e)
            }
        }
    }

    private fun deletePhotoLegacy() {
        if (currentUri == null) return

        try {
            val deleted = contentResolver.delete(currentUri!!, null, null)
            if (deleted > 0) {
                removeCurrentPhoto()
            } else {
                Toast.makeText(this, "삭제 실패", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("PREVIEW", "Failed to delete", e)
        }
    }

    private fun removeCurrentPhoto() {
        if (photoList.isEmpty()) {
            finish()
            return
        }

        photoList.removeAt(currentPosition)

        if (photoList.isEmpty()) {
            Toast.makeText(this, "사진이 삭제되었습니다", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            currentPosition = currentPosition.coerceAtMost(photoList.size - 1)
            binding.viewPager.adapter?.notifyDataSetChanged()
            binding.viewPager.setCurrentItem(currentPosition, false)
            updateFavoriteIcon()
            Toast.makeText(this, "사진이 삭제되었습니다", Toast.LENGTH_SHORT).show()
        }
    }



}
