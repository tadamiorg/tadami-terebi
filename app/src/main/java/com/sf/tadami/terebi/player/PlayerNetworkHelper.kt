package com.sf.tadami.terebi.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@OptIn(UnstableApi::class)
class PlayerNetworkHelper(context : Context) {
    @UnstableApi
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val cacheSize = 400L * 1024 * 1024 // 400 MiB
    private val cacheDir = File(context.cacheDir, "player_network_cache")
    @UnstableApi
    var cache = SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(cacheSize), databaseProvider)

    /**
     * Trust-all OkHttp client used by the player's OkHttpDataSource.
     *
     * Trust-all is bound to this client instance (not the JVM default) because the process-wide
     * `HttpsURLConnection.setDefaultSSLSocketFactory` is ignored on this device — the platform's
     * internal HttpsURLConnectionImpl + GMS Conscrypt override it. Pinned to HTTP/1.1 to stay
     * close to the phone's HttpURLConnection stack.
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(TrustAll.sslSocketFactory, TrustAll.trustManager)
            .hostnameVerifier(TrustAll.hostnameVerifier)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun clearCache(){
        cache.release()
        SimpleCache.delete(cacheDir,databaseProvider)
        cache = SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(cacheSize), databaseProvider)
    }

    /**
     * Trust-all TLS bound to [okHttpClient]. Some Android TV / Google TV devices ship an outdated
     * CA trust store and reject certs the phone accepts (`SSLHandshakeException: Trust anchor for
     * certification path not found`); relaxing validation on the player's client lets those hosts
     * play. Bound to the client (not the JVM default) because the global default is ignored here.
     */
    object TrustAll {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val trustManager: X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslSocketFactory: SSLSocketFactory by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }.socketFactory
        }

        val hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
}
