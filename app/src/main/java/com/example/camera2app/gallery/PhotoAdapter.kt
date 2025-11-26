package com.example.camera2app.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ItemPhotoBinding

class PhotoAdapter(
    private val context: Context,
    private val photos: List<Uri>
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

        // Load thumbnail
        Glide.with(holder.b.thumb)
            .load(uri)
            .centerCrop()
            .into(holder.b.thumb)

        holder.b.root.setOnClickListener {
            val intent = Intent(context, PreviewActivity::class.java)
            intent.putExtra(PreviewActivity.EXTRA_IMAGE_URI, uri.toString())
            context.startActivity(intent)
        }
    }
}

