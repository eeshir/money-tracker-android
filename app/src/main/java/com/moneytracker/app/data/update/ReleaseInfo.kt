package com.moneytracker.app.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published release the app could update to. */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 of the APK; an update without one is refused. */
    val sha256: String?
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
internal data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
    val digest: String? = null
)

/** Pure rules: which release is newer, and which of its files is this phone's APK. */
object UpdateRules {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** "v2.49.0", "2.49.0-debug" → [2, 49, 0]; null when there are no numbers. */
    fun parseVersion(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() }
    }

    /** True when [candidate] is a higher version than [current]; unreadable versions are never "newer". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Reads GitHub's "latest release" reply. [abis] are this phone's ABIs, best first: a universal APK is
     * preferred, then one built for the first matching ABI. The F-Droid build is never offered.
     */
    fun parse(body: String, abis: List<String>): ReleaseInfo? {
        val release = runCatching { json.decodeFromString<GitHubRelease>(body) }.getOrNull() ?: return null
        if (release.draft || release.prerelease || release.tagName.isBlank()) return null
        val apks = release.assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("fdroid", ignoreCase = true)
        }
        val apk = apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.contains(abi, ignoreCase = true) } }
            ?: return null
        val sidecar = release.assets.firstOrNull { it.name.equals(apk.name + ".sha256", ignoreCase = true) }
        val digest = apk.digest?.removePrefix("sha256:")?.trim()?.lowercase()?.takeIf { it.matches(HEX64) }
        return ReleaseInfo(
            version = release.tagName.removePrefix("v").removePrefix("V"),
            notes = release.body.orEmpty().trim(),
            apkName = apk.name,
            apkUrl = apk.url,
            apkSize = apk.size,
            sha256 = digest ?: sidecar?.let { "sidecar:${it.url}" }
        )
    }

    /** The hash inside a `.sha256` file ("<hex>  <name>"), lower-cased; null when it isn't one. */
    fun parseSha256File(text: String): String? =
        text.trim().split(' ', '\t', '\r', '\n').firstOrNull()?.lowercase()?.takeIf { it.matches(HEX64) }

    private val HEX64 = Regex("[0-9a-f]{64}")
}
