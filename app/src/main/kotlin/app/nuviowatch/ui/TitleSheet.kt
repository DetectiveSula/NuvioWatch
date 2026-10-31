@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.nuviowatch.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.nuviowatch.core.Fun
import app.nuviowatch.core.Social
import app.nuviowatch.core.episodeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens the title sheet for a title; provided by the main screen. */
val LocalOpenTitle = compositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

/** Everything you can do with a title: open it, suggest it, rate it, and talk about it without spoilers. */
@Composable
fun TitleSheet(state: UiState, vm: MainViewModel, open: OpenTitle) {
    val context = LocalContext.current
    val info = state.titles[open.contentId]
    val name = info?.name ?: open.contentId
    val me = state.device.profileIndex
    val score = Social.familyScore(open.contentId, state.social.ratings)
    val myStars = score?.second?.get(me)
    val reached = me?.let { Fun.latestEpisode(state.viewings, it, open.contentId) }
    val (comments, hidden) = Social.visibleComments(state.social.comments, open.contentId, reached)

    ModalBottomSheet(onDismissRequest = { vm.closeTitle() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF15131D)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(info?.poster, width = 72.dp, corner = 12.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = display(24), maxLines = 3)
                    Text(listOfNotNull(info?.year, info?.genres?.take(3)?.joinToString(", ")).joinToString(" · "), color = TextMuted, fontSize = 13.sp)
                    if (score != null) {
                        Text("⭐ %.1f family score · %d rating%s".format(score.first, score.second.size, if (score.second.size == 1) "" else "s"), color = Color(0xFFFFD166), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { openInNuvio(context, open.contentId, open.series) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("▶ Open in Nuvio") }

            // Rating
            SheetLabel("Your rating")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { n ->
                    Text(
                        if (myStars != null && n <= myStars) "★" else "☆",
                        fontSize = 34.sp,
                        color = Color(0xFFFFD166),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "$n stars") { vm.rate(open.contentId, n) }.padding(2.dp),
                    )
                }
            }
            score?.second?.let { all ->
                Text(all.entries.joinToString(" · ") { (p, s) -> "${state.profileName(p)} ${"★".repeat(s)}" }, fontSize = 12.sp, color = TextMuted)
            }
            if (me == null) Text("Set who you are in Settings → This device to rate.", fontSize = 12.sp, color = TextMuted)

            // Suggest
            SheetLabel("Suggest to")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.snapshot.profiles.filter { it.index != me }.forEach { p ->
                    val already = state.viewings.any { it.profileIndex == p.index && it.contentId == open.contentId }
                    Text(
                        "💡 ${p.name}" + if (already) " (seen)" else "",
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF))
                            .clickable(role = Role.Button) { vm.suggest(p.index, open.contentId, open.series) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            state.social.message?.let { Text(it, fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 6.dp)) }

            // Comments
            SheetLabel("Chat")
            if (comments.isEmpty() && hidden == 0) Text("No comments yet. Start the conversation!", color = TextMuted, fontSize = 13.sp)
            comments.takeLast(30).forEach { c ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        listOfNotNull(c.fromName, episodeLabel(c.season, c.episode), timeAgo(c.at, state.clock)).joinToString(" · "),
                        fontSize = 12.sp,
                        color = TextMuted,
                    )
                    Text(c.text, fontSize = 15.sp)
                }
            }
            if (hidden > 0) {
                Text("🔒 $hidden comment${if (hidden == 1) "" else "s"} from further ahead, hidden until you catch up", fontSize = 12.sp, color = Color(0xFFFFD166), modifier = Modifier.padding(vertical = 6.dp))
            }
            var draft by rememberSaveable(open.contentId) { mutableStateOf("") }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (reached != null) "Say something about ${episodeLabel(reached.first, reached.second)}…" else "Say something…") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.comment(open.contentId, draft); draft = "" }, enabled = draft.isNotBlank()) { Text("Send") }
            if (open.series) {
                Text("Comments are tagged with the episode you're on, so they stay hidden from anyone behind you.", fontSize = 11.sp, color = TextMuted)
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text.uppercase(), style = Eyebrow, color = TextMuted)
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------- Share as an image

/** Wraps [content] so it can be captured as an image with [ShareHandle.share]. */
class ShareHandle(val layer: GraphicsLayer)

@Composable
fun rememberShareHandle(): ShareHandle {
    val layer = rememberGraphicsLayer()
    return remember(layer) { ShareHandle(layer) }
}

fun Modifier.capturable(handle: ShareHandle): Modifier = drawWithContent {
    handle.layer.record { this@drawWithContent.drawContent() }
    drawLayer(handle.layer)
}

/** A share button that sends whatever [handle] has drawn as a PNG. */
@Composable
fun ShareButton(handle: ShareHandle, label: String = "Share", text: String = "From Nuvio Watch", color: Color = Color.White) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        scope.launch {
            val bitmap = handle.layer.toImageBitmap().asAndroidBitmap()
            shareBitmap(context, bitmap, text)
        }
    }) { Text("↗ $label", color = color, fontWeight = FontWeight.SemiBold) }
}

private suspend fun shareBitmap(context: Context, bitmap: Bitmap, text: String) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "share").apply { mkdirs() }.resolve("nuvio-watch.png").also { f ->
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
