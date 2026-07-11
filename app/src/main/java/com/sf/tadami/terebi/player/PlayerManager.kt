package com.sf.tadami.terebi.player

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sf.tadami.terebi.player.subtitles.CustomSubtitleParserFactory
import com.google.android.gms.cast.MediaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Owns the receiver's ExoPlayer and a MediaSessionCompat. The session token is handed
 * to Cast's MediaManager so the phone sender's transport controls (play/pause/seek/stop)
 * drive ExoPlayer, and ExoPlayer state is mirrored back into the session's PlaybackState.
 *
 * On top of the transport bridge it parses the phone's MediaInfo (title/episode metadata +
 * the `availableSources` / `selectedSource` customData) so the TV can render the full control
 * overlay and switch source/subtitle locally — mirroring `EpisodeActivity.loadRemoteMedia()`.
 */
@OptIn(UnstableApi::class)
class PlayerManager(context: Context) {

    data class Snapshot(
        val hasMedia: Boolean = false,
        val title: String = "",
        val episodeLabel: String = "",
        val isPlaying: Boolean = false,
        /** Playback reached the end — the UI shows a replay control. */
        val ended: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val buffering: Boolean = false,
        /** A source switch / first-frame buffer is in progress. */
        val loading: Boolean = false,
        val loadingLabel: String = "",
        val sources: List<TvStreamSource> = emptyList(),
        val selectedSourceIndex: Int = -1,
        val subtitles: List<TvSubtitleTrack> = emptyList(),
        /** null = subtitles off. */
        val selectedSubtitleIndex: Int? = null,
        val episodes: List<TvEpisode> = emptyList(),
        val displayMode: String = "NUMBER",
        val currentEpisodeId: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Same data-source stack as the phone's VideoPlayer.kt — no proxy; headers are injected
    // here (or a User-Agent fallback), media is cached, and VTT uses the custom parser.
    @Volatile private var currentHeaders: Map<String, String>? = null
    @Volatile private var currentUserAgent: String? = null

    private val networkHelper = PlayerNetworkHelper(context)

    // Route HTTP(S) through the trust-all, HTTP/1.1-pinned OkHttp client (see PlayerNetworkHelper):
    // trust-all bound to the client actually applies here (the JVM-default socket factory is
    // ignored on this device), and HTTP/1.1 avoids the h2 400 some servers return. DefaultDataSource
    // still handles file/asset/content URIs.
    private val httpDataSourceFactory = OkHttpDataSource.Factory(networkHelper.okHttpClient)
    private val upstreamDataSource = DefaultDataSource.Factory(context, httpDataSourceFactory)
    private val cacheFactory = CacheDataSource.Factory()
        .setCache(networkHelper.cache)
        .setUpstreamDataSourceFactory(upstreamDataSource)
    private val resolvingFactory = ResolvingDataSource.Factory(cacheFactory) { dataSpec ->
        // Always send a browser User-Agent. Some hosts (e.g. sibnet.ru) reject OkHttp's default
        // "okhttp/x.y" UA with 400; source headers frequently carry only a Referer, so OkHttp
        // would fill in its own UA unless we set one. Source headers win if they specify a UA.
        val requestHeaders = LinkedHashMap<String, String>()
        currentHeaders?.let { requestHeaders.putAll(it) }
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestHeaders["User-Agent"] = currentUserAgent ?: DEFAULT_USER_AGENT
        }
        dataSpec.withRequestHeaders(requestHeaders)
    }
    private val mediaSourceFactory = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(resolvingFactory)
        .setSubtitleParserFactory(CustomSubtitleParserFactory())
    // TV heap is ~128 MiB — hard-cap the on-heap media buffer. (The phone uses 300s +
    // prioritizeTimeOverSize(true), which only fits because it has largeHeap + a bigger cap;
    // that config holds ~600s of compressed media on-heap and OOMs on the TV.)
    private val loadControl = DefaultLoadControl.Builder()
        .setTargetBufferBytes(24 * 1024 * 1024)
        .setBufferDurationsMs(15_000, 50_000, 1_500, 3_500)
        .setBackBuffer(10_000, false)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .build()

    val session = MediaSessionCompat(context, "TadamiTerebi")

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private var sources: List<TvStreamSource> = emptyList()
    private var selectedSourceIndex: Int = -1
    private var currentSubtitles: List<TvSubtitleTrack> = emptyList()
    private var selectedSubtitleIndex: Int? = null
    private var episodes: List<TvEpisode> = emptyList()
    private var displayMode: String = "NUMBER"
    private var currentEpisodeId: Long = 0L

    private val _themeColors = MutableStateFlow<TvThemeColors?>(null)
    val themeColors: StateFlow<TvThemeColors?> = _themeColors

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { player.play() }
            override fun onPause() { player.pause() }
            override fun onSeekTo(pos: Long) { player.seekTo(pos.coerceAtLeast(0L)) }
            override fun onStop() { player.stop() }
        })
        session.isActive = true

        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                setLoading(false)
            }

            override fun onEvents(p: Player, events: Player.Events) {
                if (p.playbackState == Player.STATE_READY) setLoading(false)
                updatePlaybackState()
                publishDuration(p.duration)
                refreshSnapshot()
            }
        })
    }

    /** Initial load from the phone sender. */
    fun load(info: MediaInfo, startMs: Long, autoplay: Boolean) {
        parseMetadata(info)
        val source = sources.getOrNull(selectedSourceIndex)
        if (source != null) {
            playSource(source, startMs, autoplay, loadingLabel = "Loading…")
        } else {
            // Fallback: play exactly what the sender resolved, subtitles from MediaInfo tracks.
            playRaw(info, startMs, autoplay)
        }
        refreshSnapshot()
    }

    /** TV-local source switch: rebuilds the URL like the phone and resumes at the same position. */
    fun switchSource(index: Int) {
        val source = sources.getOrNull(index) ?: return
        selectedSourceIndex = index
        playSource(
            source = source,
            startMs = player.currentPosition.coerceAtLeast(0L),
            autoplay = true,
            loadingLabel = "Switching source…",
        )
        refreshSnapshot()
    }

    private fun playSource(
        source: TvStreamSource,
        startMs: Long,
        autoplay: Boolean,
        loadingLabel: String,
    ) {
        currentSubtitles = source.subtitleTracks
        currentHeaders = source.headers
        val item = MediaItem.Builder()
            .setUri(source.url)
            .apply {
                val configs = source.subtitleTracks.mapIndexedNotNull { index, sub ->
                    val mime = resolveSubtitleMime(sub.mimeType, sub.url) ?: return@mapIndexedNotNull null
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(mime)
                        .setLanguage(convertToIetfLanguageTag(sub.lang) + index)
                        .build()
                }
                if (configs.isNotEmpty()) setSubtitleConfigurations(configs)
            }
            .build()
        setLoading(true, loadingLabel)
        player.setMediaItem(item, startMs)
        applySubtitleSelection(selectedSubtitleIndex)
        player.prepare()
        player.playWhenReady = autoplay
    }

    private fun playRaw(info: MediaInfo, startMs: Long, autoplay: Boolean) {
        val uri = info.contentUrl ?: info.contentId ?: return
        val subs = info.mediaTracks
            ?.filter { it.type == com.google.android.gms.cast.MediaTrack.TYPE_TEXT }
            ?.mapNotNull { track ->
                val url = track.contentId ?: return@mapNotNull null
                TvSubtitleTrack(url = url, lang = track.language ?: "", mimeType = track.contentType ?: "text/x-unknown")
            }
            .orEmpty()
        currentSubtitles = subs
        currentHeaders = null
        val item = MediaItem.Builder()
            .setUri(uri)
            .apply {
                val configs = subs.mapIndexedNotNull { index, sub ->
                    val mime = resolveSubtitleMime(sub.mimeType, sub.url) ?: return@mapIndexedNotNull null
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(mime)
                        .setLanguage(convertToIetfLanguageTag(sub.lang) + index)
                        .build()
                }
                if (configs.isNotEmpty()) setSubtitleConfigurations(configs)
            }
            .build()
        setLoading(true, "Loading…")
        player.setMediaItem(item, startMs)
        player.prepare()
        player.playWhenReady = autoplay
    }

    private fun parseMetadata(info: MediaInfo) {
        val meta = info.metadata
        val title = meta?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE).orEmpty()
        val episode = meta?.getString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE).orEmpty()

        val custom = info.customData
        currentUserAgent = custom?.optString("userAgent")?.ifBlank { null }
        val available = custom?.optString("availableSources")?.ifBlank { null }?.let { raw ->
            runCatching { json.decodeFromString<List<TvStreamSource>>(raw) }.getOrNull()
        }
        val selected = custom?.optString("selectedSource")?.ifBlank { null }?.let { raw ->
            runCatching { json.decodeFromString<TvStreamSource>(raw) }.getOrNull()
        }

        sources = when {
            !available.isNullOrEmpty() -> available
            selected != null -> listOf(selected)
            else -> emptyList()
        }
        selectedSourceIndex = when {
            selected != null -> sources.indexOfFirst { it.url == selected.url }.coerceAtLeast(0)
            sources.isNotEmpty() -> 0
            else -> -1
        }
        selectedSubtitleIndex = null

        episodes = custom?.optString("episodes")?.ifBlank { null }?.let { raw ->
            runCatching { json.decodeFromString<List<TvEpisode>>(raw) }.getOrNull()
        } ?: emptyList()
        displayMode = custom?.optString("displayMode")?.ifBlank { null } ?: "NUMBER"
        currentEpisodeId = custom?.optLong("episodeId") ?: 0L
        custom?.optString("theme")?.ifBlank { null }?.let { raw ->
            runCatching { json.decodeFromString<TvThemeColors>(raw) }.getOrNull()
        }?.let { _themeColors.value = it }

        _snapshot.update { it.copy(title = title, episodeLabel = episode, hasMedia = true) }
    }

    /** EDIT_TRACKS from the phone sender — track ids are 1-based. */
    fun selectTextTrack(trackId: Long?) {
        val index = trackId?.let { (it - 1).toInt() }?.takeIf { it >= 0 }
        selectSubtitle(index)
    }

    /** TV-local subtitle switch by list index (null = off). */
    fun selectSubtitle(index: Int?) {
        selectedSubtitleIndex = index
        applySubtitleSelection(index)
        refreshSnapshot()
    }

    private fun applySubtitleSelection(index: Int?) {
        val params = player.trackSelectionParameters.buildUpon()
        if (index == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            currentSubtitles.getOrNull(index)?.let { sub ->
                params.setPreferredTextLanguage(convertToIetfLanguageTag(sub.lang) + index)
            }
        }
        player.trackSelectionParameters = params.build()
    }

    /** Restart the finished stream from the beginning. */
    fun replay() {
        player.seekTo(0)
        player.play()
    }

    fun playPause() = if (player.isPlaying) player.pause() else player.play()
    fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0L))
    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0L))

    /** Show the loading overlay while we wait for the phone to re-resolve & re-load an episode. */
    fun setAwaitingReload(label: String) = setLoading(true, label)

    private fun setLoading(loading: Boolean, label: String = "") {
        _snapshot.update {
            if (loading) it.copy(loading = true, loadingLabel = label)
            else it.copy(loading = false, loadingLabel = "")
        }
    }

    private fun refreshSnapshot() {
        _snapshot.update {
            it.copy(
                isPlaying = player.isPlaying,
                ended = player.playbackState == Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.coerceAtLeast(0L),
                buffering = player.playbackState == Player.STATE_BUFFERING,
                sources = sources,
                selectedSourceIndex = selectedSourceIndex,
                subtitles = currentSubtitles,
                selectedSubtitleIndex = selectedSubtitleIndex,
                episodes = episodes,
                displayMode = displayMode,
                currentEpisodeId = currentEpisodeId,
            )
        }
    }

    private fun updatePlaybackState() {
        val state = when {
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP,
            )
            .setState(state, player.currentPosition.coerceAtLeast(0L), player.playbackParameters.speed)
            .build()
        session.setPlaybackState(playbackState)
    }

    private var publishedDuration = 0L

    /** Publishes the real duration into the session metadata so senders can read total time. */
    private fun publishDuration(durationMs: Long) {
        if (durationMs <= 0L || durationMs == publishedDuration) return
        publishedDuration = durationMs
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, _snapshot.value.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, _snapshot.value.episodeLabel)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build(),
        )
    }

    /** Called from the activity's broadcast ticker to keep the session position fresh. */
    fun pumpPlaybackState() {
        updatePlaybackState()
        publishDuration(player.duration)
    }

    fun release() {
        session.isActive = false
        session.release()
        player.release()
        runCatching { networkHelper.cache.release() }
    }

    companion object {
        /** Fallback UA when the sender didn't supply one, mirroring the phone's default. */
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    }
}
