package com.imaginstudio.imagetools.pixellab.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.imaginstudio.imagetools.pixellab.engine.CropBounds
import com.imaginstudio.imagetools.pixellab.engine.CropHandle
import com.imaginstudio.imagetools.pixellab.engine.CropZoomMath
import com.imaginstudio.imagetools.pixellab.engine.PixelLabRenderer
import com.imaginstudio.imagetools.pixellab.model.BezierAnchor
import com.imaginstudio.imagetools.pixellab.model.BezierLayer
import com.imaginstudio.imagetools.pixellab.model.CanvasLayer
import com.imaginstudio.imagetools.pixellab.model.DrawPoint
import com.imaginstudio.imagetools.pixellab.model.DrawingLayer
import com.imaginstudio.imagetools.pixellab.model.DrawingStroke
import com.imaginstudio.imagetools.pixellab.model.GridState
import com.imaginstudio.imagetools.pixellab.model.ImageLayer
import com.imaginstudio.imagetools.pixellab.model.ProjectState
import com.imaginstudio.imagetools.pixellab.model.ShapeLayer
import com.imaginstudio.imagetools.pixellab.model.TextLayer
import com.imaginstudio.imagetools.pixellab.ui.PixelLoupeDrawer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class CanvasStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class BrushConfig(
        var color: Int = Color.parseColor("#0079FF"),
        var width: Float = 16f,
        var blur: Float = 0f,
        var dashed: Boolean = false,
        var neon: Boolean = false,
        var fill: Boolean = false,
        var eraser: Boolean = false
    )

    enum class StageToolMode {
        NORMAL,
        DRAW,
        PERSPECTIVE,
        MASK,
        BEZIER,
        BITMAP_ERASER,
        EYEDROPPER,
        CROP
    }

    enum class CropTarget {
        IMAGE_LAYER,
        CANVAS
    }

    var project: ProjectState = ProjectState(id = "init")
        set(value) {
            val dimsChanged = field.width != value.width || field.height != value.height
            field = value
            if (toolMode == StageToolMode.EYEDROPPER) {
                eyedropperBitmapDirty = true
            }
            if (dimsChanged && autoFit && width > 0 && height > 0) {
                fitToScreen()
            } else {
                invalidate()
            }
        }

    var grid: GridState = GridState()
        set(value) {
            field = value
            invalidate()
        }

    var selectedLayerId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var zoomModeEnabled: Boolean = false
        set(value) {
            field = value
            notifyZoomChanged()
            invalidate()
        }

    var toolMode: StageToolMode = StageToolMode.NORMAL
        set(value) {
            field = value
            if (value != StageToolMode.DRAW) {
                activeDrawPoints.clear()
            }
            if (value != StageToolMode.EYEDROPPER) {
                eyedropperX = -1f
                eyedropperY = -1f
                eyedropperDragging = false
                eyedropperBitmapDirty = false
                eyedropperBitmap?.recycle()
                eyedropperBitmap = null
            }
            if (value != StageToolMode.CROP) {
                activeCropHandle = CropHandle.NONE
            }
            invalidate()
        }

    var bezierActionMode: Int = 0 // 0 = Add Point, 1 = Move/Drag Point
    var bitmapEraserWidth: Float = 28f

    val brushConfig = BrushConfig()
    private val pendingStrokes = mutableListOf<DrawingStroke>()
    private val activeDrawPoints = mutableListOf<DrawPoint>()
    private val activeEraserPoints = mutableListOf<DrawPoint>()

    private var activePerspectiveIndex = -1
    private var activeVertexIndex = -1
    private var eyedropperX = -1f
    private var eyedropperY = -1f
    private var eyedropperColor = Color.WHITE
    private var eyedropperDragging = false
    private var eyedropperBitmapDirty = false
    private var eyedropperBitmap: Bitmap? = null

    var onLayerSelected: ((String?) -> Unit)? = null
    var onBeforeTransform: (() -> Unit)? = null
    var onLayerTransform: ((CanvasLayer) -> Unit)? = null
    var onTransformEnd: (() -> Unit)? = null
    var onDoubleTapText: ((TextLayer) -> Unit)? = null
    var onDrawStrokeCountChanged: ((Int) -> Unit)? = null
    var onEyedropperColor: ((Int) -> Unit)? = null
    var onZoomChanged: ((Int, Boolean) -> Unit)? = null

    var zoom: Float = 1f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set
    var autoFit: Boolean = true
        private set

    var cropTarget: CropTarget = CropTarget.CANVAS
        private set
    var cropRect: RectF = RectF(0f, 0f, 1280f, 1280f)
        private set
    var cropAspectRatio: Float? = null
        private set
    var straightenAngle: Float = 0f
        private set

    private var activeCropHandle = CropHandle.NONE
    private var cropDragStartX = 0f
    private var cropDragStartY = 0f
    private var cropRectStart = CropBounds(0f, 0f, 1280f, 1280f)

    private val activePointers = LinkedHashMap<Int, Pair<Float, Float>>()
    private var gestureActive = false
    private var gestureWaitingAllUp = false
    private var gestureInitialDist = 1f
    private var gestureInitialZoom = 1f
    private var gestureInitialPanX = 0f
    private var gestureInitialPanY = 0f
    private var gestureStartMidX = 0f
    private var gestureStartMidY = 0f
    private var gestureAnchorImgX = 0f
    private var gestureAnchorImgY = 0f

    private var baseScale = 1f
    private var stageLeft = 0f
    private var stageTop = 0f

    private enum class DragMode { NONE, MOVE, RESIZE_CORNER, RESIZE_WIDTH, RESIZE_HEIGHT, ROTATE, PAN }

    private var dragMode = DragMode.NONE
    private var dragSnapshotSent = false
    private var touchStartProjX = 0f
    private var touchStartProjY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var origLayerX = 0f
    private var origLayerY = 0f
    private var origLayerW = 0f
    private var origLayerH = 0f
    private var origRot = 0f
    private var origFontSize = 0f
    private var origAspect = 1f
    private var origLocalX = 1f
    private var origLocalY = 1f
    private var origTouchAngleDeg = 0f
    private var origHandleEdgeOffsetX = 0f
    private var origHandleEdgeOffsetY = 0f
    private val tempArcRect = RectF()
    private val tempPath = Path()
    private val tempStageRect = RectF()
    private val tempStageRadii8 = FloatArray(8)
    private val tempPerspPts = FloatArray(8)
    private var eraserStrokeAdded = false

    private val checkerPaint: Paint by lazy {
        val tile = 32
        val half = tile / 2
        val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val light = Paint().apply { color = Color.parseColor("#FFFFFF") }
        val dark = Paint().apply { color = Color.parseColor("#D9D9D9") }
        c.drawRect(0f, 0f, half.toFloat(), half.toFloat(), light)
        c.drawRect(half.toFloat(), 0f, tile.toFloat(), half.toFloat(), dark)
        c.drawRect(0f, half.toFloat(), half.toFloat(), tile.toFloat(), dark)
        c.drawRect(half.toFloat(), half.toFloat(), tile.toFloat(), tile.toFloat(), light)
        Paint().apply {
            shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private val workspaceDotsPaint: Paint by lazy {
        val spacing = kotlin.math.round(22f * resources.displayMetrics.density).toInt().coerceAtLeast(16)
        val dotRadius = (1.1f * resources.displayMetrics.density).coerceAtLeast(1.2f)
        val bmp = Bitmap.createBitmap(spacing, spacing, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#FAFAFA"))
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 127, 127, 127)
            style = Paint.Style.FILL
        }
        c.drawCircle(spacing * 0.5f, spacing * 0.5f, dotRadius, dotPaint)
        Paint().apply {
            shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private val loupeDrawer: PixelLoupeDrawer by lazy {
        PixelLoupeDrawer(resources.displayMetrics.density)
    }
    private val stageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#B4B4B4")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(190, 56, 189, 248)
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val selShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        color = Color.argb(95, 0, 0, 0)
    }
    private val selStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = Color.WHITE
    }
    private val rotArcShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(130, 0, 0, 0)
    }
    private val rotArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val overlayWirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#F59E0B")
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(130, 0, 0, 0)
    }
    private val handleWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#F59E0B")
    }

    private val cropMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88000000")
    }
    private val cropBorderShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(120, 0, 0, 0)
    }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }
    private val cropGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(145, 255, 255, 255)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomModeEnabled) {
                resetZoomToFit()
                return true
            }
            if (toolMode != StageToolMode.NORMAL) return false
            val (px, py) = viewToProject(e.x, e.y)
            val hit = hitTestLayer(px, py)
            if (hit is TextLayer && !hit.locked) {
                onDoubleTapText?.invoke(hit)
                return true
            }
            return false
        }
    })

    private fun dpPx(dpVal: Float): Float = dpVal * context.resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && autoFit) {
            fitToScreen()
        }
    }

    private fun notifyZoomChanged() {
        val pct = (zoom * 100f).roundToIntCompat().coerceAtLeast(2)
        val fitScale = computeFitScale(width.toFloat(), height.toFloat())
        val visible = zoomModeEnabled || !autoFit || abs(zoom - fitScale) > 0.01f
        onZoomChanged?.invoke(pct, visible)
    }

    private fun Float.roundToIntCompat(): Int = kotlin.math.round(this).toInt()

    private fun computeFitScale(vw: Float, vh: Float): Float {
        return CropZoomMath.computeFitZoom(
            viewportW = vw,
            viewportH = vh,
            imageW = project.width.toFloat().coerceAtLeast(1f),
            imageH = project.height.toFloat().coerceAtLeast(1f)
        )
    }

    private fun clampPan() {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val (cx, cy) = CropZoomMath.clampPan(
            panX = panX,
            panY = panY,
            viewportW = vw,
            viewportH = vh,
            imageW = project.width.toFloat().coerceAtLeast(1f),
            imageH = project.height.toFloat().coerceAtLeast(1f),
            zoom = zoom
        )
        panX = cx
        panY = cy
    }

    fun fitToScreen() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) {
            autoFit = true
            return
        }
        zoom = computeFitScale(vw, vh)
        panX = 0f
        panY = 0f
        autoFit = true
        notifyZoomChanged()
        invalidate()
    }

    fun zoomAt(viewX: Float, viewY: Float, targetZoom: Float) {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val iw = project.width.toFloat().coerceAtLeast(1f)
        val ih = project.height.toFloat().coerceAtLeast(1f)
        val next = CropZoomMath.zoomAnchoredAt(
            focusViewX = viewX,
            focusViewY = viewY,
            targetZoom = targetZoom,
            viewportW = vw,
            viewportH = vh,
            imageW = iw,
            imageH = ih,
            currentZoom = zoom,
            currentPanX = panX,
            currentPanY = panY
        )
        zoom = next.zoom
        panX = next.panX
        panY = next.panY
        autoFit = false
        clampPan()
        notifyZoomChanged()
        invalidate()
    }

    fun zoomIn() {
        zoomAt(width * 0.5f, height * 0.5f, zoom * 1.25f)
    }

    fun zoomOut() {
        zoomAt(width * 0.5f, height * 0.5f, zoom / 1.25f)
    }

    fun zoomTo100() {
        zoomAt(width * 0.5f, height * 0.5f, 1f)
    }

    fun resetZoomToFit() {
        fitToScreen()
    }

    fun resetZoom() {
        fitToScreen()
    }

    fun getCropImageDimensions(): Pair<Float, Float> {
        return if (cropTarget == CropTarget.IMAGE_LAYER) {
            val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? ImageLayer
            if (sel != null) {
                sel.width.coerceAtLeast(1f) to sel.height.coerceAtLeast(1f)
            } else {
                project.width.toFloat().coerceAtLeast(1f) to project.height.toFloat().coerceAtLeast(1f)
            }
        } else {
            project.width.toFloat().coerceAtLeast(1f) to project.height.toFloat().coerceAtLeast(1f)
        }
    }

    fun initCrop(target: CropTarget) {
        cropTarget = target
        val (imgW, imgH) = getCropImageDimensions()
        cropRect = RectF(0f, 0f, imgW, imgH)
        cropAspectRatio = null
        straightenAngle = 0f
        activeCropHandle = CropHandle.NONE
        toolMode = StageToolMode.CROP
        invalidate()
    }

    fun setCropAspectRatio(ratio: Float?) {
        cropAspectRatio = ratio
        val (imgW, imgH) = getCropImageDimensions()
        val fitted = CropZoomMath.aspectRatioRect(imgW, imgH, ratio)
        cropRect.set(fitted.left, fitted.top, fitted.right, fitted.bottom)
        invalidate()
    }

    fun setStraightenAngle(deg: Float) {
        straightenAngle = deg.coerceIn(-45f, 45f)
        invalidate()
    }

    fun resetCrop() {
        val (imgW, imgH) = getCropImageDimensions()
        cropRect = RectF(0f, 0f, imgW, imgH)
        cropAspectRatio = null
        straightenAngle = 0f
        activeCropHandle = CropHandle.NONE
        invalidate()
    }

    fun clearCropState() {
        cropAspectRatio = null
        straightenAngle = 0f
        activeCropHandle = CropHandle.NONE
        if (toolMode == StageToolMode.CROP) {
            toolMode = StageToolMode.NORMAL
        }
        invalidate()
    }

    fun undoPendingStroke() {
        if (pendingStrokes.isNotEmpty()) {
            pendingStrokes.removeAt(pendingStrokes.lastIndex)
            onDrawStrokeCountChanged?.invoke(pendingStrokes.size)
            invalidate()
        }
    }

    fun clearPendingDrawing() {
        pendingStrokes.clear()
        activeDrawPoints.clear()
        onDrawStrokeCountChanged?.invoke(0)
        invalidate()
    }

    fun finishDrawingLayer(): DrawingLayer? {
        if (pendingStrokes.isEmpty()) return null
        val layer = DrawingLayer(
            id = "draw_${System.currentTimeMillis()}",
            name = "Drawing",
            x = project.width / 2f,
            y = project.height / 2f,
            width = project.width.toFloat(),
            height = project.height.toFloat(),
            sourceWidth = project.width.toFloat(),
            sourceHeight = project.height.toFloat(),
            strokes = pendingStrokes.toList()
        )
        pendingStrokes.clear()
        activeDrawPoints.clear()
        onDrawStrokeCountChanged?.invoke(0)
        invalidate()
        return layer
    }

    private fun updateStageMetrics() {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        baseScale = CropZoomMath.clampZoom(zoom)
        val stageW = project.width * baseScale
        val stageH = project.height * baseScale
        stageLeft = (vw - stageW) * 0.5f + panX
        stageTop = (vh - stageH) * 0.5f + panY
    }

    private fun viewToProject(vx: Float, vy: Float): Pair<Float, Float> {
        updateStageMetrics()
        val px = (vx - stageLeft) / baseScale
        val py = (vy - stageTop) / baseScale
        return Pair(px, py)
    }

    private fun projectToLayerNormalized(layer: CanvasLayer, px: Float, py: Float): DrawPoint {
        val dx = px - layer.x
        val dy = py - layer.y
        val rad = -layer.rotation * PI / 180.0
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val nx = (lx / layer.width.coerceAtLeast(1f)) + 0.5f
        val ny = (ly / layer.height.coerceAtLeast(1f)) + 0.5f
        return DrawPoint(nx, ny)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), workspaceDotsPaint)
        updateStageMetrics()

        val stageW = project.width * baseScale
        val stageH = project.height * baseScale

        val pw = project.width.toFloat()
        val ph = project.height.toFloat()
        val hasBgCorners = PixelLabRenderer.hasBgCornerRadius(project.background, pw, ph)
        canvas.save()
        canvas.translate(stageLeft, stageTop)
        if (project.background.type == com.imaginstudio.imagetools.pixellab.model.BackgroundType.TRANSPARENT ||
            project.background.circular ||
            hasBgCorners
        ) {
            canvas.drawRect(0f, 0f, stageW, stageH, checkerPaint)
        }

        canvas.save()
        canvas.scale(baseScale, baseScale)

        val renderProjectState = if (
            toolMode == StageToolMode.CROP &&
            cropTarget == CropTarget.IMAGE_LAYER &&
            abs(straightenAngle) > 0.01f
        ) {
            val selId = selectedLayerId
            project.copy(layers = project.layers.map { l ->
                if (l is ImageLayer && l.id == selId) {
                    l.copy(rotation = l.rotation + straightenAngle)
                } else l
            })
        } else {
            project
        }

        canvas.save()
        if (toolMode == StageToolMode.CROP && cropTarget == CropTarget.CANVAS && abs(straightenAngle) > 0.01f) {
            canvas.rotate(straightenAngle, pw * 0.5f, ph * 0.5f)
        }
        PixelLabRenderer.renderProject(
            context = context,
            canvas = canvas,
            project = renderProjectState,
            skipTransparentBg = false,
            clipToArtboard = false
        )
        canvas.restore()

        if (toolMode == StageToolMode.DRAW && (pendingStrokes.isNotEmpty() || activeDrawPoints.isNotEmpty())) {
            val needsLayer = brushConfig.eraser || pendingStrokes.any { it.eraser }
            val saveL = if (needsLayer) {
                canvas.saveLayer(0f, 0f, project.width.toFloat(), project.height.toFloat(), null)
            } else -1
            for (s in pendingStrokes) {
                PixelLabRenderer.drawStroke(canvas, s)
            }
            if (activeDrawPoints.isNotEmpty()) {
                PixelLabRenderer.drawStrokePoints(
                    canvas = canvas,
                    points = activeDrawPoints,
                    color = brushConfig.color,
                    width = brushConfig.width,
                    blur = brushConfig.blur,
                    dashed = brushConfig.dashed,
                    neon = brushConfig.neon,
                    fill = brushConfig.fill,
                    eraser = brushConfig.eraser
                )
            }
            if (saveL >= 0) {
                canvas.restoreToCount(saveL)
            }
        }

        if (grid.enabled) {
            val pw = project.width.toFloat()
            val ph = project.height.toFloat()
            for (hFrac in grid.hGuides) {
                val gy = hFrac * ph
                canvas.drawLine(0f, gy, pw, gy, gridPaint)
            }
            for (vFrac in grid.vGuides) {
                val gx = vFrac * pw
                canvas.drawLine(gx, 0f, gx, ph, gridPaint)
            }
        }

        val selected = project.layers.firstOrNull { it.id == selectedLayerId && !it.hidden }
        val invScale = (1f / baseScale).coerceAtLeast(0.5f)
        if (toolMode == StageToolMode.CROP) {
            if (cropTarget == CropTarget.CANVAS) {
                drawCropOverlay(canvas, project.width.toFloat(), project.height.toFloat(), invScale)
            } else if (selected is ImageLayer) {
                canvas.save()
                canvas.translate(selected.x - selected.width * 0.5f, selected.y - selected.height * 0.5f)
                if (selected.rotation != 0f) {
                    canvas.rotate(selected.rotation, selected.width * 0.5f, selected.height * 0.5f)
                }
                drawCropOverlay(canvas, selected.width, selected.height, invScale)
                canvas.restore()
            }
        } else if (selected != null) {
            when (toolMode) {
                StageToolMode.NORMAL -> drawSelectionOverlay(canvas, selected, invScale)
                StageToolMode.PERSPECTIVE -> drawPerspectiveOverlay(canvas, selected, invScale)
                StageToolMode.MASK -> drawMaskOverlay(canvas, selected, invScale)
                StageToolMode.BEZIER -> if (selected is BezierLayer) drawBezierOverlay(canvas, selected, invScale)
                StageToolMode.BITMAP_ERASER -> drawSelectionOverlay(canvas, selected, invScale)
                else -> Unit
            }
        }

        canvas.restore()
        canvas.drawRect(0f, 0f, stageW, stageH, stageBorderPaint)
        if (project.background.circular || hasBgCorners) {
            canvas.save()
            canvas.scale(baseScale, baseScale)
            if (PixelLabRenderer.buildArtboardClipPath(project.background, pw, ph, tempPath, tempStageRect, tempStageRadii8)) {
                val prevW = stageBorderPaint.strokeWidth
                stageBorderPaint.strokeWidth = 1.5f * invScale
                canvas.drawPath(tempPath, stageBorderPaint)
                stageBorderPaint.strokeWidth = prevW
            }
            canvas.restore()
        }
        canvas.restore()

        if (toolMode == StageToolMode.EYEDROPPER && eyedropperX >= 0f && eyedropperY >= 0f) {
            val vx = stageLeft + eyedropperX * baseScale
            val vy = stageTop + eyedropperY * baseScale
            val snap = eyedropperBitmap
            if (snap != null && !snap.isRecycled) {
                val ix = ((eyedropperX / pw.coerceAtLeast(1f)) * (snap.width - 1)).roundToInt().coerceIn(0, snap.width - 1)
                val iy = ((eyedropperY / ph.coerceAtLeast(1f)) * (snap.height - 1)).roundToInt().coerceIn(0, snap.height - 1)
                loupeDrawer.drawLoupeWithCrosshair(
                    canvas = canvas,
                    viewWidth = width.toFloat(),
                    viewHeight = height.toFloat(),
                    bitmap = snap,
                    sampleBmpX = ix,
                    sampleBmpY = iy,
                    touchX = vx,
                    touchY = vy,
                    sampledColor = eyedropperColor,
                    showLoupe = eyedropperDragging,
                    flipThresholdTopPx = 56f * resources.displayMetrics.density
                )
            } else {
                loupeDrawer.drawCrosshairOnly(canvas, vx, vy, eyedropperColor)
            }
        }
    }

    private fun drawCropOverlay(canvas: Canvas, imgW: Float, imgH: Float, invScale: Float) {
        val r = cropRect
        tempPath.reset()
        tempPath.fillType = Path.FillType.EVEN_ODD
        tempPath.addRect(0f, 0f, imgW, imgH, Path.Direction.CW)
        tempPath.addRect(r, Path.Direction.CW)
        canvas.drawPath(tempPath, cropMaskPaint)
        tempPath.fillType = Path.FillType.WINDING

        cropBorderShadowPaint.strokeWidth = 3.5f * invScale
        cropBorderPaint.strokeWidth = 2f * invScale
        cropGridPaint.strokeWidth = 1f * invScale
        handleBorderPaint.strokeWidth = 1.8f * invScale

        canvas.drawRect(r, cropBorderShadowPaint)
        canvas.drawRect(r, cropBorderPaint)

        val rw = r.width()
        val rh = r.height()
        for (i in 1..2) {
            val gx = r.left + rw * i / 3f
            val gy = r.top + rh * i / 3f
            canvas.drawLine(gx, r.top, gx, r.bottom, cropGridPaint)
            canvas.drawLine(r.left, gy, r.right, gy, cropGridPaint)
        }

        val cx = r.centerX()
        val cy = r.centerY()
        val hr = 11f * invScale
        val handleCoords = floatArrayOf(
            r.left, r.top,
            r.right, r.top,
            r.left, r.bottom,
            r.right, r.bottom,
            cx, r.top,
            cx, r.bottom,
            r.left, cy,
            r.right, cy
        )
        var idx = 0
        while (idx < handleCoords.size) {
            val hx = handleCoords[idx]
            val hy = handleCoords[idx + 1]
            canvas.drawCircle(hx, hy, hr, handleFillPaint)
            canvas.drawCircle(hx, hy, hr, handleBorderPaint)
            idx += 2
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas, layer: CanvasLayer, invScale: Float) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        val hw = layer.width / 2f
        val hh = layer.height / 2f

        selShadowPaint.strokeWidth = 3.2f * invScale
        selStrokePaint.strokeWidth = 1.8f * invScale
        handleBorderPaint.strokeWidth = 1.8f * invScale
        rotArcShadowPaint.strokeWidth = 5.4f * invScale
        rotArcPaint.strokeWidth = 3.0f * invScale

        canvas.drawRect(-hw, -hh, hw, hh, selShadowPaint)
        canvas.drawRect(-hw, -hh, hw, hh, selStrokePaint)

        if (layer is TextLayer) {
            // PixelLab Text selection: bottom-left scale dot, right-center wrap dot, bottom-center smile rotation arc
            val dotR = 11f * invScale
            canvas.drawCircle(-hw, hh, dotR, handleFillPaint)
            canvas.drawCircle(-hw, hh, dotR, handleBorderPaint)

            canvas.drawCircle(hw, 0f, dotR, handleFillPaint)
            canvas.drawCircle(hw, 0f, dotR, handleBorderPaint)

            val arcR = 16f * invScale
            tempArcRect.set(-arcR, hh + 6f * invScale, arcR, hh + 6f * invScale + arcR * 1.4f)
            canvas.drawArc(tempArcRect, 20f, 140f, false, rotArcShadowPaint)
            canvas.drawArc(tempArcRect, 20f, 140f, false, rotArcPaint)
        } else {
            // PixelLab Shape/Image selection: bottom-right main corner dot, right/bottom edge dots, bottom-left corner rotation arc
            val cornerR = 14f * invScale
            val edgeR = 7.5f * invScale

            canvas.drawCircle(hw, hh, cornerR, handleFillPaint)
            canvas.drawCircle(hw, hh, cornerR, handleBorderPaint)

            canvas.drawCircle(hw, 0f, edgeR, handleFillPaint)
            canvas.drawCircle(hw, 0f, edgeR, handleBorderPaint)

            canvas.drawCircle(0f, hh, edgeR, handleFillPaint)
            canvas.drawCircle(0f, hh, edgeR, handleBorderPaint)

            val arcR = 20f * invScale
            val cx = -hw + 8f * invScale
            val cy = hh - 8f * invScale
            tempArcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
            canvas.drawArc(tempArcRect, 90f, 90f, false, rotArcShadowPaint)
            canvas.drawArc(tempArcRect, 90f, 90f, false, rotArcPaint)
        }
        canvas.restore()
    }

    private fun drawPerspectiveOverlay(canvas: Canvas, layer: CanvasLayer, invScale: Float) {
        val pts = when (layer) {
            is TextLayer -> layer.perspectivePoints
            is ShapeLayer -> layer.perspectivePoints
            is ImageLayer -> layer.perspectivePoints
            else -> return
        }
        if (pts.size != 4) return
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        overlayWirePaint.strokeWidth = 3f * invScale
        handleWarnPaint.strokeWidth = 3.5f * invScale

        for (i in 0..3) {
            tempPerspPts[i * 2] = (pts[i].x - 0.5f) * layer.width
            tempPerspPts[i * 2 + 1] = (pts[i].y - 0.5f) * layer.height
        }
        tempPath.reset()
        tempPath.moveTo(tempPerspPts[0], tempPerspPts[1])
        for (i in 1..3) tempPath.lineTo(tempPerspPts[i * 2], tempPerspPts[i * 2 + 1])
        tempPath.close()
        canvas.drawPath(tempPath, overlayWirePaint)

        val hr = 16f * invScale
        for (i in 0..3) {
            val cx = tempPerspPts[i * 2]
            val cy = tempPerspPts[i * 2 + 1]
            canvas.drawCircle(cx, cy, hr, handleFillPaint)
            canvas.drawCircle(cx, cy, hr, handleWarnPaint)
        }
        canvas.restore()
    }

    private fun drawMaskOverlay(canvas: Canvas, layer: CanvasLayer, invScale: Float) {
        val pts = when (layer) {
            is TextLayer -> layer.maskPoints
            is ShapeLayer -> layer.maskPoints
            is ImageLayer -> layer.maskPoints
            else -> return
        }
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        overlayWirePaint.strokeWidth = 3f * invScale
        handleWarnPaint.strokeWidth = 3f * invScale
        if (pts.size >= 2) {
            tempPath.reset()
            tempPath.moveTo((pts[0].x - 0.5f) * layer.width, (pts[0].y - 0.5f) * layer.height)
            for (i in 1 until pts.size) {
                tempPath.lineTo((pts[i].x - 0.5f) * layer.width, (pts[i].y - 0.5f) * layer.height)
            }
            if (pts.size >= 3) tempPath.close()
            canvas.drawPath(tempPath, overlayWirePaint)
        }
        val hr = 14f * invScale
        for (pt in pts) {
            val lx = (pt.x - 0.5f) * layer.width
            val ly = (pt.y - 0.5f) * layer.height
            canvas.drawCircle(lx, ly, hr, handleFillPaint)
            canvas.drawCircle(lx, ly, hr, handleWarnPaint)
        }
        canvas.restore()
    }

    private fun drawBezierOverlay(canvas: Canvas, layer: BezierLayer, invScale: Float) {
        canvas.save()
        canvas.translate(layer.x, layer.y)
        canvas.rotate(layer.rotation)
        overlayWirePaint.strokeWidth = 2.5f * invScale
        handleWarnPaint.strokeWidth = 3f * invScale
        val hr = 14f * invScale
        for (anc in layer.anchors) {
            val ax = (anc.x - 0.5f) * layer.width
            val ay = (anc.y - 0.5f) * layer.height
            val ix = (anc.ctrlInX - 0.5f) * layer.width
            val iy = (anc.ctrlInY - 0.5f) * layer.height
            val ox = (anc.ctrlOutX - 0.5f) * layer.width
            val oy = (anc.ctrlOutY - 0.5f) * layer.height
            canvas.drawLine(ix, iy, ox, oy, overlayWirePaint)
            canvas.drawCircle(ix, iy, hr * 0.65f, handleFillPaint)
            canvas.drawCircle(ox, oy, hr * 0.65f, handleFillPaint)
            canvas.drawCircle(ax, ay, hr, handleFillPaint)
            canvas.drawCircle(ax, ay, hr, handleWarnPaint)
        }
        canvas.restore()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun cancelSingleFingerInteractions() {
        dragMode = DragMode.NONE
        dragSnapshotSent = false
        activeCropHandle = CropHandle.NONE
        activePerspectiveIndex = -1
        activeVertexIndex = -1
        activeDrawPoints.clear()
        activeEraserPoints.clear()
        eraserStrokeAdded = false
        eyedropperDragging = false
    }

    private fun syncActivePointers(event: MotionEvent) {
        val count = event.pointerCount
        for (i in 0 until count) {
            val pid = event.getPointerId(i)
            activePointers[pid] = event.getX(i) to event.getY(i)
        }
        val iter = activePointers.keys.iterator()
        while (iter.hasNext()) {
            val pid = iter.next()
            var stillPresent = false
            for (i in 0 until count) {
                if (event.getPointerId(i) == pid) {
                    stillPresent = true
                    break
                }
            }
            if (!stillPresent) iter.remove()
        }
    }

    private fun firstTwoPointers(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val iter = activePointers.values.iterator()
        if (!iter.hasNext()) return null
        val a = iter.next()
        if (!iter.hasNext()) return null
        val b = iter.next()
        return a to b
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointers.clear()
                activePointers[event.getPointerId(0)] = event.x to event.y
                gestureActive = false
                gestureWaitingAllUp = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                syncActivePointers(event)
                if (activePointers.size >= 2 && !gestureActive) {
                    cancelSingleFingerInteractions()
                    val pair = firstTwoPointers() ?: return true
                    val a = pair.first
                    val b = pair.second
                    gestureInitialDist = max(1f, hypot(a.first - b.first, a.second - b.second))
                    gestureInitialZoom = zoom
                    gestureInitialPanX = panX
                    gestureInitialPanY = panY
                    val midX = (a.first + b.first) * 0.5f
                    val midY = (a.second + b.second) * 0.5f
                    gestureStartMidX = midX
                    gestureStartMidY = midY
                    val (ux, uy) = CropZoomMath.viewToImage(
                        midX, midY,
                        width.toFloat().coerceAtLeast(1f),
                        height.toFloat().coerceAtLeast(1f),
                        project.width.toFloat().coerceAtLeast(1f),
                        project.height.toFloat().coerceAtLeast(1f),
                        zoom, panX, panY
                    )
                    gestureAnchorImgX = ux
                    gestureAnchorImgY = uy
                    gestureActive = true
                    gestureWaitingAllUp = false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                syncActivePointers(event)
                if (gestureActive && activePointers.size >= 2) {
                    val pair = firstTwoPointers() ?: return true
                    val a = pair.first
                    val b = pair.second
                    val dist = hypot(a.first - b.first, a.second - b.second)
                    val midX = (a.first + b.first) * 0.5f
                    val midY = (a.second + b.second) * 0.5f
                    val vw = width.toFloat().coerceAtLeast(1f)
                    val vh = height.toFloat().coerceAtLeast(1f)
                    val iw = project.width.toFloat().coerceAtLeast(1f)
                    val ih = project.height.toFloat().coerceAtLeast(1f)
                    val res = if (zoomModeEnabled) {
                        CropZoomMath.pinchPanTransform(
                            anchorImageX = gestureAnchorImgX,
                            anchorImageY = gestureAnchorImgY,
                            initialZoom = gestureInitialZoom,
                            initialDist = gestureInitialDist,
                            currentDist = dist,
                            midViewX = midX,
                            midViewY = midY,
                            viewportW = vw,
                            viewportH = vh,
                            imageW = iw,
                            imageH = ih
                        )
                    } else {
                        CropZoomMath.pinchZoomOnlyTransform(
                            focusViewX = gestureStartMidX,
                            focusViewY = gestureStartMidY,
                            initialZoom = gestureInitialZoom,
                            initialPanX = gestureInitialPanX,
                            initialPanY = gestureInitialPanY,
                            initialDist = gestureInitialDist,
                            currentDist = dist,
                            viewportW = vw,
                            viewportH = vh,
                            imageW = iw,
                            imageH = ih
                        )
                    }
                    zoom = res.zoom
                    val (cx, cy) = CropZoomMath.clampPan(res.panX, res.panY, vw, vh, iw, ih, zoom)
                    panX = cx
                    panY = cy
                    autoFit = false
                    notifyZoomChanged()
                    invalidate()
                    return true
                }
                if (gestureWaitingAllUp) {
                    if (zoomModeEnabled && event.pointerCount == 1) {
                        panX += event.x - lastTouchX
                        panY += event.y - lastTouchY
                        autoFit = false
                        clampPan()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        notifyZoomChanged()
                        invalidate()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                activePointers.remove(liftedId)
                if (gestureActive && activePointers.size < 2) {
                    gestureActive = false
                    gestureWaitingAllUp = true
                    val rem = activePointers.values.firstOrNull()
                    if (rem != null) {
                        lastTouchX = rem.first
                        lastTouchY = rem.second
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                gestureActive = false
                if (gestureWaitingAllUp) {
                    gestureWaitingAllUp = false
                    dragMode = DragMode.NONE
                    return true
                }
            }
        }


        if (zoomModeEnabled && toolMode != StageToolMode.CROP) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragMode = DragMode.PAN
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragMode == DragMode.PAN && !gestureActive) {
                        panX += event.x - lastTouchX
                        panY += event.y - lastTouchY
                        autoFit = false
                        clampPan()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        notifyZoomChanged()
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
            }
            return true
        }

        val (px, py) = viewToProject(event.x, event.y)

        when (toolMode) {
            StageToolMode.CROP -> {
                handleCropTouch(event, px, py)
                return true
            }
            StageToolMode.DRAW -> {
                handleFreehandDraw(event, px, py)
                return true
            }
            StageToolMode.PERSPECTIVE -> {
                handlePerspectiveTouch(event, px, py)
                return true
            }
            StageToolMode.MASK -> {
                handleMaskTouch(event, px, py)
                return true
            }
            StageToolMode.BEZIER -> {
                handleBezierTouch(event, px, py)
                return true
            }
            StageToolMode.BITMAP_ERASER -> {
                handleBitmapEraserTouch(event, px, py)
                return true
            }
            StageToolMode.EYEDROPPER -> {
                val clampedPx = px.coerceIn(0f, project.width.toFloat().coerceAtLeast(1f))
                val clampedPy = py.coerceIn(0f, project.height.toFloat().coerceAtLeast(1f))
                eyedropperX = clampedPx
                eyedropperY = clampedPy
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> eyedropperDragging = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> eyedropperDragging = false
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN || eyedropperBitmap == null || eyedropperBitmap?.isRecycled == true || eyedropperBitmapDirty) {
                    eyedropperBitmap?.recycle()
                    val maxSide = max(project.width, project.height).coerceAtLeast(1)
                    val snapScale = min(1f, 1080f / maxSide.toFloat())
                    val sw = (project.width * snapScale).roundToInt().coerceIn(64, 1080)
                    val sh = (project.height * snapScale).roundToInt().coerceIn(64, 1080)
                    eyedropperBitmap = PixelLabRenderer.exportToBitmap(context, project, sw, sh, includeBackground = true)
                    eyedropperBitmapDirty = false
                }
                eyedropperBitmap?.let { snap ->
                    val ix = ((clampedPx / project.width.coerceAtLeast(1)) * (snap.width - 1)).roundToInt().coerceIn(0, snap.width - 1)
                    val iy = ((clampedPy / project.height.coerceAtLeast(1)) * (snap.height - 1)).roundToInt().coerceIn(0, snap.height - 1)
                    eyedropperColor = snap.getPixel(ix, iy)
                    onEyedropperColor?.invoke(eyedropperColor)
                }
                invalidate()
                return true
            }
            StageToolMode.NORMAL -> Unit
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragSnapshotSent = false
                touchStartProjX = px
                touchStartProjY = py

                val sel = project.layers.firstOrNull { it.id == selectedLayerId && !it.hidden }
                if (sel != null && !sel.locked) {
                    val handleHit = checkHandleHit(sel, px, py)
                    if (handleHit != DragMode.NONE) {
                        dragMode = handleHit
                        origLayerX = sel.x
                        origLayerY = sel.y
                        origLayerW = sel.width
                        origLayerH = sel.height
                        origRot = sel.rotation
                        origFontSize = (sel as? TextLayer)?.fontSize ?: 0f
                        origAspect = (sel.width / sel.height.coerceAtLeast(1f)).coerceAtLeast(0.05f)
                        origTouchAngleDeg = (atan2(py - sel.y, px - sel.x) * 180.0 / PI).toFloat()
                        val (lx, ly) = projectToLayerLocal(sel, px, py)
                        origLocalX = lx
                        origLocalY = ly
                        origHandleEdgeOffsetX = lx - sel.width / 2f
                        origHandleEdgeOffsetY = ly - sel.height / 2f
                        return true
                    }
                }

                val hit = hitTestLayer(px, py)
                if (hit != null) {
                    onLayerSelected?.invoke(hit.id)
                    if (!hit.locked) {
                        dragMode = DragMode.MOVE
                        origLayerX = hit.x
                        origLayerY = hit.y
                    } else {
                        dragMode = DragMode.NONE
                    }
                } else {
                    onLayerSelected?.invoke(null)
                    dragMode = DragMode.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.PAN && !gestureActive) {
                    panX += event.x - lastTouchX
                    panY += event.y - lastTouchY
                    autoFit = false
                    clampPan()
                    lastTouchX = event.x
                    lastTouchY = event.y
                    notifyZoomChanged()
                    invalidate()
                    return true
                }
                val sel = project.layers.firstOrNull { it.id == selectedLayerId && !it.hidden && !it.locked }
                    ?: return true
                if (dragMode == DragMode.NONE) return true

                if (!dragSnapshotSent) {
                    dragSnapshotSent = true
                    onBeforeTransform?.invoke()
                }

                when (dragMode) {
                    DragMode.MOVE -> {
                        var nx = origLayerX + (px - touchStartProjX)
                        var ny = origLayerY + (py - touchStartProjY)
                        if (grid.enabled && grid.snap) {
                            nx = snapToGuides(nx, project.width.toFloat(), grid.vGuides)
                            ny = snapToGuides(ny, project.height.toFloat(), grid.hGuides)
                        }
                        onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny))
                    }
                    DragMode.RESIZE_CORNER -> {
                        val (lx, ly) = projectToOriginLocal(px, py)
                        if (sel is TextLayer) {
                            // Text bottom-left handle anchors opposite top-right corner (+origLayerW/2, -origLayerH/2)
                            val ax = origLayerW / 2f
                            val ay = -origLayerH / 2f
                            val origDx = origLocalX - ax
                            val origDy = origLocalY - ay
                            val lenSq = (origDx * origDx + origDy * origDy).coerceAtLeast(64f)
                            val scale = (((lx - ax) * origDx + (ly - ay) * origDy) / lenSq).coerceIn(0.12f, 12f)
                            val nextFont = (origFontSize * scale).coerceIn(14f, 450f)
                            val probe = sel.copy(fontSize = nextFont)
                            val (nw, nh) = PixelLabRenderer.measureTextBounds(probe, context)
                            val (nx, ny) = originLocalShiftToCanvas((origLayerW - nw) / 2f, (nh - origLayerH) / 2f)
                            onLayerTransform?.invoke(probe.copy(x = nx, y = ny, width = nw, height = nh))
                        } else {
                            // Bottom-right corner handle anchors opposite top-left corner (-origLayerW/2, -origLayerH/2)
                            val ax = -origLayerW / 2f
                            val ay = -origLayerH / 2f
                            val origDx = origLocalX - ax
                            val origDy = origLocalY - ay
                            val lenSq = (origDx * origDx + origDy * origDy).coerceAtLeast(64f)
                            val scale = (((lx - ax) * origDx + (ly - ay) * origDy) / lenSq).coerceIn(0.12f, 12f)
                            val nw = (origLayerW * scale).coerceAtLeast(36f)
                            val nh = (nw / origAspect).coerceAtLeast(28f)
                            val (nx, ny) = originLocalShiftToCanvas((nw - origLayerW) / 2f, (nh - origLayerH) / 2f)
                            onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny, width = nw, height = nh))
                        }
                    }
                    DragMode.RESIZE_WIDTH -> {
                        val (lx, _) = projectToOriginLocal(px, py)
                        val rightEdge = lx - origHandleEdgeOffsetX
                        val minW = if (sel is TextLayer) 64f else 36f
                        val targetW = (rightEdge - (-origLayerW / 2f)).coerceAtLeast(minW)
                        if (sel is TextLayer) {
                            val (nw, nh) = PixelLabRenderer.measureWrappedTextBounds(sel, targetW, context)
                            val (nx, ny) = originLocalShiftToCanvas((nw - origLayerW) / 2f, (nh - origLayerH) / 2f)
                            onLayerTransform?.invoke(sel.copy(x = nx, y = ny, width = nw, height = nh))
                        } else {
                            val (nx, ny) = originLocalShiftToCanvas((targetW - origLayerW) / 2f, 0f)
                            onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny, width = targetW))
                        }
                    }
                    DragMode.RESIZE_HEIGHT -> {
                        val (_, ly) = projectToOriginLocal(px, py)
                        val bottomEdge = ly - origHandleEdgeOffsetY
                        val nh = (bottomEdge - (-origLayerH / 2f)).coerceAtLeast(32f)
                        val (nx, ny) = originLocalShiftToCanvas(0f, (nh - origLayerH) / 2f)
                        onLayerTransform?.invoke(sel.copyBase(x = nx, y = ny, height = nh))
                    }
                    DragMode.ROTATE -> {
                        val curAngleDeg = (atan2(py - sel.y, px - sel.x) * 180.0 / PI).toFloat()
                        var deg = origRot + (curAngleDeg - origTouchAngleDeg)
                        while (deg > 180f) deg -= 360f
                        while (deg < -180f) deg += 360f
                        deg = snapAngle(deg)
                        onLayerTransform?.invoke(sel.copyBase(rotation = deg))
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragSnapshotSent) {
                    onTransformEnd?.invoke()
                }
                dragMode = DragMode.NONE
                dragSnapshotSent = false
            }
        }
        return true
    }

    private fun handleCropTouch(event: MotionEvent, px: Float, py: Float) {
        val (cx, cy) = if (cropTarget == CropTarget.IMAGE_LAYER) {
            val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? ImageLayer ?: return
            val (lx, ly) = projectToLayerLocal(sel, px, py)
            (lx + sel.width * 0.5f) to (ly + sel.height * 0.5f)
        } else {
            px to py
        }
        val (imgW, imgH) = getCropImageDimensions()
        val invScale = (1f / baseScale).coerceAtLeast(0.5f)
        val hitRadius = max(34f * invScale, 24f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bounds = CropBounds(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
                val hit = CropZoomMath.hitCropHandle(bounds, cx, cy, hitRadius)
                if (hit != CropHandle.NONE) {
                    activeCropHandle = hit
                    cropDragStartX = cx
                    cropDragStartY = cy
                    cropRectStart = bounds
                } else {
                    activeCropHandle = CropHandle.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCropHandle != CropHandle.NONE) {
                    val next = CropZoomMath.resizeCrop(
                        handle = activeCropHandle,
                        dx = cx - cropDragStartX,
                        dy = cy - cropDragStartY,
                        start = cropRectStart,
                        imageW = imgW,
                        imageH = imgH,
                        aspectRatio = cropAspectRatio
                    )
                    cropRect.set(next.left, next.top, next.right, next.bottom)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCropHandle = CropHandle.NONE
            }
        }
    }

    private fun handleFreehandDraw(event: MotionEvent, px: Float, py: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeDrawPoints.clear()
                activeDrawPoints.add(DrawPoint(px, py))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                activeDrawPoints.add(DrawPoint(px, py))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeDrawPoints.isNotEmpty()) {
                    pendingStrokes.add(
                        DrawingStroke(
                            points = activeDrawPoints.toList(),
                            color = brushConfig.color,
                            width = brushConfig.width,
                            blur = brushConfig.blur,
                            dashed = brushConfig.dashed,
                            neon = brushConfig.neon,
                            fill = brushConfig.fill,
                            eraser = brushConfig.eraser
                        )
                    )
                    activeDrawPoints.clear()
                    onDrawStrokeCountChanged?.invoke(pendingStrokes.size)
                    invalidate()
                }
            }
        }
    }

    private fun handlePerspectiveTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } ?: return
        val pts = when (sel) {
            is TextLayer -> sel.perspectivePoints
            is ShapeLayer -> sel.perspectivePoints
            is ImageLayer -> sel.perspectivePoints
            else -> return
        }
        if (pts.size != 4) return
        val np = projectToLayerNormalized(sel, px, py)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePerspectiveIndex = pts.indices.minByOrNull { idx ->
                    hypot(pts[idx].x - np.x, pts[idx].y - np.y)
                } ?: -1
                if (activePerspectiveIndex >= 0) {
                    val dist = hypot((pts[activePerspectiveIndex].x - np.x) * sel.width, (pts[activePerspectiveIndex].y - np.y) * sel.height)
                    if (dist > max(64f / baseScale, 48f)) activePerspectiveIndex = -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePerspectiveIndex in 0..3) {
                    val updatedPts = pts.toMutableList()
                    updatedPts[activePerspectiveIndex] = DrawPoint(np.x.coerceIn(-0.6f, 1.6f), np.y.coerceIn(-0.6f, 1.6f))
                    val updated = when (sel) {
                        is TextLayer -> sel.copy(perspectiveEnabled = true, perspectivePoints = updatedPts)
                        is ShapeLayer -> sel.copy(perspectiveEnabled = true, perspectivePoints = updatedPts)
                        is ImageLayer -> sel.copy(perspectiveEnabled = true, perspectivePoints = updatedPts)
                        else -> sel
                    }
                    onLayerTransform?.invoke(updated)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activePerspectiveIndex = -1
        }
    }

    private fun handleMaskTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } ?: return
        val pts = when (sel) {
            is TextLayer -> sel.maskPoints
            is ShapeLayer -> sel.maskPoints
            is ImageLayer -> sel.maskPoints
            else -> return
        }
        val np = projectToLayerNormalized(sel, px, py)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitIdx = pts.indexOfFirst {
                    hypot((it.x - np.x) * sel.width, (it.y - np.y) * sel.height) < max(42f / baseScale, 32f)
                }
                if (hitIdx >= 0) {
                    activeVertexIndex = hitIdx
                } else {
                    val nextPts = pts + np
                    val updated = when (sel) {
                        is TextLayer -> sel.copy(maskEnabled = nextPts.size >= 3, maskPoints = nextPts)
                        is ShapeLayer -> sel.copy(maskEnabled = nextPts.size >= 3, maskPoints = nextPts)
                        is ImageLayer -> sel.copy(maskEnabled = nextPts.size >= 3, maskPoints = nextPts)
                        else -> sel
                    }
                    onLayerTransform?.invoke(updated)
                    activeVertexIndex = nextPts.lastIndex
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeVertexIndex in pts.indices) {
                    val nextPts = pts.toMutableList()
                    nextPts[activeVertexIndex] = np
                    val updated = when (sel) {
                        is TextLayer -> sel.copy(maskPoints = nextPts)
                        is ShapeLayer -> sel.copy(maskPoints = nextPts)
                        is ImageLayer -> sel.copy(maskPoints = nextPts)
                        else -> sel
                    }
                    onLayerTransform?.invoke(updated)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeVertexIndex = -1
        }
    }

    private fun handleBezierTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? BezierLayer ?: return
        val np = projectToLayerNormalized(sel, px, py)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitIdx = sel.anchors.indexOfFirst {
                    hypot((it.x - np.x) * sel.width, (it.y - np.y) * sel.height) < max(44f / baseScale, 32f)
                }
                if (hitIdx >= 0) {
                    activeVertexIndex = hitIdx
                } else if (bezierActionMode == 0) {
                    val prev = sel.anchors.lastOrNull()
                    val offset = 0.08f
                    val newAnchor = if (prev != null) {
                        val dx = (np.x - prev.x) * 0.25f
                        val dy = (np.y - prev.y) * 0.25f
                        BezierAnchor(np.x, np.y, np.x - dx, np.y - dy, np.x + dx, np.y + dy)
                    } else {
                        BezierAnchor(np.x, np.y, np.x - offset, np.y, np.x + offset, np.y)
                    }
                    onLayerTransform?.invoke(sel.copy(anchors = sel.anchors + newAnchor))
                    activeVertexIndex = sel.anchors.size
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeVertexIndex in sel.anchors.indices) {
                    val list = sel.anchors.toMutableList()
                    val old = list[activeVertexIndex]
                    val dx = np.x - old.x
                    val dy = np.y - old.y
                    list[activeVertexIndex] = old.copy(
                        x = np.x,
                        y = np.y,
                        ctrlInX = old.ctrlInX + dx,
                        ctrlInY = old.ctrlInY + dy,
                        ctrlOutX = old.ctrlOutX + dx,
                        ctrlOutY = old.ctrlOutY + dy
                    )
                    onLayerTransform?.invoke(sel.copy(anchors = list))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeVertexIndex = -1
        }
    }

    private fun handleBitmapEraserTouch(event: MotionEvent, px: Float, py: Float) {
        val sel = project.layers.firstOrNull { it.id == selectedLayerId } as? ImageLayer ?: return
        val np = projectToLayerNormalized(sel, px, py)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeEraserPoints.clear()
                activeEraserPoints.add(np)
                eraserStrokeAdded = false
            }
            MotionEvent.ACTION_MOVE -> {
                activeEraserPoints.add(np)
                val previewStroke = DrawingStroke(
                    points = activeEraserPoints.toList(),
                    color = Color.TRANSPARENT,
                    width = bitmapEraserWidth,
                    eraser = true
                )
                val base = if (eraserStrokeAdded) sel.eraserStrokes.dropLast(1) else sel.eraserStrokes
                eraserStrokeAdded = true
                onLayerTransform?.invoke(sel.copy(eraserStrokes = base + previewStroke))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeEraserPoints.clear()
                eraserStrokeAdded = false
            }
        }
    }

    private fun snapAngle(deg: Float): Float {
        val snapTargets = floatArrayOf(0f, 90f, -90f, 180f, -180f)
        for (target in snapTargets) {
            if (abs(deg - target) <= 4f) return target
        }
        return deg
    }

    private fun snapToGuides(coord: Float, total: Float, guides: List<Float>): Float {
        val threshold = total * 0.02f
        if (abs(coord - total / 2f) < threshold) return total / 2f
        for (g in guides) {
            val pos = g * total
            if (abs(coord - pos) < threshold) return pos
        }
        return coord
    }

    private fun projectToOriginLocal(px: Float, py: Float): Pair<Float, Float> {
        val dx = px - origLayerX
        val dy = py - origLayerY
        val rad = -origRot * PI / 180.0
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return lx to ly
    }

    private fun originLocalShiftToCanvas(cxLocal: Float, cyLocal: Float): Pair<Float, Float> {
        val rad = origRot * PI / 180.0
        val nx = origLayerX + (cxLocal * cos(rad) - cyLocal * sin(rad)).toFloat()
        val ny = origLayerY + (cxLocal * sin(rad) + cyLocal * cos(rad)).toFloat()
        return nx to ny
    }

    private fun projectToLayerLocal(layer: CanvasLayer, px: Float, py: Float): Pair<Float, Float> {
        val dx = px - layer.x
        val dy = py - layer.y
        val rad = -layer.rotation * PI / 180.0
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return lx to ly
    }

    private fun checkHandleHit(layer: CanvasLayer, px: Float, py: Float): DragMode {
        val (lx, ly) = projectToLayerLocal(layer, px, py)
        val invScale = (1f / baseScale).coerceAtLeast(0.5f)

        val hw = layer.width / 2f
        val hh = layer.height / 2f
        val hitR = (30f / baseScale).coerceAtLeast(24f)

        if (layer is TextLayer) {
            // Bottom-center smile rotation arc
            val rotY = hh + 24f * invScale
            if (hypot(lx, ly - rotY) <= hitR * 1.15f) return DragMode.ROTATE
            // Bottom-left proportional scale handle
            if (hypot(lx + hw, ly - hh) <= hitR) return DragMode.RESIZE_CORNER
            // Right-center width wrap handle
            if (hypot(lx - hw, ly) <= hitR) return DragMode.RESIZE_WIDTH
        } else {
            // Bottom-left outer corner rotation arc
            val rotX = -hw - 12f * invScale
            val rotY = hh + 12f * invScale
            if (hypot(lx - rotX, ly - rotY) <= hitR * 1.2f || hypot(lx + hw, ly - hh) <= hitR * 0.9f) {
                return DragMode.ROTATE
            }
            // Bottom-right main proportional scale corner
            if (hypot(lx - hw, ly - hh) <= hitR) return DragMode.RESIZE_CORNER
            // Right-center width handle
            if (hypot(lx - hw, ly) <= hitR) return DragMode.RESIZE_WIDTH
            // Bottom-center height handle
            if (hypot(lx, ly - hh) <= hitR) return DragMode.RESIZE_HEIGHT
        }

        return DragMode.NONE
    }

    private fun hitTestLayer(px: Float, py: Float): CanvasLayer? {
        for (i in project.layers.indices.reversed()) {
            val l = project.layers[i]
            if (l.hidden) continue
            val dx = px - l.x
            val dy = py - l.y
            val rad = -l.rotation * PI / 180.0
            val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
            val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
            val pad = max(14f / baseScale, 10f)
            if (abs(lx) <= l.width / 2f + pad && abs(ly) <= l.height / 2f + pad) {
                return l
            }
        }
        return null
    }
}
