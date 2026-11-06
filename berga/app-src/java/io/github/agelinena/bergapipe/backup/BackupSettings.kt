package io.github.agelinena.bergapipe.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class BackupDestination { NONE, NEXTCLOUD, S3, GOOGLE_DRIVE, LOCAL_FOLDER }

/**
 * Configuração do backup automático. Fica num SharedPreferences próprio, fora das
 * configurações do PipePipe, para as senhas nunca irem parar dentro do próprio backup.
 * Senhas e chaves são cifradas com uma chave do Android Keystore.
 */
data class BackupConfig(
    val enabled: Boolean = false,
    val destination: BackupDestination = BackupDestination.NONE,
    val encrypt: Boolean = false,
    val password: String? = null,
    // Nextcloud / WebDAV
    val nextcloudServer: String = "",
    val nextcloudUser: String = "",
    val nextcloudPassword: String? = null,
    val nextcloudFolder: String = "Bergapipe",
    // S3 / Backblaze B2
    val s3Endpoint: String = "",
    val s3Region: String = "",
    val s3Bucket: String = "",
    val s3Prefix: String = "bergapipe/",
    val s3KeyId: String = "",
    val s3Secret: String? = null,
    // Google Drive
    val driveClientId: String = "",
    val driveClientSecret: String? = null,
    val driveRefreshToken: String? = null,
    val driveFolderId: String? = null,
    // Pasta no celular (Storage Access Framework)
    val localTreeUri: String? = null,
) {
    /** Destino em rede: o backup diário espera o Wi-Fi. */
    val needsNetwork: Boolean get() = destination != BackupDestination.LOCAL_FOLDER

    val isConfigured: Boolean
        get() = when (destination) {
            BackupDestination.NONE -> false
            BackupDestination.NEXTCLOUD ->
                nextcloudServer.isNotBlank() && nextcloudUser.isNotBlank() && !nextcloudPassword.isNullOrEmpty()
            BackupDestination.S3 -> s3Endpoint.isNotBlank() && s3Bucket.isNotBlank() &&
                s3KeyId.isNotBlank() && !s3Secret.isNullOrEmpty()
            BackupDestination.GOOGLE_DRIVE -> driveClientId.isNotBlank() && !driveRefreshToken.isNullOrEmpty()
            BackupDestination.LOCAL_FOLDER -> !localTreeUri.isNullOrEmpty()
        } && (!encrypt || !password.isNullOrEmpty())
}

/** Resultado do último backup, para mostrar nos ajustes. */
data class BackupStatus(val lastSuccessAt: Long, val lastName: String?, val lastError: String?, val lastErrorAt: Long)

object BackupSettings {
    private const val PREFS = "bergapipe_backup"

    fun load(context: Context): BackupConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun secret(key: String) = p.getString(key, null)?.let { SecretStore.decrypt(it) }
        return BackupConfig(
            enabled = p.getBoolean("enabled", false),
            destination = runCatching {
                BackupDestination.valueOf(p.getString("destination", null) ?: "NONE")
            }.getOrDefault(BackupDestination.NONE),
            encrypt = p.getBoolean("encrypt", false),
            password = secret("password"),
            nextcloudServer = p.getString("nextcloud_server", "")!!,
            nextcloudUser = p.getString("nextcloud_user", "")!!,
            nextcloudPassword = secret("nextcloud_password"),
            nextcloudFolder = p.getString("nextcloud_folder", "Bergapipe")!!,
            s3Endpoint = p.getString("s3_endpoint", "")!!,
            s3Region = p.getString("s3_region", "")!!,
            s3Bucket = p.getString("s3_bucket", "")!!,
            s3Prefix = p.getString("s3_prefix", "bergapipe/")!!,
            s3KeyId = p.getString("s3_key_id", "")!!,
            s3Secret = secret("s3_secret"),
            driveClientId = p.getString("drive_client_id", "")!!,
            driveClientSecret = secret("drive_client_secret"),
            driveRefreshToken = secret("drive_refresh_token"),
            driveFolderId = p.getString("drive_folder_id", null),
            localTreeUri = p.getString("local_tree_uri", null),
        )
    }

    fun save(context: Context, c: BackupConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            fun secret(key: String, value: String?) {
                if (value.isNullOrEmpty()) remove(key) else putString(key, SecretStore.encrypt(value))
            }
            putBoolean("enabled", c.enabled)
            putString("destination", c.destination.name)
            putBoolean("encrypt", c.encrypt)
            secret("password", c.password)
            putString("nextcloud_server", c.nextcloudServer)
            putString("nextcloud_user", c.nextcloudUser)
            secret("nextcloud_password", c.nextcloudPassword)
            putString("nextcloud_folder", c.nextcloudFolder)
            putString("s3_endpoint", c.s3Endpoint)
            putString("s3_region", c.s3Region)
            putString("s3_bucket", c.s3Bucket)
            putString("s3_prefix", c.s3Prefix)
            putString("s3_key_id", c.s3KeyId)
            secret("s3_secret", c.s3Secret)
            putString("drive_client_id", c.driveClientId)
            secret("drive_client_secret", c.driveClientSecret)
            secret("drive_refresh_token", c.driveRefreshToken)
            putString("drive_folder_id", c.driveFolderId)
            putString("local_tree_uri", c.localTreeUri)
        }
    }

    fun update(context: Context, change: (BackupConfig) -> BackupConfig): BackupConfig =
        change(load(context)).also { save(context, it) }

    fun status(context: Context): BackupStatus {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BackupStatus(
            lastSuccessAt = p.getLong("last_success_at", 0),
            lastName = p.getString("last_name", null),
            lastError = p.getString("last_error", null),
            lastErrorAt = p.getLong("last_error_at", 0),
        )
    }

    fun recordSuccess(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong("last_success_at", System.currentTimeMillis())
            putString("last_name", name)
            remove("last_error")
            remove("last_error_at")
        }
    }

    fun recordFailure(context: Context, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("last_error", error)
            putLong("last_error_at", System.currentTimeMillis())
        }
    }
}

/** Cifra segredos com uma chave AES que nunca sai do Android Keystore. */
internal object SecretStore {
    private const val ALIAS = "bergapipe_backup_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /** null se não der para decifrar (chave apagada, por exemplo depois de restaurar o celular). */
    fun decrypt(encoded: String): String? = try {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
