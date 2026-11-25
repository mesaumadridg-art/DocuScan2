package com.example.pdf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifica que el PDF que escribe [PdfWriter] es válido y que la marca de agua no lo rompe.
 * El archivo queda en build/test-pdf/ para poder abrirlo con un lector externo.
 */
class PdfWriterTest {

    private fun jpegDePrueba(): ByteArray {
        val url = javaClass.classLoader!!.getResourceAsStream("pagina_prueba.jpg")
            ?: error("Falta pagina_prueba.jpg en test/resources")
        return url.use { it.readBytes() }
    }

    @Test
    fun `escribe un pdf de dos paginas con marca de agua`() {
        val jpeg = jpegDePrueba()
        val pages = listOf(
            PdfWriter.JpegPage(jpeg, 600, 800),
            PdfWriter.JpegPage(jpeg, 600, 800)
        )
        val out = File("build/test-pdf/con-marca.pdf").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }

        PdfWriter.write(
            file = out,
            pages = pages,
            pageWidthPt = 612,
            pageHeightPt = 792,
            title = "Prueba (con) parentesis",
            watermarkText = "CONFIDENCIAL"
        )

        assertTrue("El PDF debe existir", out.exists())
        val bytes = out.readBytes()
        assertTrue("Debe empezar con la firma PDF", String(bytes, 0, 5).contains("%PDF-"))
        assertTrue("Debe terminar con EOF", String(bytes, bytes.size - 8, 8).contains("%%EOF"))
        // Dos páginas => dos objetos /Type /Page y dos imágenes DCTDecode
        val txt = String(bytes, Charsets.ISO_8859_1)
        assertTrue("Dos páginas", Regex("/MediaBox").findAll(txt).count() == 2)
        assertTrue("Dos imágenes JPEG", Regex("/DCTDecode").findAll(txt).count() == 2)
        assertTrue("Marca de agua presente", txt.contains("CONFIDENCIAL"))
        println("PDF generado: ${out.absolutePath} (${bytes.size} bytes)")
    }

    @Test
    fun `escala sin ampliar y respeta el lado mayor`() {
        // fitLongest usa Bitmap, así que aquí solo se comprueba la aritmética de la resolución objetivo.
        val target = PdfWriter.targetLongestSide(612, 792, 220)
        assertTrue("Carta a 220 dpi ronda las 2400 px de alto", target in 2350..2450)
    }
}
