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
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.camera2app.databinding.ActivityGalleryBinding
import com.example.camera2app.databinding.ActivityGallerySelectableBinding
import com.example.camera2app.reference.LikeManager
import com.example.camera2app.ai.TryAngleFeedback
import com.example.camera2app.MainActivity




class GalleryActivity : ComponentActivity() {

    private lateinit var normalBinding: ActivityGalleryBinding
    private lateinit var selectBinding: ActivityGallerySelectableBinding
    private var inSelectMode = false

    private val photos = mutableListOf<Uri>()
    private val selectedPhotos = mutableListOf<Uri>()

    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadMediaIfGranted() }




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        normalBinding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(normalBinding.root)

        // ★ LikeManager 초기화
        LikeManager.init(this)

        setupNormalUI()
        ensurePermissionThenLoad()
    }

    // ---------------------- Normal Mode ----------------------
    private fun setupNormalUI() {
        inSelectMode = false

        normalBinding.photoGrid.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)

            // 기존 ItemDecoration 제거 후 새로 추가
            while (itemDecorationCount > 0) {
                removeItemDecorationAt(0)
            }
            addItemDecoration(GridSpacingItemDecoration(3, dpToPx(1), false))

            adapter = PhotoAdapter(this@GalleryActivity, photos) { pos ->
                // ★ 롱클릭 시 선택 모드 진입
                enterSelectMode(pos)
            }
        }

        normalBinding.btnClose.setOnClickListener { finish() }
    }

    // ---------------------- Select Mode ----------------------
    private fun enterSelectMode(firstPos: Int) {
        selectedPhotos.clear()

        inSelectMode = true
        selectBinding = ActivityGallerySelectableBinding.inflate(layoutInflater)
        setContentView(selectBinding.root)

        setupSelectUI()
    }

    private fun setupSelectUI() {
        selectBinding.photoGridSelectable.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)

            // 기존 ItemDecoration 제거 후 새로 추가
            while (itemDecorationCount > 0) {
                removeItemDecorationAt(0)
            }
            addItemDecoration(GridSpacingItemDecoration(3, dpToPx(1), false))

            adapter = PhotoSelectableAdapter(
                photos,
                photos.associateWith { uri ->
                    MainActivity.feedbackMap[uri.toString()]
                },
                selectedPhotos
            ) {
                updateBottomMenu()
            }



        }

        // ★ 닫기 버튼
        selectBinding.btnClose.setOnClickListener {
            setContentView(normalBinding.root)
            setupNormalUI()
        }

        // ========== 하단 메뉴 버튼 기능 ==========

        // ⭐ 공유 버튼
        selectBinding.btnShare.setOnClickListener {
            shareSelectedPhotos()
        }

        // ⭐ 하트 버튼 (레퍼런스 My에 추가)
        selectBinding.btnFav.setOnClickListener {
            addToReference()
        }

        // ⭐ 삭제 버튼
        selectBinding.btnDelete.setOnClickListener {
            confirmAndDeletePhotos()
        }

        updateBottomMenu()
    }

    private fun updateBottomMenu() {
        selectBinding.bottomMenu.visibility =
            if (selectedPhotos.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ========== 공유 기능 ==========
    private fun shareSelectedPhotos() {
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(this, "공유할 사진을 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val shareIntent = Intent().apply {
                if (selectedPhotos.size == 1) {
                    // 단일 사진
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, selectedPhotos[0])
                    type = "image/*"
                } else {
                    // 여러 사진
                    action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        ArrayList(selectedPhotos)
                    )
                    type = "image/*"
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "사진 공유"))
            Toast.makeText(this, "${selectedPhotos.size}개 사진 공유", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "공유 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("GALLERY", "Share failed", e)
        }
    }

    // ========== 레퍼런스에 추가 기능 ==========
    private fun addToReference() {
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(this, "추가할 사진을 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // LikeManager를 사용해서 각 사진을 My에 추가
            selectedPhotos.forEach { uri ->
                LikeManager.addLike(uri.toString())
            }

            Toast.makeText(
                this,
                "${selectedPhotos.size}개 사진이 레퍼런스에 추가되었습니다",
                Toast.LENGTH_SHORT
            ).show()

            // 선택 해제 및 일반 모드로 복귀
            selectedPhotos.clear()
            setContentView(normalBinding.root)
            setupNormalUI()

        } catch (e: Exception) {
            Toast.makeText(this, "추가 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("GALLERY", "Add to reference failed", e)
        }
    }

    // ========== 삭제 기능 ==========
    private fun confirmAndDeletePhotos() {
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(this, "삭제할 사진을 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }

        // 삭제 확인 다이얼로그
        AlertDialog.Builder(this)
            .setTitle("사진 삭제")
            .setMessage("선택한 ${selectedPhotos.size}개의 사진을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                deleteSelectedPhotos()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteSelectedPhotos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) 이상: MediaStore의 createDeleteRequest 사용
            deletePhotosModern()
        } else {
            // Android 10 이하: 직접 삭제
            deletePhotosLegacy()
        }
    }

    // Android 11+ 삭제 방식
    private fun deletePhotosModern() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(
                    contentResolver,
                    selectedPhotos
                )

                // 시스템 다이얼로그 표시
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    DELETE_REQUEST_CODE,
                    null, 0, 0, 0
                )

            } catch (e: Exception) {
                Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("GALLERY", "Delete failed", e)
            }
        }
    }

    // Android 10 이하 삭제 방식
    private fun deletePhotosLegacy() {
        var successCount = 0
        var failCount = 0

        selectedPhotos.forEach { uri ->
            try {
                val deleted = contentResolver.delete(uri, null, null)
                if (deleted > 0) successCount++ else failCount++
            } catch (e: Exception) {
                failCount++
                Log.e("GALLERY", "Failed to delete: $uri", e)
            }
        }

        // 결과 메시지
        val message = if (failCount == 0) {
            "${successCount}개 사진이 삭제되었습니다"
        } else {
            "${successCount}개 삭제 성공, ${failCount}개 실패"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        // 삭제된 사진을 목록에서 제거
        photos.removeAll(selectedPhotos)
        selectedPhotos.clear()

        // UI 갱신
        setContentView(normalBinding.root)
        setupNormalUI()
    }

    // 삭제 요청 결과 처리 (Android 11+)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == DELETE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(
                    this,
                    "${selectedPhotos.size}개 사진이 삭제되었습니다",
                    Toast.LENGTH_SHORT
                ).show()

                // 삭제된 사진을 목록에서 제거
                photos.removeAll(selectedPhotos)
                selectedPhotos.clear()

                // UI 갱신
                setContentView(normalBinding.root)
                setupNormalUI()
            } else {
                Toast.makeText(this, "삭제가 취소되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------- Permission / Load Photos ----------------------
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

    private fun loadMediaIfGranted() = ensurePermissionThenLoad()

    private fun loadPhotos() {
        photos.clear()

        val volumes = listOf(
            MediaStore.VOLUME_EXTERNAL,
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        for (volume in volumes) {
            val collection = if (Build.VERSION.SDK_INT >= 29)
                MediaStore.Images.Media.getContentUri(volume)
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            contentResolver.query(
                collection, projection, null, null, sort
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id)
                    photos += uri
                }
            }
        }

        Log.d("GALLERY", "photos loaded = ${photos.size}")

        // ★ 추가: 공유 홀더에 저장
        PhotoListHolder.photos = photos.toList()

        if (inSelectMode) {
            setContentView(selectBinding.root)
            setupSelectUI()
        } else {
            setContentView(normalBinding.root)
            setupNormalUI()
        }


    }



    // ---------------------- Utility ----------------------
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ---------------------- Grid Spacing ItemDecoration ----------------------
    class GridSpacingItemDecoration(
        private val spanCount: Int,
        private val spacing: Int,
        private val includeEdge: Boolean
    ) : RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val column = position % spanCount

            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCount
                outRect.right = (column + 1) * spacing / spanCount

                if (position < spanCount) {
                    outRect.top = spacing
                }
                outRect.bottom = spacing
            } else {
                outRect.left = column * spacing / spanCount
                outRect.right = spacing - (column + 1) * spacing / spanCount
                if (position >= spanCount) {
                    outRect.top = spacing
                }
            }
        }
    }

    companion object {
        private const val DELETE_REQUEST_CODE = 1001
    }
}