package com.example.imaging

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class DetectionResult(
    val corners: QuadCorners,
    val isDocumentDetected: Boolean,
    val message: String
)

/**
 * Detector de bordes de documento.
 *
 * No hay coordenadas ni umbrales fijos por caso: todo se calcula de cada foto.
 *
 *  1. La foto se lleva a 720 px y se suaviza (el ruido del sensor rompe los bordes).
 *  2. Binarización por **Otsu**, que saca el umbral del histograma de esa foto concreta;
 *     si casi todo sale claro se invierte la máscara. Un cierre 3x3 tapa huecos de texto.
 *  3. Cada mancha clara se puntúa por **rectangularidad**: área de la mancha / área de su
 *     rectángulo mínimo rotado. Una hoja real da ~0.99; una mesa, una mano o dos hojas
 *     pegadas dan mucho menos y se descartan. Ese filtro es el que faltaba.
 *  4. Si no aparece una mancha rectangular (dos papeles unidos por la sombra), se separan
 *     por **transformada de distancia**: cada objeto tiene su núcleo lejos del fondo y una
 *     partición BFS reparte la mancha entre esos núcleos.
 *  5. Las esquinas no se sacan de los extremos de x±y (eso falla en cuanto la hoja se gira):
 *     se ajusta una **recta por mínimos cuadrados a cada lado** y se cortan las cuatro.
 *  6. Validación de ángulos (55°-125°). Si algo no cuadra, devuelve la imagen completa:
 *     mejor no recortar nada que recortar mal.
 */
object EdgeDetector {

    private const val MAX_DIM = 720
    private const val MIN_AREA_RATIO = 0.06f
    private const val MIN_RECTANGULARITY = 0.72f

    /**
     * El modelo queda apagado: con seis fotos reales (hoja sobre el teclado del portátil) DocAligner
     * marcaba el **bloque de texto** en vez del papel, con confianza alta (0.92-1.00) pero dejando
     * fuera el pie y los márgenes. Un detector que se equivoca con seguridad es peor que uno que
     * admite que no ve nada. Se deja el código y el modelo en assets para cuando haya un modelo
     * entrenado con fotos de esta app.
     */
    private const val USAR_MODELO = false

    suspend fun detectPaperEdges(bitmap: Bitmap, maxDim: Int = MAX_DIM): DetectionResult =
        withContext(Dispatchers.Default) {
            if (USAR_MODELO) {
                val porRed = try {
                    DocDetector.detect(bitmap)?.also {
                        android.util.Log.i("EdgeDetector", "usa red: ${it.message}")
                    }
                } catch (e: Exception) {
                    null
                }
                if (porRed != null) return@withContext porRed
            }

            try {
                val geometrico = detect(bitmap, maxDim)
                android.util.Log.i("EdgeDetector", "usa geometrico: ${geometrico.message}")
                geometrico
            } catch (e: Exception) {
                fallback("Ajusta las esquinas del documento a mano.")
            }
        }

    private fun detect(bitmap: Bitmap, maxDim: Int = MAX_DIM): DetectionResult {
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(32, (bitmap.width * scale).toInt())
        val h = max(32, (bitmap.height * scale).toInt())
        val small = if (w == bitmap.width && h == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, w, h, true)

        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val smooth = blur3(lum, w, h)

        var mask = otsuMask(smooth)
        var bright = 0
        for (b in mask) if (b) bright++
        if (bright > mask.size * 0.92f) {
            for (i in mask.indices) mask[i] = !mask[i]
            bright = mask.size - bright
        }

        val total = w * h
        var corners: Array<PointF>? = null
        if (bright >= mask.size * 0.04f) {
            corners = solveMask(mask, w, h)
        }
        // Si el papel y el fondo tienen brillo parecido (mesa clara), la luminancia no
        // separa nada: se busca el borde y se rellena el contorno.
        if (corners == null) {
            val edge = edgeMask(smooth, w, h)
            if (edge != null) corners = solveMask(edge, w, h)
        }

        val refined = corners
            ?: return fallback(
                "No se reconoció el borde del papel. Ajusta el marco propuesto.",
                w,
                h
            )

        // Si el cuadrilátero ocupa casi todo el encuadre EN LOS DOS EJES no hay nada que recortar.
        // Ojo: exigir los dos. Una foto tomada cerca puede llenar el 95 % del ancho y sólo el
        // 70 % del alto; ahí el marco sí sirve (recorta arriba y abajo). Con un OR, la segunda
        // página parecía "no detectada" porque se descartaba un marco válido.
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for (p in refined) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val spanX = (maxX - minX) / w
        val spanY = (maxY - minY) / h
        if (spanX > 0.95f && spanY > 0.95f) {
            // Aquí el marco detectado ya es la foto entera: no hay nada mejor que proponer.
            return marcoCompleto("El documento ocupa casi todo el encuadre. Arrastra las esquinas si quieres ajustar el borde.")
        }

        val fw = w.toFloat()
        val fh = h.toFloat()
        // ORDEN VISUAL: el refinamiento devuelve las esquinas en los ejes rotados de la hoja,
        // así que refined[0] no tiene por qué ser la de arriba-izquierda en pantalla. Se
        // reordenan por posición para que topLeft/topRight/bottomRight/bottomLeft sean las
        // que ve el usuario (si no, al arrastrar una esquina se mueve otra y los límites
        // usan vecinas equivocadas: el punto se puede alejar pero no acercar).
        val ord = orderVisually(refined)
        val quad = QuadCorners(
            topLeft = PointF((ord[0].x / fw).coerceIn(0f, 1f), (ord[0].y / fh).coerceIn(0f, 1f)),
            topRight = PointF((ord[1].x / fw).coerceIn(0f, 1f), (ord[1].y / fh).coerceIn(0f, 1f)),
            bottomRight = PointF((ord[2].x / fw).coerceIn(0f, 1f), (ord[2].y / fh).coerceIn(0f, 1f)),
            bottomLeft = PointF((ord[3].x / fw).coerceIn(0f, 1f), (ord[3].y / fh).coerceIn(0f, 1f))
        )
        val pct = (quadArea(refined) / total * 100).toInt().coerceIn(1, 100)
        return DetectionResult(
            corners = quad,
            isDocumentDetected = true,
            message = "Documento detectado · $pct% del encuadre"
        )
    }

    /** Busca el cuadrilátero del papel dentro de una máscara ya preparada. */
    private fun solveMask(maskIn: BooleanArray, w: Int, h: Int): Array<PointF>? {
        val total = w * h
        val mask = close3(maskIn, w, h)
        var best: Candidate? = null
        for (comp in rankedComponents(mask, w, h, total, 4)) {
            val cand = evaluate(comp, w, h, total) ?: continue
            if (best == null || cand.score > best.score) best = cand
        }
        if (best == null || best.score < MIN_RECTANGULARITY) {
            for (cand in separateByDistance(mask, w, h, total)) {
                if (cand.score >= MIN_RECTANGULARITY && (best == null || cand.score > best.score)) {
                    best = cand
                }
            }
        }
        val found = best
        if (found == null || found.score < MIN_RECTANGULARITY) return null
        val refined = refineCorners(found, w, h) ?: return null
        return if (isValidQuad(refined, total)) refined else null
    }

    /** Proporción de una hoja carta, que es el papel con el que se trabaja por defecto. */
    private const val ASPECTO_HOJA = 0.7727f

    private fun fallback(message: String, width: Int = 1, height: Int = 1) = DetectionResult(
        corners = propuestaInicial(width, height),
        isDocumentDetected = false,
        message = message
    )

    /**
     * Marco inicial cuando no se reconoce el papel: un rectángulo al 88 % del ancho con la
     * proporción de una hoja, centrado.
     *
     * Antes, cuando el detector no encontraba nada, se devolvía la imagen completa y el usuario
     * tenía que **dibujar** las cuatro esquinas desde cero. Con un marco ya puesto solo tiene que
     * corregirlo, que es mucho menos trabajo y menos frustrante.
     */
    /** Marco propuesto al 88 % con proporción de hoja, expuesto para que la pantalla pueda ofrecerlo. */
    fun marcoPropuesto(width: Int, height: Int): QuadCorners = propuestaInicial(width, height)

    private fun propuestaInicial(width: Int, height: Int): QuadCorners {
        if (width <= 0 || height <= 0) {
            return QuadCorners(
                topLeft = PointF(0f, 0f),
                topRight = PointF(1f, 0f),
                bottomRight = PointF(1f, 1f),
                bottomLeft = PointF(0f, 1f)
            )
        }

        val vertical = height >= width
        // En coordenadas normalizadas el ancho y el alto no miden lo mismo: hay que convertir la
        // proporción del papel a fracciones de esta imagen concreta.
        val ancho = 0.88f
        var alto = ancho * width / (ASPECTO_HOJA * height)
        if (vertical && alto > 0.92f) {
            alto = 0.92f
            val anchoAjustado = alto * ASPECTO_HOJA * height / width
            return centrado(anchoAjustado.coerceIn(0.2f, 0.98f), alto)
        }
        if (!vertical) {
            // Foto apaisada: el marco se limita a la altura disponible.
            alto = 0.88f * width / (ASPECTO_HOJA * height)
            if (alto > 0.95f) alto = 0.95f
        }
        return centrado(ancho.coerceIn(0.2f, 0.98f), alto.coerceIn(0.2f, 0.98f))
    }

    /** Marco completo, sin recortar: para cuando no hay nada mejor que ofrecer. */
    private fun marcoCompleto(message: String) = DetectionResult(
        corners = QuadCorners(
            topLeft = PointF(0f, 0f),
            topRight = PointF(1f, 0f),
            bottomRight = PointF(1f, 1f),
            bottomLeft = PointF(0f, 1f)
        ),
        isDocumentDetected = false,
        message = message
    )

    private fun centrado(ancho: Float, alto: Float): QuadCorners {
        val x0 = (1f - ancho) / 2f
        val y0 = (1f - alto) / 2f
        return QuadCorners(
            topLeft = PointF(x0, y0),
            topRight = PointF(x0 + ancho, y0),
            bottomRight = PointF(x0 + ancho, y0 + alto),
            bottomLeft = PointF(x0, y0 + alto)
        )
    }

    // -------------------------------------------------------------------- imagen

    private fun blur3(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = if (x > 0) x - 1 else 0
                val x1 = if (x < w - 1) x + 1 else w - 1
                tmp[row + x] = (src[row + x0] + src[row + x] + src[row + x1]) / 3f
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val y0 = if (y > 0) y - 1 else 0
            val y1 = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                out[y * w + x] = (tmp[y0 * w + x] + tmp[y * w + x] + tmp[y1 * w + x]) / 3f
            }
        }
        return out
    }

    private fun otsuMask(lum: FloatArray): BooleanArray {
        val thr = otsuThreshold(lum)
        return BooleanArray(lum.size) { lum[it] >= thr }
    }

    /** Umbral de Otsu sobre cualquier señal (luminancia o magnitud de borde). */
    private fun otsuThreshold(lum: FloatArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.toInt().coerceIn(0, 255)]++
        val total = lum.size
        var sumAll = 0.0
        for (t in 0..255) sumAll += t.toDouble() * hist[t]
        var sumB = 0.0
        var wB = 0
        var best = -1.0
        var thr = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thr = t
            }
        }
        return thr
    }

    /** Cierre 3x3 (dilatar y erosionar) sin perder el borde de la mancha. */
    private fun close3(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val dil = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var on = false
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    if (mask[ny * w + nx]) {
                        on = true
                        break
                    }
                }
                if (on) break
            }
            dil[y * w + x] = on
        }
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var on = true
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    if (!dil[ny * w + nx]) {
                        on = false
                        break
                    }
                }
                if (!on) break
            }
            out[y * w + x] = on || mask[y * w + x]
        }
        return out
    }

    /** Máscara por bordes (DoG) con relleno: papel y fondo con brillo parecido. */
    private fun edgeMask(src: FloatArray, w: Int, h: Int): BooleanArray? {
        val a = gaussian(src, w, h, 1.2f)
        val b = gaussian(src, w, h, 6f)
        val dog = FloatArray(src.size) { abs(a[it] - b[it]) }
        var dmax = 1e-3f
        for (v in dog) if (v > dmax) dmax = v
        val scaled = FloatArray(dog.size) { dog[it] / dmax * 255f }
        val thr = otsuThreshold(scaled)

        var edges = BooleanArray(scaled.size) { scaled[it] >= thr }
        repeat(2) { edges = dilate3(edges, w, h) }
        edges = closeSize5(edges, w, h)
        val filled = fillHoles(edges, w, h)
        var interior = 0
        val mask = BooleanArray(edges.size)
        for (i in edges.indices) {
            if (filled[i] && !edges[i]) {
                mask[i] = true
                interior++
            }
        }
        if (interior < mask.size * 0.05f) return null
        return closeSize5(mask, w, h)
    }

    /** Gaussiano separable (radio 3σ): suaviza sin destruir los bordes del papel. */
    private fun gaussian(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val radius = max(1, (3f * sigma).toInt())
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val v = kotlin.math.exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    acc += src[row + xx] * kernel[k + radius]
                }
                tmp[row + x] = acc
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    acc += tmp[yy * w + x] * kernel[k + radius]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    private fun dilate3(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var on = false
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    if (mask[ny * w + nx]) {
                        on = true
                        break
                    }
                }
                if (on) break
            }
            out[y * w + x] = on
        }
        return out
    }

    /** Cierre con vecindad 5x5 (dilatar 2 y erosionar 2). */
    private fun closeSize5(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        var m = dilate3(dilate3(mask, w, h), w, h)
        m = erode3(erode3(m, w, h), w, h)
        for (i in mask.indices) if (mask[i]) m[i] = true
        return m
    }

    private fun erode3(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var on = true
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    if (!mask[ny * w + nx]) {
                        on = false
                        break
                    }
                }
                if (!on) break
            }
            out[y * w + x] = on
        }
        return out
    }

    /** Rellena los huecos cerrados: lo alcanzable desde el borde de la imagen no es hueco. */
    private fun fillHoles(edges: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(edges.size)
        val queue = IntArray(edges.size)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!edges[i] && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            if (x > 0) seed(idx - 1)
            if (x < w - 1) seed(idx + 1)
            if (idx - w >= 0) seed(idx - w)
            if (idx + w < edges.size) seed(idx + w)
        }
        return BooleanArray(edges.size) { !outside[it] }
    }

    // ---------------------------------------------------------------- componentes

    private data class Candidate(
        val comp: BooleanArray,
        val angle: Float,
        val score: Float,
        val areaRatio: Float
    )

    private fun rankedComponents(mask: BooleanArray, w: Int, h: Int, total: Int, limit: Int): List<BooleanArray> {
        val labels = IntArray(mask.size) { -1 }
        val sizes = ArrayList<Int>()
        val queue = IntArray(mask.size)
        var nextLabel = 0
        for (start in mask.indices) {
            if (!mask[start] || labels[start] != -1) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = nextLabel
            var count = 0
            while (head < tail) {
                val idx = queue[head++]
                count++
                val x = idx % w
                var t = tail
                if (x > 0) t = push(idx - 1, mask, labels, nextLabel, queue, t)
                if (x < w - 1) t = push(idx + 1, mask, labels, nextLabel, queue, t)
                if (idx - w >= 0) t = push(idx - w, mask, labels, nextLabel, queue, t)
                if (idx + w < mask.size) t = push(idx + w, mask, labels, nextLabel, queue, t)
                tail = t
            }
            sizes.add(count)
            nextLabel++
        }

        val ranked = sizes.indices
            .filter { sizes[it] >= total * MIN_AREA_RATIO && sizes[it] <= total * 0.995f }
            .sortedByDescending { sizes[it] }
            .take(limit)

        return ranked.map { lab -> BooleanArray(mask.size) { labels[it] == lab } }
    }

    private fun push(idx: Int, mask: BooleanArray, labels: IntArray, lab: Int, queue: IntArray, tail: Int): Int {
        if (mask[idx] && labels[idx] == -1) {
            labels[idx] = lab
            queue[tail] = idx
            return tail + 1
        }
        return tail
    }

    private fun countTrue(a: BooleanArray): Int {
        var c = 0
        for (v in a) if (v) c++
        return c
    }

    private fun evaluate(comp: BooleanArray, w: Int, h: Int, total: Int): Candidate? {
        val area = countTrue(comp).toFloat()
        if (area < total * MIN_AREA_RATIO) return null
        val outline = outlineOf(comp, w, h)
        if (outline.size < 30) return null
        val hull = convexHull(outline) ?: return null
        if (hull.size < 4) return null
        val rect = minAreaRect(hull) ?: return null
        if (rect.area <= 0f) return null
        val score = area / rect.area
        return Candidate(comp, rect.angle, score, area / total)
    }

    private fun outlineOf(comp: BooleanArray, w: Int, h: Int): List<PointF> {
        val out = ArrayList<PointF>(2048)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (!comp[i]) continue
                if (!comp[i - 1] || !comp[i + 1] || !comp[i - w] || !comp[i + w]) {
                    out.add(PointF(x.toFloat(), y.toFloat()))
                }
            }
        }
        return out
    }

    // ------------------------------------------------------ separación por distancia

    private fun separateByDistance(mask: BooleanArray, w: Int, h: Int, total: Int): List<Candidate> {
        val dist = distanceTransform(mask, w, h)
        var dmax = 0f
        for (v in dist) if (v > dmax) dmax = v
        if (dmax < 3f) return emptyList()

        val results = ArrayList<Candidate>()
        for (frac in floatArrayOf(0.30f, 0.20f, 0.12f)) {
            val limit = max(2.5f, dmax * frac)
            val seeds = IntArray(mask.size)
            var count = 0
            for (i in mask.indices) {
                if (mask[i] && dist[i] >= limit) {
                    count++
                    seeds[i] = count
                }
            }
            if (count == 0 || count > 40) continue

            val owner = partitionByBfs(mask, seeds, w, h)
            val sizes = IntArray(count + 1)
            for (v in owner) if (v > 0) sizes[v]++

            for (lab in 1..count) {
                if (sizes[lab] < total * MIN_AREA_RATIO) continue
                val comp = BooleanArray(mask.size) { owner[it] == lab }
                val cand = evaluate(comp, w, h, total) ?: continue
                results.add(cand)
            }
        }
        return results
    }

    /** Chamfer 3x3: distancia de cada píxel de la mancha al fondo. */
    private fun distanceTransform(mask: BooleanArray, w: Int, h: Int): FloatArray {
        val big = 1e6f
        val d = FloatArray(mask.size) { if (mask[it]) big else 0f }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                var m = d[i]
                if (x > 0) m = min(m, d[i - 1] + 1f)
                if (y > 0) {
                    m = min(m, d[i - w] + 1f)
                    if (x > 0) m = min(m, d[i - w - 1] + 1.414f)
                    if (x < w - 1) m = min(m, d[i - w + 1] + 1.414f)
                }
                d[i] = m
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (!mask[i]) continue
                var m = d[i]
                if (x < w - 1) m = min(m, d[i + 1] + 1f)
                if (y < h - 1) {
                    m = min(m, d[i + w] + 1f)
                    if (x < w - 1) m = min(m, d[i + w + 1] + 1.414f)
                    if (x > 0) m = min(m, d[i + w - 1] + 1.414f)
                }
                d[i] = m
            }
        }
        return d
    }

    /** BFS multi-fuente: cada píxel de la mancha se queda con el núcleo que llega primero. */
    private fun partitionByBfs(mask: BooleanArray, seeds: IntArray, w: Int, h: Int): IntArray {
        val owner = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var head = 0
        var tail = 0
        for (i in seeds.indices) {
            if (seeds[i] > 0) {
                owner[i] = seeds[i]
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val idx = queue[head++]
            val lab = owner[idx]
            val x = idx % w
            if (x > 0 && mask[idx - 1] && owner[idx - 1] == 0) {
                owner[idx - 1] = lab; queue[tail++] = idx - 1
            }
            if (x < w - 1 && mask[idx + 1] && owner[idx + 1] == 0) {
                owner[idx + 1] = lab; queue[tail++] = idx + 1
            }
            if (idx - w >= 0 && mask[idx - w] && owner[idx - w] == 0) {
                owner[idx - w] = lab; queue[tail++] = idx - w
            }
            if (idx + w < mask.size && mask[idx + w] && owner[idx + w] == 0) {
                owner[idx + w] = lab; queue[tail++] = idx + w
            }
        }
        return owner
    }

    // ------------------------------------------------------------------- geometría

    private fun convexHull(points: List<PointF>): List<PointF>? {
        if (points.size < 3) return null
        val pts = points.sortedWith(compareBy({ it.x }, { it.y }))

        fun cross(o: PointF, a: PointF, b: PointF) =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val lower = ArrayList<PointF>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = ArrayList<PointF>()
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        if (lower.size > 1) lower.removeAt(lower.size - 1)
        if (upper.size > 1) upper.removeAt(upper.size - 1)
        val hull = lower + upper
        return if (hull.size >= 3) hull else null
    }

    private data class Rect(val angle: Float, val area: Float)

    private fun minAreaRect(hull: List<PointF>): Rect? {
        if (hull.size < 3) return null
        var bestArea = Float.MAX_VALUE
        var bestAngle = 0f
        var found = false
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            if (abs(a.x - b.x) < 1e-4f && abs(a.y - b.y) < 1e-4f) continue
            val ang = atan2(b.y - a.y, b.x - a.x)
            val ca = cos(-ang)
            val sa = sin(-ang)
            var umin = Float.MAX_VALUE
            var umax = -Float.MAX_VALUE
            var vmin = Float.MAX_VALUE
            var vmax = -Float.MAX_VALUE
            for (p in hull) {
                val u = p.x * ca - p.y * sa
                val v = p.x * sa + p.y * ca
                if (u < umin) umin = u
                if (u > umax) umax = u
                if (v < vmin) vmin = v
                if (v > vmax) vmax = v
            }
            val area = (umax - umin) * (vmax - vmin)
            if (area < bestArea) {
                bestArea = area
                bestAngle = ang
                found = true
            }
        }
        return if (found) Rect(bestAngle, bestArea) else null
    }

    private fun fitLine(points: List<PointF>): Pair<PointF, PointF>? {
        if (points.size < 8) return null
        var mx = 0f
        var my = 0f
        for (p in points) {
            mx += p.x
            my += p.y
        }
        mx /= points.size
        my /= points.size
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (p in points) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val theta = 0.5f * atan2(2f * sxy, sxx - syy)
        return PointF(mx, my) to PointF(cos(theta), sin(theta))
    }

    private fun intersect(c1: PointF, d1: PointF, c2: PointF, d2: PointF): PointF? {
        val den = d1.x * d2.y - d1.y * d2.x
        if (abs(den) < 1e-6f) return null
        val t = ((c2.x - c1.x) * d2.y - (c2.y - c1.y) * d2.x) / den
        return PointF(c1.x + d1.x * t, c1.y + d1.y * t)
    }

    /** Una recta por lado (mínimos cuadrados) y corte de las cuatro. */
    private fun refineCorners(cand: Candidate, w: Int, h: Int): Array<PointF>? {
        val outline = outlineOf(cand.comp, w, h)
        if (outline.size < 40) return null

        val ca = cos(-cand.angle)
        val sa = sin(-cand.angle)
        var umin = Float.MAX_VALUE
        var umax = -Float.MAX_VALUE
        var vmin = Float.MAX_VALUE
        var vmax = -Float.MAX_VALUE
        val us = FloatArray(outline.size)
        val vs = FloatArray(outline.size)
        for (i in outline.indices) {
            val p = outline[i]
            val u = p.x * ca - p.y * sa
            val v = p.x * sa + p.y * ca
            us[i] = u
            vs[i] = v
            if (u < umin) umin = u
            if (u > umax) umax = u
            if (v < vmin) vmin = v
            if (v > vmax) vmax = v
        }
        val uw = umax - umin
        val vh = vmax - vmin
        if (uw < 20f || vh < 20f) return null

        val bandU = uw * 0.06f
        val bandV = vh * 0.06f
        val top = ArrayList<PointF>()
        val bottom = ArrayList<PointF>()
        val left = ArrayList<PointF>()
        val right = ArrayList<PointF>()
        for (i in outline.indices) {
            val u = us[i]
            val v = vs[i]
            if (u > umin + uw * 0.12f && u < umax - uw * 0.12f) {
                if (v - vmin < bandV) top.add(outline[i])
                if (vmax - v < bandV) bottom.add(outline[i])
            }
            if (v > vmin + vh * 0.12f && v < vmax - vh * 0.12f) {
                if (u - umin < bandU) left.add(outline[i])
                if (umax - u < bandU) right.add(outline[i])
            }
        }

        val lTop = fitLine(top) ?: return null
        val lBottom = fitLine(bottom) ?: return null
        val lLeft = fitLine(left) ?: return null
        val lRight = fitLine(right) ?: return null

        val tl = intersect(lTop.first, lTop.second, lLeft.first, lLeft.second) ?: return null
        val tr = intersect(lTop.first, lTop.second, lRight.first, lRight.second) ?: return null
        val br = intersect(lBottom.first, lBottom.second, lRight.first, lRight.second) ?: return null
        val bl = intersect(lBottom.first, lBottom.second, lLeft.first, lLeft.second) ?: return null
        return arrayOf(tl, tr, br, bl)
    }

    /**
     * Reordena cuatro esquinas a topLeft, topRight, bottomRight, bottomLeft tal como se ven
     * en pantalla: TL suma mínima (x+y), BR suma máxima, TR máxima diferencia (x-y), BL mínima.
     * Es el criterio estándar para documentos y no depende de la rotación de la hoja.
     */
    private fun orderVisually(q: Array<PointF>): Array<PointF> {
        var tl = q[0]; var tr = q[0]; var br = q[0]; var bl = q[0]
        var sMin = Float.MAX_VALUE; var sMax = -Float.MAX_VALUE
        var dMax = -Float.MAX_VALUE; var dMin = Float.MAX_VALUE
        for (p in q) {
            val s = p.x + p.y
            val d = p.x - p.y
            if (s < sMin) { sMin = s; tl = p }
            if (s > sMax) { sMax = s; br = p }
            if (d > dMax) { dMax = d; tr = p }
            if (d < dMin) { dMin = d; bl = p }
        }
        return arrayOf(tl, tr, br, bl)
    }

    private fun isValidQuad(q: Array<PointF>, total: Int): Boolean {
        // Esquinas casi rectas...
        for (i in 0..3) {
            val a = q[(i + 3) % 4]
            val b = q[i]
            val c = q[(i + 1) % 4]
            val v1x = a.x - b.x
            val v1y = a.y - b.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val n1 = hypot(v1x, v1y)
            val n2 = hypot(v2x, v2y)
            if (n1 < 8f || n2 < 8f) return false
            val cosv = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
            val deg = Math.toDegrees(acos(cosv).toDouble()).toFloat()
            if (deg < 65f || deg > 115f) return false
        }
        // ...y lados opuestos casi paralelos: eso descarta manchas casuales.
        for (i in 0..1) {
            val e1x = q[(i + 1) % 4].x - q[i].x
            val e1y = q[(i + 1) % 4].y - q[i].y
            val e2x = q[(i + 3) % 4].x - q[(i + 2) % 4].x
            val e2y = q[(i + 3) % 4].y - q[(i + 2) % 4].y
            val n1 = hypot(e1x, e1y)
            val n2 = hypot(e2x, e2y)
            if (n1 < 1f || n2 < 1f) return false
            val cosang = abs((e1x * e2x + e1y * e2y) / (n1 * n2)).coerceIn(0f, 1f)
            if (Math.toDegrees(acos(cosang).toDouble()) > 18.0) return false
        }
        return quadArea(q) > total * 0.03f
    }

    private fun quadArea(q: Array<PointF>): Float {
        var s = 0f
        for (i in 0..3) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }
}
