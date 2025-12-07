package com.example.camera2app.ai

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.SizeF


object CacheManager {

    // 메모리 캐시
    private val referenceCache = mutableMapOf<String, CachedReference>()
    private val calibrationFactors = mutableMapOf<String, CalibrationFactor>()

    // 캐시 설정
    private const val MAX_CACHE_SIZE = 5
    private const val CACHE_TIMEOUT = 60 * 60 * 1000L   // 1시간 (ms)

    // ----------------------------------------------------
    // ✅ 레퍼런스 캐싱
    // ----------------------------------------------------

    fun cacheReference(
        id: String,
        image: Bitmap,
        bbox: RectF,
        margins: MarginInfo,
        compressionIndex: Float? = null
    ): CachedReference {

        if (referenceCache.size >= MAX_CACHE_SIZE) {
            removeOldestCache()
        }

        val cached = CachedReference(
            id = id,
            image = image,
            bbox = bbox,
            imageSize = SizeF(image.width.toFloat(), image.height.toFloat()),
            margins = margins,
            compressionIndex = compressionIndex,
            timestamp = System.currentTimeMillis()
        )

        referenceCache[id] = cached
        println("📦 레퍼런스 캐시 저장: $id")

        return cached
    }

    fun getReference(id: String): CachedReference? {
        val cached = referenceCache[id] ?: return null

        if (System.currentTimeMillis() - cached.timestamp > CACHE_TIMEOUT) {
            referenceCache.remove(id)
            println("⏰ 캐시 만료됨: $id")
            return null
        }

        return cached
    }

    fun getCurrentReference(): CachedReference? {
        return referenceCache.values
            .sortedByDescending { it.timestamp }
            .firstOrNull()
    }

    // ----------------------------------------------------
    // ✅ 보정 계수
    // ----------------------------------------------------

    fun saveCalibration(id: String, factor: CalibrationFactor) {
        calibrationFactors[id] = factor
        println("🔧 보정 계수 저장: $id")
    }

    fun getCalibration(id: String): CalibrationFactor {
        return calibrationFactors[id] ?: CalibrationFactor.IDENTITY
    }

    fun applyCalibration(
        margins: MarginInfo,
        calibrationId: String
    ): MarginInfo {

        val factor = getCalibration(calibrationId)

        return margins.copy(
            leftRatio = margins.leftRatio * factor.leftRatio,
            rightRatio = margins.rightRatio * factor.rightRatio,
            topRatio = margins.topRatio * factor.topRatio,
            bottomRatio = margins.bottomRatio * factor.bottomRatio
        )
    }

    // ----------------------------------------------------
    // ✅ 캐시 관리
    // ----------------------------------------------------

    fun removeCache(id: String) {
        referenceCache.remove(id)
        calibrationFactors.remove(id)
        println("🗑️ 캐시 삭제: $id")
    }

    fun clearAllCache() {
        referenceCache.clear()
        calibrationFactors.clear()
        println("🗑️ 모든 캐시 삭제됨")
    }

    private fun removeOldestCache() {
        val oldest = referenceCache.values.minByOrNull { it.timestamp } ?: return
        removeCache(oldest.id)
    }

    // ----------------------------------------------------
    // ✅ 통계
    // ----------------------------------------------------

    val cacheCount: Int
        get() = referenceCache.size

    val cachedIds: List<String>
        get() = referenceCache.keys.toList()
}
