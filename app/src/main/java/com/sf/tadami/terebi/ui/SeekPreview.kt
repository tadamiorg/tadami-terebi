package com.sf.tadami.terebi.ui

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.sf.tadami.terebi.player.preview.SeekPreviewPlayer
import com.sf.tadami.terebi.player.preview.seekFraction
import com.sf.tadami.terebi.player.preview.seekPreviewLeft
import kotlin.math.roundToInt

/** Frame size of the preview card, 16:9 and sized to read from the couch. */
private val PreviewWidth = 240.dp
private val PreviewHeight = 135.dp

/** Space between the card and the timeline, and the least between the card and the screen edges (overscan). */
private val PreviewGap = 12.dp
private val PreviewEdgeMargin = 48.dp

/**
 * The frame of the preview step [positionMs] falls in, with the exact [positionMs] under it. While the step's
 * frame loads, the last frame shown stays with a spinner over it; with no frame yet, or none available, only
 * the time shows.
 */
@OptIn(UnstableApi::class)
@Composable
fun SeekPreviewCard(preview: SeekPreviewPlayer, positionMs: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .shadow(8.dp, shape)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val frame = preview.frame
        if (frame != null) {
            Box(modifier = Modifier.size(PreviewWidth, PreviewHeight), contentAlignment = Alignment.Center) {
                val image = remember(frame) { frame.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(PreviewWidth, PreviewHeight),
                )
                if (preview.frameLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            text = positionMs.formatMinSec(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * [SeekPreviewCard] drawn above a timeline track and centred on the position [positionMs], within the
 * screen edges. Laid out as 0x0 so the timeline keeps its size: place it at the top start of the box holding
 * the track, whose coordinates [track] returns. The thumb is centred on the fill end, so it counts as 0 wide.
 */
@OptIn(UnstableApi::class)
@Composable
fun SeekPreviewAnchor(
    preview: SeekPreviewPlayer,
    positionMs: Long,
    durationMs: Long,
    track: () -> LayoutCoordinates?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            val margin = PreviewEdgeMargin.toPx()
            layout(0, 0) {
                val trackCoordinates = track()?.takeIf { it.isAttached } ?: return@layout
                val root = trackCoordinates.findRootCoordinates()
                val trackStart = root.localPositionOf(trackCoordinates, Offset.Zero).x
                val left = seekPreviewLeft(
                    fraction = seekFraction(positionMs, durationMs),
                    trackStart = trackStart,
                    trackWidth = trackCoordinates.size.width.toFloat(),
                    thumbWidth = 0f,
                    previewWidth = placeable.width.toFloat(),
                    boundsStart = margin,
                    boundsEnd = root.size.width - margin,
                )
                placeable.place(
                    x = (left - trackStart).roundToInt(),
                    y = -placeable.height - PreviewGap.roundToPx(),
                )
            }
        },
    ) {
        SeekPreviewCard(preview, positionMs)
    }
}

/**
 * The surface the preview frames are decoded into, never seen: 1 px, to be placed under the video (whose
 * surface hides it). It lives as long as the playback screen so a fetch ending after the scrub still lands.
 */
@OptIn(UnstableApi::class)
@Composable
fun SeekPreviewSurface(preview: SeekPreviewPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.size(1.dp),
        factory = { context -> TextureView(context).also(preview::attachSurface) },
        onRelease = preview::detachSurface,
    )
}
