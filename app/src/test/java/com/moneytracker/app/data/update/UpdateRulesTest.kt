package com.moneytracker.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val hash = "a".repeat(64)

    @Test fun `newer versions compare numerically not as text`() {
        assertTrue(UpdateRules.isNewer("v2.50.0", "2.49.0"))
        assertTrue(UpdateRules.isNewer("2.49.10", "2.49.9"))
        assertTrue(UpdateRules.isNewer("3.0", "2.99.99"))
        assertFalse(UpdateRules.isNewer("2.49.0", "2.49.0-debug"))
        assertFalse(UpdateRules.isNewer("2.48.8", "2.49.0"))
        assertFalse(UpdateRules.isNewer("nightly", "2.49.0"))
    }

    @Test fun `picks the universal apk, never fdroid, and takes the hash from the digest`() {
        val body = """{"tag_name":"v2.50.0","body":"notes","assets":[
            {"name":"app-fdroid-v2.50.0.apk","browser_download_url":"https://x/f.apk","size":1},
            {"name":"app-v2.50.0-arm64-v8a.apk","browser_download_url":"https://x/a.apk","size":2},
            {"name":"app-v2.50.0-universal.apk","browser_download_url":"https://x/u.apk","size":3,"digest":"sha256:$hash"}]}"""
        val r = UpdateRules.parse(body, listOf("arm64-v8a"))!!
        assertEquals("2.50.0", r.version)
        assertEquals("https://x/u.apk", r.apkUrl)
        assertEquals(hash, r.sha256)
    }

    @Test fun `falls back to the phone abi and to a sha256 sidecar file`() {
        val body = """{"tag_name":"v2.50.0","assets":[
            {"name":"app-arm64-v8a.apk","browser_download_url":"https://x/a.apk"},
            {"name":"app-arm64-v8a.apk.sha256","browser_download_url":"https://x/a.sha"}]}"""
        val r = UpdateRules.parse(body, listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals("sidecar:https://x/a.sha", r.sha256)
    }

    @Test fun `drafts, prereleases, junk and releases without a usable apk give nothing`() {
        assertNull(UpdateRules.parse("""{"tag_name":"v9","draft":true,"assets":[]}""", emptyList()))
        assertNull(UpdateRules.parse("""{"tag_name":"v9","prerelease":true,"assets":[]}""", emptyList()))
        assertNull(UpdateRules.parse("not json", emptyList()))
        assertNull(UpdateRules.parse("""{"tag_name":"v9","assets":[{"name":"notes.txt","browser_download_url":"u"}]}""", emptyList()))
    }

    @Test fun `reads a sha256 file`() {
        assertEquals(hash, UpdateRules.parseSha256File("${hash.uppercase()}  app.apk\n"))
        assertNull(UpdateRules.parseSha256File("garbage"))
    }
}
