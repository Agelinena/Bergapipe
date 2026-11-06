package io.github.agelinena.bergapipe

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import io.github.agelinena.bergapipe.cast.BergaCast
import io.github.agelinena.bergapipe.folders.BergaFolders
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Botões do Bergapipe na fileira de ações da tela do vídeo ("Pasta" e "TV"), criados ao lado
 * do botão de adicionar à playlist, sem mexer no layout do PipePipe.
 */
object BergaDetail {
    private const val TAG_FOLDER = "bergapipe_folder_button"
    private const val TAG_CAST = "bergapipe_cast_button"

    /** Gancho único em VideoDetailFragment. */
    @JvmStatic
    fun attach(fragment: Fragment, anchor: View, currentInfo: () -> StreamInfo?) {
        val folder = addButton(anchor, anchor, TAG_FOLDER, R.string.berga_folder_button,
            R.string.berga_folder_save_to, R.drawable.berga_ic_folder_add) {
            val info = currentInfo() ?: return@addButton
            fragment.context?.let { BergaFolders.showSaveDialog(it, StreamEntity(info)) }
        } ?: return
        addButton(anchor, folder, TAG_CAST, R.string.berga_cast_button,
            R.string.berga_cast_action, R.drawable.berga_ic_cast) {
            val info = currentInfo() ?: return@addButton
            BergaCast.castCurrent(fragment, info)
        }
    }

    /** Copia o visual de [style] e põe o botão logo depois de [after]. */
    private fun addButton(
        style: View, after: View, tag: String, text: Int, description: Int, icon: Int,
        onClick: () -> Unit,
    ): View? {
        val parent = after.parent as? ViewGroup ?: return null
        parent.findViewWithTag<View>(tag)?.let { return it }
        val context = style.context
        val source = style as? TextView
        val button = TextView(context).apply {
            this.tag = tag
            setText(text)
            contentDescription = context.getString(description)
            gravity = source?.gravity ?: Gravity.CENTER
            if (source != null) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, source.textSize)
                setTextColor(source.textColors)
                compoundDrawablePadding = source.compoundDrawablePadding
            }
            setPadding(style.paddingLeft, style.paddingTop, style.paddingRight, style.paddingBottom)
            setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
            background = style.background?.constantState?.newDrawable()?.mutate()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val params = when (val lp = style.layoutParams) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            else -> ViewGroup.LayoutParams(lp)
        }
        parent.addView(button, parent.indexOfChild(after) + 1, params)
        return button
    }
}
