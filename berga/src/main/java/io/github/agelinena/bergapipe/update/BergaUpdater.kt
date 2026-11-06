package io.github.agelinena.bergapipe.update

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Atualizações do Bergapipe. Substitui o verificador do PipePipe (NewVersionWorker), que
 * apontaria para as releases do criador.
 *
 * As releases vêm de https://github.com/Agelinena/Bergapipe/releases. O workflow só publica
 * versões feitas sobre uma stable do PipePipe, e o app só lê a release "latest", que no GitHub
 * nunca é uma pre-release.
 */
object BergaUpdater {
    private const val WORK_NAME = "bergapipe-update-check"

    /**
     * Procura uma versão nova em segundo plano.
     *
     * @param manual pedido do usuário (botão nas configurações): ignora o intervalo mínimo
     * entre checagens e avisa também quando não há nada novo ou quando a checagem falha.
     */
    @JvmStatic
    fun enqueueCheck(context: Context, manual: Boolean) {
        val app = context.applicationContext
        // Build de debug tem outro ID; a release instalaria um segundo app.
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!manual && debuggable) return

        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setInputData(workDataOf(UpdateCheckWorker.KEY_MANUAL to manual))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            WORK_NAME,
            if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }
}
