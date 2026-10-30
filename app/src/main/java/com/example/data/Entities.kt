package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val colorHex: String = "#0C4A60"
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("folderId")]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val folderId: Long? = null,
    val isFavorite: Boolean = false,
    val isTrash: Boolean = false,
    val thumbnailPath: String? = null,
    val pageCount: Int = 1,
    val pdfPath: String? = null,
    val combinedOcrText: String? = null
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val originalImagePath: String,
    val processedImagePath: String,
    val rotationDegrees: Int = 0,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val filterType: String = "ORIGINAL", // ORIGINAL, COLOR_ENHANCE, GRAYSCALE, BLACK_WHITE, DOC_ENHANCE
    val cropCornersJson: String? = null, // JSON formatted normalized coordinates: x1,y1,x2,y2,x3,y3,x4,y4
    val ocrText: String? = null
)
