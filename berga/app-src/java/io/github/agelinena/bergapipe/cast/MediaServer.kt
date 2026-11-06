package io.github.agelinena.bergapipe.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo que entrega o MP4 preparado para a TV, com suporte a Range (para a TV
 * poder avançar e voltar) e os cabeçalhos DLNA que as TVs Samsung pedem. O endereço tem um
 * token aleatório, então só quem recebeu o link (a TV) acha o arquivo.
 */
class MediaServer(private val file: File, private val mime: String = "video/mp4") {
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private var server: ServerSocket? = null

    val path: String get() = "/$token/media.mp4"

    /** Começa a escutar e devolve o endereço que a TV deve abrir. */
    fun start(context: Context): String {
        val host = localIpv4(context) ?: throw IOException("Conecte o celular ao Wi-Fi da TV")
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        server = socket
        thread(name = "BergaCastServer", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "BergaCastClient", isDaemon = true) { handle(client) }
            }
        }
        return "http://$host:${socket.localPort}$path"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val target = parts.getOrNull(1).orEmpty().substringBefore('?')
                val out = socket.getOutputStream()
                if (target != path || (method != "GET" && method != "HEAD")) {
                    respond(out, "404 Not Found", mapOf("Content-Length" to "0"))
                    return
                }
                serveFile(out, method == "HEAD", headers["range"])
            } catch (e: IOException) {
                // A TV fecha conexões o tempo todo ao avançar o vídeo.
                Log.d(TAG, "Conexão encerrada: ${e.message}")
            }
        }
    }

    private fun serveFile(out: OutputStream, headOnly: Boolean, range: String?) {
        val length = file.length()
        var start = 0L
        var end = length - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty() && to.isNotEmpty()) {
                start = (length - to.toLong()).coerceAtLeast(0)
            } else {
                start = from.toLongOrNull() ?: 0
                if (to.isNotEmpty()) end = minOf(to.toLong(), length - 1)
            }
            if (start >= length || start > end) {
                respond(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
                return
            }
            partial = true
        }
        val count = end - start + 1
        val headers = linkedMapOf(
            "Content-Type" to mime,
            "Content-Length" to count.toString(),
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*",
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to DLNA_FEATURES,
        )
        if (partial) headers["Content-Range"] = "bytes $start-$end/$length"
        respond(out, if (partial) "206 Partial Content" else "200 OK", headers)
        if (headOnly) return

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(256 * 1024)
            var remaining = count
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.flush()
        }
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val text = StringBuilder("HTTP/1.1 $status\r\n")
        headers.forEach { (k, v) -> text.append(k).append(": ").append(v).append("\r\n") }
        text.append("Connection: close\r\n\r\n")
        out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    companion object {
        private const val TAG = "BergaCast"
        const val DLNA_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        /** IPv4 do celular na rede Wi-Fi (ou Ethernet), que a TV consegue alcançar. */
        fun localIpv4(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { network ->
                val caps = cm.getNetworkCapabilities(network)
                val local = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                if (local) {
                    cm.getLinkProperties(network)?.linkAddresses
                        ?.map { it.address }
                        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                        ?.let { return it.hostAddress }
                }
            }
            // Sem rede ativa de Wi-Fi (por exemplo, VPN por cima): procura a interface wlan.
            return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }

        internal fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                if (b == '\n'.code) break
                if (b != '\r'.code) bytes.write(b)
                if (bytes.size() > 16 * 1024) throw IOException("Linha HTTP longa demais")
            }
            return bytes.toString("ISO-8859-1")
        }
    }
}

/**
 * HTTP/1.0 simples por socket, para falar com a TV na rede local. O Android bloqueia http://
 * nas bibliotecas de rede do app (e é bom que continue assim); o socket não passa por essa
 * regra, do mesmo jeito que o RawHttp do PipePipe faz.
 */
internal object LanHttp {
    class Response(val code: Int, val body: String)

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Response {
        val uri = URI(url)
        if (uri.scheme != "http") throw IOException("Só http:// na rede local: $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), 5_000)
            socket.soTimeout = 10_000
            val payload = body?.toByteArray(Charsets.UTF_8)
            val text = StringBuilder("$method $path HTTP/1.0\r\n")
            text.append("Host: ${uri.host}:$port\r\n")
            headers.forEach { (k, v) -> text.append(k).append(": ").append(v).append("\r\n") }
            if (payload != null) text.append("Content-Length: ${payload.size}\r\n")
            text.append("Connection: close\r\n\r\n")
            val out = socket.getOutputStream()
            out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
            if (payload != null) out.write(payload)
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val status = MediaServer.readLine(input) ?: throw IOException("A TV não respondeu")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Resposta estranha da TV: $status")
            var chunked = false
            while (true) {
                val line = MediaServer.readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("transfer-encoding:") && line.lowercase().contains("chunked")) chunked = true
            }
            val raw = input.readBytes()
            val bodyBytes = if (chunked) dechunk(raw) else raw
            return Response(code, String(bodyBytes, Charsets.UTF_8))
        }
    }

    /** Algumas TVs respondem chunked mesmo para HTTP/1.0. */
    private fun dechunk(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < raw.size) {
            val lineEnd = (i until raw.size - 1).firstOrNull { raw[it] == '\r'.code.toByte() && raw[it + 1] == '\n'.code.toByte() } ?: break
            val size = String(raw, i, lineEnd - i, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            if (start + size > raw.size) break
            out.write(raw, start, size)
            i = start + size + 2
        }
        return out.toByteArray()
    }
}
