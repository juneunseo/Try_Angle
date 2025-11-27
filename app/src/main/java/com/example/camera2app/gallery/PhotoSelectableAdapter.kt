// PhotoSelectableAdapter.kt
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
    private val selectedPhotos: MutableList<Uri>,
    private val onSelectionChanged: () -> Unit
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
        val isSelected = selectedPhotos.contains(uri)

        // 썸네일 로드
        Glide.with(holder.b.thumb)
            .load(uri)
            .centerCrop()
            .into(holder.b.thumb)

        // ★ 아이콘 항상 표시 (선택 모드에서는 빈 원 or 체크)
        holder.b.checkIcon.visibility = View.VISIBLE

        if (isSelected) {
            // 선택됨 → 체크 아이콘 + 오버레이
            holder.b.checkIcon.setImageResource(R.drawable.ic_select_checked)
            holder.b.overlay.visibility = View.VISIBLE
        } else {
            // 선택 안됨 → 빈 원 아이콘
            holder.b.checkIcon.setImageResource(R.drawable.ic_select_empty)
            holder.b.overlay.visibility = View.GONE
        }

        // 클릭 → 선택/해제 토글
        holder.b.root.setOnClickListener {
            if (selectedPhotos.contains(uri)) {
                selectedPhotos.remove(uri)
            } else {
                selectedPhotos.add(uri)
            }
            notifyItemChanged(position)
            onSelectionChanged()
        }
    }
}