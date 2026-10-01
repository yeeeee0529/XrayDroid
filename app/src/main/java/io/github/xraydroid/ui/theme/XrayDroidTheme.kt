package io.github.xraydroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF225EA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF064579),
    secondary = Color(0xFF526078),
    secondaryContainer = Color(0xFFD6E3F8),
    tertiary = Color(0xFF006B60),
    tertiaryContainer = Color(0xFF9FF2E1),
    surface = Color(0xFFF8F9FF),
    background = Color(0xFFF8F9FF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF064579),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBAC7DC),
    secondaryContainer = Color(0xFF3B485D),
    tertiary = Color(0xFF83D5C6),
    tertiaryContainer = Color(0xFF005047),
    surface = Color(0xFF111318),
    background = Color(0xFF111318)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun XrayDroidTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}
