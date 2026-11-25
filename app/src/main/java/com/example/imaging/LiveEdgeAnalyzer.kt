package com.example.imaging

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * Analiza cuadros de la cámara para dibujar el marco del documento en vivo y,
 * cuando el cuadrilátero se mantiene quieto, avisar para disparar solo.
 *
 * Corre a ~5 fps con detección a 480 px: el detector cuesta ~90 ms a 720 px, así que
 * 30 fps es imposible y tampoco hace falta — para saber si el cuadro está firme
 * alcanza con leerlo cuatro veces por segundo.
 */
class LiveEdgeAnalyzer(
    private val hayCapturaEnCurso: () -> Boolean,
    private val onDeteccion: (DetectionResult) -> Unit,
    private val onCuadroEstable: () -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "LiveEdge"
        private const val INTERVALO_MS = 200L        // ~5 fps
        private const val DIM_LIVE = 480              // el detector trabaja a esta escala
        private const val CUADROS_ESTABLES = 3
        private const val TOLERANCIA = 0.05f          // en coordenadas normalizadas
        private const val AREA_MIN = 0.10f
        private const val AREA_MAX = 0.95f
        private const val ENFRIAMIENTO_MS = 1500L
    }

    private val main = Handler(Looper.getMainLooper())
    private var ultimaCorrida = 0L
    private var anterior: QuadCorners? = null
    private var estables = 0
    private var ultimoDisparo = 0L

    @Volatile var activo: Boolean = true

    fun reiniciar() {
        anterior = null
        estables = 0
    }

    override fun analyze(image: ImageProxy) {
        try {
            val ahora = SystemClock.elapsedRealtime()
            if (!activo || hayCapturaEnCurso() || ahora - ultimaCorrida < INTERVALO_MS) return
            ultimaCorrida = ahora

            val bmp = image.toBitmap()
            val resultado = runBlocking { EdgeDetector.detectPaperEdges(bmp, maxDim = DIM_LIVE) }
            val area = areaNormalizada(resultado.corners)

            if (resultado.isDocumentDetected && area in AREA_MIN..AREA_MAX && parecido(resultado.corners, anterior)) {
                estables++
            } else {
                estables = 0
            }
            anterior = resultado.corners

            main.post { onDeteccion(resultado) }

            if (estables >= CUADROS_ESTABLES && ahora - ultimoDisparo > ENFRIAMIENTO_MS) {
                estables = 0
                ultimoDisparo = ahora
                main.post { onCuadroEstable() }
            }
        } catch (t: Throwable) {
            // Un cuadro raro no puede tumbar la cámara.
            Log.w(TAG, "cuadro descartado: ${t.message}")
        } finally {
            image.close()
        }
    }

    /** Área del cuadrilátero en coordenadas normalizadas (0..1). */
    private fun areaNormalizada(q: QuadCorners): Float {
        val pts = listOf(
            q.topLeft, q.topRight, q.bottomRight, q.bottomLeft, q.topLeft
        )
        var suma = 0f
        for (i in 0 until pts.size - 1) {
            suma += pts[i].x * pts[i + 1].y - pts[i + 1].x * pts[i].y
        }
        return abs(suma) / 2f
    }

    private fun parecido(actual: QuadCorners, previo: QuadCorners?): Boolean {
        previo ?: return false
        val pares = listOf(
            actual.topLeft to previo.topLeft,
            actual.topRight to previo.topRight,
            actual.bottomRight to previo.bottomRight,
            actual.bottomLeft to previo.bottomLeft
        )
        return pares.all { (a, b) -> abs(a.x - b.x) < TOLERANCIA && abs(a.y - b.y) < TOLERANCIA }
    }
}
