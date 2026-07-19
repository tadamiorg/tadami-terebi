package com.sf.tadami.terebi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sf.tadami.terebi.R
import com.sf.tadami.terebi.player.TvEpisode
import com.sf.tadami.terebi.player.TvAudioTrack
import com.sf.tadami.terebi.player.TvStreamSource
import com.sf.tadami.terebi.player.TvSubtitleTrack

@Composable
private fun DialogScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Intercept BACK before the list's focus system (which would otherwise just move focus
            // off the selected row on the first press) so BACK closes the dialog immediately.
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) onDismiss()
                    true
                } else {
                    false
                }
            }
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .fillMaxHeight(0.85f),
        ) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
}

@Composable
private fun DialogRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = if (selected) "● $label" else label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
fun SourceDialog(
    sources: List<TvStreamSource>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    DialogScaffold(title = stringResource(R.string.dialog_video_source), onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(sources) { index, source ->
                DialogRow(
                    label = source.label,
                    selected = index == selectedIndex,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
fun EpisodeDialog(
    episodes: List<TvEpisode>,
    displayMode: String,
    currentEpisodeId: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    DialogScaffold(title = stringResource(R.string.dialog_episodes), onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(episodes) { index, episode ->
                DialogRow(
                    label = episode.label(displayMode),
                    selected = episode.id == currentEpisodeId,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onSelect(episode.id) },
                )
            }
        }
    }
}

@Composable
fun SubtitleDialog(
    tracks: List<TvSubtitleTrack>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    DialogScaffold(title = stringResource(R.string.dialog_subtitles), onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                DialogRow(
                    label = stringResource(R.string.subtitles_off),
                    selected = selectedIndex == null,
                    modifier = Modifier.focusRequester(firstFocus),
                    onClick = { onSelect(null) },
                )
            }
            itemsIndexed(tracks) { index, track ->
                DialogRow(
                    label = track.lang.ifBlank { stringResource(R.string.subtitle_track, index + 1) },
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
fun AudioDialog(
    tracks: List<TvAudioTrack>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    DialogScaffold(title = stringResource(R.string.dialog_audio), onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(tracks) { index, track ->
                DialogRow(
                    label = track.lang.ifBlank { stringResource(R.string.audio_track, index + 1) },
                    selected = selectedIndex == index,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}
