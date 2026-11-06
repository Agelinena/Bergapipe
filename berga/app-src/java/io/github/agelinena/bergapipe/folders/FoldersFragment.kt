package io.github.agelinena.bergapipe.folders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.util.PicassoHelper

/** Aba "Pastas": lista as pastas, cria, renomeia e apaga. */
class FoldersFragment : BaseFragment() {
    private val adapter = FolderAdapter(
        onClick = { openFolder(it) },
        onLongClick = { showFolderOptions(it) },
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.berga_fragment_folders, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.berga_folders_list)
        val empty = view.findViewById<TextView>(R.id.berga_folders_empty)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        view.findViewById<FloatingActionButton>(R.id.berga_folders_add).setOnClickListener {
            createFolder()
        }

        val dao = FoldersDatabase.get(requireContext()).dao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.summaries().collect { folders ->
                    adapter.submitList(folders)
                    empty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun openFolder(folder: FolderSummary) {
        getFM().beginTransaction()
            .setCustomAnimations(
                R.animator.custom_fade_in, R.animator.custom_fade_out,
                R.animator.custom_fade_in, R.animator.custom_fade_out
            )
            .replace(R.id.fragment_holder, FolderFragment.newInstance(folder.id, folder.name))
            .addToBackStack(null)
            .commit()
    }

    private fun createFolder() {
        val context = requireContext()
        BergaFolders.askFolderName(context, R.string.berga_folder_new, "") { name ->
            lifecycleScope.launch {
                FoldersDatabase.get(context).dao().insertFolder(FolderEntity(name = name))
            }
        }
    }

    private fun showFolderOptions(folder: FolderSummary) {
        val context = requireContext()
        val dao = FoldersDatabase.get(context).dao()
        AlertDialog.Builder(context)
            .setTitle(folder.name)
            .setItems(
                arrayOf(
                    getString(R.string.berga_folder_rename),
                    getString(R.string.berga_folder_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> BergaFolders.askFolderName(
                        context, R.string.berga_folder_rename, folder.name
                    ) { name ->
                        lifecycleScope.launch { dao.renameFolder(folder.id, name) }
                    }
                    1 -> confirmDelete(folder)
                }
            }
            .show()
    }

    private fun confirmDelete(folder: FolderSummary) {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.berga_folder_delete_title, folder.name))
            .setMessage(R.string.berga_folder_delete_message)
            .setPositiveButton(R.string.berga_folder_delete) { _, _ ->
                lifecycleScope.launch {
                    FoldersDatabase.get(context).dao().deleteFolder(folder.id)
                }
            }
            .setNegativeButton(R.string.berga_cancel, null)
            .show()
    }
}

private class FolderAdapter(
    private val onClick: (FolderSummary) -> Unit,
    private val onLongClick: (FolderSummary) -> Unit,
) : ListAdapter<FolderSummary, FolderAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.berga_folder_thumbnail)
        val name: TextView = view.findViewById(R.id.berga_folder_name)
        val count: TextView = view.findViewById(R.id.berga_folder_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.berga_item_folder, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val folder = getItem(position)
        val context = holder.itemView.context
        holder.name.text = folder.name
        holder.count.text = context.resources.getQuantityString(
            R.plurals.berga_folder_video_count, folder.count, folder.count
        )
        if (folder.thumbnailUrl != null) {
            PicassoHelper.loadThumbnail(folder.thumbnailUrl).into(holder.thumbnail)
        } else {
            holder.thumbnail.setImageResource(R.drawable.berga_ic_folder)
        }
        holder.itemView.setOnClickListener { onClick(folder) }
        holder.itemView.setOnLongClickListener {
            onLongClick(folder)
            true
        }
    }

    private object Diff : DiffUtil.ItemCallback<FolderSummary>() {
        override fun areItemsTheSame(a: FolderSummary, b: FolderSummary) = a.id == b.id
        override fun areContentsTheSame(a: FolderSummary, b: FolderSummary) = a == b
    }
}
