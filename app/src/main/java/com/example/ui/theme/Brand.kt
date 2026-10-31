package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Colores de marca que se adaptan al esquema activo.
 *
 * El azul petróleo de la marca (#0C4A60) es ilegible como texto o como fondo de botón
 * cuando la app está en modo oscuro: sobre el fondo casi negro no se distingue el botón
 * y el contenido pierde contraste. Estas funciones devuelven el par fondo/contenido
 * correcto en cada caso, sin cambiar el aspecto en modo claro.
 */

/** True cuando el esquema activo es oscuro (respeta el tema elegido en Ajustes). */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Fondo de un botón de acción de marca. */
@Composable
fun brandAction(): Color =
    if (isDarkScheme()) Color(0xFF1F6B85) else Color(0xFF0C4A60)

/** Contenido (texto e iconos) sobre [brandAction]. */
@Composable
fun brandActionContent(): Color = Color.White

/** Fondo de una tarjeta destacada de marca. */
@Composable
fun brandCard(): Color =
    if (isDarkScheme()) Color(0xFF1B4965) else Color(0xFF0C4A60)

/** Contenido sobre [brandCard]. */
@Composable
fun brandCardContent(): Color = Color.White

/** Texto secundario sobre [brandCard]. */
@Composable
fun brandCardMuted(): Color =
    if (isDarkScheme()) Color(0xFFCFE7F2) else Color(0xFFBCE9F7)

/** Fondo suave para círculos y miniaturas con icono de marca. */
@Composable
fun brandSoft(): Color =
    if (isDarkScheme()) Color(0xFF17313D) else Color(0xFFE5ECEF)

/** Icono sobre [brandSoft]. */
@Composable
fun brandOnSoft(): Color =
    if (isDarkScheme()) Color(0xFF7FD4E8) else Color(0xFF0C4A60)

/** Acento turquesa legible en ambos modos. */
@Composable
fun brandAccent(): Color =
    if (isDarkScheme()) Color(0xFF4DD0E1) else Color(0xFF009688)
