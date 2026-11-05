package ca.lampandlight

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * Decoding a bed's loop region to PCM, once.
 *
 * Why not let the media player loop it: both ways of asking ExoPlayer to repeat a clipped
 * region — REPEAT_MODE_ONE, and a playlist of identical clipped items — re-seek the source at
 * every wrap. Measured on device, the playback position froze for 100-150 ms each time, which
 * is heard as a gap. ClippingMediaSource is built for trimming a track, not for looping part
 * of one.
 *
 * Holding the region as PCM and writing it round and round is sample-exact by construction,
 * and it is the same approach the narrator already uses. A loop costs about 2 MB per 22
 * seconds at 24 kHz mono.
 */
object BedAudio {

    /** What the bed plays at: half the file's 48 kHz, which is plenty under speech. */
    const val RATE = 24_000

    /**
     * Decode [file] between [fromMs] and [toMs] to mono PCM at [RATE].
     *
     * Returns null rather than throwing: a bed that cannot be decoded should leave the reader
     * in silence, not in a crash.
     */
    fun decode(file: File, fromMs: Long, toMs: Long): FloatArray? = runCatching {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        try {
            var audio = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audio = i; fmt = f; break
                }
            }
            val format = fmt ?: return null
            ex.selectTrack(audio)
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME)!!
            ).apply { configure(format, null, null, 0) ; start() }

            // Seek slightly before the loop start: a decoder needs to be fed from a sync
            // point, and anything decoded early is dropped below.
            ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val out = ArrayList<FloatArray>()
            val info = MediaCodec.BufferInfo()
            var fed = false
            var done = false
            while (!done) {
                if (!fed) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fed = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            if (ex.sampleTime > toMs * 1000) fed = true
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val pcm = pull(codec.getOutputBuffer(outIdx)!!, info, format)
                        // Keep only what falls inside the loop region.
                        val startUs = info.presentationTimeUs
                        val perSample = 1_000_000.0 / srcRate
                        val keep = ArrayList<Float>(pcm.size / srcCh)
                        for (i in pcm.indices step srcCh) {
                            val tUs = startUs + (i / srcCh) * perSample
                            if (tUs < fromMs * 1000) continue
                            if (tUs >= toMs * 1000) { done = true; break }
                            // Mono: average the channels rather than drop one.
                            var sum = 0f
                            for (c in 0 until srcCh) sum += pcm[i + c]
                            keep.add(sum / srcCh)
                        }
                        if (keep.isNotEmpty()) out.add(keep.toFloatArray())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            codec.stop(); codec.release()

            val total = out.sumOf { it.size }
            if (total == 0) return null
            val joined = FloatArray(total)
            var at = 0
            out.forEach { System.arraycopy(it, 0, joined, at, it.size); at += it.size }
            if (srcRate == RATE) joined else resample(joined, srcRate, RATE)
        } finally {
            runCatching { ex.release() }
        }
    }.getOrNull()

    /** Read a decoder output buffer as floats, whatever integer format it used. */
    private fun pull(buf: ByteBuffer, info: MediaCodec.BufferInfo, fmt: MediaFormat): FloatArray {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val enc = runCatching { fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) }
            .getOrDefault(android.media.AudioFormat.ENCODING_PCM_16BIT)
        return when (enc) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val f = buf.asFloatBuffer()
                FloatArray(f.remaining()).also { f.get(it) }
            }
            else -> {
                val sh = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                FloatArray(sh.remaining()) { sh.get(it) / 32768f }
            }
        }
    }

    /** Plain linear resample. These are slow pads; nothing here needs a windowed filter. */
    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || src.isEmpty()) return src
        val ratio = to.toDouble() / from
        val out = FloatArray((src.size * ratio).toInt())
        for (i in out.indices) {
            val x = i / ratio
            val a = x.toInt()
            val b = (a + 1).coerceAtMost(src.size - 1)
            val t = (x - a).toFloat()
            out[i] = src[a] * (1 - t) + src[b] * t
        }
        return out
    }
}
