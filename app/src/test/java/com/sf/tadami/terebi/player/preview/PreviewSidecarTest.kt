package com.sf.tadami.terebi.player.preview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The seekbar preview sidecar the phone serves for a downloaded episode: its format, and what makes one unusable. */
class PreviewSidecarTest {

    // 23:50.026: 14.3 s steps, 0..99 for the phone, step 100 a 26 ms leftover
    private val duration = 1_430_026L
    private val videoLength = 812_345_678L

    private fun sidecar(
        entries: List<PreviewSidecar.Entry> = (0..99).map { step ->
            if (step == 29) {
                PreviewSidecar.Entry(step, -1, null)
            } else {
                PreviewSidecar.Entry(step, step * 14_300L + 1_000, ByteArray(100 + step) { (step + it).toByte() })
            }
        },
        durationMs: Long = duration,
        stepMs: Long = previewBucketSizeMs(durationMs),
        width: Int = 256,
        height: Int = 144,
    ) = PreviewSidecar(videoLength, durationMs, stepMs, width, height, entries)

    @Test
    fun `a sidecar reads back as written`() {
        val written = sidecar()
        val read = PreviewSidecar.decode(PreviewSidecar.encode(written), videoLength)!!
        assertEquals(videoLength, read.videoLength)
        assertEquals(duration, read.durationMs)
        assertEquals(14_300L, read.stepMs)
        assertEquals(256, read.frameWidth)
        assertEquals(144, read.frameHeight)
        assertEquals(100, read.entries.size)
        assertEquals(99, read.frameCount)
        read.entries.zip(written.entries).forEach { (actual, expected) ->
            assertEquals(expected.step, actual.step)
            assertEquals(expected.timeMs, actual.timeMs)
            assertArrayEquals(expected.jpeg, actual.jpeg)
        }
        // A step without frame stays one
        val none = read.entries.single { it.step == 29 }
        assertFalse(none.hasFrame)
        assertEquals(-1L, none.timeMs)
        // Written again, byte for byte the same
        assertArrayEquals(PreviewSidecar.encode(written), PreviewSidecar.encode(read))
    }

    @Test
    fun `the receiver reads a sidecar without knowing the phone's video length`() {
        val read = PreviewSidecar.decode(PreviewSidecar.encode(sidecar()), videoLength = null)
        assertNotNull(read)
        assertEquals(videoLength, read!!.videoLength)
        assertEquals(99, read.frameCount)
    }

    @Test
    fun `another video's sidecar is refused when the length is known`() {
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(sidecar()), videoLength + 1))
    }

    @Test
    fun `a portrait video's frames read back, frames past the bound are refused`() {
        // 9:16: 144×256, both sides within the bound
        val portrait = sidecar(width = 144, height = 256)
        val read = PreviewSidecar.decode(PreviewSidecar.encode(portrait), null)!!
        assertEquals(144 to 256, read.frameWidth to read.frameHeight)
        // Higher or wider than the bound, or empty: refused
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(sidecar(width = 256, height = 455)), null))
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(sidecar(width = 257, height = 144)), null))
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(sidecar(width = 0, height = 144)), null))
    }

    @Test
    fun `an empty sidecar is valid`() {
        val read = PreviewSidecar.decode(PreviewSidecar.encode(sidecar(entries = emptyList())), videoLength)
        assertNotNull(read)
        assertEquals(0, read!!.frameCount)
    }

    @Test
    fun `a step size that isn't the duration's is refused`() {
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(sidecar(stepMs = 14_000)), null))
    }

    @Test
    fun `a sidecar without duration is refused`() {
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(sidecar(entries = emptyList(), durationMs = 0, stepMs = 5_000)), null))
    }

    @Test
    fun `another format version or no sidecar at all is refused`() {
        val bytes = PreviewSidecar.encode(sidecar())
        // The version, after the magic
        val newer = bytes.copyOf().also { it[7] = (PreviewSidecar.VERSION + 1).toByte() }
        assertNull(PreviewSidecar.decode(newer, null))
        val notOne = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull(PreviewSidecar.decode(notOne, null))
        assertNull(PreviewSidecar.decode(ByteArray(0), null))
        // A 404 page served by mistake
        assertNull(PreviewSidecar.decode("not found".toByteArray(), null))
    }

    @Test
    fun `a truncated sidecar is refused, wherever it was cut`() {
        val bytes = PreviewSidecar.encode(sidecar())
        for (size in listOf(3, 20, 43, 44, 60, bytes.size / 2, bytes.size - 1)) {
            assertNull("cut at $size of ${bytes.size}", PreviewSidecar.decode(bytes.copyOf(size), null))
        }
        // Cut right after an entry: the count tells
        val firstEntryEnd = 44 + 16 + 100
        assertNull(PreviewSidecar.decode(bytes.copyOf(firstEntryEnd), null))
    }

    @Test
    fun `bytes past the last entry are refused`() {
        val bytes = PreviewSidecar.encode(sidecar()) + byteArrayOf(0)
        assertNull(PreviewSidecar.decode(bytes, null))
    }

    /** A sidecar's header, then an entry count of [count] and the entries [entries] writes. */
    private fun withEntries(count: Int, entries: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                // The header, without its entry count
                it.write(PreviewSidecar.encode(sidecar(entries = emptyList())).copyOf(40))
                it.writeInt(count)
                it.entries()
            }
        }.toByteArray()

    @Test
    fun `a corrupted entry is refused`() {
        // One entry whose length is absurd, or negative
        assertNull(PreviewSidecar.decode(withEntries(1) { writeInt(0); writeLong(0); writeInt(Int.MAX_VALUE) }, null))
        assertNull(PreviewSidecar.decode(withEntries(1) { writeInt(0); writeLong(0); writeInt(-1) }, null))
        // A JPEG just over the bound
        assertNull(PreviewSidecar.decode(withEntries(1) { writeInt(0); writeLong(0); writeInt(1024 * 1024 + 1) }, null))
        // A count that is absurd, or negative
        assertNull(PreviewSidecar.decode(withEntries(12_001) {}, null))
        assertNull(PreviewSidecar.decode(withEntries(-1) {}, null))
        // A step past the end of the video, or before its start
        val past = sidecar(entries = listOf(PreviewSidecar.Entry(101, 0, ByteArray(10))))
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(past), null))
        val before = sidecar(entries = listOf(PreviewSidecar.Entry(-1, 0, ByteArray(10))))
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(before), null))
        // A step twice
        val twice = sidecar(entries = listOf(PreviewSidecar.Entry(3, 0, ByteArray(10)), PreviewSidecar.Entry(3, 0, null)))
        assertNull(PreviewSidecar.decode(PreviewSidecar.encode(twice), null))
    }

    @Test
    fun `the last step of the video, leftover included, is a valid entry`() {
        val last = sidecar(entries = listOf(PreviewSidecar.Entry(100, 1_430_000, ByteArray(10))))
        assertNotNull(PreviewSidecar.decode(PreviewSidecar.encode(last), null))
    }

    @Test
    fun `the duration must be the player's, within a second`() {
        val read = sidecar()
        assertTrue(read.matchesDuration(duration))
        assertTrue(read.matchesDuration(duration - 1_000))
        assertTrue(read.matchesDuration(duration + 1_000))
        assertFalse(read.matchesDuration(duration + 1_001))
        assertFalse(read.matchesDuration(duration - 1_001))
        assertFalse(read.matchesDuration(0))
        assertFalse(read.matchesDuration(-1))
    }

    @Test
    fun `each step of the player's timeline finds the sidecar's frame of its time`() {
        val read = sidecar()
        // The same duration: the same steps
        (0..99).forEach { step ->
            assertEquals(step, read.entryAt(previewBucketRangeMs(step, duration))?.step)
        }
        // The receiver's leftover end step takes the last frame
        assertEquals(99, read.entryAt(previewBucketRangeMs(100, duration))?.step)
        // A receiver whose duration is 300 ms shorter (steps of 14,297 ms): still step for step
        val receiver = duration - 300
        (0..99).forEach { step ->
            assertEquals(step, read.entryAt(previewBucketRangeMs(step, receiver))?.step)
        }
    }

    @Test
    fun `every receiver step maps to the sidecar entry of the same time`() {
        val read = sidecar()
        val steps = sidecarStepEntries(read, duration)
        // 0..99 and the 26 ms leftover step 100, which shows step 99's frame
        assertEquals((0..100).toSet(), steps.keys)
        (0..99).forEach { assertEquals(it, steps[it]?.step) }
        assertEquals(99, steps[100]?.step)
        // The step without frame maps to its entry, which tells there's none
        assertFalse(steps.getValue(29).hasFrame)
        // A receiver 900 ms longer: 14,309 ms steps, each still taking the entry of its own time
        val longer = sidecarStepEntries(read, duration + 900)
        (0..99).forEach { step ->
            val range = previewBucketRangeMs(step, duration + 900)
            val entry = longer.getValue(step)
            assertEquals(previewBucketOf((range.first + range.last) / 2, duration), entry.step)
        }
        // No duration yet: nothing
        assertTrue(sidecarStepEntries(read, 0).isEmpty())
    }

    @Test
    fun `steps the sidecar has no entry for are left out`() {
        val partial = sidecar(entries = (0..49).map { PreviewSidecar.Entry(it, it * 14_300L, ByteArray(10)) })
        val steps = sidecarStepEntries(partial, duration)
        assertEquals((0..49).toSet(), steps.keys)
    }

    /**
     * A sidecar the phone wrote, when one is given (`TEREBI_SIDECAR_SAMPLE=<path to a .tdp>`): it reads, and the
     * receiver maps every step of its timeline to it.
     */
    @Test
    fun `a sidecar the phone wrote reads back`() {
        val path = System.getenv("TEREBI_SIDECAR_SAMPLE")
        assumeTrue("no TEREBI_SIDECAR_SAMPLE", !path.isNullOrBlank() && File(path).isFile)
        val bytes = File(path!!).readBytes()
        val read = PreviewSidecar.decode(bytes, null)!!
        assertTrue(read.frameCount > 0)
        assertTrue(read.matchesDuration(read.durationMs))
        assertArrayEquals(bytes, PreviewSidecar.encode(read))
        val steps = sidecarStepEntries(read, read.durationMs)
        assertEquals(previewBucketOf(read.durationMs - 1, read.durationMs) + 1, steps.size)
        // Each frame starts as a JPEG
        read.entries.mapNotNull { it.jpeg }.forEach { jpeg ->
            assertEquals(0xFF.toByte(), jpeg[0])
            assertEquals(0xD8.toByte(), jpeg[1])
        }
        println("Sample: ${read.durationMs} ms, ${read.entries.size} entries, ${read.frameCount} frames, ${bytes.size} bytes")
    }
}
