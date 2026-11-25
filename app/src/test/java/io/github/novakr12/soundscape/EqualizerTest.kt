package io.github.novakr12.soundscape

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class EqualizerTest {
    private val rate = 48_000

    private fun sineBuffer(hz: Double, sampleRate: Int, encoding: Int, frames: Int = 9600, amplitude: Double = 0.1): ByteBuffer {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val pcm = ByteBuffer.allocate(frames * 2 * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = amplitude * sin(2.0 * PI * hz * i / sampleRate)
            repeat(2) {
                when (encoding) {
                    C.ENCODING_PCM_16BIT -> pcm.putShort((sample * 32767.0).roundToInt().toShort())
                    C.ENCODING_PCM_24BIT -> {
                        val whole = (sample * 8388607.0).roundToInt()
                        pcm.put(whole.toByte()).put((whole shr 8).toByte()).put((whole shr 16).toByte())
                    }
                    C.ENCODING_PCM_32BIT -> pcm.putInt((sample * 2147483647.0).toLong().toInt())
                    else -> pcm.putFloat(sample.toFloat())
                }
            }
        }
        pcm.flip()
        return pcm
    }

    private fun readSamples(buffer: ByteBuffer, encoding: Int, skipFrames: Int = 0): DoubleArray {
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleBytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val channels = 2
        val totalFrames = pcm.remaining() / (sampleBytes * channels)
        val keepFrames = (totalFrames - skipFrames).coerceAtLeast(0)
        val out = DoubleArray(keepFrames)
        var pos = pcm.position() + skipFrames * sampleBytes * channels
        for (frame in 0 until keepFrames) {
            out[frame] = when (encoding) {
                C.ENCODING_PCM_16BIT -> pcm.getShort(pos) / 32768.0
                C.ENCODING_PCM_24BIT -> (
                    (pcm.get(pos).toInt() and 0xff) or
                        ((pcm.get(pos + 1).toInt() and 0xff) shl 8) or
                        (pcm.get(pos + 2).toInt() shl 16)
                    ) / 8388608.0
                C.ENCODING_PCM_32BIT -> pcm.getInt(pos) / 2147483648.0
                else -> pcm.getFloat(pos).toDouble()
            }
            pos += sampleBytes * channels
        }
        return out
    }

    private fun rmsDb(samples: DoubleArray): Double {
        val meanSq = samples.sumOf { it * it } / samples.size.coerceAtLeast(1)
        return 20.0 * log10(sqrt(meanSq).coerceAtLeast(1e-12))
    }

    @Test
    fun clampsSnapsAndRoundTripsEqualizerJson() {
        assertEquals(0.0, Equalizer.clampGain(Double.NaN), 0.0)
        assertEquals(0.0, Equalizer.clampGain(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(-12.0, Equalizer.clampGain(-20.0), 0.0)
        assertEquals(12.0, Equalizer.clampGain(19.0), 0.0)
        assertEquals(1.0, Equalizer.clampGain(1.24), 0.0)
        assertEquals(1.5, Equalizer.clampGain(1.26), 0.0)
        assertEquals(0.0, Equalizer.clampGain(-0.1), 0.0)

        val eq = Equalizer.normalize(true, doubleArrayOf(-20.0, 1.24, Double.NaN, -0.1, 15.0, 3.5))
        assertTrue(eq.isActive)
        assertEquals(10, eq.bands.size)
        assertArrayEquals(doubleArrayOf(-12.0, 1.0, 0.0, 0.0, 12.0, 3.5, 0.0, 0.0, 0.0, 0.0), eq.bands, 0.0)

        val roundTrip = Equalizer.decode(Equalizer.encode(eq))
        assertEquals(eq, roundTrip)

        val alias = Equalizer.decodeObject(JSONObject("""{"on":true,"bands":[3, -2.5, "bad"]}"""))!!
        assertTrue(alias.enabled)
        assertArrayEquals(doubleArrayOf(3.0, -2.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), alias.bands, 0.0)

        assertFalse(EqualizerState(enabled = true, bands = DoubleArray(10)).isActive)
        assertFalse(EqualizerState(enabled = false, bands = doubleArrayOf(5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).isActive)
        assertNull(Equalizer.decode("not json"))
        assertNull(Equalizer.decode(null))
        assertNull(Equalizer.decodeObject(null))
    }

    @Test
    fun passesThroughBitExactWhenDisabledOrFlatAcrossPcmFormats() {
        val formats = listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        val nonFlatDisabled = Equalizer.normalize(false, doubleArrayOf(6.0, -6.0, 12.0, 0.0, 3.0))
        val flatEnabled = EqualizerState(enabled = true, bands = DoubleArray(10))

        for (state in listOf(nonFlatDisabled, flatEnabled)) {
            for (encoding in formats) {
                val processor = EqualizerAudioProcessor(state)
                val configured = processor.configure(AudioProcessor.AudioFormat(rate, 2, encoding))
                assertNotEquals(AudioProcessor.AudioFormat.NOT_SET, configured)
                processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

                val input = sineBuffer(1000.0, rate, encoding, frames = 512)
                val expectedBytes = ByteArray(input.remaining())
                input.duplicate().get(expectedBytes)

                processor.queueInput(input)
                val out = processor.output
                val actualBytes = ByteArray(out.remaining())
                out.get(actualBytes)
                assertArrayEquals("Pass-through mismatch for $encoding", expectedBytes, actualBytes)
            }
        }
    }

    @Test
    fun shapesTargetedBandWhileLeavingDistantBandsNeutral() {
        val boostBands = DoubleArray(10).also { it[2] = 12.0 } // 119 Hz +12 dB
        val cutBands = DoubleArray(10).also { it[2] = -12.0 } // 119 Hz -12 dB

        val targetRaw = sineBuffer(119.0, rate, C.ENCODING_PCM_FLOAT)
        val targetBaseDb = rmsDb(readSamples(targetRaw, C.ENCODING_PCM_FLOAT, skipFrames = 2400))

        val boostProc = EqualizerAudioProcessor(EqualizerState(true, boostBands)).apply {
            configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        boostProc.queueInput(sineBuffer(119.0, rate, C.ENCODING_PCM_FLOAT))
        val boostedDb = rmsDb(readSamples(boostProc.output, C.ENCODING_PCM_FLOAT, skipFrames = 2400))
        assertEquals(12.0, boostedDb - targetBaseDb, 1.0)

        val cutProc = EqualizerAudioProcessor(EqualizerState(true, cutBands)).apply {
            configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        cutProc.queueInput(sineBuffer(119.0, rate, C.ENCODING_PCM_FLOAT))
        val cutDb = rmsDb(readSamples(cutProc.output, C.ENCODING_PCM_FLOAT, skipFrames = 2400))
        assertEquals(-12.0, cutDb - targetBaseDb, 1.0)

        val distantRaw = sineBuffer(7523.0, rate, C.ENCODING_PCM_FLOAT)
        val distantBaseDb = rmsDb(readSamples(distantRaw, C.ENCODING_PCM_FLOAT, skipFrames = 2400))
        boostProc.flush(AudioProcessor.StreamMetadata.DEFAULT)
        boostProc.queueInput(sineBuffer(7523.0, rate, C.ENCODING_PCM_FLOAT))
        val distantAfterDb = rmsDb(readSamples(boostProc.output, C.ENCODING_PCM_FLOAT, skipFrames = 2400))
        assertEquals(0.0, distantAfterDb - distantBaseDb, 1.0)
    }

    @Test
    fun staysSafeAtLowSampleRatesAndUpdatesLiveMidStream() {
        val topBoost = EqualizerState(true, DoubleArray(10).also { it[9] = 12.0 }) // 15011 Hz @ 16kHz rate
        val lowRateProc = EqualizerAudioProcessor(topBoost).apply {
            configure(AudioProcessor.AudioFormat(16_000, 2, C.ENCODING_PCM_FLOAT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        val lowInput = sineBuffer(1000.0, 16_000, C.ENCODING_PCM_FLOAT, frames = 1024)
        lowRateProc.queueInput(lowInput)
        val outSamples = readSamples(lowRateProc.output, C.ENCODING_PCM_FLOAT)
        assertTrue(outSamples.all { it.isFinite() })

        val proc = EqualizerAudioProcessor(Equalizer.DEFAULT).apply {
            configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        val baseInput = sineBuffer(119.0, rate, C.ENCODING_PCM_FLOAT)
        val baseDb = rmsDb(readSamples(baseInput, C.ENCODING_PCM_FLOAT, skipFrames = 2400))

        proc.queueInput(sineBuffer(119.0, rate, C.ENCODING_PCM_FLOAT))
        val flatOutDb = rmsDb(readSamples(proc.output, C.ENCODING_PCM_FLOAT, skipFrames = 2400))
        assertEquals(0.0, flatOutDb - baseDb, 0.05)

        proc.update(EqualizerState(true, DoubleArray(10).also { it[2] = 12.0 }))
        proc.queueInput(sineBuffer(119.0, rate, C.ENCODING_PCM_FLOAT))
        val updatedOutDb = rmsDb(readSamples(proc.output, C.ENCODING_PCM_FLOAT, skipFrames = 2400))
        assertEquals(12.0, updatedOutDb - baseDb, 1.0)
    }
}
