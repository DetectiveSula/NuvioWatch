package app.nuviowatch.ui

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import app.nuviowatch.R
import app.nuviowatch.core.Profile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult

// ---------------------------------------------------------------- Theme

val Ink = Color(0xFF0A0910)
val TextPrimary = Color(0xFFF4F2FA)
val TextMuted = Color(0xFFB9B3CC)
val Glass = Color(0x0FFFFFFF)
val GlassBorder = Color(0x14FFFFFF)
val Accent = Color(0xFF8C9BFF)
val LiveRed = Color(0xFFFF5C7A)
val DefaultTint = Color(0xFF4B3A7A)

val Grotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

@Composable
fun NuvioWatchTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Accent,
        onPrimary = Ink,
        secondary = Color(0xFFC3CAFF),
        background = Ink,
        onBackground = TextPrimary,
        surface = Ink,
        onSurface = TextPrimary,
        surfaceVariant = Color(0xFF1C1A26),
        onSurfaceVariant = TextMuted,
        surfaceContainer = Color(0xFF15131D),
        surfaceContainerHigh = Color(0xFF1C1A26),
        outline = Color(0xFF4A4658),
        error = Color(0xFFFF8AA0),
    )
    val base = Typography()
    val typography = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Grotesk, fontWeight = FontWeight.Bold),
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        // Screens draw straight on the background (no Surface), so set the default text colour here.
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}

/** Big display numbers and titles. */
fun display(size: Int, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = Grotesk, fontWeight = weight, fontSize = size.sp, letterSpacing = (-size / 28f).sp, lineHeight = (size * 1.05f).sp)

/** Small spaced-out capitals, e.g. "NOW PLAYING". */
val Eyebrow = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

fun parseColor(hex: String, fallback: Color = Accent): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** A profile colour light enough to see on the dark background. */
fun visibleOnDark(color: Color): Color =
    if (color.luminance() < 0.2f) lerp(color, Color.White, 0.5f - color.luminance()) else color

// ---------------------------------------------------------------- Poster colour

/** The main colour of an image, for tinting the screen; animates when the image changes. */
@Composable
fun rememberImageTint(url: String?, fallback: Color = DefaultTint): Color {
    val context = LocalContext.current
    var tint by remember { mutableStateOf(fallback) }
    androidx.compose.runtime.LaunchedEffect(url) {
        if (url == null) {
            tint = fallback
            return@LaunchedEffect
        }
        val result = coil.Coil.imageLoader(context).execute(
            ImageRequest.Builder(context).data(url).allowHardware(false).size(96).build(),
        )
        val bitmap = (result as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
        val palette = Palette.from(bitmap).generate()
        val picked = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch
        if (picked != null) tint = Color(picked.rgb)
    }
    val animated by animateColorAsState(tint, tween(900), label = "tint")
    return animated
}

/** Soft glow of [tint] behind the top of a screen. */
fun Modifier.glow(tint: Color, alpha: Float = 0.55f): Modifier = drawBehind {
    val radius = size.width * 1.1f
    drawRect(
        Brush.radialGradient(
            colors = listOf(tint.copy(alpha = alpha), Color.Transparent),
            center = Offset(size.width / 2, -radius * 0.15f),
            radius = radius,
        ),
    )
}

// ---------------------------------------------------------------- Building blocks

@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassBorder, shape)
            .padding(padding),
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = Eyebrow, color = TextMuted)
    Spacer(Modifier.height(12.dp))
}

@Composable
fun LiveDot(size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Canvas(Modifier.size(size * 2.4f)) {
        val r = size.toPx() / 2
        drawCircle(LiveRed.copy(alpha = (1f - pulse) * 0.6f), radius = r + r * 1.4f * pulse)
        drawCircle(LiveRed, radius = r)
    }
}

@Composable
fun ProfileAvatar(profile: Profile, size: Dp = 40.dp, ring: Color? = null) {
    val ringModifier = if (ring != null) Modifier.border(2.dp, ring, CircleShape).padding(3.dp) else Modifier
    Box(ringModifier) {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(parseColor(profile.avatarColorHex)),
            contentAlignment = Alignment.Center,
        ) {
            if (!profile.avatarUrl.isNullOrBlank()) {
                AsyncImage(model = profile.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            } else {
                Text(
                    profile.name.take(1).uppercase().ifEmpty { "?" },
                    color = Color.White,
                    style = display((size.value * 0.42f).toInt()),
                )
            }
        }
    }
}

@Composable
fun Poster(url: String?, width: Dp = 56.dp, corner: Dp = 10.dp) {
    Box(Modifier.width(width).height(width * 1.5f).clip(RoundedCornerShape(corner)).background(Color(0xFF221F2E))) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = if (filled) Ink else color,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun ThinProgress(fraction: Float, color: Color = Color.White, height: Dp = 4.dp, track: Color = Color(0x33FFFFFF)) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

/** Simple bar chart; [labels] are drawn under the first, middle and last bars. */
@Composable
fun BarChart(values: List<Long>, labels: Triple<String, String, String>) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column {
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val slot = size.width / values.size
            val barWidth = slot * 0.66f
            values.forEachIndexed { i, v ->
                val h = if (v == 0L) 3f else (v.toFloat() / max) * size.height
                drawRoundRect(
                    color = if (v == 0L) Accent.copy(alpha = 0.18f) else Accent,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 3, barWidth / 3),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(labels.first, labels.second, labels.third).forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}

// ---------------------------------------------------------------- Floating tab bar

data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun FloatingNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xE61A1724))
            .border(1.dp, Color(0x1AFFFFFF), shape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { i, item ->
            val active = i == selected
            Row(
                Modifier
                    .weight(if (active) 1.6f else 1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { contentDescription = item.label },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, contentDescription = null, tint = if (active) Ink else Color(0xFFD6D1E6), modifier = Modifier.size(20.dp))
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Text(item.label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Formatting

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        hours >= 100 -> "${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

fun formatClock(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun timeAgo(atMs: Long, nowMs: Long): String =
    if (nowMs - atMs < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(atMs, nowMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
