package com.shixi.client

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Approved visual tokens: neutral surfaces, orange only for playback and selection.
fun clientColors(dark: Boolean) = if (dark) darkColorScheme(
    inversePrimary = Color(0xFFBC451D), inverseSurface = Color(0xFFFCFCFB), inverseOnSurface = Color(0xFF252925),
    primary = Color(0xFFF29466), onPrimary = Color(0xFF302017),
    primaryContainer = Color(0xFF3A2A22), onPrimaryContainer = Color(0xFFF29466),
    secondary = Color(0xFFAAB3A8), onSecondary = Color(0xFF171B18),
    secondaryContainer = Color(0xFF252C26), onSecondaryContainer = Color(0xFFE8ECE6),
    surface = Color(0xFF171B18), background = Color(0xFF171B18),
    onSurface = Color(0xFFE8ECE6), onBackground = Color(0xFFE8ECE6), onSurfaceVariant = Color(0xFFAAB3A8),
    surfaceContainer = Color(0xFF252C26), surfaceContainerLow = Color(0xFF202621),
    surfaceContainerHigh = Color(0xFF2C342D), surfaceContainerHighest = Color(0xFF353E36),
    surfaceVariant = Color(0xFF252C26), outline = Color(0xFF82907F), outlineVariant = Color(0xFF353E36),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
) else lightColorScheme(
    inversePrimary = Color(0xFFF29466), inverseSurface = Color(0xFF171B18), inverseOnSurface = Color(0xFFE8ECE6),
    primary = Color(0xFFBC451D), onPrimary = Color.White,
    primaryContainer = Color(0xFFFBECE5), onPrimaryContainer = Color(0xFFBC451D),
    secondary = Color(0xFF6B716C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3F4F2), onSecondaryContainer = Color(0xFF252925),
    surface = Color(0xFFFCFCFB), background = Color(0xFFFCFCFB),
    onSurface = Color(0xFF252925), onBackground = Color(0xFF252925), onSurfaceVariant = Color(0xFF6B716C),
    surfaceContainer = Color(0xFFF3F4F2), surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFEDEFEA), surfaceContainerHighest = Color(0xFFE8EAE5),
    surfaceVariant = Color(0xFFF3F4F2), outline = Color(0xFF858C84), outlineVariant = Color(0xFFE8EAE5),
    error = Color(0xFFB3261E), onError = Color.White
)
val ClientTypography = Typography().run { copy(
    headlineLarge = headlineLarge.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = headlineSmall.copy(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = titleLarge.copy(fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = titleSmall.copy(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = labelSmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
    labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 20.sp)
) }
