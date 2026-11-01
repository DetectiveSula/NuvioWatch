package app.nuviowatch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nuviowatch.core.Season
import app.nuviowatch.core.Seasons
import java.time.LocalDate
import kotlin.math.sin
import kotlin.random.Random

/** Colours, reactions and decorations for the time of year. */
data class SeasonStyle(
    val season: Season,
    /** Live pill, progress, active tab. */
    val accent: Color,
    val onAccent: Color,
    /** A soft glow behind the hero's title, if any. */
    val glow: Color?,
    val reactions: List<String>,
) {
    /** A short line next to "Continue watching", e.g. "54 days to Christmas". */
    fun tagline(today: LocalDate = LocalDate.now()): String? = when (season) {
        Season.HALLOWEEN -> if (today.dayOfMonth == 31) "Happy Halloween" else "Spooky season"
        Season.CHRISTMAS -> Seasons.daysToChristmas(today).let { days ->
            when {
                days > 1 -> "$days days to Christmas"
                days == 1L -> "Christmas Eve"
                days == 0L -> "Merry Christmas!"
                else -> "Happy holidays"
            }
        }
        Season.EASTER -> "Happy Easter"
        Season.EID -> "Eid Mubarak"
        Season.NEW_YEAR -> "Happy New Year ${today.year}"
        Season.SPRING -> "Spring is here"
        Season.SUMMER -> "Summer nights"
        Season.AUTUMN -> "Cosy season"
        Season.EVERYDAY -> null
    }

    companion object {
        val EVERYDAY = SeasonStyle(Season.EVERYDAY, LiveRed, Color.White, null, listOf("🔥", "😂", "😱", "😭", "💤", "👏", "❤️"))

        fun of(season: Season): SeasonStyle = when (season) {
            Season.HALLOWEEN -> SeasonStyle(season, Color(0xFFFF7A1A), Color(0xFF1A0A00), Color(0x47FF7A1A), listOf("🎃", "😂", "😱", "👻", "💤", "👏", "🦇"))
            Season.CHRISTMAS -> SeasonStyle(season, Color(0xFFD62F3F), Color.White, null, listOf("🎄", "😂", "😱", "😭", "⛄", "👏", "❤️"))
            Season.EASTER -> SeasonStyle(season, Color(0xFFF2C94C), Color(0xFF2A1E00), Color(0x3DF7A8C8), listOf("🐣", "😂", "😱", "😭", "🐰", "👏", "🥚"))
            Season.EID -> SeasonStyle(season, Color(0xFFD4AF37), Color(0xFF0B1F17), Color(0x401F8A5B), listOf("🌙", "😂", "😱", "😭", "✨", "👏", "❤️"))
            Season.NEW_YEAR -> SeasonStyle(season, Color(0xFFFFD166), Color(0xFF0A0910), Color(0x402B4FD1), listOf("🎆", "😂", "😱", "😭", "🥂", "👏", "❤️"))
            Season.SPRING -> SeasonStyle(season, Color(0xFF7CE0C3), Color(0xFF0B241A), Color(0x38F7A8C8), listOf("🌸", "😂", "😱", "😭", "🌷", "👏", "❤️"))
            Season.SUMMER -> SeasonStyle(season, Color(0xFFFFB347), Color(0xFF2A1400), Color(0x40FF6B57), listOf("☀️", "😂", "😱", "😭", "🍦", "👏", "❤️"))
            Season.AUTUMN -> SeasonStyle(season, Color(0xFFE8890C), Color(0xFF1F0E00), Color(0x40E8890C), listOf("🍂", "😂", "😱", "😭", "☕", "👏", "❤️"))
            Season.EVERYDAY -> EVERYDAY
        }
    }
}

val LocalSeason = staticCompositionLocalOf { SeasonStyle.EVERYDAY }

/** The little picture next to "Nuvio Watch": a pumpkin, a Santa hat, an egg or a crescent moon. */
@Composable
fun SeasonBadge(size: Dp = 22.dp) {
    val style = LocalSeason.current
    if (style.season == Season.EVERYDAY) return
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (style.season) {
            Season.HALLOWEEN -> {
                drawOval(Color(0xFFFF7A1A), Offset(w * 0.05f, h * 0.28f), Size(w * 0.9f, h * 0.68f))
                drawLine(Color(0xFF3E7A2E), Offset(w * 0.5f, h * 0.3f), Offset(w * 0.62f, h * 0.06f), strokeWidth = w * 0.1f)
                val eyes = Path().apply {
                    moveTo(w * 0.28f, h * 0.58f); lineTo(w * 0.36f, h * 0.48f); lineTo(w * 0.44f, h * 0.58f); close()
                    moveTo(w * 0.56f, h * 0.58f); lineTo(w * 0.64f, h * 0.48f); lineTo(w * 0.72f, h * 0.58f); close()
                }
                drawPath(eyes, Color(0xFF1A0A00))
                drawArc(Color(0xFF1A0A00), 20f, 140f, false, Offset(w * 0.3f, h * 0.56f), Size(w * 0.4f, h * 0.22f), style = Stroke(w * 0.07f))
            }
            Season.CHRISTMAS -> {
                val hat = Path().apply { moveTo(w * 0.1f, h * 0.8f); quadraticBezierTo(w * 0.4f, h * 0.0f, w * 0.88f, h * 0.3f); lineTo(w * 0.72f, h * 0.8f); close() }
                drawPath(hat, Color(0xFFD62F3F))
                drawRoundRect(Color.White, Offset(w * 0.02f, h * 0.74f), Size(w * 0.8f, h * 0.22f), androidx.compose.ui.geometry.CornerRadius(h * 0.11f))
                drawCircle(Color.White, w * 0.12f, Offset(w * 0.88f, h * 0.3f))
            }
            Season.EASTER -> {
                drawOval(Color(0xFFF7A8C8), Offset(w * 0.18f, h * 0.04f), Size(w * 0.64f, h * 0.9f))
                drawLine(Color(0xFF7CE0C3), Offset(w * 0.2f, h * 0.45f), Offset(w * 0.8f, h * 0.45f), strokeWidth = h * 0.1f)
                drawLine(Color(0xFFF2C94C), Offset(w * 0.22f, h * 0.65f), Offset(w * 0.78f, h * 0.65f), strokeWidth = h * 0.08f)
            }
            Season.EID -> crescent(Offset(w * 0.45f, h * 0.5f), w * 0.42f, Color(0xFFD4AF37))
            Season.NEW_YEAR -> burst(Offset(w / 2, h / 2), w * 0.48f, Color(0xFFFFD166), 1f)
            Season.SPRING -> flower(Offset(w / 2, h / 2), w * 0.5f, Color(0xFFF7A8C8))
            Season.SUMMER -> {
                drawCircle(Color(0xFFFFB347), w * 0.24f, Offset(w / 2, h / 2))
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    drawLine(
                        Color(0xFFFFB347),
                        Offset(w / 2 + (w * 0.34f * kotlin.math.cos(a)).toFloat(), h / 2 + (h * 0.34f * kotlin.math.sin(a)).toFloat()),
                        Offset(w / 2 + (w * 0.48f * kotlin.math.cos(a)).toFloat(), h / 2 + (h * 0.48f * kotlin.math.sin(a)).toFloat()),
                        strokeWidth = w * 0.08f,
                    )
                }
            }
            Season.AUTUMN -> leaf(Offset(w / 2, h / 2), w * 0.46f, Color(0xFFE8890C), 0f)
            Season.EVERYDAY -> Unit
        }
    }
}

/** A firework: lines bursting out from the middle. */
private fun DrawScope.burst(center: Offset, r: Float, color: Color, alpha: Float) {
    for (i in 0 until 12) {
        val a = i * Math.PI / 6
        val start = Offset(center.x + (r * 0.3f * kotlin.math.cos(a)).toFloat(), center.y + (r * 0.3f * kotlin.math.sin(a)).toFloat())
        val end = Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat())
        drawLine(color.copy(alpha = alpha), start, end, strokeWidth = r * 0.12f)
    }
}

private fun DrawScope.flower(center: Offset, r: Float, color: Color) {
    for (i in 0 until 5) {
        val a = i * 2 * Math.PI / 5
        drawCircle(color, r * 0.36f, Offset(center.x + (r * 0.5f * kotlin.math.cos(a)).toFloat(), center.y + (r * 0.5f * kotlin.math.sin(a)).toFloat()))
    }
    drawCircle(Color(0xFFF2C94C), r * 0.3f, center)
}

private fun DrawScope.leaf(center: Offset, r: Float, color: Color, angle: Float) {
    rotate(angle, center) {
        val p = Path().apply {
            moveTo(center.x, center.y - r)
            quadraticBezierTo(center.x + r * 0.9f, center.y, center.x, center.y + r)
            quadraticBezierTo(center.x - r * 0.9f, center.y, center.x, center.y - r)
            close()
        }
        drawPath(p, color)
        drawLine(Color(0x66000000), Offset(center.x, center.y - r * 0.8f), Offset(center.x, center.y + r * 0.9f), strokeWidth = r * 0.08f)
    }
}

private fun DrawScope.crescent(center: Offset, r: Float, color: Color) {
    val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(center.x + r * 0.45f, center.y - r * 0.2f), r * 0.85f)) }
    drawPath(Path().apply { op(moon, bite, androidx.compose.ui.graphics.PathOperation.Difference) }, color)
}

/** Seasonal touches over the hero picture: falling snow, bats, eggs, or a moon and stars. */
@Composable
fun SeasonDecor(modifier: Modifier) {
    val style = LocalSeason.current
    if (style.season == Season.EVERYDAY) return
    val t by rememberInfiniteTransition(label = "decor").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (style.season == Season.CHRISTMAS) 14_000 else 6_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    val seeds = remember(style.season) { Random(7).let { r -> List(40) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (style.season) {
            Season.CHRISTMAS -> seeds.forEach { (x, y, s) ->
                val fall = (y + t * (0.6f + s * 0.6f)) % 1f
                val drift = sin((t * 6.28f) + s * 10f) * 6f
                drawCircle(Color.White.copy(alpha = 0.45f + s * 0.45f), 1.5f + s * 2.5f, Offset(x * w + drift, fall * h))
            }
            Season.HALLOWEEN -> {
                // Cobweb in the top corner
                val web = Color.White.copy(alpha = 0.25f)
                val corner = Offset(w, 0f)
                listOf(0.18f, 0.3f, 0.42f).forEach { k -> drawLine(web, corner, Offset(w - w * k * 1.6f, h * k * 0.9f), strokeWidth = 2f) }
                listOf(0.08f, 0.14f, 0.2f).forEach { k ->
                    drawArc(web, 90f, 90f, false, Offset(w - w * k * 2f, -h * k), Size(w * k * 4f, h * k * 2f), style = Stroke(2f))
                }
                // Two bats, bobbing gently
                listOf(Offset(0.55f, 0.24f) to 1f, Offset(0.72f, 0.18f) to 0.7f).forEachIndexed { i, (p, scale) ->
                    val bob = sin(t * 6.28f + i * 2f) * 6f
                    val cx = p.x * w
                    val cy = p.y * h + bob
                    val s = 22f * scale * density
                    val bat = Path().apply {
                        moveTo(cx - s, cy); quadraticBezierTo(cx - s * 0.6f, cy - s * 0.6f, cx - s * 0.2f, cy - s * 0.1f)
                        quadraticBezierTo(cx, cy - s * 0.4f, cx + s * 0.2f, cy - s * 0.1f)
                        quadraticBezierTo(cx + s * 0.6f, cy - s * 0.6f, cx + s, cy)
                        quadraticBezierTo(cx + s * 0.5f, cy - s * 0.15f, cx + s * 0.2f, cy + s * 0.25f)
                        quadraticBezierTo(cx, cy, cx - s * 0.2f, cy + s * 0.25f)
                        quadraticBezierTo(cx - s * 0.5f, cy - s * 0.15f, cx - s, cy); close()
                    }
                    drawPath(bat, Color(0xFF0B0710))
                }
            }
            Season.EASTER -> {
                val colors = listOf(Color(0xFFF7A8C8), Color(0xFF7CE0C3), Color(0xFFF2C94C), Color(0xFFB9A7FF))
                listOf(Offset(0.86f, 0.14f), Offset(0.93f, 0.22f), Offset(0.08f, 0.2f)).forEachIndexed { i, p ->
                    val ew = 18f * density
                    val bob = sin(t * 6.28f + i) * 3f
                    drawOval(colors[i % colors.size].copy(alpha = 0.9f), Offset(p.x * w - ew / 2, p.y * h - ew * 0.65f + bob), Size(ew, ew * 1.3f))
                }
            }
            Season.EID -> {
                crescent(Offset(w * 0.84f, h * 0.16f), 26f * density, Color(0xFFD4AF37))
                seeds.take(14).forEach { (x, y, s) ->
                    val twinkle = 0.35f + 0.65f * ((sin(t * 6.28f + s * 12f) + 1f) / 2f)
                    drawCircle(Color(0xFFFFE9A8).copy(alpha = twinkle), 1.5f + s * 2f, Offset(x * w, y * h * 0.4f))
                }
            }
            Season.NEW_YEAR -> {
                // Fireworks popping and fading in turn
                listOf(Offset(0.2f, 0.16f) to Color(0xFFFFD166), Offset(0.78f, 0.12f) to Color(0xFFFF5C7A), Offset(0.55f, 0.28f) to Color(0xFF8C9BFF)).forEachIndexed { i, (p, c) ->
                    val phase = (t + i / 3f) % 1f
                    burst(Offset(p.x * w, p.y * h), (14f + phase * 26f) * density, c, (1f - phase).coerceIn(0f, 1f))
                }
            }
            Season.SPRING -> seeds.take(18).forEach { (x, y, s) ->
                val fall = (y + t * (0.4f + s * 0.4f)) % 1f
                val drift = sin(t * 6.28f + s * 8f) * 14f
                val r = (3f + s * 3f) * density / 2f
                drawOval(Color(0xFFF7A8C8).copy(alpha = 0.55f + s * 0.4f), Offset(x * w + drift, fall * h), Size(r * 2.2f, r * 1.4f))
            }
            Season.SUMMER -> {
                drawCircle(Brush.radialGradient(listOf(Color(0x66FFB347), Color.Transparent), Offset(w * 0.86f, h * 0.1f), w * 0.45f), w * 0.45f, Offset(w * 0.86f, h * 0.1f))
                seeds.take(16).forEach { (x, y, s) ->
                    val rise = (y - t * (0.15f + s * 0.2f) + 1f) % 1f
                    drawCircle(Color(0xFFFFE0A8).copy(alpha = 0.25f + s * 0.4f), 1.5f + s * 2f, Offset(x * w, rise * h))
                }
            }
            Season.AUTUMN -> seeds.take(12).forEachIndexed { i, (x, y, s) ->
                val fall = (y + t * (0.35f + s * 0.35f)) % 1f
                val drift = sin(t * 6.28f + s * 9f) * 18f
                val colors = listOf(Color(0xFFE8890C), Color(0xFFB5541C), Color(0xFFF2C94C))
                leaf(Offset(x * w + drift, fall * h), (6f + s * 4f) * density / 2f, colors[i % 3].copy(alpha = 0.85f), t * 360f + s * 200f)
            }
            Season.EVERYDAY -> Unit
        }
    }
}
