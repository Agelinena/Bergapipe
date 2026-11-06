package io.github.agelinena.bergapipe.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.agelinena.bergapipe.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Janela que baixa o APK da release e instala pelo PackageInstaller.
 *
 * O Android mostra a confirmação "Atualizar este app?" a menos que o Bergapipe já seja o
 * instalador registrado de si mesmo (Android 12+), o que vale a partir da primeira atualização
 * feita por esta tela.
 */
class UpdateActivity : ComponentActivity() {
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var button: Button

    private var job: Job? = null
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        buildLayout()
        if (!handleInstallStatus(intent)) start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!handleInstallStatus(intent)) {
            setIntent(intent)
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        // Voltou das configurações depois de liberar "instalar apps desta fonte".
        if (waitingForPermission && canInstall()) start()
    }

    private fun start() {
        val url = intent.getStringExtra(EXTRA_URL)
        val versionName = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        if (url == null) {
            finish()
            return
        }
        if (!canInstall()) {
            waitingForPermission = true
            progress.visibility = View.GONE
            show(getString(R.string.berga_update_need_permission), R.string.berga_update_open_settings) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        waitingForPermission = false
        if (job?.isActive == true) return

        job = lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            show(getString(R.string.berga_update_downloading, versionName), R.string.berga_cancel) {
                job?.cancel()
                finish()
            }
            try {
                val apk = withContext(Dispatchers.IO) { download(url) }
                message.text = getString(R.string.berga_update_installing)
                progress.isIndeterminate = true
                withContext(Dispatchers.IO) { install(apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message)
            }
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private suspend fun download(url: String): File {
        val target = File(cacheDir, "bergapipe-update.apk")
        val request = Request.Builder().url(url).build()
        ReleaseSource.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                progress.post {
                                    progress.isIndeterminate = false
                                    progress.progress = percent
                                }
                            }
                        }
                    }
                }
            }
        }
        return target
    }

    private fun install(apk: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("bergapipe.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(this, UpdateActivity::class.java).setAction(ACTION_STATUS)
            // Mutável: o instalador do sistema preenche o resultado nos extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getActivity(this, sessionId, status, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Resposta do PackageInstaller. Devolve false se o intent não for uma resposta. */
    private fun handleInstallStatus(intent: Intent): Boolean {
        if (intent.action != ACTION_STATUS) return false
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java
                )
                if (confirm != null) startActivity(confirm) else showError(null)
            }
            // Em caso de sucesso o sistema encerra o app para trocar a versão.
            PackageInstaller.STATUS_SUCCESS,
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish()
            else -> showError(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
        return true
    }

    private fun showError(detail: String?) {
        progress.visibility = View.GONE
        val text = if (detail.isNullOrBlank()) {
            getString(R.string.berga_update_failed_unknown)
        } else {
            getString(R.string.berga_update_failed, detail)
        }
        show(text, R.string.berga_close) { finish() }
    }

    private fun show(text: String, buttonText: Int, onClick: () -> Unit) {
        message.text = text
        button.setText(buttonText)
        button.setOnClickListener { onClick() }
    }

    private fun buildLayout() {
        val pad = dp(24)
        val title = TextView(this).apply {
            setText(R.string.berga_update_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        message = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(16), 0, dp(16))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        button = Button(this, null, android.R.attr.borderlessButtonStyle)
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.END }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(12))
            minimumWidth = dp(300)
            addView(title)
            addView(message)
            addView(progress)
            addView(button, buttonParams)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ACTION_STATUS = "io.github.agelinena.bergapipe.UPDATE_STATUS"
        private const val EXTRA_URL = "apk_url"
        private const val EXTRA_VERSION = "version_name"

        fun intent(context: Context, release: Release): Intent =
            Intent(context, UpdateActivity::class.java)
                .putExtra(EXTRA_URL, release.apkUrl)
                .putExtra(EXTRA_VERSION, release.versionName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
