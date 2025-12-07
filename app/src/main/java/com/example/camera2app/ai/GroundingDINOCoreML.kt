package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

class GroundingDINOCoreML(private val context: Context) {

    private val onnxModel = GroundingDinoONNX(context)

    init {
        Log.d("DINO", "✅ GroundingDINO ONNX Wrapper initialized")
    }

    /**
     * 사람 1명 bounding box 반환 (최고 score)
     */
    fun detectPerson(bitmap: Bitmap): RectF? {
        return try {
            onnxModel.detectOne(bitmap)
        } catch (e: Exception) {
            Log.e("DINO", "❌ DINO detectOne 실패", e)
            null
        }
    }

    /**
     * 여러 사람 bounding box 반환 (NMS 포함)
     */
    fun detectAllPersons(
        bitmap: Bitmap,
        threshold: Float = 0.5f
    ): List<Detection> {
        return try {
            onnxModel.detectAll(bitmap, threshold)
        } catch (e: Exception) {
            Log.e("DINO", "❌ DINO detectAll 실패", e)
            emptyList()
        }
    }
}
