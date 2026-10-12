package jr.brian.thorhud.update

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.HudPalette
import kotlinx.coroutines.launch
import java.io.File

private enum class UpdatePhase { Info, PermissionRequired, Downloading, Complete, ErrorState }

/**
 * The update dialog, hosted over the HUD when the user taps the
 * download chip in the status bar. One screen per phase, swapped by
 * state — info, permission ask, download progress, install prompt,
 * error. Styled to the OLED HUD: pure-black card, accent border, no
 * fancy gradients.
 */
@Composable
internal fun UpdateDialog(
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
    var file by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    fun openReleaseInBrowser() {
        if (updateInfo.releaseHtmlUrl.isBlank()) return
        context.startActivity(Intent(Intent.ACTION_VIEW, updateInfo.releaseHtmlUrl.toUri()))
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
            ).collect { state ->
                when (state) {
                    is DownloadState.Idle -> progress = 0
                    is DownloadState.Downloading -> {
                        progress = state.progress
                        downloaded = state.downloadedBytes
                        total = state.totalBytes
                    }
                    is DownloadState.Success -> {
                        file = state.file
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

    val dismissible = phase != UpdatePhase.Downloading
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = palette.accent.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                ),
            shape = RoundedCornerShape(16.dp),
            color = Color.Black,
        ) {
            when (phase) {
                UpdatePhase.Info -> InfoPhase(
                    updateInfo = updateInfo,
                    currentVersion = currentVersion,
                    palette = palette,
                    onDownload = ::startDownload,
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
                        file?.let { UpdateDownloader.installApk(context, it) }
                        onDismiss()
                    },
                    onLater = onDismiss,
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
    onOpenInBrowser: () -> Unit,
    onLater: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
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
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            MarkdownBlock(updateInfo.releaseNotes.ifBlank {
                stringResource(R.string.update_no_notes)
            })
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.update_later), color = palette.accent)
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
 * Tiny Markdown-ish renderer for release notes. Enough to make the
 * bundled `patch_notes.md` look like patch notes: `##` / `###`
 * headers, `-` or `*` bullets, bold spans, inline code. Anything else
 * falls through as plain text. On purpose — the release body round-
 * trips through GitHub's rendering, which also keeps it simple.
 */
@Composable
private fun MarkdownBlock(text: String) {
    val lines = text.split("\n")
    for (line in lines) {
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
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("• ", color = Color(0xFFC0CFC8))
                Text(
                    parseInline(line.trimStart().removePrefix("- ").removePrefix("* ")),
                    color = Color(0xFFEDEDED),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            else -> Text(
                parseInline(line),
                color = Color(0xFFEDEDED),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
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
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
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
