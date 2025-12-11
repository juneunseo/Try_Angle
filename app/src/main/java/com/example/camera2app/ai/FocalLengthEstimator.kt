package com.example.camera2app.ai

import android.graphics.ImageFormat
import android.media.ExifInterface
import com.example.camera2app.ai.models.FocalLengthInfo
import com.example.camera2app.ai.models.DepthResult
import java.io.File
import kotlin.math.roundToInt

/**
 * Focal Length Estimator - Estimates 35mm equivalent focal length
 * Ported from iOS TryAngle v1.5 FocalLengthEstimator.swift (331 lines)
 *
 * USAGE:
 * Copy this file to: camera2app_new/app/src/main/java/com/example/camera2app/ai/
 *
 * Data Sources (in priority order):
 * 1. EXIF metadata (confidence: 1.0)
 * 2. Camera zoom factor (confidence: 1.0)
 * 3. Depth map estimation (confidence: 0.5-0.8)
 * 4. User input (confidence: 1.0)
 * 5. Fallback default 50mm (confidence: 0.3)
 *
 * iPhone Reference:
 * 0.5x = 13mm (Ultra Wide)
 * 1.0x = 24mm (Wide)
 * 2.0x = 48mm (Normal)
 * 3.0x = 72mm (Semi-Telephoto)
 * 5.0x = 120mm (Telephoto)
 */
class FocalLengthEstimator {

    companion object {
        // iPhone base focal length (1x zoom = 24mm)
        const val IPHONE_BASE_FOCAL_LENGTH = 24

        // Android typical base focal length (varies by device)
        const val ANDROID_BASE_FOCAL_LENGTH = 26  // Samsung/Pixel average

        // Fallback default
        const val DEFAULT_FOCAL_LENGTH = 50

        // Sensor crop factors for estimation
        const val SMARTPHONE_CROP_FACTOR = 6.5f  // ~6-7x typical
    }

    /**
     * Calculate focal length from camera zoom factor
     * @param zoomFactor Camera zoom (0.5, 1.0, 2.0, 3.0, etc.)
     * @param baseFocalLength Device base focal length (default: 26mm for Android)
     * @return Focal length info with high confidence
     */
    fun focalLengthFromZoom(
        zoomFactor: Float,
        baseFocalLength: Int = ANDROID_BASE_FOCAL_LENGTH
    ): FocalLengthInfo {
        val focalLength = (baseFocalLength * zoomFactor).roundToInt()

        return FocalLengthInfo(
            focalLength35mm = focalLength.coerceAtLeast(13),  // Min 13mm (0.5x)
            source = FocalLengthInfo.FocalLengthSource.ZOOM_CALCULATION,
            confidence = 1.0f,
            lensType = FocalLengthInfo.LensType.from(focalLength)
        )
    }

    /**
     * Extract focal length from EXIF metadata
     * @param imagePath Path to image file
     * @return Focal length info or null if EXIF not available
     */
    fun extractFocalLengthFromEXIF(imagePath: String): FocalLengthInfo? {
        return try {
            val exif = ExifInterface(imagePath)

            // Try to get 35mm equivalent focal length directly
            val focalLength35mm = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.toIntOrNull()

            if (focalLength35mm != null && focalLength35mm > 0) {
                return FocalLengthInfo(
                    focalLength35mm = focalLength35mm,
                    source = FocalLengthInfo.FocalLengthSource.EXIF,
                    confidence = 1.0f,
                    lensType = FocalLengthInfo.LensType.from(focalLength35mm)
                )
            }

            // Try actual focal length (need to convert with crop factor)
            val focalLengthStr = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
            if (focalLengthStr != null) {
                val focalLength = parseFocalLength(focalLengthStr)
                if (focalLength != null && focalLength > 0) {
                    // Estimate 35mm equivalent using smartphone crop factor
                    val estimated35mm = (focalLength * SMARTPHONE_CROP_FACTOR).roundToInt()
                    return FocalLengthInfo(
                        focalLength35mm = estimated35mm,
                        source = FocalLengthInfo.FocalLengthSource.EXIF,
                        confidence = 0.7f,  // Lower confidence for estimated conversion
                        lensType = FocalLengthInfo.LensType.from(estimated35mm)
                    )
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract focal length from EXIF (File object)
     */
    fun extractFocalLengthFromEXIF(imageFile: File): FocalLengthInfo? {
        return extractFocalLengthFromEXIF(imageFile.absolutePath)
    }

    /**
     * Estimate focal length from depth map compression index
     * @param depthResult Depth estimation result
     * @return Estimated focal length info
     */
    fun estimateFocalLengthFromDepth(depthResult: DepthResult): FocalLengthInfo {
        // Compression index: 0.0 = wide angle, 1.0 = telephoto
        // Map to focal length range: 24mm (wide) ~ 120mm (telephoto)

        val minFocal = 24f
        val maxFocal = 120f
        val focalLength = (minFocal + (maxFocal - minFocal) * depthResult.compressionIndex).roundToInt()

        // Confidence based on depth difference (more difference = more confident)
        val confidence = (0.5f + depthResult.depthDifference * 0.3f).coerceIn(0.5f, 0.8f)

        return FocalLengthInfo(
            focalLength35mm = focalLength,
            source = FocalLengthInfo.FocalLengthSource.DEPTH_ESTIMATE,
            confidence = confidence,
            lensType = FocalLengthInfo.LensType.from(focalLength)
        )
    }

    /**
     * Get focal length with fallback priority
     * Tries multiple sources in order: EXIF -> Zoom -> Depth -> Default
     */
    fun getFocalLengthWithFallback(
        imagePath: String? = null,
        zoomFactor: Float? = null,
        depthResult: DepthResult? = null
    ): FocalLengthInfo {
        // 1. Try EXIF first (highest confidence)
        if (imagePath != null) {
            extractFocalLengthFromEXIF(imagePath)?.let { return it }
        }

        // 2. Try zoom factor
        if (zoomFactor != null) {
            return focalLengthFromZoom(zoomFactor)
        }

        // 3. Try depth estimation
        if (depthResult != null) {
            return estimateFocalLengthFromDepth(depthResult)
        }

        // 4. Fallback to default
        return FocalLengthInfo(
            focalLength35mm = DEFAULT_FOCAL_LENGTH,
            source = FocalLengthInfo.FocalLengthSource.FALLBACK,
            confidence = 0.3f,
            lensType = FocalLengthInfo.LensType.from(DEFAULT_FOCAL_LENGTH)
        )
    }

    /**
     * Parse focal length from EXIF string (handles "4.6mm" or "4.6/1" formats)
     */
    private fun parseFocalLength(focalLengthStr: String): Float? {
        return try {
            // Remove "mm" suffix if present
            val cleaned = focalLengthStr.replace("mm", "").trim()

            // Handle rational format "numerator/denominator"
            if (cleaned.contains("/")) {
                val parts = cleaned.split("/")
                if (parts.size == 2) {
                    val numerator = parts[0].toFloat()
                    val denominator = parts[1].toFloat()
                    return numerator / denominator
                }
            }

            // Handle decimal format
            cleaned.toFloatOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get recommended zoom factor to match target focal length
     * @param currentFocal Current focal length (35mm equiv)
     * @param targetFocal Target focal length (35mm equiv)
     * @param baseFocalLength Device base focal length
     * @return Recommended zoom factor
     */
    fun getRecommendedZoom(
        currentFocal: Int,
        targetFocal: Int,
        baseFocalLength: Int = ANDROID_BASE_FOCAL_LENGTH
    ): Float {
        val currentZoom = currentFocal.toFloat() / baseFocalLength
        val targetZoom = targetFocal.toFloat() / baseFocalLength

        // Return zoom change factor
        return targetZoom / currentZoom.coerceAtLeast(0.1f)
    }

    /**
     * Get focal length difference description (Korean)
     */
    fun describeFocalLengthDifference(
        currentFocal: Int,
        targetFocal: Int
    ): String {
        val diff = targetFocal - currentFocal

        return when {
            diff == 0 -> "초점거리 일치"
            diff > 0 -> "${diff}mm 더 망원으로"
            else -> "${-diff}mm 더 광각으로"
        }
    }

    /**
     * Check if focal lengths are similar (within threshold)
     */
    fun areSimilarFocalLengths(
        focal1: Int,
        focal2: Int,
        threshold: Int = 10
    ): Boolean {
        return kotlin.math.abs(focal1 - focal2) <= threshold
    }
}

/**
 * Extension function for FocalLengthInfo
 */
fun FocalLengthInfo.toDisplayString(): String {
    return "${focalLength35mm}mm ${lensType.displayName} (${source.name}, ${(confidence * 100).toInt()}%)"
}
