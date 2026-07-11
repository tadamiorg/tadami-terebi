package com.sf.tadami.terebi.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the update APK (reporting progress for the TV dialog's progress bar) and installs it via
 * the [PackageInstaller] session API. On Android TV a plain `ACTION_VIEW` of an APK often doesn't
 * resolve to an installer UI, so we drive the session directly and surface the system confirm prompt
 * from the install status receiver — mirroring the phone's TadamiPackageInstaller.
 */
object ApkInstaller {

    private const val INSTALL_ACTION = "com.sf.tadami.terebi.update.INSTALL_ACTION"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Streams [url] to `externalCacheDir/update.apk`, invoking [onProgress] with a 0f..1f fraction
     * as bytes arrive (invoked on the IO dispatcher). Throws on any HTTP/IO failure.
     */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val apk = File(context.externalCacheDir, "update.apk")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var readTotal = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            if (total > 0) {
                                val pct = (readTotal * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            }
                        }
                        output.flush()
                    }
                }
            }
            onProgress(1f)
            apk
        }

    /** Hands [apk] to the system package installer; the confirm prompt is surfaced from the receiver. */
    fun install(context: Context, apk: File) {
        val appContext = context.applicationContext
        registerReceiver(appContext)
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intentSender = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                Intent(INSTALL_ACTION).setPackage(appContext.packageName),
                flags,
            ).intentSender
            session.commit(intentSender)
        }
    }

    @Volatile
    private var receiverRegistered = false

    /** Registered once per process; forwards the system's "confirm install" intent to the user. */
    private fun registerReceiver(appContext: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = confirmIntent(intent)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        if (confirm != null) runCatching { appContext.startActivity(confirm) }
                            .onFailure { Log.e("ApkInstaller", "confirm launch failed", it) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Log.i("ApkInstaller", "update installed")
                    else -> Log.e(
                        "ApkInstaller",
                        "install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
                    )
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
