package com.example.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Detector de hoja por red neuronal (DocAligner, Apache-2.0).
 *
 * Devuelve las cuatro esquinas del papel en coordenadas normalizadas, o null si no está seguro.
 *
 * Por qué existe: el detector geométrico (`EdgeDetector`) necesita que el papel contraste y que sus
 * cuatro lados se vean; con sombras, fondos claros o un borde cortado se queda sin candidato. Este
 * modelo mira la imagen completa y aprende la forma del papel, así que aguanta esos casos.
 *
 * El modelo es pequeño a propósito (LCNet-050, 4,7 MB, entrada 256x256): la detección en vivo corre
 * a 5 fps y en un teléfono modestero tiene que caber en un fotograma.
 */
object DocDetector {

    private const val TAG = "DocDetector"
    private const val ASSET = "doc_detector.onnx"
    private const val INPUT = "img"
    private const val SIZE = 256

    /** Debajo de esto el modelo no está seguro y se prefiere el detector geométrico. */
    private const val MIN_CONFIDENCE = 0.55f

    /** Un cuadrilátero que ocupa menos que esto no es una hoja. */
    private const val MIN_AREA_RATIO = 0.08f

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var failed = false

    /** Carga el modelo una sola vez. Se puede llamar desde cualquier hilo. */
    fun warmUp(context: Context) {
        if (session != null || failed) return
        try {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(bytes, options)
            Log.i(TAG, "modelo cargado (${bytes.size / 1024} KB)")
        } catch (t: Throwable) {
            failed = true
            Log.w(TAG, "no se pudo cargar el modelo: ${t.message}")
        }
    }

    /**
     * Devuelve el resultado si el modelo ve un documento con confianza suficiente, o null para que
     * decida el detector geométrico.
     */
    suspend fun detect(bitmap: Bitmap): DetectionResult? = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext null
        val t0 = System.nanoTime()
        try {
            Log.i(TAG, "entrada ${bitmap.width}x${bitmap.height}")
            val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
            val input = toInputTensor(scaled)
            if (scaled !== bitmap) scaled.recycle()

            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(
                env,
                input,
                longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
            ).use { tensor ->
                s.run(mapOf(INPUT to tensor)).use { out ->
                    val points = out.get("points").orElse(null) ?: return@withContext null
                    val obj = out.get("has_obj").orElse(null) ?: return@withContext null

                    val confidence = flatten(obj.value)?.firstOrNull() ?: return@withContext null
                    if (confidence < MIN_CONFIDENCE) return@withContext null

                    val flat = flatten(points.value) ?: return@withContext null
                    val corners = toCorners(flat) ?: return@withContext null
                    if (!isPlausible(corners)) return@withContext null

                    Log.i(
                        TAG,
                        "red: confianza=%.2f area=%.0f%% en %d ms".format(
                            confidence, areaRatio(corners) * 100, (System.nanoTime() - t0) / 1_000_000
                        )
                    )
                    DetectionResult(
                        corners = corners,
                        isDocumentDetected = true,
                        message = "Documento detectado · ${(areaRatio(corners) * 100).toInt()}% del encuadre"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fallo la detección: ${t.message}")
            null
        }
    }

    /** Convierte la salida del modelo (array anidado de cualquier forma) en una lista plana. */
    private fun flatten(value: Any?): FloatArray? {
        val lista = ArrayList<Float>(8)
        fun recorrer(v: Any?) {
            when (v) {
                is FloatArray -> v.forEach { lista.add(it) }
                is Array<*> -> v.forEach { recorrer(it) }
                is Float -> lista.add(v)
            }
        }
        recorrer(value)
        return if (lista.isEmpty()) null else lista.toFloatArray()
    }

    private fun toInputTensor(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val buffer = FloatBuffer.allocate(3 * w * h)
        // Primero R, luego G y B: el modelo se entrenó con canales en ese orden.
        for (canal in 0..2) {
            val shift = when (canal) {
                0 -> 16
                1 -> 8
                else -> 0
            }
            for (p in pixels) {
                buffer.put(((p shr shift) and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    /** Los puntos vienen como [x1,y1,x2,y2,x3,y3,x4,y4] normalizados en el orden TL, TR, BR, BL. */
    private fun toCorners(flat: FloatArray): QuadCorners? {
        if (flat.size < 8) return null
        fun punto(i: Int) = PointF(
            flat[i * 2].coerceIn(0f, 1f),
            flat[i * 2 + 1].coerceIn(0f, 1f)
        )
        return QuadCorners(
            topLeft = punto(0),
            topRight = punto(1),
            bottomRight = punto(2),
            bottomLeft = punto(3)
        )
    }

    private fun areaRatio(c: QuadCorners): Float {
        // Fórmula del área de un cuadrilátero por sus diagonales.
        val x = floatArrayOf(c.topLeft.x, c.topRight.x, c.bottomRight.x, c.bottomLeft.x)
        val y = floatArrayOf(c.topLeft.y, c.topRight.y, c.bottomRight.y, c.bottomLeft.y)
        var suma = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            suma += x[i] * y[j] - x[j] * y[i]
        }
        return kotlin.math.abs(suma) / 2f
    }

    /**
     * Descarta cuadriláteros que no pueden ser una hoja: muy pequeños, con lados cruzados o con un
     * vértice pegado a otro. Es más barato rechazar aquí que arrastrar un recorte absurdo.
     */
    private fun isPlausible(c: QuadCorners): Boolean {
        if (areaRatio(c) < MIN_AREA_RATIO) return false

        val puntos = arrayOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft)
        for (i in 0 until 4) {
            val a = puntos[i]
            val b = puntos[(i + 1) % 4]
            val dx = a.x - b.x
            val dy = a.y - b.y
            if (dx * dx + dy * dy < 0.0004f) return false // lados de menos de 2% del ancho
        }

        // Sin cruces: el área de los cuatro triángulos debe sumar la del cuadrilátero.
        var suma = 0f
        for (i in 1..2) {
            suma += triangulo(puntos[0], puntos[i], puntos[i + 1])
        }
        val total = areaRatio(c)
        return suma <= total * 1.15f
    }

    private fun triangulo(a: PointF, b: PointF, c: PointF): Float =
        kotlin.math.abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2f
}
