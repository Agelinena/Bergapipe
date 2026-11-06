package io.github.agelinena.bergapipe.folders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.InfoListAdapter
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.OnClickGesture

/** Vídeos de uma pasta, com a mesma lista do resto do app. */
class FolderFragment : BaseFragment() {
    private var folderId = 0L
    private var folderName = ""
    private var videos: List<FolderVideoEntity> = emptyList()
    private lateinit var adapter: InfoListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderId = requireArguments().getLong(ARG_ID)
        folderName = requireArguments().getString(ARG_NAME).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.berga_fragment_folder, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.berga_folder_list)
        val empty = view.findViewById<TextView>(R.id.berga_folder_empty)

        adapter = InfoListAdapter(requireContext())
        adapter.setOnStreamSelectedListener(object : OnClickGesture<StreamInfoItem>() {
            override fun selected(selectedItem: StreamInfoItem) = open(selectedItem)
            override fun held(selectedItem: StreamInfoItem) = showVideoOptions(selectedItem)
        })
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val dao = FoldersDatabase.get(requireContext()).dao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dao.folder(folderId).collect { folder ->
                        if (folder == null) {
                            // A pasta foi apagada.
                            parentFragmentManager.popBackStack()
                        } else if (folder.name != folderName) {
                            folderName = folder.name
                            setTitle(folderName)
                        }
                    }
                }
                dao.videos(folderId).collect { items ->
                    videos = items
                    adapter.setInfoItemList(items.map { it.toStreamInfoItem() })
                    empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    requireActivity().invalidateMenu()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setTitle(folderName)
    }

    private fun open(item: StreamInfoItem) {
        NavigationHelper.openVideoDetailFragment(
            requireContext(), getFM(), item.serviceId, item.url, item.name, null, false
        )
    }

    private fun hostActivity() = requireActivity() as AppCompatActivity

    private fun queue(startAt: Int = 0) =
        SinglePlayQueue(videos.map { it.toStreamInfoItem() }, startAt)

    private fun showVideoOptions(item: StreamInfoItem) {
        val context = requireContext()
        val dao = FoldersDatabase.get(context).dao()
        val index = videos.indexOfFirst { it.serviceId == item.serviceId && it.url == item.url }
        AlertDialog.Builder(context)
            .setTitle(item.name)
            .setItems(
                arrayOf(
                    getString(R.string.berga_folder_play_from_here),
                    getString(R.string.berga_folder_background_from_here),
                    getString(R.string.berga_folder_other_folders),
                    getString(R.string.berga_folder_remove_video),
                )
            ) { _, which ->
                when (which) {
                    0 -> if (index >= 0) NavigationHelper.playOnMainPlayer(hostActivity(), queue(index))
                    1 -> if (index >= 0) {
                        NavigationHelper.playOnBackgroundPlayer(context, queue(index), false)
                    }
                    2 -> BergaFolders.showSaveDialog(context, StreamEntity(item))
                    3 -> lifecycleScope.launch {
                        dao.removeVideo(folderId, item.serviceId, item.url)
                    }
                }
            }
            .show()
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.berga_menu_folder, menu)
        }

        override fun onPrepareMenu(menu: Menu) {
            val hasVideos = videos.isNotEmpty()
            menu.findItem(R.id.berga_menu_play_all)?.isVisible = hasVideos
            menu.findItem(R.id.berga_menu_background_all)?.isVisible = hasVideos
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            val context = requireContext()
            val dao = FoldersDatabase.get(context).dao()
            when (menuItem.itemId) {
                R.id.berga_menu_play_all -> NavigationHelper.playOnMainPlayer(hostActivity(), queue())
                R.id.berga_menu_background_all ->
                    NavigationHelper.playOnBackgroundPlayer(context, queue(), false)
                R.id.berga_menu_rename -> BergaFolders.askFolderName(
                    context, R.string.berga_folder_rename, folderName
                ) { name -> lifecycleScope.launch { dao.renameFolder(folderId, name) } }
                R.id.berga_menu_delete -> AlertDialog.Builder(context)
                    .setTitle(getString(R.string.berga_folder_delete_title, folderName))
                    .setMessage(R.string.berga_folder_delete_message)
                    .setPositiveButton(R.string.berga_folder_delete) { _, _ ->
                        lifecycleScope.launch { dao.deleteFolder(folderId) }
                    }
                    .setNegativeButton(R.string.berga_cancel, null)
                    .show()
                else -> return false
            }
            return true
        }
    }

    companion object {
        private const val ARG_ID = "folder_id"
        private const val ARG_NAME = "folder_name"

        fun newInstance(id: Long, name: String) = FolderFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_ID, id)
                putString(ARG_NAME, name)
            }
        }
    }
}
