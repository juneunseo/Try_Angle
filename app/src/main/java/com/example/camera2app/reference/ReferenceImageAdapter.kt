package com.example.camera2app.reference

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.camera2app.R

class ReferenceImageAdapter(
    private var images: List<ReferenceImage>,  // ⭐ List<Int> → List<ReferenceImage>
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
        val image = images[position]

        // ⭐ 타입에 따라 다르게 처리
        when (image) {
            is ReferenceImage.ResourceImage -> {
                // Drawable 리소스
                holder.img.setImageResource(image.resId)
                setupResourceImage(holder, image.resId)
            }
            is ReferenceImage.UriImage -> {
                // URI (갤러리 사진)
                Glide.with(holder.itemView.context)
                    .load(Uri.parse(image.uri))
                    .centerCrop()
                    .into(holder.img)
                setupUriImage(holder, image.uri)
            }
        }
    }

    override fun getItemCount() = images.size

    // ========== Drawable 리소스 이미지 처리 ==========
    private fun setupResourceImage(holder: Holder, imageRes: Int) {
        // 하트 상태 표시
        val isLiked = LikeManager.isLiked(imageRes)
        updateHeartIcon(holder.heart, isLiked)

        // 이미지 클릭 → ImageDetailActivity로 이동
        holder.img.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, ImageDetailActivity::class.java).apply {
                putExtra(ImageDetailActivity.EXTRA_IMAGE_RES_ID, imageRes)
            }

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
            animateHeart(holder.heart)
        }
    }

    // ========== URI 이미지 처리 (갤러리에서 추가한 사진) ==========
    private fun setupUriImage(holder: Holder, uri: String) {
        val isLiked = LikeManager.isLiked(uri)
        updateHeartIcon(holder.heart, isLiked)

        // ⭐ 기존 PreviewActivity로 보내던 코드 제거!
        // Uri 이미지도 ImageDetailActivity와 동일한 경로로 이동하도록 변경

        holder.img.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, ImageDetailActivity::class.java).apply {
                putExtra("image_uri", uri)   // ⭐ 반드시 새로 넣어줘야 함
            }

            if (context is ReferenceActivity) {
                context.startActivityForResult(intent, ReferenceActivity.REQUEST_IMAGE_DETAIL)
            } else {
                context.startActivity(intent)
            }
        }

        holder.heart.setOnClickListener {
            val nowLiked = LikeManager.toggleLike(uri)
            updateHeartIcon(holder.heart, nowLiked)
            onLikeChanged?.invoke()
            animateHeart(holder.heart)
        }
    }


    // ========== 공통 메서드 ==========
    private fun updateHeartIcon(heartView: ImageView, isLiked: Boolean) {
        heartView.setImageResource(
            if (isLiked) R.drawable.ic_heart_filled
            else R.drawable.ic_heart_empty
        )
    }

    private fun animateHeart(heartView: ImageView) {
        heartView.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(100)
            .withEndAction {
                heartView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    fun updateImages(newImages: List<ReferenceImage>) {
        images = newImages
        notifyDataSetChanged()
    }
}