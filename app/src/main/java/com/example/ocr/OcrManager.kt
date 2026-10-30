package com.example.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class OcrResult(
    val fullText: String,
    val wordCount: Int,
    val lineCount: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

class OcrManager(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(2500L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            if (continuation.isActive) {
                                val text = visionText.text.trim()
                                val lines = visionText.textBlocks.flatMap { it.lines }
                                val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }

                                continuation.resume(
                                    OcrResult(
                                        fullText = text,
                                        wordCount = words.size,
                                        lineCount = lines.size,
                                        isSuccess = true
                                    )
                                )
                            }
                        }
                        .addOnFailureListener { exception ->
                            if (continuation.isActive) {
                                continuation.resume(
                                    OcrResult(
                                        fullText = "",
                                        wordCount = 0,
                                        lineCount = 0,
                                        isSuccess = false,
                                        errorMessage = exception.localizedMessage ?: "Error en OCR."
                                    )
                                )
                            }
                        }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(
                            OcrResult(
                                fullText = "",
                                wordCount = 0,
                                lineCount = 0,
                                isSuccess = false,
                                errorMessage = e.localizedMessage
                            )
                        )
                    }
                }
            }
        }

        result ?: OcrResult(
            fullText = "",
            wordCount = 0,
            lineCount = 0,
            isSuccess = false,
            errorMessage = "Tiempo de espera agotado"
        )
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DocuScan OCR", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Texto copiado al portapapeles", Toast.LENGTH_SHORT).show()
    }

    fun shareText(text: String, title: String = "Texto extraído con DocuScan") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir texto vía").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
