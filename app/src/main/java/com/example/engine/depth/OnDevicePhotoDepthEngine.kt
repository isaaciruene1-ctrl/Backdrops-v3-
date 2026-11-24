package com.example.engine.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Dedicated On-Device Photo Depth Engine optimized for real user-imported gallery / Photo Picker
 * photographs (people, pets, architectural monuments, flora).
 *
 * Performs safe EXIF/bounds-clamped bitmap decoding, prioritizes portrait foreground separation,
 * and fuses ML Kit Subject Segmentation with Backdrop's CIE L*a*b* multi-layer contour solver.
 */
class OnDevicePhotoDepthEngine(
    private val coreEngine: DepthDetectionEngine = DepthDetectionEngine()
) {

    suspend fun decodeAndExtractPhoto(
        context: Context,
        uri: Uri,
        cacheKey: String = "photo_$uri"
    ): Pair<Bitmap, DepthDetectionResult>? = withContext(Dispatchers.IO) {
        val raw = decodeBoundedSoftwareBitmap(context, uri, MAX_LONG_EDGE_PX) ?: return@withContext null
        val normalized = normalizeMaxEdge(raw, MAX_LONG_EDGE_PX)
        val result = coreEngine.detectAndExtract(
            sourceBitmap = normalized,
            titleHint = "Personal Photo",
            cacheKey = cacheKey,
            preferPortraitPrior = true
        )
        normalized to result
    }

    suspend fun extractFromPhotoBitmap(
        bitmap: Bitmap,
        cacheKey: String? = null
    ): DepthDetectionResult {
        val normalized = normalizeMaxEdge(bitmap, MAX_LONG_EDGE_PX)
        return coreEngine.detectAndExtract(
            sourceBitmap = normalized,
            titleHint = "Personal Photo",
            cacheKey = cacheKey,
            preferPortraitPrior = true
        )
    }

    private fun normalizeMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(32)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(32)
        return bitmap.scale(w, h)
    }

    private fun decodeBoundedSoftwareBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxEdge) {
                        val factor = maxEdge.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * factor).toInt().coerceAtLeast(64),
                            (info.size.height * factor).toInt().coerceAtLeast(64)
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrNull()

    companion object {
        private const val MAX_LONG_EDGE_PX = 1600
    }
}
