package com.example

import android.app.Application
import com.example.data.DocuScanDatabase
import com.example.data.PreferencesManager

class DocuScanApp : Application() {

    val database: DocuScanDatabase by lazy {
        DocuScanDatabase.getDatabase(this)
    }

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // El modelo de detección se carga en segundo plano: son 4,7 MB desde assets y no debe
        // retrasar el arranque de la app.
        Thread({
            try {
                com.example.imaging.DocDetector.warmUp(this)
            } catch (t: Throwable) {
                // Si falla, la app sigue con el detector geométrico.
            }
        }, "doc-detector-warmup").start()
    }

    companion object {
        lateinit var instance: DocuScanApp
            private set
    }
}
