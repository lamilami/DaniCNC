package org.example.danicnc

import com.github.micycle1.clipper2.Clipper
import com.github.micycle1.clipper2.core.*
import com.github.micycle1.clipper2.offset.EndType
import com.github.micycle1.clipper2.offset.JoinType
import kotlin.math.abs
import kotlin.math.hypot

/** Complete offset rings, joined only where the entire cutter fits inside the pocket. */
internal object SpiralPocket {
    fun calculate(area: PathsD, settings: CutSettings, ids: List<Int>): List<PreviewPath> {
        val radius = settings.bitMm / 2
        val step = settings.bitMm * settings.stepOverPercent / 100
        val result = mutableListOf<MutableList<PointD>>()
        var distance = radius
        var count = 0
        while (true) {
            val rings = Clipper.inflatePaths(area, -distance, JoinType.Round, EndType.Polygon,
                2.0, ToolpathPreview.ARC_TOLERANCE_MM, ToolpathPreview.PRECISION)
            if (rings.isEmpty()) break
            require(++count <= 10000) { "Pocket exceeds 10000 offset levels" }
            for (ring in rings) {
                if (ring.size < 3) continue
                var chain: MutableList<PointD>? = null
                var start = 0
                // Keep disconnected regions and islands separate unless a cutter-width link fits.
                val candidates = result.map { path ->
                    val end = path.last()
                    val index = ring.indices.minBy { hypot(end.x-ring[it].x, end.y-ring[it].y) }
                    Triple(path, index, hypot(end.x-ring[index].x, end.y-ring[index].y))
                }.sortedBy { it.third }
                for ((path, index, _) in candidates) {
                    val link = PathD().apply { add(path.last()); add(ring[index]) }
                    val links = PathsD().apply { add(link) }
                    val swept = Clipper.inflatePaths(links, radius - 0.00001, JoinType.Round, EndType.Round,
                        2.0, ToolpathPreview.ARC_TOLERANCE_MM, ToolpathPreview.PRECISION)
                    val outside = Clipper.difference(swept, area, FillRule.NonZero, ToolpathPreview.PRECISION)
                    if (outside.sumOf { abs(Clipper.area(it)) } <= 1e-8) {
                        chain = path; start = index; break
                    }
                }
                val target = chain ?: mutableListOf<PointD>().also { result.add(it) }
                for (i in 0..ring.size) target.add(ring[(start+i)%ring.size])
                require(result.sumOf { it.size } <= 200000) { "Pocket exceeds 200000 points" }
            }
            distance += step
        }
        require(result.isNotEmpty()) { "Tool cannot fit inside this pocket" }
        return result.map { chain ->
            val xy = DoubleArray(chain.size*2)
            chain.forEachIndexed { i, p -> xy[2*i]=p.x; xy[2*i+1]=p.y }
            PreviewPath(xy, false, ids)
        }
    }
}
