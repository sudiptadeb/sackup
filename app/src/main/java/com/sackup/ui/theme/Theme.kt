package com.sackup.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Tertiary is used as the app's "success / warning-ish" accent: a calm green pair
// for "everything is safe" cards and a warm amber for "needs attention" rows.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF546E7A),
    tertiary = Color(0xFFE65100),                // amber-orange: "not backed up yet", warnings
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8E6C9),       // soft green: success card background
    onTertiaryContainer = Color(0xFF1B5E20),
    surface = Color(0xFFFAFAFA),
    background = Color(0xFFFAFAFA),
    error = Color(0xFFD32F2F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF90A4AE),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF3E2723),
    tertiaryContainer = Color(0xFF1B5E20),
    onTertiaryContainer = Color(0xFFC8E6C9),
    surface = Color(0xFF121212),
    background = Color(0xFF121212),
    error = Color(0xFFEF9A9A),
)

/** Text/icon colour to draw on top of [ColorScheme.scrim] overlays (always white). */
val ColorScheme.onScrim: Color get() = Color.White

/** Walks up ContextWrapper chain to find the hosting Activity, or null. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun SackUpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // MainActivity calls enableEdgeToEdge(); we only pick icon contrast here.
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
