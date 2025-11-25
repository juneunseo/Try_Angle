package com.example.camera2app.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.camera2app.R
import com.example.camera2app.databinding.ActivityGalleryBinding
import com.example.camera2app.databinding.ItemPhotoBinding


class GalleryActivity : ComponentActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val photos = mutableListOf<Uri>()

    // 선택 상태
    private var selectionMode = false
    private var selectedUri: Uri? = null

    // 권한 요청
    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadMediaIfGranted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3열 그리드
        binding.photoGrid.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = PhotoAdapter()
            addItemDecoration(GridSpacing(3, dp(2), includeEdge = false))
        }

        binding.btnClose.setOnClickListener { finish() }

        binding.btnEdit.setOnClickListener {
            selectedUri?.let {
                val intent = Intent(this, PreviewActivity::class.java)
                intent.putExtra(PreviewActivity.EXTRA_IMAGE_URI, it.toString())
                startActivity(intent)
            }
        }



        ensurePermissionThenLoad()
        updateBottomMenuVisibility()
    }

    // 권한 처리
    private fun ensurePermissionThenLoad() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val granted = perms.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (granted) loadPhotos()
        else requestPerm.launch(perms)
    }

    private fun loadMediaIfGranted() {
        val ok = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

        if (ok) loadPhotos()
    }

    // 사진 불러오기
    private fun loadPhotos() {
        photos.clear()

        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, proj, null, null, sort)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val uri = ContentUris.withAppendedId(collection, id)
                photos += uri
            }
        }

        binding.photoGrid.adapter?.notifyDataSetChanged()
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v + 0.5f).toInt()

    // ─────────────────────
    // Adapter
    // ─────────────────────

    inner class PhotoAdapter :
        RecyclerView.Adapter<PhotoAdapter.PhotoVH>() {

        inner class PhotoVH(val b: ItemPhotoBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(uri: Uri) {

                // 썸네일 표시
                Glide.with(b.thumb)
                    .load(uri)
                    .centerCrop()
                    .into(b.thumb)

                val isSelected = (selectionMode && selectedUri == uri)

                if (isSelected) {
                    b.selectionOverlay.visibility = View.VISIBLE
                    b.checkIcon.visibility = View.VISIBLE
                    b.checkIcon.setImageResource(R.drawable.ic_select_checked)
                }
                else if (selectionMode) {
                    b.selectionOverlay.visibility = View.GONE
                    b.checkIcon.visibility = View.VISIBLE
                    b.checkIcon.setImageResource(R.drawable.ic_select_empty)
                }
                else {
                    b.selectionOverlay.visibility = View.GONE
                    b.checkIcon.visibility = View.GONE
                }


                // 🔥 클릭 → 선택 변경
                b.root.setOnClickListener {
                    if (selectionMode) {
                        selectedUri = uri
                        notifyDataSetChanged()
                    }
                }

                // 🔥 길게 눌러서 선택모드 진입
                b.root.setOnLongClickListener {
                    if (!selectionMode) {
                        selectionMode = true
                        selectedUri = uri
                        notifyDataSetChanged()
                    }
                    true
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
            return PhotoVH(
                ItemPhotoBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun getItemCount() = photos.size

        override fun onBindViewHolder(holder: PhotoVH, position: Int) {
            holder.bind(photos[position])
        }
    }





    // 프리뷰 화면 이동 함수
    private fun goToPreview(uri: Uri) {
        val intent = Intent(this, PreviewActivity::class.java)
        intent.putExtra(PreviewActivity.EXTRA_IMAGE_URI, uri.toString())
        startActivity(intent)
    }

    // 하단 메뉴 표시 여부
    private fun updateBottomMenuVisibility() {
        binding.bottomMenu.visibility =
            if (selectionMode && selectedUri != null) View.VISIBLE else View.GONE
    }

    // 간격
    class GridSpacing(
        private val spanCount: Int,
        private val spacingPx: Int,
        private val includeEdge: Boolean
    ) : RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val pos = parent.getChildAdapterPosition(view)
            val col = pos % spanCount

            if (includeEdge) {
                outRect.left = spacingPx - col * spacingPx / spanCount
                outRect.right = (col + 1) * spacingPx / spanCount
                if (pos < spanCount) outRect.top = spacingPx
                outRect.bottom = spacingPx
            } else {
                outRect.left = col * spacingPx / spanCount
                outRect.right = spacingPx - (col + 1) * spacingPx / spanCount
                if (pos >= spanCount) outRect.top = spacingPx
            }
        }
    }
}
