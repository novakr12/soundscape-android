package io.github.novakr12.soundscape

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(UnstableApi::class)
class SpectrumTest {
    private val rate = 48_000

    private fun tone(hz: Double, encoding: Int, frames: Int = 4800): ByteBuffer {
        val pcm = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val value = 0.5 * sin(2 * PI * hz * i / rate)
            repeat(2) {
                when (encoding) {
                    C.ENCODING_PCM_16BIT -> pcm.putShort((value * 32767).roundToInt().toShort())
                    C.ENCODING_PCM_24BIT -> {
                        val whole = (value * 8388607).roundToInt()
                        pcm.put(whole.toByte()).put((whole shr 8).toByte()).put((whole shr 16).toByte())
                    }
                    C.ENCODING_PCM_32BIT -> pcm.putInt((value * 2147483647).roundToInt())
                    else -> pcm.putFloat(value.toFloat())
                }
            }
        }
        pcm.flip()
        return pcm
    }

    private fun listening(encoding: Int) = Spectrum().apply {
        configure(rate, 2, encoding)
        on = true
    }

    @Test
    fun findsToneInEveryPcmFormat() {
        val formats =
            listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        for (encoding in formats) {
            for (hz in listOf(375.0, 3000.0, 15_000.0)) {
                val spectrum = listening(encoding)
                spectrum.hear(tone(hz, encoding), 0)
                spectrum.playedTo(1_000_000)
                val bins = spectrum.latest!!.map { it.toInt() and 0xff }
                val loudest = bins.indices.maxBy { bins[it] }
                assertEquals("$hz Hz in $encoding", hz * 256 / rate, loudest.toDouble(), 1.0)
                assertTrue(bins[loudest] > 200)
                assertTrue(bins.filterIndexed { at, _ -> abs(at - loudest) > 4 }.all { it < 20 })
            }
        }
    }

    @Test
    fun waitsUntilTheSoundIsHeard() {
        val spectrum = listening(C.ENCODING_PCM_16BIT)
        val start = 1_000_000_000_000L
        spectrum.hear(tone(1000.0, C.ENCODING_PCM_16BIT), start)
        spectrum.playedTo(start + 20_000)
        assertNull(spectrum.latest)
        spectrum.playedTo(start + 60_000)
        assertTrue(spectrum.latest != null)
    }

    @Test
    fun takesARetriedBufferOnlyOnceAndLeavesItUntouched() {
        val spectrum = listening(C.ENCODING_PCM_16BIT)
        val pcm = tone(1000.0, C.ENCODING_PCM_16BIT)
        val length = pcm.remaining()
        spectrum.hear(pcm, 0)
        spectrum.playedTo(1_000_000)
        val first = spectrum.latest
        spectrum.hear(pcm, 0)
        spectrum.playedTo(2_000_000)
        assertTrue(first === spectrum.latest)
        assertEquals(0, pcm.position())
        assertEquals(length, pcm.remaining())
    }

    @Test
    fun doesNothingWhileNobodyWatches() {
        val spectrum = listening(C.ENCODING_PCM_16BIT).apply { on = false }
        spectrum.hear(tone(1000.0, C.ENCODING_PCM_16BIT), 0)
        spectrum.playedTo(1_000_000)
        assertNull(spectrum.latest)
    }

    @Test
    fun handsThePageUnsignedBins() {
        val script = Bridge.spectrum(byteArrayOf(0, 127, -128, -1))
        assertTrue(script.contains("detail: [0,127,128,255]"))
        assertTrue(script.contains("soundscape-spectrum-bins"))
    }
}
