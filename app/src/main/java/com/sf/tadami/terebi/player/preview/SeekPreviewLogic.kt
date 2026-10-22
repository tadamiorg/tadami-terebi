package com.sf.tadami.terebi.player.preview

import android.content.ComponentCallbacks2
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import com.sf.tadami.terebi.player.TvStreamSource

// Ported from the phone app's SeekPreviewLogic.kt (same steps, cache and request rules).

/** Where [positionMs] sits on a [durationMs]-long timeline, from 0 to 1 (0 while the duration is unknown). */
fun seekFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Left edge of a [previewWidth]-wide preview centred over the thumb at [fraction], kept between
 * [boundsStart] and [boundsEnd] (the screen edges). The thumb centre moves from `thumbWidth / 2` to
 * `trackWidth - thumbWidth / 2`. All values are in the same pixel space.
 */
fun seekPreviewLeft(
    fraction: Float,
    trackStart: Float,
    trackWidth: Float,
    thumbWidth: Float,
    previewWidth: Float,
    boundsStart: Float,
    boundsEnd: Float,
): Float {
    val thumbCenter = trackStart + thumbWidth / 2 + fraction.coerceIn(0f, 1f) * (trackWidth - thumbWidth)
    val maxLeft = boundsEnd - previewWidth
    if (maxLeft < boundsStart) return boundsStart
    return (thumbCenter - previewWidth / 2).coerceIn(boundsStart, maxLeft)
}

/**
 * The preview player's tracks: the lowest-bitrate video only, no audio nor subtitles, so a scrub
 * fetches the smallest segments and decodes the smallest frames.
 */
fun previewTrackSelectionParameters(base: TrackSelectionParameters): TrackSelectionParameters {
    return base.buildUpon()
        .setForceLowestBitrate(true)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
}

/** Buffer the main player keeps ahead before preview loads may share the network with it. */
const val MAIN_PLAYER_MIN_BUFFER_AHEAD_MS = 10_000L

/**
 * Whether the main player can spare network for preview loads: it isn't rebuffering, and it has
 * [MAIN_PLAYER_MIN_BUFFER_AHEAD_MS] buffered ahead or the whole video loaded.
 */
fun mainPlayerCanSpareNetwork(
    isBuffering: Boolean,
    bufferedAheadMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
): Boolean {
    if (isBuffering) return false
    return bufferedAheadMs >= MAIN_PLAYER_MIN_BUFFER_AHEAD_MS || (durationMs > 0L && bufferedPositionMs >= durationMs)
}

/** Preview steps: 1% of the duration, so about 100 frames per video, but 5 s at least and 30 s at most. */
const val PREVIEW_BUCKET_MIN_MS = 5_000L
const val PREVIEW_BUCKET_MAX_MS = 30_000L

/** Length of a preview step of a [durationMs]-long video. */
fun previewBucketSizeMs(durationMs: Long): Long = (durationMs / 100).coerceIn(PREVIEW_BUCKET_MIN_MS, PREVIEW_BUCKET_MAX_MS)

/** The preview step [positionMs] falls in; positions out of the video belong to its first or last step. */
fun previewBucketOf(positionMs: Long, durationMs: Long): Int {
    val clamped = positionMs.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
    return (clamped / previewBucketSizeMs(durationMs)).toInt()
}

/** Start and end (exclusive) of the preview step [bucket]; the last step ends with the video. */
fun previewBucketRangeMs(bucket: Int, durationMs: Long): LongRange {
    val size = previewBucketSizeMs(durationMs)
    val start = bucket * size
    val end = (start + size).coerceAtMost(durationMs.coerceAtLeast(start + 1))
    return start until end
}

/**
 * Identity of a stream for the preview frames: its URL with its server and quality. Frames of one identity
 * never change, frames of another are never shown for it.
 */
fun previewSourceIdentity(source: TvStreamSource): String = "${source.url}|${source.server}|${source.quality}"

/** A preview frame: the step [bucket] of the stream [sourceId]. */
data class PreviewFrameKey(val sourceId: String, val bucket: Int)

/**
 * Least recently used preview frames of the current source, bounded in bytes. A new source drops all the
 * frames; frames of another source than the current one are refused.
 */
class PreviewFrameCache<V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val entries = LinkedHashMap<PreviewFrameKey, V>(16, 0.75f, true)

    var sourceId: String? = null
        private set

    var bytes = 0L
        private set

    val size: Int
        get() = entries.size

    operator fun get(key: PreviewFrameKey): V? = entries[key]

    /** Whether the frame is cached, without making it recently used. */
    operator fun contains(key: PreviewFrameKey): Boolean = entries.containsKey(key)

    fun put(key: PreviewFrameKey, value: V) {
        if (key.sourceId != sourceId) return
        entries.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }

    /** Makes [id] the current source; another source than the current one drops every frame. */
    fun setSource(id: String?) {
        if (id == sourceId) return
        clear()
        sourceId = id
    }

    fun clear() {
        entries.clear()
        bytes = 0L
    }
}

/**
 * Which preview step to fetch: only the one being scrubbed to, never one cached or already loading, and
 * one at a time. Scrubbing back to the step loading waits for it.
 */
class BucketRequests {
    var inFlight: Int? = null
        private set

    private var target: Int? = null

    /** The scrub is over [bucket], or null once it ended. */
    fun aim(bucket: Int?) {
        target = bucket
    }

    /** The step to fetch now, marked as loading; null when there's none, it [isDone] already, or one is loading. */
    fun next(isDone: (Int) -> Boolean): Int? {
        if (inFlight != null) return null
        val bucket = target?.takeUnless(isDone) ?: return null
        inFlight = bucket
        return bucket
    }

    /** The fetch of [bucket] ended, its frame cached or given up. */
    fun finished(bucket: Int) {
        if (inFlight == bucket) inFlight = null
    }

    fun clear() {
        inFlight = null
        target = null
    }
}

/**
 * Whether a memory trim drops the preview frames: on memory pressure, or once the app is a background
 * process. Hiding the UI alone keeps them.
 */
@Suppress("DEPRECATION")
fun shouldDropPreviewFrames(trimLevel: Int): Boolean {
    return trimLevel == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        trimLevel == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        trimLevel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
}

/** Instances a hardware decoder must allow for the preview to take one: the main player's and the preview's. */
const val MIN_HARDWARE_DECODER_INSTANCES = 2

/**
 * The decoders the preview may use, in order. TV boxes often run only one or two hardware video decoders at
 * once, so the preview takes a software decoder whenever one handles the format: it never competes with the
 * main player for a hardware one. With no software decoder, only hardware decoders reporting room for
 * [MIN_HARDWARE_DECODER_INSTANCES] are allowed; with none, the list is empty and the preview stays off.
 */
fun <T> previewDecoders(decoders: List<T>, isSoftware: (T) -> Boolean, maxInstances: (T) -> Int): List<T> {
    val software = decoders.filter(isSoftware)
    if (software.isNotEmpty()) return software
    return decoders.filter { maxInstances(it) >= MIN_HARDWARE_DECODER_INSTANCES }
}

/** Whether [errorCode] is a decoder failure (init, decode, capabilities or reclaimed resources). */
fun isDecoderError(errorCode: Int): Boolean =
    errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
