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
    const val RECEIVER_VERSION = 2

    /** customData key the phone uses to advertise the minimum receiver protocol it requires. */
    const val MIN_RECEIVER_KEY = "minReceiverProtocol"

    /**
     * Oldest sender (phone) protocol this receiver can work with. The phone advertises its own
     * [SENDER_KEY] protocol version; if it is below this, the phone is too old and the receiver
     * blocks with a "update your phone app" dialog (see [UpdateController.senderOutdated]). Bumped,
     * like [RECEIVER_VERSION], only when the wire contract changes in a way older phones can't honor.
     */
    const val MIN_SENDER_VERSION = 1

    /** customData / handshake key the phone advertises its own protocol version on. */
    const val SENDER_KEY = "senderProtocol"

    /** Custom cast namespace the phone pushes its protocol versions on at connect time. */
    const val HANDSHAKE_NAMESPACE = "urn:x-cast:com.sf.tadami.handshake"
}
