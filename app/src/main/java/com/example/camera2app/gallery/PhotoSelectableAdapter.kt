package com.example.camera2app.gallery

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.camera2app.R
import com.example.camera2app.ai.GateSystem
import com.example.camera2app.ai.TryAngleFeedback
import com.example.camera2app.databinding.ItemPhotoSelectableBinding
import com.example.camera2app.MainActivity


class PhotoSelectableAdapter(
    private val photos: List<Uri>,
    private val feedbackMap: Map<Uri, TryAngleFeedback?>,   // ✅ AI 결과
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

        val feedback = feedbackMap[uri]
        val aiPassed = feedback?.isPersonDetected == true

        Glide.with(holder.b.thumb)
            .load(uri)
            .centerCrop()
            .into(holder.b.thumb)

        // ✅ 1️⃣ 기본 상태: 무조건 체크 OFF
        holder.b.checkIcon.visibility = View.VISIBLE
        holder.b.checkIcon.setImageResource(R.drawable.ic_select_empty)
        holder.b.overlay.visibility = View.GONE

        // ✅ 2️⃣ AI가 아직 시작도 안 했으면 → 여기서 바로 종료
        if (!MainActivity.aiAnalysisStarted) return

        // ✅ 3️⃣ AI가 시작된 이후 → 통과한 것만 체크 ON
        if (aiPassed) {
            holder.b.checkIcon.setImageResource(R.drawable.ic_select_checked)
            holder.b.overlay.visibility = View.VISIBLE
        }

        // ✅ ❗ 클릭은 아무것도 하지 않음 (AI 전용)
        holder.b.root.setOnClickListener(null)
    }



}
