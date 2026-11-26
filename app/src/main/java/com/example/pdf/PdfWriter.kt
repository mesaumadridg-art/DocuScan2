package com.example.pdf

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escritor de PDF mínimo: una página por imagen JPEG, incrustada tal cual (filtro DCTDecode).
 *
 * ¿Por qué no `android.graphics.pdf.PdfDocument`? Porque rasteriza cada página a ~320 dpi y la
 * incrusta como PNG: un escaneo de 6 páginas salía de 13 MB (2,2 MB por hoja). Aquí la imagen se
 * guarda como JPEG a la resolución y calidad que decidamos, así el archivo pesa lo que debe.
 */
object PdfWriter {

    data class JpegPage(
        val jpeg: ByteArray,
        val pxWidth: Int,
        val pxHeight: Int,
        /** `DCTDecode` para JPEG; `FlateDecode` para blanco y negro de 1 bit. */
        val filter: String = "DCTDecode",
        val colorSpace: String = "DeviceRGB",
        val bitsPerComponent: Int = 8
    )

    private const val PAGE_DPI_REF = 72f

    /**
     * Escribe el PDF.
     * @param pages imágenes ya comprimidas, en orden.
     * @param pageWidthPt ancho de hoja en puntos (1/72").
     * @param pageHeightPt alto de hoja en puntos.
     * @param coverPage si es true la imagen cubre la hoja entera; si no, se ajusta conservando proporción.
     * @param watermarkText marca de agua opcional (se dibuja en diagonal, gris claro).
     */
    fun write(
        file: File,
        pages: List<JpegPage>,
        pageWidthPt: Int,
        pageHeightPt: Int,
        title: String,
        coverPage: Boolean = false,
        watermarkText: String? = null
    ) {
        require(pages.isNotEmpty()) { "El PDF necesita al menos una página" }

        val out = ByteArrayOutputStream(1 shl 20)
        // Cabecera: sin esto muchos lectores rechazan el archivo (PyMuPDF es tolerante, los visores
        // de WhatsApp y los móviles no). La segunda línea marca el archivo como binario.
        out.write("%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
        val offsets = HashMap<Int, Int>()
        var nextObj = 1

        fun alloc(): Int = nextObj++

        // Objetos fijos
        val catalogObj = alloc()
        val pagesObj = alloc()
        val infoObj = alloc()
        val fontObj = alloc()

        val pageRefs = StringBuilder()

        // Cada página usa 3 objetos: Page, Contents, Image
        data class PageObjs(val page: Int, val content: Int, val image: Int)
        val pageObjs = pages.map {
            val p = PageObjs(alloc(), alloc(), alloc())
            pageRefs.append("${p.page} 0 R ")
            p
        }

        fun startObj(id: Int, dict: String) {
            offsets[id] = out.size()
            out.write("$id 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(dict.toByteArray(Charsets.ISO_8859_1))
        }

        fun streamObj(id: Int, dictHead: String, data: ByteArray) {
            offsets[id] = out.size()
            out.write("$id 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write("<< $dictHead /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            out.write(data)
            out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }

        // 1: catálogo
        startObj(catalogObj, "<< /Type /Catalog /Pages $pagesObj 0 R >>\nendobj\n")
        // 2: árbol de páginas
        startObj(pagesObj, "<< /Type /Pages /Kids [$pageRefs] /Count ${pages.size} >>\nendobj\n")
        // 3: información
        val now = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val safeTitle = escapeText(title)
        startObj(
            infoObj,
            "<< /Title ($safeTitle) /Producer (DocuScan) /Creator (DocuScan) /CreationDate (D:$now) >>\nendobj\n"
        )
        // 4: fuente para la marca de agua
        startObj(
            fontObj,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n"
        )

        val wm = watermarkText?.trim()?.takeIf { it.isNotEmpty() }

        pages.forEachIndexed { index, jpegPage ->
            val objs = pageObjs[index]
            val imgW = jpegPage.pxWidth.toFloat()
            val imgH = jpegPage.pxHeight.toFloat()
            val pageW = pageWidthPt.toFloat()
            val pageH = pageHeightPt.toFloat()

            // Ajuste de la imagen dentro de la hoja
            val drawW: Float
            val drawH: Float
            val dx: Float
            val dy: Float
            if (coverPage) {
                drawW = pageW
                drawH = pageH
                dx = 0f
                dy = 0f
            } else {
                val imgAspect = imgW / imgH
                val pageAspect = pageW / pageH
                if (imgAspect > pageAspect) {
                    drawW = pageW
                    drawH = pageW / imgAspect
                } else {
                    drawH = pageH
                    drawW = pageH * imgAspect
                }
                dx = (pageW - drawW) / 2f
                dy = (pageH - drawH) / 2f
            }

            val content = StringBuilder()
            content.append("q\n")
            content.append("${f(drawW)} 0 0 ${f(drawH)} ${f(dx)} ${f(dy)} cm\n")
            content.append("/Im0 Do\n")
            content.append("Q\n")
            if (wm != null) {
                val size = (pageW / 12f).coerceIn(28f, 72f)
                // Diagonal de 45°: matriz [cos sin -sin cos tx ty]
                val c = 0.7071f
                content.append("q\n")
                content.append("0.86 0.86 0.86 rg\n")
                content.append("BT\n")
                content.append("/F1 ${f(size)} Tf\n")
                content.append("${f(c)} ${f(c)} ${f(-c)} ${f(c)} ${f(pageW * 0.14f)} ${f(pageH * 0.22f)} Tm\n")
                content.append("(${escapeText(wm)}) Tj\n")
                content.append("ET\n")
                content.append("Q\n")
            }
            val contentBytes = content.toString().toByteArray(Charsets.ISO_8859_1)

            // Página
            startObj(
                objs.page,
                "<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] " +
                    "/Resources << /XObject << /Im0 ${objs.image} 0 R >> /Font << /F1 $fontObj 0 R >> >> " +
                    "/Contents ${objs.content} 0 R >>\nendobj\n"
            )
            // Contenido
            streamObj(objs.content, "", contentBytes)
            // Imagen JPEG
            streamObj(
                objs.image,
                "/Type /XObject /Subtype /Image /Width ${jpegPage.pxWidth} /Height ${jpegPage.pxHeight} " +
                    "/ColorSpace /${jpegPage.colorSpace} /BitsPerComponent ${jpegPage.bitsPerComponent} " +
                    "/Filter /${jpegPage.filter}",
                jpegPage.jpeg
            )
        }

        // xref
        val xrefPos = out.size()
        val total = nextObj
        out.write("xref\n0 $total\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (i in 1 until total) {
            val off = offsets[i] ?: 0
            out.write(String.format(Locale.US, "%010d 00000 n \n", off).toByteArray(Charsets.ISO_8859_1))
        }
        out.write(
            "trailer\n<< /Size $total /Root $catalogObj 0 R /Info $infoObj 0 R >>\nstartxref\n$xrefPos\n%%EOF\n"
                .toByteArray(Charsets.ISO_8859_1)
        )

        FileOutputStream(file).use { it.write(out.toByteArray()) }
    }

    /** Comprime un bitmap a JPEG. Devuelve los bytes y el tamaño en píxeles. */
    fun encodeJpeg(source: Bitmap, quality: Int): JpegPage {
        val bos = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), bos)
        return JpegPage(bos.toByteArray(), source.width, source.height)
    }

    /**
     * Convierte la página a **blanco y negro puro de 1 bit** y la comprime con Deflate.
     *
     * Un documento de texto en color a 220 dpi pesa ~520 KB por página, y cincuenta hojas serían
     * 26 MB. En 1 bit con zlib baja a ~40-50 KB por página: cincuenta hojas, ~2 MB, con el texto
     * igual de legible. Es el mismo truco que usan los escáneres de oficina (allí con CCITT G4).
     *
     * El umbral es local (Bradley): compara cada píxel con la media de su entorno, así una sombra
     * suave o un papel amarillento no se comen el texto. Después se limpian los puntos sueltos, que
     * es lo que deja el ruido del sensor al binarizar.
     *
     * Ojo: se pierde el color. Para documentos con sellos, firmas a color o gráficos, usar JPEG.
     */
    fun encodeMonochrome(source: Bitmap): JpegPage {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Luminancia en un byte por píxel: los Int de color ocupan cuatro veces más y no hacen falta.
        val gray = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }

        // Suavizado 5x5 antes de umbralizar. No es cosmetico: con una foto real, sin este paso el
        // ruido del sensor parte las letras finas y el texto sale ilegible; con el, se lee entero.
        // Se hace como dos pasadas separables (horizontal y vertical) para no pagar 9 lecturas por pixel.
        val horizontal = IntArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var suma = 0
                var n = 0
                for (dx in -2..2) {
                    val nx = x + dx
                    if (nx in 0 until w) {
                        suma += gray[base + nx].toInt() and 0xFF
                        n++
                    }
                }
                horizontal[base + x] = suma / n
            }
        }
        val suave = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var suma = 0
                var n = 0
                for (dy in -2..2) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        suma += horizontal[ny * w + x]
                        n++
                    }
                }
                suave[y * w + x] = suma / n
            }
        }
        horizontal.fill(0)

        // Imagen integral para calcular la media de cualquier ventana en tiempo constante.
        val stride = w + 1
        val integral = IntArray(stride * (h + 1))
        for (y in 1..h) {
            var fila = 0
            val base = y * stride
            val prev = (y - 1) * stride
            val filaGray = (y - 1) * w
            for (x in 1..w) {
                fila += suave[filaGray + x - 1]
                integral[base + x] = integral[prev + x] + fila
            }
        }

        val radio = maxOf(10, w / 32)
        val negro = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radio)
            val y1 = minOf(h, y + radio + 1)
            val baseY0 = y0 * stride
            val baseY1 = y1 * stride
            val filaGray = y * w
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radio)
                val x1 = minOf(w, x + radio + 1)
                val suma = integral[baseY1 + x1] - integral[baseY0 + x1] -
                    integral[baseY1 + x0] + integral[baseY0 + x0]
                val cuenta = (y1 - y0) * (x1 - x0)
                // 0.88 en lugar de 1.0: el texto queda un poco más grueso y no se rompen las letras finas.
                // Sin piso de fondo a proposito: descartar las zonas oscuras borraba tambien las
                // cabeceras negras con texto blanco (tablas), que es informacion del documento.
                negro[filaGray + x] = suave[filaGray + x] * cuenta < suma * 0.88f
            }
        }
        pixels.fill(0)

        // Sin despeckle: la limpieza de puntos sueltos tambien borraba los extremos de los trazos
        // finos y dejaba las letras partidas. El suavizado 5x5 ya quita casi todo el ruido del sensor,
        // y las motas que sobreviven no molestan a la lectura.
        val limpio = negro

        // Empaquetado: 1 bit por píxel, bit alto primero, 1 = blanco y 0 = negro (lo que espera
        // DeviceGray de 1 bit). Las filas se rellenan hasta el byte completo.
        val rowBytes = (w + 7) / 8
        val raw = ByteArray(rowBytes * h) { 0xFF.toByte() }
        for (y in 0 until h) {
            val base = y * rowBytes
            val filaBits = y * w
            for (x in 0 until w) {
                if (limpio[filaBits + x]) {
                    val idx = base + (x shr 3)
                    raw[idx] = (raw[idx].toInt() and (0x7F ushr (x and 7)).inv()).toByte()
                }
            }
        }

        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val salida = ByteArrayOutputStream(raw.size / 4 + 64)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n <= 0) break
            salida.write(buffer, 0, n)
        }
        deflater.end()

        return JpegPage(
            jpeg = salida.toByteArray(),
            pxWidth = w,
            pxHeight = h,
            filter = "FlateDecode",
            colorSpace = "DeviceGray",
            bitsPerComponent = 1
        )
    }

    /** Escala un bitmap para que su lado mayor no supere [maxLongest]; no amplía. */
    fun fitLongest(source: Bitmap, maxLongest: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxLongest) return source
        val scale = maxLongest.toFloat() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /** Píxeles necesarios para que la hoja quede a [dpi] (se usa el lado mayor). */
    fun targetLongestSide(pageWidthPt: Int, pageHeightPt: Int, dpi: Int): Int =
        ((maxOf(pageWidthPt, pageHeightPt) * dpi) / PAGE_DPI_REF).toInt()

    private fun f(v: Float): String = String.format(Locale.US, "%.2f", v)

    private fun escapeText(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '\n', '\r' -> sb.append(' ')
                else -> if (ch.code in 32..255) sb.append(ch) else sb.append('?')
            }
        }
        return sb.toString()
    }
}
