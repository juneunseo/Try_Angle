package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import kotlin.math.min

/**
 * iOS의 PoseMLAnalyzer.swift 역할:
 *  - 사람 박스 추정 (YOLOX)
 *  - RTMPose로 133 키포인트 추정
 *  - 얼굴 박스 및 전신 박스 추정
 *  - 밝기 계산
 */
class PoseEstimator(
    private val context: Context
) {

    // ✅ 이미 프로젝트에 있는 두 개 모델 재사용
    private val yoloDetector = YoloXDetector(context)
    private val rtmPoseEstimator = RTMPoseEstimator(context)

    /**
     * 한 프레임에서 얼굴 + 포즈를 같이 분석
     * RealtimeAnalyzer에서 사용
     */
    fun estimate(bitmap: Bitmap): FrameAnalysis? {

        // ✅ 1) 사람 박스 (YOLOX)
        val personBox: RectF = yoloDetector.detectPerson(bitmap) ?: run {
            Log.d("PoseEstimator", "No person detected")
            return null
        }

        // ✅ 2) RTMPose 키포인트 (원본 Pair 리스트)
        val rawKeypoints: List<Pair<PointF, Float>> =
            rtmPoseEstimator.estimatePose(bitmap, personBox) ?: run {
                Log.d("PoseEstimator", "RTMPose failed")
                return null
            }

        // ✅ ✅ ✅ 여기!! 네가 물어본 코드가 EXACTLY 들어가는 자리 ✅ ✅ ✅
        val poseKeypoints: List<PoseKeypoint> =
            rawKeypoints.map { (pt, conf) ->
                PoseKeypoint(point = pt, confidence = conf)

            }


        Log.d("PoseEstimator", "RTMPose: ${poseKeypoints.size} keypoints detected")

        // ✅ 3) 얼굴 박스 추정
        val faceRect = estimateFaceRectFromPerson(personBox)

        // ✅ 4) 밝기 계산
        val brightness = calculateBrightness(bitmap)

        // ✅ ✅ ✅ 최종 FrameAnalysis 생성 (최신 정의와 100% 일치)
        return FrameAnalysis(
            faceRect = faceRect,
            poseKeypoints = poseKeypoints,
            boundingBox = personBox,
            brightness = brightness,
            compositionType = null,
            depth = null,
            gaze = null
        )
    }

    /**
     * iOS의 estimateBodyRect(from faceRect:) 대응
     */
    fun estimateBodyRect(faceRect: RectF?): RectF? {
        faceRect ?: return null

        val bodyWidth = faceRect.width() * 3f
        val bodyHeight = faceRect.height() * 7f
        val bodyX = faceRect.centerX() - bodyWidth / 2f
        val bodyY = faceRect.top

        return RectF(
            bodyX,
            bodyY,
            bodyX + bodyWidth,
            bodyY + bodyHeight
        )
    }

    // ----------------------------------------------------
    // ✅ 내부 유틸들
    // ----------------------------------------------------

    /** 사람 박스 상단에서 얼굴 영역 대략 추정 */
    private fun estimateFaceRectFromPerson(personBox: RectF): RectF {
        val faceHeight = personBox.height() * 0.3f
        val top = personBox.top
        val bottom = top + faceHeight

        return RectF(
            personBox.left,
            top,
            personBox.right,
            bottom
        )
    }

    /** 밝기 계산 (0.0 ~ 1.0) */
    fun calculateBrightness(bitmap: Bitmap): Float {
        val targetSize = 100
        val w = min(bitmap.width, targetSize)
        val h = min(bitmap.height, targetSize)

        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        var total = 0f
        for (c in pixels) {
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            total += (r + g + b) / 3f
        }

        return if (pixels.isNotEmpty()) total / pixels.size else 0.5f
    }
}
