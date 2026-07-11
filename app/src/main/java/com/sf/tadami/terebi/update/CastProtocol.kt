package com.sf.tadami.terebi.update

import com.sf.tadami.terebi.update.CastProtocol.RECEIVER_VERSION


/**
 * Phone↔TV cast compatibility contract, from the receiver's side. Monotonic protocol version,
 * independent of the marketing versionName, bumped whenever the wire contract this app implements
 * changes. The phone sends the minimum receiver protocol it needs in the load customData; if
 * [RECEIVER_VERSION] is below that, the receiver forces the user to update (see [UpdateController]).
 */
object CastProtocol {
    /** Protocol version this receiver build implements. */
    const val RECEIVER_VERSION = 1

    /** customData key the phone uses to advertise the minimum receiver protocol it requires. */
    const val MIN_RECEIVER_KEY = "minReceiverProtocol"
}
