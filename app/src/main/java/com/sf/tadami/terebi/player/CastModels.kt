package com.sf.tadami.terebi.player

import kotlinx.serialization.Serializable

/**
 * TV-side mirrors of the phone's `StreamSource` / `Track` models. The phone sends these as
 * JSON in the Cast `MediaInfo` customData (`availableSources` / `selectedSource`), where
 * `OkhttpHeadersSerializer` emits headers as a flat `{"Name":"value"}` object — so a plain
 * `Map<String, String>` deserializes them 1:1.
 */
@Serializable
data class TvSubtitleTrack(
    val url: String = "",
    val lang: String = "",
    val mimeType: String = "text/x-unknown",
)

@Serializable
data class TvAudioTrack(
    val url: String = "",
    val lang: String = "",
)

@Serializable
data class TvEpisode(
    val id: Long = 0L,
    val name: String = "",
    val episodeNumber: Float = 0f,
    val seen: Boolean = false,
) {
    fun label(displayMode: String): String {
        val number = if (episodeNumber % 1f == 0f) episodeNumber.toInt().toString() else episodeNumber.toString()
        return if (displayMode == "NAME" && name.isNotBlank()) name else "Episode $number"
    }
}

/** ARGB ints of the phone's active color scheme, forwarded so the TV mirrors the phone theme. */
@Serializable
data class TvThemeColors(
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
)

/** TV → phone control messages (progress persistence + episode navigation). */
@Serializable
data class TvControlMessage(
    val type: String,
    val position: Long = 0L,
    val duration: Long = 0L,
    val playing: Boolean = false,
    val episodeId: Long? = null,
)

@Serializable
data class TvStreamSource(
    val url: String = "",
    val fullName: String = "",
    val quality: String = "",
    val server: String = "",
    val headers: Map<String, String>? = null,
    val subtitleTracks: List<TvSubtitleTrack> = emptyList(),
    val audioTracks: List<TvAudioTrack> = emptyList(),
) {
    /** Human-readable label for the source picker. */
    val label: String
        get() = fullName.ifBlank { "$server $quality".trim() }.ifBlank { url }
}
