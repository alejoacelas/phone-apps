package com.alejoacelas.morningreader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Serif = FontFamily(Font(R.font.literata, FontWeight.Normal), Font(R.font.literata, FontWeight.SemiBold))

private val Light = lightColorScheme(
    background = Color(0xFFF6F1E7), surface = Color(0xFFF6F1E7), surfaceContainer = Color(0xFFEFE8DB),
    surfaceContainerLow = Color(0xFFFBF8F2), surfaceContainerHigh = Color(0xFFEDE5D6),
    onBackground = Color(0xFF2B2A27), onSurface = Color(0xFF2B2A27), onSurfaceVariant = Color(0xFF6E6A62),
    primary = Color(0xFF9A5B26), onPrimary = Color.White, secondaryContainer = Color(0xFFE9DCC8),
    onSecondaryContainer = Color(0xFF3B2F22), outlineVariant = Color(0xFFDCD3C3),
)

private val Dark = darkColorScheme(
    background = Color(0xFF161513), surface = Color(0xFF161513), surfaceContainer = Color(0xFF211F1C),
    surfaceContainerLow = Color(0xFF1C1B18), surfaceContainerHigh = Color(0xFF282622),
    onBackground = Color(0xFFE6E1D6), onSurface = Color(0xFFE6E1D6), onSurfaceVariant = Color(0xFF9D978B),
    primary = Color(0xFFD9A066), onPrimary = Color(0xFF2B1A08), secondaryContainer = Color(0xFF3A3128),
    onSecondaryContainer = Color(0xFFEBDCC6), outlineVariant = Color(0xFF34312C),
)

// E-ink shows only greys, and a light grey on white barely registers.
private val Paper = lightColorScheme(
    background = Color.White, surface = Color.White, surfaceContainer = Color.White,
    surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFEEEEEE),
    onBackground = Color.Black, onSurface = Color.Black, onSurfaceVariant = Color(0xFF333333),
    primary = Color.Black, onPrimary = Color.White, secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color.Black, outline = Color.Black, outlineVariant = Color(0xFF888888),
)

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
        titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
        titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    )
    MaterialTheme(colorScheme = when { Eink.on -> Paper; isSystemInDarkTheme() -> Dark; else -> Light }, typography = typography, content = content)
}
