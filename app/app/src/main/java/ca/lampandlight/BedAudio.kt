package ca.lampandlight

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One bed track, decoded a little at a time as it is played.
 *
 * Until 0.53 a bed was a loop of under a minute, decoded whole and held in memory, which is
 * what made the join sample-exact. A whole track of two or three minutes in stereo is 60 MB
 * held that way and ten of them are not thinkable, and there is no join any more: a track
 * plays through once and the next one fades in over its end. So this hands out the samples
 * as they are wanted and keeps nothing.
 *
 * Always interleaved stereo floats, whatever the file holds, at the file's own rate. Not
 * thread-safe: one stream belongs to the one thread that plays it (see [Bed]).
 */
class BedStream(val name: String, file: File, fallbackSecs: Int = 0) {
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var fed = false
    private var ended = false
    /** Decoded and not yet handed out: floats, two to a frame, from [heldAt] to [heldEnd]. */
    private var held = FloatArray(0)
    private var heldAt = 0
    private var heldEnd = 0
    private var channels = 2
    private var floats = false

    var rate = Bed.RATE; private set
    /** How long the track is, in frames; 0 when the file does not say. */
    val frames: Long
    /** Frames handed out so far, counted from the start of the track. */
    var at = 0L; private set

    init {
        ex.setDataSource(file.path)
        var found: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                ex.selectTrack(i); found = f; break
            }
        }
        val format = found ?: run { ex.release(); error("no audio in ${file.name}") }
        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val us = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        frames = if (us > 0) us * rate / 1_000_000L else fallbackSecs.toLong() * rate
        codec = try {
            MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                .apply { configure(format, null, null, 0); start() }
        } catch (e: Exception) { ex.release(); throw e }
    }

    /** Start from [frame] instead of the beginning. Only before the first [read]. */
    fun seek(frame: Long) {
        if (frame <= 0L) return
        ex.seekTo(frame * 1_000_000L / rate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        at = (ex.sampleTime.coerceAtLeast(0L)) * rate / 1_000_000L
    }

    /**
     * Put up to [want] frames into [dst], from its start; returns how many. Fewer than
     * asked means the track is over, and 0 that it was over already.
     */
    fun read(dst: FloatArray, want: Int): Int {
        var got = 0
        while (got < want) {
            if (heldAt >= heldEnd) { if (!more()) break; continue }
            val n = minOf(want - got, (heldEnd - heldAt) / 2)
            System.arraycopy(held, heldAt, dst, got * 2, n * 2)
            heldAt += n * 2
            got += n
        }
        at += got
        return got
    }

    /** Decode until there is something in [held]. False when the track is over. */
    private fun more(): Boolean {
        var idle = 0
        while (!ended) {
            if (!fed) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                floats = runCatching { f.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                    .getOrDefault(AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                continue
            }
            if (o >= 0) {
                idle = 0
                if (info.size > 0) take(codec.getOutputBuffer(o)!!)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                if (heldEnd > heldAt) return true
            } else if (++idle > 400) {
                // Two seconds with nothing out of the decoder: it is not coming.
                ended = true
            }
        }
        return false
    }

    /** One decoder buffer into [held] as stereo floats, whatever it came as. */
    private fun take(buf: ByteBuffer) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.order(ByteOrder.nativeOrder())
        val ch = channels.coerceAtLeast(1)
        val n = info.size / (if (floats) 4 else 2) / ch
        if (held.size < n * 2) held = FloatArray(n * 2)
        if (floats) {
            val f = buf.asFloatBuffer()
            for (i in 0 until n) {
                val l = f.get(i * ch)
                held[i * 2] = l
                held[i * 2 + 1] = if (ch > 1) f.get(i * ch + 1) else l
            }
        } else {
            val s = buf.asShortBuffer()
            for (i in 0 until n) {
                val l = s.get(i * ch) / 32768f
                held[i * 2] = l
                held[i * 2 + 1] = if (ch > 1) s.get(i * ch + 1) / 32768f else l
            }
        }
        heldAt = 0
        heldEnd = n * 2
    }

    fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { ex.release() }
    }
}
