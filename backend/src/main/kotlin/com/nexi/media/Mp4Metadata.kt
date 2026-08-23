package com.nexi.media

/** MP4 kabından okunabilen temel bilgiler. */
data class Mp4Metadata(
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
)

/**
 * MP4 kutularını (box/atom) gezerek süre ve çözünürlüğü okur.
 *
 * Sunucuda FFmpeg yok; kareyi çözmek de gerekmiyor. Süre `moov/mvhd`, boyut ise
 * en büyük `moov/trak/tkhd` kutusundan geliyor — ses izlerinin genişlik ve
 * yüksekliği sıfır olduğu için en büyüğünü seçmek görüntü izini bulmanın
 * güvenilir yolu.
 *
 * Kutular dosyanın başında da sonunda da olabilir (`faststart` uygulanmamış
 * dosyalarda `moov` sondadır), bu yüzden çağıran hem baştan hem sondan bir
 * pencere okuyup ikisini de deniyor.
 */
object Mp4Parser {
    /** `moov` genellikle onlarca KB; bu pencere kısa videolar için fazlasıyla yeterli. */
    const val SCAN_WINDOW_BYTES = 512 * 1024

    fun parse(bytes: ByteArray): Mp4Metadata? {
        val moov = findBox(bytes, 0, bytes.size, "moov") ?: return null
        val mvhd = findBox(bytes, moov.contentStart, moov.contentEnd, "mvhd") ?: return null
        val duration = readDuration(bytes, mvhd) ?: return null
        val size = readLargestTrackSize(bytes, moov) ?: return null
        return Mp4Metadata(duration, size.first, size.second)
    }

    private data class Box(val contentStart: Int, val contentEnd: Int)

    /**
     * Verilen aralıkta ilk seviye kutuları tarar. İç içe arama için çağıran
     * bulunan kutunun içeriğiyle tekrar çağırır.
     */
    private fun findBox(bytes: ByteArray, from: Int, until: Int, type: String): Box? {
        var offset = from
        while (offset + 8 <= until) {
            val declared = readUInt32(bytes, offset) ?: return null
            val boxType = readAscii(bytes, offset + 4, 4) ?: return null

            // size == 1 -> 64 bit uzunluk, size == 0 -> dosyanın sonuna kadar
            var headerSize = 8
            var boxSize = declared
            if (declared == 1L) {
                if (offset + 16 > until) return null
                boxSize = readUInt64(bytes, offset + 8) ?: return null
                headerSize = 16
            } else if (declared == 0L) {
                boxSize = (until - offset).toLong()
            }
            if (boxSize < headerSize) return null

            val end = offset + boxSize
            if (end > until) {
                // Kutu okuduğumuz pencerenin dışına taşıyor; içeriği eksik.
                if (boxType == type) return null
                return null
            }
            if (boxType == type) return Box(offset + headerSize, end.toInt())
            offset = end.toInt()
        }
        return null
    }

    /** `mvhd`: sürüm 0'da 32 bit, sürüm 1'de 64 bit alanlar. */
    private fun readDuration(bytes: ByteArray, mvhd: Box): Double? {
        val version = bytes.getOrNull(mvhd.contentStart)?.toInt()?.and(0xFF) ?: return null
        // içerik: version(1) + flags(3) + created + modified + timescale + duration
        val base = mvhd.contentStart + 4
        val timescale: Long
        val duration: Long
        if (version == 1) {
            timescale = readUInt32(bytes, base + 16) ?: return null
            duration = readUInt64(bytes, base + 20) ?: return null
        } else {
            timescale = readUInt32(bytes, base + 8) ?: return null
            duration = readUInt32(bytes, base + 12) ?: return null
        }
        if (timescale <= 0) return null
        return duration.toDouble() / timescale.toDouble()
    }

    /** Görüntü izini bulmak için bütün `tkhd` kutularının en büyüğünü seçer. */
    private fun readLargestTrackSize(bytes: ByteArray, moov: Box): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var offset = moov.contentStart

        while (offset + 8 <= moov.contentEnd) {
            val declared = readUInt32(bytes, offset) ?: break
            val boxType = readAscii(bytes, offset + 4, 4) ?: break
            var headerSize = 8
            var boxSize = declared
            if (declared == 1L) {
                boxSize = readUInt64(bytes, offset + 8) ?: break
                headerSize = 16
            } else if (declared == 0L) {
                boxSize = (moov.contentEnd - offset).toLong()
            }
            if (boxSize < headerSize || offset + boxSize > moov.contentEnd) break

            if (boxType == "trak") {
                val trak = Box(offset + headerSize, (offset + boxSize).toInt())
                findBox(bytes, trak.contentStart, trak.contentEnd, "tkhd")
                    ?.let { readTrackSize(bytes, it) }
                    ?.let { candidate ->
                        if (best == null || candidate.first.toLong() * candidate.second >
                            best!!.first.toLong() * best!!.second
                        ) {
                            best = candidate
                        }
                    }
            }
            offset = (offset + boxSize).toInt()
        }
        return best
    }

    /** `tkhd` sonundaki genişlik/yükseklik 16.16 sabit noktalı sayıdır. */
    private fun readTrackSize(bytes: ByteArray, tkhd: Box): Pair<Int, Int>? {
        if (tkhd.contentEnd - tkhd.contentStart < 8) return null
        val widthOffset = tkhd.contentEnd - 8
        val width = readUInt32(bytes, widthOffset) ?: return null
        val height = readUInt32(bytes, widthOffset + 4) ?: return null
        val w = (width shr 16).toInt()
        val h = (height shr 16).toInt()
        return if (w > 0 && h > 0) w to h else null
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        var value = 0L
        for (index in 0 until 4) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        return value
    }

    private fun readUInt64(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 8 > bytes.size) return null
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        return if (value < 0) null else value
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset < 0 || offset + length > bytes.size) return null
        return String(bytes, offset, length, Charsets.US_ASCII)
    }
}
