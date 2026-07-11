package com.sf.tadami.terebi.crash

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.tv.CastReceiverContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists uncaught-exception logs and streams them to the phone sender over a custom Cast
 * channel (`urn:x-cast:com.sf.tadami.crash`). Because sending while the process is dying is
 * unreliable, the log is kept on disk and re-sent on the next launch once a sender connects.
 */
object CrashReporter {

    const val NAMESPACE = "urn:x-cast:com.sf.tadami.crash"
    private const val TAG = "CrashReporter"
    private const val MAX_LEN = 24_000

    private val json = Json { encodeDefaults = true }

    @Serializable
    data class CrashLog(
        val stacktrace: String,
        val packageName: String,
        val versionName: String,
        val timestamp: Long,
    )

    private fun crashFile(context: Context): File =
        File(context.filesDir, "crash/last_crash.txt").apply { parentFile?.mkdirs() }

    /** Writes the throwable to disk as a JSON CrashLog. */
    fun writeCrash(context: Context, throwable: Throwable) {
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
            val log = CrashLog(
                stacktrace = sw.toString().take(MAX_LEN),
                packageName = context.packageName,
                versionName = versionName,
                timestamp = System.currentTimeMillis(),
            )
            crashFile(context).writeText(json.encodeToString(CrashLog.serializer(), log))
        }.onFailure { Log.e(TAG, "writeCrash failed", it) }
    }

    fun hasPending(context: Context): Boolean =
        runCatching { crashFile(context).let { it.exists() && it.length() > 0 } }.getOrDefault(false)

    /** Reads the raw stacktrace text for on-screen display (best effort). */
    fun readPendingStacktrace(context: Context): String? = runCatching {
        val raw = crashFile(context).takeIf { it.exists() }?.readText() ?: return null
        json.decodeFromString(CrashLog.serializer(), raw).stacktrace
    }.getOrNull()

    /** Sends a pending crash log to every connected sender; deletes it once delivered. */
    fun sendPending(context: Context) {
        if (!hasPending(context)) return
        runCatching {
            val message = crashFile(context).readText()
            val senders = CastReceiverContext.getInstance().senders.orEmpty()
            if (senders.isEmpty()) return
            var delivered = false
            for (sender in senders) {
                val id = sender.senderId
                runCatching {
                    CastReceiverContext.getInstance().sendMessage(NAMESPACE, id, message)
                    delivered = true
                }.onFailure { Log.e(TAG, "sendMessage failed for $id", it) }
            }
            if (delivered) crashFile(context).delete()
        }.onFailure { Log.e(TAG, "sendPending failed", it) }
    }
}
