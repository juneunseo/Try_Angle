package com.example.camera2app.reference

/**
 * 레퍼런스 이미지 타입
 * - ResourceImage: drawable 리소스 (Int)
 * - UriImage: 갤러리에서 추가한 실제 사진 (String URI)
 */
sealed class ReferenceImage {
    /**
     * Drawable 리소스 이미지 (예: R.drawable.hot1)
     */
    data class ResourceImage(val resId: Int) : ReferenceImage()

    /**
     * URI 이미지 (예: content://media/external/images/media/123)
     */
    data class UriImage(val uri: String) : ReferenceImage()
}