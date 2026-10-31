package app.nuviowatch.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuviowatch.core.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

val Ink = Color(0xFF0A0910)
val TextPrimary = Color(0xFFF4F2FA)
val TextMuted = Color(0xFFB9B3CC)
val Glass = Color(0x0FFFFFFF)
val GlassBorder = Color(0x14FFFFFF)
val Accent = Color(0xFF8C9BFF)
val LiveRed = Color(0xFFFF5C7A)
val Gold = Color(0xFFFFD166)
val Mint = Color(0xFF7CE0C3)

val Grotesk = FontFamily(
    Font("space_grotesk_medium.ttf", FontWeight.Medium),
    Font("space_grotesk_bold.ttf", FontWeight.Bold),
)

@Composable
fun NuvioTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Accent, onPrimary = Ink, background = Ink, onBackground = TextPrimary,
        surface = Color(0xFF15131D), onSurface = TextPrimary, surfaceVariant = Color(0xFF1C1A26), onSurfaceVariant = TextMuted,
        outline = Color(0xFF4A4658), error = Color(0xFFFF8AA0),
    )
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}

fun display(size: Int, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = Grotesk, fontWeight = weight, fontSize = size.sp, letterSpacing = (-size / 28f).sp, lineHeight = (size * 1.08f).sp)

val Eyebrow = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Glass).border(1.dp, GlassBorder, shape).padding(padding), content = content)
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = Eyebrow, color = TextMuted, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
fun ThinProgress(fraction: Float, color: Color = Color.White, height: Dp = 4.dp, track: Color = Color(0x33FFFFFF)) {
    Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

// ---------------------------------------------------------------- Images from the web

object Images {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                Model.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.bytes() ?: return@use null
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }.getOrNull()?.also { cache[url] = it }
        }
    }
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { Images.load(it) } }
    Box(modifier.background(Color(0xFF221F2E))) {
        image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize()) }
    }
}

@Composable
fun Poster(url: String?, width: Dp = 56.dp, corner: Dp = 10.dp) {
    RemoteImage(url, Modifier.width(width).height(width * 1.5f).clip(RoundedCornerShape(corner)))
}

fun parseColor(hex: String): Color = runCatching {
    val clean = hex.removePrefix("#")
    Color((if (clean.length == 6) "FF$clean" else clean).toLong(16))
}.getOrDefault(Accent)

@Composable
fun Avatar(profile: Profile?, size: Dp = 40.dp, fallbackName: String = "?") {
    val color = profile?.let { parseColor(it.avatarColorHex) } ?: Accent
    val url = profile?.avatarUrl
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (url != null) {
            RemoteImage(url, Modifier.matchParentSize())
        } else {
            Text((profile?.name ?: fallbackName).take(1).uppercase(), style = display((size.value * 0.42f).toInt()), color = Color.White)
        }
    }
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        hours >= 100 -> "${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

fun timeAgo(at: Long, now: Long): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        minutes < 7 * 24 * 60 -> "${minutes / 1440}d ago"
        else -> java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    }
}
