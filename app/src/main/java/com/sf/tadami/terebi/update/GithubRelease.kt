package com.sf.tadami.terebi.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Subset of the GitHub `releases/latest` JSON we care about. Mirrors the phone's GithubUpdate. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val version: String,
    @SerialName("body") val info: String = "",
    @SerialName("html_url") val releaseLink: String = "",
    @SerialName("assets") val assets: List<Asset> = emptyList(),
) {
    /** The APK asset's download URL (falls back to the first asset if none end in .apk). */
    fun apkDownloadUrl(): String? =
        assets.firstOrNull { it.downloadUrl.endsWith(".apk", ignoreCase = true) }?.downloadUrl
            ?: assets.firstOrNull()?.downloadUrl

    @Serializable
    data class Asset(@SerialName("browser_download_url") val downloadUrl: String)
}
