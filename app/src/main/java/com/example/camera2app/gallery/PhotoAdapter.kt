package com.example.camera2app.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ItemPhotoBinding

// PhotoAdapter.kt 수정
class PhotoAdapter(
    private val context: Context,
    private val photos: List<Uri>,
    private val onLongClick: ((Int) -> Unit)? = null  // ★ 롱클릭 콜백 추가
) : RecyclerView.Adapter<PhotoAdapter.PhotoVH>() {

    inner class PhotoVH(val b: ItemPhotoBinding) : RecyclerView.ViewHolder(b.root)

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
        val uri = photos[position]

        Glide.with(holder.b.thumb)
            .load(uri)
            .centerCrop()
            .into(holder.b.thumb)

        // 일반 클릭 → 프리뷰
        holder.b.root.setOnClickListener {
            val intent = Intent(context, PreviewActivity::class.java)
            intent.putExtra(PreviewActivity.EXTRA_IMAGE_URI, uri.toString())
            context.startActivity(intent)
        }

        // ★ 롱클릭 → 선택 모드 진입
        holder.b.root.setOnLongClickListener {
            onLongClick?.invoke(position)
            true
        }
    }
}
