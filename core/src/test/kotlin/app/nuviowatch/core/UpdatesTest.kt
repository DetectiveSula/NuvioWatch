package app.nuviowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatesTest {
    private val json = """{"tag_name":"v1.0.30","body":"New: TV reactions","assets":[
        {"name":"NuvioWatch-Mac.zip","browser_download_url":"https://x/mac.zip"},
        {"name":"NuvioWatch.apk","browser_download_url":"https://x/app.apk"}]}"""

    @Test
    fun newerReleaseIsOffered() {
        val info = Updates.parse(json, currentVersionCode = 26)!!
        assertEquals(30, info.versionCode)
        assertEquals("1.0.30", info.versionName)
        assertEquals("https://x/app.apk", info.apkUrl)
        assertEquals("New: TV reactions", info.notes)
    }

    @Test
    fun sameOrOlderIsIgnored() {
        assertNull(Updates.parse(json, currentVersionCode = 30))
        assertNull(Updates.parse(json, currentVersionCode = 31))
        assertNull(Updates.parse("""{"tag_name":"v1.0.40","assets":[]}""", 26), "no APK attached yet")
    }
}
