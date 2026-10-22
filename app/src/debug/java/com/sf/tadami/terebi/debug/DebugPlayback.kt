package com.sf.tadami.terebi.debug

import android.content.Intent
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.sf.tadami.terebi.player.PlayerManager
import com.sf.tadami.terebi.player.TvStreamSource
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Debug builds only: plays a stream without a Cast sender, as if the phone had loaded it.
 *
 * ```
 * adb shell am start -n com.sf.tadami.terebi.debug/com.sf.tadami.terebi.ui.PlaybackActivity \
 *     -a com.sf.tadami.terebi.debug.PLAY --es url <stream url> [--es title <title>] [--es referer <url>]
 * ```
 */
object DebugPlayback {
    private const val ACTION = "com.sf.tadami.terebi.debug.PLAY"

    fun handle(intent: Intent?, playerManager: PlayerManager) {
        if (intent?.action != ACTION) return
        val url = intent.getStringExtra("url") ?: return
        val title = intent.getStringExtra("title") ?: "Debug stream"
        val headers = intent.getStringExtra("referer")?.let { mapOf("Referer" to it) }
        val source = TvStreamSource(url = url, fullName = title, server = "Debug", headers = headers)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        val info = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .setCustomData(JSONObject().put("selectedSource", Json.encodeToString(TvStreamSource.serializer(), source)))
            .build()
        Log.d("DebugPlayback", "Playing $url")
        playerManager.load(info, startMs = intent.getLongExtra("startMs", 0L), autoplay = true)
    }
}
