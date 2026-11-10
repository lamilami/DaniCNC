package org.example.danicnc

import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.geometry.VPos
import javafx.scene.Cursor
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.input.MouseButton
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * 2D работна площ в машинни координати, mm: X надясно, Y нагоре, (0,0) = нулева точка.
 *
 * Мишка:
 *  - колелце = zoom около курсора;
 *  - влачене в празно (ляв бутон) или със среден бутон = pan;
 *  - клик върху контур = избор; Ctrl+клик = добавяне/премахване;
 *  - влачене на контур = преместване на целия избор (със Snap to Grid по желание).
 */
class WorkArea : Pane() {

    companion object {
        /** Пиксели на mm при 100% (96 dpi). */
        const val PX_PER_MM_100 = 96.0 / 25.4
        private const val ZOOM_STEP = 1.15
        private const val MIN_SCALE = 0.01
        private const val MAX_SCALE = 5000.0
        /** Радиус за улучване на контур, px. */
        private const val HIT_PX = 6.0
        /** Под това преместване натискането е клик, а не влачене, px. */
        private const val CLICK_PX = 3.0

        val CLOSED_COLOR: Color = Color.web("#1565c0")
        val OPEN_COLOR: Color = Color.web("#8e24aa")
        val SELECTED_COLOR: Color = Color.web("#ff6d00")
        val TOOLPATH_COLOR: Color = Color.web("#d5006d")
        private val HOVER_COLOR = Color.web("#00acc1")
        private val X_AXIS_COLOR = Color.web("#d32f2f")
        private val Y_AXIS_COLOR = Color.web("#2e7d32")
    }

    private val canvas = Canvas()

    // изглед: screenX = tx + x*s ; screenY = ty - y*s  (Y нагоре)
    private var s = PX_PER_MM_100
    private var tx = 0.0
    private var ty = 0.0

    private var drawing: SvgDrawing? = null
    var previewResult = PreviewResult()
        private set
    var previewVisible = true
        set(value) { field = value; redraw() }
    var onPreviewChanged: (PreviewResult) -> Unit = {}
    var onExportSelected: () -> Unit = {}
    val contourContextMenu = ContextMenu()

    /** Also used by keyboard/context-menu integration tests. Coordinates are local pixels. */
    fun prepareContourMenu(px: Double, py: Double): Boolean {
        val at=toMm(px,py)
        val visiblePaths=if(previewVisible) previewResult.paths else emptyList()
        val hitPath=visiblePaths.filter { ToolpathTraversal.distance(it,at)<=HIT_PX/s }
            .minByOrNull { ToolpathTraversal.distance(it,at) }
        val hitContour=hitTest(px,py)
        if(hitPath==null && hitContour==null) return false
        val path=hitPath ?: previewResult.paths.filter { hitContour!!.id in it.contourIds }
            .minByOrNull { ToolpathTraversal.distance(it,at) }
        val ids=hitPath?.contourIds ?: listOf(hitContour!!.id)
        if(selection.none { it.id in ids }) {
            selection.clear()
            selection.addAll(projectContours.filter { it.id in ids })
            onSelectionChanged(selected)
        }
        val start=MenuItem("Set Start Point Here").apply {
            isDisable=path==null
            setOnAction { path?.let { ToolpathTraversal.setStart(it,projectContours,at);refreshPreview() } }
        }
        val reverse=MenuItem("Reverse Direction").apply {
            isDisable=path==null
            setOnAction { path?.let { ToolpathTraversal.reverse(it,projectContours);refreshPreview() } }
        }
        val export=MenuItem("Export Selected G-code").apply { setOnAction { onExportSelected() } }
        contourContextMenu.items.setAll(start,reverse,export)
        redraw()
        return true
    }

    fun refreshPreview() {
        previewResult = ToolpathPreview.calculate(drawing?.contours ?: emptyList())
        onPreviewChanged(previewResult)
        redraw()
    }
    private var pendingFit = true

    private enum class Drag { NONE, PENDING_PAN, PENDING_MOVE, PAN, MOVE }
    private var drag = Drag.NONE
    private var pressPx: Point2D? = null
    private var lastPx: Point2D? = null
    private var pressMm: Point2D? = null
    private var moveStartBL: Point2D? = null
    private var dragCursorPx: Point2D? = null

    private var hover: Contour? = null
    private val selection = LinkedHashSet<Contour>()

    /** Snap to Grid при влачене. */
    var snapToGrid = true
        set(v) { field = v; redraw() }
    /** Стъпка на мрежата, mm (1, 5 или 10). */
    var gridStep = 10.0
        set(v) { require(v == 1.0 || v == 5.0 || v == 10.0); field = v; redraw() }

    /** Извиква се при всяка промяна на избора. */
    var onSelectionChanged: (List<Contour>) -> Unit = {}
    /** Извиква се, когато геометрията е преместена (при влачене — на всяка стъпка). */
    var onGeometryMoved: () -> Unit = {}

    val selected: List<Contour> get() = selection.toList()
    val projectContours: List<Contour> get() = drawing?.contours?.toList() ?: emptyList()

    /** Обхващащ правоъгълник на избора (mm, Y нагоре), null ако няма избор. */
    val selectionBounds: Bounds? get() = Contour.boundsOf(selection)

    private val zoomWrapper = ReadOnlyDoubleWrapper(100.0)
    /** Мащаб в % спрямо реален размер (96 dpi). */
    val zoomPercent = zoomWrapper.readOnlyProperty

    private val cursorWrapper = ReadOnlyObjectWrapper<Point2D?>(null)
    /** Позиция на курсора в mm (null извън площта). */
    val cursorMm = cursorWrapper.readOnlyProperty

    init {
        canvas.isManaged = false
        children.add(canvas)
        setOnContextMenuRequested { e ->
            contourContextMenu.hide()
            if(prepareContourMenu(e.x,e.y)) contourContextMenu.show(this,e.screenX,e.screenY)
            e.consume()
        }
        setMinSize(0.0, 0.0)
        setPrefSize(1000.0, 700.0)

        setOnScroll { e ->
            if (e.deltaY != 0.0) zoomAt(e.x, e.y, if (e.deltaY > 0) ZOOM_STEP else 1 / ZOOM_STEP)
            e.consume()
        }
        setOnMousePressed { e -> pressed(e.x, e.y, e.button, e.isShortcutDown) }
        setOnMouseDragged { e -> dragged(e.x, e.y) }
        setOnMouseReleased { e -> released(e.x, e.y, e.button, e.isShortcutDown) }
        setOnMouseMoved { e ->
            updateCursor(e.x, e.y)
            val h = hitTest(e.x, e.y)
            cursor = if (h != null && h in selection) Cursor.MOVE else Cursor.DEFAULT
            if (h !== hover) { hover = h; redraw() }
        }
        setOnMouseExited {
            cursorWrapper.set(null)
            if (hover != null) { hover = null; redraw() }
        }
    }

    fun setDrawing(d: SvgDrawing?) {
        drawing?.contours?.forEach { it.onCutSettingsChanged = {} }
        drawing = d
        d?.contours?.forEach { it.onCutSettingsChanged = ::refreshPreview }
        refreshPreview()
        hover = null
        selection.clear()
        onSelectionChanged(selected)
        fit()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        onSelectionChanged(selected)
        redraw()
    }

    /**
     * Премества избора така, че долният ляв ъгъл на обхващащия му правоъгълник
     * да е точно в (x, y) mm. Променя реалните координати на геометрията.
     */
    fun moveSelectionTo(x: Double, y: Double) {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite" }
        val b = selectionBounds ?: return
        val dx = x - b.minX
        val dy = y - b.minY
        selection.forEach { it.translate(dx, dy) }
        // компенсира грешки от закръгляне, за да е ъгълът точно в целта
        selectionBounds?.let { nb ->
            val ex = x - nb.minX; val ey = y - nb.minY
            if (ex != 0.0 || ey != 0.0) selection.forEach { it.translate(ex, ey) }
        }
        refreshPreview()
        onGeometryMoved()
        redraw()
    }

    // ------------------------------------------------------------ мишка

    private fun pressed(px: Double, py: Double, button: MouseButton, ctrl: Boolean) {
        pressPx = Point2D(px, py)
        lastPx = pressPx
        pressMm = toMm(px, py)
        drag = when (button) {
            MouseButton.MIDDLE -> Drag.PENDING_PAN
            MouseButton.PRIMARY -> {
                val hit = hitTest(px, py)
                if (hit != null && !ctrl) {
                    if (hit !in selection) { // натискане върху неизбран → избира само него
                        selection.clear(); selection += hit
                        onSelectionChanged(selected); redraw()
                    }
                    moveStartBL = selectionBounds?.let { Point2D(it.minX, it.minY) }
                    Drag.PENDING_MOVE
                } else if (hit == null) Drag.PENDING_PAN else Drag.NONE
            }
            else -> Drag.NONE
        }
    }

    private fun dragged(px: Double, py: Double) {
        updateCursor(px, py)
        val p0 = pressPx ?: return
        if ((drag == Drag.PENDING_PAN || drag == Drag.PENDING_MOVE) && hypot(px - p0.x, py - p0.y) > CLICK_PX) {
            drag = if (drag == Drag.PENDING_PAN) Drag.PAN else Drag.MOVE
            cursor = if (drag == Drag.PAN) Cursor.CLOSED_HAND else Cursor.MOVE
        }
        when (drag) {
            Drag.PAN -> {
                lastPx?.let { tx += px - it.x; ty += py - it.y }
                redraw()
            }
            Drag.MOVE -> {
                val start = moveStartBL ?: return
                val m0 = pressMm ?: return
                val m = toMm(px, py)
                var x = start.x + (m.x - m0.x)
                var y = start.y + (m.y - m0.y)
                if (snapToGrid) { x = snap(x); y = snap(y) }
                dragCursorPx = Point2D(px, py)
                moveSelectionTo(x, y)
            }
            else -> {}
        }
        lastPx = Point2D(px, py)
    }

    private fun released(px: Double, py: Double, button: MouseButton, ctrl: Boolean) {
        val wasDragging = drag == Drag.PAN || drag == Drag.MOVE
        if (!wasDragging && button == MouseButton.PRIMARY && pressPx != null) click(px, py, ctrl)
        drag = Drag.NONE
        pressPx = null
        dragCursorPx = null
        cursor = Cursor.DEFAULT
        redraw()
    }

    private fun click(px: Double, py: Double, toggle: Boolean) {
        val hit = hitTest(px, py)
        if (toggle) {
            if (hit == null) return
            if (!selection.remove(hit)) selection += hit
        } else {
            selection.clear()
            hit?.let { selection += it }
        }
        onSelectionChanged(selected)
        redraw()
    }

    /** Закръгля към най-близката линия на мрежата. */
    fun snap(v: Double): Double = round(v / gridStep) * gridStep + 0.0

    /** Най-близкият контур, чиято линия е в радиус HIT_PX от точката на екрана. */
    private fun hitTest(px: Double, py: Double): Contour? {
        val d = drawing ?: return null
        val p = toMm(px, py)
        val tol = HIT_PX / s
        var best: Contour? = null
        var bestDist = tol
        for (c in d.contours) {
            val b = c.bounds
            if (p.x < b.minX - tol || p.x > b.maxX + tol || p.y < b.minY - tol || p.y > b.maxY + tol) continue
            val dist = c.distanceTo(p.x, p.y)
            if (dist <= bestDist) { bestDist = dist; best = c }
        }
        return best
    }

    // ------------------------------------------------------------ изглед

    /** Побира листа и чертежа в прозореца. */
    fun fit() {
        if (width <= 0 || height <= 0) {
            pendingFit = true
            return
        }
        pendingFit = false
        val d = drawing
        var x0 = 0.0; var y0 = 0.0; var x1 = 100.0; var y1 = 100.0
        if (d != null) {
            val b = d.geometryBounds
            if (d.docWidthMm > 0 && d.docHeightMm > 0) {
                x0 = 0.0; y0 = 0.0; x1 = d.docWidthMm; y1 = d.docHeightMm
            } else if (b != null) {
                x0 = b.minX; y0 = b.minY; x1 = b.maxX; y1 = b.maxY
            }
            if (b != null) {
                x0 = min(x0, b.minX); y0 = min(y0, b.minY); x1 = max(x1, b.maxX); y1 = max(y1, b.maxY)
            }
        }
        // нулевата точка винаги да се вижда
        x0 = min(x0, 0.0); y0 = min(y0, 0.0); x1 = max(x1, 0.0); y1 = max(y1, 0.0)
        val bw = max(x1 - x0, 1e-3)
        val bh = max(y1 - y0, 1e-3)
        val margin = 60.0
        s = min((width - 2 * margin) / bw, (height - 2 * margin) / bh).coerceIn(MIN_SCALE, MAX_SCALE)
        tx = (width - bw * s) / 2 - x0 * s
        ty = (height + bh * s) / 2 + y0 * s
        redraw()
    }

    private fun zoomAt(px: Double, py: Double, factor: Double) {
        val newScale = (s * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = newScale / s
        tx = px - (px - tx) * f
        ty = py - (py - ty) * f
        s = newScale
        redraw()
        updateCursor(px, py)
    }

    private fun updateCursor(px: Double, py: Double) = cursorWrapper.set(toMm(px, py))

    override fun layoutChildren() {
        super.layoutChildren()
        canvas.width = width
        canvas.height = height
        if (pendingFit) fit() else redraw()
    }

    /** Екран (px) → mm. */
    fun toMm(px: Double, py: Double) = Point2D((px - tx) / s, (ty - py) / s)
    /** mm → локални координати на площта (px). */
    fun mmToLocal(x: Double, y: Double) = Point2D(sx(x), sy(y))

    private fun sx(x: Double) = tx + x * s
    private fun sy(y: Double) = ty - y * s

    // ------------------------------------------------------------ рисуване

    private fun redraw() {
        zoomWrapper.set(s / PX_PER_MM_100 * 100)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val g = canvas.graphicsContext2D
        g.fill = Color.web("#d9dde3")
        g.fillRect(0.0, 0.0, w, h)

        val d = drawing
        if (d != null && d.docWidthMm > 0 && d.docHeightMm > 0) {
            g.fill = Color.WHITE
            g.fillRect(sx(0.0), sy(d.docHeightMm), d.docWidthMm * s, d.docHeightMm * s)
            g.stroke = Color.web("#9aa3ad")
            g.lineWidth = 1.0
            g.strokeRect(sx(0.0), sy(d.docHeightMm), d.docWidthMm * s, d.docHeightMm * s)
        }

        val shownStep = drawGrid(g, w, h)
        drawAxes(g, w, h, shownStep)

        if (d != null) {
            g.lineJoin = StrokeLineJoin.ROUND
            g.lineCap = StrokeLineCap.ROUND
            for (c in d.contours) {
                if (c in selection || c === hover) continue
                drawContour(g, c, if (c.closed) CLOSED_COLOR else OPEN_COLOR, 1.5, w, h)
            }
            hover?.takeIf { it !in selection }?.let { drawContour(g, it, HOVER_COLOR, 2.5, w, h) }
            for (c in selection) drawContour(g, c, SELECTED_COLOR, if (c === hover) 3.5 else 3.0, w, h)

            val sb = selectionBounds
            if (sb != null) drawSelectionBox(g, sb)
            (sb ?: d.geometryBounds)?.let { b -> drawDimensions(g, b) }
        }

        if (previewVisible) {
            g.save()
            g.stroke = TOOLPATH_COLOR
            g.lineWidth = 2.0
            g.setLineDashes(7.0, 4.0)
            for (path in previewResult.paths) {
                val p = path.points
                if (p.size < 4) continue
                g.beginPath()
                g.moveTo(sx(p[0]), sy(p[1]))
                for (i in 2 until p.size step 2) g.lineTo(sx(p[i]), sy(p[i + 1]))
                if (path.closed) g.closePath()
                g.stroke()
                g.fill = Color.web("#008a35")
                g.fillOval(sx(p[0])-5,sy(p[1])-5,10.0,10.0)
                g.font=Font.font("System",FontWeight.BOLD,11.0)
                g.textAlign=TextAlignment.LEFT;g.textBaseline=VPos.BOTTOM
                g.fillText("START",sx(p[0])+8,sy(p[1])-6)
            }
            g.restore()
        }
        dragCursorPx?.let { p -> selectionBounds?.let { b -> drawDragLabel(g, p, b) } }

        g.fill = Color.web("#37474f")
        g.font = Font.font(11.0)
        g.textAlign = TextAlignment.LEFT
        g.textBaseline = VPos.BOTTOM
        val gridTxt = if (shownStep == gridStep) "grid ${fmt(gridStep)} mm" else "grid ${fmt(gridStep)} mm (shown ${fmt(shownStep)} mm)"
        g.fillText(gridTxt + if (snapToGrid) " · snap ON" else " · snap OFF", 8.0, h - 6)
    }

    private fun drawContour(g: GraphicsContext, c: Contour, color: Color, width: Double, w: Double, h: Double) {
        val b = c.bounds
        if (sx(b.maxX) < -10 || sx(b.minX) > w + 10 || sy(b.minY) < -10 || sy(b.maxY) > h + 10) return
        val p = c.points
        g.stroke = color
        g.lineWidth = width
        g.beginPath()
        g.moveTo(sx(p[0]), sy(p[1]))
        for (i in 1 until c.pointCount) g.lineTo(sx(p[2 * i]), sy(p[2 * i + 1]))
        if (c.closed) g.closePath()
        g.stroke()
        if (!c.closed) { // маркери за краищата на отворен контур
            g.fill = Color.WHITE
            g.lineWidth = 1.5
            for (i in intArrayOf(0, c.pointCount - 1)) {
                val x = sx(p[2 * i]); val y = sy(p[2 * i + 1])
                g.fillOval(x - 3.5, y - 3.5, 7.0, 7.0)
                g.strokeOval(x - 3.5, y - 3.5, 7.0, 7.0)
            }
        }
    }

    /**
     * Мрежа със стъпка [gridStep]. Ако линиите са по-гъсти от 6 px, се показва всяка 10-та.
     * Връща реално показаната стъпка.
     */
    private fun drawGrid(g: GraphicsContext, w: Double, h: Double): Double {
        var step = gridStep
        while (step * s < 6) step *= 10
        val major = step * 10
        val minorC = Color.rgb(0, 0, 0, 0.07)
        val majorC = Color.rgb(0, 0, 0, 0.18)
        g.lineWidth = 1.0
        var i = floor(-tx / s / step).toLong()
        while (i * step <= (w - tx) / s) {
            g.stroke = if (isMultiple(i * step, major)) majorC else minorC
            val p = pix(sx(i * step)); g.strokeLine(p, 0.0, p, h)
            i++
        }
        i = floor((ty - h) / s / step).toLong()
        while (i * step <= ty / s) {
            g.stroke = if (isMultiple(i * step, major)) majorC else minorC
            val p = pix(sy(i * step)); g.strokeLine(0.0, p, w, p)
            i++
        }
        return step
    }

    /** Осите X и Y през (0,0), стрелки, надписи, нулева точка и деления. */
    private fun drawAxes(g: GraphicsContext, w: Double, h: Double, step: Double) {
        val ox = sx(0.0)
        val oy = sy(0.0)
        g.lineWidth = 2.0
        // X
        g.stroke = X_AXIS_COLOR; g.fill = X_AXIS_COLOR
        if (oy in 0.0..h) {
            g.strokeLine(0.0, oy, w - 2, oy)
            g.fillPolygon(doubleArrayOf(w - 2, w - 14, w - 14), doubleArrayOf(oy, oy - 5, oy + 5), 3)
            g.font = Font.font("System", FontWeight.BOLD, 14.0)
            g.textAlign = TextAlignment.RIGHT; g.textBaseline = VPos.BOTTOM
            g.fillText("X", w - 6, oy - 6)
        }
        // Y
        g.stroke = Y_AXIS_COLOR; g.fill = Y_AXIS_COLOR
        if (ox in 0.0..w) {
            g.strokeLine(ox, h, ox, 2.0)
            g.fillPolygon(doubleArrayOf(ox, ox - 5, ox + 5), doubleArrayOf(2.0, 14.0, 14.0), 3)
            g.font = Font.font("System", FontWeight.BOLD, 14.0)
            g.textAlign = TextAlignment.LEFT; g.textBaseline = VPos.TOP
            g.fillText("Y", ox + 8, 4.0)
        }

        // числа по осите на всяка основна линия (поне ~60 px една от друга)
        var labelStep = step * 10
        while (labelStep * s < 60) labelStep *= if (labelStep.toString().startsWith("1")) 5.0 else 2.0
        g.font = Font.font(10.5)
        val ly = oy.coerceIn(4.0, h - 18)
        g.fill = Color.web("#b71c1c")
        g.textAlign = TextAlignment.CENTER; g.textBaseline = VPos.TOP
        var i = ceil(-tx / s / labelStep).toLong()
        while (i * labelStep <= (w - tx) / s) {
            val v = i * labelStep
            if (v != 0.0) {
                val x = sx(v)
                g.fillText(fmt(v), x, ly + 4)
                g.stroke = X_AXIS_COLOR; g.lineWidth = 1.0
                if (oy in 0.0..h) g.strokeLine(pix(x), oy - 4, pix(x), oy + 4)
            }
            i++
        }
        val lx = ox.coerceIn(30.0, w - 4)
        g.fill = Color.web("#1b5e20")
        g.textAlign = TextAlignment.RIGHT; g.textBaseline = VPos.CENTER
        i = ceil((ty - h) / s / labelStep).toLong()
        while (i * labelStep <= ty / s) {
            val v = i * labelStep
            if (v != 0.0) {
                val y = sy(v)
                g.fillText(fmt(v), lx - 6, y)
                g.stroke = Y_AXIS_COLOR; g.lineWidth = 1.0
                if (ox in 0.0..w) g.strokeLine(ox - 4, pix(y), ox + 4, pix(y))
            }
            i++
        }

        // нулева точка
        if (ox in -10.0..w + 10 && oy in -10.0..h + 10) {
            g.fill = Color.WHITE
            g.fillOval(ox - 6, oy - 6, 12.0, 12.0)
            g.stroke = Color.BLACK; g.lineWidth = 2.0
            g.strokeOval(ox - 6, oy - 6, 12.0, 12.0)
            g.fill = Color.BLACK
            g.fillOval(ox - 2, oy - 2, 4.0, 4.0)
            g.font = Font.font("System", FontWeight.BOLD, 11.0)
            g.textAlign = TextAlignment.LEFT; g.textBaseline = VPos.TOP
            g.fillText("0,0", ox + 8, oy + 6)
        }
    }

    /** Пунктирана рамка на избора и маркер в долния ляв ъгъл (опорната точка). */
    private fun drawSelectionBox(g: GraphicsContext, b: Bounds) {
        g.stroke = SELECTED_COLOR
        g.lineWidth = 1.0
        g.setLineDashes(5.0, 4.0)
        g.strokeRect(sx(b.minX), sy(b.maxY), b.width * s, b.height * s)
        g.setLineDashes()
        val x = sx(b.minX); val y = sy(b.minY)
        g.fill = SELECTED_COLOR
        g.fillRect(x - 4, y - 4, 8.0, 8.0)
    }

    private fun drawDragLabel(g: GraphicsContext, p: Point2D, b: Bounds) {
        val text = "X ${mm(b.minX)}   Y ${mm(b.minY)}"
        g.font = Font.font("System", FontWeight.BOLD, 12.0)
        val tw = text.length * 7.2 + 12
        val x = (p.x + 16).coerceAtMost(width - tw - 4)
        val y = (p.y + 18).coerceAtMost(height - 26)
        g.fill = Color.rgb(33, 33, 33, 0.85)
        g.fillRoundRect(x, y, tw, 22.0, 6.0, 6.0)
        g.fill = Color.WHITE
        g.textAlign = TextAlignment.LEFT; g.textBaseline = VPos.CENTER
        g.fillText(text, x + 6, y + 11)
    }

    private fun drawDimensions(g: GraphicsContext, b: Bounds) {
        val x0 = b.minX; val x1 = b.maxX; val yTop = b.maxY; val yBot = b.minY
        val c = Color.web("#e65100")
        g.stroke = c
        g.fill = c
        g.lineWidth = 1.0
        g.font = Font.font(12.0)
        val off = 18.0
        val yd = sy(yTop) - off
        g.strokeLine(sx(x0), sy(yTop) - 4, sx(x0), yd - 4)
        g.strokeLine(sx(x1), sy(yTop) - 4, sx(x1), yd - 4)
        g.strokeLine(sx(x0), yd, sx(x1), yd)
        arrow(g, sx(x0), yd, 1.0, 0.0); arrow(g, sx(x1), yd, -1.0, 0.0)
        g.textAlign = TextAlignment.CENTER
        g.textBaseline = VPos.BOTTOM
        g.fillText("${mm(x1 - x0)} mm", (sx(x0) + sx(x1)) / 2, yd - 3)
        val xd = sx(x1) + off
        g.strokeLine(sx(x1) + 4, sy(yTop), xd + 4, sy(yTop))
        g.strokeLine(sx(x1) + 4, sy(yBot), xd + 4, sy(yBot))
        g.strokeLine(xd, sy(yTop), xd, sy(yBot))
        arrow(g, xd, sy(yTop), 0.0, 1.0); arrow(g, xd, sy(yBot), 0.0, -1.0)
        g.textAlign = TextAlignment.LEFT
        g.textBaseline = VPos.CENTER
        g.fillText("${mm(yTop - yBot)} mm", xd + 6, (sy(yTop) + sy(yBot)) / 2)
    }

    private fun arrow(g: GraphicsContext, x: Double, y: Double, dx: Double, dy: Double) {
        val l = 7.0; val w = 3.0
        g.fillPolygon(
            doubleArrayOf(x, x + dx * l - dy * w, x + dx * l + dy * w),
            doubleArrayOf(y, y + dy * l + dx * w, y + dy * l - dx * w), 3,
        )
    }

    private fun pix(v: Double) = floor(v) + 0.5
    private fun isMultiple(v: Double, m: Double) = abs(v / m - (v / m).roundToLong()) < 1e-6

    private fun fmt(v: Double): String =
        if (abs(v - v.roundToLong()) < 1e-9) v.roundToLong().toString() else mm(v)

    private fun mm(v: Double) = String.format(Locale.ROOT, "%.2f", v + 0.0)
}
