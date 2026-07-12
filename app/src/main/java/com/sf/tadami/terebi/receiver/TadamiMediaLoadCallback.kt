package com.sf.tadami.terebi.receiver

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.sf.tadami.terebi.player.PlayerManager
import com.sf.tadami.terebi.update.CastProtocol
import java.util.concurrent.Callable
import java.util.concurrent.Executor

/** Receives the phone sender's load requests and starts ExoPlayer playback. */
@OptIn(UnstableApi::class)
class TadamiMediaLoadCallback(
    private val playerManager: PlayerManager,
    private val uiExecutor: Executor,
    private val onIncompatible: () -> Unit,
    private val onSenderOutdated: () -> Unit,
    private val onLoaded: (MediaLoadRequestData) -> Unit,
) : MediaLoadCommandCallback() {

    override fun onLoad(
        senderId: String?,
        loadRequestData: MediaLoadRequestData,
    ): Task<MediaLoadRequestData> = Tasks.call(
        uiExecutor,
        Callable {
            val info = requireNotNull(loadRequestData.mediaInfo) { "missing mediaInfo" }
            // Compatibility gate: if the phone requires a newer receiver protocol than we implement,
            // refuse to play and show the forced-update dialog instead.
            val minReceiver = info.customData?.optInt(CastProtocol.MIN_RECEIVER_KEY, 1) ?: 1
            if (CastProtocol.RECEIVER_VERSION < minReceiver) {
                onIncompatible()
                return@Callable loadRequestData
            }
            // Reverse gate: if the phone is older than this receiver requires, refuse to play and
            // show the "update your phone app" dialog instead.
            val senderProtocol = info.customData?.optInt(CastProtocol.SENDER_KEY, 1) ?: 1
            if (senderProtocol < CastProtocol.MIN_SENDER_VERSION) {
                onSenderOutdated()
                return@Callable loadRequestData
            }
            val startMs = loadRequestData.currentTime.coerceAtLeast(0L)
            val autoplay = loadRequestData.autoplay ?: true
            playerManager.load(info, startMs, autoplay)
            loadRequestData.activeTrackIds?.firstOrNull()?.let { playerManager.selectTextTrack(it) }
            onLoaded(loadRequestData)
            loadRequestData
        },
    )
}
