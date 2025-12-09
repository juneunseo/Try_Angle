package com.example.camera2app.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ActivityFeedbackScoreBinding
import java.util.Locale

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

        // ✅ Intent 데이터 수신
        capturedUri = intent.getStringExtra(EXTRA_CAPTURED_URI)
        referenceUri = intent.getStringExtra(EXTRA_REFERENCE_URI)
        val score = intent.getFloatExtra(EXTRA_SCORE, 8.3f)
        val feedbackMessage = intent.getStringExtra(EXTRA_FEEDBACK_MESSAGE)
            ?: "촬영 각도와 구도를 조금 조정해보세요!"

        // ✅ ✅ ✅ Glide 크래시 완전 차단 (내장 리소스만 사용)
        capturedUri?.let { uriString ->
            try {
                val uri = Uri.parse(uriString)

                Glide.with(this)
                    .load(uri)
                    .error(android.R.drawable.ic_delete)   // ✅ 내장 리소스 사용
                    .into(binding.imageFull)

            } catch (e: Exception) {
                e.printStackTrace()
                binding.imageFull.setImageResource(android.R.drawable.ic_delete)
            }
        } ?: run {
            // ✅ URI 자체가 없을 때도 안전 처리
            binding.imageFull.setImageResource(android.R.drawable.ic_delete)
        }

        // ✅ 점수 표시 (Locale 명시)
        binding.scoreText.text = String.format(Locale.US, "%.1f", score)

        // ✅ 뒤로가기
        binding.btnBack.setOnClickListener {
            finish()
        }

        // ✅ (i) 정보 버튼 → 상세 피드백 화면 이동
        binding.btnInfo.setOnClickListener {
            val intent = Intent(this, FeedbackActivity::class.java).apply {
                putExtra(FeedbackActivity.EXTRA_IMAGE_URI, capturedUri)
                putExtra(FeedbackActivity.EXTRA_REFERENCE_URI, referenceUri)
                putExtra(FeedbackActivity.EXTRA_SCORE, score)
                putExtra(FeedbackActivity.EXTRA_FEEDBACK_MESSAGE, feedbackMessage)
            }
            startActivity(intent)
        }

        // ✅ 프로그레스 바
        binding.progressComposition.progress = (score * 10).toInt().coerceIn(0, 100)
        binding.progressLighting.progress = (score * 8).toInt().coerceIn(0, 100)
        binding.progressFocus.progress = (score * 6).toInt().coerceIn(0, 100)
    }
}
