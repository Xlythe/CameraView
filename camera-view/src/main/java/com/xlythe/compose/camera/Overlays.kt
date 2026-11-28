package com.xlythe.compose.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xlythe.view.camera.edit.Overlay
import com.xlythe.view.camera.edit.OverlayView

/**
 * Stickers and text over a picture, to move, pinch, turn, flip and remove. See [OverlayView]
 * for its gestures, handles and animated stickers.
 *
 * @param modifier Modifier for layout.
 * @param canvasWidth The picture's width, in the units items are kept in, such as its pixels.
 * @param canvasHeight The picture's height.
 * @param overlays The items to show; when they change to other than the view's own, the view
 *   shows them with nothing to undo.
 * @param dropTarget Whether pictures dropped from other apps are added where they land.
 * @param onOverlaysChanged Called with the items after every change.
 * @param onSelectionChanged Called with the chosen item, or null.
 * @param onView Called once with the view, to add items and undo.
 */
@Composable
fun Overlays(
    modifier: Modifier = Modifier,
    canvasWidth: Float = 0f,
    canvasHeight: Float = 0f,
    overlays: List<Overlay>? = null,
    dropTarget: Boolean = false,
    onOverlaysChanged: (List<Overlay>) -> Unit = {},
    onSelectionChanged: (Overlay?) -> Unit = {},
    onView: (OverlayView) -> Unit = {},
) {
    val changed by rememberUpdatedState(onOverlaysChanged)
    val selected by rememberUpdatedState(onSelectionChanged)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            OverlayView(context).apply {
                setOnOverlayChangedListener(object : OverlayView.OnOverlayChangedListener {
                    override fun onOverlaysChanged(overlays: List<Overlay>) = changed(overlays)
                    override fun onSelectionChanged(overlay: Overlay?) = selected(overlay)
                })
                onView(this)
            }
        },
        update = { view ->
            view.setCanvasSize(canvasWidth, canvasHeight)
            view.setDropTargetEnabled(dropTarget)
            if (overlays != null && overlays != view.overlays) {
                view.setOverlays(overlays)
            }
        },
    )
}
