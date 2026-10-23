package com.sf.tadami.terebi.player.preview

import com.sf.tadami.terebi.player.preview.SeekPreviewFrames.SidecarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which seekbar preview frames show, and which get decoded: streams none, downloaded episodes from their sidecar first. */
class SeekPreviewFramesTest {

    // 23:50.026: 14.3 s steps 0..99, step 100 a 26 ms leftover
    private val duration = 1_430_026L
    private val step = 14_300L

    private val stream = "https://cdn.example/ep1/master.m3u8"
    private val localUrl = "http://192.168.1.20:8214/local-3f9a01-video.m3u8"

    private fun frames(maxBytes: Long = 1_000_000) = SeekPreviewFrames<String>(maxBytes) { 100L }

    private fun sidecar(entries: List<PreviewSidecar.Entry> = (0..99).map { s ->
        if (s == 29) PreviewSidecar.Entry(s, -1, null) else PreviewSidecar.Entry(s, s * step + 1_000, ByteArray(4))
    }, durationMs: Long = duration) =
        PreviewSidecar(1L, durationMs, previewBucketSizeMs(durationMs), 256, 144, entries)

    /** The frames of [sidecar] as the receiver decodes them, by the sidecar's steps. */
    private fun decoded(sidecar: PreviewSidecar) = sidecar.entries.filter { it.hasFrame }.associate { it.step to "sidecar ${it.step}" }

    /** A downloaded episode whose sidecar was [read] (null: none) and whose player's duration is [durationMs]. */
    private fun localEpisode(read: PreviewSidecar?, durationMs: Long = duration, maxBytes: Long = 1_000_000) =
        frames(maxBytes).apply {
            setSource("local", localUrl)
            onSidecarFetching()
            onSidecarRead("local", read, read?.let(::decoded).orEmpty())
            setDuration(durationMs)
        }

    /** Decodes every step [nextDecode] picks, with a frame, until none or [count]: the order they came in. */
    private fun SeekPreviewFrames<String>.decodeAll(positionMs: Long, count: Int = Int.MAX_VALUE): List<Int> {
        val order = mutableListOf<Int>()
        while (order.size < count) {
            val next = nextDecode(positionMs, prefetch = true) ?: break
            onFrame(next, "decoded $next")
            order += next
        }
        return order
    }

    @Test
    fun `streams never create a preview player`() {
        val frames = frames()
        assertTrue(frames.setSource("stream", stream))
        assertFalse(frames.local)
        assertNull(frames.sidecarUrl)
        assertEquals(SidecarState.NONE, frames.sidecarState)
        frames.setDuration(duration)
        // Nothing to fetch: a sidecar can't even be taken for it
        frames.onSidecarFetching()
        assertFalse(frames.onSidecarRead("stream", sidecar(), decoded(sidecar())))
        // Whatever the scrub and the playback, nothing is ever decoded: no preview player is made
        for (bucket in listOf(0, 10, 50, 99, 100)) {
            frames.aim(bucket)
            assertNull(frames.nextDecode(bucket * step, prefetch = true))
            assertNull(frames.frameOf(bucket))
        }
        frames.aim(null)
        assertNull(frames.nextDecode(0, prefetch = true))
        assertNull(frames.decoding)
        assertEquals(0, frames.size)
    }

    @Test
    fun `the phone's proxy wrapping a stream is still a stream`() {
        val frames = frames()
        frames.setSource("wrapped", "http://192.168.1.20:8214/proxy?url=https%3A%2F%2Fcdn.example%2Flocal-1-video.mp4")
        assertFalse(frames.local)
        frames.setDuration(duration)
        frames.aim(3)
        assertNull(frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `a downloaded episode fetches its sidecar from the same route`() {
        val frames = frames()
        frames.setSource("local", localUrl)
        assertTrue(frames.local)
        assertEquals("http://192.168.1.20:8214/local-3f9a01-previews.tdp", frames.sidecarUrl)
        assertEquals(SidecarState.WANTED, frames.sidecarState)
        frames.onSidecarFetching()
        assertEquals(SidecarState.FETCHING, frames.sidecarState)
        // Nothing is decoded before the sidecar is known: it may cover the step
        frames.setDuration(duration)
        frames.aim(10)
        assertNull(frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `with a sidecar every step shows at once and none is decoded`() {
        val frames = localEpisode(sidecar())
        assertEquals(SidecarState.APPLIED, frames.sidecarState)
        (0..99).filter { it != 29 }.forEach { assertEquals("sidecar $it", frames.frameOf(it)) }
        // The leftover end step shows the last frame
        assertEquals("sidecar 99", frames.frameOf(100))
        // The step the phone got no frame for: the time alone, and not decoded here either
        assertNull(frames.frameOf(29))
        assertTrue(frames.isDone(29))
        for (bucket in 0..100) {
            frames.aim(bucket)
            assertNull(frames.nextDecode(bucket * step, prefetch = true))
        }
    }

    @Test
    fun `a sidecar read before the player's duration waits for it`() {
        val frames = frames()
        frames.setSource("local", localUrl)
        frames.onSidecarFetching()
        assertTrue(frames.onSidecarRead("local", sidecar(), decoded(sidecar())))
        assertEquals(SidecarState.READ, frames.sidecarState)
        assertNull(frames.frameOf(10))
        // Within a second of the sidecar's: its frames go to the receiver's own steps
        frames.setDuration(duration - 300)
        assertEquals(SidecarState.APPLIED, frames.sidecarState)
        assertEquals("sidecar 10", frames.frameOf(10))
    }

    @Test
    fun `a sidecar for another duration is dropped and the frames are decoded here`() {
        val frames = localEpisode(sidecar(), durationMs = duration + 1_001)
        assertEquals(SidecarState.UNUSABLE, frames.sidecarState)
        assertEquals(0, frames.size)
        assertNull(frames.frameOf(10))
        frames.aim(10)
        assertEquals(10, frames.nextDecode(0, prefetch = false))
    }

    @Test
    fun `a sidecar covering part of the episode leaves the rest to decode`() {
        val half = sidecar(entries = (0..49).map { PreviewSidecar.Entry(it, it * step, ByteArray(4)) })
        val frames = localEpisode(half)
        assertEquals("sidecar 49", frames.frameOf(49))
        frames.aim(49)
        assertNull(frames.nextDecode(0, prefetch = false))
        frames.aim(60)
        assertEquals(60, frames.nextDecode(0, prefetch = false))
    }

    @Test
    fun `without a sidecar the step scrubbed to is decoded first, then the nearest the playback`() {
        val frames = localEpisode(read = null)
        assertEquals(SidecarState.UNUSABLE, frames.sidecarState)
        // Playing at step 10, scrubbing over step 50
        frames.aim(50)
        assertEquals(50, frames.nextDecode(10 * step, prefetch = true))
        frames.onFrame(50, "decoded 50")
        frames.aim(null)
        // Then the background decoding, coarse to fine around the playback position, step 50 done already
        val expected = coarseToFineOrder(10, 99, SeekPreviewFrames.PREFETCH_NEAR_STEPS).filter { it != 50 }.take(30).toList()
        assertEquals(expected, frames.decodeAll(10 * step, count = 30))
        assertEquals(listOf(10, 11, 9, 12, 8), expected.take(5))
    }

    @Test
    fun `the background decoding is re-centred on a seek`() {
        val frames = localEpisode(read = null)
        assertEquals(listOf(10, 11, 9), frames.decodeAll(10 * step, count = 3))
        // Seeked to step 70
        assertEquals(70, frames.nextDecode(70 * step, prefetch = true))
    }

    @Test
    fun `the background decoding covers the whole episode but its leftover end step`() {
        val frames = localEpisode(read = null)
        val order = frames.decodeAll(0)
        assertEquals((0..99).toList(), order.sorted())
        // Scrubbed to, the leftover step is still decoded
        frames.aim(100)
        assertEquals(100, frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `a step shows a frame only when cached`() {
        val frames = localEpisode(read = null)
        frames.aim(5)
        assertEquals(5, frames.nextDecode(0, prefetch = false))
        frames.onFrame(5, "decoded 5")
        frames.aim(6)
        assertEquals(6, frames.nextDecode(0, prefetch = false))
        // Decoding: the time alone, never the neighbour's frame
        assertNull(frames.frameOf(6))
        assertEquals("decoded 5", frames.frameOf(5))
        assertNull(frames.frameOf(7))
        frames.onFrame(6, "decoded 6")
        assertEquals("decoded 6", frames.frameOf(6))
        // A step that gave none in time stays without, and isn't decoded again
        frames.aim(7)
        assertEquals(7, frames.nextDecode(0, prefetch = false))
        frames.onNoFrame(7)
        assertNull(frames.frameOf(7))
        assertNull(frames.nextDecode(0, prefetch = false))
    }

    @Test
    fun `cached frames change what shows`() {
        val frames = localEpisode(read = null)
        val before = frames.changes
        frames.aim(3)
        frames.onFrame(frames.nextDecode(0, prefetch = false)!!, "decoded 3")
        assertTrue(frames.changes > before)
    }

    @Test
    fun `one step decodes at a time, the scrub's first`() {
        val frames = localEpisode(read = null)
        assertEquals(0, frames.nextDecode(0, prefetch = true))
        frames.aim(40)
        assertNull(frames.nextDecode(0, prefetch = true))
        frames.onFrame(0, "decoded 0")
        assertEquals(40, frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `no background decoding unless the main player allows it`() {
        val frames = localEpisode(read = null)
        assertNull(frames.nextDecode(0, prefetch = false))
        // The step scrubbed to still is
        frames.aim(20)
        assertEquals(20, frames.nextDecode(0, prefetch = false))
    }

    @Test
    fun `the background decoding stops once the cache is full, a scrub still decodes`() {
        val frames = localEpisode(read = null, maxBytes = 300)
        assertEquals(3, frames.decodeAll(0).size)
        assertNull(frames.nextDecode(0, prefetch = true))
        frames.aim(60)
        assertEquals(60, frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `nothing is decoded before the duration is known`() {
        val frames = frames()
        frames.setSource("local", "http://192.168.1.20:8214/local-3f9a01-video.mp4")
        frames.onSidecarFetching()
        frames.onSidecarRead("local", null, emptyMap())
        frames.aim(0)
        assertNull(frames.nextDecode(0, prefetch = true))
        frames.setDuration(duration)
        assertEquals(0, frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `a decoder error stops every decode for the session, the cached frames stay`() {
        val frames = localEpisode(read = null)
        frames.aim(3)
        frames.onFrame(frames.nextDecode(0, prefetch = false)!!, "decoded 3")
        frames.disableDecoding()
        frames.aim(4)
        assertNull(frames.nextDecode(0, prefetch = true))
        assertEquals("decoded 3", frames.frameOf(3))
        // Another episode: still none
        frames.setSource("other", "http://192.168.1.20:8214/local-77-video.mp4")
        frames.onSidecarFetching()
        frames.onSidecarRead("other", null, emptyMap())
        frames.setDuration(duration)
        frames.aim(4)
        assertNull(frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `a failed source decodes nothing more until another one plays`() {
        val frames = localEpisode(read = null)
        frames.aim(3)
        assertEquals(3, frames.nextDecode(0, prefetch = false))
        frames.failSource()
        assertNull(frames.decoding)
        assertNull(frames.nextDecode(0, prefetch = true))
        frames.setSource("other", "http://192.168.1.20:8214/local-77-video.mp4")
        frames.onSidecarFetching()
        frames.onSidecarRead("other", null, emptyMap())
        frames.setDuration(duration)
        assertEquals(0, frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `a decode dropped unfinished may be made again`() {
        val frames = localEpisode(read = null)
        frames.aim(3)
        assertEquals(3, frames.nextDecode(0, prefetch = false))
        frames.onDecodeDropped(3)
        assertEquals(3, frames.nextDecode(0, prefetch = false))
    }

    @Test
    fun `a trim drops the frames, the sidecar's come back at the next scrub`() {
        val frames = localEpisode(sidecar())
        frames.trim()
        assertNull(frames.frameOf(10))
        assertEquals(SidecarState.WANTED, frames.sidecarState)
        frames.onSidecarFetching()
        frames.onSidecarRead("local", sidecar(), decoded(sidecar()))
        assertEquals("sidecar 10", frames.frameOf(10))
    }

    @Test
    fun `after a trim the background decoding waits for the next scrub`() {
        val frames = localEpisode(read = null)
        frames.decodeAll(0, count = 5)
        frames.trim()
        assertEquals(0, frames.size)
        assertNull(frames.nextDecode(0, prefetch = true))
        frames.aim(30)
        assertEquals(30, frames.nextDecode(0, prefetch = true))
        frames.onFrame(30, "decoded 30")
        frames.aim(null)
        assertEquals(0, frames.nextDecode(0, prefetch = true))
    }

    @Test
    fun `another source drops the frames, a late sidecar of the previous one is ignored`() {
        val frames = frames()
        frames.setSource("local", localUrl)
        frames.onSidecarFetching()
        frames.setSource("next", "http://192.168.1.20:8214/local-77-video.mp4")
        frames.onSidecarFetching()
        assertFalse(frames.onSidecarRead("local", sidecar(), decoded(sidecar())))
        assertEquals(SidecarState.FETCHING, frames.sidecarState)
        assertEquals(0, frames.size)
        // The same source again: nothing changes
        assertFalse(frames.setSource("next", "http://192.168.1.20:8214/local-77-video.mp4"))
    }

    @Test
    fun `a route URL without a token has no sidecar, its frames are decoded here`() {
        val frames = frames()
        frames.setSource("local", "http://192.168.1.20:8214/local-video.mp4")
        assertTrue(frames.local)
        assertNull(frames.sidecarUrl)
        assertEquals(SidecarState.UNUSABLE, frames.sidecarState)
        frames.setDuration(duration)
        assertEquals(0, frames.nextDecode(0, prefetch = true))
    }
}
