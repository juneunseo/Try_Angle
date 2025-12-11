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
        const val EXTRA_ANALYSIS_MODE = "ANALYSIS_MODE"
        const val EXTRA_PERSON_DETECTED = "EXTRA_PERSON_DETECTED"
    }

    private lateinit var binding: ActivityPreviewFeedbackTotalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPreviewFeedbackTotalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Intent 데이터 수신
        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        val uri = uriStr?.let { Uri.parse(it) }

        val score = intent.getFloatExtra(EXTRA_SCORE, 0f)
        val feedbackMessage = intent.getStringExtra(EXTRA_FEEDBACK_MESSAGE) ?: ""
        val mode = intent.getStringExtra(EXTRA_ANALYSIS_MODE) ?: "single"
        val isPersonDetected =
            intent.getBooleanExtra(EXTRA_PERSON_DETECTED, false)

        // ✅ 이미지 표시
        if (uri != null) {
            Glide.with(this)
                .load(uri)
                .into(binding.imageFull)
        }

        // ✅ 메인 피드백 표시
        displayFeedback(
            score = score,
            feedbackMessage = feedbackMessage,
            mode = mode,
            isPersonDetected = isPersonDetected
        )

        // ✅ 뒤로가기
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // ✅ 다시 평가
        binding.btnRetry.setOnClickListener {
            val intent = Intent(this, com.example.camera2app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("KEEP_REFERENCE_MODE", true)
            }
            startActivity(intent)
            finish()
        }
    }

    // ============================================================
    // ✅ 메인 표시 함수
    // ============================================================
    private fun displayFeedback(
        score: Float,
        feedbackMessage: String,
        mode: String,
        isPersonDetected: Boolean
    ) {
        binding.photoName.text = "촬영 사진"
        binding.scoreTotalDesc.text = String.format("%.1f / 10", score)

        if (mode == "reference") {

            binding.photoSubtitle.text = getOverallDescriptionReference(score)
            binding.scoreSummaryDesc.text = getScoreSummaryReference(score)

            val categoryFeedbacks =
                generateCategoryFeedbacksReference(score)

            applyCategoryFeedbacks(categoryFeedbacks)

        } else {

            // ✅ 사람 없으면 무조건 차단 메시지
            binding.photoSubtitle.text =
                getOverallDescriptionSingle(score, isPersonDetected)

            binding.scoreSummaryDesc.text =
                getScoreSummarySingle(score, isPersonDetected)

            val categoryFeedbacks =
                generateCategoryFeedbacksSingle(score, isPersonDetected)

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

    // ============================================================
    // ✅ 단일 사진 모드 (사람 감지 기반)
    // ============================================================
    private fun getOverallDescriptionSingle(
        score: Float,
        isPersonDetected: Boolean
    ): String {
        if (!isPersonDetected) return "사람이 감지되지 않았어요"

        return when {
            score >= 9.0f -> "완벽한 사진이에요! 🎉"
            score >= 7.0f -> "좋은 사진이에요!"
            score >= 5.0f -> "조금만 더 조정하면 좋아질 거예요"
            score >= 3.0f -> "사진 구도를 조금 조정해보세요"
            else -> "구도가 불안정해요"
        }
    }

    private fun getScoreSummarySingle(
        score: Float,
        isPersonDetected: Boolean
    ): String {
        if (!isPersonDetected) return "사람 인식이 되지 않았어요"

        return when {
            score >= 9.0f -> "전체적으로 훌륭해요!"
            score >= 7.0f -> "좋은 밸런스예요"
            score >= 5.0f -> "조금 더 조정해보세요"
            score >= 3.0f -> "여러 부분을 개선해보세요"
            else -> "구도가 불안정해요"
        }
    }

    private fun generateCategoryFeedbacksSingle(
        score: Float,
        isPersonDetected: Boolean
    ): Map<String, String> {

        if (!isPersonDetected) {
            return mapOf(
                "pose" to "사람이 잘 보이지 않아요",
                "composition" to "구도 분석이 어려워요",
                "viewpoint" to "시점 분석이 어려워요",
                "color" to "조명이 부족해요",
                "mood" to "분위기 분석이 어려워요"
            )
        }

        return mapOf(
            "pose" to when {
                score >= 8f -> "포즈가 자연스러워요 ✓"
                score >= 5f -> "포즈가 조금 불안정해요"
                else -> "포즈 안정성이 부족해요"
            },

            "composition" to when {
                score >= 8f -> "구도가 안정적이에요 ✓"
                score >= 5f -> "구도를 약간 조정해보세요"
                else -> "프레이밍이 불안정해요"
            },

            "viewpoint" to when {
                score >= 8f -> "카메라 각도가 좋아요 ✓"
                score >= 5f -> "각도를 조금 조정해보세요"
                else -> "촬영 앵글이 어색해요"
            },

            "color" to when {
                score >= 8f -> "노출이 좋아요 ✓"
                score >= 5f -> "조명을 조금 더 밝게 해보세요"
                else -> "노출이 부족해요"
            },

            "mood" to when {
                score >= 8f -> "사진 분위기가 좋아요 ✓"
                score >= 5f -> "분위기를 조금 조정해보세요"
                else -> "분위기가 어색해요"
            }
        )
    }

    // ============================================================
    // ✅ 레퍼런스 비교 모드
    // ============================================================
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

        feedbacks["pose"] = when {
            score >= 8.0f -> "포즈가 레퍼런스와 잘 맞아요 ✓"
            score >= 6.0f -> "팔 위치를 조금 조정해보세요"
            score >= 4.0f -> "포즈가 많이 달라요"
            else -> "레퍼런스를 참고하세요"
        }

        feedbacks["composition"] = when {
            score >= 8.0f -> "구도가 안정적이에요 ✓"
            score >= 6.0f -> "중앙에 더 가까이 서보세요"
            score >= 4.0f -> "프레이밍을 조정해주세요"
            else -> "카메라 거리 조절이 필요해요"
        }

        feedbacks["viewpoint"] = when {
            score >= 8.0f -> "카메라 앵글이 적절해요 ✓"
            score >= 6.0f -> "카메라 높이를 조정해보세요"
            score >= 4.0f -> "각도를 다시 맞춰보세요"
            else -> "레퍼런스 앵글을 참고하세요"
        }

        feedbacks["color"] = when {
            score >= 8.0f -> "조명이 좋아요 ✓"
            score >= 6.0f -> "조명을 더 밝게 해보세요"
            score >= 4.0f -> "더 밝은 곳에서 촬영하세요"
            else -> "조명 환경이 부족해요"
        }

        feedbacks["mood"] = when {
            score >= 8.0f -> "분위기가 잘 살았어요 ✓"
            score >= 6.0f -> "표정을 더 자연스럽게 해보세요"
            score >= 4.0f -> "분위기를 다시 잡아보세요"
            else -> "전체적 느낌을 조정해주세요"
        }

        return feedbacks
    }
}
