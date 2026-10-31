package app.nuviowatch.desktop

import app.nuviowatch.core.UpdateInfo
import app.nuviowatch.core.Updates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

/**
 * Checks GitHub for a newer Mac app every 3 hours, sends a notification once per version,
 * and can install it: downloads the .dmg, swaps the app in Applications and reopens it.
 */
object MacUpdater {
    private val updates = Updates(Model.http)
    val available = MutableStateFlow<UpdateInfo?>(null)
    val message = MutableStateFlow<String?>(null)

    fun start() = Model.scope.launch {
        delay(20_000)
        while (isActive) {
            check()
            delay(3 * 3_600_000L)
        }
    }

    suspend fun check(manual: Boolean = false) {
        if (BuildInfo.RUN == 0) return // a development build
        val info = updates.check(BuildInfo.RUN, ".dmg")
        available.value = info
        if (info == null) {
            if (manual) message.value = "You're up to date (${BuildInfo.VERSION})."
            return
        }
        message.value = null
        val key = "update|${info.versionName}"
        if (key !in Model.store.saved.value.announced) {
            Model.notify("Nuvio Watch ${info.versionName} is ready", "Open Nuvio Watch → Settings and click Install update.")
            Model.store.update { it.copy(announced = it.announced + key) }
        }
    }

    /** Downloads the new version and replaces this app with it, then reopens. */
    fun install() = Model.scope.launch {
        val info = available.value ?: return@launch
        val appPath = System.getProperty("jpackage.app-path")?.let { File(it) }
            ?.let { generateSequence(it) { f -> f.parentFile }.firstOrNull { f -> f.name.endsWith(".app") } }
        if (appPath == null) {
            message.value = "Can't update a development build."
            return@launch
        }
        message.value = "Downloading ${info.versionName}…"
        val dmg = runCatching { updates.download(info, File(Model.store.dir, "update/NuvioWatch.dmg")) }.getOrElse {
            message.value = "Download failed: ${it.message}"
            return@launch
        }
        message.value = "Installing…"
        val pid = ProcessHandle.current().pid()
        val target = appPath.absolutePath
        // Runs after this app quits: mount the new version, swap it in, reopen.
        val script = File(Model.store.dir, "update/install.sh")
        script.writeText(
            """
            #!/bin/bash
            while kill -0 $pid 2>/dev/null; do sleep 0.5; done
            MOUNT=${'$'}(mktemp -d)
            hdiutil attach -nobrowse -noverify -mountpoint "${'$'}MOUNT" "${dmg.absolutePath}" || exit 1
            NEW=${'$'}(ls -d "${'$'}MOUNT"/*.app | head -1)
            rm -rf "$target.old"
            mv "$target" "$target.old" && ditto "${'$'}NEW" "$target" && rm -rf "$target.old" || { rm -rf "$target"; mv "$target.old" "$target"; }
            hdiutil detach "${'$'}MOUNT" -quiet
            xattr -dr com.apple.quarantine "$target" 2>/dev/null
            open "$target"
            """.trimIndent(),
        )
        script.setExecutable(true)
        ProcessBuilder("/bin/bash", script.absolutePath).redirectErrorStream(true)
            .redirectOutput(File(Model.store.dir, "update/install.log")).start()
        delay(500)
        exitProcess(0)
    }
}
