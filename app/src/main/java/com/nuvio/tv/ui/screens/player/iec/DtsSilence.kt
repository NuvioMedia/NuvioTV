package com.nuvio.tv.ui.screens.player.iec

internal object DtsSilence {
    const val SAMPLE_COUNT = 512
    const val FRAME_BYTES = 2012
    const val SAMPLE_RATE = 48_000

    private val multichannel: ByteArray? by lazy { load("dts_silence_5_1.bin") }
    private val stereo: ByteArray? by lazy { load("dts_silence_2_0.bin") }

    fun frameFor(core: CoreHeader): ByteArray? {
        if (core.sampleRate != SAMPLE_RATE || core.sampleCount != SAMPLE_COUNT) return null
        val frame = when {
            core.amode == AMODE_3_2 && core.lfe -> multichannel
            core.amode == AMODE_STEREO && !core.lfe -> stereo
            else -> null
        }
        return frame?.copyOf()
    }

    fun parseCore(head: ByteArray): CoreHeader? {
        if (head.size < CORE_HEADER_BYTES) return null
        val bigEndian = when {
            head[0] == 0x7F.toByte() && head[1] == 0xFE.toByte() && head[2] == 0x80.toByte() && head[3] == 0x01.toByte() -> true
            head[0] == 0xFE.toByte() && head[1] == 0x7F.toByte() && head[2] == 0x01.toByte() && head[3] == 0x80.toByte() -> false
            else -> return null
        }
        fun bits(offset: Int, length: Int): Int {
            var value = 0
            for (bit in offset until offset + length) {
                val index = bit ushr 3
                val byte = (if (bigEndian) head[index] else head[index xor 1]).toInt() and 0xFF
                value = (value shl 1) or ((byte ushr (7 - (bit and 7))) and 1)
            }
            return value
        }
        val sampleRate = SAMPLE_RATES.getOrNull(bits(SFREQ_BIT, 4)) ?: 0
        return CoreHeader(
            sampleCount = (bits(NBLKS_BIT, 7) + 1) * 32,
            amode = bits(AMODE_BIT, 6),
            sampleRate = sampleRate,
            lfe = bits(LFF_BIT, 2) != 0
        )
    }

    private fun load(name: String): ByteArray? = try {
        DtsSilence::class.java.getResourceAsStream(name)?.use { it.readBytes() }
            ?.takeIf { it.size == FRAME_BYTES }
    } catch (_: Exception) {
        null
    }

    data class CoreHeader(val sampleCount: Int, val amode: Int, val sampleRate: Int, val lfe: Boolean)

    private const val CORE_HEADER_BYTES = 11
    private const val NBLKS_BIT = 39
    private const val AMODE_BIT = 60
    private const val SFREQ_BIT = 66
    private const val LFF_BIT = 85
    private const val AMODE_STEREO = 2
    private const val AMODE_3_2 = 9
    private val SAMPLE_RATES = intArrayOf(
        0, 8_000, 16_000, 32_000, 0, 0, 11_025, 22_050, 44_100, 0, 0, 12_000, 24_000, 48_000
    ).map { if (it == 0) null else it }
}
