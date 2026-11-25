package com.example.imaging

import android.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Orientación de una foto según su EXIF.
 *
 * Las cámaras guardan los píxeles «como salieron del sensor» y anotan en el EXIF cuántos grados hay
 * que girarlos para verlos derechos. `BitmapFactory` **no** aplica esa anotación, así que una foto
 * vertical de móvil se decodifica apaisada si no se corrige aquí.
 */
object ExifRotation {

    /** Grados (0, 90, 180, 270) que hay que girar la imagen en sentido horario. */
    fun degreesFromJpeg(bytes: ByteArray): Int {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun degreesFromFile(path: String): Int {
        return try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
