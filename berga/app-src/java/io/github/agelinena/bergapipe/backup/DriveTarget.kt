package io.github.agelinena.bergapipe.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google Drive, com o escopo drive.file: o app só enxerga os arquivos que ele mesmo criou.
 * Os backups ficam na pasta "Bergapipe" do Drive.
 */
class DriveTarget(private val context: Context, private val config: BackupConfig) : BackupTarget {
    private val http get() = BackupTargets.http
    private var accessToken: String? = null
    private var folderId: String? = config.driveFolderId

    override fun test() {
        folder()
    }

    override fun upload(name: String, file: File) {
        val parent = folder()
        val metadata = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
        // Upload resumível: primeiro os metadados, depois o arquivo no endereço devolvido.
        val start = authorized("$UPLOAD/files?uploadType=resumable")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(metadata.toString().toRequestBody(JSON))
            .build()
        val location = http.newCall(start).execute().use { response ->
            BackupTargets.check(response, "Começar o envio para o Drive")
            response.header("Location") ?: throw IOException("O Drive não devolveu o endereço de envio")
        }
        val put = Request.Builder().url(location)
            .put(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(put).execute().use { BackupTargets.check(it, "Enviar o backup para o Drive") }
    }

    override fun list(): List<RemoteBackup> {
        val parent = folder()
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$parent' in parents and trashed = false")
            .addQueryParameter("fields", "files(id,name,size,modifiedTime)")
            .addQueryParameter("pageSize", "1000")
            .build()
        val json = http.newCall(authorized(url.toString()).get().build()).execute().use { response ->
            BackupTargets.check(response, "Listar a pasta do Drive")
            JSONObject(response.body.string())
        }
        val files = json.optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { files.getJSONObject(it) }
            .filter { BackupArchive.isBackupName(it.getString("name")) }
            .map {
                RemoteBackup(
                    name = it.getString("name"),
                    size = it.optString("size").toLongOrNull() ?: 0,
                    modifiedAt = BackupTargets.parseIsoDate(it.optString("modifiedTime")),
                    id = it.getString("id"),
                )
            }
    }

    override fun download(backup: RemoteBackup, to: File) {
        http.newCall(authorized("$API/files/${backup.id}?alt=media").get().build()).execute().use {
            BackupTargets.check(it, "Baixar o backup do Drive")
            BackupTargets.save(it.body.byteStream(), to)
        }
    }

    override fun delete(backup: RemoteBackup) {
        http.newCall(authorized("$API/files/${backup.id}").delete().build()).execute().use {
            if (it.code != 404) BackupTargets.check(it, "Apagar backup antigo do Drive")
        }
    }

    /** Pasta "Bergapipe" no Drive; cria e guarda o id na primeira vez. */
    private fun folder(): String {
        folderId?.let { id ->
            val ok = http.newCall(authorized("$API/files/$id?fields=id,trashed").get().build()).execute()
                .use { response ->
                    response.isSuccessful && !JSONObject(response.body.string()).optBoolean("trashed")
                }
            if (ok) return id
        }
        val create = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME)
        val id = http.newCall(
            authorized("$API/files?fields=id").post(create.toString().toRequestBody(JSON)).build()
        ).execute().use { response ->
            BackupTargets.check(response, "Criar a pasta no Drive")
            JSONObject(response.body.string()).getString("id")
        }
        folderId = id
        BackupSettings.update(context) { it.copy(driveFolderId = id) }
        return id
    }

    private fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${token()}")

    private fun token(): String {
        accessToken?.let { return it }
        val refresh = config.driveRefreshToken ?: throw IOException("Entre com a conta Google de novo")
        val body = FormBody.Builder()
            .add("client_id", config.driveClientId)
            .apply { config.driveClientSecret?.let { add("client_secret", it) } }
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val json = http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute().use { response ->
            if (response.code == 400 || response.code == 401) {
                throw IOException("O acesso ao Google Drive expirou. Entre com a conta Google de novo.")
            }
            BackupTargets.check(response, "Renovar o acesso ao Google")
            JSONObject(response.body.string())
        }
        return json.getString("access_token").also { accessToken = it }
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val FOLDER_NAME = "Bergapipe"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Login no Google pelo navegador do celular (OAuth com PKCE e redirecionamento para um
 * servidor local em 127.0.0.1, o fluxo de "app para computador" do Google). Não depende do
 * Google Play Services.
 */
object GoogleSignIn {
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val TIMEOUT_MS = 5 * 60 * 1000

    /** Abre o navegador, espera o retorno e devolve o refresh token. Bloqueante. */
    fun signIn(context: Context, clientId: String, clientSecret: String?): String {
        val random = SecureRandom()
        val verifier = base64Url(ByteArray(48).also(random::nextBytes))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = base64Url(ByteArray(16).also(random::nextBytes))

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = TIMEOUT_MS
            val redirect = "http://127.0.0.1:${server.localPort}"
            val url = Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .build()
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            val code = try {
                waitForCode(server, state)
            } catch (e: SocketTimeoutException) {
                throw IOException("O login no Google demorou demais. Tente de novo.")
            }

            val body = FormBody.Builder()
                .add("code", code)
                .add("client_id", clientId)
                .apply { if (!clientSecret.isNullOrEmpty()) add("client_secret", clientSecret) }
                .add("redirect_uri", redirect)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .build()
            val json = BackupTargets.http.newCall(Request.Builder().url(TOKEN_URL).post(body).build())
                .execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        val reason = runCatching { JSONObject(text).optString("error_description") }.getOrNull()
                        throw IOException("O Google recusou o login: ${reason ?: "HTTP ${response.code}"}")
                    }
                    JSONObject(text)
                }
            return json.optString("refresh_token").ifEmpty {
                throw IOException("O Google não devolveu acesso permanente. Tente de novo.")
            }
        }
    }

    /** Atende o navegador quando o Google redireciona para 127.0.0.1 com o código. */
    private fun waitForCode(server: ServerSocket, state: String): String {
        while (true) {
            server.accept().use { socket ->
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                val params = target.substringAfter('?', "").split('&').filter { '=' in it }
                    .associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                            URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                val ok = params["code"] != null && params["state"] == state
                val message = when {
                    ok -> "Pronto! Pode voltar ao Bergapipe."
                    params["error"] != null -> "O login foi cancelado. Volte ao Bergapipe."
                    else -> null
                }
                if (message != null) {
                    val html = "<html><head><meta name=\"viewport\" content=\"width=device-width\">" +
                        "<meta charset=\"utf-8\"></head><body style=\"font-family:sans-serif;" +
                        "text-align:center;padding-top:20vh\"><h2>$message</h2></body></html>"
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                        )
                        write(bytes)
                        flush()
                    }
                } else {
                    // Outra requisição do navegador (favicon etc.).
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
                if (ok) return params["code"]!!
                params["error"]?.let { throw IOException("Login no Google cancelado ($it)") }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("inalcançável")
    }

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
