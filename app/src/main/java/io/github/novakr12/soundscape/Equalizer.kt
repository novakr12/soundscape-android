package io.github.novakr12.soundscape

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class EqualizerState(
    val enabled: Boolean = false,
    val bands: DoubleArray = DoubleArray(Equalizer.BAND_COUNT),
) {
    val isActive: Boolean
        get() = enabled && bands.any { abs(it) > 1e-6 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqualizerState) return false
        return enabled == other.enabled && bands.contentEquals(other.bands)
    }

    override fun hashCode(): Int = 31 * enabled.hashCode() + bands.contentHashCode()
}

object Equalizer {
    const val BAND_COUNT = 10
    const val MIN_DB = -12.0
    const val MAX_DB = 12.0
    const val STEP_DB = 0.5

    val FREQUENCIES = doubleArrayOf(
        29.0,
        59.0,
        119.0,
        237.0,
        474.0,
        947.0,
        1889.0,
        3770.0,
        7523.0,
        15011.0,
    )

    val DEFAULT = EqualizerState(false, DoubleArray(BAND_COUNT))

    fun clampGain(value: Double): Double {
        if (!value.isFinite()) return 0.0
        val clamped = value.coerceIn(MIN_DB, MAX_DB)
        val snapped = round(clamped / STEP_DB) * STEP_DB
        return if (snapped == 0.0) 0.0 else snapped
    }

    fun normalize(enabled: Boolean, rawBands: DoubleArray): EqualizerState {
        val clean = DoubleArray(BAND_COUNT)
        for (i in 0 until minOf(BAND_COUNT, rawBands.size)) {
            clean[i] = clampGain(rawBands[i])
        }
        return EqualizerState(enabled, clean)
    }

    fun encode(state: EqualizerState): String {
        val clean = normalize(state.enabled, state.bands)
        return JSONObject()
            .put("enabled", clean.enabled)
            .put("bands", JSONArray().apply { clean.bands.forEach(::put) })
            .toString()
    }

    fun decodeObject(obj: JSONObject?): EqualizerState? {
        obj ?: return null
        val enabled = when {
            obj.has("enabled") && !obj.isNull("enabled") -> obj.optBoolean("enabled", false)
            else -> obj.optBoolean("on", false)
        }
        val array = obj.optJSONArray("bands")
        val bands = DoubleArray(BAND_COUNT)
        if (array != null) {
            for (i in 0 until minOf(BAND_COUNT, array.length())) {
                val raw = (array.opt(i) as? Number)?.toDouble() ?: Double.NaN
                bands[i] = clampGain(raw)
            }
        }
        return EqualizerState(enabled, bands)
    }

    fun decode(json: String?): EqualizerState? {
        if (json.isNullOrBlank()) return null
        return decodeObject(runCatching { JSONObject(json) }.getOrNull())
    }
}

@OptIn(UnstableApi::class)
class EqualizerAudioProcessor(initial: EqualizerState = Equalizer.DEFAULT) : BaseAudioProcessor() {
    @Volatile
    private var desired = Equalizer.normalize(initial.enabled, initial.bands)
    private val revision = AtomicLong(1L)
    private var appliedRevision = 0L

    private val b0 = DoubleArray(Equalizer.BAND_COUNT)
    private val b1 = DoubleArray(Equalizer.BAND_COUNT)
    private val b2 = DoubleArray(Equalizer.BAND_COUNT)
    private val a1 = DoubleArray(Equalizer.BAND_COUNT)
    private val a2 = DoubleArray(Equalizer.BAND_COUNT)
    private val activeBand = BooleanArray(Equalizer.BAND_COUNT)
    private var anyActive = false

    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    fun update(state: EqualizerState) {
        desired = Equalizer.normalize(state.enabled, state.bands)
        revision.incrementAndGet()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
            -> true
            else -> false
        }
        if (!supported || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val slots = Equalizer.BAND_COUNT * inputAudioFormat.channelCount
        z1 = DoubleArray(slots)
        z2 = DoubleArray(slots)
        appliedRevision = 0L
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    override fun onReset() {
        z1 = DoubleArray(0)
        z2 = DoubleArray(0)
        appliedRevision = 0L
        anyActive = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val currentRevision = revision.get()
        if (currentRevision != appliedRevision) {
            refreshCoefficients(inputAudioFormat.sampleRate, desired)
            appliedRevision = currentRevision
        }

        val output = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
        if (!anyActive) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val channels = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val sampleBytes = bytesPerFrame / channels
        val frames = remaining / bytesPerFrame
        var pos = src.position()

        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                var x = readSample(src, pos, encoding)
                for (band in 0 until Equalizer.BAND_COUNT) {
                    if (!activeBand[band]) continue
                    val idx = band * channels + ch
                    val y = b0[band] * x + z1[idx]
                    z1[idx] = b1[band] * x - a1[band] * y + z2[idx]
                    z2[idx] = b2[band] * x - a2[band] * y
                    x = y
                }
                writeSample(output, x.coerceIn(-1.0, 1.0), encoding)
                pos += sampleBytes
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun refreshCoefficients(sampleRate: Int, state: EqualizerState) {
        if (!state.enabled || sampleRate <= 0) {
            activeBand.fill(false)
            anyActive = false
            z1.fill(0.0)
            z2.fill(0.0)
            return
        }
        var foundActive = false
        val q = sqrt(2.0)
        for (band in 0 until Equalizer.BAND_COUNT) {
            val gainDb = state.bands[band]
            val freq = Equalizer.FREQUENCIES[band]
            if (abs(gainDb) < 1e-6 || freq >= 0.45 * sampleRate) {
                if (activeBand[band]) zeroBandState(band)
                activeBand[band] = false
                continue
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val rawB0: Double
            val rawB1: Double
            val rawB2: Double
            val rawA0: Double
            val rawA1: Double
            val rawA2: Double
            when (band) {
                0 -> {
                    val alpha = sinW0 / sqrt(2.0)
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    rawB0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                    rawB1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                    rawB2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                    rawA0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                    rawA1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                    rawA2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
                }
                Equalizer.BAND_COUNT - 1 -> {
                    val alpha = sinW0 / sqrt(2.0)
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    rawB0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                    rawB1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                    rawB2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                    rawA0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                    rawA1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                    rawA2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
                }
                else -> {
                    val alpha = sinW0 / (2.0 * q)
                    rawB0 = 1.0 + alpha * a
                    rawB1 = -2.0 * cosW0
                    rawB2 = 1.0 - alpha * a
                    rawA0 = 1.0 + alpha / a
                    rawA1 = -2.0 * cosW0
                    rawA2 = 1.0 - alpha / a
                }
            }
            b0[band] = rawB0 / rawA0
            b1[band] = rawB1 / rawA0
            b2[band] = rawB2 / rawA0
            a1[band] = rawA1 / rawA0
            a2[band] = rawA2 / rawA0
            activeBand[band] = true
            foundActive = true
        }
        anyActive = foundActive
    }

    private fun zeroBandState(band: Int) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(0)
        val start = band * channels
        val end = minOf(z1.size, start + channels)
        for (i in start until end) {
            z1[i] = 0.0
            z2[i] = 0.0
        }
    }

    private fun readSample(pcm: ByteBuffer, at: Int, encoding: Int): Double = when (encoding) {
        C.ENCODING_PCM_16BIT -> pcm.getShort(at) / 32768.0
        C.ENCODING_PCM_24BIT -> (
            (pcm.get(at).toInt() and 0xff) or
                ((pcm.get(at + 1).toInt() and 0xff) shl 8) or
                (pcm.get(at + 2).toInt() shl 16)
            ) / 8388608.0
        C.ENCODING_PCM_32BIT -> pcm.getInt(at) / 2147483648.0
        else -> pcm.getFloat(at).toDouble().let { if (it.isFinite()) it else 0.0 }
    }

    private fun writeSample(out: ByteBuffer, value: Double, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val whole = (value * 32767.0).roundToInt().coerceIn(-32768, 32767)
                out.putShort(whole.toShort())
            }
            C.ENCODING_PCM_24BIT -> {
                val whole = (value * 8388607.0).roundToInt().coerceIn(-8388608, 8388607)
                out.put(whole.toByte()).put((whole shr 8).toByte()).put((whole shr 16).toByte())
            }
            C.ENCODING_PCM_32BIT -> {
                val whole = (value * 2147483647.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                out.putInt(whole)
            }
            else -> out.putFloat(value.toFloat())
        }
    }
}
