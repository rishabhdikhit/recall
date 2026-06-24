package app.recall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Nothing-OS-inspired design system: a true-black brutalist canvas, monochrome ink, a single red
 * "signal" accent used sparingly, and translucent "glass" panels with hairline borders.
 *
 * Glass here is layered alpha (not a Gaussian backdrop blur): real blur needs RenderEffect (API 31+),
 * but translucent fill + hairline border + the dotted backdrop reads as frosted on every device
 * (minSdk 24). A true-blur upgrade via the Haze library is a clean follow-up for API 31+ only.
 */

// --- Palette ---
val Ink = Color(0xFF000000)          // true-black canvas (OLED + Nothing feel)
val Paper = Color(0xFFF2F2F2)        // primary "ink on paper" text
val Ash = Color(0xFF7A7A7A)          // muted / secondary
val Signal = Color(0xFFD71921)       // Nothing red — accents, active states, counts (sparing)
val Hairline = Color(0x16FFFFFF)     // ~9% white exposed-grid border
val GlassFill = Color(0x0DFFFFFF)    // ~5% white frosted panel
val GlassStrong = Color(0x14FFFFFF)  // slightly denser glass (bars, active)
val Dot = Color(0x0AFFFFFF)          // dotted backdrop

/** Translucent frosted panel: glass fill + hairline border, hard-ish brutalist corners. */
fun Modifier.glass(corner: Int = 6, fill: Color = GlassFill): Modifier = this
    .clip(RoundedCornerShape(corner.dp))
    .background(fill)
    .border(1.dp, Hairline, RoundedCornerShape(corner.dp))

/** Nothing-style dotted grid backdrop, drawn full-bleed behind content. */
fun Modifier.dotGrid(color: Color = Dot, step: Float = 38f, radius: Float = 1.5f): Modifier =
    this.drawBehind {
        var y = step
        while (y < size.height) {
            var x = step
            while (x < size.width) {
                drawCircle(color, radius = radius, center = Offset(x, y))
                x += step
            }
            y += step
        }
    }

/** Brutalist technical label: uppercase monospace, wide tracking. */
@Composable
fun SectionLabel(text: String, color: Color = Ash, size: Int = 11) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp,
    )
}
