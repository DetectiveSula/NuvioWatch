package app.nuviowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Values produced by the web dashboard's Web Crypto code (docs/index.html) for the same account. */
class InteropTest {
    @Test
    fun browserAndAppAgree() {
        val channel = LiveChannel("user-1")
        val db = FamilyDb("https://example.invalid", "pk", "user-1")
        assertEquals("nw-82e7392ac616d2b49dadbf3f003ad9dd", channel.topic)
        assertEquals("814c7b421458cab828a2d13be8c2b0b27e13dd3e6685826262a63be3a3d5382f", db.familyKey)
        assertEquals("c1d565416792758a41637fb9fa5ed1e49ac4b3984426d67a17d31038cb84a0d1", db.family)
        val fromBrowser = "BwcHBwcHBwcHBwcH6V0iKjhXhxvFSKsrJZSuzSDlCjdMZEWRiDzt2z7yDgQ1WJpGvwHPNlY2a9gA1u3l9HU="
        assertEquals("""{"type":"reaction","emoji":"👏"}""", channel.decryptText(fromBrowser))
    }
}
