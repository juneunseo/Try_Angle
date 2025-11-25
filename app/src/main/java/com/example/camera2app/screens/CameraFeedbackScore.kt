package com.example.camera2app.screens

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.camera2app.R

class CameraFeedbackScore : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_feedback_score)

        // XML의 ImageView id : feedbackBackgroundImage
        Glide.with(this)
            .load("https://storage.googleapis.com/tagjs-prod.appspot.com/v1/5MsrjCFRSQ/s2kvwgf3_expires_30_days.png")
            .into(findViewById(R.id.feedbackBackgroundImage))
    }
}
