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
    // Por defecto, la imagen completa: si el detector no está seguro, no recorta nada.
    val topLeft: PointF = PointF(0f, 0f),
    val topRight: PointF = PointF(1f, 0f),
    val bottomRight: PointF = PointF(1f, 1f),
    val bottomLeft: PointF = PointF(0f, 1f)
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

enum class DocumentFilter(val displayName: String, val shortLabel: String) {
    ORIGINAL("Original", "Sin filtro"),
    AUTOMATIC("Automático", "Niveles"),
    DOC_ENHANCE("Doc Mejorado", "Documento"),
    HIGH_CONTRAST("Alto contraste", "Contraste"),
    GRAYSCALE("Escala de grises", "Grises"),
    BLACK_WHITE("Blanco y negro", "B/N"),
    RECEIPT("Factura / ticket", "Ticket"),
    LIGHTEN("Aclarar", "Aclarar"),
    COLOR_ENHANCE("Color vivo", "Vivo"),
    SEPIA("Sepia", "Sepia")
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

            DocumentFilter.AUTOMATIC -> {
                // Auto-niveles: estira el histograma de ESTA foto (percentiles 2 % y 98 %).
                // Es lo que hace el "auto" de un escáner: fondo limpio sin quemar el texto.
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                autoLevels(pixels)
                output.setPixels(pixels, 0, width, 0, 0, width, height)
            }

            DocumentFilter.COLOR_ENHANCE -> {
                val cm = ColorMatrix()
                cm.setSaturation(1.45f)
                val adjustCm = ColorMatrix()
                applyBrightnessContrast(adjustCm, brightness + 6f, contrast * 1.10f)
                cm.postConcat(adjustCm)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }

            DocumentFilter.GRAYSCALE -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                val adjustCm = ColorMatrix()
                applyBrightnessContrast(adjustCm, brightness + 12f, contrast * 1.15f)
                cm.postConcat(adjustCm)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }

            DocumentFilter.HIGH_CONTRAST -> {
                // Gris con curva en S: el papel queda blanco y la tinta negra, sin binarizar.
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                val sCurve = ColorMatrix()
                applyBrightnessContrast(sCurve, brightness + 18f, contrast * 1.45f)
                cm.postConcat(sCurve)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }

            DocumentFilter.BLACK_WHITE -> {
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                val threshold = percentileThreshold(pixels, 0.62f, brightness)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val lum = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)).toInt()
                    pixels[i] = if (lum > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                }
                output.setPixels(pixels, 0, width, 0, 0, width, height)
            }

            DocumentFilter.RECEIPT -> {
                // Tickets y facturas: papel térmico grisáceo, texto fino. Se estira el
                // contraste y se empuja el fondo a blanco sin llegar a binarizar.
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                autoLevels(pixels, lowPct = 0.02f, highPct = 0.62f)
                val white = percentileThreshold(pixels, 0.70f, 0f)
                val gain = 1.35f
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    var lum = 0.299f * r + 0.587f * g + 0.114f * b
                    lum = ((lum - white) * gain + white).coerceIn(0f, 255f)
                    val v = lum.toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                output.setPixels(pixels, 0, width, 0, 0, width, height)
            }

            DocumentFilter.LIGHTEN -> {
                // Aclara sombras (gamma) manteniendo el texto oscuro.
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                val lut = IntArray(256) { v ->
                    val x = (v / 255f)
                    val y = Math.pow(x.toDouble(), 0.72).toFloat()
                    (y * 255f).toInt().coerceIn(0, 255)
                }
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r = lut[(p shr 16) and 0xFF]
                    val g = lut[(p shr 8) and 0xFF]
                    val b = lut[p and 0xFF]
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                output.setPixels(pixels, 0, width, 0, 0, width, height)
            }

            DocumentFilter.SEPIA -> {
                val cm = ColorMatrix()
                cm.setSaturation(0.25f)
                val sepia = ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(sepia)
                val adjustCm = ColorMatrix()
                applyBrightnessContrast(adjustCm, brightness, contrast)
                cm.postConcat(adjustCm)
                paint.colorFilter = ColorMatrixColorFilter(cm)
                canvas.drawBitmap(source, 0f, 0f, paint)
            }

            DocumentFilter.DOC_ENHANCE -> {
                // Papel a blanco manteniendo la tinta: escala de luminancia con ganancia.
                val cm = ColorMatrix()
                val scale = contrast * 1.30f
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

    /** Estira el histograma entre dos percentiles: el "auto" de un escáner. */
    private fun autoLevels(pixels: IntArray, lowPct: Float = 0.02f, highPct: Float = 0.98f) {
        val hist = IntArray(256)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            hist[((0.299f * r + 0.587f * g + 0.114f * b).toInt()).coerceIn(0, 255)]++
        }
        val total = pixels.size
        var acc = 0
        var lo = 0
        var hi = 255
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= total * lowPct) { lo = v; break }
        }
        acc = 0
        for (v in 255 downTo 0) {
            acc += hist[v]
            if (acc >= total * (1f - highPct)) { hi = v; break }
        }
        if (hi - lo < 8) return
        val lut = IntArray(256) { v ->
            (((v - lo).toFloat() / (hi - lo)) * 255f).toInt().coerceIn(0, 255)
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val b = lut[p and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Luminancia por debajo de la cual está el [pct] de los píxeles. */
    private fun percentileThreshold(pixels: IntArray, pct: Float, shift: Float): Int {
        val hist = IntArray(256)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            hist[((0.299f * r + 0.587f * g + 0.114f * b).toInt()).coerceIn(0, 255)]++
        }
        val target = pixels.size * pct
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return (v + shift).toInt().coerceIn(24, 232)
        }
        return 128
    }
}
