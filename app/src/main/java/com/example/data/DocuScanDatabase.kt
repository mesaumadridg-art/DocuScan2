package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FolderEntity::class, DocumentEntity::class, PageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DocuScanDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao

    companion object {
        @Volatile
        private var INSTANCE: DocuScanDatabase? = null

        fun getDatabase(context: Context): DocuScanDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocuScanDatabase::class.java,
                    "docuscan_database.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
