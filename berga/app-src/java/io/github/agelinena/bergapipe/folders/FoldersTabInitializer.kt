package io.github.agelinena.bergapipe.folders

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.startup.Initializer
import org.schabi.newpipe.settings.tabs.BergaFoldersTab
import org.schabi.newpipe.settings.tabs.TabsManager

/**
 * Põe a aba "Pastas" na página principal uma vez só, na primeira abertura com pastas.
 * Se depois você tirar a aba em Ajustes, ela não volta sozinha.
 * Declarado no manifesto do módulo berga (App Startup).
 */
class FoldersTabInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ADDED, false)) return
        try {
            val manager = TabsManager.getManager(context)
            val tabs = manager.tabs.toMutableList()
            if (tabs.none { it.tabId == BergaFoldersTab.ID }) {
                tabs.add(BergaFoldersTab())
                manager.saveTabs(tabs)
            }
            prefs.edit { putBoolean(KEY_ADDED, true) }
        } catch (e: Exception) {
            Log.w(TAG, "Não deu para adicionar a aba Pastas", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private companion object {
        const val TAG = "BergaFolders"
        const val PREFS = "bergapipe_folders"
        const val KEY_ADDED = "tab_added"
    }
}
