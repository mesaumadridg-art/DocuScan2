package com.example.imaging

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DetectionResult(
    val corners: QuadCorners,
    val isDocumentDetected: Boolean,
    val message: String
)

/**
 * Detector de bordes de documento.
 *
 * Estrategia doble, porque un solo método falla en la mitad de los casos reales:
 *
 *  1. Segmentación por luminancia: el papel suele ser la región más clara de la foto. Se
 *     binariza con el umbral de Otsu, se toma la componente conexa más grande y de ahí se
 *     sacan las 4 esquinas por los extremos de (x+y) y (x-y). Esto sí respeta la perspectiva
 *     (el cuadrilátero sale inclinado si la hoja está torcida).
 *  2. Gradientes: para cuando el papel ocupa casi todo el encuadre y no hay fondo oscuro que
 *     lo separe. Busca las 4 rectas de borde en los márgenes con un umbral adaptativo
 *     (media + desviación), no un múltiplo fijo de la energía media.
 *
 * Se queda con el candidato de mayor área válida; si ninguno convence, devuelve un marco
 * interior y avisa que hay que ajustar a mano.
 */
object EdgeDetector {

    private const val MAX_DIM = 480
    private const val MIN_AREA_RATIO = 0.10f
    private const val MAX_AREA_RATIO = 0.985f
    /** Por encima de esto el "papel" es en realidad toda la foto: no hay borde que detectar. */
    private const val FULL_FRAME_RATIO = 0.86f

    suspend fun detectPaperEdges(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        try {
            val scaled = scaleDown(bitmap)
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val lum = luminance(pixels)

            val paperQuad = detectByLuminance(lum, w, h)
            val gradientQuad = detectByGradient(lum, w, h)

            val paperArea = paperQuad?.let { quadArea(it) } ?: 0f
            val gradientArea = gradientQuad?.let { quadArea(it) } ?: 0f
            val total = (w * h).toFloat()

            // El papel "que llena todo" es sospechoso: en ese caso manda el gradiente.
            val paperUsable = paperQuad != null && paperArea / total <= FULL_FRAME_RATIO
            val chosen = when {
                paperUsable && paperArea >= gradientArea -> paperQuad
                gradientQuad != null && gradientArea / total >= MIN_AREA_RATIO -> gradientQuad
                else -> paperQuad
            }

            if (chosen == null) {
                return@withContext DetectionResult(
                    corners = insetCorners(0.04f),
                    isDocumentDetected = false,
                    message = "No se detectó el borde. Ajusta las 4 esquinas o usa Página completa."
                )
            }

            val areaRatio = quadArea(chosen) / total
            if (areaRatio < MIN_AREA_RATIO || areaRatio > MAX_AREA_RATIO || !isSaneQuad(chosen)) {
                return@withContext DetectionResult(
                    corners = insetCorners(0.04f),
                    isDocumentDetected = false,
                    message = "Bordes poco claros. Ajusta las esquinas manualmente."
                )
            }

            DetectionResult(
                corners = toNormalized(chosen, w, h),
                isDocumentDetected = true,
                message = "Documento detectado · ${(areaRatio * 100).toInt()}% del encuadre"
            )
        } catch (e: Exception) {
            DetectionResult(
                corners = insetCorners(0.05f),
                isDocumentDetected = false,
                message = "Ajusta las esquinas del documento"
            )
        }
    }

    // ---------------------------------------------------------------- luminancia (papel)

    /** Máscara del papel por umbral de Otsu + componente conexa mayor + esquinas por extremos. */
    private fun detectByLuminance(lum: FloatArray, w: Int, h: Int): List<PointF>? {
        val threshold = otsuThreshold(lum).toFloat()
        val mask = BooleanArray(lum.size)

        // El papel es claro: se prueba "claro sobre fondo oscuro" y, si sale una región
        // absurda (casi todo o casi nada), el inverso (documento oscuro sobre mesa clara).
        var bright = 0
        for (i in lum.indices) {
            if (lum[i] >= threshold) {
                mask[i] = true
                bright++
            }
        }
        val brightRatio = bright.toFloat() / lum.size
        if (brightRatio < 0.05f || brightRatio > 0.97f) {
            for (i in lum.indices) mask[i] = !mask[i]
        }

        val component = largestComponent(mask, w, h) ?: return null
        if (component.size < lum.size * 0.05f) return null

        // Esquinas por extremos de las diagonales: con perspectiva leve esto da el cuadrilátero
        // real, no el rectángulo envolvente.
        var tlScore = Float.MAX_VALUE
        var brScore = -Float.MAX_VALUE
        var trScore = -Float.MAX_VALUE
        var blScore = Float.MAX_VALUE
        var tl = component[0]
        var br = component[0]
        var tr = component[0]
        var bl = component[0]

        for (idx in component) {
            val x = (idx % w).toFloat()
            val y = (idx / w).toFloat()
            val sum = x + y
            val diff = x - y
            if (sum < tlScore) { tlScore = sum; tl = idx }
            if (sum > brScore) { brScore = sum; br = idx }
            if (diff > trScore) { trScore = diff; tr = idx }
            if (diff < blScore) { blScore = diff; bl = idx }
        }

        fun pt(idx: Int) = PointF((idx % w).toFloat(), (idx / w).toFloat())

        val quad = listOf(pt(tl), pt(tr), pt(br), pt(bl))
        return expandOutward(quad, w, h, 1.5f)
    }

    private fun otsuThreshold(lum: FloatArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.toInt().coerceIn(0, 255)]++
        val total = lum.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var weightB = 0
        var bestVar = -1.0
        var threshold = 127
        for (t in 0..255) {
            weightB += hist[t]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break
            sumB += t.toDouble() * hist[t]
            val meanB = sumB / weightB
            val meanF = (sum - sumB) / weightF
            val between = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)
            if (between > bestVar) {
                bestVar = between
                threshold = t
            }
        }
        return threshold
    }

    /** BFS iterativo (sin recursión: una pila explícita aguanta imágenes de 480 px sin desbordar). */
    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val visited = BooleanArray(mask.size)
        var best: IntArray? = null
        val stack = IntArray(mask.size)
        val current = IntArray(mask.size)

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var top = 0
            var size = 0
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val idx = stack[--top]
                current[size++] = idx
                val x = idx % w
                val y = idx / w
                if (x > 0) {
                    val n = idx - 1
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
                if (x < w - 1) {
                    val n = idx + 1
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
                if (y > 0) {
                    val n = idx - w
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
                if (y < h - 1) {
                    val n = idx + w
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
            }
            if (best == null || size > best.size) best = current.copyOf(size)
        }
        return best
    }

    // ---------------------------------------------------------------- gradientes (respaldo)

    /** Busca las 4 rectas de borde con umbral adaptativo (media + desviación de la energía). */
    private fun detectByGradient(lum: FloatArray, w: Int, h: Int): List<PointF>? {
        val grad = FloatArray(lum.size)
        var sumEnergy = 0f
        var sumSquares = 0f
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx = (lum[idx + 1] - lum[idx - 1]) +
                    0.5f * (lum[idx + 1 - w] - lum[idx - 1 - w]) +
                    0.5f * (lum[idx + 1 + w] - lum[idx - 1 + w])
                val gy = (lum[idx + w] - lum[idx - w]) +
                    0.5f * (lum[idx + w - 1] - lum[idx - w - 1]) +
                    0.5f * (lum[idx + w + 1] - lum[idx - w + 1])
                val mag = abs(gx) + abs(gy)
                grad[idx] = mag
                sumEnergy += mag
                sumSquares += mag * mag
                count++
            }
        }
        if (count == 0) return null
        val mean = sumEnergy / count
        val std = sqrt(max(0f, sumSquares / count - mean * mean))
        // Umbral adaptativo: un borde real tiene que destacar sobre el ruido de esa foto.
        val cut = mean + std

        val band = (h * 0.6f).toInt()
        val bandTop = (h * 0.2f).toInt()
        val bandW = (w * 0.6f).toInt()
        val bandLeft = (w * 0.2f).toInt()

        fun bestColumn(from: Int, until: Int): Int? {
            var bestCol: Int? = null
            var bestScore = 0f
            for (x in from until until) {
                var score = 0f
                for (y in bandTop until bandTop + band) score += grad[y * w + x]
                if (score > bestScore) { bestScore = score; bestCol = x }
            }
            // Debe superar el umbral del 55 % de la banda (antes se exigía un 60 % fijo).
            return if (bestScore > cut * band * 0.55f) bestCol else null
        }

        fun bestRow(from: Int, until: Int): Int? {
            var bestRow: Int? = null
            var bestScore = 0f
            for (y in from until until) {
                var score = 0f
                for (x in bandLeft until bandLeft + bandW) score += grad[y * w + x]
                if (score > bestScore) { bestScore = score; bestRow = y }
            }
            return if (bestScore > cut * bandW * 0.55f) bestRow else null
        }

        val left = bestColumn((w * 0.02f).toInt(), (w * 0.42f).toInt())
        val right = bestColumn(w - 1 - (w * 0.02f).toInt(), w - 1 - (w * 0.42f).toInt())
        val top = bestRow((h * 0.02f).toInt(), (h * 0.42f).toInt())
        val bottom = bestRow(h - 1 - (h * 0.02f).toInt(), h - 1 - (h * 0.42f).toInt())

        if (left == null || right == null || top == null || bottom == null) return null
        if (right - left < w * 0.25f || bottom - top < h * 0.25f) return null

        return listOf(
            PointF(left.toFloat(), top.toFloat()),
            PointF(right.toFloat(), top.toFloat()),
            PointF(right.toFloat(), bottom.toFloat()),
            PointF(left.toFloat(), bottom.toFloat())
        )
    }

    // ---------------------------------------------------------------- utilidades

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_DIM) return bitmap
        val ratio = longest.toFloat() / MAX_DIM
        val w = (bitmap.width / ratio).toInt().coerceAtLeast(32)
        val h = (bitmap.height / ratio).toInt().coerceAtLeast(32)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun luminance(pixels: IntArray): FloatArray {
        val lum = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return lum
    }

    /** Separa el cuadrilátero 1-2 px hacia afuera: el borde de la máscara queda dentro del papel. */
    private fun expandOutward(quad: List<PointF>, w: Int, h: Int, px: Float): List<PointF> {
        val cx = quad.sumOf { it.x.toDouble() }.toFloat() / quad.size
        val cy = quad.sumOf { it.y.toDouble() }.toFloat() / quad.size
        return quad.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            PointF(
                (p.x + dx / len * px).coerceIn(0f, (w - 1).toFloat()),
                (p.y + dy / len * px).coerceIn(0f, (h - 1).toFloat())
            )
        }
    }

    private fun quadArea(quad: List<PointF>): Float {
        var area = 0.0
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % quad.size]
            area += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(area).toFloat() / 2f
    }

    /** Descarta cuadriláteros degenerados (auto-intersectados o con lados casi nulos). */
    private fun isSaneQuad(quad: List<PointF>): Boolean {
        if (quad.size != 4) return false
        var sign = 0f
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-3f) return false
            val s = if (cross > 0) 1f else -1f
            if (sign == 0f) sign = s else if (sign != s) return false
        }
        return true
    }

    private fun toNormalized(quad: List<PointF>, w: Int, h: Int): QuadCorners {
        val fw = (w - 1).toFloat().coerceAtLeast(1f)
        val fh = (h - 1).toFloat().coerceAtLeast(1f)
        return QuadCorners(
            topLeft = PointF((quad[0].x / fw).coerceIn(0f, 1f), (quad[0].y / fh).coerceIn(0f, 1f)),
            topRight = PointF((quad[1].x / fw).coerceIn(0f, 1f), (quad[1].y / fh).coerceIn(0f, 1f)),
            bottomRight = PointF((quad[2].x / fw).coerceIn(0f, 1f), (quad[2].y / fh).coerceIn(0f, 1f)),
            bottomLeft = PointF((quad[3].x / fw).coerceIn(0f, 1f), (quad[3].y / fh).coerceIn(0f, 1f))
        )
    }

    private fun insetCorners(inset: Float): QuadCorners = QuadCorners(
        topLeft = PointF(inset, inset),
        topRight = PointF(1f - inset, inset),
        bottomRight = PointF(1f - inset, 1f - inset),
        bottomLeft = PointF(inset, 1f - inset)
    )
}
