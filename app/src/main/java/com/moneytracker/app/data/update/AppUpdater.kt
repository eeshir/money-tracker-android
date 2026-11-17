package com.moneytracker.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.moneytracker.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Where the update stands, for the Settings row and the badge on Home. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val reason: Reason) : UpdateState

    enum class Reason { NETWORK, NO_RELEASE, NO_HASH, BAD_HASH, WRONG_APP, WRONG_SIGNATURE }
}

/**
 * Updates the app from the project's GitHub releases. It only ever reads the public releases
 * of [RELEASES_REPO]; nothing about the person or the phone is sent. A download is installed only
 * after it matches its published SHA-256 and is the same app signed with the same key, and Android
 * still asks the person to confirm the install: an app cannot update itself silently.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val lock = Mutex()
    private val client = HttpClient(Android) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }
    private var lastCheckMillis = 0L

    /** Whether this build checks for updates: the standard flavor. F-Droid updates through F-Droid. A debug build has another app id, so its install is refused by the identity check. */
    val canSelfUpdate: Boolean get() = BuildConfig.FLAVOR == "standard"

    /** Looks for a newer release. [force] ignores the once-every-six-hours limit used by the automatic check. */
    suspend fun check(force: Boolean = false) {
      lock.withLock {
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.ReadyToInstall) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMillis < CHECK_EVERY_MILLIS) return
        lastCheckMillis = now
        _state.value = UpdateState.Checking
        _state.value = try {
            val body = withContext(Dispatchers.IO) {
                client.get("https://api.github.com/repos/$RELEASES_REPO/releases/latest") {
                    header("Accept", "application/vnd.github+json")
                    header("User-Agent", "money-tracker-android-updater")
                }.bodyAsText()
            }
            val release = UpdateRules.parse(body, Build.SUPPORTED_ABIS.toList())
            when {
                release == null -> UpdateState.UpToDate
                UpdateRules.isNewer(release.version, BuildConfig.VERSION_NAME) -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.javaClass.simpleName}")
            UpdateState.Failed(UpdateState.Reason.NETWORK)
        }
    }
    }

    /** Downloads the available release and checks it; on success the state becomes [UpdateState.ReadyToInstall]. */
    suspend fun download() {
      lock.withLock {
        val release = (_state.value as? UpdateState.Available)?.release
            ?: (_state.value as? UpdateState.Failed)?.let { lastRelease }
            ?: return
        lastRelease = release
        _state.value = UpdateState.Downloading(release, 0f)
        _state.value = try {
            val expected = release.sha256?.let { resolveHash(it) }
                ?: return@withLock fail(UpdateState.Reason.NO_HASH)
            val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, release.apkName)
            withContext(Dispatchers.IO) { fetch(release, file) }
            val actual = withContext(Dispatchers.IO) { sha256(file) }
            if (actual != expected) {
                file.delete()
                return@withLock fail(UpdateState.Reason.BAD_HASH)
            }
            when (val problem = withContext(Dispatchers.IO) { checkIdentity(file) }) {
                null -> UpdateState.ReadyToInstall(release, file)
                else -> { file.delete(); UpdateState.Failed(problem) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed: ${e.javaClass.simpleName}")
            UpdateState.Failed(UpdateState.Reason.NETWORK)
        }
    }
    }

    private var lastRelease: ReleaseInfo? = null

    private fun fail(reason: UpdateState.Reason): UpdateState.Failed =
        UpdateState.Failed(reason).also { _state.value = it }

    private suspend fun resolveHash(value: String): String? =
        if (value.startsWith("sidecar:")) {
            UpdateRules.parseSha256File(client.get(value.removePrefix("sidecar:")).bodyAsText())
        } else value

    private suspend fun fetch(release: ReleaseInfo, target: File) {
        client.prepareGet(release.apkUrl).execute { response ->
            val total = response.contentLength() ?: release.apkSize
            val channel: ByteReadChannel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            target.outputStream().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    done += n
                    if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Null when [apk] is this app signed with this app's key; otherwise why it must not be installed. */
    @Suppress("DEPRECATION")
    private fun checkIdentity(apk: File): UpdateState.Reason? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return UpdateState.Reason.WRONG_APP
        if (archive.packageName != context.packageName) return UpdateState.Reason.WRONG_APP
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun signers(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
                ?.toSet().orEmpty()
        val a = signers(archive)
        return if (a.isNotEmpty() && a == signers(installed)) null else UpdateState.Reason.WRONG_SIGNATURE
    }

    /**
     * Opens Android's installer for a downloaded update. Returns false when the person first has to allow
     * this app to install apps; the settings screen for that is opened instead.
     */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    companion object {
        /** The public repo whose releases carry the APKs. */
        const val RELEASES_REPO = "eeshir/money-tracker-android"
        private const val TAG = "AppUpdater"
        private const val CHECK_EVERY_MILLIS = 6 * 60 * 60 * 1000L
    }
}
