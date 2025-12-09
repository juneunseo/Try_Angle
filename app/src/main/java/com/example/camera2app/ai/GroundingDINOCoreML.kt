package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

class GroundingDINOCoreML(private val context: Context) {

    private val onnxModel = GroundingDinoONNX(context)

    init {
        Log.d("DINO", "✅ GroundingDINO ONNX Async Wrapper initialized")
    }

    /**
     * ✅ 사람 1명 비동기 감지 (최고 score)
     */
    fun detectPersonAsync(
        bitmap: Bitmap,
        onResult: (RectF?) -> Unit
    ) {
        onnxModel.detectOneAsync(bitmap) { box ->
            onResult(box)
        }
    }
}
