package com.example.camera2app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF

class RTMPoseRunner(private val context: Context) {

    private val detector = YoloXDetector(context)
    private val estimator = RTMPoseEstimator(context)

    /**
     * detect(bitmap) → PoseResult?
     * 1) YOLOX 로 사람 bbox 검출
     * 2) RTMPose 로 keypoint 추정 (현재 모델은 keypoint 1개)
     */
    fun detect(bitmap: Bitmap): PoseResult? {

        // 1) 사람 검출 → bbox
        val bbox = detector.detectPerson(bitmap)
            ?: RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())

        // 2) RTMPose 예측 → keypoints (List<Pair<PointF, Float>>)
        val keypoints = estimator.estimatePose(bitmap, bbox)
            ?: return null

        return PoseResult(
            keypoints = keypoints,
            boundingBox = bbox
        )
    }
}
