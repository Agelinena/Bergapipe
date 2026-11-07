package io.github.agelinena.bergapipe.cast

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.startup.Initializer
import kotlinx.coroutines.launch
import org.schabi.newpipe.R

/**
 * Barra fixa no rodapé das telas do app enquanto uma TV está conectada (ou o vídeo está sendo
 * preparado para ela). Toque abre os controles completos; só o botão de parar encerra a
 * transmissão. Entra em cada Activity do app pelo ActivityLifecycleCallbacks, sem gancho no
 * código do PipePipe.
 */
class CastBarInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(CastBar)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

object CastBar : Application.ActivityLifecycleCallbacks {
    private const val TAG = "bergapipe_cast_bar"

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Só telas normais do app: a janela de controle e as de sistema ficam de fora.
        if (activity !is ComponentActivity || activity is CastControlActivity) return
        if (!activity.javaClass.name.startsWith("org.schabi.newpipe.")) return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastManager.state.collect { state -> render(activity, state) }
            }
        }
    }

    private fun render(activity: Activity, state: CastState) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        var bar = root.findViewWithTag<View>(TAG)
        if (state is CastState.Idle) {
            bar?.visibility = View.GONE
            return
        }
        if (bar == null) {
            bar = build(activity)
            root.addView(bar)
        }
        bar.visibility = View.VISIBLE
        bar.bringToFront()

        val title = bar.findViewWithTag<TextView>("title")
        val status = bar.findViewWithTag<TextView>("status")
        val toggle = bar.findViewWithTag<ImageButton>("toggle")
        when (state) {
            is CastState.Preparing -> {
                title.text = state.title
                status.text = CastTexts.preparing(activity, state)
                toggle.visibility = View.GONE
            }
            is CastState.Active -> {
                title.text = state.title
                status.text = CastTexts.active(activity, state)
                toggle.visibility = View.VISIBLE
                toggle.setImageResource(
                    if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
                toggle.contentDescription = activity.getString(
                    if (state.playing) R.string.berga_cast_pause else R.string.berga_cast_play
                )
            }
            is CastState.Failed -> {
                title.text = activity.getString(R.string.berga_cast_failed)
                status.text = state.message
                toggle.visibility = View.GONE
            }
            CastState.Idle -> Unit
        }
    }

    private fun color(activity: Activity, attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
    }

    private fun build(activity: Activity): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val textColor = color(activity, android.R.attr.textColorPrimary)
        val background = color(activity, android.R.attr.colorBackground)
        val accent = color(activity, R.attr.colorPrimary)

        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.berga_ic_cast)
            setColorFilter(accent)
        }
        val title = TextView(activity).apply {
            tag = "title"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val status = TextView(activity).apply {
            tag = "status"
            setTextColor(textColor)
            alpha = 0.75f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(title)
            addView(status)
        }
        fun iconButton(tagName: String, image: Int, description: Int, onClick: () -> Unit) =
            ImageButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
                tag = tagName
                setImageResource(image)
                setColorFilter(textColor)
                contentDescription = activity.getString(description)
                setOnClickListener { onClick() }
            }
        val toggle = iconButton("toggle", android.R.drawable.ic_media_pause, R.string.berga_cast_pause) {
            CastManager.togglePause()
        }
        val stop = iconButton("stop", android.R.drawable.ic_menu_close_clear_cancel, R.string.berga_cast_stop) {
            CastManager.stop()
        }

        val row = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            this.background = GradientDrawable().apply {
                setColor(background)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), accent)
            }
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener {
                activity.startActivity(Intent(activity, CastControlActivity::class.java))
            }
            addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
            addView(stop)
        }
        row.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { setMargins(dp(8), 0, dp(8), dp(8)) }

        // Fica acima da barra de navegação do sistema.
        ViewCompat.setOnApplyWindowInsetsListener(row) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            (view.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(8) + bottom
            view.requestLayout()
            insets
        }
        return row
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
