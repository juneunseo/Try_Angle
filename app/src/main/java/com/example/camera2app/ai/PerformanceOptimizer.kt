package com.example.camera2app.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.math.abs
import kotlin.math.max

// ✅ 성능 최적화 매니저 (iOS PerformanceOptimizer.swift 완전 대응)
class PerformanceOptimizer private constructor() {

    companion object {
        val shared: PerformanceOptimizer by lazy { PerformanceOptimizer() }
    }

    init {
        android.util.Log.d("PerformanceOptimizer", "🚀 PerformanceOptimizer 초기화 완료")
    }

    // ---------------------------------------------
    // ✅ 비동기 처리 큐 (iOS DispatchQueue 대응)
    // ---------------------------------------------

    /** Level 1: RTMPose 전용 큐 (매 프레임) */
    val level1Executor: ExecutorService =
        Executors.newFixedThreadPool(2)

    /** Level 2: Depth 전용 큐 (5프레임마다) */
    val level2Executor: ExecutorService =
        Executors.newSingleThreadExecutor()

    /** Level 3: Grounding DINO 전용 큐 (30프레임마다) */
    val level3Executor: ExecutorService =
        Executors.newSingleThreadExecutor()

    /** 전처리 전용 큐 */
    val preprocessExecutor: ExecutorService =
        Executors.newFixedThreadPool(2)

    // ---------------------------------------------
    // ✅ 프레임 스킵 관리
    // ---------------------------------------------

    private var lastFrameHash: Long = 0L
    private var frameSkipCounter = 0
    private val maxConsecutiveSkips = 5

    // ---------------------------------------------
    // ✅ 성능 통계
    // ---------------------------------------------

    var averageLevel1Time = 0.0
        private set
    var averageLevel2Time = 0.0
        private set
    var averageLevel3Time = 0.0
        private set

    private val timeHistory = mutableMapOf<String, MutableList<Double>>()
    private val historySize = 30

    // ---------------------------------------------
    // ✅ 고속 리사이즈 (iOS fastResize 대응)
    // ---------------------------------------------

    fun fastResize(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val matrix = Matrix().apply {
            postScale(
                targetW.toFloat() / bitmap.width,
                targetH.toFloat() / bitmap.height
            )
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ---------------------------------------------
    // ✅ 고속 정규화 (CHW FloatArray, ImageNet 기준)
    // ---------------------------------------------

    fun fastNormalize(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height

        val buffer = ByteBuffer.allocate(pixelCount * 4)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        val result = FloatArray(pixelCount * 3)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (c in 0..2) {
            val offset = c * pixelCount
            for (i in 0 until pixelCount) {
                val color = pixels[i]
                val value = when (c) {
                    0 -> (color shr 16) and 0xFF
                    1 -> (color shr 8) and 0xFF
                    else -> color and 0xFF
                }

                val normalized = ((value / 255f) - mean[c]) / std[c]
                result[offset + i] = normalized
            }
        }

        return result
    }

    // ---------------------------------------------
    // ✅ 프레임 변화 감지 → 스킵 여부 판단
    // ---------------------------------------------

    fun shouldSkipFrame(bitmap: Bitmap): Boolean {
        val thumb = fastResize(bitmap, 32, 32)

        var hash = 0L
        val pixels = IntArray(32 * 32)
        thumb.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        val step = max(1, pixels.size / 64)
        for (i in pixels.indices step step) {
            hash = hash * 31 + pixels[i]
        }

        val isSimilar = hash == lastFrameHash
        lastFrameHash = hash

        if (isSimilar && frameSkipCounter < maxConsecutiveSkips) {
            frameSkipCounter++
            return true
        }

        frameSkipCounter = 0
        return false
    }

    // ---------------------------------------------
    // ✅ 실행 시간 측정 (iOS measureTime 대응)
    // ---------------------------------------------

    fun measureTime(level: String, block: () -> Unit): Double {
        val start = SystemClock.elapsedRealtime()
        block()
        val elapsed = (SystemClock.elapsedRealtime() - start).toDouble()

        val history = timeHistory.getOrPut(level) { mutableListOf() }
        history.add(elapsed)
        if (history.size > historySize) history.removeAt(0)

        val average = history.average()

        when (level) {
            "level1" -> averageLevel1Time = average
            "level2" -> averageLevel2Time = average
            "level3" -> averageLevel3Time = average
        }

        return elapsed
    }

    // ---------------------------------------------
    // ✅ 성능 리포트
    // ---------------------------------------------

    fun getPerformanceReport(): String {
        val total =
            averageLevel1Time +
                    averageLevel2Time / 5 +
                    averageLevel3Time / 30

        return """
            📊 성능 리포트:
            - Level 1 (RTMPose): ${"%.1f".format(averageLevel1Time)}ms
            - Level 2 (Depth): ${"%.1f".format(averageLevel2Time)}ms
            - Level 3 (Grounding): ${"%.1f".format(averageLevel3Time)}ms
            - 총 프레임 시간: ${"%.1f".format(total)}ms
        """.trimIndent()
    }
}
