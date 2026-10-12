package jr.brian.thorhud.update

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    /** [uri] is the content URI the file was written to (handed to
     *  the installer for an APK, ignored for an ASI). [displayPath] is
     *  the human-readable `Downloads/gtc-downloads/<version>/<name>`
     *  the dialog shows the user. */
    data class Success(val uri: Uri, val displayPath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Pulls release assets (APK + ASIs) into the shared Downloads folder
 * under a per-version subfolder (`gtc-downloads/<version>/`). The APK's
 * MediaStore content URI goes straight to Android's package installer;
 * ASIs stay in Downloads for the user to copy into the game's Wine
 * prefix.
 */
object UpdateDownloader {

    private const val BUFFER_BYTES = 8192
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val MIME_BINARY = "application/octet-stream"

    /** Top-level folder in the shared Downloads collection. Each
     *  release gets its own `<version>` subfolder underneath so users
     *  can keep organised copies as the APK + ASIs change release to
     *  release. */
    const val DOWNLOADS_RELATIVE_DIR = "gtc-downloads"

    fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String,
        version: String,
    ): Flow<DownloadState> = downloadToVersionedDownloads(
        context = context,
        downloadUrl = downloadUrl,
        fileName = fileName.ifBlank { "update.apk" },
        version = version,
        mimeType = MIME_APK,
    )

    fun downloadAsi(
        context: Context,
        downloadUrl: String,
        fileName: String,
        version: String,
    ): Flow<DownloadState> = downloadToVersionedDownloads(
        context = context,
        downloadUrl = downloadUrl,
        fileName = fileName,
        version = version,
        mimeType = MIME_BINARY,
    )

    /**
     * Streams a release asset into
     * `Download/gtc-downloads/<version>/<fileName>` via MediaStore
     * (minSdk 33, so no WRITE_EXTERNAL_STORAGE). An existing file with
     * the same display name in the same relative path is deleted first
     * so repeated downloads overwrite instead of piling up
     * `name (1).apk`, `(2)`, etc.
     */
    private fun downloadToVersionedDownloads(
        context: Context,
        downloadUrl: String,
        fileName: String,
        version: String,
        mimeType: String,
    ): Flow<DownloadState> = flow {
        emit(DownloadState.Idle)
        val resolver = context.contentResolver
        val versionDir = sanitizeVersion(version)
        val relativePath = buildString {
            append(Environment.DIRECTORY_DOWNLOADS)
            append('/')
            append(DOWNLOADS_RELATIVE_DIR)
            if (versionDir.isNotEmpty()) {
                append('/')
                append(versionDir)
            }
        }
        val displayDir = buildString {
            append("Downloads/")
            append(DOWNLOADS_RELATIVE_DIR)
            if (versionDir.isNotEmpty()) {
                append('/')
                append(versionDir)
            }
        }
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(fileName, "$relativePath/")
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val existing = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                resolver.delete(existing, null, null)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (targetUri == null) {
            emit(DownloadState.Error("Could not create $fileName in Downloads"))
            return@flow
        }
        try {
            // GitHub's browser_download_url redirects to a signed S3
            // URL; the default HttpURLConnection follows redirects.
            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                resolver.delete(targetUri, null, null)
                emit(DownloadState.Error("Server returned ${connection.responseCode}"))
                return@flow
            }
            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            val stream = resolver.openOutputStream(targetUri)
            if (stream == null) {
                resolver.delete(targetUri, null, null)
                emit(DownloadState.Error("Could not open $fileName for writing"))
                return@flow
            }
            stream.use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress =
                            if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else -1
                        emit(DownloadState.Downloading(progress, downloaded, totalBytes))
                    }
                }
            }
            resolver.update(
                targetUri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            emit(DownloadState.Success(targetUri, "$displayDir/$fileName"))
        } catch (t: Throwable) {
            runCatching { resolver.delete(targetUri, null, null) }
            emit(DownloadState.Error(t.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    fun installApk(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, MIME_APK)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    /** Package-installer permission (REQUEST_INSTALL_PACKAGES). The
     *  user has to grant this to GTC in Settings the first time. */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /** Strip anything a MediaStore RELATIVE_PATH would reject (slashes,
     *  control chars) so a goofy tag name does not blow up the insert.
     *  Blank in → blank out, in which case the caller skips the
     *  per-version subfolder entirely. */
    private fun sanitizeVersion(version: String): String =
        version.trim().removePrefix("v")
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
