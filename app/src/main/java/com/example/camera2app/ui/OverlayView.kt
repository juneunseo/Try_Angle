package com.example.camera2app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ================================
    // 1) 레터박스 / 그리드 관련 변수
    // ================================
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x88000000.toInt()
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x44FFFFFF.toInt()
    }

    private val visibleRect = RectF()
    private var hasRect = false

    fun setVisibleRect(rect: RectF) {
        synchronized(visibleRect) {
            visibleRect.set(rect)
            hasRect = true
        }
        invalidate()
    }

    // ================================
    // 2) 포즈(KeyPoint) 관련 변수
    // ================================
    private var keypoints: List<Pair<PointF, Float>> = emptyList()
    private var bbox: RectF? = null

    fun updatePose(kps: List<Pair<PointF, Float>>, box: RectF) {
        keypoints = kps
        bbox = box
        invalidate()
    }

    // ================================
    // 3) onDraw() → 모든 요소를 한 번에 그림
    // ================================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!hasRect) return
        val r = synchronized(visibleRect) { RectF(visibleRect) }

        val w = width.toFloat()
        val h = height.toFloat()

        // -----------------------
        // (1) 레터박스 마스크
        // -----------------------
        canvas.drawRect(0f, 0f, w, r.top, maskPaint)
        canvas.drawRect(0f, r.bottom, w, h, maskPaint)
        canvas.drawRect(0f, r.top, r.left, r.bottom, maskPaint)
        canvas.drawRect(r.right, r.top, w, r.bottom, maskPaint)

        // -----------------------
        // (2) 3×3 그리드
        // -----------------------
        val thirdW = r.width() / 3f
        val thirdH = r.height() / 3f

        canvas.drawLine(r.left + thirdW, r.top, r.left + thirdW, r.bottom, gridPaint)
        canvas.drawLine(r.left + 2f * thirdW, r.top, r.left + 2f * thirdW, r.bottom, gridPaint)

        canvas.drawLine(r.left, r.top + thirdH, r.right, r.top + thirdH, gridPaint)
        canvas.drawLine(r.left, r.top + 2f * thirdH, r.right, r.top + 2f * thirdH, gridPaint)

        // -----------------------
        // (3) 포즈 Keypoints
        // -----------------------
        val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            strokeWidth = 6f
        }

        for ((pt, conf) in keypoints) {
            if (conf > 0.3f) {
                canvas.drawCircle(pt.x, pt.y, 6f, paint)
            }
        }

        // -----------------------
        // (4) Bounding Box (optional)
        // -----------------------
        bbox?.let {
            val boxPaint = Paint().apply {
                color = Color.YELLOW
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawRect(it, boxPaint)
        }
    }
}
