package com.example.camera2app.reference

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.camera2app.R


class ReferenceImageAdapter(
    private var images: List<Int>,
    private val onLikeChanged: (() -> Unit)? = null
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

        // 하트 상태 표시
        val isLiked = LikeManager.isLiked(imageRes)
        updateHeartIcon(holder.heart, isLiked)

        // ✅ 이미지 클릭 → ImageDetailActivity로 이동 (이 부분 추가!)
        holder.img.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, ImageDetailActivity::class.java).apply {
                putExtra(ImageDetailActivity.EXTRA_IMAGE_RES_ID, imageRes)
            }

            // ✅ startActivityForResult 사용
            if (context is ReferenceActivity) {
                context.startActivityForResult(intent, ReferenceActivity.REQUEST_IMAGE_DETAIL)
            } else {
                context.startActivity(intent)
            }
        }


        // 하트 클릭 리스너
        holder.heart.setOnClickListener {
            val nowLiked = LikeManager.toggleLike(imageRes)
            updateHeartIcon(holder.heart, nowLiked)

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

    fun updateImages(newImages: List<Int>) {
        images = newImages
        notifyDataSetChanged()
    }
}