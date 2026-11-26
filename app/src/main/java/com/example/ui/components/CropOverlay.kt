package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.imaging.QuadCorners
import kotlin.math.hypot

/** Rectángulo donde se dibuja la imagen dentro del canvas (ajuste por aspecto). */
private data class DrawRect(val w: Float, val h: Float, val x: Float, val y: Float) {
    fun toScreen(p: PointF) = Offset(x + p.x * w, y + p.y * h)
}

/**
 * Rectángulo donde se dibuja la imagen dentro del canvas (ajuste por aspecto).
 *
 * [inset] deja un margen alrededor: sin él la imagen toca los bordes del área y una esquina en la
 * posición 0 o 1 queda pegada al borde de la pantalla (o debajo del panel de herramientas), donde
 * el dedo no llega. Con 18 dp de margen las cuatro esquinas siempre son alcanzables.
 */
private fun fitRect(bitmap: Bitmap, canvas: Size, inset: Float = 0f): DrawRect {
    if (canvas.width <= 0f || canvas.height <= 0f) return DrawRect(0f, 0f, 0f, 0f)
    val disponibleW = (canvas.width - inset * 2f).coerceAtLeast(1f)
    val disponibleH = (canvas.height - inset * 2f).coerceAtLeast(1f)
    val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val canvasAspect = disponibleW / disponibleH
    return if (bmpAspect > canvasAspect) {
        val h = disponibleW / bmpAspect
        DrawRect(disponibleW, h, inset, inset + (disponibleH - h) / 2f)
    } else {
        val w = disponibleH * bmpAspect
        DrawRect(w, disponibleH, inset + (disponibleW - w) / 2f, inset)
    }
}

/** Separación mínima (en fracción de la imagen) entre una esquina y sus vecinas. */
private const val MIN_GAP = 0.015f

@Composable
fun InteractiveCropCanvas(
    bitmap: Bitmap,
    corners: QuadCorners,
    onCornersChanged: (QuadCorners) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeCorner by remember { mutableStateOf<Int?>(null) } // 0: TL, 1: TR, 2: BR, 3: BL

    // Punto donde se apoyó el dedo y posición de la esquina en ese instante.
    // El arrastre se mide contra este origen fijo: el punto sigue al dedo 1:1 y no depende
    // de que la recomposición llegue entre evento y evento. Antes se sumaba el delta al
    // último valor de estado, se perdían deltas y el punto se resistía a acercarse.
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var cornerOrigin by remember { mutableStateOf(PointF(0f, 0f)) }

    // El gesto NO debe reiniciarse cuando cambian las esquinas: se lee el valor actual
    // con rememberUpdatedState en vez de usar `corners` como key del pointerInput.
    val currentCorners by rememberUpdatedState(corners)
    val currentBitmap by rememberUpdatedState(bitmap)

    fun pointOf(c: QuadCorners, i: Int): PointF = when (i) {
        0 -> c.topLeft
        1 -> c.topRight
        2 -> c.bottomRight
        else -> c.bottomLeft
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // El tamaño se toma del propio nodo (size del PointerInputScope) y no de un
                        // estado aparte: si el panel cambia de alto, el estado puede ir un frame por
                        // detrás y el toque caía donde la imagen ya no estaba. Eso se notaba sobre
                        // todo abajo, donde el desfase se acumula.
                        val area = Size(size.width.toFloat(), size.height.toFloat())
                        if (area.width <= 0f || area.height <= 0f) return@detectDragGestures
                        val rect = fitRect(currentBitmap, area, 18.dp.toPx())
                        val c = currentCorners
                        val touchRadius = 72.dp.toPx()

                        // Antes se probaba en orden TL, TR, BR, BL y ganaba la primera que estuviera
                        // dentro del radio: con el marco estrecho, tocar abajo agarraba la de arriba
                        // y la lupa enseñaba otra zona. Ahora gana la más cercana al dedo.
                        val candidatos = listOf(
                            0 to c.topLeft,
                            1 to c.topRight,
                            2 to c.bottomRight,
                            3 to c.bottomLeft
                        ).map { (i, p) ->
                            val s = rect.toScreen(p)
                            i to hypot(offset.x - s.x, offset.y - s.y)
                        }
                        val (elegida, distancia) = candidatos.minBy { it.second }
                        activeCorner = if (distancia < touchRadius) elegida else null
                        if (activeCorner != null) {
                            dragOrigin = offset
                            cornerOrigin = pointOf(c, elegida)
                        }
                    },
                    onDrag = { change, _ ->
                        val corner = activeCorner ?: return@detectDragGestures
                        change.consume()

                        val rect = fitRect(
                            currentBitmap,
                            Size(size.width.toFloat(), size.height.toFloat()),
                            18.dp.toPx()
                        )
                        if (rect.w <= 0f || rect.h <= 0f) return@detectDragGestures

                        // Desplazamiento TOTAL del dedo desde que apoyó (no el delta del evento):
                        // así el punto acompaña al dedo sin acumular errores.
                        val nx = (cornerOrigin.x + (change.position.x - dragOrigin.x) / rect.w).coerceIn(0f, 1f)
                        val ny = (cornerOrigin.y + (change.position.y - dragOrigin.y) / rect.h).coerceIn(0f, 1f)
                        val c = currentCorners

                        val updated = when (corner) {
                            0 -> c.copy(
                                topLeft = PointF(
                                    nx.coerceAtMost(minOf(c.topRight.x, c.bottomRight.x) - MIN_GAP).coerceAtLeast(0f),
                                    ny.coerceAtMost(minOf(c.bottomLeft.y, c.bottomRight.y) - MIN_GAP).coerceAtLeast(0f)
                                )
                            )
                            1 -> c.copy(
                                topRight = PointF(
                                    nx.coerceAtLeast(maxOf(c.topLeft.x, c.bottomLeft.x) + MIN_GAP).coerceAtMost(1f),
                                    ny.coerceAtMost(minOf(c.bottomRight.y, c.bottomLeft.y) - MIN_GAP).coerceAtLeast(0f)
                                )
                            )
                            2 -> c.copy(
                                bottomRight = PointF(
                                    nx.coerceAtLeast(maxOf(c.bottomLeft.x, c.topLeft.x) + MIN_GAP).coerceAtMost(1f),
                                    ny.coerceAtLeast(maxOf(c.topRight.y, c.topLeft.y) + MIN_GAP).coerceAtMost(1f)
                                )
                            )
                            else -> c.copy(
                                bottomLeft = PointF(
                                    nx.coerceAtMost(minOf(c.bottomRight.x, c.topRight.x) - MIN_GAP).coerceAtLeast(0f),
                                    ny.coerceAtLeast(maxOf(c.topLeft.y, c.topRight.y) + MIN_GAP).coerceAtMost(1f)
                                )
                            )
                        }
                        onCornersChanged(updated)
                    },
                    onDragEnd = { activeCorner = null },
                    onDragCancel = { activeCorner = null }
                )
            }
    ) {
        val composeBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height
            val rect = fitRect(bitmap, size, 18.dp.toPx())

            // 1. Imagen
            drawImage(
                image = composeBitmap,
                dstOffset = IntOffset(rect.x.toInt(), rect.y.toInt()),
                dstSize = IntSize(rect.w.toInt(), rect.h.toInt())
            )

            val pTL = rect.toScreen(corners.topLeft)
            val pTR = rect.toScreen(corners.topRight)
            val pBR = rect.toScreen(corners.bottomRight)
            val pBL = rect.toScreen(corners.bottomLeft)

            // 2. Oscurecer fuera del cuadrilátero
            val quadPath = Path().apply {
                moveTo(pTL.x, pTL.y)
                lineTo(pTR.x, pTR.y)
                lineTo(pBR.x, pBR.y)
                lineTo(pBL.x, pBL.y)
                close()
            }
            val dimPath = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, canvasW, canvasH))
                addPath(quadPath)
            }
            drawPath(path = dimPath, color = Color(0x99000000))

            // 3. Bordes
            val borderWidth = 3.dp.toPx()
            val accentTurquoise = Color(0xFF00B4D8)
            val petrolBlue = Color(0xFF0C4A60)

            drawLine(color = accentTurquoise, start = pTL, end = pTR, strokeWidth = borderWidth, cap = StrokeCap.Round)
            drawLine(color = accentTurquoise, start = pTR, end = pBR, strokeWidth = borderWidth, cap = StrokeCap.Round)
            drawLine(color = accentTurquoise, start = pBR, end = pBL, strokeWidth = borderWidth, cap = StrokeCap.Round)
            drawLine(color = accentTurquoise, start = pBL, end = pTL, strokeWidth = borderWidth, cap = StrokeCap.Round)

            // 4. Asas
            val handleRadius = 14.dp.toPx()
            val activeRadius = 18.dp.toPx()
            val cornerPoints = listOf(pTL, pTR, pBR, pBL)
            for ((idx, pt) in cornerPoints.withIndex()) {
                val isActive = activeCorner == idx
                val r = if (isActive) activeRadius else handleRadius

                drawCircle(color = Color.White.copy(alpha = 0.5f), radius = r + 4.dp.toPx(), center = pt)
                drawCircle(color = petrolBlue, radius = r, center = pt)
                drawCircle(
                    color = if (isActive) Color(0xFF64DFDF) else accentTurquoise,
                    radius = r - 3.dp.toPx(),
                    center = pt
                )
                drawCircle(color = Color.White, radius = 4.dp.toPx(), center = pt)
            }

            // 5. Lupa: sigue al punto y se coloca por ENCIMA del dedo (la mano viene de abajo),
            //    pegándose a los bordes cuando no cabe. Antes iba a una esquina fija de la
            //    pantalla y la mano tapaba la lupa.
            val cornerIdx = activeCorner
            if (cornerIdx != null) {
                val pt = cornerPoints[cornerIdx]
                val radius = 58.dp.toPx()
                val gapDedo = 46.dp.toPx()
                val borde = 10.dp.toPx()

                var cx = pt.x
                var cy = pt.y - radius - gapDedo          // por encima del dedo
                if (cy - radius < borde) {
                    // No cabe arriba: se va a un costado, nunca debajo (ahí está la mano).
                    cy = pt.y
                    cx = if (pt.x > canvasW / 2f) pt.x - radius - gapDedo else pt.x + radius + gapDedo
                }
                cx = cx.coerceIn(radius + borde, (canvasW - radius - borde).coerceAtLeast(radius + borde))
                cy = cy.coerceIn(radius + borde, (canvasH - radius - borde).coerceAtLeast(radius + borde))

                // Zoom fijo de 3x. Antes el recorte era el 14 % del ancho del bitmap, una cifra que
                // no tiene relacion con lo que se ve en pantalla: con imagenes grandes la "lupa"
                // abarcaba una zona enorme y el punto quedaba perdido dentro de ella. Ahora el lado
                // del recorte sale del radio en pantalla y de la escala real de la imagen.
                val zoom = 3f
                val escala = if (rect.w > 0f) rect.w / bitmap.width else 1f
                val maxSide = minOf(bitmap.width, bitmap.height).toFloat()
                val side = ((radius * 2f) / zoom / escala).coerceIn(16f, maxSide)
                val srcX = (pt.x - rect.x) / rect.w * bitmap.width
                val srcY = (pt.y - rect.y) / rect.h * bitmap.height
                val sx = (srcX - side / 2f).coerceIn(0f, (bitmap.width - side).coerceAtLeast(0f))
                val sy = (srcY - side / 2f).coerceIn(0f, (bitmap.height - side).coerceAtLeast(0f))

                val lens = Offset(cx, cy)
                drawLine(color = Color(0x66FFFFFF), start = pt, end = lens, strokeWidth = 1.dp.toPx())

                val circle = Path().apply {
                    addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
                }
                clipPath(circle) {
                    drawImage(
                        image = composeBitmap,
                        srcOffset = IntOffset(sx.toInt(), sy.toInt()),
                        srcSize = IntSize(side.toInt(), side.toInt()),
                        dstOffset = IntOffset((cx - radius).toInt(), (cy - radius).toInt()),
                        dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                        filterQuality = FilterQuality.High
                    )
                }
                drawCircle(color = Color.White, radius = radius, center = lens, style = Stroke(width = 3.dp.toPx()))
                drawCircle(color = Color(0xFF00B4D8), radius = radius + 2.5.dp.toPx(), center = lens, style = Stroke(width = 2.dp.toPx()))
                // punto de mira
                drawLine(Color.White, Offset(cx - 12.dp.toPx(), cy), Offset(cx + 12.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, Offset(cx, cy - 12.dp.toPx()), Offset(cx, cy + 12.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                drawCircle(color = Color(0xFF00B4D8), radius = 2.5.dp.toPx(), center = lens)
            }
        }
    }
}
