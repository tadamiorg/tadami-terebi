package com.sf.tadami.terebi.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sf.tadami.terebi.R
import com.sf.tadami.terebi.player.ControlSender
import com.sf.tadami.terebi.player.PlayerManager
import com.sf.tadami.terebi.player.subtitles.PlayerSubtitleView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val SKIP_OP_MS = 85_000L
private const val SEEK_STEP_MS = 10_000L
private const val SEEK_STEP_S = SEEK_STEP_MS / 1000
private const val CONTROLS_TIMEOUT_MS = 5_000L

@OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(playerManager: PlayerManager) {
    val snapshot by playerManager.snapshot.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    // "Poke" on any interaction to reset the auto-hide timer without recomposing the tree.
    val controlPokes = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showEpisodeDialog by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    val dialogOpen = showSourceDialog || showSubtitleDialog || showAudioDialog || showEpisodeDialog

    // Episode position within the (source-order) list drives next/previous availability and
    // mirrors the phone's iterator: "next" = the entry before the current one.
    val currentIndex = snapshot.episodes.indexOfFirst { it.id == snapshot.currentEpisodeId }
    val hasNext = currentIndex > 0
    val hasPrevious = currentIndex in 0 until (snapshot.episodes.size - 1)

    // Smoothly-updating playback position for the timeline.
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = playerManager.player.currentPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Hidden-controls quick-seek (LEFT/RIGHT): running scrub target + accumulated seconds (signed).
    var quickSeekMs by remember { mutableStateOf<Long?>(null) }
    var quickSeekAccumSec by remember { mutableLongStateOf(0L) }

    // Commit the quick-seek shortly after the last press, then hide the mini overlay.
    LaunchedEffect(quickSeekMs) {
        val target = quickSeekMs ?: return@LaunchedEffect
        delay(500)
        playerManager.seekTo(target)
        positionMs = target
        quickSeekMs = null
        quickSeekAccumSec = 0L
    }

    // Surface the controls whenever a new title/episode/source starts playing.
    LaunchedEffect(snapshot.hasMedia, snapshot.episodeLabel, snapshot.selectedSourceIndex) {
        if (snapshot.hasMedia) {
            controlsVisible = true
            controlPokes.tryEmit(Unit)
        }
    }

    // At end of stream, surface the controls (with the replay button) and keep them up.
    LaunchedEffect(snapshot.ended) {
        if (snapshot.ended) {
            controlsVisible = true
            controlPokes.tryEmit(Unit)
        }
    }

    // Auto-hide controls after CONTROLS_TIMEOUT_MS of inactivity; each poke resets the timer
    // (without recomposing). Never while a dialog is open or the stream has ended.
    LaunchedEffect(controlsVisible, dialogOpen, snapshot.ended) {
        if (controlsVisible && !dialogOpen && !snapshot.ended) {
            while (true) {
                val poked = withTimeoutOrNull(CONTROLS_TIMEOUT_MS) { controlPokes.first() }
                if (poked == null) {
                    controlsVisible = false
                    break
                }
            }
        }
    }

    // Focus routing: dialogs self-focus; otherwise play/pause when visible, root when hidden.
    LaunchedEffect(controlsVisible, dialogOpen) {
        delay(50)
        runCatching {
            when {
                dialogOpen -> {}
                controlsVisible -> playFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Handle BACK here (before Compose's focus system clears focus for a focus-exit):
                // when controls are up, BACK hides them immediately. Consume down+up.
                if (event.key == Key.Back && !dialogOpen && controlsVisible) {
                    if (event.type == KeyEventType.KeyDown) controlsVisible = false
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown || dialogOpen) {
                    return@onPreviewKeyEvent false
                }
                if (controlsVisible) {
                    controlPokes.tryEmit(Unit)
                    return@onPreviewKeyEvent false
                }
                // Controls hidden: LEFT/RIGHT quick-seek (mini timeline + ±seconds), other keys open.
                val duration = snapshot.durationMs
                fun commitQuickSeek() {
                    quickSeekMs?.let { playerManager.seekTo(it); positionMs = it }
                    quickSeekMs = null
                    quickSeekAccumSec = 0L
                }
                when (event.key) {
                    Key.DirectionRight -> if (duration > 0) {
                        val base = quickSeekMs ?: playerManager.player.currentPosition.coerceAtLeast(0L)
                        quickSeekMs = (base + SEEK_STEP_MS).coerceAtMost(duration)
                        quickSeekAccumSec = if (quickSeekAccumSec < 0) SEEK_STEP_S else quickSeekAccumSec + SEEK_STEP_S
                        true
                    } else {
                        controlsVisible = true; true
                    }
                    Key.DirectionLeft -> if (duration > 0) {
                        val base = quickSeekMs ?: playerManager.player.currentPosition.coerceAtLeast(0L)
                        quickSeekMs = (base - SEEK_STEP_MS).coerceAtLeast(0L)
                        quickSeekAccumSec = if (quickSeekAccumSec > 0) -SEEK_STEP_S else quickSeekAccumSec - SEEK_STEP_S
                        true
                    } else {
                        controlsVisible = true; true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (quickSeekMs != null) commitQuickSeek() else controlsVisible = true
                        true
                    }
                    else -> {
                        commitQuickSeek()
                        controlsVisible = true
                        true
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerManager.player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Hidden — subtitles are drawn by the custom Compose overlay below.
                    subtitleView?.visibility = View.GONE
                }
            },
        )

        val subtitleStyle by playerManager.subtitleStyle.collectAsState()
        PlayerSubtitleView(
            player = playerManager.player,
            style = subtitleStyle,
            modifier = Modifier.fillMaxSize(),
        )

        // Splash / idle screen while waiting for the phone to cast something.
        if (!snapshot.hasMedia) {
            IdleScreen()
        }

        if (controlsVisible && snapshot.hasMedia && !dialogOpen) {
            ControlsScrim(
                title = snapshot.title,
                episodeLabel = snapshot.episodeLabel,
                isPlaying = snapshot.isPlaying,
                ended = snapshot.ended,
                positionProvider = { positionMs },
                durationMs = snapshot.durationMs,
                sourcesEnabled = snapshot.sources.size > 1,
                sourceLabel = snapshot.sources.getOrNull(snapshot.selectedSourceIndex)?.label
                    ?: stringResource(R.string.chip_source),
                subtitlesEnabled = snapshot.subtitles.isNotEmpty(),
                subtitleLabel = when {
                    snapshot.subtitles.isEmpty() -> stringResource(R.string.dialog_subtitles)
                    else -> snapshot.selectedSubtitleIndex
                        ?.let { snapshot.subtitles.getOrNull(it)?.lang?.ifBlank { stringResource(R.string.subtitle_track, it + 1) } }
                        ?: stringResource(R.string.subtitles_off)
                },
                audioEnabled = snapshot.audioTracks.size > 1,
                audioLabel = snapshot.audioTracks.getOrNull(snapshot.selectedAudioIndex)?.lang
                    ?.ifBlank { stringResource(R.string.dialog_audio) }
                    ?: stringResource(R.string.dialog_audio),
                episodesEnabled = snapshot.episodes.isNotEmpty(),
                hasNext = hasNext,
                hasPrevious = hasPrevious,
                playFocus = playFocus,
                onDismiss = { controlsVisible = false },
                onReplay = { playerManager.replay(); controlPokes.tryEmit(Unit) },
                onPlayPause = { playerManager.playPause(); controlPokes.tryEmit(Unit) },
                onSkipOp = { playerManager.seekBy(SKIP_OP_MS); controlPokes.tryEmit(Unit) },
                onSeek = { ms -> playerManager.seekTo(ms); positionMs = ms; controlPokes.tryEmit(Unit) },
                onSources = { showSourceDialog = true },
                onSubtitles = { showSubtitleDialog = true },
                onAudio = { showAudioDialog = true },
                onEpisodes = { showEpisodeDialog = true },
                onNext = {
                    ControlSender.next(positionMs, snapshot.durationMs)
                    playerManager.setAwaitingReload("Loading episode…")
                },
                onPrevious = {
                    ControlSender.previous(positionMs, snapshot.durationMs)
                    playerManager.setAwaitingReload("Loading episode…")
                },
            )
        }

        // Hidden-controls quick-seek feedback: side chevrons + ±seconds + a mini timeline.
        if (snapshot.hasMedia && !controlsVisible && quickSeekMs != null) {
            QuickSeekOverlay(
                accumSec = quickSeekAccumSec,
                positionMs = quickSeekMs ?: positionMs,
                durationMs = snapshot.durationMs,
            )
        }

        // Loading feedback, drawn above the controls. `loading` = fetching / switching source or
        // episode (labelled, opaque); `buffering` = a mid-playback rebuffer (bare spinner).
        if (snapshot.hasMedia && snapshot.loading) {
            LoadingOverlay()
        } else if (snapshot.hasMedia && snapshot.buffering) {
            BufferingSpinner()
        }

        if (showSourceDialog) {
            SourceDialog(
                sources = snapshot.sources,
                selectedIndex = snapshot.selectedSourceIndex,
                onSelect = { index ->
                    showSourceDialog = false
                    // Persist watch time on the phone before the TV-local source switch.
                    ControlSender.save(positionMs, snapshot.durationMs)
                    playerManager.switchSource(index)
                },
                onDismiss = { showSourceDialog = false },
            )
        }

        if (showEpisodeDialog) {
            EpisodeDialog(
                episodes = snapshot.episodes,
                displayMode = snapshot.displayMode,
                currentEpisodeId = snapshot.currentEpisodeId,
                onSelect = { episodeId ->
                    showEpisodeDialog = false
                    ControlSender.selectEpisode(episodeId, positionMs, snapshot.durationMs)
                    playerManager.setAwaitingReload("Loading episode…")
                },
                onDismiss = { showEpisodeDialog = false },
            )
        }

        if (showSubtitleDialog) {
            SubtitleDialog(
                tracks = snapshot.subtitles,
                selectedIndex = snapshot.selectedSubtitleIndex,
                onSelect = { index ->
                    showSubtitleDialog = false
                    playerManager.selectSubtitle(index)
                },
                onDismiss = { showSubtitleDialog = false },
            )
        }

        if (showAudioDialog) {
            AudioDialog(
                tracks = snapshot.audioTracks,
                selectedIndex = snapshot.selectedAudioIndex,
                onSelect = { index ->
                    showAudioDialog = false
                    playerManager.selectAudio(index)
                },
                onDismiss = { showAudioDialog = false },
            )
        }
    }
}

@Composable
private fun ControlsScrim(
    title: String,
    episodeLabel: String,
    isPlaying: Boolean,
    ended: Boolean,
    positionProvider: () -> Long,
    durationMs: Long,
    sourcesEnabled: Boolean,
    sourceLabel: String,
    subtitlesEnabled: Boolean,
    subtitleLabel: String,
    audioEnabled: Boolean,
    audioLabel: String,
    episodesEnabled: Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    playFocus: FocusRequester,
    onDismiss: () -> Unit,
    onReplay: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipOp: () -> Unit,
    onSeek: (Long) -> Unit,
    onSources: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onEpisodes: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
    ) {
        // Top-left: anime title (bold) + episode (italic), mirroring the phone's TopControls.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            if (episodeLabel.isNotBlank()) {
                Text(
                    text = episodeLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                )
            }
        }

        // Center transport — prev / play-pause (emphasized by size) / next, like the phone.
        // This is the topmost focusable row, so pressing UP here collapses the controls.
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.cd_previous_episode),
                onClick = onPrevious,
                enabled = hasPrevious,
                buttonSize = 56.dp,
                iconSize = 32.dp,
            )
            PlayerIconButton(
                imageVector = when {
                    ended -> Icons.Filled.Replay
                    isPlaying -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = if (ended) stringResource(R.string.cd_replay) else stringResource(R.string.cd_play_pause),
                onClick = if (ended) onReplay else onPlayPause,
                modifier = Modifier.focusRequester(playFocus),
                buttonSize = 76.dp,
                iconSize = 44.dp,
            )
            PlayerIconButton(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.cd_next_episode),
                onClick = onNext,
                enabled = hasNext,
                buttonSize = 56.dp,
                iconSize = 32.dp,
            )
        }

        // Bottom: seek bar, then a SpaceBetween row of selector buttons + the +85s button.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Isolated so the 2 Hz position tick only recomposes this row, not the whole scrim.
            SeekBarRow(positionProvider = positionProvider, durationMs = durationMs, onSeek = onSeek)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sourcesEnabled) {
                        PlayerChip(
                            imageVector = Icons.Filled.VideoSettings,
                            label = sourceLabel,
                            contentDescription = stringResource(R.string.cd_change_source),
                            onClick = onSources,
                        )
                    }
                    PlayerChip(
                        imageVector = Icons.Filled.Subtitles,
                        label = subtitleLabel,
                        contentDescription = stringResource(R.string.cd_change_subtitles),
                        onClick = onSubtitles,
                        enabled = subtitlesEnabled,
                    )
                    if (audioEnabled) {
                        PlayerChip(
                            imageVector = Icons.Filled.Audiotrack,
                            label = audioLabel,
                            contentDescription = stringResource(R.string.cd_change_audio),
                            onClick = onAudio,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (episodesEnabled) {
                        PlayerIconButton(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.cd_episodes), onEpisodes)
                    }
                    Button(onClick = onSkipOp) { Text("+85s") }
                }
            }
        }
    }
}

/**
 * Time labels + seek bar. Reads the position via [positionProvider] here (not in [ControlsScrim]),
 * so the ~2 Hz position updates only recompose this row.
 */
@Composable
private fun SeekBarRow(
    positionProvider: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val positionMs = positionProvider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(positionMs.formatMinSec(), style = MaterialTheme.typography.labelLarge)
        SeekTimeline(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeek,
            modifier = Modifier.weight(1f),
        )
        Text(durationMs.formatMinSec(), style = MaterialTheme.typography.labelLarge)
    }
}

/** D-pad-seekable timeline styled like the phone's slider: rounded track, primary fill + thumb. */
@Composable
private fun SeekTimeline(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekable = durationMs > 0
    var focused by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val displayMs = scrubMs ?: positionMs
    val fraction = if (durationMs > 0) (displayMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    LaunchedEffect(scrubMs) {
        val target = scrubMs ?: return@LaunchedEffect
        delay(600)
        onSeek(target)
        scrubMs = null
    }

    val trackHeight = if (focused) 8.dp else 6.dp
    val thumbSize = if (focused) 18.dp else 14.dp

    Box(
        modifier = modifier
            .height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (!seekable || event.type != KeyEventType.KeyDown) {
                    false
                } else when (event.key) {
                    Key.DirectionLeft -> {
                        scrubMs = ((scrubMs ?: positionMs) - SEEK_STEP_MS).coerceAtLeast(0L)
                        true
                    }
                    Key.DirectionRight -> {
                        scrubMs = ((scrubMs ?: positionMs) + SEEK_STEP_MS).coerceAtMost(durationMs)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        scrubMs?.let { onSeek(it); scrubMs = null }
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Track + active fill.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    RoundedCornerShape(50),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(trackHeight)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            )
        }
        // Thumb: a box of width `fraction` places the circle at the current position.
        if (seekable) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(thumbSize)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

/**
 * Hidden-controls quick-seek feedback, mirroring the phone's double-tap: a primary-tinted flash on
 * the seeked half with pulsing chevrons + the accumulated seconds, plus a minimal bottom timeline.
 */
@Composable
private fun QuickSeekOverlay(accumSec: Long, positionMs: Long, durationMs: Long) {
    val forward = accumSec >= 0
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeekChevrons(reversed = !forward)
                Text(
                    text = stringResource(R.string.seek_seconds, abs(accumSec)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        QuickSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/** Three play-triangle chevrons pulsing in sequence (mirrored when reversed) — port of SeekAnimation. */
@Composable
private fun SeekChevrons(reversed: Boolean) {
    val transition = rememberInfiniteTransition(label = "seek")
    @Composable
    fun chevronAlpha(offsetMs: Int) = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(offsetMs),
        ),
        label = "chevron",
    ).value

    val a1 = chevronAlpha(0)
    val a2 = chevronAlpha(200)
    val a3 = chevronAlpha(400)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SeekChevron(alpha = if (reversed) a3 else a1, reversed = reversed)
        SeekChevron(alpha = a2, reversed = reversed)
        SeekChevron(alpha = if (reversed) a1 else a3, reversed = reversed)
    }
}

@Composable
private fun SeekChevron(alpha: Float, reversed: Boolean) {
    Icon(
        imageVector = Icons.Filled.PlayArrow,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .size(30.dp)
            .alpha(alpha)
            .graphicsLayer { if (reversed) scaleX = -1f },
    )
}

/** Minimal timeline (position — track+fill — duration) shown during hidden-controls quick-seek. */
@Composable
private fun QuickSeekBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(positionMs.formatMinSec(), style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    RoundedCornerShape(50),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            )
        }
        Text(durationMs.formatMinSec(), style = MaterialTheme.typography.labelLarge)
    }
}

/** Full-screen loader (scrimmed spinner) for fetching sources / switching source or episode. */
@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Bare centered spinner for a mid-playback rebuffer (keeps the last frame visible). */
@Composable
private fun BufferingSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PlayerIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            disabledContainerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
        modifier = modifier.size(buttonSize),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Small card: icon on the left, current selection name on the right. Opens the matching dialog. */
@Composable
private fun PlayerChip(
    imageVector: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}
