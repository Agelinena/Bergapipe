package io.github.agelinena.bergapipe.folders

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Ações das pastas chamadas pelos ganchos nos arquivos do PipePipe. */
object BergaFolders {
    /** Gravações sobrevivem ao fechamento do diálogo e da tela que o abriu. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Entrada "Salvar na pasta" do menu de segurar o vídeo. */
    @JvmStatic
    fun showSaveDialog(fragment: Fragment, item: StreamInfoItem) {
        val context = fragment.context ?: return
        showSaveDialog(context, StreamEntity(item))
    }

    /**
     * Lista as pastas com caixas de marcar: marcadas são as que já têm o vídeo. Salvar grava
     * a diferença. Sem pastas, vai direto para criar a primeira.
     */
    fun showSaveDialog(context: Context, video: StreamEntity) {
        val dao = FoldersDatabase.get(context).dao()
        scope.launch {
            val folders = dao.folders()
            if (folders.isEmpty()) {
                askFolderName(context, R.string.berga_folder_new, "") { name ->
                    scope.launch {
                        val id = dao.insertFolder(FolderEntity(name = name))
                        dao.addVideo(FolderVideoEntity.from(id, video))
                        toast(context, context.getString(R.string.berga_folder_saved_in, name))
                    }
                }
                return@launch
            }

            val inside = dao.foldersContaining(video.serviceId, video.url).toSet()
            val checked = BooleanArray(folders.size) { folders[it].id in inside }
            val names = folders.map { it.name }.toTypedArray()

            AlertDialog.Builder(context)
                .setTitle(R.string.berga_folder_save_to)
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.berga_save) { _, _ ->
                    scope.launch {
                        var added = 0
                        folders.forEachIndexed { i, folder ->
                            val wasInside = folder.id in inside
                            if (checked[i] && !wasInside) {
                                dao.addVideo(FolderVideoEntity.from(folder.id, video))
                                added++
                            } else if (!checked[i] && wasInside) {
                                dao.removeVideo(folder.id, video.serviceId, video.url)
                            }
                        }
                        val total = checked.count { it }
                        toast(
                            context,
                            when {
                                total == 0 -> context.getString(R.string.berga_folder_removed_all)
                                total == 1 -> context.getString(
                                    R.string.berga_folder_saved_in,
                                    folders[checked.indexOfFirst { it }].name
                                )
                                else -> context.resources.getQuantityString(
                                    R.plurals.berga_folder_saved_in_many, total, total
                                )
                            }
                        )
                    }
                }
                .setNeutralButton(R.string.berga_folder_new) { _, _ ->
                    askFolderName(context, R.string.berga_folder_new, "") { name ->
                        scope.launch {
                            val id = dao.insertFolder(FolderEntity(name = name))
                            dao.addVideo(FolderVideoEntity.from(id, video))
                            toast(context, context.getString(R.string.berga_folder_saved_in, name))
                        }
                    }
                }
                .setNegativeButton(R.string.berga_cancel, null)
                .show()
        }
    }

    /** Pede o nome de uma pasta. Ignora nome vazio. */
    fun askFolderName(context: Context, title: Int, initial: String, onName: (String) -> Unit) {
        val input = EditText(context).apply {
            setSingleLine()
            setText(initial)
            setSelection(initial.length)
            setHint(R.string.berga_folder_name_hint)
        }
        val padding = dp(context, 20)
        val container = FrameLayout(context).apply {
            setPadding(padding, dp(context, 8), padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.berga_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.berga_cancel, null)
            .create()
        dialog.setOnShowListener { input.requestFocus() }
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.show()
    }

    private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
