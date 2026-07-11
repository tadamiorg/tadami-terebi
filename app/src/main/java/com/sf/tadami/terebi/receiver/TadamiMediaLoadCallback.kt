package com.sf.tadami.terebi.receiver

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.sf.tadami.terebi.player.PlayerManager
import java.util.concurrent.Callable
import java.util.concurrent.Executor

/** Receives the phone sender's load requests and starts ExoPlayer playback. */
@OptIn(UnstableApi::class)
class TadamiMediaLoadCallback(
    private val playerManager: PlayerManager,
    private val uiExecutor: Executor,
    private val onLoaded: (MediaLoadRequestData) -> Unit,
) : MediaLoadCommandCallback() {

    override fun onLoad(
        senderId: String?,
        loadRequestData: MediaLoadRequestData,
    ): Task<MediaLoadRequestData> = Tasks.call(
        uiExecutor,
        Callable {
            val info = requireNotNull(loadRequestData.mediaInfo) { "missing mediaInfo" }
            val startMs = loadRequestData.currentTime.coerceAtLeast(0L)
            val autoplay = loadRequestData.autoplay ?: true
            playerManager.load(info, startMs, autoplay)
            loadRequestData.activeTrackIds?.firstOrNull()?.let { playerManager.selectTextTrack(it) }
            onLoaded(loadRequestData)
            loadRequestData
        },
    )
}
