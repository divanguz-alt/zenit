package io.github.divanguz_alt.zenit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.divanguz_alt.zenit.R

object Z {
    val Background = Color(0xFF000000)
    val Text = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFF9AA0A6)
    val TextTertiary = Color(0xFF6B7076)
    val Card = Color(0xFF1A1B1F)
    val Divider = Color(0x0FFFFFFF)
    val BottomBar = Color(0xFF17181B)
    val Sheet = Color(0xFF1E1F23)
    val SheetTile = Color(0xFF26272B)
    val Accent = Color(0xFF5BE38A)
    val AccentOn = Color(0xFF0B3B1E)
    val Heading = Color(0xFFFF6B5E)
    val Searching = Color(0xFF7FD3FF)
    val Outline = Color(0xFF3C4043)
}

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int) = Font(
    R.font.roboto_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val RobotoFlex = FontFamily(flex(400), flex(500), flex(600), flex(700))

/** Tabular figures for every number on screen. */
val Numeric = TextStyle(fontFamily = RobotoFlex, fontFeatureSettings = "tnum")

private val base = Typography()
private fun TextStyle.flex() = copy(fontFamily = RobotoFlex)

private val ZenitTypography = Typography(
    displayLarge = base.displayLarge.flex(),
    displayMedium = base.displayMedium.flex(),
    displaySmall = base.displaySmall.flex(),
    headlineLarge = base.headlineLarge.flex(),
    headlineMedium = base.headlineMedium.flex(),
    headlineSmall = base.headlineSmall.flex(),
    titleLarge = base.titleLarge.flex(),
    titleMedium = base.titleMedium.flex(),
    titleSmall = base.titleSmall.flex(),
    bodyLarge = base.bodyLarge.flex(),
    bodyMedium = base.bodyMedium.flex(),
    bodySmall = base.bodySmall.flex(),
    labelLarge = base.labelLarge.flex(),
    labelMedium = base.labelMedium.flex(),
    labelSmall = base.labelSmall.flex(),
)

private val ZenitColors = darkColorScheme(
    primary = Z.Accent,
    onPrimary = Z.AccentOn,
    secondary = Z.Searching,
    background = Z.Background,
    onBackground = Z.Text,
    surface = Z.Sheet,
    onSurface = Z.Text,
    surfaceVariant = Z.SheetTile,
    onSurfaceVariant = Z.TextSecondary,
    surfaceContainer = Z.Card,
    surfaceContainerHigh = Z.Sheet,
    surfaceContainerHighest = Z.SheetTile,
    surfaceContainerLow = Z.Card,
    outline = Color(0xFF8E918F),
    outlineVariant = Z.Outline,
    secondaryContainer = Color(0xFF1F4D30),
    onSecondaryContainer = Color(0xFFC9F5D6),
)

@Composable
fun ZenitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZenitColors, typography = ZenitTypography) {
        CompositionLocalProvider(
            LocalContentColor provides Z.Text,
            LocalTextStyle provides TextStyle(fontFamily = RobotoFlex, color = Z.Text),
            content = content,
        )
    }
}

/** Label 12 sp / value 24 sp Medium / unit 12 sp secondary, as in the prototype. */
object ZType {
    val section = TextStyle(fontFamily = RobotoFlex, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Z.TextSecondary)
    val label = TextStyle(fontFamily = RobotoFlex, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Z.TextSecondary)
    val value = Numeric.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Z.Text)
    val unit = TextStyle(fontFamily = RobotoFlex, fontSize = 12.sp, color = Z.TextSecondary)
    val sub = Numeric.copy(fontSize = 12.sp, color = Z.TextTertiary)
    val barValue = Numeric.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Z.Text)
    val barLabel = TextStyle(fontFamily = RobotoFlex, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Z.TextSecondary)
}
