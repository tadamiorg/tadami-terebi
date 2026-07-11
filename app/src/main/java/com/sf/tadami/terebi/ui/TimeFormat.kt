package com.sf.tadami.terebi.ui

import java.util.Locale

/** Ported from the phone's NumberExtensions.formatMinSec(). */
fun Long.formatMinSec(): String {
    if (this <= 0L) return "00:00"
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
