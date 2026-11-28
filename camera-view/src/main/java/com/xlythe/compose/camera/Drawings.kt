package com.xlythe.compose.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xlythe.view.camera.edit.Brush
import com.xlythe.view.camera.edit.BrushPanelView
import com.xlythe.view.camera.edit.Drawing
import com.xlythe.view.camera.edit.DrawingView

/**
 * A surface to draw on with a pen, a finger or a mouse. See [DrawingView] for what it takes
 * care of: pressure and tilt, the eraser end, palms, prediction and steadying.
 *
 * @param modifier Modifier for layout.
 * @param drawing The drawing to carry on with, such as one restored from [Drawing.toJson]; when
 *   it changes to one other than the surface's own, the surface shows it with nothing to undo.
 * @param brush The brush strokes draw with; its width is in density-independent pixels.
 * @param tool One of [DrawingView]'s tools.
 * @param eraseMode One of [DrawingView]'s erase modes.
 * @param fingerDrawing Whether fingers draw, or pass through to what is beneath.
 * @param onDrawingChanged Called with the drawing after every stroke, erase, undo or redo.
 * @param onView Called once with the view, for undo, redo and clear.
 */
@Composable
fun DrawingSurface(
    modifier: Modifier = Modifier,
    drawing: Drawing? = null,
    brush: Brush = Brush.PEN,
    tool: Int = DrawingView.TOOL_DRAW,
    eraseMode: Int = DrawingView.ERASE_TOUCHED,
    fingerDrawing: Boolean = true,
    onDrawingChanged: (Drawing) -> Unit = {},
    onView: (DrawingView) -> Unit = {},
) {
    val changed by rememberUpdatedState(onDrawingChanged)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            DrawingView(context).apply {
                setOnDrawingChangedListener { changed(it) }
                onView(this)
            }
        },
        update = { view ->
            if (drawing != null && drawing != view.drawing) {
                view.drawing = drawing
            }
            view.brush = brush
            view.tool = tool
            view.eraseMode = eraseMode
            view.isFingerDrawingEnabled = fingerDrawing
        },
    )
}

/**
 * A plain, text-free brush picker: brushes, colours, size and opacity. See [BrushPanelView].
 *
 * @param modifier Modifier for layout.
 * @param brush The brush as chosen.
 * @param onBrushChange Called with the brush the user picks.
 * @param brushes The brushes offered.
 * @param colors The colours offered, as ARGB.
 */
@Composable
fun BrushPanel(
    modifier: Modifier = Modifier,
    brush: Brush,
    onBrushChange: (Brush) -> Unit,
    brushes: List<Brush> = listOf(Brush.PEN, Brush.INKING_PEN, Brush.MARKER, Brush.PENCIL,
        Brush.CHARCOAL, Brush.ERASER),
    colors: IntArray? = null,
) {
    val change by rememberUpdatedState(onBrushChange)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            BrushPanelView(context).apply {
                setBrushes(brushes)
                colors?.let { setColors(it) }
                setOnBrushChangedListener { change(it) }
            }
        },
        update = { view ->
            if (view.brushes != brushes) {
                view.setBrushes(brushes)
            }
            if (colors != null && !colors.contentEquals(view.colors)) {
                view.setColors(colors)
            }
            if (view.brush != brush) {
                view.brush = brush
            }
        },
    )
}
