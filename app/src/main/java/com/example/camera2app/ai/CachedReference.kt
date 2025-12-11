package com.example.camera2app.ai

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.SizeF


data class CachedReference(
    val id: String,
    val image: Bitmap,
    val bbox: RectF,              // 정규화 좌표
    val imageSize: SizeF,
    val margins: MarginInfo,
    val compressionIndex: Float?,
    val timestamp: Long,

    // 추가 분석 데이터
    val keypoints: List<List<Float>>? = null,
    val framingType: FramingType? = null,
    val cameraType: CameraType? = null
) {

    enum class FramingType(val label: String) {
        FULL_BODY("전신"),
        THREE_QUARTER("무릎샷"),
        WAIST("웨이스트샷"),
        BUST("바스트샷"),
        CLOSE_UP("클로즈업")
    }

    enum class CameraType(val label: String) {
        WIDE("광각"),
        NORMAL("표준"),
        TELEPHOTO("망원")
    }
}
