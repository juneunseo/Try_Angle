package com.example.camera2app.gallery

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.camera2app.R
import com.example.camera2app.databinding.ItemPhotoSelectableBinding

class PhotoSelectableAdapter(
    private val photos: List<Uri>,
    private val selected: MutableList<Uri>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<PhotoSelectableAdapter.VH>() {

    inner class VH(val b: ItemPhotoSelectableBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            ItemPhotoSelectableBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun getItemCount() = photos.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = photos[position]

        Glide.with(holder.b.thumb)
            .load(uri)
            .centerCrop()
            .into(holder.b.thumb)

        val isOn = selected.contains(uri)

        holder.b.selectionOverlay.visibility = if (isOn) View.VISIBLE else View.GONE
        holder.b.checkIcon.visibility = if (isOn) View.VISIBLE else View.GONE

        holder.b.root.setOnClickListener {
            if (isOn) selected.remove(uri)
            else selected.add(uri)

            notifyItemChanged(position)
            onChanged()
        }
    }
}

