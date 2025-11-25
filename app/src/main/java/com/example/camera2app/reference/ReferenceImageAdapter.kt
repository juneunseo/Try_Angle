package com.example.camera2app.reference

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.camera2app.R

class ReferenceImageAdapter(
    private val images: List<Int>
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
        holder.img.setImageResource(images[position])
    }

    override fun getItemCount() = images.size
}
