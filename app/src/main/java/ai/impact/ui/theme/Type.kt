package ai.impact.ui.theme

import ai.impact.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Inter: a clean, highly legible UI typeface, bundled so it works offline.
 * Characters it lacks (non-Latin scripts, emoji) fall back to the system fonts.
 */
val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold)
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

// Inter reads best with slightly tight tracking on large sizes and neutral tracking on body text.
val Typography = Typography(
    displayLarge = style(56, 64, FontWeight.Bold, -1.2),
    displayMedium = style(44, 52, FontWeight.Bold, -0.9),
    displaySmall = style(36, 44, FontWeight.Bold, -0.7),
    headlineLarge = style(30, 38, FontWeight.Bold, -0.6),
    headlineMedium = style(26, 34, FontWeight.Bold, -0.45),
    headlineSmall = style(22, 30, FontWeight.SemiBold, -0.3),
    titleLarge = style(20, 28, FontWeight.SemiBold, -0.2),
    titleMedium = style(16, 24, FontWeight.SemiBold, -0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 25, FontWeight.Normal, -0.1),
    bodyMedium = style(14, 21, FontWeight.Normal),
    bodySmall = style(12, 17, FontWeight.Normal, 0.1),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.Medium, 0.1),
    labelSmall = style(11, 16, FontWeight.Medium, 0.2)
)
