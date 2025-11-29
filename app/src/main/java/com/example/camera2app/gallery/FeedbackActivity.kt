package com.example.camera2app.gallery

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ActivityPreviewFeedbackTotalBinding

class FeedbackActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_REFERENCE_URI = "extra_reference_uri"
        const val EXTRA_SCORE = "extra_score"
        const val EXTRA_FEEDBACK_MESSAGE = "extra_feedback_message"
    }

    private lateinit var binding: ActivityPreviewFeedbackTotalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPreviewFeedbackTotalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent에서 데이터 받기
        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        val uri = uriStr?.let { Uri.parse(it) }
        val score = intent.getFloatExtra(EXTRA_SCORE, 5.0f)
        val feedbackMessage = intent.getStringExtra(EXTRA_FEEDBACK_MESSAGE) ?: ""

        // 이미지 표시
        if (uri != null) {
            Glide.with(this)
                .load(uri)
                .into(binding.imageFull)
        }

        // 점수에 따른 피드백 생성 및 표시
        displayFeedback(score, feedbackMessage)

        // 뒤로가기
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // 다시 평가받기 버튼
        binding.btnRetry.setOnClickListener {
            // MainActivity로 돌아가기 (레퍼런스 모드 유지)
            val intent = Intent(this, com.example.camera2app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("KEEP_REFERENCE_MODE", true)  // 레퍼런스 모드 유지 플래그
            }
            startActivity(intent)
            finish()
        }
    }

    private fun displayFeedback(score: Float, feedbackMessage: String) {
        // 사진 이름/설명
        binding.photoName.text = "촬영 사진"
        binding.photoSubtitle.text = getOverallDescription(score)

        // 총점 표시
        binding.scoreTotalDesc.text = String.format("%.1f / 10", score)

        // 점수 요약
        binding.scoreSummaryDesc.text = getScoreSummary(score)

        // 카테고리별 피드백 생성
        val categoryFeedbacks = generateCategoryFeedbacks(score)

        // 포즈
        binding.categoryPoseDesc.text = categoryFeedbacks["pose"] ?: "분석 중..."

        // 구도
        binding.categoryCompositionDesc.text = categoryFeedbacks["composition"] ?: "분석 중..."

        // 시점
        binding.categoryViewpointDesc.text = categoryFeedbacks["viewpoint"] ?: "분석 중..."

        // 색감
        binding.categoryColorDesc.text = categoryFeedbacks["color"] ?: "분석 중..."

        // 감성
        binding.categoryMoodDesc.text = categoryFeedbacks["mood"] ?: "분석 중..."
    }

    private fun getOverallDescription(score: Float): String {
        return when {
            score >= 9.0f -> "완벽한 사진이에요! 🎉"
            score >= 7.0f -> "좋은 사진이에요! 👍"
            score >= 5.0f -> "조금만 더 조정하면 좋아질 거예요"
            score >= 3.0f -> "레퍼런스를 다시 확인해보세요"
            else -> "포즈와 구도를 조정해주세요"
        }
    }

    private fun getScoreSummary(score: Float): String {
        return when {
            score >= 9.0f -> "레퍼런스와 거의 동일해요!"
            score >= 7.0f -> "레퍼런스에 가까워요"
            score >= 5.0f -> "조금 더 조정이 필요해요"
            score >= 3.0f -> "많은 조정이 필요해요"
            else -> "레퍼런스를 참고해주세요"
        }
    }

    private fun generateCategoryFeedbacks(score: Float): Map<String, String> {
        // 점수 기반으로 카테고리별 피드백 생성
        // 실제로는 AI 분석 결과에서 카테고리별 점수를 받아와야 함

        val feedbacks = mutableMapOf<String, String>()

        // 포즈 피드백
        feedbacks["pose"] = when {
            score >= 8.0f -> "포즈가 레퍼런스와 잘 맞아요 ✓"
            score >= 6.0f -> "팔 위치를 조금 조정해보세요"
            score >= 4.0f -> "포즈가 많이 달라요. 레퍼런스를 참고하세요"
            else -> "포즈를 레퍼런스와 비슷하게 잡아주세요"
        }

        // 구도 피드백
        feedbacks["composition"] = when {
            score >= 8.0f -> "구도가 안정적이에요 ✓"
            score >= 6.0f -> "화면 중앙에 더 가까이 서보세요"
            score >= 4.0f -> "프레이밍을 조정해주세요"
            else -> "카메라와의 거리를 조절해보세요"
        }

        // 시점 피드백
        feedbacks["viewpoint"] = when {
            score >= 8.0f -> "카메라 앵글이 적절해요 ✓"
            score >= 6.0f -> "카메라를 조금 높이거나 낮춰보세요"
            score >= 4.0f -> "카메라 각도를 조정해주세요"
            else -> "레퍼런스의 촬영 각도를 참고하세요"
        }

        // 색감 피드백
        feedbacks["color"] = when {
            score >= 8.0f -> "조명이 좋아요 ✓"
            score >= 6.0f -> "조명을 조금 더 밝게 해보세요"
            score >= 4.0f -> "더 밝은 곳에서 촬영해보세요"
            else -> "조명 환경을 개선해주세요"
        }

        // 감성 피드백
        feedbacks["mood"] = when {
            score >= 8.0f -> "분위기가 잘 살았어요 ✓"
            score >= 6.0f -> "표정을 더 자연스럽게 해보세요"
            score >= 4.0f -> "레퍼런스의 분위기를 참고하세요"
            else -> "전체적인 느낌을 조정해주세요"
        }

        return feedbacks
    }
}