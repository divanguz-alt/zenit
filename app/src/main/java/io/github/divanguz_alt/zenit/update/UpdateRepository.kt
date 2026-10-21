package io.github.divanguz_alt.zenit.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.divanguz_alt.zenit.BuildConfig
import io.github.divanguz_alt.zenit.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(
    val version: SemVer,
    val tag: String,
    val notes: String,
    val apk: GhAsset,
    val checksum: GhAsset?,
    val htmlUrl: String,
)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val info: UpdateInfo) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

class UpdateException(message: String) : Exception(message)

/**
 * Self-update from GitHub Releases: check (cached for an hour), download with progress,
 * verify SHA-256 and signing certificate, then hand off to PackageInstaller.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    val currentVersion: SemVer? = SemVer.parse(BuildConfig.VERSION_NAME.substringBefore("-debug"))

    suspend fun check(force: Boolean): UpdateCheck = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val (lastCheck, cached) = settings.updateCache()
        val release: GhRelease = try {
            if (!force && cached != null && now - lastCheck < CACHE_MS) {
                json.decodeFromString(cached)
            } else {
                val body = fetchLatest()
                settings.saveUpdateCache(now, body)
                json.decodeFromString(body)
            }
        } catch (e: Exception) {
            return@withContext UpdateCheck.Failed(e.message ?: "Network error")
        }
        val current = currentVersion ?: return@withContext UpdateCheck.Failed("Unknown installed version")
        evaluate(release, current)
    }

    /** Last check time (millis) — used for the once-a-day silent check. */
    suspend fun lastCheckMillis(): Long = settings.updateCache().first

    private fun fetchLatest(): String {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("User-Agent", "Zenit/${BuildConfig.VERSION_NAME} (Android)")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> throw UpdateException("No releases published yet")
                !resp.isSuccessful -> throw UpdateException("GitHub returned HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            // Round-trip through the model so the cache holds only the fields we use.
            return json.encodeToString(json.decodeFromString<GhRelease>(body))
        }
    }

    /** Downloads the APK into cacheDir/updates and verifies it. [onProgress] receives 0..1. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, info.apk.name)
        val req = Request.Builder().url(info.apk.url).header("User-Agent", "Zenit/${BuildConfig.VERSION_NAME}").build()
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw UpdateException("Download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw UpdateException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apk.size
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        info.checksum?.let { verifyChecksum(it, actual) }
            ?: throw UpdateException("Release has no .sha256 checksum; refusing to install")
        verifySignature(target)
        target
    }

    private fun verifyChecksum(asset: GhAsset, actual: String) {
        val req = Request.Builder().url(asset.url).header("User-Agent", "Zenit/${BuildConfig.VERSION_NAME}").build()
        val expected = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw UpdateException("Checksum download failed (HTTP ${resp.code})")
            resp.body?.string().orEmpty().trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
        }
        if (expected.isNullOrEmpty() || expected != actual) throw UpdateException("SHA-256 mismatch: the download is corrupt")
    }

    /** The update must be the same package, signed with the same certificate as the installed app. */
    private fun verifySignature(apk: File) {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk.path) ?: throw UpdateException("Downloaded file is not a valid APK")
        if (archive.packageName != context.packageName) {
            throw UpdateException("Update is for ${archive.packageName}, not this build (${context.packageName})")
        }
        val installed = installedInfo(pm)
        if (certDigests(archive) != certDigests(installed)) {
            throw UpdateException("Signing certificate differs from the installed app")
        }
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun certDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            }
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** True if the user has allowed this app to install packages (Android 8+ per-app setting). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Starts a PackageInstaller session; the system confirmation arrives via [InstallResultReceiver]. */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("zenit.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    companion object {
        private const val CACHE_MS = 60 * 60 * 1000L

        /** Decides whether [release] is a newer, installable version than [current]. */
        fun evaluate(release: GhRelease, current: SemVer): UpdateCheck {
            val remote = SemVer.parse(release.tag) ?: return UpdateCheck.Failed("Unrecognised version ${release.tag}")
            if (release.draft || release.prerelease || remote <= current) return UpdateCheck.UpToDate
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return UpdateCheck.Failed("Release ${release.tag} has no APK")
            val sha = release.assets.firstOrNull { it.name == apk.name + ".sha256" }
            return UpdateCheck.Available(
                UpdateInfo(remote, release.tag, markdownToPlain(release.body.orEmpty()), apk, sha, release.htmlUrl),
            )
        }

        /** GitHub-flavoured markdown to plain text for the update dialog. */
        fun markdownToPlain(md: String): String = md.lines().joinToString("\n") { raw ->
            var line = raw.trimEnd()
            line = line.replace(Regex("""^#{1,6}\s*"""), "")
            line = line.replace(Regex("""^\s*[-*+]\s+"""), "• ")
            line = line.replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
            line = line.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            line = line.replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")
            line = line.replace(Regex("""(?<![*\w])[*_](.+?)[*_](?![*\w])"""), "$1")
            line = line.replace("`", "")
            line
        }.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
