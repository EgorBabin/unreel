package app.unreel.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object UnreelColors {
    val Bg = Color(0xFF0B0A17)
    val Surface = Color(0x0DFFFFFF)
    val Border = Color(0x14FFFFFF)
    val Text = Color(0xFFF1EFFF)
    val Muted = Color(0xFF9D99C2)
    val Violet = Color(0xFF8B7BFF)
    val Blue = Color(0xFF5B8CFF)
    val Cyan = Color(0xFF22D3EE)
    val Danger = Color(0xFFFF6B8B)
    val Warning = Color(0xFFFFC266)

    val Gradient = Brush.linearGradient(listOf(Violet, Blue, Cyan))
}

private val scheme = darkColorScheme(
    primary = UnreelColors.Violet,
    onPrimary = UnreelColors.Bg,
    secondary = UnreelColors.Cyan,
    background = UnreelColors.Bg,
    onBackground = UnreelColors.Text,
    surface = UnreelColors.Bg,
    onSurface = UnreelColors.Text,
    onSurfaceVariant = UnreelColors.Muted,
    outline = UnreelColors.Border,
    error = UnreelColors.Danger,
)

@Composable
fun UnreelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
