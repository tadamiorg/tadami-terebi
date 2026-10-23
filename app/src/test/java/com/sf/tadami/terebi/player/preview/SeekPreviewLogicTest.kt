package com.sf.tadami.terebi.player.preview

import android.content.ComponentCallbacks2
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.PlaybackException
import com.sf.tadami.terebi.player.TvStreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewLogicTest {

    @Test
    fun `seek fraction is the position over the duration, clamped`() {
        assertEquals(0.25f, seekFraction(positionMs = 300_000, durationMs = 1_200_000), 1e-6f)
        assertEquals(1f, seekFraction(positionMs = 1_300_000, durationMs = 1_200_000), 0f)
        assertEquals(0f, seekFraction(positionMs = -5, durationMs = 1_200_000), 0f)
    }

    @Test
    fun `seek fraction is 0 while the duration is unknown`() {
        assertEquals(0f, seekFraction(positionMs = 5_000, durationMs = 0), 0f)
        assertEquals(0f, seekFraction(positionMs = 5_000, durationMs = -1), 0f)
    }

    // A 1000 px slider from x = 100, 20 px thumb, 200 px preview, screen bounds 0..1300
    private fun left(fraction: Float, boundsStart: Float = 0f, boundsEnd: Float = 1300f) = seekPreviewLeft(
        fraction = fraction,
        trackStart = 100f,
        trackWidth = 1000f,
        thumbWidth = 20f,
        previewWidth = 200f,
        boundsStart = boundsStart,
        boundsEnd = boundsEnd,
    )

    @Test
    fun `preview is centred on the thumb`() {
        // Thumb centre: 100 + 10 + 0.5 * 980 = 600
        assertEquals(500f, left(0.5f), 1e-3f)
        // Thumb centre: 100 + 10 + 0.25 * 980 = 355
        assertEquals(255f, left(0.25f), 1e-3f)
    }

    @Test
    fun `preview is kept within the screen edges`() {
        // Thumb centre 110 would put the left edge at 10, before the 50 px bound
        assertEquals(50f, left(0f, boundsStart = 50f), 0f)
        // Thumb centre 1090 would put the right edge at 1190, past the 1150 px bound
        assertEquals(950f, left(1f, boundsEnd = 1150f), 0f)
        // Out of range fractions are clamped to the slider ends
        assertEquals(left(1f), left(1.5f), 0f)
    }

    @Test
    fun `preview wider than the bounds sticks to the start bound`() {
        assertEquals(40f, left(0.5f, boundsStart = 40f, boundsEnd = 200f), 0f)
    }

    @Test
    fun `preview tracks are the lowest bitrate video only`() {
        val parameters = previewTrackSelectionParameters(TrackSelectionParameters.DEFAULT)
        assertTrue(parameters.forceLowestBitrate)
        assertTrue(C.TRACK_TYPE_AUDIO in parameters.disabledTrackTypes)
        assertTrue(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)
        assertFalse(C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes)
    }

    @Test
    fun `main player spares the network with enough buffered ahead`() {
        assertTrue(mainPlayerCanSpareNetwork(isBuffering = false, bufferedAheadMs = 10_000, bufferedPositionMs = 70_000, durationMs = 1_400_000))
        assertFalse(mainPlayerCanSpareNetwork(isBuffering = false, bufferedAheadMs = 9_999, bufferedPositionMs = 70_000, durationMs = 1_400_000))
    }

    @Test
    fun `main player never spares the network while rebuffering`() {
        assertFalse(mainPlayerCanSpareNetwork(isBuffering = true, bufferedAheadMs = 60_000, bufferedPositionMs = 1_400_000, durationMs = 1_400_000))
    }

    @Test
    fun `main player loaded to the end spares the network`() {
        assertTrue(mainPlayerCanSpareNetwork(isBuffering = false, bufferedAheadMs = 2_000, bufferedPositionMs = 1_400_000, durationMs = 1_400_000))
        // Unknown duration: only the buffer ahead counts
        assertFalse(mainPlayerCanSpareNetwork(isBuffering = false, bufferedAheadMs = 2_000, bufferedPositionMs = 0, durationMs = C.TIME_UNSET))
    }

    private val episode = 1_440_000L // 24 min

    @Test
    fun `steps are 1 percent of the duration within 5 and 30 s`() {
        assertEquals(14_400L, previewBucketSizeMs(episode))
        // 3 min: 1% would be 1.8 s
        assertEquals(5_000L, previewBucketSizeMs(180_000))
        // 2 h: 1% would be 72 s
        assertEquals(30_000L, previewBucketSizeMs(7_200_000))
        // Unknown duration
        assertEquals(5_000L, previewBucketSizeMs(0))
    }

    @Test
    fun `positions map to their step, ends included`() {
        assertEquals(0, previewBucketOf(0, episode))
        assertEquals(0, previewBucketOf(14_399, episode))
        assertEquals(1, previewBucketOf(14_400, episode))
        assertEquals(99, previewBucketOf(episode - 1, episode))
        // The end and past it belong to the last step, before the start to the first
        assertEquals(99, previewBucketOf(episode, episode))
        assertEquals(99, previewBucketOf(episode + 60_000, episode))
        assertEquals(0, previewBucketOf(-1_000, episode))
    }

    @Test
    fun `short and long videos get sensible step counts`() {
        // 3 min in 5 s steps: 36 steps, the last one 0..35
        assertEquals(35, previewBucketOf(179_999, 180_000))
        // 2 h in 30 s steps
        assertEquals(239, previewBucketOf(7_199_999, 7_200_000))
        // A 7 s clip: two steps, the second one shorter
        assertEquals(5_000L until 7_000L, previewBucketRangeMs(1, 7_000))
    }

    @Test
    fun `the phone's steps fold a short leftover end step into the one before`() {
        // 1,430,026 ms in 14,300 ms steps: step 100 is a 26 ms leftover
        assertEquals(100, previewBucketOf(1_430_025, 1_430_026))
        assertEquals(99, phonePreviewBucketOf(1_430_025, 1_430_026))
        assertEquals(99, phonePreviewBucketOf(1_429_999, 1_430_026))
        // A last step of half a step or more is kept
        assertEquals(12, phonePreviewBucketOf(62_999, 63_000))
        assertEquals(0, phonePreviewBucketOf(0, 0))
    }

    @Test
    fun `step ranges cover the video without gaps`() {
        assertEquals(0L until 14_400L, previewBucketRangeMs(0, episode))
        assertEquals(14_400L until 28_800L, previewBucketRangeMs(1, episode))
        assertEquals(1_425_600L until episode, previewBucketRangeMs(99, episode))
        // A 1 min 03 s video ends with a 3 s step
        assertEquals(60_000L until 63_000L, previewBucketRangeMs(12, 63_000))
    }

    @Test
    fun `stream identity is its URL, server and quality`() {
        val stream = TvStreamSource(url = "https://cdn.example/master.m3u8", server = "Kwik", quality = "720p")
        assertEquals("https://cdn.example/master.m3u8|Kwik|720p", previewSourceIdentity(stream))
        assertNotEquals(previewSourceIdentity(stream), previewSourceIdentity(stream.copy(quality = "1080p")))
        assertNotEquals(previewSourceIdentity(stream), previewSourceIdentity(stream.copy(server = "Other")))
        // Same stream from another load (other label, headers or tracks): same frames
        assertEquals(
            previewSourceIdentity(stream),
            previewSourceIdentity(stream.copy(fullName = "Kwik - 720p", headers = mapOf("Referer" to "https://kwik.example/"))),
        )
    }

    private fun cache(maxBytes: Long = 300) = PreviewFrameCache<String>(maxBytes) { 100L }.apply { setSource("a") }

    @Test
    fun `cached frames are keyed by source and step`() {
        val cache = cache()
        cache.put(PreviewFrameKey("a", 1), "a1")
        assertEquals("a1", cache[PreviewFrameKey("a", 1)])
        assertNull(cache[PreviewFrameKey("a", 2)])
        assertNull(cache[PreviewFrameKey("b", 1)])
    }

    @Test
    fun `frames of another source than the current one are refused`() {
        val cache = cache()
        cache.put(PreviewFrameKey("old", 1), "late frame")
        assertFalse(PreviewFrameKey("old", 1) in cache)
        assertEquals(0, cache.size)
    }

    @Test
    fun `another source drops every frame, the same source keeps them`() {
        val cache = cache()
        cache.put(PreviewFrameKey("a", 1), "a1")
        cache.setSource("a")
        assertEquals("a1", cache[PreviewFrameKey("a", 1)])
        cache.setSource("b")
        assertEquals(0, cache.size)
        assertEquals(0L, cache.bytes)
        assertFalse(PreviewFrameKey("a", 1) in cache)
        cache.setSource(null)
        assertNull(cache.sourceId)
    }

    @Test
    fun `cache drops the least recently used frames past its byte budget`() {
        val cache = cache(maxBytes = 300)
        cache.put(PreviewFrameKey("a", 1), "a1")
        cache.put(PreviewFrameKey("a", 2), "a2")
        cache.put(PreviewFrameKey("a", 3), "a3")
        // Reading step 1 makes step 2 the least recently used
        cache[PreviewFrameKey("a", 1)]
        cache.put(PreviewFrameKey("a", 4), "a4")
        assertEquals(3, cache.size)
        assertEquals(300L, cache.bytes)
        assertFalse(PreviewFrameKey("a", 2) in cache)
        assertTrue(PreviewFrameKey("a", 1) in cache)
    }

    @Test
    fun `replacing a frame counts its bytes once`() {
        val cache = cache()
        cache.put(PreviewFrameKey("a", 1), "first")
        cache.put(PreviewFrameKey("a", 1), "second")
        assertEquals(1, cache.size)
        assertEquals(100L, cache.bytes)
    }

    @Test
    fun `only the step scrubbed to is fetched, once`() {
        val requests = BucketRequests()
        val cached = mutableSetOf<Int>()
        requests.aim(3)
        assertEquals(3, requests.next { it in cached })
        // Still loading: no second request, for it or for another step
        assertNull(requests.next { it in cached })
        requests.aim(4)
        requests.aim(5)
        assertNull(requests.next { it in cached })
        // Done: the step now scrubbed to comes next, the ones passed over never
        cached += 3
        requests.finished(3)
        assertEquals(5, requests.next { it in cached })
    }

    @Test
    fun `scrubbing back to the step loading waits for it`() {
        val requests = BucketRequests()
        requests.aim(3)
        assertEquals(3, requests.next { false })
        requests.aim(4)
        requests.aim(3)
        assertNull(requests.next { false })
        assertEquals(3, requests.inFlight)
    }

    @Test
    fun `cached steps are never fetched`() {
        val requests = BucketRequests()
        requests.aim(7)
        assertNull(requests.next { it == 7 })
        assertNull(requests.inFlight)
    }

    @Test
    fun `nothing is fetched once the scrub ended, the fetch in flight is kept`() {
        val requests = BucketRequests()
        requests.aim(2)
        assertEquals(2, requests.next { false })
        requests.aim(null)
        assertEquals(2, requests.inFlight)
        requests.finished(2)
        assertNull(requests.next { false })
    }

    @Test
    fun `a stale end of fetch doesn't free the step loading`() {
        val requests = BucketRequests()
        requests.aim(2)
        requests.next { false }
        requests.finished(9)
        assertEquals(2, requests.inFlight)
    }

    @Test
    fun `the background decoding's step comes when none is scrubbed to`() {
        val requests = BucketRequests()
        assertEquals(7, requests.next(fallback = { 7 }) { false })
        // One at a time
        assertNull(requests.next(fallback = { 8 }) { false })
        requests.finished(7)
        // The step scrubbed to first
        requests.aim(3)
        assertEquals(3, requests.next(fallback = { 8 }) { false })
        requests.finished(3)
        // Done, it leaves room for the background one, never a done one
        assertEquals(8, requests.next(fallback = { 8 }) { it == 3 })
        requests.finished(8)
        assertNull(requests.next(fallback = { 8 }) { it == 3 || it == 8 })
    }

    @Test
    fun `the cache tells when another frame fits without dropping one`() {
        val cache = cache(maxBytes = 300)
        assertTrue(cache.hasRoomFor(100))
        cache.put(PreviewFrameKey("a", 1), "a1")
        cache.put(PreviewFrameKey("a", 2), "a2")
        assertTrue(cache.hasRoomFor(100))
        cache.put(PreviewFrameKey("a", 3), "a3")
        assertFalse(cache.hasRoomFor(100))
        assertTrue(cache.hasRoomFor(0))
    }

    @Test
    fun `only the phone's local route is a downloaded episode, any other URL is a stream`() {
        assertTrue(isLocalMediaUrl("http://192.168.1.20:8214/local-3f9a01-video.ts"))
        assertTrue(isLocalMediaUrl("http://192.168.1.20:8214/local-3f9a01-video.m3u8"))
        assertTrue(isLocalMediaUrl("https://10.0.0.2:8214/local-3f9a01-video.mp4?x=1"))
        assertTrue(isLocalMediaUrl("http://10.0.2.2:8214/local-xyz-video.mp4"))
        assertFalse(isLocalMediaUrl("https://cdn.example/ep1/master.m3u8"))
        // Not at the start of the path
        assertFalse(isLocalMediaUrl("https://cdn.example/files/local-3f9a01-video.mp4"))
        assertFalse(isLocalMediaUrl("https://cdn.example/local/video.mp4"))
        // A stream the phone's proxy wraps
        assertFalse(isLocalMediaUrl("http://192.168.1.20:8214/proxy?url=http%3A%2F%2F192.168.1.20%3A8214%2Flocal-1-video.mp4"))
        // Not an HTTP URL
        assertFalse(isLocalMediaUrl("file:///sdcard/local-3f9a01-video.mp4"))
        assertFalse(isLocalMediaUrl("/local-3f9a01-video.mp4"))
        assertFalse(isLocalMediaUrl(""))
    }

    @Test
    fun `the sidecar is served on the same route as the episode`() {
        assertEquals(
            "http://192.168.1.20:8214/local-3f9a01-previews.tdp",
            previewSidecarUrl("http://192.168.1.20:8214/local-3f9a01-video.ts"),
        )
        // Whatever the name: a playlist, an audio track's with dashes in its name
        assertEquals(
            "http://192.168.1.20:8214/local-3f9a01-previews.tdp",
            previewSidecarUrl("http://192.168.1.20:8214/local-3f9a01-master.m3u8"),
        )
        assertEquals(
            "http://192.168.1.20:8214/local-3f9a01-previews.tdp",
            previewSidecarUrl("http://192.168.1.20:8214/local-3f9a01-audio-en-US.m3u8"),
        )
        // Same scheme, host and port; no query nor fragment
        assertEquals(
            "https://phone.lan/local-ab12-previews.tdp",
            previewSidecarUrl("https://phone.lan/local-ab12-video.mp4?start=10#t"),
        )
        assertEquals("http://10.0.2.2:8214/local-xyz-previews.tdp", previewSidecarUrl("http://10.0.2.2:8214/local-xyz-video.mp4"))
    }

    @Test
    fun `streams and route URLs without a token have no sidecar`() {
        assertNull(previewSidecarUrl("https://cdn.example/ep1/master.m3u8"))
        assertNull(previewSidecarUrl("https://cdn.example/files/local-3f9a01-video.mp4"))
        assertNull(previewSidecarUrl("http://192.168.1.20:8214/local--video.mp4"))
        assertNull(previewSidecarUrl("http://192.168.1.20:8214/local-video.mp4"))
        assertNull(previewSidecarUrl("http://192.168.1.20:8214/local-ab/12-video.mp4"))
        assertNull(previewSidecarUrl("not a url"))
    }

    @Test
    fun `prefetch order goes outward from the centre`() {
        assertEquals(listOf(5, 6, 4, 7, 3, 8, 2), prefetchOrder(5, 8).take(7).toList())
        assertEquals(listOf(0, 1, 2, 3), prefetchOrder(0, 3).toList())
        assertEquals(listOf(3, 2, 1, 0), prefetchOrder(3, 3).toList())
        assertEquals(listOf(3, 2, 1, 0), prefetchOrder(10, 3).toList())
        assertEquals(10, prefetchOrder(4, 9).toList().size)
        assertTrue(prefetchOrder(0, -1).toList().isEmpty())
    }

    @Test
    fun `coarse to fine covers every step once, the nearest first`() {
        for (last in listOf(0, 1, 7, 8, 9, 63, 99, 239)) {
            for (center in listOf(0, last / 2, last)) {
                val order = coarseToFineOrder(center, last, near = 8).toList()
                assertEquals("last $last, centre $center", (0..last).toList(), order.sorted())
            }
        }
        assertTrue(coarseToFineOrder(0, -1, near = 8).toList().isEmpty())
        // As prefetchOrder within ±8 of the centre
        assertEquals(prefetchOrder(40, 99).take(17).toList(), coarseToFineOrder(40, 99, near = 8).take(17).toList())
        assertEquals(listOf(40, 41, 39, 42, 38), coarseToFineOrder(40, 99, near = 8).take(5).toList())
    }

    @Test
    fun `coarse to fine, then every 8th step from step 0, nearest the centre first, then finer`() {
        val order = coarseToFineOrder(40, 99, near = 8).toList()
        // 0, 8, … 96 but those near the centre (32, 40, 48), the nearest first
        assertEquals(listOf(56, 24, 64, 16, 72, 8, 80, 0, 88, 96), order.subList(17, 27))
        // Then every 4th, nearest first
        assertEquals(listOf(52, 28, 60, 20), order.subList(27, 31))
        assertTrue(order.subList(27, 37).all { it % 4 == 0 && it % 8 != 0 })
    }

    @Test
    fun `coarse to fine, the grid stays where it is when the centre moves`() {
        val grid = (0..99 step 8).toSet()
        for (center in listOf(3, 37, 61, 99)) {
            val order = coarseToFineOrder(center, 99, near = 8).toList()
            val nearby = order.takeWhile { kotlin.math.abs(it - center) <= 8 }
            val coarse = order.drop(nearby.size).take((grid - nearby.toSet()).size)
            assertEquals("centre $center", grid - nearby.toSet(), coarse.toSet())
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `frames go on memory pressure, not when the UI hides`() {
        assertFalse(shouldDropPreviewFrames(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(shouldDropPreviewFrames(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(shouldDropPreviewFrames(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(shouldDropPreviewFrames(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(shouldDropPreviewFrames(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(shouldDropPreviewFrames(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    private data class Decoder(val name: String, val software: Boolean, val instances: Int)

    private fun allowed(vararg decoders: Decoder) =
        previewDecoders(decoders.toList(), isSoftware = { it.software }, maxInstances = { it.instances }).map { it.name }

    @Test
    fun `the preview decodes in software whenever it can`() {
        val hardware = Decoder("c2.vendor.avc.decoder", software = false, instances = 32)
        val software = Decoder("c2.android.avc.decoder", software = true, instances = 32)
        assertEquals(listOf("c2.android.avc.decoder"), allowed(hardware, software))
    }

    @Test
    fun `a hardware decoder is taken only with room for the main player's too`() {
        assertEquals(listOf("hw2"), allowed(Decoder("hw1", false, 1), Decoder("hw2", false, 2)))
        // One instance only, or unknown: the preview stays off
        assertEquals(emptyList<String>(), allowed(Decoder("hw1", false, 1), Decoder("hw0", false, 0)))
        assertEquals(emptyList<String>(), allowed())
    }

    @Test
    fun `decoder errors are told from the others`() {
        assertTrue(isDecoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(isDecoderError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(isDecoderError(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertTrue(isDecoderError(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
        assertFalse(isDecoderError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(isDecoderError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertFalse(isDecoderError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }
}
