package io.github.agelinena.bergapipe.cast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException

sealed class CastState {
    object Idle : CastState()

    /** [progress] de 0 a 1 enquanto baixa; null quando busca os dados ou junta vídeo e áudio. */
    data class Preparing(
        val deviceName: String,
        val title: String,
        val progress: Float?,
        val muxing: Boolean = false,
        val quality: String = "",
    ) : CastState()

    data class Active(
        val deviceName: String,
        val title: String,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val quality: String,
    ) : CastState()

    data class Failed(val deviceName: String, val title: String, val message: String) : CastState()
}

/**
 * Uma transmissão por vez: prepara o vídeo, serve pelo [MediaServer], conecta à TV e acompanha
 * a posição. Vive no processo; o [CastService] mantém o processo vivo e mostra a notificação.
 */
object CastManager {
    private const val TAG = "BergaCast"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> get() = mutableState

    private var job: Job? = null
    private var session: TvSession? = null
    private var server: MediaServer? = null
    @Volatile private var cancelled = false

    /**
     * Transmite um vídeo. [loadInfo] busca os dados do vídeo (já carregados na tela do vídeo, ou
     * buscados na hora a partir de uma lista).
     */
    fun start(context: Context, device: TvDevice, title: String, loadInfo: suspend () -> StreamInfo) {
        val app = context.applicationContext
        val previous = session
        job?.cancel()
        cancelled = false
        // Antes de ligar o serviço: com o estado Idle ele se desligaria na hora.
        mutableState.value = CastState.Preparing(device.name, title, null)
        CastService.start(app)

        job = scope.launch {
            var shownTitle = title
            try {
                session = null
                if (previous != null) runCatching { previous.stop() }
                server?.stop()
                server = null

                val info = loadInfo()
                shownTitle = info.name
                val plan = CastMediaPreparer.choose(info)
                mutableState.value = CastState.Preparing(device.name, shownTitle, 0f, quality = plan.label)

                val file = withContext(Dispatchers.IO) {
                    CastMediaPreparer(
                        app,
                        onProgress = { progress ->
                            mutableState.value = CastState.Preparing(
                                device.name, shownTitle, progress ?: 1f, muxing = progress == null, quality = plan.label
                            )
                        },
                        isCancelled = { cancelled },
                    ).prepare(info, plan, File(app.cacheDir, "berga-cast"))
                }

                val mediaServer = MediaServer(file)
                val url = withContext(Dispatchers.IO) { mediaServer.start(app) }
                server = mediaServer

                val tv: TvSession = when (device) {
                    is TvDevice.GoogleCast -> GoogleCastTvSession.connect(app, device)
                    is TvDevice.Dlna -> DlnaTvSession(device.renderer)
                }
                session = tv
                val durationMs = info.duration * 1000
                tv.load(url, shownTitle, info.thumbnailUrl, durationMs, file.length())
                mutableState.value = CastState.Active(tv.deviceName, shownTitle, 0, durationMs, true, plan.label)

                watch(tv)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CastMediaPreparer.CancelledException) {
                cleanup()
                mutableState.value = CastState.Idle
            } catch (e: Exception) {
                Log.w(TAG, "Transmissão falhou", e)
                cleanup()
                mutableState.value = CastState.Failed(device.name, shownTitle, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Acompanha a TV a cada segundo até o vídeo acabar ou a TV sumir. */
    private suspend fun watch(tv: TvSession) {
        var sawPlaying = false
        var misses = 0
        while (scope.isActive) {
            delay(1000)
            val current = mutableState.value as? CastState.Active ?: return
            val status = tv.status()
            if (status == null) {
                if (++misses >= 15) throw IOException("A TV parou de responder")
                continue
            }
            misses = 0
            if (status.playing) sawPlaying = true
            if (sawPlaying && status.ended) {
                cleanup(stopTv = false)
                mutableState.value = CastState.Idle
                return
            }
            mutableState.value = current.copy(
                positionMs = if (status.positionMs > 0 || status.playing) status.positionMs else current.positionMs,
                durationMs = if (status.durationMs > 0) status.durationMs else current.durationMs,
                playing = status.playing,
            )
        }
    }

    fun togglePause() {
        val tv = session ?: return
        val current = mutableState.value as? CastState.Active ?: return
        mutableState.value = current.copy(playing = !current.playing)
        scope.launch {
            runCatching { if (current.playing) tv.pause() else tv.play() }
                .onFailure { Log.w(TAG, "Play/pausa falhou", it) }
        }
    }

    fun seekTo(positionMs: Long) {
        val tv = session ?: return
        val current = mutableState.value as? CastState.Active ?: return
        mutableState.value = current.copy(positionMs = positionMs)
        scope.launch { runCatching { tv.seek(positionMs) }.onFailure { Log.w(TAG, "Avançar falhou", it) } }
    }

    fun seekBy(deltaMs: Long) {
        val current = mutableState.value as? CastState.Active ?: return
        val target = (current.positionMs + deltaMs).coerceIn(0, maxOf(0, current.durationMs - 1000))
        seekTo(target)
    }

    /** Para tudo: cancela a preparação, para a TV e o servidor. */
    fun stop() {
        cancelled = true
        job?.cancel()
        job = null
        scope.launch {
            cleanup()
            mutableState.value = CastState.Idle
        }
    }

    fun dismissError() {
        if (mutableState.value is CastState.Failed) mutableState.value = CastState.Idle
    }

    private suspend fun cleanup(stopTv: Boolean = true) {
        val tv = session
        session = null
        if (stopTv && tv != null) runCatching { tv.stop() }
        server?.stop()
        server = null
    }
}
