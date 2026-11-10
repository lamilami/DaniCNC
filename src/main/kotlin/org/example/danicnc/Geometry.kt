package org.example.danicnc

import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.transform.Affine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Един векторен контур (subpath) в машинни координати, mm (X надясно, Y нагоре).
 * [points] = x0,y0,x1,y1,... ; при затворен контур последната точка съвпада с първата.
 * Формата е фиксирана; положението се променя само чрез [translate].
 */
class Contour(
    val id: Int,
    val source: String,
    private val pts: DoubleArray,
    val closed: Boolean,
) {
    var cutSettings: CutSettings = CutSettings()
        internal set(value) { field = value; onCutSettingsChanged() }
    internal var onCutSettingsChanged: () -> Unit = {}
    /** Traversal edits belong to the project, never to the original SVG points. */
    internal val pathTraversals = mutableListOf<PathTraversal>()

    /** Точките само за четене (не променяй масива отвън — ползвай [translate]). */
    val points: DoubleArray get() = pts

    val pointCount get() = pts.size / 2

    val length: Double = run {
        var l = 0.0
        for (i in 1 until pointCount) {
            l += hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1])
        }
        l
    }

    var bounds: Bounds = computeBounds()
        private set

    /** Премества реалните координати на контура. */
    fun translate(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        for (i in 0 until pointCount) {
            pts[2 * i] += dx
            pts[2 * i + 1] += dy
        }
        bounds = computeBounds()
    }

    private fun computeBounds(): Bounds {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until pointCount) {
            x0 = min(x0, pts[2 * i]); x1 = max(x1, pts[2 * i])
            y0 = min(y0, pts[2 * i + 1]); y1 = max(y1, pts[2 * i + 1])
        }
        return BoundingBox(x0, y0, x1 - x0, y1 - y0)
    }

    companion object {
        /** Общ обхващащ правоъгълник на група контури. */
        fun boundsOf(list: Collection<Contour>): Bounds? {
            if (list.isEmpty()) return null
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            list.forEach {
                x0 = min(x0, it.bounds.minX); y0 = min(y0, it.bounds.minY)
                x1 = max(x1, it.bounds.maxX); y1 = max(y1, it.bounds.maxY)
            }
            return BoundingBox(x0, y0, x1 - x0, y1 - y0)
        }
    }

    /** Най-късо разстояние от точка (mm) до линията на контура. */
    fun distanceTo(x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 1 until pointCount) {
            best = min(best, segDist(x, y, pts[2 * i - 2], pts[2 * i - 1], pts[2 * i], pts[2 * i + 1]))
        }
        return if (pointCount == 1) hypot(x - pts[0], y - pts[1]) else best
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

/**
 * Строи контури от команди moveTo/lineTo/cubicTo/close в потребителски координати.
 * Всяка точка се трансформира с [ctm] към mm, кривите се разбиват на отсечки.
 */
class ContourBuilder(
    private val ctm: Affine,
    private val source: String,
    private val sink: (source: String, pts: DoubleArray, closed: Boolean) -> Unit,
) {
    companion object {
        /** Максимална дължина на отсечка при разбиване на криви, mm. */
        private const val FLATTEN_STEP_MM = 0.1
        private const val MAX_SEGMENTS = 2000
        /** Толеранс за съвпадение на начало/край → затворен контур, mm. */
        const val CLOSE_TOL_MM = 0.01
    }

    private val pts = ArrayList<Double>()
    private var startX = 0.0; private var startY = 0.0 // начало на subpath (user)
    var curX = 0.0; private set
    var curY = 0.0; private set

    fun moveTo(x: Double, y: Double) {
        flush(false)
        startX = x; startY = y; curX = x; curY = y
        addMm(x, y)
    }

    fun lineTo(x: Double, y: Double) {
        if (pts.isEmpty()) moveTo(curX, curY)
        addMm(x, y)
        curX = x; curY = y
    }

    fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        if (pts.isEmpty()) moveTo(curX, curY)
        val p0 = ctm.transform(curX, curY)
        val p1 = ctm.transform(x1, y1)
        val p2 = ctm.transform(x2, y2)
        val p3 = ctm.transform(x, y)
        val ctrlLen = p0.distance(p1) + p1.distance(p2) + p2.distance(p3)
        val n = ceil(ctrlLen / FLATTEN_STEP_MM).toInt().coerceIn(2, MAX_SEGMENTS)
        for (i in 1..n) {
            val t = i.toDouble() / n
            val u = 1 - t
            val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
            pts += a * p0.x + b * p1.x + c * p2.x + d * p3.x
            pts += a * p0.y + b * p1.y + c * p2.y + d * p3.y
        }
        curX = x; curY = y
    }

    fun quadTo(x1: Double, y1: Double, x: Double, y: Double) {
        cubicTo(
            curX + 2.0 / 3 * (x1 - curX), curY + 2.0 / 3 * (y1 - curY),
            x + 2.0 / 3 * (x1 - x), y + 2.0 / 3 * (y1 - y), x, y,
        )
    }

    /** Елиптична дъга по спецификацията на SVG (F.6.5). */
    fun arcTo(rxIn: Double, ryIn: Double, phiDeg: Double, largeArc: Boolean, sweep: Boolean, x: Double, y: Double) {
        var rx = abs(rxIn); var ry = abs(ryIn)
        val x0 = curX; val y0 = curY
        if (x0 == x && y0 == y) return
        if (rx == 0.0 || ry == 0.0) { lineTo(x, y); return }
        val phi = Math.toRadians(phiDeg % 360)
        val cosP = cos(phi); val sinP = sin(phi)
        val dx2 = (x0 - x) / 2; val dy2 = (y0 - y) / 2
        val x1p = cosP * dx2 + sinP * dy2
        val y1p = -sinP * dx2 + cosP * dy2
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(max(0.0, num / den))
        if (largeArc == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cosP * cxp - sinP * cyp + (x0 + x) / 2
        val cy = sinP * cxp + cosP * cyp + (y0 + y) / 2
        val theta1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var dTheta = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - theta1
        if (sweep && dTheta < 0) dTheta += 2 * PI
        if (!sweep && dTheta > 0) dTheta -= 2 * PI
        arcCenter(cx, cy, rx, ry, phi, theta1, dTheta)
        curX = x; curY = y
    }

    /**
     * Дъга с център: точки директно от уравнението на елипсата (без апроксимация с Безие),
     * трансформирани към mm. Стъпката е ≈ FLATTEN_STEP_MM по дъгата.
     */
    private fun arcCenter(cx: Double, cy: Double, rx: Double, ry: Double, phi: Double, start: Double, delta: Double) {
        if (pts.isEmpty()) moveTo(curX, curY)
        val cosP = cos(phi); val sinP = sin(phi)
        fun pointAt(a: Double): javafx.geometry.Point2D {
            val ex = rx * cos(a); val ey = ry * sin(a)
            return ctm.transform(cx + ex * cosP - ey * sinP, cy + ex * sinP + ey * cosP)
        }
        // груба оценка на дължината в mm → брой отсечки
        var est = 0.0
        var prev = pointAt(start)
        for (i in 1..32) {
            val p = pointAt(start + delta * i / 32)
            est += prev.distance(p); prev = p
        }
        val n = max(ceil(est / FLATTEN_STEP_MM).toInt(), ceil(abs(delta) / (PI / 36)).toInt()).coerceIn(2, MAX_SEGMENTS * 4)
        for (i in 1..n) {
            val p = pointAt(start + delta * i / n)
            pts += p.x; pts += p.y
        }
    }

    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double) {
        if (rx <= 0 || ry <= 0) return
        moveTo(cx + rx, cy)
        arcCenter(cx, cy, rx, ry, 0.0, 0.0, 2 * PI)
        curX = cx + rx; curY = cy
        close()
    }

    fun close() {
        if (pts.isEmpty()) return
        lineTo(startX, startY)
        flush(true)
        curX = startX; curY = startY
    }

    /** Приключва текущия subpath. */
    fun flush(closedByCommand: Boolean = false) {
        if (pts.size >= 4) {
            val arr = pts.toDoubleArray()
            val n = arr.size
            val coincident = hypot(arr[0] - arr[n - 2], arr[1] - arr[n - 1]) <= CLOSE_TOL_MM
            val hasLength = arr.size > 4 || hypot(arr[0] - arr[2], arr[1] - arr[3]) > 0
            if (hasLength) sink(source, arr, closedByCommand || (coincident && n >= 8))
        }
        pts.clear()
    }

    private fun addMm(x: Double, y: Double) {
        val p = ctm.transform(x, y)
        val n = pts.size
        if (n >= 2 && pts[n - 2] == p.x && pts[n - 1] == p.y) return
        pts += p.x; pts += p.y
    }

    fun rect(x: Double, y: Double, w: Double, h: Double, rxIn: Double, ryIn: Double) {
        val rx = min(rxIn, w / 2); val ry = min(ryIn, h / 2)
        if (rx <= 0 || ry <= 0) {
            moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
            return
        }
        moveTo(x + rx, y)
        lineTo(x + w - rx, y); arcTo(rx, ry, 0.0, false, true, x + w, y + ry)
        lineTo(x + w, y + h - ry); arcTo(rx, ry, 0.0, false, true, x + w - rx, y + h)
        lineTo(x + rx, y + h); arcTo(rx, ry, 0.0, false, true, x, y + h - ry)
        lineTo(x, y + ry); arcTo(rx, ry, 0.0, false, true, x + rx, y)
        close()
    }
}

/** Парсер на SVG path data (d="..."), подава команди към [ContourBuilder]. */
object PathDataParser {

    fun parse(d: String, b: ContourBuilder) {
        val t = Tokenizer(d)
        var cmd = ' '
        var lastCtrlX = 0.0; var lastCtrlY = 0.0
        var lastWasCubic = false; var lastWasQuad = false

        while (true) {
            t.skipSeparators()
            if (t.atEnd()) break
            if (t.peekIsCommand()) cmd = t.nextChar()
            else if (cmd == ' ') throw IllegalArgumentException("Path data трябва да започва с команда")

            val rel = cmd.isLowerCase()
            val ox = if (rel) b.curX else 0.0
            val oy = if (rel) b.curY else 0.0
            var cubic = false; var quad = false

            when (cmd.uppercaseChar()) {
                'M' -> {
                    b.moveTo(ox + t.num(), oy + t.num())
                    cmd = if (rel) 'l' else 'L' // следващите двойки са lineto
                }
                'L' -> b.lineTo(ox + t.num(), oy + t.num())
                'H' -> b.lineTo(ox + t.num(), b.curY)
                'V' -> b.lineTo(b.curX, oy + t.num())
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    b.cubicTo(x1, y1, x2, y2, ox + t.num(), oy + t.num())
                    lastCtrlX = x2; lastCtrlY = y2; cubic = true
                }
                'S' -> {
                    val x1 = if (lastWasCubic) 2 * b.curX - lastCtrlX else b.curX
                    val y1 = if (lastWasCubic) 2 * b.curY - lastCtrlY else b.curY
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    b.cubicTo(x1, y1, x2, y2, ox + t.num(), oy + t.num())
                    lastCtrlX = x2; lastCtrlY = y2; cubic = true
                }
                'Q' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    b.quadTo(x1, y1, ox + t.num(), oy + t.num())
                    lastCtrlX = x1; lastCtrlY = y1; quad = true
                }
                'T' -> {
                    val x1 = if (lastWasQuad) 2 * b.curX - lastCtrlX else b.curX
                    val y1 = if (lastWasQuad) 2 * b.curY - lastCtrlY else b.curY
                    b.quadTo(x1, y1, ox + t.num(), oy + t.num())
                    lastCtrlX = x1; lastCtrlY = y1; quad = true
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    b.arcTo(rx, ry, rot, large, sweep, ox + t.num(), oy + t.num())
                }
                'Z' -> {
                    b.close()
                    cmd = ' ' // след Z трябва нова команда
                    t.skipSeparators()
                    if (!t.atEnd() && !t.peekIsCommand()) cmd = if (rel) 'l' else 'L'
                }
                else -> throw IllegalArgumentException("Непозната команда '$cmd'")
            }
            lastWasCubic = cubic
            lastWasQuad = quad
        }
        b.flush()
    }

    private class Tokenizer(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length
        fun skipSeparators() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun peekIsCommand() = s[i].isLetter() && s[i] != 'e' && s[i] != 'E'
        fun nextChar() = s[i++]

        fun flag(): Boolean {
            skipSeparators()
            require(i < s.length && (s[i] == '0' || s[i] == '1')) { "Очакван флаг 0/1 в позиция $i" }
            return s[i++] == '1'
        }

        fun num(): Double {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var dot = false
            while (i < s.length && (s[i].isDigit() || (s[i] == '.' && !dot))) { if (s[i] == '.') dot = true; i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            require(i > start) { "Очаквано число в позиция $start" }
            return s.substring(start, i).toDouble()
        }
    }
}
