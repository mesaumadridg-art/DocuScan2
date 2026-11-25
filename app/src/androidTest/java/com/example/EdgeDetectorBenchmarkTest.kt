package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imaging.EdgeDetector
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mide el costo real de EdgeDetector.detectPaperEdges en el dispositivo.
 * No valida nada: imprime tiempos para decidir si sirve en vivo (overlay a 5-10 fps).
 */
@RunWith(AndroidJUnit4::class)
class EdgeDetectorBenchmarkTest {

    private fun hojaPerspectiva(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(92, 86, 78))          // mesa
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.11f)             // hoja inclinada
            lineTo(w * 0.86f, h * 0.19f)
            lineTo(w * 0.79f, h * 0.88f)
            lineTo(w * 0.10f, h * 0.79f)
            close()
        }
        c.drawPath(path, p)
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            textSize = h * 0.03f
        }
        for (i in 1..8) {
            c.drawText("Linea de texto $i del documento", w * 0.2f, h * (0.2f + i * 0.07f), texto)
        }
        return bmp
    }

    @Test
    fun medirDeteccion() {
        val casos = listOf(1600 to 1200, 2200 to 1650)
        repeat(1) {
            for ((w, h) in casos) {
                val bmp = hojaPerspectiva(w, h)
                // calentamiento: una pasada que no se mide (JIT, carga de clases)
                runBlocking { EdgeDetector.detectPaperEdges(bmp) }

                val tiempos = mutableListOf<Long>()
                repeat(5) {
                    val t0 = System.nanoTime()
                    val r = runBlocking { EdgeDetector.detectPaperEdges(bmp) }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    tiempos += ms
                    Log.d("EDGEPERF", "entrada=${w}x$h  ms=$ms  detectado=${r.isDocumentDetected}")
                }
                val ordenados = tiempos.sorted()
                Log.d(
                    "EDGEPERF",
                    "RESUMEN ${w}x$h  min=${ordenados.first()}  mediana=${ordenados[ordenados.size / 2]}  max=${ordenados.last()}"
                )
            }
        }
    }
}
