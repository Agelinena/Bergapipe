package io.github.agelinena.bergapipe.cast

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.schabi.newpipe.R

/**
 * Mantém o app vivo enquanto a TV puxa o vídeo do celular (tela apagada inclusive) e mostra a
 * notificação com os controles da transmissão.
 */
class CastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tem que virar serviço em primeiro plano logo, antes de qualquer outra coisa.
        startInForeground(build(CastManager.state.value))
        when (intent?.action) {
            ACTION_TOGGLE -> CastManager.togglePause()
            ACTION_STOP -> CastManager.stop()
            ACTION_BACK -> CastManager.seekBy(-15_000)
            ACTION_FORWARD -> CastManager.seekBy(30_000)
        }
        acquireLocks()
        if (observer == null) {
            observer = scope.launch {
                CastManager.state.collect { state ->
                    if (state is CastState.Idle) {
                        ServiceCompat.stopForeground(this@CastService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notifySafely(build(state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bergapipe:cast")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bergapipe:cast")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifySafely(notification: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun build(state: CastState): Notification {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.berga_cast_channel))
                .build()
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CastControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.berga_ic_cast)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        when (state) {
            is CastState.Preparing -> builder
                .setContentTitle(state.title)
                .setContentText(CastTexts.preparing(this, state))
                .setProgress(100, ((state.progress ?: 0f) * 100).toInt(), state.progress == null || state.muxing)
                .addAction(0, getString(R.string.berga_cast_stop), action(ACTION_STOP))
            is CastState.Active -> builder
                .setContentTitle(state.title)
                .setContentText(CastTexts.active(this, state))
                .addAction(0, getString(R.string.berga_cast_back), action(ACTION_BACK))
                .addAction(
                    0, getString(if (state.playing) R.string.berga_cast_pause else R.string.berga_cast_play),
                    action(ACTION_TOGGLE)
                )
                .addAction(0, getString(R.string.berga_cast_forward), action(ACTION_FORWARD))
                .addAction(0, getString(R.string.berga_cast_stop), action(ACTION_STOP))
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            is CastState.Failed -> builder
                .setContentTitle(getString(R.string.berga_cast_failed))
                .setContentText(state.message)
                .setOngoing(false)
                .addAction(0, getString(R.string.berga_close), action(ACTION_STOP))
            CastState.Idle -> builder.setContentTitle(getString(R.string.berga_cast_channel))
        }
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(), Intent(this, CastService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL_ID = "bergapipe_cast"
        private const val NOTIFICATION_ID = 0x42455249
        private const val ACTION_TOGGLE = "io.github.agelinena.bergapipe.cast.TOGGLE"
        private const val ACTION_STOP = "io.github.agelinena.bergapipe.cast.STOP"
        private const val ACTION_BACK = "io.github.agelinena.bergapipe.cast.BACK"
        private const val ACTION_FORWARD = "io.github.agelinena.bergapipe.cast.FORWARD"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CastService::class.java))
        }
    }
}

internal object CastTexts {
    fun time(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
    }

    /** "Na TV: Sala · 1:23 / 10:00 · baixado 45%" */
    fun active(context: Context, state: CastState.Active): String {
        val text = context.getString(R.string.berga_cast_on_device, state.deviceName) + " · " +
            time(state.positionMs) + " / " + time(state.durationMs)
        val downloaded = state.downloaded ?: return text
        return text + " · " + context.getString(R.string.berga_cast_downloaded, (downloaded * 100).toInt())
    }

    fun preparing(context: Context, state: CastState.Preparing): String = when {
        state.muxing -> context.getString(R.string.berga_cast_muxing)
        state.progress == null -> context.getString(R.string.berga_cast_loading)
        else -> context.getString(
            R.string.berga_cast_downloading, (state.progress * 100).toInt(), state.quality
        )
    }
}
