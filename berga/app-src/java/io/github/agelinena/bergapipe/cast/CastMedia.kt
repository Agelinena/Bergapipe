package io.github.agelinena.bergapipe.cast

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.datasource.YoutubeHttpDataSource
import org.schabi.newpipe.youtube.SabrAttestationRetryHandler
import org.schabi.newpipe.youtube.SabrPlayerReloader
import org.schabi.newpipe.youtube.SabrRequestCoordinator
import us.shandian.giga.get.MissionRecoveryInfo
import us.shandian.giga.get.SabrDownloadFormatResolver
import us.shandian.giga.get.SabrDownloadTarget
import us.shandian.giga.get.SabrSegmentWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** Vídeo e áudio escolhidos para a TV. Ou os dois separados, ou um arquivo já misturado. */
data class CastPlan(val video: VideoStream?, val audio: AudioStream?, val muxed: VideoStream?) {
    val label: String
        get() = (video ?: muxed)?.resolution ?: ""
}

/**
 * Prepara um arquivo MP4 (H.264 + AAC, até 1080p) que o Chromecast e as TVs Samsung tocam:
 * baixa os formatos escolhidos (por URL ou pela sessão SABR do YouTube, as mesmas peças que os
 * downloads do PipePipe usam) e junta com o ffmpeg, sem recodificar.
 */
class CastMediaPreparer(
    private val context: Context,
    private val onProgress: (Float?) -> Unit,
    private val isCancelled: () -> Boolean,
) {
    fun prepare(info: StreamInfo, plan: CastPlan, workDir: File): File {
        workDir.deleteRecursively()
        if (!workDir.mkdirs()) throw IOException("Não deu para criar a pasta temporária")

        plan.muxed?.let { muxed ->
            val out = File(workDir, "media.mp4")
            download(listOf(muxed), listOf(out))
            return out
        }

        val video = plan.video!!
        val audio = plan.audio!!
        val videoFile = File(workDir, "video.media")
        val audioFile = File(workDir, "audio.media")
        download(listOf(video, audio), listOf(videoFile, audioFile))
        checkCancelled()

        onProgress(null)
        val out = File(workDir, "media.mp4")
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-hide_banner", "-nostats", "-loglevel", "error", "-y",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-map", "0:v:0", "-map", "1:a:0",
                "-c", "copy",
                "-movflags", "+faststart",
                out.absolutePath,
            )
        )
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException("Não deu para juntar vídeo e áudio: ${session.output?.takeLast(300)}")
        }
        videoFile.delete()
        audioFile.delete()
        return out
    }

    // ----- Download -----

    private var totalBytes = 0L
    private var doneBytes = 0L
    /** Um dos downloads paralelos falhou: os outros param também. */
    @Volatile private var aborted = false

    /**
     * Baixa os formatos, cada um no seu arquivo, crescendo do começo para o fim. Os de URL
     * baixam em paralelo e os SABR numa sessão só, então vídeo e áudio avançam juntos (o que
     * permite começar a tocar antes do fim, em [CastHlsStream]).
     */
    fun download(streams: List<Stream>, files: List<File>) {
        synchronized(this) {
            totalBytes = 0
            doneBytes = 0
        }
        aborted = false
        val sabr = streams.indices.filter { streams[it].deliveryMethod == DeliveryMethod.SABR }
        val direct = streams.indices - sabr.toSet()
        val errors = ConcurrentLinkedQueue<Throwable>()
        fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                errors += t
                aborted = true
            }
        }
        val threads = direct.map { i ->
            thread(name = "BergaCastFetch$i") {
                guarded {
                    val stream = streams[i]
                    if (!stream.isUrl) throw IOException("Formato sem endereço direto (${stream.deliveryMethod})")
                    fetchUrl(stream.content, stream.itagItem?.contentLength ?: -1, files[i])
                }
            }
        }
        if (sabr.isNotEmpty()) guarded { fetchSabr(sabr.map { streams[it] }, sabr.map { files[it] }) }
        threads.forEach { it.join() }
        // Prefere o erro de verdade ao "cancelado" que ele causou nos outros downloads.
        (errors.firstOrNull { it !is CancelledException } ?: errors.firstOrNull())?.let { throw it }
    }

    @Synchronized
    private fun addTotal(bytes: Long) {
        if (bytes > 0) totalBytes += bytes
    }

    @Synchronized
    private fun reportBytes(delta: Long) {
        doneBytes += delta
        onProgress(if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f)
    }

    private fun checkCancelled() {
        if (isCancelled() || aborted) throw CancelledException()
    }

    /** Baixa por URL em pedaços, com a mesma fonte HTTP que o player usa para o YouTube. */
    private fun fetchUrl(url: String, expectedLength: Long, file: File) {
        val factory = YoutubeHttpDataSource.Factory()
            .setRangeParameterEnabled(true)
            .setRnParameterEnabled(true)
        var position = 0L
        var knownLength = if (expectedLength > 0) expectedLength else -1L
        addTotal(knownLength)
        val buffer = ByteArray(64 * 1024)
        FileOutputStream(file).use { out ->
            while (knownLength < 0 || position < knownLength) {
                checkCancelled()
                val source = factory.createDataSource()
                val spec = DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(position)
                    .setLength(if (knownLength > 0) minOf(CHUNK_BYTES, knownLength - position) else CHUNK_BYTES)
                    .build()
                try {
                    source.open(spec)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    source.close()
                    if (e.responseCode == 416) break
                    throw IOException("O servidor do vídeo respondeu HTTP ${e.responseCode}", e)
                }
                if (knownLength < 0) {
                    knownLength = source.responseHeaders["Content-Range"]?.firstOrNull()
                        ?.substringAfterLast('/')?.toLongOrNull() ?: -1
                    addTotal(knownLength)
                }
                var readInChunk = 0L
                try {
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, read)
                        position += read
                        readInChunk += read
                        reportBytes(read.toLong())
                        if (readInChunk % (1024 * 1024) < read) checkCancelled()
                    }
                } finally {
                    source.close()
                }
                if (readInChunk == 0L || (knownLength < 0 && readInChunk < CHUNK_BYTES)) break
            }
        }
    }

    /**
     * Baixa formatos SABR do começo ao fim, como o SabrDownloader do PipePipe, gravando cada
     * formato num arquivo.
     */
    private fun fetchSabr(streams: List<Stream>, files: List<File>) {
        val info = streams.firstNotNullOfOrNull { it.deliveryMethodInfo as? YoutubeSabrInfo }
            ?: throw IOException("Faltam os dados SABR do vídeo")
        val recoveries = streams.map { MissionRecoveryInfo(it) }.toTypedArray()
        val workDir = files.first().parentFile!!
        val built = SabrDownloadFormatResolver.buildTargets(info, recoveries, workDir)
        // Usa os nomes de arquivo que o preparador espera.
        val targets = built.mapIndexed { i, target -> target.copy(file = files[i]) }
        addTotal(targets.sumOf { it.format.contentLength.coerceAtLeast(0) })

        val session = YoutubeSabrSession(info)
        val poToken = info.poToken ?: throw IOException("O YouTube não liberou este vídeo (sem PO Token)")
        session.setPoToken(poToken)
        session.setInfoReloader(SabrPlayerReloader)
        val coordinator = SabrRequestCoordinator(session, SabrAttestationRetryHandler(info.videoId), null)

        val outputs = targets.associate { it.resourceIndex to FileOutputStream(it.file) }
        try {
            val writer = SabrSegmentWriter(targets, outputs) { _, delta -> reportBytes(delta) }
            writer.observeWrittenInitializations()
            request(coordinator, YoutubeSabrRequest.preparation(0L, targets.map { it.format }), writer)
            writer.observeWrittenInitializations()

            var idle = 0
            while (!isComplete(targets)) {
                checkCancelled()
                val playerTimeMs = targets.minOf { t ->
                    if (t.nextWriteSequence <= 1) 0L else t.timeline?.getEndMs(t.nextWriteSequence - 1) ?: 0L
                }
                val tracks = targets.sortedBy { if (it.format.isAudio) 0 else 1 }.map {
                    YoutubeSabrRequest.Track.of(it.format, it.timeline, it.nextWriteSequence - 1)
                }
                val before = targets.map { it.nextWriteSequence }
                request(coordinator, YoutubeSabrRequest.playback(playerTimeMs, 1.0f, tracks), writer)
                writer.observeWrittenInitializations()
                if (targets.indices.any { targets[it].nextWriteSequence > before[it] }) {
                    idle = 0
                } else if (++idle >= 60) {
                    throw IOException("O YouTube parou de mandar o vídeo")
                } else {
                    Thread.sleep(250)
                }
            }
        } finally {
            outputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun request(coordinator: SabrRequestCoordinator, request: YoutubeSabrRequest, writer: SabrSegmentWriter) {
        var attempts = 0
        while (true) {
            try {
                coordinator.request(request) { segment -> writer.acceptSegment(segment) }
                return
            } catch (e: IOException) {
                val retryable = e is SocketTimeoutException || e is SocketException
                if (!retryable || ++attempts > 2) throw e
                Thread.sleep(1000)
                checkCancelled()
            }
        }
    }

    private fun isComplete(targets: List<SabrDownloadTarget>) = targets.all { t ->
        t.pending.isEmpty() && t.nextWriteSequence > (t.timeline?.endSequence ?: Int.MAX_VALUE)
    }

    class CancelledException : IOException("Cancelado")

    companion object {
        private const val CHUNK_BYTES = 10L * 1024 * 1024
        /** Chromecasts antigos e TVs Samsung tocam H.264 até 1080p. */
        private const val MAX_HEIGHT = 1080

        /** Escolhe a melhor combinação que a TV toca. */
        fun choose(info: StreamInfo): CastPlan {
            fun height(v: VideoStream) =
                if (v.height > 0) v.height else v.resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            fun playable(s: Stream) = s.deliveryMethod == DeliveryMethod.SABR ||
                (s.isUrl && (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || s.deliveryMethod == DeliveryMethod.DASH))
            fun isH264(v: VideoStream) = v.format == MediaFormat.MPEG_4 &&
                v.codec.let { it.isNullOrEmpty() || it.startsWith("avc") }

            val video = info.videoOnlyStreams
                .filter { isH264(it) && playable(it) && height(it) in 1..MAX_HEIGHT }
                .maxWithOrNull(compareBy<VideoStream>({ height(it) }, { it.fps }, { it.bitrate }))

            val m4a = info.audioStreams.filter { it.format == MediaFormat.M4A && playable(it) }
            // Faixa original quando o vídeo tem dublagens (no YouTube o id termina em ".4").
            val audio = m4a.filter { it.audioTrackId?.endsWith(".4") ?: true }
                .ifEmpty { m4a }
                .maxByOrNull { it.averageBitrate }

            val muxed = info.videoStreams
                .filter { !it.isVideoOnly && isH264(it) && playable(it) }
                .maxByOrNull { height(it) }

            if (video != null && audio != null && (muxed == null || height(video) > height(muxed))) {
                return CastPlan(video, audio, null)
            }
            if (muxed != null) return CastPlan(null, null, muxed)
            throw IOException("Este vídeo não tem um formato que a TV toque (H.264 com AAC)")
        }
    }
}
