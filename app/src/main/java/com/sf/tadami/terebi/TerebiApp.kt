package com.sf.tadami.terebi

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.security.ProviderInstaller
import com.sf.tadami.terebi.crash.CrashHandler

class TerebiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        updateSecurityProvider()
        CrashHandler.install(this)
        CastReceiverContext.initInstance(this)
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
