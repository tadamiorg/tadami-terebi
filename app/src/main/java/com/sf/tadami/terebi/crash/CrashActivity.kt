package com.sf.tadami.terebi.crash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sf.tadami.terebi.R
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.sf.tadami.terebi.ui.TadamiTerebiTheme
import kotlin.system.exitProcess

/** Shown after an uncaught exception (separate `:crash` process). Displays the stacktrace. */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stacktrace = intent.getStringExtra(EXTRA_STACKTRACE) ?: "No details available."

        setContent {
            TadamiTerebiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.crash_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = stringResource(R.string.crash_message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                        ) {
                            Text(
                                text = stacktrace,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                            )
                        }
                        Surface(onClick = { finishAndExit() }) {
                            Text(
                                text = "Close",
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun finishAndExit() {
        finish()
        exitProcess(0)
    }

    companion object {
        const val EXTRA_STACKTRACE = "stacktrace"
    }
}
