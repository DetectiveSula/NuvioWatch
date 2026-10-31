package app.nuviowatch.desktop

import java.io.File

/** Starts Nuvio Watch when you log in to the Mac (a per-user launch agent), and retires the old Terminal helper. */
object LoginItem {
    private val agents = File(System.getProperty("user.home"), "Library/LaunchAgents")
    private val plist = File(agents, "app.nuviowatch.desktop.plist")
    private val oldHelper = File(agents, "app.nuviowatch.helper.plist")

    /** The app's own launcher, when running as an installed app. */
    private val appPath: String? get() = System.getProperty("jpackage.app-path")

    fun set(enabled: Boolean) {
        runCatching {
            if (!enabled) {
                plist.delete()
                return
            }
            val path = appPath ?: return
            agents.mkdirs()
            plist.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key><string>app.nuviowatch.desktop</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>${path.replace("&", "&amp;").replace("<", "&lt;")}</string>
                        <string>--background</string>
                    </array>
                    <key>RunAtLoad</key><true/>
                    <key>ProcessType</key><string>Interactive</string>
                </dict>
                </plist>
                """.trimIndent(),
            )
        }
    }

    /** The Terminal helper did the same job; the app takes over its connection to Nuvio Desktop. */
    fun removeOldHelper(): Boolean {
        if (!oldHelper.exists()) return false
        runCatching {
            val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
            ProcessBuilder("launchctl", "bootout", "gui/$uid", oldHelper.path).start().waitFor()
        }
        oldHelper.delete()
        File(System.getProperty("user.home"), "Library/Application Support/NuvioWatch").deleteRecursively()
        return true
    }
}
