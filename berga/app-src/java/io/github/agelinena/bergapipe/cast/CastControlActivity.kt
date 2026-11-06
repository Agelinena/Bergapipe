package io.github.agelinena.bergapipe.cast

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.schabi.newpipe.R

/** Janela de controle da transmissão (abre ao escolher a TV e pela notificação). */
class CastControlActivity : ComponentActivity() {
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var seek: SeekBar
    private lateinit var times: TextView
    private lateinit var controls: LinearLayout
    private lateinit var toggle: Button
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastManager.state.collect { render(it) }
            }
        }
    }

    private fun render(state: CastState) {
        when (state) {
            CastState.Idle -> finish()
            is CastState.Preparing -> {
                title.text = state.title
                status.text = getString(R.string.berga_cast_preparing_for, state.deviceName) + "\n" +
                    CastTexts.preparing(this, state)
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = state.progress == null || state.muxing
                progress.progress = ((state.progress ?: 0f) * 100).toInt()
                seek.visibility = View.GONE
                times.visibility = View.GONE
                toggle.visibility = View.GONE
                controls.visibility = View.GONE
            }
            is CastState.Active -> {
                title.text = state.title
                status.text = getString(R.string.berga_cast_on_device, state.deviceName) +
                    if (state.quality.isNotEmpty()) " · ${state.quality}" else ""
                progress.visibility = View.GONE
                seek.visibility = View.VISIBLE
                times.visibility = View.VISIBLE
                toggle.visibility = View.VISIBLE
                controls.visibility = View.VISIBLE
                toggle.setText(if (state.playing) R.string.berga_cast_pause else R.string.berga_cast_play)
                if (!userSeeking) {
                    seek.max = (state.durationMs / 1000).toInt().coerceAtLeast(1)
                    seek.progress = (state.positionMs / 1000).toInt()
                }
                times.text = CastTexts.time(state.positionMs) + " / " + CastTexts.time(state.durationMs)
            }
            is CastState.Failed -> {
                title.text = state.title
                status.text = getString(R.string.berga_cast_failed) + "\n" + state.message
                progress.visibility = View.GONE
                seek.visibility = View.GONE
                times.visibility = View.GONE
                toggle.visibility = View.GONE
                controls.visibility = View.GONE
            }
        }
    }

    private fun buildLayout() {
        val pad = dp(24)
        title = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 3
        }
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(12))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) times.text = CastTexts.time(value * 1000L)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    CastManager.seekTo(bar.progress * 1000L)
                }
            })
        }
        times = TextView(this).apply { gravity = Gravity.END }
        fun button(text: Int, onClick: () -> Unit) =
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                setText(text)
                setOnClickListener { onClick() }
            }
        toggle = button(R.string.berga_cast_pause) { CastManager.togglePause() }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button(R.string.berga_cast_back) { CastManager.seekBy(-15_000) })
            addView(toggle)
            addView(button(R.string.berga_cast_forward) { CastManager.seekBy(30_000) })
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button(R.string.berga_cast_stop) { CastManager.stop(); finish() })
            addView(button(R.string.berga_close) { CastManager.dismissError(); finish() })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(12))
            minimumWidth = dp(320)
            addView(title)
            addView(status)
            addView(progress)
            addView(seek)
            addView(times)
            addView(controls)
            addView(bottom)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
