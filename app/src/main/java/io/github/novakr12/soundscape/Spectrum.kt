package io.github.novakr12.soundscape

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

@OptIn(UnstableApi::class)
class Spectrum {
    @Volatile
    var on = false

    @Volatile
    var latest: ByteArray? = null
        private set

    private class Reading(val atUs: Long, val bins: ByteArray)

    private val ahead = ArrayDeque<Reading>()
    private val window = FloatArray(SIZE) {
        (0.42 - 0.5 * cos(2 * PI * it / SIZE) + 0.08 * cos(4 * PI * it / SIZE)).toFloat()
    }
    private val samples = FloatArray(SIZE)
    private val real = FloatArray(SIZE)
    private val imaginary = FloatArray(SIZE)
    private val power = DoubleArray(BINS)
    private var filled = 0
    private var windows = 0
    private var frames = 0
    private var rate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var sampleBytes = 0
    private var takenUs = C.TIME_UNSET

    fun configure(sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
        rate = sampleRate
        channels = channelCount
        encoding = pcmEncoding
        sampleBytes = when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        restart()
    }

    fun hear(buffer: ByteBuffer, timeUs: Long) {
        if (!on) {
            latest = null
            if (frames > 0) restart()
            return
        }
        if (timeUs == takenUs || rate <= 0 || channels <= 0 || sampleBytes == 0) return
        takenUs = timeUs
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = pcm.position()
        val step = channels * sampleBytes
        val wanted = rate * EVERY_MS / 1000
        for (frame in 0 until pcm.remaining() / step) {
            var sum = 0f
            for (channel in 0 until channels) sum += sample(pcm, start + frame * step + channel * sampleBytes)
            samples[filled++] = sum / channels
            frames++
            if (filled < SIZE) continue
            filled = 0
            addWindow()
            if (frames < wanted) continue
            val middle = frame - frames / 2
            ahead.addLast(Reading(timeUs + middle * 1_000_000L / rate, bins()))
            if (ahead.size > MOST_AHEAD) ahead.removeFirst()
            restart()
        }
    }

    fun playedTo(positionUs: Long) {
        while (ahead.isNotEmpty() && ahead.first().atUs <= positionUs) latest = ahead.removeFirst().bins
    }

    fun wakeWithinUs(durationUs: Long): Long = if (on) minOf(durationUs, WAKE_US) else durationUs

    fun clear() {
        ahead.clear()
        latest = null
        takenUs = C.TIME_UNSET
        restart()
    }

    private fun restart() {
        power.fill(0.0)
        filled = 0
        windows = 0
        frames = 0
    }

    private fun sample(pcm: ByteBuffer, at: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> pcm.getShort(at) / 32768f
        C.ENCODING_PCM_24BIT ->
            ((pcm.get(at).toInt() and 0xff) or ((pcm.get(at + 1).toInt() and 0xff) shl 8) or
                (pcm.get(at + 2).toInt() shl 16)) / 8388608f
        C.ENCODING_PCM_32BIT -> pcm.getInt(at) / 2147483648f
        else -> pcm.getFloat(at)
    }

    private fun addWindow() {
        for (i in 0 until SIZE) {
            real[i] = samples[i] * window[i]
            imaginary[i] = 0f
        }
        transform()
        for (i in 0 until BINS) {
            power[i] += (real[i] * real[i] + imaginary[i] * imaginary[i]).toDouble() / (SIZE * SIZE)
        }
        windows++
    }

    private fun bins() = ByteArray(BINS) {
        val db = 10 * log10((power[it] / windows).coerceAtLeast(1e-12))
        (255 * (db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0.0, 255.0).toInt().toByte()
    }

    private fun transform() {
        var j = 0
        for (i in 1 until SIZE) {
            var bit = SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imaginary[i] = imaginary[j].also { imaginary[j] = imaginary[i] }
            }
        }
        var length = 2
        while (length <= SIZE) {
            val half = length / 2
            for (k in 0 until half) {
                val angle = -2 * PI * k / length
                val cosine = cos(angle).toFloat()
                val sine = sin(angle).toFloat()
                for (even in k until SIZE step length) {
                    val odd = even + half
                    val re = real[odd] * cosine - imaginary[odd] * sine
                    val im = real[odd] * sine + imaginary[odd] * cosine
                    real[odd] = real[even] - re
                    imaginary[odd] = imaginary[even] - im
                    real[even] += re
                    imaginary[even] += im
                }
            }
            length *= 2
        }
    }

    companion object {
        const val BINS = 128
        private const val SIZE = BINS * 2
        private const val EVERY_MS = 45
        private const val MOST_AHEAD = 128
        private const val WAKE_US = 10_000L

        private const val MIN_DB = -65.0
        private const val MAX_DB = -14.0
    }
}

@OptIn(UnstableApi::class)
class SpectrumSink(sink: AudioSink, private val spectrum: Spectrum) : ForwardingAudioSink(sink) {
    override fun configure(config: AudioSink.AudioSinkConfig) {
        super.configure(config)
        spectrum.configure(config.format.sampleRate, config.format.channelCount, config.format.pcmEncoding)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        spectrum.hear(buffer, presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET) spectrum.playedTo(position)
        return position
    }

    override fun flush() {
        spectrum.clear()
        super.flush()
    }

    override fun reset() {
        spectrum.clear()
        super.reset()
    }
}
