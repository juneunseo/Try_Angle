package com.example.camera2app.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ActivityFeedbackScoreBinding

class FeedbackScoreActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CAPTURED_URI = "extra_captured_uri"
        const val EXTRA_REFERENCE_URI = "extra_reference_uri"
        const val EXTRA_SCORE = "extra_score"
        const val EXTRA_FEEDBACK_MESSAGE = "extra_feedback_message"
    }

    private lateinit var binding: ActivityFeedbackScoreBinding

    private var capturedUri: String? = null
    private var referenceUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFeedbackScoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent에서 데이터 받기
        capturedUri = intent.getStringExtra(EXTRA_CAPTURED_URI)
        referenceUri = intent.getStringExtra(EXTRA_REFERENCE_URI)
        val score = intent.getFloatExtra(EXTRA_SCORE, 8.3f)
        val feedbackMessage = intent.getStringExtra(EXTRA_FEEDBACK_MESSAGE)
            ?: "카메라 셔터도를 약간 높이면서도,\n#가까운 여러 넓은면 더 괜찮지는 시,\n비슷한 이미지를 얻을 수 있습니다!"

        // 촬영한 사진 표시
        capturedUri?.let { uri ->
            Glide.with(this)
                .load(Uri.parse(uri))
                .into(binding.imageFull)
        }

        // 점수 표시
        binding.scoreText.text = String.format("%.1f", score)


        // 뒤로가기 버튼
        binding.btnBack.setOnClickListener {
            finish()
        }

        // (i) 정보 버튼 → FeedbackActivity로 이동
        // (i) 정보 버튼 → FeedbackActivity로 이동
        binding.btnInfo.setOnClickListener {
            val intent = Intent(this, FeedbackActivity::class.java).apply {
                putExtra(FeedbackActivity.EXTRA_IMAGE_URI, capturedUri)
                putExtra(FeedbackActivity.EXTRA_REFERENCE_URI, referenceUri)
                putExtra(FeedbackActivity.EXTRA_SCORE, score)
                putExtra(FeedbackActivity.EXTRA_FEEDBACK_MESSAGE, feedbackMessage)
            }
            startActivity(intent)
        }
    }
}