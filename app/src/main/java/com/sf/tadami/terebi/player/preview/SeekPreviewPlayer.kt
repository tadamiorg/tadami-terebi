package com.sf.tadami.terebi.player.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.sf.tadami.terebi.player.preview.SeekPreviewFrames.SidecarState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/**
 * Frames of the seekbar preview: one per preview step (see [previewBucketSizeMs]), for a downloaded episode the phone
 * serves only ([SeekPreviewFrames] keeps them and tells what to decode). A stream gets no preview at all: nothing is
 * fetched nor decoded, its scrubs show the time alone. Ported from the phone app's SeekPreviewPlayer.
 *
 * A downloaded episode's frames come first from its sidecar, fetched once through the main player's data source
 * ([readSidecar]: same headers and cache) and decoded off the main thread: every step it covers shows at once.
 *
 * Without a usable sidecar, a step's frame comes from a second, muted ExoPlayer that reads the episode through the
 * main player's media source factory but has its own bandwidth meter, track selection and load control, so it never
 * changes what the main player buffers or picks: the step scrubbed to first, then the others in the background,
 * nearest the playback position first, while the main player plays at ease ([mainPlayback]). It decodes into a hidden
 * [TextureView] ([attachSurface]), the frame is scaled into a bitmap and cached. Main thread only.
 *
 * TV boxes often allow only one or two video decoders at once, and the main playback comes first: the
 * preview decodes in software when it can (see [previewDecoders]), lets go of its decoder shortly after its last
 * decode, and turns itself off for the session on any decoder error, its own or the main player's.
 */
@UnstableApi
class SeekPreviewPlayer(
    private val context: Context,
    private val mainPlayback: () -> MainPlayback,
    private val readSidecar: (url: String) -> ByteArray?,
) : TextureView.SurfaceTextureListener {

    private var player: ExoPlayer? = null
    private var sourceFactory: (() -> MediaSource)? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null

    private val frames = SeekPreviewFrames<Bitmap>(FRAME_CACHE_BYTES) { it.allocationByteCount.toLong() }
    private val handler = Handler(Looper.getMainLooper())
    private val pump = Runnable { pumpDecodes() }
    private val tick = object : Runnable {
        override fun run() {
            pumpDecodes()
            handler.postDelayed(this, PREFETCH_TICK_MS)
        }
    }
    private val giveUp = Runnable { onFetchTimeout() }
    private val idleRelease = Runnable { if (fingerBucket == null && frames.decoding == null) release() }
    private val sidecarReader = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "SeekPreviewSidecar")
    }

    private var fingerBucket: Int? = null
    private var videoAspectRatio = DEFAULT_ASPECT_RATIO
    private var fetchStartedAtNs = 0L
    private var nextPrefetchAtMs = 0L

    // The decoder released a frame since the fetch started: the next texture update is the step's frame
    private var frameReleasedSinceFetch = false

    /** Changes as the cached frames do: read by the cards, so a frame shows as soon as it's cached. */
    var framesVersion by mutableIntStateOf(0)
        private set

    /**
     * Sets the source to preview: [sourceId] its [previewSourceIdentity], [mediaUrl] where it plays from, [factory]
     * its video for the decoding. Another source drops the frames; a stream gets no preview at all.
     */
    fun setSource(sourceId: String?, mediaUrl: String?, factory: (() -> MediaSource)?) {
        if (sourceId == frames.sourceId) return
        release()
        handler.removeCallbacks(tick)
        frames.setSource(sourceId, mediaUrl)
        sourceFactory = factory.takeIf { frames.local }
        if (frames.local) {
            Log.i(TAG, "Downloaded episode: seekbar preview on")
            fetchSidecar()
            handler.postDelayed(tick, PREFETCH_TICK_MS)
        } else if (sourceId != null) {
            Log.i(TAG, "Streamed episode: no seekbar preview")
        }
        publish()
    }

    /** The cached frame of the step [positionMs] falls in; null (the time alone) until it's cached, and for a stream. */
    fun frameAt(positionMs: Long, durationMs: Long): Bitmap? {
        if (!frames.local || durationMs <= 0L) return null
        return frames.frameOf(previewBucketOf(positionMs, durationMs))
    }

    /** The scrub is over [positionMs]: its step is decoded first unless it's cached. Nothing for a stream. */
    fun scrubTo(positionMs: Long, durationMs: Long) {
        if (!frames.local || durationMs <= 0L) return
        handler.removeCallbacks(idleRelease)
        syncDuration(durationMs)
        // Frames a memory trim dropped come back
        fetchSidecar()
        val bucket = previewBucketOf(positionMs, durationMs)
        if (bucket == fingerBucket) return
        fingerBucket = bucket
        frames.aim(bucket)
        pumpDecodes()
    }

    /** The scrub ended: the decode in flight still lands in the cache. */
    fun endScrub() {
        fingerBucket = null
        frames.aim(null)
        handler.removeCallbacks(pump)
        scheduleIdleRelease()
    }

    /** Frees the decoder, the loads and the playback thread. The frames are kept. */
    fun release() {
        fingerBucket = null
        frames.aim(null)
        handler.removeCallbacks(pump)
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(idleRelease)
        frames.decoding?.let(frames::onDecodeDropped)
        if (player != null) Log.d(TAG, "Preview player released")
        player?.release()
        player = null
    }

    /** The playback screen is gone: releases everything, frames included. */
    fun destroy() {
        release()
        handler.removeCallbacks(tick)
        frames.setSource(null, null)
        sourceFactory = null
        sidecarReader.shutdownNow()
        publish()
    }

    fun onTrimMemory(level: Int) {
        if (!shouldDropPreviewFrames(level)) return
        frames.trim()
        publish()
    }

    /** A decoder failed: the preview decodes nothing more for the session, so it never takes one from the main player again. */
    fun disableForSession() {
        if (frames.decoderDisabled) return
        Log.w(TAG, "Seekbar preview decoding disabled for the session")
        frames.disableDecoding()
        release()
    }

    fun attachSurface(view: TextureView) {
        textureView = view
        view.surfaceTextureListener = this
        view.surfaceTexture?.let { onSurfaceTextureAvailable(it, view.width, view.height) }
    }

    fun detachSurface(view: TextureView) {
        if (textureView !== view) return
        view.surfaceTextureListener = null
        releaseSurface()
        textureView = null
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture).also { player?.setVideoSurface(it) }
        pumpDecodes()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releaseSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        captureFrame()
    }

    private fun releaseSurface() {
        surface?.let {
            player?.clearVideoSurface(it)
            it.release()
        }
        surface = null
    }

    /** Lets the cards show what the cache now holds. */
    private fun publish() {
        framesVersion = frames.changes
    }

    /** The main player's [durationMs]: a sidecar read is checked against it once it's known. */
    private fun syncDuration(durationMs: Long) {
        val before = frames.sidecarState
        frames.setDuration(durationMs)
        if (before == SidecarState.READ) logSidecarApplied()
        publish()
    }

    private fun logSidecarApplied() {
        when (frames.sidecarState) {
            SidecarState.APPLIED -> Log.i(TAG, "Preview sidecar applied: ${frames.size} steps cached, ${frames.bytes / 1024} KiB")
            SidecarState.UNUSABLE -> Log.i(TAG, "Preview sidecar of another duration than ${frames.durationMs} ms: frames decoded here")
            else -> Unit
        }
    }

    /** Reads the downloaded episode's sidecar off the main thread, its frames decoded as the cache keeps them. */
    private fun fetchSidecar() {
        val url = frames.sidecarUrl ?: return
        if (frames.sidecarState != SidecarState.WANTED) return
        frames.onSidecarFetching()
        val sourceId = frames.sourceId
        val startedAt = SystemClock.elapsedRealtime()
        try {
            sidecarReader.execute {
                val sidecar = readSidecar(url)?.let { PreviewSidecar.decode(it, videoLength = null) }
                val decoded = sidecar?.let(::decodeFrames).orEmpty()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                handler.post { onSidecarRead(sourceId, sidecar, decoded, elapsedMs) }
            }
        } catch (_: RejectedExecutionException) {
            // Destroyed meanwhile
        }
    }

    private fun onSidecarRead(sourceId: String?, sidecar: PreviewSidecar?, decoded: Map<Int, Bitmap>, elapsedMs: Long) {
        if (!frames.onSidecarRead(sourceId, sidecar, decoded)) return
        if (sidecar == null) {
            Log.i(TAG, "No usable preview sidecar: frames decoded here")
        } else {
            Log.i(TAG, "Preview sidecar read: ${decoded.size} frames of a ${sidecar.durationMs} ms video in $elapsedMs ms")
            logSidecarApplied()
        }
        publish()
        pumpDecodes()
    }

    private fun scheduleIdleRelease() {
        handler.removeCallbacks(idleRelease)
        if (player != null) handler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private fun pumpDecodes() {
        handler.removeCallbacks(pump)
        if (!frames.local) return
        val main = mainPlayback()
        syncDuration(main.durationMs)
        // Decodes only while the screen shows, into a surface, one step at a time
        val view = textureView ?: return
        if (surface == null || view.windowVisibility != View.VISIBLE || sourceFactory == null || frames.decoding != null) return
        if (!main.canSpareNetwork) {
            // Leaves the network to the main player while its buffer is short
            if (fingerBucket != null) handler.postDelayed(pump, WAIT_FOR_MAIN_PLAYER_MS)
            return
        }
        // The background decoding: only while the main player plays or pauses at ease, with a gap after each step
        val prefetch = main.ready && SystemClock.elapsedRealtime() >= nextPrefetchAtMs
        frames.nextDecode(main.positionMs, prefetch)?.let(::fetch)
    }

    private fun fetch(bucket: Int) {
        val range = previewBucketRangeMs(bucket, frames.durationMs)
        val targetMs = (range.first + range.last + 1) / 2
        // Any frame of the step will do: a keyframe in it when there's one, saving the decode up to the middle
        val toleranceUs = (range.last + 1 - range.first) / 2 * 1000
        val previewPlayer = player ?: create()
        if (previewPlayer == null) {
            // Its video can't be read: nothing is decoded for it
            frames.failSource()
            return
        }
        Log.d(TAG, "Fetching step $bucket at $targetMs ms")
        fetchStartedAtNs = System.nanoTime()
        frameReleasedSinceFetch = false
        handler.postDelayed(giveUp, FETCH_TIMEOUT_MS)
        previewPlayer.setSeekParameters(SeekParameters(toleranceUs, toleranceUs))
        previewPlayer.seekTo(targetMs)
        if (previewPlayer.playbackState == Player.STATE_IDLE) previewPlayer.prepare()
    }

    private fun captureFrame() {
        val bucket = frames.decoding ?: return
        if (!frameReleasedSinceFetch) return
        val view = textureView ?: return
        val height = (PREVIEW_FRAME_WIDTH_PX / videoAspectRatio).roundToInt().coerceAtLeast(1)
        val captured = view.getBitmap(PREVIEW_FRAME_WIDTH_PX, height) ?: return
        // A video frame has no alpha: RGB_565 keeps twice as many frames in the cache
        val frame = captured.copy(Bitmap.Config.RGB_565, false)?.also { captured.recycle() } ?: captured
        frames.onFrame(bucket, frame)
        Log.d(TAG, "Cached step $bucket: ${frames.size} frames, ${frames.bytes / 1024} KiB")
        onDecodeEnded()
    }

    private fun onFetchTimeout() {
        val bucket = frames.decoding ?: return
        // A late frame mustn't be taken for the next step: drop the player, the next fetch makes a new one
        Log.w(TAG, "Step $bucket timed out, preview player state ${player?.playbackState} at ${player?.currentPosition} ms")
        player?.release()
        player = null
        frames.onNoFrame(bucket)
        onDecodeEnded()
    }

    private fun onDecodeEnded() {
        handler.removeCallbacks(giveUp)
        nextPrefetchAtMs = SystemClock.elapsedRealtime() + PREFETCH_GAP_MS
        publish()
        if (fingerBucket == null) scheduleIdleRelease()
        pumpDecodes()
    }

    private fun create(): ExoPlayer? {
        val mediaSource = runCatching { sourceFactory?.invoke() }
            .onFailure { Log.w(TAG, "Preview source creation failed", it) }
            .getOrNull() ?: return null
        val previewPlayer = ExoPlayer.Builder(context, videoOnlyRenderers(context))
            // Its own meter: preview loads must not move the main player's bitrate estimate
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).build())
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Loads about one segment around the target, nothing more
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, MIN_BUFFER_MS, MIN_BUFFER_MS)
                    .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .setBackBuffer(0, false)
                    .build()
            )
            .build()
        previewPlayer.trackSelectionParameters = previewTrackSelectionParameters(previewPlayer.trackSelectionParameters)
        previewPlayer.volume = 0f
        previewPlayer.playWhenReady = false
        // Coalesces the seeks issued while one is still loading or decoding
        previewPlayer.setScrubbingModeEnabled(true)
        previewPlayer.setVideoFrameMetadataListener { _, releaseTimeNs, _, _ ->
            // Playback thread: the frame is about to be queued to the surface
            handler.post { if (releaseTimeNs >= fetchStartedAtNs) frameReleasedSinceFetch = true }
        }
        previewPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.d(TAG, "Preview decoder: $decoderName")
            }
        })
        previewPlayer.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Seekbar preview failed (${error.errorCodeName})", error)
                if (isDecoderError(error.errorCode)) {
                    handler.post { disableForSession() }
                    return
                }
                // Not retried on every scrub: no new frame until the source changes
                frames.failSource()
                handler.post { release() }
            }
        })
        surface?.let(previewPlayer::setVideoSurface)
        previewPlayer.setMediaSource(mediaSource)
        player = previewPlayer
        return previewPlayer
    }

    private companion object {
        const val TAG = "SeekPreviewPlayer"
        const val WAIT_FOR_MAIN_PLAYER_MS = 250L
        const val FETCH_TIMEOUT_MS = 10_000L
        // The decoder is let go this long after its last decode, leaving the main player alone between scrubs
        const val IDLE_RELEASE_MS = 5_000L
        // The background decoding looks at the main player this often, and leaves it this long after each step
        const val PREFETCH_TICK_MS = 1_000L
        const val PREFETCH_GAP_MS = 1_000L
        const val MIN_BUFFER_MS = 500
        const val MAX_BUFFER_MS = 1_000
        const val TARGET_BUFFER_BYTES = 4 * 1024 * 1024
        const val DEFAULT_ASPECT_RATIO = 16f / 9f

        // 256×144 RGB_565 frames (72 KiB): 12 MiB keep about 170 of them, every step of a video up to 85 min
        const val FRAME_CACHE_BYTES = 12L * 1024 * 1024

        /** The sidecar's JPEGs as bitmaps, by its own steps: RGB_565 like the frames decoded here. */
        fun decodeFrames(sidecar: PreviewSidecar): Map<Int, Bitmap> {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            return sidecar.entries.mapNotNull { entry ->
                val jpeg = entry.jpeg ?: return@mapNotNull null
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.let { entry.step to it }
            }.toMap()
        }

        /** Software decoders first; hardware ones only when none, and only with a spare instance. */
        val previewCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            previewDecoders(
                decoders = all,
                isSoftware = { it.softwareOnly },
                maxInstances = { it.capabilities?.maxSupportedInstances ?: 0 },
            ).also { allowed ->
                if (allowed.isEmpty() && all.isNotEmpty()) {
                    Log.w(TAG, "No decoder to spare for $mimeType among ${all.map { it.name }}")
                }
            }
        }

        /** A single video renderer: no audio, text nor metadata renderer is even created. */
        fun videoOnlyRenderers(context: Context) = RenderersFactory { handler, videoListener, _, _, _ ->
            arrayOf(
                MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(previewCodecSelector)
                    .setEventHandler(handler)
                    .setEventListener(videoListener)
                    .setEnableDecoderFallback(true)
                    .build()
            )
        }
    }
}

/** Bytes past which a sidecar isn't read: 300 steps of 16 KB JPEGs are under 5 MB. */
private const val MAX_SIDECAR_BYTES = 16 * 1024 * 1024
private const val SIDECAR_READ_BUFFER_BYTES = 64 * 1024

/**
 * The whole preview sidecar at [url], read through [dataSource] (the main player's: headers, User-Agent, cache).
 * Null when the phone has none (404), on any error, or past [MAX_SIDECAR_BYTES]. Blocking.
 */
@UnstableApi
fun readPreviewSidecar(dataSource: DataSource, url: String): ByteArray? {
    return try {
        val length = dataSource.open(DataSpec(Uri.parse(url)))
        if (length > MAX_SIDECAR_BYTES) return null
        val out = ByteArrayOutputStream(if (length > 0) length.toInt() else SIDECAR_READ_BUFFER_BYTES)
        val buffer = ByteArray(SIDECAR_READ_BUFFER_BYTES)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_SIDECAR_BYTES) return null
        }
        out.toByteArray()
    } catch (e: IOException) {
        Log.i("SeekPreviewPlayer", "No preview sidecar: $e")
        null
    } finally {
        runCatching { dataSource.close() }
    }
}
