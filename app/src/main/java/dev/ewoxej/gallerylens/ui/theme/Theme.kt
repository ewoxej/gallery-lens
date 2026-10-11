package dev.ewoxej.gallerylens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ewoxej.gallerylens.R

// IBM Plex, bundled from the design mockup.
private val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)
private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

/** Monospace face for figures and status text (IBM Plex Mono). */
val MonoFont: FontFamily = PlexMono

// Palette from the "OCR Gallery" design: light with an orange accent; dark reuses
// the same orange accent on a near-black ground.
private object L {
    val bg = Color(0xFFEEF3F0); val panel = Color(0xFFFFFFFF)
    val card = Color(0xFFF5F8F6); val chip = Color(0xFFEAF1EC)
    val border = Color(0xFFDDE6E0)
    val tx = Color(0xFF132019); val tx2 = Color(0xFF4A5F54); val tx3 = Color(0xFF6F8A7D)
    val accent = Color(0xFFEF5B34); val onAccent = Color(0xFFFFFFFF)
    val alert = Color(0xFFE05A5A)
}

private object D {
    val bg = Color(0xFF0A0F0D); val panel = Color(0xFF0E1512)
    val card = Color(0xFF141D19); val chip = Color(0xFF1B2721)
    val border = Color(0xFF223029)
    val tx = Color(0xFFF2F7F4); val tx2 = Color(0xFF9DB3A7); val tx3 = Color(0xFF7C9086)
    val accent = Color(0xFFEF5B34); val onAccent = Color(0xFFFFFFFF)
    val alert = Color(0xFFE05A5A)
}

private val LightColors = lightColorScheme(
    primary = L.accent, onPrimary = L.onAccent,
    secondary = L.accent, onSecondary = L.onAccent,
    background = L.bg, onBackground = L.tx,
    surface = L.card, onSurface = L.tx,
    surfaceVariant = L.chip, onSurfaceVariant = L.tx3,
    surfaceContainerLowest = L.panel, surfaceContainerLow = L.bg,
    surfaceContainer = L.card, surfaceContainerHigh = L.card, surfaceContainerHighest = L.chip,
    outline = L.border, outlineVariant = L.border,
    error = L.alert, onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = D.accent, onPrimary = D.onAccent,
    secondary = D.accent, onSecondary = D.onAccent,
    background = D.bg, onBackground = D.tx,
    surface = D.card, onSurface = D.tx,
    surfaceVariant = D.chip, onSurfaceVariant = D.tx3,
    surfaceContainerLowest = D.panel, surfaceContainerLow = D.bg,
    surfaceContainer = D.card, surfaceContainerHigh = D.card, surfaceContainerHighest = D.chip,
    outline = D.border, outlineVariant = D.border,
    error = D.alert, onError = Color.White,
)

private val GalleryShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

private val GalleryTypography: Typography = Typography().run {
    fun TextStyle.sans() = copy(fontFamily = PlexSans)
    copy(
        displayLarge = displayLarge.sans(), displayMedium = displayMedium.sans(), displaySmall = displaySmall.sans(),
        headlineLarge = headlineLarge.sans(), headlineMedium = headlineMedium.sans(), headlineSmall = headlineSmall.sans(),
        titleLarge = titleLarge.sans(), titleMedium = titleMedium.sans(), titleSmall = titleSmall.sans(),
        bodyLarge = bodyLarge.sans(), bodyMedium = bodyMedium.sans(), bodySmall = bodySmall.sans(),
        labelLarge = labelLarge.sans(), labelMedium = labelMedium.sans(), labelSmall = labelSmall.sans(),
    )
}

@Composable
fun GalleryLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = GalleryShapes,
        typography = GalleryTypography,
        content = content,
    )
}
