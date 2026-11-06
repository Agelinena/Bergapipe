package io.github.agelinena.bergapipe.backup

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Um backup guardado no destino. [id] é o que o destino usa para achar o arquivo. */
data class RemoteBackup(val name: String, val size: Long, val modifiedAt: Long, val id: String = name)

/** Destino do backup. Todos os métodos são bloqueantes: chame fora da thread principal. */
interface BackupTarget {
    /** Confere acesso e cria a pasta, se preciso. Lança exceção com a mensagem do problema. */
    fun test()
    fun upload(name: String, file: File)
    /** Só os arquivos de backup do Bergapipe. */
    fun list(): List<RemoteBackup>
    fun download(backup: RemoteBackup, to: File)
    fun delete(backup: RemoteBackup)
}

object BackupTargets {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    fun create(context: Context, config: BackupConfig): BackupTarget = when (config.destination) {
        BackupDestination.NEXTCLOUD -> WebDavTarget(
            nextcloudUrl(config.nextcloudServer, config.nextcloudUser, config.nextcloudFolder),
            config.nextcloudUser,
            config.nextcloudPassword.orEmpty(),
        )
        BackupDestination.S3 -> S3Target(
            config.s3Endpoint, config.s3Region, config.s3Bucket, config.s3Prefix,
            config.s3KeyId, config.s3Secret.orEmpty(),
        )
        BackupDestination.GOOGLE_DRIVE -> DriveTarget(context, config)
        BackupDestination.LOCAL_FOLDER -> LocalFolderTarget(context, Uri.parse(config.localTreeUri))
        BackupDestination.NONE -> throw IOException("Nenhum destino de backup configurado")
    }

    /**
     * Endereço WebDAV da pasta. Aceita o endereço do Nextcloud (https://nuvem.exemplo.com) ou
     * um endereço WebDAV completo (com /remote.php/ ou de outro servidor, terminado na pasta).
     */
    fun nextcloudUrl(server: String, user: String, folder: String): String {
        val base = server.trim().trimEnd('/').let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        if (base.contains("/remote.php/") || base.contains("/dav")) return "$base/"
        val path = folder.trim('/').split('/').filter { it.isNotBlank() }
            .joinToString("/") { encodeSegment(it) }
        return "$base/remote.php/dav/files/${encodeSegment(user.trim())}/" +
            (if (path.isEmpty()) "" else "$path/")
    }

    fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    internal fun check(response: Response, action: String) {
        if (!response.isSuccessful) {
            val hint = when (response.code) {
                401, 403 -> " (usuário, senha ou chave sem permissão)"
                404 -> " (endereço ou pasta não encontrados)"
                else -> ""
            }
            throw IOException("$action: HTTP ${response.code}$hint")
        }
    }

    internal fun save(input: InputStream, to: File) {
        FileOutputStream(to).use { input.copyTo(it) }
    }

    internal fun parseIsoDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(value.take(19))!!.time
        }.getOrDefault(0)
    }
}

/** Nextcloud e qualquer servidor WebDAV. */
class WebDavTarget(baseUrl: String, private val user: String, private val password: String) : BackupTarget {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val http get() = BackupTargets.http

    private fun request(url: String) =
        Request.Builder().url(url).header("Authorization", Credentials.basic(user, password))

    override fun test() {
        // MKCOL cria a pasta; 405 quer dizer que ela já existe.
        http.newCall(request(base).method("MKCOL", null).build()).execute().use { response ->
            if (response.code == 409) throw IOException("A pasta de cima não existe no servidor")
            if (response.code != 405) BackupTargets.check(response, "Criar a pasta")
        }
        propfind(depth = 0).close()
    }

    override fun upload(name: String, file: File) {
        test()
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        http.newCall(request(base + BackupTargets.encodeSegment(name)).put(body).build()).execute()
            .use { BackupTargets.check(it, "Enviar o backup") }
    }

    override fun list(): List<RemoteBackup> {
        val result = mutableListOf<RemoteBackup>()
        propfind(depth = 1).use { response ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(response.body.byteStream(), "UTF-8")
            var href: String? = null
            var size = 0L
            var modified = 0L
            var text = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        text = StringBuilder()
                        if (parser.name == "response") {
                            href = null; size = 0; modified = 0
                        }
                    }
                    XmlPullParser.TEXT -> text.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "href" -> href = text.toString().trim()
                        "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0
                        "getlastmodified" -> modified = runCatching {
                            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                                .parse(text.toString().trim())!!.time
                        }.getOrDefault(0)
                        "response" -> {
                            val name = href?.trimEnd('/')?.substringAfterLast('/')
                                ?.let { URLDecoder.decode(it, "UTF-8") }
                            if (name != null && BackupArchive.isBackupName(name)) {
                                result += RemoteBackup(name, size, modified)
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun propfind(depth: Int): Response {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
            "<d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
        val response = http.newCall(
            request(base).header("Depth", depth.toString())
                .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
                .build()
        ).execute()
        try {
            BackupTargets.check(response, "Listar a pasta")
        } catch (e: IOException) {
            response.close()
            throw e
        }
        return response
    }

    override fun download(backup: RemoteBackup, to: File) {
        http.newCall(request(base + BackupTargets.encodeSegment(backup.name)).get().build()).execute()
            .use { response ->
                BackupTargets.check(response, "Baixar o backup")
                BackupTargets.save(response.body.byteStream(), to)
            }
    }

    override fun delete(backup: RemoteBackup) {
        http.newCall(request(base + BackupTargets.encodeSegment(backup.name)).delete().build()).execute()
            .use { if (it.code != 404) BackupTargets.check(it, "Apagar backup antigo") }
    }
}

/** Pasta escolhida no celular (pode ser um app de nuvem que aparece no seletor de arquivos). */
class LocalFolderTarget(private val context: Context, private val treeUri: Uri) : BackupTarget {
    private fun folder(): DocumentFile {
        val folder = DocumentFile.fromTreeUri(context, treeUri)
        if (folder == null || !folder.canWrite()) {
            throw IOException("Sem acesso à pasta escolhida. Escolha a pasta de novo.")
        }
        return folder
    }

    override fun test() {
        folder()
    }

    override fun upload(name: String, file: File) {
        val target = folder().createFile("application/octet-stream", name)
            ?: throw IOException("Não deu para criar o arquivo na pasta")
        context.contentResolver.openOutputStream(target.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Não deu para gravar na pasta")
    }

    override fun list(): List<RemoteBackup> = folder().listFiles()
        .filter { it.isFile && it.name != null && BackupArchive.isBackupName(it.name!!) }
        .map { RemoteBackup(it.name!!, it.length(), it.lastModified(), it.uri.toString()) }

    override fun download(backup: RemoteBackup, to: File) {
        context.contentResolver.openInputStream(Uri.parse(backup.id))?.use {
            BackupTargets.save(it, to)
        } ?: throw IOException("Não deu para ler o backup")
    }

    override fun delete(backup: RemoteBackup) {
        DocumentFile.fromSingleUri(context, Uri.parse(backup.id))?.delete()
    }
}
