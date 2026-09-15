package io.github.dovecoteescapee.byedpi.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.dovecoteescapee.byedpi.BuildConfig
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

object AppUpdateRepository {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/lolososka/zapret-control-center-android/releases/latest"
    private const val MAX_APK_BYTES = 64L * 1024L * 1024L
    private const val MIN_APK_BYTES = 512L * 1024L
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
    private val stableTag = Regex("^android-v(\\d+\\.\\d+\\.\\d+)$")
    private val stableVersion = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
    private val sha256Digest = Regex("^sha256:([0-9a-f]{64})$")

    data class Release(
        val version: String,
        val apkName: String,
        val apkUrl: String,
        val checksumUrl: String,
        val pageUrl: String,
        val size: Long,
        val sha256: String,
    )

    suspend fun latest(): Release? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val connection = openHttps(URL(LATEST_RELEASE_URL), "application/vnd.github+json")
        val body = connection.useBody { input ->
            readLimited(input, 1024 * 1024).toString(Charsets.UTF_8)
        }
        val root = JSONObject(body)
        val version = stableTag.matchEntire(root.getString("tag_name"))?.groupValues?.get(1)
            ?: throw IOException("Unsupported release tag")
        if (!isNewerVersion(version, BuildConfig.VERSION_NAME)) return@withContext null

        val expectedApkName = "zapret-mobile-$version.apk"
        val expectedChecksumName = "$expectedApkName.sha256"
        var apkUrl: String? = null
        var checksumUrl: String? = null
        var size = 0L
        var digest: String? = null
        val assets = root.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            when (asset.getString("name")) {
                expectedApkName -> {
                    apkUrl = asset.getString("browser_download_url")
                    size = asset.getLong("size")
                    digest = sha256Digest.matchEntire(asset.getString("digest"))
                        ?.groupValues
                        ?.get(1)
                }

                expectedChecksumName -> checksumUrl = asset.getString("browser_download_url")
            }
        }
        if (size !in MIN_APK_BYTES..MAX_APK_BYTES || digest == null) {
            throw IOException("Invalid APK metadata")
        }
        val checkedApkUrl = requireReleaseAssetUrl(apkUrl)
        val checkedChecksumUrl = requireReleaseAssetUrl(checksumUrl)
        Release(
            version = version,
            apkName = expectedApkName,
            apkUrl = checkedApkUrl,
            checksumUrl = checkedChecksumUrl,
            pageUrl = requireGithubUrl(root.getString("html_url")),
            size = size,
            sha256 = digest,
        )
    }

    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
    ): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create update directory")
        }
        directory.listFiles()?.forEach { old ->
            if (old.name != release.apkName) old.delete()
        }

        val part = File(directory, "${release.apkName}.part")
        val target = File(directory, release.apkName)
        part.delete()
        target.delete()

        val connection = openHttps(URL(release.apkUrl), "application/vnd.android.package-archive")
        val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        if (contentLength > MAX_APK_BYTES ||
            (contentLength > 0L && contentLength != release.size)
        ) {
            connection.disconnect()
            throw IOException("Unexpected APK size")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L
        try {
            connection.useBody { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        downloaded += read
                        if (downloaded > release.size || downloaded > MAX_APK_BYTES) {
                            throw IOException("APK exceeds declared size")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(((downloaded * 100L) / release.size).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (downloaded != release.size) throw IOException("Incomplete APK")
            val actualSha256 = digest.digest().toHex()
            if (!actualSha256.equals(release.sha256, ignoreCase = true)) {
                throw IOException("APK digest mismatch")
            }

            val checksum = downloadChecksum(release.checksumUrl)
            val expectedLine = "${release.sha256}  ${release.apkName}"
            if (checksum != expectedLine) throw IOException("Checksum manifest mismatch")
            if (!part.renameTo(target)) throw IOException("Cannot finalize APK")
            verifyApk(context, target, release.version)
            onProgress(100)
            target
        } catch (error: Exception) {
            part.delete()
            target.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    fun verifyApk(context: Context, apk: File, expectedVersion: String) {
        if (!apk.isFile || apk.length() !in MIN_APK_BYTES..MAX_APK_BYTES) {
            throw IOException("Invalid APK file")
        }
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("Cannot read APK")
        if (candidate.packageName != BuildConfig.APPLICATION_ID) {
            throw IOException("Package name mismatch")
        }
        if (candidate.versionName != expectedVersion) {
            throw IOException("APK version mismatch")
        }

        val installed = packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        if (versionCode(candidate) <= versionCode(installed)) {
            throw IOException("APK is not newer")
        }
        val candidateCertificates = signingCertificates(candidate)
        val installedCertificates = signingCertificates(installed)
        val signaturesMatch = isTrustedSigningUpdate(
            candidate = candidateCertificates,
            installed = installedCertificates,
            supportsSigningHistory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
        )
        if (!signaturesMatch) {
            throw IOException("Signing certificate mismatch")
        }
    }

    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        val candidateParts = parseStableVersion(candidate) ?: return false
        val currentParts = parseStableVersion(current.removeSuffix("-debug")) ?: return false
        for (index in 0..2) {
            if (candidateParts[index] != currentParts[index]) {
                return candidateParts[index] > currentParts[index]
            }
        }
        return false
    }

    private fun parseStableVersion(value: String): List<Int>? {
        val match = stableVersion.matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { part -> part.toIntOrNull() ?: return null }
    }

    internal fun isTrustedSigningUpdate(
        candidate: SigningCertificates,
        installed: SigningCertificates,
        supportsSigningHistory: Boolean,
    ): Boolean {
        if (candidate.current.isEmpty() || installed.current.isEmpty()) return false
        if (supportsSigningHistory && !candidate.multiple && !installed.multiple) {
            // A forward key rotation must carry the installed signer in the
            // candidate's verified lineage, never the other way around.
            return candidate.current.size == 1 && installed.current.size == 1 &&
                candidate.history.containsAll(candidate.current) &&
                candidate.history.contains(installed.current.single())
        }
        return candidate.current == installed.current
    }

    private fun downloadChecksum(url: String): String {
        val connection = openHttps(URL(url), "text/plain")
        return connection.useBody { input ->
            val bytes = readLimited(input, 256)
            bytes.toString(Charsets.US_ASCII).trimEnd('\r', '\n')
        }
    }

    private fun openHttps(start: URL, accept: String): HttpsURLConnection {
        var current = start
        repeat(6) {
            requireAllowedGithubUrl(current)
            val connection = current.openConnection() as? HttpsURLConnection
                ?: throw IOException("HTTPS is required")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "Zapret-Mobile/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val responseCode = connection.responseCode
            when (responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) throw IOException("Redirect without location")
                    current = URL(current, location)
                }

                else -> {
                    connection.disconnect()
                    throw IOException("GitHub returned HTTP $responseCode")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun requireReleaseAssetUrl(value: String?): String {
        val url = value?.let(::URL) ?: throw IOException("Release asset is missing")
        if (url.protocol != "https" || url.host != "github.com") {
            throw IOException("Unexpected release asset URL")
        }
        return url.toString()
    }

    private fun requireGithubUrl(value: String): String {
        val url = URL(value)
        if (url.protocol != "https" || url.host != "github.com") {
            throw IOException("Unexpected release page URL")
        }
        return url.toString()
    }

    private fun requireAllowedGithubUrl(url: URL) {
        val host = url.host.lowercase()
        val allowed = host == "github.com" ||
            host == "api.github.com" ||
            host.endsWith(".githubusercontent.com")
        if (url.protocol != "https" || !allowed || url.userInfo != null) {
            throw IOException("Blocked update URL")
        }
    }

    private inline fun <T> HttpsURLConnection.useBody(
        block: (java.io.InputStream) -> T,
    ): T = try {
        inputStream.use(block)
    } finally {
        disconnect()
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw IOException("Response is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    internal data class SigningCertificates(
        val current: Set<String>,
        val history: Set<String>,
        val multiple: Boolean,
    )

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): SigningCertificates {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val current = certificateDigests(info.signatures)
            return SigningCertificates(current, current, multiple = current.size > 1)
        }
        val signingInfo = info.signingInfo
            ?: return SigningCertificates(emptySet(), emptySet(), multiple = false)
        val multiple = signingInfo.hasMultipleSigners()
        val current = certificateDigests(signingInfo.apkContentsSigners)
        val history = if (multiple) current else certificateDigests(signingInfo.signingCertificateHistory)
        return SigningCertificates(current, history, multiple)
    }

    private fun certificateDigests(signatures: Array<out android.content.pm.Signature>?): Set<String> =
        signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
