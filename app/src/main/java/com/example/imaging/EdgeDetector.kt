package com.example.imaging

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(
    val corners: QuadCorners,
    val isDocumentDetected: Boolean,
    val message: String
)

object EdgeDetector {

    suspend fun detectPaperEdges(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        try {
            // Scale down for rapid processing without lag
            val maxDim = 320
            val scale = max(bitmap.width, bitmap.height).toFloat() / maxDim
            val targetW = (bitmap.width / scale).toInt().coerceAtLeast(60)
            val targetH = (bitmap.height / scale).toInt().coerceAtLeast(60)

            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            val pixels = IntArray(targetW * targetH)
            scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

            // Convert to grayscale luminance
            val lum = FloatArray(targetW * targetH)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            // Compute gradient magnitude using Sobel-like convolution
            val grad = FloatArray(targetW * targetH)
            var totalEnergy = 0f
            for (y in 1 until targetH - 1) {
                for (x in 1 until targetW - 1) {
                    val idx = y * targetW + x
                    val gx = (lum[idx + 1] - lum[idx - 1]) +
                            0.5f * (lum[idx + 1 - targetW] - lum[idx - 1 - targetW]) +
                            0.5f * (lum[idx + 1 + targetW] - lum[idx - 1 + targetW])
                    val gy = (lum[idx + targetW] - lum[idx - targetW]) +
                            0.5f * (lum[idx + targetW - 1] - lum[idx - targetW - 1]) +
                            0.5f * (lum[idx + targetW + 1] - lum[idx - targetW + 1])
                    val mag = abs(gx) + abs(gy)
                    grad[idx] = mag
                    totalEnergy += mag
                }
            }

            val avgEnergy = totalEnergy / (targetW * targetH)

            // Find bounds: search inward from the 4 borders for the strongest gradient boundary
            var bestLeft = (targetW * 0.08f).toInt()
            var bestRight = (targetW * 0.92f).toInt()
            var bestTop = (targetH * 0.08f).toInt()
            var bestBottom = (targetH * 0.92f).toInt()

            val marginLimitX = (targetW * 0.35f).toInt()
            val marginLimitY = (targetH * 0.35f).toInt()

            // Left search
            var maxColSum = 0f
            for (x in (targetW * 0.04f).toInt() until marginLimitX) {
                var colSum = 0f
                for (y in (targetH * 0.2f).toInt() until (targetH * 0.8f).toInt()) {
                    colSum += grad[y * targetW + x]
                }
                if (colSum > maxColSum && colSum > avgEnergy * 1.6f * (targetH * 0.6f)) {
                    maxColSum = colSum
                    bestLeft = x
                }
            }

            // Right search
            maxColSum = 0f
            for (x in targetW - (targetW * 0.04f).toInt() downTo targetW - marginLimitX) {
                var colSum = 0f
                for (y in (targetH * 0.2f).toInt() until (targetH * 0.8f).toInt()) {
                    colSum += grad[y * targetW + x]
                }
                if (colSum > maxColSum && colSum > avgEnergy * 1.6f * (targetH * 0.6f)) {
                    maxColSum = colSum
                    bestRight = x
                }
            }

            // Top search
            var maxRowSum = 0f
            for (y in (targetH * 0.04f).toInt() until marginLimitY) {
                var rowSum = 0f
                for (x in (targetW * 0.2f).toInt() until (targetW * 0.8f).toInt()) {
                    rowSum += grad[y * targetW + x]
                }
                if (rowSum > maxRowSum && rowSum > avgEnergy * 1.6f * (targetW * 0.6f)) {
                    maxRowSum = rowSum
                    bestTop = y
                }
            }

            // Bottom search
            maxRowSum = 0f
            for (y in targetH - (targetH * 0.04f).toInt() downTo targetH - marginLimitY) {
                var rowSum = 0f
                for (x in (targetW * 0.2f).toInt() until (targetW * 0.8f).toInt()) {
                    rowSum += grad[y * targetW + x]
                }
                if (rowSum > maxRowSum && rowSum > avgEnergy * 1.6f * (targetW * 0.6f)) {
                    maxRowSum = rowSum
                    bestBottom = y
                }
            }

            val detected = (bestRight - bestLeft) > targetW * 0.35f && (bestBottom - bestTop) > targetH * 0.35f

            // Slightly perturb corner points for natural perspective quadrilateral
            val normTL = PointF(bestLeft.toFloat() / targetW, bestTop.toFloat() / targetH)
            val normTR = PointF(bestRight.toFloat() / targetW, bestTop.toFloat() / targetH)
            val normBR = PointF(bestRight.toFloat() / targetW, bestBottom.toFloat() / targetH)
            val normBL = PointF(bestLeft.toFloat() / targetW, bestBottom.toFloat() / targetH)

            val corners = QuadCorners(
                topLeft = normTL,
                topRight = normTR,
                bottomRight = normBR,
                bottomLeft = normBL
            )

            if (detected) {
                DetectionResult(
                    corners = corners,
                    isDocumentDetected = true,
                    message = "Documento detectado automáticamente"
                )
            } else {
                DetectionResult(
                    corners = QuadCorners(
                        topLeft = PointF(0.06f, 0.06f),
                        topRight = PointF(0.94f, 0.06f),
                        bottomRight = PointF(0.94f, 0.94f),
                        bottomLeft = PointF(0.06f, 0.94f)
                    ),
                    isDocumentDetected = false,
                    message = "Bordes poco contrastados. Ajusta las 4 esquinas manualmente."
                )
            }
        } catch (e: Exception) {
            DetectionResult(
                corners = QuadCorners(),
                isDocumentDetected = false,
                message = "Ajusta las esquinas del documento"
            )
        }
    }
}
