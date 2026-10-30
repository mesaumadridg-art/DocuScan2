package com.example.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Devuelve una función que ejecuta [action] solo cuando hay permiso para escribir en
 * Descargas/Galería.
 *
 * En Android 10+ (scoped storage + MediaStore) no se necesita permiso. En Android 7-9
 * (API 24-28) MediaStore exige WRITE_EXTERNAL_STORAGE, así que se pide en runtime y, si el
 * usuario lo concede, se retoma la acción pendiente.
 */
@Composable
fun rememberStoragePermissionGate(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(
                context,
                "Sin permiso de almacenamiento no se puede guardar en Descargas ni en la galería",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    return { action: () -> Unit ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasLegacyStoragePermission(context)) {
            action()
        } else {
            pendingAction = action
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

private fun hasLegacyStoragePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
