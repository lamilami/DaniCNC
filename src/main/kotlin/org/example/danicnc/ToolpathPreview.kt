package org.example.danicnc

import com.github.micycle1.clipper2.Clipper
import com.github.micycle1.clipper2.core.FillRule
import com.github.micycle1.clipper2.core.PathD
import com.github.micycle1.clipper2.core.PathsD
import com.github.micycle1.clipper2.core.PointD
import com.github.micycle1.clipper2.offset.EndType
import com.github.micycle1.clipper2.offset.JoinType
import kotlin.math.abs

data class PreviewPath(val points: DoubleArray, val closed: Boolean, val contourIds: List<Int>, val traversalKey: Long? = null,
                       val traversalOwnerId: Int? = null)
data class PreviewResult(val paths: List<PreviewPath> = emptyList(), val warnings: List<String> = emptyList())

/** XY cutter centre only. All offset, nesting normalization and topology operations use Clipper2. */
object ToolpathPreview {
    const val PRECISION = 6
    const val ARC_TOLERANCE_MM = 0.001

    fun calculate(contours: List<Contour>): PreviewResult {
        val paths = mutableListOf<PreviewPath>()
        val warnings = mutableListOf<String>()
        // Subpaths from one SVG element form a compound contour. Other elements keep independent settings.
        val groups = contours.groupBy { Triple(it.source, it.cutSettings.type, it.cutSettings.bitMm) }
        for ((_, group) in groups) {
            val first = group.first()
            val settings = first.cutSettings
            val label = "${first.source} (#${group.joinToString { it.id.toString() }})"
            if (settings.type == CutType.ON_PATH) {
                group.forEach { paths += PreviewPath(it.points.copyOf(), it.closed, listOf(it.id)) }
                continue
            }
            if (group.any { !it.closed }) {
                warnings += "$label: Inside/Outside/Pocket requires a closed contour."
                continue
            }
            // Do not silently reinterpret only part of a compound element as a filled area.
            if (contours.any { it.source == first.source && (it.cutSettings.type != settings.type || it.cutSettings.bitMm != settings.bitMm) }) {
                warnings += "$label: Compound contour has different Cut Type/Bit values; apply common settings to its subpaths."
                continue
            }
            try {
                val input = PathsD()
                group.forEach { c ->
                    require(c.points.all { it.isFinite() && abs(it) <= 1e8 }) { "Coordinates outside preview range" }
                    val p = PathD()
                    for (i in c.points.indices step 2) p.add(PointD(c.points[i], c.points[i + 1]))
                    if (p.size > 1 && p.first().x == p.last().x && p.first().y == p.last().y) p.removeAt(p.lastIndex)
                    input.add(p)
                }
                // EvenOdd gives opposite winding to holes regardless of SVG winding direction.
                val normalized = Clipper.union(input, null, FillRule.EvenOdd, PRECISION)
                require(normalized.isNotEmpty()) { "No nonzero closed area" }
                if (settings.type == CutType.POCKET) {
                    require(group.all { it.cutSettings == settings }) { "Pocket subpaths require common cutting settings" }
                    paths += SpiralPocket.calculate(normalized, settings, group.map { it.id })
                    continue
                }
                val radius = settings.bitMm / 2
                val delta = if (settings.type == CutType.INSIDE) -radius else radius
                val result = Clipper.inflatePaths(normalized, delta, JoinType.Round, EndType.Polygon,
                    2.0, ARC_TOLERANCE_MM, PRECISION)
                val inputIslands = normalized.count { Clipper.area(it) > 0 }
                val outputIslands = result.count { Clipper.area(it) > 0 }
                if (result.isEmpty() || (settings.type == CutType.INSIDE && outputIslands > inputIslands)) {
                    warnings += "$label: Tool Ø${settings.bitMm} mm cannot pass a narrow region; no preview generated for this operation."
                    continue
                }
                if (result.size < normalized.size) {
                    warnings += "$label: A boundary disappeared at tool Ø${settings.bitMm} mm; no preview generated for this operation."
                    continue
                }
                result.forEach { p ->
                    val xy = DoubleArray(p.size * 2)
                    p.forEachIndexed { i, point -> xy[2 * i] = point.x; xy[2 * i + 1] = point.y }
                    paths += PreviewPath(xy, true, group.map { it.id })
                }
            } catch (e: RuntimeException) {
                warnings += "$label: Cannot calculate preview: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return PreviewResult(ToolpathTraversal.apply(paths, contours), warnings)
    }
}
