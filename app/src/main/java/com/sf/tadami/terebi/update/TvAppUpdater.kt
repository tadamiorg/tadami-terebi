package com.sf.tadami.terebi.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub releases for a newer Tadami Terebi build. GitHub serves valid TLS, so this uses a
 * plain OkHttp client (no trust-all needed) and follows the redirect to the asset CDN when the APK
 * is later downloaded. Mirrors the phone's AppUpdater version-compare logic.
 */
object TvAppUpdater {

    private const val GITHUB_REPO = "tadamiorg/tadami-terebi"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Latest published release, or null on any network/parse error. */
    suspend fun fetchLatestRelease(): GithubRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<GithubRelease>(body)
            }
        }.getOrNull()
    }

    /** Latest release only if it is newer than [currentVersionName]; else null. */
    suspend fun checkForUpdate(currentVersionName: String): GithubRelease? {
        val release = fetchLatestRelease() ?: return null
        return if (isNewer(release.version, currentVersionName)) release else null
    }

    /** Fragment-wise semver compare on the numeric parts only (e.g. "v1.2.0" > "1.1.9"). */
    private fun isNewer(remoteTag: String, current: String): Boolean {
        val new = remoteTag.numericFragments()
        val old = current.numericFragments()
        for (i in 0 until maxOf(new.size, old.size)) {
            val n = new.getOrElse(i) { 0 }
            val o = old.getOrElse(i) { 0 }
            if (n != o) return n > o
        }
        return false
    }

    private fun String.numericFragments(): List<Int> =
        replace("[^\\d.]".toRegex(), "").split(".").mapNotNull { it.toIntOrNull() }
}
