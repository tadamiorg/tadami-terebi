package com.sf.tadami.terebi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sf.tadami.terebi.R

/**
 * Shown on cold start and whenever no media is loaded — the app is waiting for the phone
 * to cast a video. Doubles as the in-app splash once Compose has mounted: the sender's tada
 * logo, the "Terebi" wordmark, and a spinner that runs until a source loads.
 */
@Composable
fun IdleScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_tada),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.size(width = 130.dp, height = 158.dp),
        )
        Text(
            text = "Terebi",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 24.dp),
        )
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 32.dp)
                .size(40.dp),
        )
    }
}
