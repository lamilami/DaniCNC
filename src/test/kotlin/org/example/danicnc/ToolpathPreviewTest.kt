package org.example.danicnc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolpathPreviewTest {
    private fun rect(id: Int = 1, source: String = "rect $id", x: Double = 0.0, y: Double = 0.0,
                     w: Double = 40.0, h: Double = 30.0) =
        Contour(id, source, doubleArrayOf(x,y, x+w,y, x+w,y+h, x,y+h, x,y), true)

    private fun configure(c: Contour, type: CutType, bit: Double = 6.0) {
        CutSettings.applyTo(listOf(c)) { it.copy(type = type, bitMm = bit) }
    }

    private fun bounds(p: PreviewPath) = Contour(0, "preview", p.points, p.closed).bounds

    @Test fun `rectangle compensation uses radius and retains original coordinates`() {
        val c = rect()
        val before = c.points.copyOf()
        for ((type, width, height) in listOf(Triple(CutType.OUTSIDE,46.0,36.0), Triple(CutType.INSIDE,34.0,24.0))) {
            configure(c,type)
            val result = ToolpathPreview.calculate(listOf(c))
            assertTrue(result.warnings.isEmpty(), result.warnings.toString())
            val b = bounds(result.paths.single())
            assertEquals(width,b.width,0.0021); assertEquals(height,b.height,0.0021)
            assertEquals(if (type == CutType.OUTSIDE) -3.0 else 3.0,b.minX,0.0021)
            assertEquals(if (type == CutType.OUTSIDE) -3.0 else 3.0,b.minY,0.0021)
            assertArrayEquals(before,c.points)
        }
    }

    @Test fun `open on path is an exact independent copy with one mm bit`() {
        val c = Contour(1,"line",doubleArrayOf(0.0,0.0,10.0,10.0,20.0,5.0),false)
        configure(c,CutType.ON_PATH,1.0)
        val p = ToolpathPreview.calculate(listOf(c)).paths.single()
        assertFalse(p.closed); assertArrayEquals(c.points,p.points)
        assertNotSame(c.points,p.points)
    }

    @Test fun `compound contour preserves hole and expands it for inside`() {
        val outer = rect(source="path #ring")
        val hole = rect(2,"path #ring",15.0,10.0,10.0,10.0)
        for (c in listOf(outer,hole)) configure(c,CutType.INSIDE)
        val result = ToolpathPreview.calculate(listOf(outer,hole))
        assertTrue(result.warnings.isEmpty(),result.warnings.toString())
        assertEquals(2,result.paths.size)
        val boxes = result.paths.map(::bounds).sortedByDescending { it.width }
        assertEquals(34.0,boxes[0].width,1e-6)
        assertEquals(16.0,boxes[1].width,0.0021)
        assertEquals(16.0,boxes[1].height,0.0021)
    }

    @Test fun `narrow and disconnected inside paths warn without fabricated connections`() {
        val thin = rect(w=40.0,h=5.0)
        configure(thin,CutType.INSIDE)
        val dumbbell = Contour(2,"neck",doubleArrayOf(0.0,0.0,20.0,0.0,20.0,8.0,40.0,8.0,
            40.0,0.0,60.0,0.0,60.0,20.0,40.0,20.0,40.0,12.0,20.0,12.0,20.0,20.0,0.0,20.0,0.0,0.0),true)
        configure(dumbbell,CutType.INSIDE)
        for (c in listOf(thin,dumbbell)) {
            val result=ToolpathPreview.calculate(listOf(c))
            assertTrue(result.paths.isEmpty())
            assertTrue(result.warnings.single().contains("narrow"))
        }
    }

    @Test fun `different contours retain independent settings and pocket is never previewed`() {
        val a=rect(); val b=rect(2,x=60.0); val pocket=rect(3,x=120.0)
        configure(a,CutType.OUTSIDE); configure(b,CutType.INSIDE,2.0); configure(pocket,CutType.POCKET)
        val result=ToolpathPreview.calculate(listOf(a,b,pocket))
        assertEquals(2,result.paths.size)
        assertEquals(46.0,bounds(result.paths.first { 1 in it.contourIds }).width,0.0021)
        assertEquals(38.0,bounds(result.paths.first { 2 in it.contourIds }).width,1e-6)
        assertTrue(result.warnings.single().contains("Pocket preview not implemented"))
    }
}
