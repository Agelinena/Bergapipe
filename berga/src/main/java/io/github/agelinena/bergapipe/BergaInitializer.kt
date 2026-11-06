package io.github.agelinena.bergapipe

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import io.github.agelinena.bergapipe.update.BergaUpdater

/**
 * Roda quando o app abre (App Startup, declarado no manifesto do módulo). Assim o Bergapipe
 * não precisa de gancho no App.java do PipePipe.
 */
class BergaInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        BergaUpdater.enqueueCheck(context, manual = false)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(WorkManagerInitializer::class.java)
}
