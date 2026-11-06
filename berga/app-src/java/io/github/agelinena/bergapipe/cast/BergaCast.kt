package io.github.agelinena.bergapipe.cast

import android.content.Context
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.mediarouter.media.MediaRouter
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.ExtractorHelper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ações de transmissão chamadas pelos ganchos nos arquivos do PipePipe. */
object BergaCast {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Botão "TV" da tela do vídeo: usa os dados já carregados. */
    fun castCurrent(fragment: Fragment, info: StreamInfo) {
        val context = fragment.context ?: return
        pickDevice(context) { device ->
            CastManager.start(context, device, info.name) { info }
            openControls(context)
        }
    }

    /** Entrada "Transmitir na TV" do menu de segurar o vídeo: busca os dados na hora. */
    @JvmStatic
    fun castItem(fragment: Fragment, item: StreamInfoItem) {
        val context = fragment.context ?: return
        pickDevice(context) { device ->
            CastManager.start(context, device, item.name) { loadInfo(item) }
            openControls(context)
        }
    }

    private suspend fun loadInfo(item: StreamInfoItem): StreamInfo = suspendCancellableCoroutine { cont ->
        val disposable = ExtractorHelper.getStreamInfo(item.serviceId, item.url, false)
            .subscribeOn(Schedulers.io())
            .subscribe({ cont.resume(it) }, { cont.resumeWithException(it) })
        cont.invokeOnCancellation { disposable.dispose() }
    }

    private fun openControls(context: Context) {
        context.startActivity(
            Intent(context, CastControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Lista as TVs da rede enquanto procura: Chromecasts pelo Cast SDK e TVs DLNA (Samsung e
     * outras) por SSDP, ao mesmo tempo.
     */
    fun pickDevice(context: Context, onPicked: (TvDevice) -> Unit) {
        val devices = mutableListOf<TvDevice>()
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, mutableListOf())
        fun add(device: TvDevice) {
            if (devices.any { it.id == device.id }) return
            devices += device
            val kind = context.getString(
                if (device is TvDevice.GoogleCast) R.string.berga_cast_kind_chromecast else R.string.berga_cast_kind_dlna
            )
            adapter.add("${device.name}  ($kind)")
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.berga_cast_searching)
            .setAdapter(adapter) { _, which -> onPicked(devices[which]) }
            .setNegativeButton(R.string.berga_cancel, null)
            .create()

        val castAvailable = GoogleCastDevices.init(context)
        val router = if (castAvailable) MediaRouter.getInstance(context) else null
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) =
                GoogleCastDevices.currentRoutes(context).forEach { add(it) }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) =
                GoogleCastDevices.currentRoutes(context).forEach { add(it) }
        }
        router?.addCallback(GoogleCastDevices.selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        if (castAvailable) GoogleCastDevices.currentRoutes(context).forEach { add(it) }

        val search = scope.launch {
            repeat(3) {
                runCatching { DlnaDevices.discover(context, 4_000) { add(TvDevice.Dlna(it)) } }
            }
            delay(500)
            if (dialog.isShowing) {
                dialog.setTitle(
                    if (devices.isEmpty()) R.string.berga_cast_none_found else R.string.berga_cast_pick
                )
            }
        }
        dialog.setOnDismissListener {
            router?.removeCallback(callback)
            search.cancel()
        }
        dialog.show()
        if (!castAvailable) {
            Toast.makeText(context, R.string.berga_cast_no_play_services, Toast.LENGTH_LONG).show()
        }
    }
}
