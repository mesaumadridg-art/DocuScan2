package com.example.data

enum class ExportFormat(
    val id: String,
    val title: String,
    val extension: String,
    val mimeType: String,
    val iconLabel: String,
    val description: String
) {
    PDF(
        id = "pdf",
        title = "Documento PDF",
        extension = "pdf",
        mimeType = "application/pdf",
        iconLabel = "PDF",
        description = "Documento estándar multipágina listo para imprimir o enviar."
    ),
    JPG(
        id = "jpg",
        title = "Imágenes JPG",
        extension = "jpg",
        mimeType = "image/jpeg",
        iconLabel = "JPG",
        description = "Imágenes fotográficas comprimidas ideales para compartir en mensajería."
    ),
    PNG(
        id = "png",
        title = "Imágenes PNG",
        extension = "png",
        mimeType = "image/png",
        iconLabel = "PNG",
        description = "Imágenes de máxima calidad y definición sin compresión con pérdida."
    ),
    TXT(
        id = "txt",
        title = "Texto OCR (.txt)",
        extension = "txt",
        mimeType = "text/plain",
        iconLabel = "TXT",
        description = "Archivo de texto plano editable extraído de las páginas escaneadas."
    ),
    ZIP(
        id = "zip",
        title = "Archivo ZIP",
        extension = "zip",
        mimeType = "application/zip",
        iconLabel = "ZIP",
        description = "Archivo comprimido que contiene todas las páginas y el texto digitalizado."
    )
}
