package cn.campus.notes.integration

import android.content.Context
import android.media.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/** Lossless AAC remuxing into bounded requests. Original audio is never rewritten. */
class AudioSegments(private val context: Context, private val secondsPerSegment: Long = 180) {
    suspend fun transcribe(file: File, mime: String, consume: suspend (ByteArray, String, Int, Long) -> Unit) {
        require(file.isFile && file.length() in 1..512_000_000) { "音频不存在或超过 512 MB，原件保留" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("文件没有可读取的音轨，原件保留")
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) {
                val duration = if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
                require(duration != null && duration <= secondsPerSegment * 1_000_000 && file.length() <= RecognitionProvider.MAX_INPUT) {
                    "本轮长音频分段支持 AAC/M4A（含应用录音）；其他音频请拆为 3 分钟、7 MB 以内再识别"
                }
                consume(file.inputStream().use { it.readBytesBounded(RecognitionProvider.MAX_INPUT) }, mime, 1, 0)
                return
            }
            extractor.selectTrack(track)
            val dir = File(context.cacheDir, "asr-segments").apply { mkdirs() }
            val buffer = ByteBuffer.allocate(256_000)
            var muxer: MediaMuxer? = null
            var temp: File? = null
            var outputTrack = -1
            var startUs = -1L
            var bytesInSegment = 0
            var index = 0
            suspend fun flush() {
                val active = muxer ?: return
                try { active.stop() } finally { active.release(); muxer = null }
                val segment = temp ?: return
                try {
                    index++; require(index <= 128) { "分段超过 128 段，请拆分原件" }
                    currentCoroutineContext().ensureActive()
                    consume(segment.inputStream().use { it.readBytesBounded(RecognitionProvider.MAX_INPUT) }, "audio/mp4", index, startUs / 1000)
                } finally { segment.delete(); temp = null }
                bytesInSegment = 0
            }
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if(size < 0) break
                    require(size <= buffer.capacity()) { "音频帧过大，无法分段；原件保留" }
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "加密音轨不支持识别" }
                    val time = extractor.sampleTime
                    if(muxer != null && (time - startUs >= secondsPerSegment * 1_000_000 || bytesInSegment + size > 5_000_000)) flush()
                    if(muxer == null) {
                        startUs = time
                        temp = File.createTempFile("segment-", ".m4a", dir)
                        muxer = MediaMuxer(temp!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        outputTrack = muxer!!.addTrack(format); muxer!!.start()
                    }
                    val flags = if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    val info = MediaCodec.BufferInfo().apply { set(0, size, time - startUs, flags) }
                    muxer!!.writeSampleData(outputTrack, buffer, info)
                    bytesInSegment += size
                    extractor.advance()
                }
                flush()
                require(index > 0) { "音轨为空，原件保留" }
            } finally {
                try { muxer?.release() } finally { temp?.delete() }
            }
        } finally { extractor.release() }
    }
}
