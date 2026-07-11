package com.sf.tadami.terebi.receiver

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.sf.tadami.terebi.crash.CrashReporter
import com.sf.tadami.terebi.player.ControlSender
import com.sf.tadami.terebi.R

class ReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions.Builder(context)
            .setStatusText("Tadami TV")
            .setCastAppId(context.getString(R.string.cast_receiver_id))
            // Custom channels the receiver pushes to the phone: crash logs + playback control.
            .setCustomNamespaces(listOf(CrashReporter.NAMESPACE, ControlSender.NAMESPACE))
            .build()
}
