package com.example.camera2app.screens

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.camera2app.R

class CameraMain : AppCompatActivity() {



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_main)

        Glide.with(this)
            .load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/5MsrjCFRSQ/zn55befd_expires_30_days.png")
            .into(findViewById(R.id.imagePreview))
    }
}
