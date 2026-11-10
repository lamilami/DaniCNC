package org.example.danicnc

import javafx.geometry.Point2D
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolpathTraversalTest {
    private fun rect(id: Int=1,source: String="rect $id",x: Double=0.0,y: Double=0.0,w: Double=40.0,h: Double=30.0)=
        Contour(id,source,doubleArrayOf(x,y,x+w,y,x+w,y+h,x,y+h,x,y),true)
    private fun configure(contours: List<Contour>,type: CutType,bit: Double=6.0) =
        CutSettings.applyTo(contours) { it.copy(type=type,bitMm=bit,depthMm=1.0,passDepthMm=1.0) }
    private fun first(p: PreviewPath)=Point2D(p.points[0],p.points[1])
    private fun signedArea(p: PreviewPath): Double {
        val poly=com.github.micycle1.clipper2.core.PathD()
        for(i in p.points.indices step 2) poly.add(com.github.micycle1.clipper2.core.PointD(p.points[i],p.points[i+1]))
        return com.github.micycle1.clipper2.Clipper.area(poly)
    }

    @Test fun `closed start splits a segment and reversed path retains start and original SVG`() {
        val c=rect();val contours=listOf(c);val original=c.points.copyOf()
        var p=ToolpathPreview.calculate(contours).paths.single()
        ToolpathTraversal.setStart(p,contours,Point2D(12.5,-2.0))
        p=ToolpathPreview.calculate(contours).paths.single()
        assertEquals(Point2D(12.5,0.0),first(p))
        assertEquals(5,p.points.size/2) // selected interior point plus four corners
        val area=signedArea(p)
        ToolpathTraversal.reverse(p,contours)
        val reversed=ToolpathPreview.calculate(contours).paths.single()
        assertEquals(first(p),first(reversed));assertEquals(-area,signedArea(reversed),1e-6)
        assertArrayEquals(original,c.points)
        val code=GCodeExporter.generate(contours,contours,ExportScope.SELECTED,true)
        assertTrue(code.contains("G0 X12.5 Y0"))
        assertTrue(code.contains("G1 X12.5 Y0 F600"))
        assertTrue(code.contains("G1 X0 Y0 F600"))
    }

    @Test fun `open starts snap to endpoints and reverse swaps ends`() {
        val c=Contour(1,"line",doubleArrayOf(0.0,0.0,10.0,0.0,20.0,10.0),false)
        val contours=listOf(c)
        var p=ToolpathPreview.calculate(contours).paths.single()
        ToolpathTraversal.setStart(p,contours,Point2D(15.0,3.0))
        p=ToolpathPreview.calculate(contours).paths.single()
        assertEquals(Point2D(20.0,10.0),first(p))
        ToolpathTraversal.reverse(p,contours)
        p=ToolpathPreview.calculate(contours).paths.single()
        assertEquals(Point2D(0.0,0.0),first(p))
        assertArrayEquals(c.points,p.points)
        ToolpathTraversal.reverse(p,contours)
        val code=GCodeExporter.generate(contours,contours,ExportScope.SELECTED,true)
        assertTrue(code.contains("G0 X20 Y10"))
        assertTrue(code.contains("G1 X10 Y0 F600\nG1 X0 Y0 F600"))
    }

    @Test fun `start reprojects on new offset and follows explicit geometry movement`() {
        val c=rect();val contours=listOf(c);configure(contours,CutType.OUTSIDE)
        var p=ToolpathPreview.calculate(contours).paths.single()
        ToolpathTraversal.setStart(p,contours,Point2D(12.5,-3.0))
        configure(contours,CutType.OUTSIDE,2.0)
        p=ToolpathPreview.calculate(contours).paths.single()
        assertEquals(Point2D(12.5,-1.0),first(p))
        ToolpathTraversal.reverse(p,contours)
        val direction=signedArea(ToolpathPreview.calculate(contours).paths.single())
        configure(contours,CutType.INSIDE,2.0)
        p=ToolpathPreview.calculate(contours).paths.single()
        assertEquals(Point2D(12.5,1.0),first(p))
        assertTrue(signedArea(p)*direction>0)
        c.translate(20.0,30.0)
        p=ToolpathPreview.calculate(contours).paths.single()
        assertEquals(Point2D(32.5,31.0),first(p))
    }

    @Test fun `compound hole edit is isolated and survives reordering and on path conversion`() {
        val outer=rect(source="path #ring")
        val hole=rect(2,"path #ring",15.0,10.0,10.0,10.0)
        val contours=listOf(outer,hole);configure(contours,CutType.INSIDE)
        var paths=ToolpathPreview.calculate(contours).paths
        val outerPath=paths.maxBy { kotlin.math.abs(signedArea(it)) }
        val holePath=paths.minBy { kotlin.math.abs(signedArea(it)) }
        ToolpathTraversal.setStart(holePath,contours,Point2D(20.0,7.0))
        paths=ToolpathPreview.calculate(contours).paths
        assertArrayEquals(outerPath.points,paths.first { it.traversalKey==null }.points)
        assertEquals(Point2D(20.0,7.0),first(paths.first { it.traversalKey!=null }))
        assertTrue(outer.pathTraversals.isEmpty());assertEquals(1,hole.pathTraversals.size)
        configure(contours,CutType.INSIDE,2.0)
        paths=ToolpathPreview.calculate(contours).paths
        assertEquals(Point2D(20.0,9.0),first(paths.first { it.traversalOwnerId==2 }))
        configure(contours,CutType.ON_PATH,2.0)
        paths=ToolpathPreview.calculate(contours).paths
        assertArrayEquals(outer.points,paths.first { 1 in it.contourIds }.points)
        assertEquals(Point2D(20.0,10.0),first(paths.first { 2 in it.contourIds }))
    }

    @Test fun `separate toolpaths from one contour retain independent starts and directions`() {
        // Two closed loops joined by a retraced segment: Clipper resolves two distinct filled islands.
        val c=Contour(1,"path #twoIslands",doubleArrayOf(0.0,0.0,20.0,0.0,20.0,20.0,0.0,20.0,0.0,0.0,
            -10.0,0.0,-10.0,20.0,-30.0,20.0,-30.0,0.0,-10.0,0.0,0.0,0.0),true)
        val contours=listOf(c);configure(contours,CutType.INSIDE,2.0)
        val initial=ToolpathPreview.calculate(contours)
        assertTrue(initial.warnings.isEmpty(),initial.warnings.toString())
        assertEquals(2,initial.paths.size)
        val left=initial.paths.first { it.points.filterIndexed { i,_ -> i%2==0 }.max()<0 }
        val right=initial.paths.first { it!==left }
        ToolpathTraversal.setStart(left,contours,Point2D(-20.0,1.0))
        var edited=ToolpathPreview.calculate(contours).paths
        assertArrayEquals(right.points,edited.first { it.traversalKey==null }.points)
        val leftEdited=edited.first { it.traversalKey!=null }
        ToolpathTraversal.reverse(leftEdited,contours)
        val rightEdited=ToolpathPreview.calculate(contours).paths.first { it.traversalKey==null }
        ToolpathTraversal.setStart(rightEdited,contours,Point2D(10.0,1.0))
        // Output order changes must not exchange the two path preferences.
        edited=ToolpathTraversal.apply(initial.paths.reversed(),contours)
        val starts=edited.map(::first).toSet()
        assertEquals(setOf(Point2D(-20.0,1.0),Point2D(10.0,1.0)),starts)
        assertTrue(signedArea(edited.first { first(it).x<0 })*signedArea(left)<0)
        assertTrue(signedArea(edited.first { first(it).x>0 })*signedArea(right)>0)
        configure(contours,CutType.INSIDE,6.0)
        edited=ToolpathPreview.calculate(contours).paths
        assertEquals(setOf(Point2D(-20.0,3.0),Point2D(10.0,3.0)),edited.map(::first).toSet())
        val code=GCodeExporter.generate(contours,contours,ExportScope.SELECTED,true)
        assertTrue(code.contains("G0 X-20 Y3"));assertTrue(code.contains("G0 X10 Y3"))
    }
}
