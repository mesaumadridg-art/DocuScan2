package com.example

import android.graphics.PointF
import com.example.data.PdfPageSize
import com.example.imaging.DocumentFilter
import com.example.imaging.QuadCorners
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
    @Test
    fun quadCorners_serialization_works() {
        val corners = QuadCorners(
            topLeft = PointF(0.1f, 0.1f),
            topRight = PointF(0.9f, 0.1f),
            bottomRight = PointF(0.9f, 0.9f),
            bottomLeft = PointF(0.1f, 0.9f)
        )
        val json = corners.toJson()
        val restored = QuadCorners.fromJson(json)

        assertEquals(0.1f, restored.topLeft.x, 0.001f)
        assertEquals(0.9f, restored.topRight.x, 0.001f)
        assertEquals(0.9f, restored.bottomRight.y, 0.001f)
        assertEquals(0.9f, restored.bottomLeft.y, 0.001f)
    }

    @Test
    fun documentFilter_values_exist() {
        assertNotNull(DocumentFilter.ORIGINAL)
        assertNotNull(DocumentFilter.COLOR_ENHANCE)
        assertNotNull(DocumentFilter.GRAYSCALE)
        assertNotNull(DocumentFilter.BLACK_WHITE)
        assertNotNull(DocumentFilter.DOC_ENHANCE)
        assertEquals("Doc Mejorado", DocumentFilter.DOC_ENHANCE.displayName)
    }

    @Test
    fun pdfPageSizes_configured_properly() {
        val sizes = PdfPageSize.entries
        assertEquals("Carta", sizes[0].displayName)
        assertEquals("Oficio", sizes[1].displayName)
        assertEquals("Legal", sizes[2].displayName)
        assertEquals("Ajustar a foto", sizes[3].displayName)
        assertEquals("A4", sizes[4].displayName) // A4 as last option
    }
}
