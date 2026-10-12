package jr.brian.thorhud.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * The release the update dialog talks about: version, body (what you
 * wrote in `app/src/main/res/raw/patch_notes.md`, round-tripped via
 * the GitHub Release), and the APK asset the user downloads.
 */
data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val releaseNotes: String,
    val releaseHtmlUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSize: Long,
) {
    val hasApkAsset: Boolean get() = apkDownloadUrl.isNotBlank()

    companion object {
        val EMPTY = UpdateInfo(
            isUpdateAvailable = false,
            latestVersion = "",
            releaseNotes = "",
            releaseHtmlUrl = "",
            apkDownloadUrl = "",
            apkFileName = "",
            apkSize = 0L,
        )
    }
}

/**
 * Asks the public GitHub Releases API what the latest tag is and
 * whether it's newer than the running version. One shot per call;
 * caller decides when (we fire once on HUD start).
 */
object UpdateChecker {
    private const val OWNER = "BrianJr03"
    private const val REPO = "GrandTheftCompanion"
    private const val APK_ASSET_SUFFIX = ".apk"

    suspend fun checkForUpdate(
        currentVersionName: String,
        owner: String = OWNER,
        repo: String = REPO,
    ): UpdateInfo = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
            val release = JSONObject(URL(url).readText())
            parse(release, currentVersionName)
        }.getOrDefault(UpdateInfo.EMPTY)
    }

    internal fun parse(release: JSONObject, currentVersionName: String): UpdateInfo {
        val latestTag = release.optString("tag_name")
        val latestVersion = latestTag.removePrefix("v").trim()
        val assets = release.optJSONArray("assets")
        var apkUrl = ""
        var apkName = ""
        var apkSize = 0L
        if (assets != null) {
            // First .apk wins. We only publish one APK per release
            // (app/build/outputs/apk/release/app-release.apk → GTC-release.apk),
            // so no variant picker.
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val downloadUrl = asset.optString("browser_download_url")
                if (!downloadUrl.endsWith(APK_ASSET_SUFFIX, ignoreCase = true)) continue
                apkUrl = downloadUrl
                apkName = asset.optString("name").ifBlank { downloadUrl.substringAfterLast("/") }
                apkSize = asset.optLong("size", 0L)
                break
            }
        }
        return UpdateInfo(
            isUpdateAvailable = isNewerVersion(latestVersion, currentVersionName),
            latestVersion = latestVersion,
            releaseNotes = release.optString("body").trim(),
            releaseHtmlUrl = release.optString("html_url"),
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            apkSize = apkSize,
        )
    }

    /**
     * True when [latestVersion] parses to a strictly greater
     * dotted-numeric tuple than [currentVersion]. Both sides are
     * normalised first — leading "v" is stripped, and anything after
     * the first "-" (e.g. "-debug", "-rc1") is dropped so a debug
     * build still compares cleanly against a release tag.
     */
    internal fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        val latest = parts(latestVersion)
        val current = parts(currentVersion)
        if (latest.isEmpty() || current.isEmpty()) return false
        val max = maxOf(latest.size, current.size)
        for (i in 0 until max) {
            val a = latest.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .substringBefore('-')
            .split('.')
            .mapNotNull { it.toIntOrNull() }
}
