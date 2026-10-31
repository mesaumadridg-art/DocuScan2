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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.imaging.QuadCorners
import kotlin.math.hypot

/** Rectángulo donde se dibuja la imagen dentro del canvas (ajuste por aspecto). */
private data class DrawRect(val w: Float, val h: Float, val x: Float, val y: Float) {
    fun toScreen(p: PointF) = Offset(x + p.x * w, y + p.y * h)
}

private fun fitRect(bitmap: Bitmap, canvas: Size): DrawRect {
    if (canvas.width <= 0f || canvas.height <= 0f) return DrawRect(0f, 0f, 0f, 0f)
    val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val canvasAspect = canvas.width / canvas.height
    return if (bmpAspect > canvasAspect) {
        val h = canvas.width / bmpAspect
        DrawRect(canvas.width, h, 0f, (canvas.height - h) / 2f)
    } else {
        val w = canvas.height * bmpAspect
        DrawRect(w, canvas.height, (canvas.width - w) / 2f, 0f)
    }
}

@Composable
fun InteractiveCropCanvas(
    bitmap: Bitmap,
    corners: QuadCorners,
    onCornersChanged: (QuadCorners) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeCorner by remember { mutableStateOf<Int?>(null) } // 0: TL, 1: TR, 2: BR, 3: BL
    var containerSize by remember { mutableStateOf(Size.Zero) }

    // El gesto NO debe reiniciarse cuando cambian las esquinas: se lee el valor actual
    // con rememberUpdatedState en vez de usar `corners` como key del pointerInput.
    val currentCorners by rememberUpdatedState(corners)
    val currentBitmap by rememberUpdatedState(bitmap)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (containerSize.width <= 0f || containerSize.height <= 0f) return@detectDragGestures
                        val rect = fitRect(currentBitmap, containerSize)
                        val c = currentCorners
                        val touchRadius = 64.dp.toPx()
                        fun near(p: PointF) = hypot(offset.x - rect.toScreen(p).x, offset.y - rect.toScreen(p).y) < touchRadius

                        activeCorner = when {
                            near(c.topLeft) -> 0
                            near(c.topRight) -> 1
                            near(c.bottomRight) -> 2
                            near(c.bottomLeft) -> 3
                            else -> null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        val corner = activeCorner ?: return@detectDragGestures
                        change.consume()

                        val rect = fitRect(currentBitmap, containerSize)
                        if (rect.w <= 0f || rect.h <= 0f) return@detectDragGestures
                        val dx = dragAmount.x / rect.w
                        val dy = dragAmount.y / rect.h
                        val c = currentCorners

                        // Rango siempre válido (si los puntos se cruzan, no revienta).
                        fun clampX(v: Float, lo: Float, hi: Float) = v.coerceIn(minOf(lo, hi), maxOf(lo, hi))

                        val updated = when (corner) {
                            0 -> c.copy(
                                topLeft = PointF(
                                    clampX(c.topLeft.x + dx, 0f, c.topRight.x - 0.04f),
                                    clampX(c.topLeft.y + dy, 0f, c.bottomLeft.y - 0.04f)
                                )
                            )
                            1 -> c.copy(
                                topRight = PointF(
                                    clampX(c.topRight.x + dx, c.topLeft.x + 0.04f, 1f),
                                    clampX(c.topRight.y + dy, 0f, c.bottomRight.y - 0.04f)
                                )
                            )
                            2 -> c.copy(
                                bottomRight = PointF(
                                    clampX(c.bottomRight.x + dx, c.bottomLeft.x + 0.04f, 1f),
                                    clampX(c.bottomRight.y + dy, c.topRight.y + 0.04f, 1f)
                                )
                            )
                            3 -> c.copy(
                                bottomLeft = PointF(
                                    clampX(c.bottomLeft.x + dx, 0f, c.bottomRight.x - 0.04f),
                                    clampX(c.bottomLeft.y + dy, c.topLeft.y + 0.04f, 1f)
                                )
                            )
                            else -> c
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
            val rect = fitRect(bitmap, size)

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
        }
    }
}
