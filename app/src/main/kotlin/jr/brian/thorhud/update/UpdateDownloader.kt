package jr.brian.thorhud.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Pulls an APK down the pipe into the app's cache directory and hands
 * it to Android's package installer. The cache dir is deliberate: no
 * extra storage permission is needed, [FileProvider] can share from
 * there, and Android cleans it up on uninstall if the install never
 * completes.
 */
object UpdateDownloader {

    private const val UPDATE_DIR = "updates"
    private const val DEFAULT_FILE_NAME = "update.apk"
    private const val BUFFER_BYTES = 8192
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000

    fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String = DEFAULT_FILE_NAME,
    ): Flow<DownloadState> = flow {
        emit(DownloadState.Idle)
        val safeName = fileName.ifBlank { DEFAULT_FILE_NAME }
        val outFile = File(cacheDir(context), safeName)
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
                emit(DownloadState.Error("Server returned ${connection.responseCode}"))
                return@flow
            }

            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
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
            emit(DownloadState.Success(outFile))
        } catch (t: Throwable) {
            emit(DownloadState.Error(t.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
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

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, UPDATE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
