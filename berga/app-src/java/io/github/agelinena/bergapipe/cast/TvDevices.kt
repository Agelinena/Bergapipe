package io.github.agelinena.bergapipe.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uma TV encontrada na rede. */
sealed class TvDevice {
    abstract val id: String
    abstract val name: String

    data class GoogleCast(val route: MediaRouter.RouteInfo) : TvDevice() {
        override val id: String get() = "cast:" + route.id
        override val name: String get() = route.name
    }

    data class Dlna(val renderer: DlnaRenderer) : TvDevice() {
        override val id: String get() = "dlna:" + renderer.controlUrl
        override val name: String get() = renderer.name
    }
}

data class TvStatus(val positionMs: Long, val durationMs: Long, val playing: Boolean, val ended: Boolean)

/** Controle de uma TV depois de conectada. */
interface TvSession {
    val deviceName: String
    /** [hls]: [url] é uma playlist HLS que ainda cresce (tocar enquanto baixa). */
    suspend fun load(url: String, title: String, thumbnail: String?, durationMs: Long, sizeBytes: Long, hls: Boolean)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun status(): TvStatus?
    /** Para a reprodução na TV e desconecta. */
    suspend fun stop()
}

// ===================== Google Cast =====================

/** Lido pelo Cast SDK pelo nome (meta-dado no manifesto do módulo berga). */
class BergaCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            // Receptor padrão do Google: toca MP4 sem app próprio na TV.
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object GoogleCastDevices {
    val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            )
            .build()
    }

    /** Inicia o Cast SDK. Devolve false em aparelhos sem Google Play Services. */
    fun init(context: Context): Boolean = try {
        CastContext.getSharedInstance(context.applicationContext)
        true
    } catch (e: Exception) {
        Log.w("BergaCast", "Cast indisponível", e)
        false
    }

    fun currentRoutes(context: Context): List<TvDevice.GoogleCast> =
        MediaRouter.getInstance(context).routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { TvDevice.GoogleCast(it) }
}

class GoogleCastTvSession private constructor(
    private val context: Context,
    private val session: CastSession,
    override val deviceName: String,
) : TvSession {
    private val client: RemoteMediaClient
        get() = session.remoteMediaClient ?: throw IOException("O Chromecast desconectou")

    override suspend fun load(
        url: String, title: String, thumbnail: String?, durationMs: Long, sizeBytes: Long, hls: Boolean,
    ) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            thumbnail?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val media = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (hls) "application/x-mpegURL" else "video/mp4")
            .apply { if (hls) setHlsSegmentFormat(HlsSegmentFormat.TS) }
            .setMetadata(metadata)
            .apply { if (durationMs > 0) setStreamDuration(durationMs) }
            .build()
        val request = MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build()
        suspendCancellableCoroutine { cont ->
            client.load(request).setResultCallback { result ->
                if (result.status.isSuccess) cont.resume(Unit)
                else cont.resumeWithException(IOException("O Chromecast não abriu o vídeo (${result.status.statusCode})"))
            }
        }
    }

    override suspend fun play() { client.play() }

    override suspend fun pause() { client.pause() }

    override suspend fun seek(positionMs: Long) {
        client.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    override suspend fun status(): TvStatus? {
        val c = session.remoteMediaClient ?: return null
        if (!session.isConnected) return null
        val state = c.playerState
        val ended = state == MediaStatus.PLAYER_STATE_IDLE && c.idleReason == MediaStatus.IDLE_REASON_FINISHED
        return TvStatus(c.approximateStreamPosition, c.streamDuration, c.isPlaying || c.isBuffering, ended)
    }

    override suspend fun stop() {
        runCatching { session.remoteMediaClient?.stop() }
        CastContext.getSharedInstance(context).sessionManager.endCurrentSession(true)
    }

    companion object {
        /** Conecta ao Chromecast (tem que rodar na thread principal). */
        suspend fun connect(context: Context, device: TvDevice.GoogleCast): GoogleCastTvSession {
            val app = context.applicationContext
            val manager = CastContext.getSharedInstance(app).sessionManager
            val current = manager.currentCastSession
            if (current != null && current.isConnected && current.castDevice?.deviceId ==
                com.google.android.gms.cast.CastDevice.getFromBundle(device.route.extras)?.deviceId
            ) {
                return GoogleCastTvSession(app, current, device.name)
            }
            val session = suspendCancellableCoroutine<CastSession> { cont ->
                val listener = object : SessionManagerListener<CastSession> {
                    fun done(block: () -> Unit) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) block()
                    }
                    override fun onSessionStarted(session: CastSession, sessionId: String) =
                        done { cont.resume(session) }
                    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
                        done { cont.resume(session) }
                    override fun onSessionStartFailed(session: CastSession, error: Int) =
                        done { cont.resumeWithException(IOException("Não conectou ao Chromecast (erro $error)")) }
                    override fun onSessionResumeFailed(session: CastSession, error: Int) =
                        done { cont.resumeWithException(IOException("Não conectou ao Chromecast (erro $error)")) }
                    override fun onSessionStarting(session: CastSession) {}
                    override fun onSessionEnding(session: CastSession) {}
                    override fun onSessionEnded(session: CastSession, error: Int) {}
                    override fun onSessionResuming(session: CastSession, sessionId: String) {}
                    override fun onSessionSuspended(session: CastSession, reason: Int) {}
                }
                manager.addSessionManagerListener(listener, CastSession::class.java)
                cont.invokeOnCancellation { manager.removeSessionManagerListener(listener, CastSession::class.java) }
                MediaRouter.getInstance(app).selectRoute(device.route)
            }
            return GoogleCastTvSession(app, session, device.name)
        }
    }
}

// ===================== DLNA / UPnP (TVs Samsung e outras) =====================

data class DlnaRenderer(val name: String, val controlUrl: String)

object DlnaDevices {
    private const val TAG = "BergaDlna"
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Procura renderizadores na rede por SSDP. Chama [onFound] para cada um novo. */
    suspend fun discover(context: Context, timeoutMs: Int, onFound: (DlnaRenderer) -> Unit) = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("bergapipe-dlna").apply { setReferenceCounted(false); acquire() }
        val seen = mutableSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val message = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SEARCH_TARGET\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextSend) {
                        socket.send(DatagramPacket(message, message.size, group, 1900))
                        nextSend = System.currentTimeMillis() + 1500
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val location = text.lineSequence()
                        .firstOrNull { it.lowercase().startsWith("location:") }
                        ?.substringAfter(':')?.trim() ?: continue
                    if (!seen.add(location)) continue
                    runCatching { describe(location) }
                        .onSuccess { renderer -> withContext(Dispatchers.Main) { onFound(renderer) } }
                        .onFailure { Log.d(TAG, "Descrição de $location falhou", it) }
                }
            }
        } finally {
            lock.release()
        }
    }

    /** Lê a descrição do aparelho: nome e endereço de controle do AVTransport. */
    private fun describe(location: String): DlnaRenderer {
        val response = LanHttp.request("GET", location)
        if (response.code !in 200..299) throw IOException("HTTP ${response.code}")
        val parser = Xml.newPullParser()
        parser.setInput(response.body.reader())
        var name: String? = null
        var urlBase: String? = null
        var serviceType: String? = null
        var controlUrl: String? = null
        var avTransportControl: String? = null
        var text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (parser.name == "service") { serviceType = null; controlUrl = null }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "friendlyName" -> if (name == null) name = text.toString().trim()
                    "URLBase" -> urlBase = text.toString().trim()
                    "serviceType" -> serviceType = text.toString().trim()
                    "controlURL" -> controlUrl = text.toString().trim()
                    "service" -> if (serviceType?.contains("AVTransport") == true && controlUrl != null) {
                        avTransportControl = controlUrl
                    }
                }
            }
        }
        val control = avTransportControl ?: throw IOException("Sem AVTransport")
        val base = URL(urlBase?.takeIf { it.isNotEmpty() } ?: location)
        return DlnaRenderer(name ?: base.host, URL(base, control).toString())
    }
}

class DlnaTvSession(private val renderer: DlnaRenderer) : TvSession {
    override val deviceName: String get() = renderer.name

    override suspend fun load(
        url: String, title: String, thumbnail: String?, durationMs: Long, sizeBytes: Long, hls: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            // Muitas TVs Samsung recusam um vídeo novo sem parar o anterior.
            runCatching { soap("Stop", "") }
            val duration = formatTime(durationMs) + ".000"
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>${escape(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:video/mp4:${MediaServer.DLNA_FEATURES}\" " +
                "size=\"$sizeBytes\" duration=\"$duration\">${escape(url)}</res>" +
                "</item></DIDL-Lite>"
            soap(
                "SetAVTransportURI",
                "<CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(didl)}</CurrentURIMetaData>"
            )
            // Algumas TVs ainda estão "TRANSITIONING" logo depois; tenta de novo.
            var attempts = 0
            while (true) {
                try {
                    soap("Play", "<Speed>1</Speed>")
                    break
                } catch (e: IOException) {
                    if (++attempts >= 5) throw e
                    Thread.sleep(800)
                }
            }
        }

    override suspend fun play() = withContext(Dispatchers.IO) { soap("Play", "<Speed>1</Speed>"); Unit }

    override suspend fun pause() = withContext(Dispatchers.IO) { soap("Pause", ""); Unit }

    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) {
        soap("Seek", "<Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>")
        Unit
    }

    override suspend fun status(): TvStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val position = soap("GetPositionInfo", "")
            val transport = soap("GetTransportInfo", "")
            val state = tag(transport, "CurrentTransportState").orEmpty()
            TvStatus(
                positionMs = parseTime(tag(position, "RelTime")),
                durationMs = parseTime(tag(position, "TrackDuration")),
                playing = state == "PLAYING" || state == "TRANSITIONING",
                ended = state == "STOPPED" || state == "NO_MEDIA_PRESENT",
            )
        }.getOrNull()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) { runCatching { soap("Stop", "") }; Unit }

    private fun soap(action: String, arguments: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$SERVICE\"><InstanceID>0</InstanceID>$arguments</u:$action>" +
            "</s:Body></s:Envelope>"
        val response = LanHttp.request(
            "POST", renderer.controlUrl,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPACTION" to "\"$SERVICE#$action\"",
            ),
            body,
        )
        if (response.code !in 200..299) {
            val detail = tag(response.body, "errorDescription") ?: tag(response.body, "errorCode") ?: ""
            throw IOException("A TV recusou $action (HTTP ${response.code} $detail)".trim())
        }
        return response.body
    }

    companion object {
        private const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")

        private fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name[^>]*>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)

        fun formatTime(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        }

        fun parseTime(value: String?): Long {
            val parts = value?.substringBefore('.')?.split(':') ?: return 0
            if (parts.size != 3) return 0
            val (h, m, s) = parts.map { it.toLongOrNull() ?: return 0 }
            return ((h * 60 + m) * 60 + s) * 1000
        }
    }
}
