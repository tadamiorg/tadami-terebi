package com.sf.tadami.terebi.player

import android.util.Log
import com.google.android.gms.cast.tv.CastReceiverContext
import kotlinx.serialization.json.Json

/**
 * Sends TV → phone control messages over a custom Cast channel
 * (`urn:x-cast:com.sf.tadami.control`): periodic playback progress (so the phone persists watch
 * time) and episode-navigation requests (the phone re-resolves sources and re-loads them).
 */
object ControlSender {

    const val NAMESPACE = "urn:x-cast:com.sf.tadami.control"
    private val json = Json { encodeDefaults = true }

    private fun send(message: TvControlMessage) {
        runCatching {
            val text = json.encodeToString(TvControlMessage.serializer(), message)
            val ctx = CastReceiverContext.getInstance() ?: return
            ctx.senders.orEmpty().forEach { sender ->
                runCatching { ctx.sendMessage(NAMESPACE, sender.senderId, text) }
            }
        }.onFailure { Log.e("ControlSender", "send failed", it) }
    }

    fun progress(positionMs: Long, durationMs: Long, playing: Boolean) =
        send(TvControlMessage("progress", positionMs, durationMs, playing))

    fun save(positionMs: Long, durationMs: Long) =
        send(TvControlMessage("save", positionMs, durationMs))

    fun next(positionMs: Long, durationMs: Long) =
        send(TvControlMessage("next", positionMs, durationMs))

    fun previous(positionMs: Long, durationMs: Long) =
        send(TvControlMessage("previous", positionMs, durationMs))

    fun selectEpisode(episodeId: Long, positionMs: Long, durationMs: Long) =
        send(TvControlMessage("selectEpisode", positionMs, durationMs, episodeId = episodeId))
}
