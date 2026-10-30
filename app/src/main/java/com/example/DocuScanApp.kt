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
    }

    companion object {
        lateinit var instance: DocuScanApp
            private set
    }
}
