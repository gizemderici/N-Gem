package com.nexi.media

import java.io.ByteArrayOutputStream

/**
 * Testler için en küçük geçerli MP4 iskeleti üretir.
 *
 * Gerçek bir kodlayıcıya gerek yok: çözümleyici yalnızca `ftyp`, `moov/mvhd` ve
 * `moov/trak/tkhd` kutularını okuyor, dolayısıyla bu kutuları elle kurmak
 * gerçek dosyayla aynı yolu sınıyor.
 */
object Mp4Fixtures {

    fun mp4(
        durationSeconds: Double = 12.0,
        timescale: Int = 1000,
        width: Int = 1920,
        height: Int = 1080,
        /** `moov` sonda: `faststart` uygulanmamış dosyaların düzeni. */
        moovAtEnd: Boolean = false,
        /** Araya konan dolgu; sondaki `moov`u tarama penceresinin dışına itmek için. */
        paddingBytes: Int = 0,
    ): ByteArray {
        val ftyp = box("ftyp", "isom".ascii() + intBe(0x200) + "isomiso2avc1mp41".ascii())
        val moov = box("moov", mvhd(durationSeconds, timescale) + trak(width, height))
        val padding = if (paddingBytes > 0) box("free", ByteArray(paddingBytes)) else ByteArray(0)

        return if (moovAtEnd) ftyp + padding + moov else ftyp + moov + padding
    }

    /** Kutuları olmayan, yalnızca imzası doğru bir dosya. */
    fun signatureOnly(): ByteArray = box("ftyp", "isom".ascii() + intBe(0x200) + "isom".ascii())

    private fun mvhd(durationSeconds: Double, timescale: Int): ByteArray {
        val duration = (durationSeconds * timescale).toLong()
        val content = ByteArrayOutputStream().apply {
            write(0)                       // version 0
            write(ByteArray(3))            // flags
            write(intBe(0))                // creation_time
            write(intBe(0))                // modification_time
            write(intBe(timescale))
            write(intBe(duration.toInt()))
            write(intBe(0x00010000))       // rate
            write(byteArrayOf(1, 0))       // volume
            write(ByteArray(10))           // reserved
            write(ByteArray(36))           // matrix
            write(ByteArray(24))           // predefined
            write(intBe(2))                // next_track_id
        }.toByteArray()
        return box("mvhd", content)
    }

    private fun trak(width: Int, height: Int): ByteArray {
        val tkhd = ByteArrayOutputStream().apply {
            write(0)                       // version 0
            write(ByteArray(3))            // flags
            write(intBe(0))                // creation_time
            write(intBe(0))                // modification_time
            write(intBe(1))                // track_id
            write(ByteArray(4))            // reserved
            write(intBe(0))                // duration
            write(ByteArray(8))            // reserved
            write(ByteArray(2))            // layer
            write(ByteArray(2))            // alternate_group
            write(ByteArray(2))            // volume
            write(ByteArray(2))            // reserved
            write(ByteArray(36))           // matrix
            write(intBe(width shl 16))     // 16.16 sabit noktalı
            write(intBe(height shl 16))
        }.toByteArray()
        return box("trak", box("tkhd", tkhd))
    }

    private fun box(type: String, content: ByteArray): ByteArray =
        intBe(content.size + 8) + type.ascii() + content

    private fun intBe(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun String.ascii() = toByteArray(Charsets.US_ASCII)
}
