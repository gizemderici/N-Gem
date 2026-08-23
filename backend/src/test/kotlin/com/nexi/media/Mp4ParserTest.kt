package com.nexi.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Mp4ParserTest {

    @Test
    fun `duration and resolution are read from the container`() {
        val metadata = assertNotNull(
            Mp4Parser.parse(Mp4Fixtures.mp4(durationSeconds = 12.5, width = 1920, height = 1080))
        )

        assertEquals(12.5, metadata.durationSeconds, 0.001)
        assertEquals(1920, metadata.width)
        assertEquals(1080, metadata.height)
    }

    @Test
    fun `a non-default timescale is honoured`() {
        // 90 kHz yayın zaman ölçeği; sure sabit degil, olcege bolunerek bulunur.
        val metadata = assertNotNull(
            Mp4Parser.parse(Mp4Fixtures.mp4(durationSeconds = 3.0, timescale = 90_000))
        )

        assertEquals(3.0, metadata.durationSeconds, 0.001)
    }

    @Test
    fun `portrait video keeps its orientation`() {
        val metadata = assertNotNull(Mp4Parser.parse(Mp4Fixtures.mp4(width = 1080, height = 1920)))

        assertEquals(1080, metadata.width)
        assertEquals(1920, metadata.height)
    }

    @Test
    fun `moov at the end is found when it is inside the window`() {
        val metadata = assertNotNull(
            Mp4Parser.parse(Mp4Fixtures.mp4(durationSeconds = 7.0, moovAtEnd = true, paddingBytes = 4_096))
        )

        assertEquals(7.0, metadata.durationSeconds, 0.001)
    }

    @Test
    fun `a file with only a signature yields nothing`() {
        assertNull(Mp4Parser.parse(Mp4Fixtures.signatureOnly()))
    }

    @Test
    fun `garbage and truncated input do not throw`() {
        assertNull(Mp4Parser.parse("bu bir video degil".toByteArray()))
        assertNull(Mp4Parser.parse(ByteArray(0)))
        // Kutu uzunlugu elimizdeki veriden buyuk oldugunda okuma tasmamali.
        assertNull(Mp4Parser.parse(Mp4Fixtures.mp4().copyOfRange(0, 20)))
    }
}
