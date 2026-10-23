package com.sf.tadami.terebi.player.preview

/**
 * The seekbar preview's frames and the step to decode next, Android aside ([SeekPreviewPlayer] reads, decodes and
 * shows them). Main thread only.
 *
 * Only a downloaded episode the phone serves ([isLocalMediaUrl]) has preview frames. A stream has none: nothing is
 * fetched nor decoded for it, so no preview player is ever made, and a scrub shows the time alone.
 *
 * A downloaded episode's frames come from its sidecar ([previewSidecarUrl]) when the phone has a usable one for the
 * video the player plays: every step it covers is cached at once. Without one they're decoded on the receiver: the
 * step scrubbed to first, then in the background nearest the playback position ([coarseToFineOrder]) while the
 * cache has room. A step shows its frame only once it's cached: never another step's, never one still loading.
 */
class SeekPreviewFrames<F : Any>(maxBytes: Long, private val sizeOf: (F) -> Long) {

    /** Where the sidecar of the downloaded episode playing is at. */
    enum class SidecarState {
        /** A stream, or no source: there's none. */
        NONE,

        /** To fetch: the source is new, or its frames were dropped. */
        WANTED,
        FETCHING,

        /** Read, its frames decoded: waits for the player's duration to be checked against. */
        READ,

        /** Its frames are cached. */
        APPLIED,

        /** None, or not for the video playing: the frames are decoded on the receiver. */
        UNUSABLE,
    }

    private val frames = PreviewFrameCache(maxBytes, sizeOf)

    // Steps known to give no frame (none in the sidecar, or none in time): never decoded for the source
    private val unavailable = mutableSetOf<Int>()
    private val requests = BucketRequests()

    // The sidecar read and its frames decoded, by its own steps, until the player's duration is known
    private var pending: Pair<PreviewSidecar, Map<Int, F>>? = null

    // Bytes of the last frame cached: the background decoding stops when another one wouldn't fit
    private var frameBytes = 0L

    // A trim dropped the frames: the background decoding waits for the next scrub to make them again
    private var prefetchPaused = false

    var sourceId: String? = null
        private set

    /** The source is a downloaded episode: it has preview frames. */
    var local = false
        private set

    /** Where the phone serves the downloaded episode's sidecar; null for a stream. */
    var sidecarUrl: String? = null
        private set

    var sidecarState = SidecarState.NONE
        private set

    var durationMs = 0L
        private set

    /** The source's preview player failed: no new frame is decoded until the source changes. */
    var failed = false
        private set

    /** A decoder failed: nothing is decoded until the app restarts. The cached frames still show. */
    var decoderDisabled = false
        private set

    /** Grows on every change of the cached frames: what shows may have changed. */
    var changes = 0
        private set

    /** The step being decoded. */
    val decoding: Int?
        get() = requests.inFlight

    val size: Int
        get() = frames.size

    val bytes: Long
        get() = frames.bytes

    /** Plays [sourceId] from [mediaUrl]; another source drops every frame. False when it's the one playing. */
    fun setSource(sourceId: String?, mediaUrl: String?): Boolean {
        if (sourceId == this.sourceId) return false
        this.sourceId = sourceId
        frames.setSource(sourceId)
        local = sourceId != null && mediaUrl != null && isLocalMediaUrl(mediaUrl)
        sidecarUrl = mediaUrl?.takeIf { local }?.let(::previewSidecarUrl)
        sidecarState = when {
            sidecarUrl != null -> SidecarState.WANTED
            local -> SidecarState.UNUSABLE
            else -> SidecarState.NONE
        }
        unavailable.clear()
        requests.clear()
        pending = null
        frameBytes = 0L
        prefetchPaused = false
        durationMs = 0L
        failed = false
        changes++
        return true
    }

    /** The player's duration, once known: a sidecar read meanwhile is checked against it. */
    fun setDuration(durationMs: Long) {
        if (durationMs <= 0L) return
        this.durationMs = durationMs
        applySidecar()
    }

    /** The sidecar is being fetched. */
    fun onSidecarFetching() {
        if (sidecarState == SidecarState.WANTED) sidecarState = SidecarState.FETCHING
    }

    /**
     * The sidecar fetched for [sourceId]: [sidecar] (null when the phone has none, or it isn't valid), its frames
     * [decoded] by its own steps. False when it comes too late, for another source.
     */
    fun onSidecarRead(sourceId: String?, sidecar: PreviewSidecar?, decoded: Map<Int, F>): Boolean {
        if (sourceId != this.sourceId || sidecarState != SidecarState.FETCHING) return false
        if (sidecar == null) {
            sidecarState = SidecarState.UNUSABLE
            return true
        }
        pending = sidecar to decoded
        sidecarState = SidecarState.READ
        applySidecar()
        return true
    }

    /** Caches the frames of the sidecar read, each step of the player's timeline taking the one of its time. */
    private fun applySidecar() {
        val (sidecar, decoded) = pending ?: return
        val duration = durationMs.takeIf { it > 0L } ?: return
        pending = null
        if (!sidecar.matchesDuration(duration)) {
            sidecarState = SidecarState.UNUSABLE
            return
        }
        sidecarState = SidecarState.APPLIED
        sidecarStepEntries(sidecar, duration).forEach { (step, entry) ->
            val frame = decoded[entry.step]
            when {
                frame != null -> put(step, frame)
                // The phone got no frame there in time: none is decoded here either
                !entry.hasFrame -> unavailable += step
            }
        }
        changes++
    }

    /** The cached frame of [bucket]; null until it's cached. */
    fun frameOf(bucket: Int): F? = frames[key(bucket)]

    /** Cached, or known to give no frame: nothing to decode. */
    fun isDone(bucket: Int): Boolean = key(bucket) in frames || bucket in unavailable

    /** The scrub is over [bucket], or null once it ended: that step is decoded first. */
    fun aim(bucket: Int?) {
        requests.aim(bucket)
        if (bucket != null) prefetchPaused = false
    }

    /**
     * The step to decode now, marked as decoding; null when there's none. Nothing for a stream, nor before the
     * sidecar is known not to cover the step, nor while a step decodes. The step scrubbed to comes first; then,
     * when [prefetch], the one nearest the playback position [positionMs] in [coarseToFineOrder], while the cache
     * has room.
     */
    fun nextDecode(positionMs: Long, prefetch: Boolean): Int? {
        if (!decodes()) return null
        return requests.next(fallback = { if (prefetch) prefetchStep(positionMs) else null }, isDone = ::isDone)
    }

    private fun decodes(): Boolean =
        local && !failed && !decoderDisabled && durationMs > 0L &&
            (sidecarState == SidecarState.APPLIED || sidecarState == SidecarState.UNUSABLE)

    private fun prefetchStep(positionMs: Long): Int? {
        if (prefetchPaused || !frames.hasRoomFor(frameBytes)) return null
        // A leftover end step too short to render a frame in isn't decoded ahead
        val last = phonePreviewBucketOf(durationMs - 1, durationMs)
        val center = previewBucketOf(positionMs, durationMs)
        return coarseToFineOrder(center, last, PREFETCH_NEAR_STEPS).firstOrNull { !isDone(it) }
    }

    /** The decode of [bucket] gave [frame]. */
    fun onFrame(bucket: Int, frame: F) {
        put(bucket, frame)
        requests.finished(bucket)
        changes++
    }

    /** The decode of [bucket] gave no frame in time: it isn't decoded again for the source. */
    fun onNoFrame(bucket: Int) {
        unavailable += bucket
        requests.finished(bucket)
    }

    /** The decode of [bucket] stopped unfinished (its player released): it may be decoded again. */
    fun onDecodeDropped(bucket: Int) {
        requests.finished(bucket)
    }

    /** The source can't be decoded: nothing more is, the cached frames stay. */
    fun failSource() {
        failed = true
        requests.clear()
    }

    /** A decoder failed: nothing is decoded until the app restarts, the cached frames stay. */
    fun disableDecoding() {
        decoderDisabled = true
        requests.clear()
    }

    /**
     * Memory is short: the frames go. A sidecar's are fetched again, and the background decoding resumes, at the next
     * scrub only.
     */
    fun trim() {
        frames.clear()
        pending = null
        if (sidecarState == SidecarState.APPLIED || sidecarState == SidecarState.READ) sidecarState = SidecarState.WANTED
        prefetchPaused = true
        changes++
    }

    private fun put(bucket: Int, frame: F) {
        frames.put(key(bucket), frame)
        frameBytes = sizeOf(frame)
    }

    private fun key(bucket: Int) = PreviewFrameKey(sourceId.orEmpty(), bucket)

    companion object {
        /**
         * Steps around the playback position decoded first, before the coarse to fine grid of the whole timeline:
         * ±2 min of a 24 min episode, past the +85 s intro skip (the phone's PHONE_PREFETCH_NEAR_STEPS).
         */
        const val PREFETCH_NEAR_STEPS = 8
    }
}
