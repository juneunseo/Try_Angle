package com.example.camera2app.gallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.camera2app.databinding.ActivityGalleryBinding
import com.example.camera2app.databinding.ActivityGallerySelectableBinding
import com.example.camera2app.util.RecyclerItemClickListener

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

        setupNormalUI()
        ensurePermissionThenLoad()
    }

    // ---------------------- Normal Mode ----------------------
    private fun setupNormalUI() {
        inSelectMode = false

        normalBinding.photoGrid.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = PhotoAdapter(this@GalleryActivity, photos)
        }

        normalBinding.btnClose.setOnClickListener { finish() }

        normalBinding.photoGrid.addOnItemTouchListener(
            RecyclerItemClickListener(
                this,
                normalBinding.photoGrid,
                onLongClick = { pos -> enterSelectMode(pos) }
            )
        )
    }

    // ---------------------- Select Mode ----------------------
    private fun enterSelectMode(firstPos: Int) {
        selectedPhotos.clear()
        selectedPhotos.add(photos[firstPos])

        inSelectMode = true
        selectBinding = ActivityGallerySelectableBinding.inflate(layoutInflater)
        setContentView(selectBinding.root)

        setupSelectUI()
    }

    private fun setupSelectUI() {
        selectBinding.photoGridSelectable.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = PhotoSelectableAdapter(photos, selectedPhotos) {
                updateBottomMenu()
            }
        }

        selectBinding.btnClose.setOnClickListener {
            setContentView(normalBinding.root)
            setupNormalUI()
        }

        updateBottomMenu()
    }

    private fun updateBottomMenu() {
        selectBinding.bottomMenu.visibility =
            if (selectedPhotos.isNotEmpty()) View.VISIBLE else View.GONE
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

        if (inSelectMode) {
            setContentView(selectBinding.root)
            setupSelectUI()
        } else {
            setContentView(normalBinding.root)
            setupNormalUI()
        }
    }
}
