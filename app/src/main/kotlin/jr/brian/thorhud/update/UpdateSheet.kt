package jr.brian.thorhud.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.HudPalette
import kotlinx.coroutines.launch

private enum class UpdatePhase {
    Info,
    PermissionRequired,
    Downloading,
    Complete,
    AsiDownloading,
    AsiComplete,
    ErrorState,
}

/**
 * The update dialog, hosted over the HUD when the user taps the
 * download chip in the status bar or when a newer release lands at
 * launch. A fullscreen modal bottom sheet so long release notes have
 * room to breathe; one screen per phase, swapped by state — info,
 * permission ask, download progress, install prompt, error. Styled to
 * the OLED HUD: pure-black container, accent drag handle, no gradients.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSheet(
    updateInfo: UpdateInfo,
    currentVersion: String,
    palette: HudPalette,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(UpdatePhase.Info) }
    var progress by remember { mutableIntStateOf(0) }
    var downloaded by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(updateInfo.apkSize) }
    var apkUri by remember { mutableStateOf<Uri?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    // Which ASI (0-based) is currently downloading, and how many in
    // this batch. Shown as "n of N — ThorBridge.asi" in the progress
    // phase.
    var asiIndex by remember { mutableIntStateOf(0) }
    var asiCount by remember { mutableIntStateOf(0) }
    var asiCurrentName by remember { mutableStateOf("") }

    fun openReleaseInBrowser() {
        if (updateInfo.releaseHtmlUrl.isBlank()) return
        context.startActivity(Intent(Intent.ACTION_VIEW, updateInfo.releaseHtmlUrl.toUri()))
    }

    fun startAsiDownloads() {
        val assets = updateInfo.asiAssets
        if (assets.isEmpty()) return
        asiCount = assets.size
        phase = UpdatePhase.AsiDownloading
        scope.launch {
            for ((i, asset) in assets.withIndex()) {
                asiIndex = i
                asiCurrentName = asset.fileName
                total = asset.size
                downloaded = 0L
                progress = 0
                var failed = false
                UpdateDownloader.downloadAsi(
                    context = context,
                    downloadUrl = asset.downloadUrl,
                    fileName = asset.fileName,
                    version = updateInfo.latestVersion,
                ).collect { state ->
                    when (state) {
                        is DownloadState.Idle -> progress = 0
                        is DownloadState.Downloading -> {
                            progress = state.progress
                            downloaded = state.downloadedBytes
                            total = state.totalBytes
                        }
                        is DownloadState.Success -> Unit
                        is DownloadState.Error -> {
                            errorMessage = state.message
                            failed = true
                        }
                    }
                }
                if (failed) {
                    phase = UpdatePhase.ErrorState
                    return@launch
                }
            }
            phase = UpdatePhase.AsiComplete
        }
    }

    fun startDownload() {
        // No APK asset on the release (manual release?) — hand off to
        // the browser so the user can at least see the page.
        if (!updateInfo.hasApkAsset) {
            openReleaseInBrowser()
            onDismiss()
            return
        }
        if (!UpdateDownloader.canInstallPackages(context)) {
            phase = UpdatePhase.PermissionRequired
            return
        }
        total = updateInfo.apkSize
        phase = UpdatePhase.Downloading
        scope.launch {
            UpdateDownloader.downloadApk(
                context = context,
                downloadUrl = updateInfo.apkDownloadUrl,
                fileName = updateInfo.apkFileName,
                version = updateInfo.latestVersion,
            ).collect { state ->
                when (state) {
                    is DownloadState.Idle -> progress = 0
                    is DownloadState.Downloading -> {
                        progress = state.progress
                        downloaded = state.downloadedBytes
                        total = state.totalBytes
                    }
                    is DownloadState.Success -> {
                        apkUri = state.uri
                        phase = UpdatePhase.Complete
                    }
                    is DownloadState.Error -> {
                        errorMessage = state.message
                        phase = UpdatePhase.ErrorState
                    }
                }
            }
        }
    }

    // Block scrim-tap and swipe-down from closing the sheet while a
    // download is running; reading latestPhase through
    // rememberUpdatedState keeps the check tracking the current state
    // instead of the value captured at sheet construction.
    val latestPhase by rememberUpdatedState(phase)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden) {
                latestPhase != UpdatePhase.Downloading &&
                    latestPhase != UpdatePhase.AsiDownloading
            } else {
                true
            }
        },
    )
    ModalBottomSheet(
        onDismissRequest = {
            if (phase != UpdatePhase.Downloading && phase != UpdatePhase.AsiDownloading) {
                onDismiss()
            }
        },
        sheetState = sheetState,
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = palette.accent.copy(alpha = 0.5f))
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            when (phase) {
                UpdatePhase.Info -> InfoPhase(
                    updateInfo = updateInfo,
                    currentVersion = currentVersion,
                    palette = palette,
                    onDownload = ::startDownload,
                    onDownloadAsi = ::startAsiDownloads,
                    onOpenInBrowser = {
                        openReleaseInBrowser()
                        onDismiss()
                    },
                    onLater = onDismiss,
                )
                UpdatePhase.PermissionRequired -> PermissionPhase(
                    palette = palette,
                    onGrant = { UpdateDownloader.openInstallPermissionSettings(context) },
                    onRetry = ::startDownload,
                    onCancel = onDismiss,
                )
                UpdatePhase.Downloading -> DownloadingPhase(
                    progress = progress,
                    downloaded = downloaded,
                    total = total,
                    palette = palette,
                )
                UpdatePhase.Complete -> CompletePhase(
                    palette = palette,
                    onInstall = {
                        apkUri?.let { UpdateDownloader.installApk(context, it) }
                        onDismiss()
                    },
                    onLater = onDismiss,
                )
                UpdatePhase.AsiDownloading -> AsiDownloadingPhase(
                    index = asiIndex,
                    count = asiCount,
                    fileName = asiCurrentName,
                    progress = progress,
                    downloaded = downloaded,
                    total = total,
                    palette = palette,
                )
                UpdatePhase.AsiComplete -> AsiCompletePhase(
                    palette = palette,
                    version = updateInfo.latestVersion,
                    canDownloadApk = updateInfo.hasApkAsset,
                    apkSize = updateInfo.apkSize,
                    onDone = onDismiss,
                    onDownloadApk = ::startDownload,
                )
                UpdatePhase.ErrorState -> ErrorPhase(
                    message = errorMessage,
                    palette = palette,
                    onRetry = ::startDownload,
                    onOpenInBrowser = {
                        openReleaseInBrowser()
                        onDismiss()
                    },
                    onCancel = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun InfoPhase(
    updateInfo: UpdateInfo,
    currentVersion: String,
    palette: HudPalette,
    onDownload: () -> Unit,
    onDownloadAsi: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onLater: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Header(
            title = stringResource(R.string.update_available_title),
            subtitle = stringResource(
                R.string.update_available_subtitle,
                currentVersion,
                updateInfo.latestVersion,
            ),
            palette = palette,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            MarkdownBlock(
                text = updateInfo.releaseNotes.ifBlank {
                    stringResource(R.string.update_no_notes)
                },
                palette = palette,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.update_later), color = palette.accent)
            }
            if (updateInfo.hasAsiAssets) {
                TextButton(onClick = onDownloadAsi) {
                    val size = if (updateInfo.totalAsiSize > 0)
                        " (${UpdateDownloader.formatBytes(updateInfo.totalAsiSize)})" else ""
                    Text(
                        stringResource(R.string.update_download_asi) + size,
                        color = palette.accent,
                    )
                }
            }
            if (updateInfo.hasApkAsset) {
                TextButton(onClick = onDownload) {
                    val size = if (updateInfo.apkSize > 0)
                        " (${UpdateDownloader.formatBytes(updateInfo.apkSize)})" else ""
                    Text(
                        stringResource(R.string.update_download) + size,
                        color = palette.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                TextButton(onClick = onOpenInBrowser) {
                    Text(
                        stringResource(R.string.update_open_release),
                        color = palette.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AsiDownloadingPhase(
    index: Int,
    count: Int,
    fileName: String,
    progress: Int,
    downloaded: Long,
    total: Long,
    palette: HudPalette,
) {
    Column(Modifier.padding(16.dp)) {
        Header(
            title = stringResource(R.string.update_asi_downloading_title),
            subtitle = stringResource(
                R.string.update_asi_downloading_which,
                (index + 1).coerceAtLeast(1),
                count.coerceAtLeast(1),
                fileName.ifBlank { "" },
            ),
            palette = palette,
        )
        Spacer(Modifier.height(12.dp))
        if (progress >= 0) {
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = palette.accent,
                trackColor = palette.accent.copy(alpha = 0.2f),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = palette.accent,
                trackColor = palette.accent.copy(alpha = 0.2f),
            )
        }
        Spacer(Modifier.height(8.dp))
        val sub = if (total > 0) {
            stringResource(
                R.string.update_downloading_progress,
                UpdateDownloader.formatBytes(downloaded),
                UpdateDownloader.formatBytes(total),
            )
        } else {
            UpdateDownloader.formatBytes(downloaded)
        }
        Text(sub, color = Color(0xFF8EA096), fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AsiCompletePhase(
    palette: HudPalette,
    version: String,
    canDownloadApk: Boolean,
    apkSize: Long,
    onDone: () -> Unit,
    onDownloadApk: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Header(
            title = stringResource(R.string.update_asi_ready_title),
            subtitle = stringResource(R.string.update_asi_ready_subtitle, version),
            palette = palette,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) {
                Text(
                    stringResource(R.string.update_done),
                    color = palette.accent,
                    fontWeight = if (canDownloadApk) FontWeight.Normal else FontWeight.Bold,
                )
            }
            if (canDownloadApk) {
                TextButton(onClick = onDownloadApk) {
                    val size = if (apkSize > 0) " (${UpdateDownloader.formatBytes(apkSize)})" else ""
                    Text(
                        stringResource(R.string.update_download) + size,
                        color = palette.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionPhase(
    palette: HudPalette,
    onGrant: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Header(
            title = stringResource(R.string.update_permission_title),
            subtitle = null,
            palette = palette,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.update_permission_body),
            color = Color(0xFFEDEDED),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_cancel), color = palette.accent)
            }
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.update_open_settings), color = palette.accent)
            }
            TextButton(onClick = onRetry) {
                Text(
                    stringResource(R.string.update_retry),
                    color = palette.accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DownloadingPhase(
    progress: Int,
    downloaded: Long,
    total: Long,
    palette: HudPalette,
) {
    Column(Modifier.padding(16.dp)) {
        Header(
            title = stringResource(R.string.update_downloading_title),
            subtitle = null,
            palette = palette,
        )
        Spacer(Modifier.height(12.dp))
        // Indeterminate when the server didn't send Content-Length
        // (progress = -1); animated bar either way.
        if (progress >= 0) {
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = palette.accent,
                trackColor = palette.accent.copy(alpha = 0.2f),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = palette.accent,
                trackColor = palette.accent.copy(alpha = 0.2f),
            )
        }
        Spacer(Modifier.height(8.dp))
        val sub = if (total > 0) {
            stringResource(
                R.string.update_downloading_progress,
                UpdateDownloader.formatBytes(downloaded),
                UpdateDownloader.formatBytes(total),
            )
        } else {
            UpdateDownloader.formatBytes(downloaded)
        }
        Text(sub, color = Color(0xFF8EA096), fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CompletePhase(
    palette: HudPalette,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Header(
            title = stringResource(R.string.update_ready_title),
            subtitle = stringResource(R.string.update_ready_subtitle),
            palette = palette,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.update_later), color = palette.accent)
            }
            TextButton(onClick = onInstall) {
                Text(
                    stringResource(R.string.update_install),
                    color = palette.accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ErrorPhase(
    message: String,
    palette: HudPalette,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Header(
            title = stringResource(R.string.update_error_title),
            subtitle = null,
            palette = palette,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message.ifBlank { stringResource(R.string.update_error_fallback) },
            color = Color(0xFFB04040),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_cancel), color = palette.accent)
            }
            TextButton(onClick = onOpenInBrowser) {
                Text(stringResource(R.string.update_open_release), color = palette.accent)
            }
            TextButton(onClick = onRetry) {
                Text(
                    stringResource(R.string.update_retry),
                    color = palette.accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, palette: HudPalette) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = palette.accent)
    if (!subtitle.isNullOrBlank()) {
        Text(
            subtitle,
            color = Color(0xFF8EA096),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Tiny Markdown-ish renderer for release notes. Covers everything our
 * bundled `patch_notes.md` + GitHub-hosted release bodies actually use:
 * `#` / `##` / `###` headers, `-` or `*` bullets (with two-space
 * indentation for nesting), `>` blockquotes, fenced ```` ``` ```` code
 * blocks, inline `**bold**`, and inline `` `code` ``. Anything else
 * falls through as plain text. The releases also render through
 * GitHub's own pass, which keeps the input simple on both ends.
 */
@Composable
private fun MarkdownBlock(text: String, palette: HudPalette) {
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("### ") -> Text(
                line.removePrefix("### "),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            line.startsWith("## ") -> Text(
                line.removePrefix("## "),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            line.startsWith("# ") -> Text(
                line.removePrefix("# "),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            line.trim().startsWith("```") -> {
                i++
                val codeLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                CodeBlock(codeLines)
            }
            line.trim().startsWith(">") -> Blockquote(
                text = line.trim().removePrefix(">").trimStart(),
                palette = palette,
            )
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val leading = line.length - line.trimStart().length
                val indent = (leading / 2).coerceAtMost(4)
                val body = line.trimStart().removePrefix("- ").removePrefix("* ")
                Row(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .padding(start = (indent * 12).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        if (indent > 0) "◦ " else "• ",
                        color = palette.accent.copy(alpha = 0.9f),
                    )
                    Text(
                        parseInline(body, palette),
                        color = Color(0xFFEDEDED),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            else -> Text(
                parseInline(line, palette),
                color = Color(0xFFEDEDED),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        i++
    }
}

@Composable
private fun Blockquote(text: String, palette: HudPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp),
            color = palette.accent.copy(alpha = 0.6f),
        ) {}
        Spacer(Modifier.width(10.dp))
        Text(
            parseInline(text, palette),
            color = Color(0xFFB8C4BE),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun CodeBlock(lines: List<String>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        color = Color.White.copy(alpha = 0.06f),
    ) {
        Text(
            lines.joinToString("\n"),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}

private fun parseInline(text: String, palette: HudPalette): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.White.copy(alpha = 0.12f),
                            color = palette.accent,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}
