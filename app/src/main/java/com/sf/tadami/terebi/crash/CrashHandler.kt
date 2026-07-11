package com.sf.tadami.terebi.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

/**
 * Global uncaught-exception handler: persists the crash, best-effort streams it to the phone,
 * then shows [CrashActivity] (in a separate process) so the user can see what happened.
 */
class CrashHandler private constructor(
    private val appContext: Context,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            CrashReporter.writeCrash(appContext, throwable)
            // Best effort while the cast session is still alive; the reliable resend happens on
            // the next launch (PlaybackActivity) once a sender reconnects.
            CrashReporter.sendPending(appContext)

            val intent = Intent(appContext, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_STACKTRACE, throwable.stackTraceToString())
            }
            appContext.startActivity(intent)
        }

        // We fully handle the crash with our own screen; don't chain to the system handler
        // (it would race a system "app stopped" dialog with CrashActivity).
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    companion object {
        fun install(context: Context) {
            if (Thread.getDefaultUncaughtExceptionHandler() is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext))
        }
    }
}
