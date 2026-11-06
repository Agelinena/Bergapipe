package io.github.agelinena.bergapipe.update

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compara a release "latest" do GitHub com a versão instalada e avisa por notificação. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        if (!manual && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) {
            return@withContext Result.success()
        }

        val release = try {
            ReleaseSource.latest()
        } catch (e: Exception) {
            Log.w(TAG, "Não deu para procurar atualização", e)
            if (manual) UpdateNotifications.showCheckFailed(context)
            return@withContext Result.success()
        }
        prefs.edit { putLong(KEY_LAST_CHECK, now) }

        if (release != null && release.versionCode > ReleaseSource.installedVersionCode(context)) {
            UpdateNotifications.showAvailable(context, release)
        } else if (manual) {
            UpdateNotifications.showUpToDate(context)
        }
        Result.success()
    }

    companion object {
        const val KEY_MANUAL = "manual"
        private const val TAG = "BergaUpdate"
        private const val PREFS = "bergapipe_update"
        private const val KEY_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
