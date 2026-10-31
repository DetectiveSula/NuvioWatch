package app.nuviowatch.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens a title's page in Nuvio (phone or TV app) using its nuvio://meta link. */
fun openInNuvio(context: Context, contentId: String, series: Boolean) {
    val type = if (series) "series" else "movie"
    val uri = Uri.parse("nuvio://meta?type=$type&id=${Uri.encode(contentId)}")
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "Nuvio isn't installed on this device", Toast.LENGTH_SHORT).show() }
}
