package com.cartpilot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object CartPilotColors {
    val Primary = Color(0xFF075B43)
    val PrimaryContainer = Color(0xFFD5EDE3)
    val Background = Color(0xFFF7F8F5)
    val Surface = Color(0xFFFFFFFF)
    val Text = Color(0xFF252D29)
    val TextMuted = Color(0xFF5E6963)
    val Border = Color(0xFFE5EAE5)
    val Accent = Color(0xFFF0A94B)
    val Error = Color(0xFFB3261E)
}

private val LightColors = lightColorScheme(
    primary = CartPilotColors.Primary,
    onPrimary = Color.White,
    primaryContainer = CartPilotColors.PrimaryContainer,
    onPrimaryContainer = CartPilotColors.Primary,
    secondary = CartPilotColors.Accent,
    onSecondary = CartPilotColors.Text,
    background = CartPilotColors.Background,
    onBackground = CartPilotColors.Text,
    surface = CartPilotColors.Surface,
    onSurface = CartPilotColors.Text,
    surfaceVariant = CartPilotColors.Background,
    onSurfaceVariant = CartPilotColors.TextMuted,
    outline = CartPilotColors.Border,
    outlineVariant = CartPilotColors.Border,
    error = CartPilotColors.Error,
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun CartPilotTheme(content: @Composable () -> Unit) {
    // Light-only by design for the demo (clean white/green commerce look).
    MaterialTheme(colorScheme = LightColors, typography = AppTypography, shapes = AppShapes, content = content)
}
