package io.github.agelinena.bergapipe.backup

import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Backblaze B2 e qualquer armazenamento compatível com S3 (MinIO, Wasabi...), com URL no
 * formato path-style (https://endpoint/bucket/chave) e assinatura AWS Signature V4.
 */
class S3Target(
    endpoint: String,
    region: String,
    private val bucket: String,
    prefix: String,
    private val keyId: String,
    private val secret: String,
) : BackupTarget {
    private val host = endpoint.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    private val region = region.trim().ifEmpty { regionFromEndpoint(host) }
    private val prefix = prefix.trim().trimStart('/').let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
    private val http get() = BackupTargets.http

    override fun test() {
        list()
    }

    override fun upload(name: String, file: File) {
        val key = prefix + name
        val request = signed("PUT", key, emptyMap(), sha256Hex(file))
            .put(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(request).execute().use { BackupTargets.check(it, "Enviar o backup") }
    }

    override fun list(): List<RemoteBackup> {
        val query = mapOf("list-type" to "2", "prefix" to prefix + BackupArchive.PREFIX)
        val request = signed("GET", "", query, EMPTY_SHA256).get().build()
        val result = mutableListOf<RemoteBackup>()
        http.newCall(request).execute().use { response ->
            BackupTargets.check(response, "Listar o bucket")
            val parser = Xml.newPullParser()
            parser.setInput(response.body.byteStream(), "UTF-8")
            var key: String? = null
            var size = 0L
            var modified = 0L
            var text = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> text = StringBuilder()
                    XmlPullParser.TEXT -> text.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "Key" -> key = text.toString()
                        "Size" -> size = text.toString().trim().toLongOrNull() ?: 0
                        "LastModified" -> modified = BackupTargets.parseIsoDate(text.toString().trim())
                        "Contents" -> {
                            val name = key?.substringAfterLast('/')
                            if (key != null && name != null && BackupArchive.isBackupName(name)) {
                                result += RemoteBackup(name, size, modified, key)
                            }
                            key = null
                        }
                    }
                }
            }
        }
        return result
    }

    override fun download(backup: RemoteBackup, to: File) {
        val request = signed("GET", backup.id, emptyMap(), EMPTY_SHA256).get().build()
        http.newCall(request).execute().use { response ->
            BackupTargets.check(response, "Baixar o backup")
            BackupTargets.save(response.body.byteStream(), to)
        }
    }

    override fun delete(backup: RemoteBackup) {
        val request = signed("DELETE", backup.id, emptyMap(), EMPTY_SHA256).delete().build()
        http.newCall(request).execute().use { BackupTargets.check(it, "Apagar backup antigo") }
    }

    /** Monta a requisição para /bucket/key já com os cabeçalhos da assinatura V4. */
    private fun signed(method: String, key: String, query: Map<String, String>, payloadHash: String): Request.Builder {
        if (region.isEmpty()) throw IOException("Informe a região do S3")
        val now = Date()
        val amzDate = format("yyyyMMdd'T'HHmmss'Z'", now)
        val date = format("yyyyMMdd", now)

        val path = "/" + uriEncode(bucket, false) + "/" + uriEncode(key, true)
        val canonicalQuery = query.toSortedMap().entries.joinToString("&") {
            uriEncode(it.key, false) + "=" + uriEncode(it.value, false)
        }
        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = listOf(
            method, path, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash
        ).joinToString("\n")

        val scope = "$date/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n" +
            hex(sha256(canonicalRequest.toByteArray()))
        var signingKey = hmac("AWS4$secret".toByteArray(), date)
        signingKey = hmac(signingKey, region)
        signingKey = hmac(signingKey, "s3")
        signingKey = hmac(signingKey, "aws4_request")
        val signature = hex(hmac(signingKey, stringToSign))

        val url = "https://$host$path" + if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"
        return Request.Builder().url(url)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=$keyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
            )
    }

    companion object {
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /** s3.us-west-004.backblazeb2.com -> us-west-004; s3.eu-central-1.amazonaws.com -> eu-central-1. */
        fun regionFromEndpoint(host: String): String {
            val parts = host.split('.')
            return if (parts.size >= 3 && parts[0] == "s3") parts[1] else ""
        }

        private fun format(pattern: String, date: Date) =
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

        /** Codificação de URI do S3: só A-Z a-z 0-9 - _ . ~ ficam como estão. */
        private fun uriEncode(value: String, keepSlash: Boolean): String {
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xff).toChar()
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' ||
                        c == '.' || c == '~' -> out.append(c)
                    c == '/' && keepSlash -> out.append(c)
                    else -> out.append('%').append("%02X".format(byte.toInt() and 0xff))
                }
            }
            return out.toString()
        }

        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

        private fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return hex(digest.digest())
        }

        private fun hmac(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
