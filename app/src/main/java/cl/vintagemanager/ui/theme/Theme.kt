package cl.vintagemanager.ui.theme

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paleta cálida vintage (contraste verificado WCAG AA para texto sobre fondo).
val Cream = Color(0xFFF8EFE0)
val CreamDark = Color(0xFFEFE2CC)
val Terracotta = Color(0xFFA34A2E)
val TerracottaLight = Color(0xFFE59A7E)
val Olive = Color(0xFF55632A)
val OliveLight = Color(0xFFB8C58A)
val Coffee = Color(0xFF4A3426)
val Espresso = Color(0xFF221A15)
val Mocha = Color(0xFF33281F)

// Semáforo FEFO
val SemFresca = Color(0xFF3F7D3A)
val SemPronto = Color(0xFFC99A1E)
val SemYa = Color(0xFFD9732B)
val SemLiquidar = Color(0xFFB3261E)
val Danger = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6D3C4),
    onPrimaryContainer = Color(0xFF3B1306),
    secondary = Olive,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE5B8),
    onSecondaryContainer = Color(0xFF1A2000),
    tertiary = Coffee,
    onTertiary = Color.White,
    background = Cream,
    onBackground = Espresso,
    surface = Cream,
    onSurface = Espresso,
    surfaceVariant = CreamDark,
    onSurfaceVariant = Coffee,
    surfaceContainer = Color(0xFFF3E7D3),
    surfaceContainerHigh = Color(0xFFEDDFC8),
    surfaceContainerLow = Color(0xFFFBF4E8),
    outline = Color(0xFF85725F),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = TerracottaLight,
    onPrimary = Color(0xFF4A1A08),
    primaryContainer = Color(0xFF7A3520),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = OliveLight,
    onSecondary = Color(0xFF283100),
    secondaryContainer = Color(0xFF3D4914),
    onSecondaryContainer = Color(0xFFDDE5B8),
    tertiary = Color(0xFFD9C2AE),
    background = Espresso,
    onBackground = Color(0xFFF1E3D3),
    surface = Espresso,
    onSurface = Color(0xFFF1E3D3),
    surfaceVariant = Mocha,
    onSurfaceVariant = Color(0xFFD8C4B2),
    surfaceContainer = Color(0xFF2C231D),
    surfaceContainerHigh = Color(0xFF372D25),
    surfaceContainerLow = Color(0xFF261E19),
    outline = Color(0xFFA08D7B),
    error = Color(0xFFFFB4AB),
)

private val base = Typography()

/** Serif para títulos, sans-serif legible para datos. */
val VintageTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
    bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
    labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
)

val MoneyStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp)

val VintageShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun VintageTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VintageTypography,
        shapes = VintageShapes,
        content = content,
    )
}
