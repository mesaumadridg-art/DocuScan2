# Estado del proyecto

Resumen técnico para retomar el desarrollo. No incluye credenciales de firma (el keystore está fuera del repo).

- **App**: DocuScan — escáner con detección de bordes, filtros, OCR (ML Kit latino), PDF y exportación.
- **Paquete**: `com.aistudio.docuscan.knrvpx` · namespace `com.example`
- **SDK**: minSdk 24 · compile/target 36.1 · AGP 9.1.1 · Gradle 9.6.0 · Kotlin 2.2.10
- **Versión**: 1.9 (versionCode 10)

## Compilar
```bash
export KEYSTORE_PATH=/ruta/keystore.jks STORE_PASSWORD=... KEY_PASSWORD=...
./gradlew assembleRelease
```
Para `assembleDebug` hace falta un `debug.keystore` en la raíz del proyecto.

## Arquitectura
- `MainActivity` navega con estado (Main / Camera / Editor / Viewer) y tabs Inferior.
- `data/`: Room (`FolderEntity`, `DocumentEntity`, `PageEntity`), `DocumentRepository`, `FileManager`, DataStore de preferencias.
- `imaging/EdgeDetector`: detección por luminancia con umbral de Otsu + mayor componente conexa; respaldo por proyecciones de gradiente.
- `pdf/PdfManager`: genera PDF solo con la imagen de cada página (sin capa de texto).
- `ocr/OcrManager`: ML Kit, timeout 30 s, se cierra en `onCleared`.
- `ui/components/CropOverlay`: canvas del recorte con cuatro handles arrastrables.

## OCR
Al guardar se ejecuta OCR en background por página: guarda `PageEntity.ocrText` y consolidado en `DocumentEntity.combinedOcrText`.
Salidas: TXT en Descargas, copiar, compartir, ZIP con el texto, búsqueda por contenido y OCR de una foto suelta en Herramientas.
El PDF **no** es buscable: no se dibuja texto, solo el bitmap.

## Tema y contraste
- Los botones de acción usan `ui/theme/Brand.kt` (`brandAction()`, `brandCard()`, `brandSoft()`) para adaptarse al modo oscuro; **todo `buttonColors` debe llevar `contentColor` explícito**, si no Compose pinta la letra oscura sobre el botón azul.
- Ajustes lee la versión real del paquete (`getPackageInfo(...).versionName`), no un texto fijo.

## Comportamiento de guardado (decidido con el usuario)
- Los botones del editor dicen "Guardar" y guardan **solo en la biblioteca** de la app (Room + `filesDir/scans`, `filesDir/pdfs`). No bajan nada a Descargas.
- Persistencia verificada con `adb reboot`: los documentos y sus archivos siguen tras reiniciar el dispositivo.
- El botón "Descargar" del visor sí exporta a Descargas (PDF/JPG/PNG/TXT/ZIP) y es la única vía para sacar el TXT.
- En Herramientas hay un documento seleccionado visible y cambiable; marca de agua y "compartir JPG" usan ese documento (antes usaban `documents.first()`).

## Pendientes
1. PDF con capa de texto invisible (OCR embebido) para que sea buscable. Pospuesto: requiere guardar las cajas de ML Kit, migración de Room y una librería PDF extra.
2. Migraciones de Room: hoy hay `fallbackToDestructiveMigration()`.
3. Añadir páginas a un documento ya guardado (el flujo actual suma en el editor).
