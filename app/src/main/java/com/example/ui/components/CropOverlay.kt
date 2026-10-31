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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.imaging.QuadCorners
import kotlin.math.hypot

@Composable
fun InteractiveCropCanvas(
    bitmap: Bitmap,
    corners: QuadCorners,
    onCornersChanged: (QuadCorners) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeCorner by remember { mutableStateOf<Int?>(null) } // 0: TL, 1: TR, 2: BR, 3: BL
    var containerSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(bitmap, corners) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (containerSize.width <= 0 || containerSize.height <= 0) return@detectDragGestures

                        // Calculate image display rect within canvas (aspect fit)
                        val bmpW = bitmap.width.toFloat()
                        val bmpH = bitmap.height.toFloat()
                        val canvasW = containerSize.width
                        val canvasH = containerSize.height

                        val bmpAspect = bmpW / bmpH
                        val canvasAspect = canvasW / canvasH

                        val (drawW, drawH, offsetX, offsetY) = if (bmpAspect > canvasAspect) {
                            val w = canvasW
                            val h = canvasW / bmpAspect
                            listOf(w, h, 0f, (canvasH - h) / 2f)
                        } else {
                            val h = canvasH
                            val w = canvasH * bmpAspect
                            listOf(w, h, (canvasW - w) / 2f, 0f)
                        }

                        fun toScreen(p: PointF) = Offset(offsetX + p.x * drawW, offsetY + p.y * drawH)

                        val tlScreen = toScreen(corners.topLeft)
                        val trScreen = toScreen(corners.topRight)
                        val brScreen = toScreen(corners.bottomRight)
                        val blScreen = toScreen(corners.bottomLeft)

                        val touchRadius = 60.dp.toPx()
                        activeCorner = when {
                            hypot((offset.x - tlScreen.x), (offset.y - tlScreen.y)) < touchRadius -> 0
                            hypot((offset.x - trScreen.x), (offset.y - trScreen.y)) < touchRadius -> 1
                            hypot((offset.x - brScreen.x), (offset.y - brScreen.y)) < touchRadius -> 2
                            hypot((offset.x - blScreen.x), (offset.y - blScreen.y)) < touchRadius -> 3
                            else -> null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        val corner = activeCorner ?: return@detectDragGestures
                        change.consume()

                        val bmpW = bitmap.width.toFloat()
                        val bmpH = bitmap.height.toFloat()
                        val canvasW = containerSize.width
                        val canvasH = containerSize.height

                        val bmpAspect = bmpW / bmpH
                        val canvasAspect = canvasW / canvasH

                        val (drawW, drawH, offsetX, offsetY) = if (bmpAspect > canvasAspect) {
                            val w = canvasW
                            val h = canvasW / bmpAspect
                            listOf(w, h, 0f, (canvasH - h) / 2f)
                        } else {
                            val h = canvasH
                            val w = canvasH * bmpAspect
                            listOf(w, h, (canvasW - w) / 2f, 0f)
                        }

                        val normDeltaX = dragAmount.x / drawW
                        val normDeltaY = dragAmount.y / drawH

                        val newCorners = when (corner) {
                            0 -> corners.copy(
                                topLeft = PointF(
                                    (corners.topLeft.x + normDeltaX).coerceIn(0f, corners.topRight.x - 0.05f),
                                    (corners.topLeft.y + normDeltaY).coerceIn(0f, corners.bottomLeft.y - 0.05f)
                                )
                            )
                            1 -> corners.copy(
                                topRight = PointF(
                                    (corners.topRight.x + normDeltaX).coerceIn(corners.topLeft.x + 0.05f, 1f),
                                    (corners.topRight.y + normDeltaY).coerceIn(0f, corners.bottomRight.y - 0.05f)
                                )
                            )
                            2 -> corners.copy(
                                bottomRight = PointF(
                                    (corners.bottomRight.x + normDeltaX).coerceIn(corners.bottomLeft.x + 0.05f, 1f),
                                    (corners.bottomRight.y + normDeltaY).coerceIn(corners.topRight.y + 0.05f, 1f)
                                )
                            )
                            3 -> corners.copy(
                                bottomLeft = PointF(
                                    (corners.bottomLeft.x + normDeltaX).coerceIn(0f, corners.bottomRight.x - 0.05f),
                                    (corners.bottomLeft.y + normDeltaY).coerceIn(corners.topLeft.y + 0.05f, 1f)
                                )
                            )
                            else -> corners
                        }
                        onCornersChanged(newCorners)
                    },
                    onDragEnd = {
                        activeCorner = null
                    },
                    onDragCancel = {
                        activeCorner = null
                    }
                )
            }
    ) {
        val composeBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            containerSize = size
            val canvasW = size.width
            val canvasH = size.height

            val bmpW = bitmap.width.toFloat()
            val bmpH = bitmap.height.toFloat()
            val bmpAspect = bmpW / bmpH
            val canvasAspect = canvasW / canvasH

            val (drawW, drawH, offsetX, offsetY) = if (bmpAspect > canvasAspect) {
                val w = canvasW
                val h = canvasW / bmpAspect
                listOf(w, h, 0f, (canvasH - h) / 2f)
            } else {
                val h = canvasH
                val w = canvasH * bmpAspect
                listOf(w, h, (canvasW - w) / 2f, 0f)
            }

            // 1. Draw source image
            drawImage(
                image = composeBitmap,
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize(drawW.toInt(), drawH.toInt())
            )

            fun toScreen(p: PointF) = Offset(offsetX + p.x * drawW, offsetY + p.y * drawH)

            val pTL = toScreen(corners.topLeft)
            val pTR = toScreen(corners.topRight)
            val pBR = toScreen(corners.bottomRight)
            val pBL = toScreen(corners.bottomLeft)

            // 2. Oscurecer FUERA del cuadrilátero (antes se sombreaba dentro, que es al revés:
            //    tapaba justo el documento que el usuario quiere revisar).
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

            // 3. Draw border lines
            val borderStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val accentTurquoise = Color(0xFF00B4D8)
            val petrolBlue = Color(0xFF0C4A60)

            drawLine(color = accentTurquoise, start = pTL, end = pTR, strokeWidth = borderStroke.width)
            drawLine(color = accentTurquoise, start = pTR, end = pBR, strokeWidth = borderStroke.width)
            drawLine(color = accentTurquoise, start = pBR, end = pBL, strokeWidth = borderStroke.width)
            drawLine(color = accentTurquoise, start = pBL, end = pTL, strokeWidth = borderStroke.width)

            // 4. Draw corner handles
            val handleRadius = 14.dp.toPx()
            val activeRadius = 18.dp.toPx()

            val cornerPoints = listOf(pTL, pTR, pBR, pBL)
            for ((idx, pt) in cornerPoints.withIndex()) {
                val isActive = activeCorner == idx
                val r = if (isActive) activeRadius else handleRadius

                // Outer halo
                drawCircle(
                    color = Color.White.copy(alpha = 0.5f),
                    radius = r + 4.dp.toPx(),
                    center = pt
                )
                // Border circle
                drawCircle(
                    color = petrolBlue,
                    radius = r,
                    center = pt
                )
                // Inner accent
                drawCircle(
                    color = if (isActive) Color(0xFF64DFDF) else accentTurquoise,
                    radius = r - 3.dp.toPx(),
                    center = pt
                )
                // Center point
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = pt
                )
            }
        }
    }
}
