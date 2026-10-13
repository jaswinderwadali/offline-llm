package ai.impact.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BrandOrange,
    onPrimary = Charcoal,
    primaryContainer = Color(0xFF4A2418),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFCFC8BD),
    onSecondary = Charcoal,
    secondaryContainer = Color(0xFF2D303B),
    onSecondaryContainer = Cream,
    tertiary = BrandGreen,
    onTertiary = Charcoal,
    tertiaryContainer = Color(0xFF1E3A2F),
    onTertiaryContainer = Color(0xFFBDEBD6),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF242731),
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF101217),
    surfaceContainerLow = Color(0xFF181A21),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = Color(0xFF242731),
    surfaceContainerHighest = Color(0xFF2D303B),
    inverseSurface = Cream,
    inverseOnSurface = Charcoal,
    inversePrimary = BurntOrange,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = BurntOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1D6),
    onPrimaryContainer = Color(0xFF5A1A05),
    secondary = Color(0xFF4A4640),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE4D8),
    onSecondaryContainer = Charcoal,
    tertiary = DeepGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7EFE4),
    onTertiaryContainer = Color(0xFF0B3B29),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFEEE7DB),
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF8F3),
    surfaceContainer = Color(0xFFF3EDE3),
    surfaceContainerHigh = Color(0xFFEEE7DB),
    surfaceContainerHighest = Color(0xFFE7DFD1),
    inverseSurface = Charcoal,
    inverseOnSurface = Cream,
    inversePrimary = BrandOrange,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color.Black
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ImpactTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
