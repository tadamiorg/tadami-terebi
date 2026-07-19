package com.sf.tadami.terebi.player

import androidx.media3.common.MimeTypes
import java.util.Locale

/** Stable id prefix set on each subtitle config so it can be selected by an explicit track override. */
const val SUBTITLE_ID_PREFIX = "tadami_sub_"

/** Ported from the phone's StringExtensions.convertToIetfLanguageTag(). */
fun convertToIetfLanguageTag(lang: String): String {
    val input = lang.lowercase(Locale.ROOT)
    return when {
        "english" in input -> Locale.ENGLISH.toLanguageTag()
        "french" in input -> Locale.FRENCH.toLanguageTag()
        "spanish" in input -> Locale("es").toLanguageTag()
        "portuguese" in input -> Locale("pt").toLanguageTag()
        "german" in input -> Locale.GERMAN.toLanguageTag()
        "russian" in input -> Locale("ru").toLanguageTag()
        "italian" in input -> Locale.ITALIAN.toLanguageTag()
        "arabic" in input -> Locale("ar").toLanguageTag()
        else -> lang
    }
}

/**
 * Resolves a subtitle MIME type, sniffing the URL extension when the source reported
 * "text/x-unknown". Returns null when truly unusable.
 */
fun resolveSubtitleMime(mimeType: String, url: String): String? {
    if (mimeType.isNotBlank() && mimeType != MimeTypes.TEXT_UNKNOWN) return mimeType
    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return when {
        path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> null
    }
}
