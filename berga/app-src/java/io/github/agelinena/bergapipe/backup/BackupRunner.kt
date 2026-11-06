package io.github.agelinena.bergapipe.backup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gera, criptografa (se pedido), envia e apaga os backups antigos. */
object BackupRunner {
    /** Quantos backups ficam no destino. */
    const val KEEP = 14
    private const val TAG = "BergaBackup"

    /** Faz um backup agora. Devolve o nome do arquivo enviado. */
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val config = BackupSettings.load(app)
        if (!config.isConfigured) throw IOException("O backup automático não está configurado")
        val target = BackupTargets.create(app, config)

        val dir = File(app.cacheDir, "berga-backup").apply { deleteRecursively(); mkdirs() }
        try {
            val zip = File(dir, "backup.zip")
            BackupArchive.create(app, zip)
            val name = BackupArchive.newName(config.encrypt)
            val upload = if (config.encrypt) {
                File(dir, "backup.zip.enc").also { BackupCrypto.encrypt(zip, it, config.password!!) }
            } else {
                zip
            }
            target.upload(name, upload)
            prune(target)
            BackupSettings.recordSuccess(app, name)
            name
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Apaga os mais antigos, deixando os [KEEP] mais recentes (o nome tem a data). */
    private fun prune(target: BackupTarget) {
        try {
            target.list().sortedByDescending { it.name }.drop(KEEP).forEach { target.delete(it) }
        } catch (e: Exception) {
            // Não falha o backup por causa da limpeza.
            Log.w(TAG, "Não deu para apagar backups antigos", e)
        }
    }

    /**
     * Baixa um backup para um zip pronto para restaurar. Pede [password] se ele estiver
     * criptografado; lança [WrongPasswordException] se a senha não servir.
     */
    suspend fun download(context: Context, backup: RemoteBackup, password: String?): File =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val target = BackupTargets.create(app, BackupSettings.load(app))
            val dir = File(app.cacheDir, "berga-restore").apply { deleteRecursively(); mkdirs() }
            val downloaded = File(dir, "download")
            target.download(backup, downloaded)
            if (!BackupCrypto.isEncrypted(downloaded)) return@withContext downloaded
            if (password.isNullOrEmpty()) throw WrongPasswordException()
            val zip = File(dir, "backup.zip")
            BackupCrypto.decrypt(downloaded, zip, password)
            downloaded.delete()
            zip
        }

    fun errorMessage(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

/** Liga ou desliga o backup diário conforme a configuração. */
object BackupScheduler {
    private const val WORK_NAME = "bergapipe-auto-backup"

    fun apply(context: Context, config: BackupConfig = BackupSettings.load(context)) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!config.enabled || !config.isConfigured) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(
                if (config.needsNetwork) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED
            )
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        BackupRunner.run(applicationContext)
        BackupNotifications.clear(applicationContext)
        Result.success()
    } catch (e: Exception) {
        val message = BackupRunner.errorMessage(e)
        BackupSettings.recordFailure(applicationContext, message)
        if (e is IOException && runAttemptCount < 2) {
            Result.retry()
        } else {
            BackupNotifications.showFailure(applicationContext, message)
            Result.failure()
        }
    }
}

internal object BackupNotifications {
    private const val CHANNEL_ID = "bergapipe_backup"
    private const val NOTIFICATION_ID = 0x42455248

    @SuppressLint("MissingPermission") // conferida abaixo
    fun showFailure(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.berga_backup_channel))
                .build()
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.berga_backup_failed_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()
        )
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
