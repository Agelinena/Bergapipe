package io.github.agelinena.bergapipe.backup

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import io.github.agelinena.bergapipe.folders.FoldersDatabase
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.settings.ContentSettingsManager
import org.schabi.newpipe.settings.NewPipeFileLocator
import org.schabi.newpipe.util.ZipHelper
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * O arquivo de backup é o mesmo zip do "Exportar banco de dados" do PipePipe (newpipe.db e
 * newpipe.settings) mais o banco das pastas. Sem criptografia, ele ainda pode ser importado no
 * PipePipe original (que só ignora as pastas).
 */
object BackupArchive {
    const val PREFIX = "bergapipe-backup-"
    private const val FOLDERS_ENTRY = "berga_pastas.db"
    private const val TAG = "BergaBackup"

    fun newName(encrypted: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return PREFIX + stamp + if (encrypted) ".zip.enc" else ".zip"
    }

    fun isBackupName(name: String) =
        name.startsWith(PREFIX) && (name.endsWith(".zip") || name.endsWith(".zip.enc"))

    fun isEncryptedName(name: String) = name.endsWith(".enc")

    /** Gera o zip em [out]. Bloqueante: chame fora da thread principal. */
    @Throws(Exception::class)
    fun create(context: Context, out: File) {
        val app = context.applicationContext
        try {
            NewPipeDatabase.getInstance(app)
            NewPipeDatabase.checkpoint()
        } catch (e: RuntimeException) {
            // "database is busy": o WAL vai junto do jeito que está; o SQLite se recupera.
            Log.w(TAG, "Checkpoint do banco do PipePipe falhou", e)
        }
        runCatching { FoldersDatabase.get(app).checkpoint() }
            .onFailure { Log.w(TAG, "Checkpoint do banco das pastas falhou", it) }

        val locator = locator(app)
        val settingsFile = File(app.cacheDir, "berga-backup.settings")
        ObjectOutputStream(FileOutputStream(settingsFile)).use { output ->
            output.writeObject(HashMap(PreferenceManager.getDefaultSharedPreferences(app).all))
        }
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
                ZipHelper.addFileToZip(zip, locator.db.path, "newpipe.db")
                ZipHelper.addFileToZip(zip, settingsFile.path, "newpipe.settings")
                val folders = app.getDatabasePath(FoldersDatabase.FILE_NAME)
                if (folders.exists()) ZipHelper.addFileToZip(zip, folders.path, FOLDERS_ENTRY)
            }
        } finally {
            settingsFile.delete()
        }
    }

    /**
     * Troca os dados do app pelos do backup (banco, configurações e pastas). Depois disso o
     * app precisa reiniciar (NavigationHelper.restartApp).
     */
    @Throws(IOException::class)
    fun restore(context: Context, zipFile: File) {
        val app = context.applicationContext
        val locator = locator(app)
        ZipFile(zipFile).use { zip ->
            val db = zip.getEntry("newpipe.db")
                ?: throw IOException("O arquivo não é um backup do Bergapipe nem do PipePipe")

            NewPipeDatabase.close()
            if (!locator.dbDir.exists() && !locator.dbDir.mkdirs()) {
                throw IOException("Não deu para criar a pasta do banco")
            }
            zip.getInputStream(db).use { input ->
                FileOutputStream(locator.db).use { input.copyTo(it) }
            }
            locator.dbJournal.delete()
            locator.dbWal.delete()
            locator.dbShm.delete()

            zip.getEntry("newpipe.settings")?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(locator.settings).use { input.copyTo(it) }
                }
                ContentSettingsManager(locator)
                    .loadSharedPreferences(PreferenceManager.getDefaultSharedPreferences(app))
                locator.settings.delete()
            }

            zip.getEntry(FOLDERS_ENTRY)?.let { entry ->
                FoldersDatabase.closeForRestore()
                val target = app.getDatabasePath(FoldersDatabase.FILE_NAME)
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { input.copyTo(it) }
                }
                File(target.path + "-wal").delete()
                File(target.path + "-shm").delete()
                File(target.path + "-journal").delete()
            }
        }
    }

    private fun locator(context: Context) =
        NewPipeFileLocator(ContextCompat.getDataDir(context) ?: context.filesDir.parentFile!!)
}

class WrongPasswordException : IOException("Senha do backup errada")

/**
 * Criptografia do backup: AES-256-GCM com chave derivada da senha (PBKDF2).
 *
 * Formato: "BGPENC1\n" (8 bytes), id do KDF (1 byte), iterações (int), sal (16 bytes),
 * IV (12 bytes) e o texto cifrado com a tag GCM no fim.
 */
object BackupCrypto {
    private val MAGIC = "BGPENC1\n".toByteArray(Charsets.US_ASCII)
    private const val KDF_SHA256: Byte = 1
    private const val KDF_SHA1: Byte = 2
    private const val ITERATIONS = 210_000

    fun isEncrypted(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(MAGIC.size)
            input.read(head) == MAGIC.size && head.contentEquals(MAGIC)
        }
    } catch (e: IOException) {
        false
    }

    fun encrypt(input: File, output: File, password: String) {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        // PBKDF2 com SHA-256 só existe a partir do Android 8.
        val kdf = if (Build.VERSION.SDK_INT >= 26) KDF_SHA256 else KDF_SHA1
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, kdf, ITERATIONS), GCMParameterSpec(128, iv))

        DataOutputStream(BufferedOutputStream(FileOutputStream(output))).use { out ->
            out.write(MAGIC)
            out.writeByte(kdf.toInt())
            out.writeInt(ITERATIONS)
            out.write(salt)
            out.write(iv)
            FileInputStream(input).use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    cipher.update(buffer, 0, read)?.let { out.write(it) }
                }
            }
            out.write(cipher.doFinal())
        }
    }

    @Throws(IOException::class)
    fun decrypt(input: File, output: File, password: String) {
        DataInputStream(FileInputStream(input).buffered()).use { source ->
            val magic = ByteArray(MAGIC.size)
            source.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("O arquivo não é um backup criptografado do Bergapipe")
            val kdf = source.readByte()
            val iterations = source.readInt()
            val salt = ByteArray(16).also { source.readFully(it) }
            val iv = ByteArray(12).also { source.readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, kdf, iterations), GCMParameterSpec(128, iv))
            FileOutputStream(output).buffered().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    cipher.update(buffer, 0, read)?.let { out.write(it) }
                }
                try {
                    out.write(cipher.doFinal())
                } catch (e: AEADBadTagException) {
                    throw WrongPasswordException()
                }
            }
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, kdf: Byte, iterations: Int): SecretKeySpec {
        val algorithm = when (kdf) {
            KDF_SHA256 -> "PBKDF2WithHmacSHA256"
            KDF_SHA1 -> "PBKDF2WithHmacSHA1"
            else -> throw IOException("Formato de backup desconhecido")
        }
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
