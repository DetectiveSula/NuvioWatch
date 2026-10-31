package app.nuviowatch.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A newer release on GitHub. */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

/** Checks the app's GitHub releases for a newer build and downloads it. */
class Updates(
    private val http: OkHttpClient,
    private val repo: String = "DetectiveSula/NuvioWatch",
    private val apiBase: String = "https://api.github.com",
) {
    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String = "",
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String = "", @SerialName("browser_download_url") val url: String = "")

    /** Null when this build is the newest (or GitHub can't be reached). */
    suspend fun check(currentVersionCode: Int, assetSuffix: String = ".apk"): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("$apiBase/repos/$repo/releases/latest").header("Accept", "application/vnd.github+json").build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string().orEmpty(), currentVersionCode, assetSuffix)
            }
        }.getOrNull()
    }

    suspend fun download(info: UpdateInfo, into: File): File = withContext(Dispatchers.IO) {
        into.parentFile?.mkdirs()
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Download failed (${response.code})")
            into.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
        into
    }

    companion object {
        /** Release tags are "v1.0.<build number>", and the build number is the app's versionCode. */
        fun parse(json: String, currentVersionCode: Int, assetSuffix: String = ".apk"): UpdateInfo? {
            val release = NuvioClient.json.decodeFromString<Release>(json)
            val code = release.tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (code <= currentVersionCode) return null
            val apk = release.assets.firstOrNull { it.name.endsWith(assetSuffix) } ?: return null
            return UpdateInfo(code, release.tag.removePrefix("v"), apk.url, release.body.orEmpty().trim())
        }
    }
}
