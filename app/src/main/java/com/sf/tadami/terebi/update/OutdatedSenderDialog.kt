package com.sf.tadami.terebi.update

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sf.tadami.terebi.R

/**
 * Blocking dialog shown when the connected phone speaks an older protocol than this receiver
 * requires (the reverse of [UpdateDialog] with `forced = true`). The TV can't update the phone, so
 * there is nothing to download — only a message asking the user to update the phone app and a
 * single [onExit] button that finishes the receiver. BACK is swallowed so it never leaks to the
 * player.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OutdatedSenderDialog(onExit: () -> Unit) {
    val exitFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { exitFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Trap focus inside the dialog and swallow BACK entirely (see UpdateDialog for the same
            // pattern) so D-pad input never reaches the player behind it.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .onPreviewKeyEvent { event -> event.key == Key.Back }
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(modifier = Modifier.fillMaxWidth(0.6f)) {
            Column(Modifier.padding(32.dp)) {
                Text(
                    text = stringResource(R.string.update_sender_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.update_sender_message),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.focusGroup()) {
                    DialogButton(
                        text = stringResource(R.string.update_exit),
                        enabled = true,
                        modifier = Modifier.focusRequester(exitFocus),
                        onClick = onExit,
                    )
                }
            }
        }
    }
}
