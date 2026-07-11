package com.sf.tadami.terebi.update

import com.sf.tadami.terebi.update.UpdateController.available
import com.sf.tadami.terebi.update.UpdateController.required
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide update state observed by PlaybackActivity's overlay.
 *
 * - [required]: the connected phone needs a newer receiver protocol than this build implements →
 *   a blocking (non-skippable) update dialog is shown and playback is refused.
 * - [available]: a newer release exists but the current build is still compatible → a dismissible
 *   ("skip") update dialog is offered. Suppressed once [required] is set.
 */
object UpdateController {

    private val _required = MutableStateFlow(false)
    val required: StateFlow<Boolean> = _required

    private val _available = MutableStateFlow<GithubRelease?>(null)
    val available: StateFlow<GithubRelease?> = _available

    /** Called from the load callback when the phone requires a newer receiver protocol. */
    fun requireUpdate() {
        _required.value = true
        _available.value = null
    }

    /** Called by the non-blocking launch check when a newer release exists (and we're compatible). */
    fun setAvailable(release: GithubRelease) {
        if (!_required.value) _available.value = release
    }

    fun dismissAvailable() {
        _available.value = null
    }
}
