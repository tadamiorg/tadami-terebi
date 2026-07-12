package com.sf.tadami.terebi.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Surface
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.SenderDisconnectedEventInfo
import com.google.android.gms.cast.tv.SenderInfo
import com.google.android.gms.cast.tv.media.MediaManager
import com.sf.tadami.terebi.BuildConfig
import com.sf.tadami.terebi.crash.CrashReporter
import com.sf.tadami.terebi.player.ControlSender
import com.sf.tadami.terebi.player.PlayerManager
import com.sf.tadami.terebi.receiver.TadamiMediaCommandCallback
import com.sf.tadami.terebi.receiver.TadamiMediaLoadCallback
import com.sf.tadami.terebi.update.OutdatedSenderDialog
import com.sf.tadami.terebi.update.TvAppUpdater
import com.sf.tadami.terebi.update.UpdateController
import com.sf.tadami.terebi.update.UpdateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
class PlaybackActivity : ComponentActivity() {

    private lateinit var playerManager: PlayerManager
    private lateinit var mediaManager: MediaManager
    private val uiExecutor = Executor { runnable -> runOnUiThread(runnable) }

    /**
     * When the phone sender disconnects (stops casting / closes / drops the connection) the
     * receiver must tear itself down instead of sitting on the last frame. Once the last sender
     * is gone we stop playback and finish the activity, returning the TV to idle.
     */
    private val eventCallback = object : CastReceiverContext.EventCallback() {
        override fun onSenderDisconnected(eventInfo: SenderDisconnectedEventInfo) {
            val remaining = CastReceiverContext.getInstance().senders.orEmpty().size
            if (remaining == 0) {
                runOnUiThread {
                    playerManager.player.stop()
                    playerManager.player.clearMediaItems()
                    finish()
                }
            }
        }

        override fun onSenderConnected(senderInfo: SenderInfo) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerManager = PlayerManager(this)

        mediaManager = CastReceiverContext.getInstance().mediaManager
        mediaManager.setMediaLoadCommandCallback(
            TadamiMediaLoadCallback(
                playerManager = playerManager,
                uiExecutor = uiExecutor,
                onIncompatible = { UpdateController.requireUpdate() },
                onSenderOutdated = { UpdateController.setSenderOutdated(true) },
            ) { loadRequestData ->
                mediaManager.setDataFromLoad(loadRequestData)
                mediaManager.broadcastMediaStatus()
                // A sender is connected now — flush any crash log saved before the last restart.
                CrashReporter.sendPending(this)
            },
        )
        mediaManager.setMediaCommandCallback(TadamiMediaCommandCallback(playerManager, uiExecutor))
        mediaManager.setSessionCompatToken(playerManager.session.sessionToken)

        // Detect sender disconnect so we can shut the receiver down (see eventCallback).
        CastReceiverContext.getInstance().registerEventCallback(eventCallback)

        setContent {
            val themeColors by playerManager.themeColors.collectAsState()
            val updateRequired by UpdateController.required.collectAsState()
            val updateAvailable by UpdateController.available.collectAsState()
            val senderOutdated by UpdateController.senderOutdated.collectAsState()
            TadamiTerebiTheme(colors = themeColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PlaybackScreen(playerManager)
                        when {
                            updateRequired -> UpdateDialog(release = null, forced = true, onDismiss = {})
                            senderOutdated -> OutdatedSenderDialog(onExit = { finish() })
                            updateAvailable != null -> UpdateDialog(
                                release = updateAvailable,
                                forced = false,
                                onDismiss = { UpdateController.dismissAvailable() },
                            )
                        }
                    }
                }
            }
        }

        startMediaStatusTicker()
        checkForOptionalUpdate()

        // Route the launch/load intent into the MediaManager.
        mediaManager.onNewIntent(intent)
    }

    /**
     * Non-blocking launch check: if a newer release exists (and we're still compatible with the
     * connected phone), offer a dismissible update dialog. Runs once per process start.
     */
    private fun checkForOptionalUpdate() {
        lifecycleScope.launch(Dispatchers.Default) {
            val release = TvAppUpdater.checkForUpdate(BuildConfig.VERSION_NAME)
            if (release != null) UpdateController.setAvailable(release)
        }
    }

    /**
     * Keeps the Cast MediaStatus fresh so the phone reads correct current/total time: every
     * second it republishes the session position + duration and broadcasts media status. The
     * sender's ProgressListener reads getStreamDuration()/getApproximateStreamPosition() from it.
     */
    private fun startMediaStatusTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var secondsSinceProgress = 0
                var wasPlaying = false
                var secondsSinceBroadcast = 0
                var lastDurationKnown = false
                while (true) {
                    playerManager.pumpPlaybackState()
                    val duration = playerManager.player.duration
                    val position = playerManager.player.currentPosition.coerceAtLeast(0L)
                    val playing = playerManager.player.isPlaying

                    // broadcastMediaStatus() serializes the full MediaStatus (incl. heavy customData)
                    // to JSON on the main thread — costly. Only do it when it matters: on a play/pause
                    // change, when the duration first becomes known, or as a ~3s keepalive. The phone's
                    // progress bar stays smooth in between via getApproximateStreamPosition().
                    val durationKnown = duration > 0L
                    val shouldBroadcast = playing != wasPlaying ||
                        (durationKnown && !lastDurationKnown) ||
                        secondsSinceBroadcast >= BROADCAST_INTERVAL_S
                    if (shouldBroadcast) {
                        runCatching {
                            if (durationKnown) {
                                mediaManager.mediaStatusModifier.mediaInfoModifier?.setStreamDuration(duration)
                            }
                            mediaManager.broadcastMediaStatus()
                        }
                        secondsSinceBroadcast = 0
                    } else {
                        secondsSinceBroadcast++
                    }
                    lastDurationKnown = durationKnown

                    // Push watch-time to the phone: periodically, and immediately on pause. Skip
                    // while a source/episode switch is loading (stale position would misattribute).
                    val loading = playerManager.snapshot.value.loading
                    if (duration > 0L && !loading) {
                        secondsSinceProgress++
                        if (secondsSinceProgress >= PROGRESS_INTERVAL_S) {
                            ControlSender.progress(position, duration, playing)
                            secondsSinceProgress = 0
                        }
                        if (wasPlaying && !playing) {
                            ControlSender.save(position, duration)
                        }
                    }
                    wasPlaying = playing

                    if (CrashReporter.hasPending(this@PlaybackActivity)) {
                        CrashReporter.sendPending(this@PlaybackActivity)
                    }
                    delay(1_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mediaManager.onNewIntent(intent)
    }

    override fun onStop() {
        // Persist watch time when the TV app is backgrounded.
        val duration = playerManager.player.duration
        if (duration > 0L) {
            ControlSender.save(playerManager.player.currentPosition.coerceAtLeast(0L), duration)
        }
        super.onStop()
    }

    override fun onDestroy() {
        CastReceiverContext.getInstance().unregisterEventCallback(eventCallback)
        playerManager.release()
        super.onDestroy()
    }

    companion object {
        private const val PROGRESS_INTERVAL_S = 5
        /** Keepalive cadence for the (costly) Cast MediaStatus broadcast; state changes broadcast immediately. */
        private const val BROADCAST_INTERVAL_S = 3
    }
}
