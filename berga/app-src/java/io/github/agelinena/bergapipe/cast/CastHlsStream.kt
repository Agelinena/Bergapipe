package io.github.agelinena.bergapipe.cast

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import org.schabi.newpipe.extractor.stream.Stream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Toca enquanto baixa (só Chromecast): os downloads crescem em arquivos, "pipes" alimentam o
 * ffmpeg à medida que os bytes chegam, e ele corta o vídeo em pedaços HLS de 4 s (MPEG-TS,
 * H.264 + AAC, sem recodificar) numa playlist que vai crescendo. O Chromecast começa com os
 * primeiros pedaços; quando o download acaba, a playlist ganha o fim e a TV mostra a duração.
 */
class CastHlsStream(
    private val context: Context,
    private val preparer: CastMediaPreparer,
    private val plan: CastPlan,
    private val dir: File,
) {
    @Volatile private var stopped = false
    val isStopped: Boolean get() = stopped
    @Volatile private var downloadDone = false
    @Volatile private var downloadError: Throwable? = null
    @Volatile private var ffmpegDone = false
    @Volatile private var ffmpegError: String? = null
    private var session: FFmpegSession? = null
    private val pipes = mutableListOf<String>()

    val playlist: File get() = File(dir, PLAYLIST)

    /** Download terminado (com ou sem erro). */
    val isDownloadFinished: Boolean get() = downloadDone || downloadError != null

    /** Começa a baixar e a gerar a playlist. Volta na hora. */
    fun start() {
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("Não deu para criar a pasta temporária")

        val streams: List<Stream> = plan.muxed?.let { listOf(it) } ?: listOf(plan.video!!, plan.audio!!)
        val files = streams.indices.map { File(dir, "input$it.media") }
        files.forEach { it.createNewFile() }

        thread(name = "BergaCastHlsDownload") {
            try {
                preparer.download(streams, files)
                downloadDone = true
            } catch (t: Throwable) {
                downloadError = t
            }
        }

        val inputs = files.map { file ->
            val pipe = FFmpegKitConfig.registerNewFFmpegPipe(context)
                ?: throw IOException("O ffmpeg não abriu o pipe de entrada")
            pipes += pipe
            thread(name = "BergaCastHlsFeed") { feed(file, pipe) }
            pipe
        }

        val args = mutableListOf("-hide_banner", "-nostats", "-loglevel", "error", "-y")
        inputs.forEach { args += listOf("-i", it) }
        if (inputs.size == 2) {
            args += listOf("-map", "0:v:0", "-map", "1:a:0")
        } else {
            args += listOf("-map", "0:v:0", "-map", "0:a:0?")
        }
        args += listOf(
            "-c", "copy",
            "-f", "hls",
            "-hls_time", "4",
            "-hls_list_size", "0",
            "-hls_playlist_type", "event",
            "-hls_segment_type", "mpegts",
            // Pedaço só aparece depois de completo, então a TV nunca lê um pela metade.
            "-hls_flags", "independent_segments+temp_file",
            "-hls_segment_filename", File(dir, "seg%05d.ts").absolutePath,
            playlist.absolutePath,
        )
        session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { finished ->
            if (!ReturnCode.isSuccess(finished.returnCode) && !stopped) {
                ffmpegError = finished.output?.takeLast(300)?.ifBlank { null } ?: "código ${finished.returnCode}"
            }
            ffmpegDone = true
        }
    }

    /** Passa os bytes do arquivo para o ffmpeg conforme o download escreve. */
    private fun feed(file: File, pipe: String) {
        try {
            // Abrir o pipe espera o ffmpeg abrir a entrada do outro lado.
            FileOutputStream(pipe).use { out ->
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (!stopped) {
                        val read = input.read(buffer)
                        if (read > 0) {
                            out.write(buffer, 0, read)
                            continue
                        }
                        // Fim do que já chegou: acabou de vez ou ainda vem mais?
                        if (downloadDone && input.channel.position() >= file.length()) break
                        if (downloadError != null) break
                        Thread.sleep(100)
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped) Log.w(TAG, "Alimentar o ffmpeg falhou", e)
        }
    }

    /**
     * Espera ter pedaços suficientes para a TV começar (ou o vídeo inteiro, se for curto).
     * Lança exceção se o download ou o ffmpeg falharem antes disso.
     */
    fun awaitReady(isCancelled: () -> Boolean, timeoutMs: Long = 120_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isCancelled()) throw CastMediaPreparer.CancelledException()
            downloadError?.let { throw it }
            ffmpegError?.let { throw IOException("O ffmpeg falhou ao gerar o HLS: $it") }
            val text = runCatching { playlist.readText() }.getOrDefault("")
            if (text.contains("#EXT-X-ENDLIST") || Regex("#EXTINF").findAll(text).count() >= MIN_SEGMENTS) return
            if (ffmpegDone) throw IOException("O ffmpeg terminou sem gerar o vídeo")
            if (System.currentTimeMillis() > deadline) throw IOException("Demorou demais para começar")
            Thread.sleep(250)
        }
    }

    /** Erro que aconteceu depois de a TV já estar tocando, se houver. */
    fun backgroundError(): String? =
        downloadError?.takeIf { it !is CastMediaPreparer.CancelledException }?.message ?: ffmpegError

    fun stop() {
        stopped = true
        session?.let { runCatching { FFmpegKit.cancel(it.sessionId) } }
        pipes.forEach { pipe ->
            // Um alimentador ainda preso abrindo o pipe só destrava quando alguém abre a
            // outra ponta; abre e fecha numa thread à parte, depois apaga o pipe.
            thread(name = "BergaCastHlsUnblock", isDaemon = true) {
                runCatching { FileInputStream(pipe).close() }
            }
            runCatching { FFmpegKitConfig.closeFFmpegPipe(pipe) }
        }
        pipes.clear()
    }

    companion object {
        private const val TAG = "BergaCast"
        const val PLAYLIST = "index.m3u8"
        /** 3 pedaços de 4 s: dá folga para a TV começar sem travar. */
        private const val MIN_SEGMENTS = 3
    }
}
