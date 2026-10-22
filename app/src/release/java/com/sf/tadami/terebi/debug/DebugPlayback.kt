package com.sf.tadami.terebi.debug

import android.content.Intent
import com.sf.tadami.terebi.player.PlayerManager

/** Release builds: no debug playback entry point (see the debug source set). */
object DebugPlayback {
    @Suppress("UNUSED_PARAMETER")
    fun handle(intent: Intent?, playerManager: PlayerManager) = Unit
}
