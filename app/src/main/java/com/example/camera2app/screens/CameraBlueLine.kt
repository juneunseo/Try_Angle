package com.example.camera2app.screens

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.camera2app.R

class CameraBlueLine : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_blue_line)

        // XML의 ImageView id = blueLine_background
        Glide.with(this)
            .load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/5MsrjCFRSQ/86q220se_expires_30_days.png")
            .into(findViewById(R.id.blueLine_background))
    }
}
