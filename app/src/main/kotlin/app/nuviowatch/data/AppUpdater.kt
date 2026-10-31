package app.nuviowatch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.nuviowatch.BuildConfig
import app.nuviowatch.core.UpdateInfo
import app.nuviowatch.graph
import java.io.File

/** Downloads a newer release from GitHub and opens Android's installer for it. */
object AppUpdater {
    suspend fun check(context: Context): UpdateInfo? = context.graph.updates.check(BuildConfig.VERSION_CODE)

    /** Android asks once per app before it can install updates; this opens that screen. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val apk = context.graph.updates.download(info, File(context.cacheDir, "updates/NuvioWatch.apk"))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
