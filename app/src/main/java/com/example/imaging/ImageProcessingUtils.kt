package com.example.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class QuadCorners(
    val topLeft: PointF = PointF(0.06f, 0.06f),
    val topRight: PointF = PointF(0.94f, 0.06f),
    val bottomRight: PointF = PointF(0.94f, 0.94f),
    val bottomLeft: PointF = PointF(0.06f, 0.94f)
) {
    fun toNormalizedFloatArray(): FloatArray = floatArrayOf(
        topLeft.x, topLeft.y,
        topRight.x, topRight.y,
        bottomRight.x, bottomRight.y,
        bottomLeft.x, bottomLeft.y
    )

    fun toJson(): String {
        return "${topLeft.x},${topLeft.y},${topRight.x},${topRight.y},${bottomRight.x},${bottomRight.y},${bottomLeft.x},${bottomLeft.y}"
    }

    companion object {
        fun fromJson(json: String?): QuadCorners {
            if (json.isNullOrBlank()) return QuadCorners()
            return try {
                val parts = json.split(",").map { it.trim().toFloat() }
                if (parts.size >= 8) {
                    QuadCorners(
                        topLeft = PointF(parts[0], parts[1]),
                        topRight = PointF(parts[2], parts[3]),
                        bottomRight = PointF(parts[4], parts[5]),
                        bottomLeft = PointF(parts[6], parts[7])
                    )
                } else {
                    QuadCorners()
                }
            } catch (e: Exception) {
                QuadCorners()
            }
        }
    }
}

enum class DocumentFilter(val displayName: String) {
    ORIGINAL("Original"),
    COLOR_ENHANCE("Color"),
    GRAYSCALE("Escala de grises"),
    BLACK_WHITE("Blanco y negro"),
    DOC_ENHANCE("Doc Mejorado")
}

object ImageProcessingUtils {

    suspend fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap = withContext(Dispatchers.Default) {
        if (degrees % 360 == 0) return@withContext bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    suspend fun warpPerspective(
        source: Bitmap,
        corners: QuadCorners
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = source.width.toFloat()
        val h = source.height.toFloat()

        // Pixel coordinates from normalized coordinates
        val pTL = PointF(corners.topLeft.x * w, corners.topLeft.y * h)
        val pTR = PointF(corners.topRight.x * w, corners.topRight.y * h)
        val pBR = PointF(corners.bottomRight.x * w, corners.bottomRight.y * h)
        val pBL = PointF(corners.bottomLeft.x * w, corners.bottomLeft.y * h)

        // Calculate expected target rectangle dimensions
        val topWidth = hypot((pTR.x - pTL.x).toDouble(), (pTR.y - pTL.y).toDouble())
        val bottomWidth = hypot((pBR.x - pBL.x).toDouble(), (pBR.y - pBL.y).toDouble())
        val targetWidth = max(topWidth, bottomWidth).toInt().coerceAtLeast(100)

        val leftHeight = hypot((pBL.x - pTL.x).toDouble(), (pBL.y - pTL.y).toDouble())
        val rightHeight = hypot((pBR.x - pTR.x).toDouble(), (pBR.y - pTR.y).toDouble())
        val targetHeight = max(leftHeight, rightHeight).toInt().coerceAtLeast(100)

        val srcPoints = floatArrayOf(
            pTL.x, pTL.y,
            pTR.x, pTR.y,
            pBR.x, pBR.y,
            pBL.x, pBL.y
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        val polySuccess = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        if (!polySuccess) {
            return@withContext source
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        result
    }

    suspend fun applyAdjustmentsAndFilter(
        source: Bitmap,
        filter: DocumentFilter,
        brightness: Float, // -100 to 100
        contrast: Float     // 0.5 to 2.0 (1.0 = normal)
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        when (filter) {
            DocumentFilter.ORIGINAL -> {
                val cm = ColorMatrix()
                applyBrightnessContrast(cm, brightness, contrast)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }

            DocumentFilter.COLOR_ENHANCE -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.35f)
                val adjustCm = ColorMatrix()
                applyBrightnessContrast(adjustCm, brightness + 10f, contrast * 1.15f)
                cm.postConcat(adjustCm)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }

            DocumentFilter.GRAYSCALE -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                val adjustCm = ColorMatrix()
                applyBrightnessContrast(adjustCm, brightness + 15f, contrast * 1.2f)
                cm.postConcat(adjustCm)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }

            DocumentFilter.BLACK_WHITE -> {
                // Adaptive high-contrast thresholding for crisp text and pure white background
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)

                // Compute average luminance
                var sumLum = 0L
                for (pixel in pixels) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    sumLum += (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                }
                val avgLum = (sumLum / pixels.size).toInt()
                val threshold = (avgLum * 0.95f + brightness).toInt().coerceIn(40, 220)

                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    val finalPixel = if (lum > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    pixels[i] = finalPixel
                }
                output.setPixels(pixels, 0, width, 0, 0, width, height)
            }

            DocumentFilter.DOC_ENHANCE -> {
                // Magic Color: lightens parchment/shadows while keeping ink vivid and sharp
                val cm = ColorMatrix()
                // Luminance matrix that boosts brightness for background and keeps letters dark
                val scale = contrast * 1.3f
                val translate = brightness + 28f
                cm.set(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }
        }

        output
    }

    private fun applyBrightnessContrast(cm: ColorMatrix, brightness: Float, contrast: Float) {
        val scale = contrast
        val translate = (1f - scale) * 128f + brightness
        cm.set(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
    }
}
