package com.example.camera2app.reference

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.camera2app.R

class ReferenceImageAdapter(
    private var images: List<Int>,
    private val onLikeChanged: (() -> Unit)? = null  // 좋아요 변경 콜백
) : RecyclerView.Adapter<ReferenceImageAdapter.Holder>() {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.imgPhoto)
        val heart: ImageView = view.findViewById(R.id.btnHeart)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reference, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val imageRes = images[position]
        holder.img.setImageResource(imageRes)

        // 하트 상태 표시 (LikeManager에서 가져옴)
        val isLiked = LikeManager.isLiked(imageRes)
        updateHeartIcon(holder.heart, isLiked)

        // 하트 클릭 리스너
        holder.heart.setOnClickListener {
            val nowLiked = LikeManager.toggleLike(imageRes)
            updateHeartIcon(holder.heart, nowLiked)

            // 콜백 호출 (My 탭 갱신용)
            onLikeChanged?.invoke()

            // 팝 애니메이션
            holder.heart.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(100)
                .withEndAction {
                    holder.heart.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
    }

    override fun getItemCount() = images.size

    private fun updateHeartIcon(heartView: ImageView, isLiked: Boolean) {
        heartView.setImageResource(
            if (isLiked) R.drawable.ic_heart_filled
            else R.drawable.ic_heart_empty
        )
    }

    // 이미지 목록 갱신 (My 탭용)
    fun updateImages(newImages: List<Int>) {
        images = newImages
        notifyDataSetChanged()
    }
}