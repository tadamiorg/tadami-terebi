package com.sf.tadami.terebi

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.security.ProviderInstaller
import com.sf.tadami.terebi.crash.CrashHandler
import com.sf.tadami.terebi.update.CastProtocol
import com.sf.tadami.terebi.update.UpdateController
import org.json.JSONObject

class TerebiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        updateSecurityProvider()
        CrashHandler.install(this)
        CastReceiverContext.initInstance(this)
        registerCompatibilityHandshake()
        // start()/stop() the receiver with the app foreground state (recommended by the Cast docs).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                CastReceiverContext.getInstance().start()
            }

            override fun onStop(owner: LifecycleOwner) {
                CastReceiverContext.getInstance().stop()
            }
        })
    }

    /**
     * Listen for the phone's connect-time protocol handshake. Registered here (app start, before the
     * phone finishes connecting) so an incompatible receiver is flagged as soon as the session opens —
     * independent of whether any media is ever loaded. Setting [UpdateController.requireUpdate] flips
     * the update dialog into its non-skippable "required" mode; PlaybackActivity observes it.
     */
    private fun registerCompatibilityHandshake() {
        runCatching {
            CastReceiverContext.getInstance().setMessageReceivedListener(
                CastProtocol.HANDSHAKE_NAMESPACE,
            ) { _, _, message ->
                val json = runCatching { JSONObject(message) }.getOrNull()
                val minReceiver = json?.optInt(CastProtocol.MIN_RECEIVER_KEY, 1) ?: 1
                if (CastProtocol.RECEIVER_VERSION < minReceiver) {
                    UpdateController.requireUpdate()
                }
                // Reverse gate: flag when the phone is older than we require (recomputed each
                // handshake so a compatible reconnect clears the block).
                val senderProtocol = json?.optInt(CastProtocol.SENDER_KEY, 1) ?: 1
                UpdateController.setSenderOutdated(senderProtocol < CastProtocol.MIN_SENDER_VERSION)
            }
        }.onFailure { Log.e("TerebiApp", "handshake listener registration failed", it) }
    }

    private fun updateSecurityProvider() {
        ProviderInstaller.installIfNeededAsync(this, object : ProviderInstaller.ProviderInstallListener {
            override fun onProviderInstalled() {
                Log.i("TerebiApp", "Security provider installed successfully.")
            }

            override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: android.content.Intent?) {
                Log.e("TerebiApp", "Security provider installation failed with error code: $errorCode")
            }
        })
    }
}
