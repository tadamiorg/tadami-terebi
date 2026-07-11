package com.sf.tadami.terebi.receiver

import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.sf.tadami.terebi.player.PlayerManager
import java.util.concurrent.Callable
import java.util.concurrent.Executor

/** Handles subtitle (EDIT_TRACKS) selection from the phone sender. */
class TadamiMediaCommandCallback(
    private val playerManager: PlayerManager,
    private val uiExecutor: Executor,
) : MediaCommandCallback() {

    override fun onSelectTracksByType(
        senderId: String?,
        type: Int,
        tracks: List<MediaTrack>,
    ): Task<Void> = Tasks.call(
        uiExecutor,
        Callable<Void> {
            if (type == MediaTrack.TYPE_TEXT) {
                playerManager.selectTextTrack(tracks.firstOrNull()?.id)
            }
            null
        },
    )
}
