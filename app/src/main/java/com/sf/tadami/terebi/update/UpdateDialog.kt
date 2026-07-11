package com.sf.tadami.terebi.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sf.tadami.terebi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private enum class Phase { Idle, Downloading, Downloaded, Error }

/**
 * Blocking / non-blocking update dialog. Flow: **Download** button → progress bar → the **Install**
 * button un-greys when the download finishes. When [forced] is false a **Skip** button dismisses it;
 * when true there is no way out but to update (the phone requires a newer receiver).
 *
 * [release] may be null in the forced case (we only know we're incompatible) — the latest release is
 * fetched lazily when Download is pressed.
 */
@Composable
fun UpdateDialog(
    release: GithubRelease?,
    forced: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var apkFile by remember { mutableStateOf<File?>(null) }

    val downloadFocus = remember { FocusRequester() }
    val installFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { downloadFocus.requestFocus() } }
    LaunchedEffect(phase) { if (phase == Phase.Downloaded) runCatching { installFocus.requestFocus() } }

    fun startDownload() {
        phase = Phase.Downloading
        progress = 0f
        scope.launch {
            val url = release?.apkDownloadUrl() ?: TvAppUpdater.fetchLatestRelease()?.apkDownloadUrl()
            if (url == null) { phase = Phase.Error; return@launch }
            runCatching { ApkInstaller.download(context, url) { progress = it } }
                .onSuccess { apkFile = it; phase = Phase.Downloaded }
                .onFailure { phase = Phase.Error }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swallow BACK entirely so it never leaks to the player; only dismiss when non-blocking
            // and not mid-download.
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown && !forced && phase != Phase.Downloading) onDismiss()
                    true
                } else {
                    false
                }
            }
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(modifier = Modifier.fillMaxWidth(0.6f)) {
            Column(Modifier.padding(32.dp)) {
                Text(
                    text = stringResource(
                        if (forced) R.string.update_required_title else R.string.update_available_title,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        forced -> stringResource(R.string.update_required_message)
                        release != null -> stringResource(R.string.update_available_message, release.version)
                        else -> stringResource(R.string.update_available_title)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(Modifier.height(24.dp))
                when (phase) {
                    Phase.Downloading -> {
                        Text(
                            text = stringResource(R.string.update_downloading, (progress * 100).toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Phase.Error -> Text(
                        text = stringResource(R.string.update_download_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }

                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DialogButton(
                        text = stringResource(
                            if (phase == Phase.Error) R.string.update_retry else R.string.update_download,
                        ),
                        enabled = phase == Phase.Idle || phase == Phase.Error,
                        modifier = Modifier.focusRequester(downloadFocus),
                        onClick = { startDownload() },
                    )
                    DialogButton(
                        text = stringResource(R.string.update_install),
                        enabled = phase == Phase.Downloaded,
                        modifier = Modifier.focusRequester(installFocus),
                        onClick = {
                            val file = apkFile ?: return@DialogButton
                            scope.launch(Dispatchers.IO) { ApkInstaller.install(context, file) }
                        },
                    )
                    if (!forced) {
                        DialogButton(
                            text = stringResource(R.string.update_skip),
                            enabled = phase != Phase.Downloading,
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        modifier = modifier,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}
