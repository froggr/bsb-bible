package ca.lampandlight

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/* Warm ink on aged paper; lamp-gold for structure, indigo for original-language data.
   Scripture is read for long stretches, so the paper is never pure white and the ink is
   never pure black — both raise contrast past comfort at length. */

val Paper = Color(0xFFF7F3EA)
val PaperSunk = Color(0xFFEFE9DC)
val Ink = Color(0xFF2B2622)
val InkSoft = Color(0xFF6D6459)
val Rule = Color(0xFFDDD4C3)
val Gold = Color(0xFFA8762C)
val Indigo = Color(0xFF4A5878)

val NightPaper = Color(0xFF15161A)
val NightSunk = Color(0xFF1D1F24)
val NightInk = Color(0xFFE6E0D4)
val NightInkSoft = Color(0xFF9A9287)
val NightRule = Color(0xFF2E3138)
val NightGold = Color(0xFFD9A84E)
val NightIndigo = Color(0xFF8FA2CC)

/** Highlight colours, light and dark. Text goes dark on all of them. */
enum class MarkColor(val light: Color, val dark: Color) {
    Gold(Color(0xFFF6E3AD), Color(0xFF6B5A2A)),
    Rose(Color(0xFFF3D3D3), Color(0xFF6E3B3B)),
    Sage(Color(0xFFD6E6CF), Color(0xFF3F5A3A)),
    Sky(Color(0xFFD3E2F3), Color(0xFF39506B));

    fun color(dark: Boolean) = if (dark) this.dark else this.light

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name.equals(name, true) } ?: Gold
    }
}

/* The faces ship with the app rather than being downloaded. The downloadable-font provider
   needs Google Play Services, which is missing on an emulator and on de-Googled Android —
   there the text silently falls back to the system sans, which is the wrong look entirely.
   Newsreader and Cormorant Garamond are variable fonts, so one file covers every weight. */

val ReadingFont = FontFamily(
    Font(R.font.newsreader, FontWeight.Light),
    Font(R.font.newsreader, FontWeight.Normal),
    Font(R.font.newsreader, FontWeight.Medium),
    Font(R.font.newsreader_italic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic),
)

val DisplayFont = FontFamily(
    Font(R.font.cormorant, FontWeight.Normal),
    Font(R.font.cormorant, FontWeight.Medium),
    Font(R.font.cormorant, FontWeight.SemiBold),
    Font(R.font.cormorant, FontWeight.Bold),
)

val UiFont = FontFamily(
    Font(R.font.plexsans, FontWeight.Normal),
    Font(R.font.plexsans, FontWeight.Medium),
    Font(R.font.plexsans, FontWeight.SemiBold),
)

val MonoFont = FontFamily(
    Font(R.font.plexmono, FontWeight.Normal),
    Font(R.font.plexmono_medium, FontWeight.Medium),
)

class Palette(
    val paper: Color, val sunk: Color, val ink: Color, val inkSoft: Color,
    val rule: Color, val gold: Color, val indigo: Color, val dark: Boolean,
)

val LocalPalette = staticCompositionLocalOf {
    Palette(Paper, PaperSunk, Ink, InkSoft, Rule, Gold, Indigo, false)
}

/** How large the reading text is; the one setting people actually change. */
val LocalTextScale = staticCompositionLocalOf { 1f }

@Composable
fun BibleTheme(dark: Boolean = isSystemInDarkTheme(), scale: Float = 1f, content: @Composable () -> Unit) {
    val p = if (dark)
        Palette(NightPaper, NightSunk, NightInk, NightInkSoft, NightRule, NightGold, NightIndigo, true)
    else
        Palette(Paper, PaperSunk, Ink, InkSoft, Rule, Gold, Indigo, false)

    val colors = if (dark) darkColorScheme(
        primary = p.gold, background = p.paper, surface = p.paper,
        onBackground = p.ink, onSurface = p.ink, surfaceVariant = p.sunk,
        onSurfaceVariant = p.inkSoft, outline = p.rule, secondary = p.indigo,
    ) else lightColorScheme(
        primary = p.gold, background = p.paper, surface = p.paper,
        onBackground = p.ink, onSurface = p.ink, surfaceVariant = p.sunk,
        onSurfaceVariant = p.inkSoft, outline = p.rule, secondary = p.indigo,
    )

    CompositionLocalProvider(LocalPalette provides p, LocalTextScale provides scale) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
