package org.example.danicnc

import com.github.micycle1.clipper2.Clipper
import com.github.micycle1.clipper2.core.*
import com.github.micycle1.clipper2.offset.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SpiralPocketTest {
    private fun contour(id: Int, xy: DoubleArray) = Contour(id,"pocket",xy,true).also {
        it.cutSettings = CutSettings(type=CutType.POCKET,bitMm=2.0,depthMm=2.5,passDepthMm=1.0)
    }
    private fun polygon(xy: DoubleArray) = PathD().apply {
        for (i in xy.indices step 2) add(PointD(xy[i],xy[i+1]))
    }
    private fun checkCutter(contours: List<Contour>) {
        val area = Clipper.union(PathsD().apply { contours.forEach { add(polygon(it.points)) } },null,FillRule.EvenOdd,6)
        val preview = ToolpathPreview.calculate(contours)
        assertTrue(preview.warnings.isEmpty(),preview.warnings.toString())
        assertTrue(preview.paths.isNotEmpty())
        for (path in preview.paths) {
            val stroke = Clipper.inflatePaths(PathsD().apply { add(polygon(path.points)) },0.99999,
                JoinType.Round,EndType.Round,2.0,0.001,6)
            val outside = Clipper.difference(stroke,area,FillRule.NonZero,6)
            assertTrue(outside.sumOf { abs(Clipper.area(it)) } < 0.002,"Cutter must stay inside the pocket")
        }
    }
    @Test fun `rectangle is one connected spiral exported at each depth`() {
        val c=contour(1,doubleArrayOf(0.0,0.0,40.0,0.0,40.0,20.0,0.0,20.0,0.0,0.0))
        val before=c.points.copyOf()
        checkCutter(listOf(c))
        val preview=ToolpathPreview.calculate(listOf(c))
        assertEquals(1,preview.paths.size)
        assertTrue(preview.paths.single().points.size>40)
        val code=GCodeExporter.generate(listOf(c),listOf(c),ExportScope.SELECTED,true)
        assertEquals(listOf("G1 Z-1 F200","G1 Z-2 F200","G1 Z-2.5 F200"),code.lines().filter { it.startsWith("G1 Z") })
        assertEquals(3*(preview.paths.single().points.size/2-1),code.lines().count { it.startsWith("G1 X") })
        assertArrayEquals(before,c.points)
        c.cutSettings=c.cutSettings.copy(stepOverPercent=20.0)
        assertTrue(ToolpathPreview.calculate(listOf(c)).paths.sumOf { it.points.size }>preview.paths.sumOf { it.points.size })
    }
    @Test fun `island and concave boundaries are respected by the entire cutter`() {
        val outer=contour(1,doubleArrayOf(0.0,0.0,30.0,0.0,30.0,30.0,0.0,30.0,0.0,0.0))
        val hole=contour(2,doubleArrayOf(10.0,10.0,20.0,10.0,20.0,20.0,10.0,20.0,10.0,10.0))
        checkCutter(listOf(outer,hole))
        checkCutter(listOf(contour(3,doubleArrayOf(0.0,0.0,30.0,0.0,30.0,10.0,10.0,10.0,10.0,30.0,0.0,30.0,0.0,0.0))))
    }
    @Test fun `invalid stepover is rejected`() {
        for (value in listOf(0.0,Double.NaN,Double.POSITIVE_INFINITY,51.0))
            assertThrows(IllegalArgumentException::class.java) { CutSettings(stepOverPercent=value) }
    }
}
