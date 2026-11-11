package org.example.danicnc

import javafx.geometry.Bounds
import javafx.scene.transform.Affine
import javafx.scene.transform.Rotate
import javafx.scene.transform.Scale
import javafx.scene.transform.Shear
import javafx.scene.transform.Transform
import javafx.scene.transform.Translate
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min
import kotlin.math.tan

/**
 * Зареден SVG чертеж — списък векторни контури в машинни координати, mm.
 * X надясно, Y нагоре; (0,0) = долният ляв ъгъл на листа.
 */
class SvgDrawing(
    val contours: List<Contour>,
    /** Размер на документа (width/height на <svg>) в mm; 0 ако не е зададен. */
    val docWidthMm: Double,
    val docHeightMm: Double,
    /** Пропуснати (неподдържани) елементи, за информация. */
    val skipped: Map<String, Int>,
    /** Original SVG bytes travel with a project; working geometry is saved separately. */
    val originalSvg: ByteArray? = null,
) {
    /** Текущ обхват на геометрията в mm (променя се при преместване). */
    val geometryBounds: Bounds? get() = Contour.boundsOf(contours)
}

object SvgLoader {
    /** SVG потребителска единица (px) = 1/96 inch. */
    const val MM_PER_PX = 25.4 / 96.0

    private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
    private val LENGTH = Regex("""^\s*([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)\s*([a-zA-Z%]*)\s*$""")
    private val TRANSFORM = Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""")
    private val IGNORED = setOf("defs", "title", "desc", "metadata", "style", "script", "clipPath", "mask",
        "symbol", "marker", "pattern", "linearGradient", "radialGradient", "filter", "namedview")

    fun load(file: File): SvgDrawing {
        val originalSvg = file.readBytes()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val svg = factory.newDocumentBuilder().parse(originalSvg.inputStream()).documentElement
        require(svg.name() == "svg") { "Файлът не е SVG (коренов елемент <${svg.name()}>)" }

        // --- мащаб от потребителски единици към mm ---
        val widthMm = lengthMm(svg.getAttribute("width"))
        val heightMm = lengthMm(svg.getAttribute("height"))
        val vb = numbers(svg.getAttribute("viewBox")).takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
        val rootCtm = Affine()
        val docW: Double
        val docH: Double
        if (vb != null) {
            docW = widthMm ?: heightMm?.let { it * vb[2] / vb[3] } ?: (vb[2] * MM_PER_PX)
            docH = heightMm ?: widthMm?.let { it * vb[3] / vb[2] } ?: (vb[3] * MM_PER_PX)
            val sx = docW / vb[2]
            val sy = docH / vb[3]
            if (svg.getAttribute("preserveAspectRatio").trim().startsWith("none")) {
                rootCtm.append(Scale(sx, sy))
            } else { // по подразбиране xMidYMid meet
                val s = min(sx, sy)
                rootCtm.append(Translate((docW - vb[2] * s) / 2, (docH - vb[3] * s) / 2))
                rootCtm.append(Scale(s, s))
            }
            rootCtm.append(Translate(-vb[0], -vb[1]))
        } else {
            docW = widthMm ?: 0.0
            docH = heightMm ?: 0.0
            rootCtm.append(Scale(MM_PER_PX, MM_PER_PX))
        }

        val ctx = Ctx()
        svg.childElements().forEach { convert(it, rootCtm, ctx) }

        // SVG е с Y надолу → машинни координати с Y нагоре: y' = H - y,
        // така долният ляв ъгъл на листа става (0,0).
        val flipH = if (docH > 0) docH else ctx.raw.maxOfOrNull { r -> r.pts.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull() ?: 0.0 } ?: 0.0
        val contours = ctx.raw.mapIndexed { i, r ->
            val p = r.pts
            for (k in 1 until p.size step 2) p[k] = flipH - p[k]
            Contour(i + 1, r.source, p, r.closed)
        }
        return SvgDrawing(contours, docW, docH, ctx.skipped, originalSvg)
    }

    private class Raw(val source: String, val pts: DoubleArray, val closed: Boolean)

    private class Ctx {
        val raw = mutableListOf<Raw>()
        val skipped = linkedMapOf<String, Int>()
        val counters = mutableMapOf<String, Int>()
    }

    private fun convert(el: Element, parentCtm: Affine, ctx: Ctx) {
        val name = el.name()
        if (name in IGNORED) return
        if (prop(el, "display") == "none" || prop(el, "visibility") == "hidden") return

        val ctm = Affine(parentCtm).apply { parseTransform(el.getAttribute("transform")).forEach { append(it) } }

        if (name == "g" || name == "a" || name == "switch" || name == "svg") {
            el.childElements().forEach { convert(it, ctm, ctx) }
            return
        }

        val idx = (ctx.counters[name] ?: 0) + 1
        val label = el.getAttribute("id").takeIf { it.isNotBlank() }?.let { "$name #$it" } ?: "$name $idx"
        val b = ContourBuilder(ctm, label) { src, pts, closed ->
            ctx.raw += Raw(src, pts, closed)
        }

        when (name) {
            "rect" -> {
                val w = len(el, "width"); val h = len(el, "height")
                if (w <= 0 || h <= 0) return
                val rx = el.getAttribute("rx").takeIf { it.isNotBlank() }?.let { userLength(it) }
                val ry = el.getAttribute("ry").takeIf { it.isNotBlank() }?.let { userLength(it) }
                b.rect(len(el, "x"), len(el, "y"), w, h, rx ?: ry ?: 0.0, ry ?: rx ?: 0.0)
            }
            "circle" -> len(el, "r").let { r -> b.ellipse(len(el, "cx"), len(el, "cy"), r, r) }
            "ellipse" -> b.ellipse(len(el, "cx"), len(el, "cy"), len(el, "rx"), len(el, "ry"))
            "line" -> {
                b.moveTo(len(el, "x1"), len(el, "y1"))
                b.lineTo(len(el, "x2"), len(el, "y2"))
                b.flush()
            }
            "polyline", "polygon" -> {
                val p = numbers(el.getAttribute("points"))
                if (p.size < 4) return
                b.moveTo(p[0], p[1])
                for (i in 1 until p.size / 2) b.lineTo(p[2 * i], p[2 * i + 1])
                if (name == "polygon") b.close() else b.flush()
            }
            "path" -> {
                val d = el.getAttribute("d")
                if (d.isBlank()) return
                try {
                    PathDataParser.parse(d, b)
                } catch (e: IllegalArgumentException) {
                    b.flush() // частично прочетеното остава; грешката в path data не спира целия файл
                }
            }
            else -> {
                ctx.skipped.merge(name, 1, Int::plus) // text, image, use, ...
                return
            }
        }
        ctx.counters[name] = idx
    }

    private fun parseTransform(s: String): List<Transform> =
        TRANSFORM.findAll(s).mapNotNull { m ->
            val a = numbers(m.groupValues[2])
            when (m.groupValues[1]) {
                "matrix" -> if (a.size == 6) Affine(a[0], a[2], a[4], a[1], a[3], a[5]) else null
                "translate" -> Translate(a.getOrElse(0) { 0.0 }, a.getOrElse(1) { 0.0 })
                "scale" -> a.getOrElse(0) { 1.0 }.let { sx -> Scale(sx, a.getOrElse(1) { sx }) }
                "rotate" -> Rotate(a.getOrElse(0) { 0.0 }, a.getOrElse(1) { 0.0 }, a.getOrElse(2) { 0.0 })
                "skewX" -> Shear(tan(Math.toRadians(a.getOrElse(0) { 0.0 })), 0.0)
                "skewY" -> Shear(0.0, tan(Math.toRadians(a.getOrElse(0) { 0.0 })))
                else -> null
            }
        }.toList()

    /** Дължина с мерна единица → mm. null за празно или проценти. */
    fun lengthMm(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = LENGTH.matchEntire(s) ?: return null
        val v = m.groupValues[1].toDouble()
        val k = when (m.groupValues[2].lowercase()) {
            "mm" -> 1.0
            "cm" -> 10.0
            "in" -> 25.4
            "pt" -> 25.4 / 72
            "pc" -> 25.4 / 6
            "px", "" -> MM_PER_PX
            else -> return null
        }
        return v * k
    }

    /** Дължина → потребителски единици (px). */
    private fun userLength(s: String): Double = (lengthMm(s) ?: 0.0) / MM_PER_PX

    private fun len(el: Element, attr: String) = userLength(el.getAttribute(attr))

    private fun numbers(s: String?): DoubleArray =
        if (s.isNullOrBlank()) DoubleArray(0) else NUMBER.findAll(s).map { it.value.toDouble() }.toList().toDoubleArray()

    /** Стойност от style="..." или от атрибут. */
    private fun prop(el: Element, name: String): String? {
        el.getAttribute("style").split(';').forEach { decl ->
            val i = decl.indexOf(':')
            if (i > 0 && decl.substring(0, i).trim() == name) return decl.substring(i + 1).trim()
        }
        return el.getAttribute(name).takeIf { it.isNotBlank() }?.trim()
    }

    private fun Element.name(): String = localName ?: nodeName.substringAfter(':')

    private fun Element.childElements(): List<Element> {
        val list = childNodes
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try { setFeature(name, value) } catch (_: Exception) { }
    }
}
