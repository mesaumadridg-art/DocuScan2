# Estado del proyecto

Resumen técnico para retomar el desarrollo. No incluye credenciales de firma (el keystore está fuera del repo).

- **App**: DocuScan — escáner con detección de bordes, filtros, OCR (ML Kit latino), PDF y exportación.
- **Paquete**: `com.aistudio.docuscan.knrvpx` · namespace `com.example`
- **SDK**: minSdk 24 · compile/target 36.1 · AGP 9.1.1 · Gradle 9.6.0 · Kotlin 2.2.10
- **Versión**: 1.16 (versionCode 17)

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

## Editor
- Lupa que **sigue al punto** y se coloca encima del dedo (a un costado si no cabe arriba), nunca debajo.
- Arrastre anclado al punto de apoyo (`onDragStart` guarda dedo + esquina), no por deltas acumulados: el punto acompaña al dedo 1:1.
- Papelera en la barra: borra la página actual y vuelve a la anterior; si era la única, sale del editor.

## PDF y memoria
- El PDF se escribe con `pdf/PdfWriter.kt` (JPEG DCTDecode), **no** con `android.graphics.pdf.PdfDocument`: ese rasteriza a ~320 dpi y mete PNG de 2,2 MB por hoja (13 MB un documento de 6 páginas).
- `PdfQuality` fija dpi y calidad JPEG (220/88, 170/80, 130/70). Un documento de 6 páginas queda en ~1,4 MB.
- Las fotos se decodifican reducidas a 2200 px (`ImageProcessingUtils.decodeForWork`); las miniaturas del editor se generan a 128 px. Sin eso, 6 páginas de 12 MP dejaban el heap nativo en 374 MB.
- Test: `./gradlew testDebugUnitTest --tests com.example.pdf.PdfWriterTest` valida cabecera `%PDF`, xref, páginas, imágenes y marca de agua.

## Plantilla carnet
- En el diálogo de guardado, `PageLayout` permite **Carnet**: junta las imágenes de dos en dos en una hoja (frente arriba, reverso abajo, etiquetas FRENTE/REVERSO y línea separadora).
- Composición en `ImageProcessingUtils.composeCardSheet`; el emparejado ocurre en `EditorViewModel.saveDocument(layout = ...)`. Con páginas impares, la última hoja lleva solo el frente.

## Detección de bordes
- `EdgeDetector` puntúa cada mancha por **rectangularidad** y ajusta una recta por lado; si el papel y el fondo tienen brillo parecido usa la vía de bordes (DoG + relleno). Sin confianza devuelve la imagen completa: **nunca recorta mal**.
- Las esquinas se **reordenan visualmente** (`orderVisually`) antes de construir el `QuadCorners`; sin eso el handle editado no era el que se veía.
- El descarte por "llena el encuadre" exige los **dos** ejes (>95 % ancho **y** alto); con uno solo se perdían marcos válidos de fotos tomadas de cerca.
- `EditorViewModel.addPages` detecta cualquier página que llegue sin mensaje de detección (camino de la cámara incluido).
- El filtro por defecto es `ORIGINAL`; hay 10 filtros en `DocumentFilter`.
- Banco de pruebas y versión Python espejo del detector fuera del repo, en `proyectos/DocuScan2-deteccion`.

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
