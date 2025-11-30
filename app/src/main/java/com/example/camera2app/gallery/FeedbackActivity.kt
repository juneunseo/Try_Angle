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

        // ⭐ 추가: 분석 모드
        // "single" = 갤러리 단일 사진 분석
        // "reference" = 레퍼런스 비교 모드
        const val EXTRA_ANALYSIS_MODE = "ANALYSIS_MODE"
    }

    private lateinit var binding: ActivityPreviewFeedbackTotalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPreviewFeedbackTotalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent 데이터 읽기
        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        val uri = uriStr?.let { Uri.parse(it) }
        val score = intent.getFloatExtra(EXTRA_SCORE, 5.0f)
        val feedbackMessage = intent.getStringExtra(EXTRA_FEEDBACK_MESSAGE) ?: ""

        // ⭐ 분석 모드 읽기
        val mode = intent.getStringExtra(EXTRA_ANALYSIS_MODE) ?: "single"

        // 이미지 표시
        if (uri != null) {
            Glide.with(this)
                .load(uri)
                .into(binding.imageFull)
        }

        // 점수 + 메시지 표시
        displayFeedback(score, feedbackMessage, mode)

        // 뒤로가기
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // 다시 평가받기 버튼
        binding.btnRetry.setOnClickListener {
            val intent = Intent(this, com.example.camera2app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("KEEP_REFERENCE_MODE", true)
            }
            startActivity(intent)
            finish()
        }
    }

    // ========================================================================
    // ⭐ 모드 분기 후 피드백 표시
    // ========================================================================
    private fun displayFeedback(score: Float, feedbackMessage: String, mode: String) {

        if (mode == "reference") {
            // -----------------------------
            // 📌 레퍼런스 비교 모드
            // -----------------------------
            binding.photoName.text = "촬영 사진"
            binding.photoSubtitle.text = getOverallDescriptionReference(score)

            binding.scoreTotalDesc.text = String.format("%.1f / 10", score)
            binding.scoreSummaryDesc.text = getScoreSummaryReference(score)

            val categoryFeedbacks = generateCategoryFeedbacksReference(score)
            applyCategoryFeedbacks(categoryFeedbacks)

        } else {
            // -----------------------------
            // 📌 갤러리 단일 분석 모드
            // -----------------------------
            binding.photoName.text = "촬영 사진"
            binding.photoSubtitle.text = getOverallDescriptionSingle(score)

            binding.scoreTotalDesc.text = String.format("%.1f / 10", score)
            binding.scoreSummaryDesc.text = getScoreSummarySingle(score)

            val categoryFeedbacks = generateCategoryFeedbacksSingle(score)
            applyCategoryFeedbacks(categoryFeedbacks)
        }
    }

    private fun applyCategoryFeedbacks(map: Map<String, String>) {
        binding.categoryPoseDesc.text = map["pose"]
        binding.categoryCompositionDesc.text = map["composition"]
        binding.categoryViewpointDesc.text = map["viewpoint"]
        binding.categoryColorDesc.text = map["color"]
        binding.categoryMoodDesc.text = map["mood"]
    }

    // ========================================================================
    // ⭐ 갤러리 단일 사진 모드 메시지
    // ========================================================================

    private fun getOverallDescriptionSingle(score: Float): String {
        return when {
            score >= 9.0f -> "완벽한 사진이에요! 🎉"
            score >= 7.0f -> "좋은 사진이에요!"
            score >= 5.0f -> "조금만 더 조정하면 좋아질 거예요"
            score >= 3.0f -> "사진 구도를 조금 조정해보세요"
            else -> "사람이 잘 보이지 않아요"
        }
    }

    private fun getScoreSummarySingle(score: Float): String {
        return when {
            score >= 9.0f -> "전체적으로 훌륭해요!"
            score >= 7.0f -> "좋은 밸런스예요"
            score >= 5.0f -> "조금 더 조정해보세요"
            score >= 3.0f -> "여러 부분을 개선해보세요"
            else -> "분석이 어려운 사진이에요"
        }
    }

    private fun generateCategoryFeedbacksSingle(score: Float): Map<String, String> {
        return mapOf(
            "pose" to when {
                score >= 8f -> "포즈가 자연스러워요 ✓"
                score >= 5f -> "포즈가 다소 불안정해 보여요"
                else -> "사람이 잘 보이지 않아요"
            },
            "composition" to when {
                score >= 8f -> "구도가 안정적이에요 ✓"
                score >= 5f -> "프레임 구도를 조금 수정해보세요"
                else -> "구도 분석이 어려워요"
            },
            "viewpoint" to when {
                score >= 8f -> "카메라 각도가 좋아요 ✓"
                score >= 5f -> "각도를 조금 조정해보세요"
                else -> "시점 분석이 어려워요"
            },
            "color" to when {
                score >= 8f -> "노출이 좋아요 ✓"
                score >= 5f -> "조명을 조금 더 밝게 해보세요"
                else -> "조명이 어두워요"
            },
            "mood" to when {
                score >= 8f -> "사진 분위기가 좋아요 ✓"
                score >= 5f -> "분위기를 조금 조정해보세요"
                else -> "분위기 분석이 어려워요"
            }
        )
    }

    // ========================================================================
    // ⭐ 레퍼런스 비교 모드 메시지 (기존 유지)
    // ========================================================================

    private fun getOverallDescriptionReference(score: Float): String {
        return when {
            score >= 9.0f -> "완벽한 사진이에요! 🎉"
            score >= 7.0f -> "좋은 사진이에요! 👍"
            score >= 5.0f -> "조금만 더 조정하면 좋아질 거예요"
            score >= 3.0f -> "레퍼런스를 다시 확인해보세요"
            else -> "포즈와 구도를 조정해주세요"
        }
    }

    private fun getScoreSummaryReference(score: Float): String {
        return when {
            score >= 9.0f -> "레퍼런스와 거의 동일해요!"
            score >= 7.0f -> "레퍼런스에 가까워요"
            score >= 5.0f -> "조금 더 조정이 필요해요"
            score >= 3.0f -> "많은 조정이 필요해요"
            else -> "레퍼런스를 참고해주세요"
        }
    }

    private fun generateCategoryFeedbacksReference(score: Float): Map<String, String> {
        val feedbacks = mutableMapOf<String, String>()

        // 포즈
        feedbacks["pose"] = when {
            score >= 8.0f -> "포즈가 레퍼런스와 잘 맞아요 ✓"
            score >= 6.0f -> "팔 위치를 조금 조정해보세요"
            score >= 4.0f -> "포즈가 많이 달라요. 레퍼런스를 참고하세요"
            else -> "포즈를 레퍼런스와 비슷하게 잡아주세요"
        }

        // 구도
        feedbacks["composition"] = when {
            score >= 8.0f -> "구도가 안정적이에요 ✓"
            score >= 6.0f -> "화면 중앙에 더 가까이 서보세요"
            score >= 4.0f -> "프레이밍을 조정해주세요"
            else -> "카메라와의 거리를 조절해보세요"
        }

        // 시점
        feedbacks["viewpoint"] = when {
            score >= 8.0f -> "카메라 앵글이 적절해요 ✓"
            score >= 6.0f -> "카메라를 조금 높이거나 낮춰보세요"
            score >= 4.0f -> "카메라 각도를 조정해주세요"
            else -> "레퍼런스의 촬영 각도를 참고하세요"
        }

        // 색감
        feedbacks["color"] = when {
            score >= 8.0f -> "조명이 좋아요 ✓"
            score >= 6.0f -> "조명을 조금 더 밝게 해보세요"
            score >= 4.0f -> "더 밝은 곳에서 촬영해보세요"
            else -> "조명 환경을 개선해주세요"
        }

        // 감성
        feedbacks["mood"] = when {
            score >= 8.0f -> "분위기가 잘 살았어요 ✓"
            score >= 6.0f -> "표정을 더 자연스럽게 해보세요"
            score >= 4.0f -> "레퍼런스의 분위기를 참고하세요"
            else -> "전체적인 느낌을 조정해주세요"
        }

        return feedbacks
    }
}
