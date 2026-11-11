package org.example.danicnc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class GCodeExporterTest {
    @TempDir lateinit var dir: Path
    private fun rectangle(id: Int = 1, x: Double = 10.0, y: Double = 20.0, width: Double = 40.0,
                          height: Double = 30.0, source: String = "rect $id") =
        Contour(id,source,doubleArrayOf(x,y,x+width,y,x+width,y+height,x,y+height,x,y),true)

    private fun settings(c: Contour, type: CutType=CutType.OUTSIDE, depth: Double=6.0, step: Double=2.0,
                         feed: Double=600.0, plunge: Double=200.0, speed: Int=12000) {
        CutSettings.applyTo(listOf(c)) { it.copy(type=type,bitMm=6.0,depthMm=depth,passDepthMm=step,
            feedMmMin=feed,plungeMmMin=plunge,spindleRpm=speed) }
    }
    private fun export(contours: List<Contour>, selected: List<Contour> = contours,
                       scope: ExportScope=ExportScope.ENTIRE_PROJECT, visible: Boolean=true) =
        GCodeExporter.generate(contours,selected,scope,visible)

    private fun words(line: String) = Regex("([XYZFS])(-?\\d+(?:\\.\\d+)?)").findAll(line)
        .associate { it.groupValues[1] to it.groupValues[2].toDouble() }
    private fun motions(code: String) = code.lines().filter { it.startsWith("G0 ") || it.startsWith("G1 ") }

    @Test fun `outside inside export the actual compensated points with original work origin`() {
        for ((type,w,h) in listOf(Triple(CutType.OUTSIDE,46.0,36.0),Triple(CutType.INSIDE,34.0,24.0))) {
            val c=rectangle(); settings(c,type)
            val before=c.points.copyOf()
            val path=ToolpathPreview.calculate(listOf(c)).paths.single()
            val code=export(listOf(c))
            val cut=motions(code).filter { it.startsWith("G1 X") }.map(::words)
            assertEquals(w,cut.maxOf { it.getValue("X") }-cut.minOf { it.getValue("X") },0.0021)
            assertEquals(h,cut.maxOf { it.getValue("Y") }-cut.minOf { it.getValue("Y") },0.0021)
            val start=motions(code).first { it.startsWith("G0 X") }.let(::words)
            assertEquals(path.points[0],start.getValue("X"),1e-6)
            assertEquals(path.points[1],start.getValue("Y"),1e-6)
            val firstPass=cut.take(path.points.size/2)
            for (i in 1 until path.points.size/2) {
                assertEquals(path.points[2*i],firstPass[i-1].getValue("X"),1e-6)
                assertEquals(path.points[2*i+1],firstPass[i-1].getValue("Y"),1e-6)
            }
            assertEquals(start.getValue("X"),firstPass.last().getValue("X"),1e-6)
            assertEquals(start.getValue("Y"),firstPass.last().getValue("Y"),1e-6)
            assertArrayEquals(before,c.points)
        }
    }

    @Test fun `three depth passes retract before every rapid XY and preserve feed rates`() {
        val c=rectangle();settings(c)
        val code=export(listOf(c)); val lines=code.lines()
        assertEquals(listOf(-2.0,-4.0,-6.0),lines.filter { it.startsWith("G1 Z") }.map { words(it).getValue("Z") })
        assertEquals(1,lines.count { it=="M3 S12000" })
        var z: Double?=null
        var spindle=false
        for (line in lines) {
            if (line.startsWith("M3")) spindle=true
            if (line=="M5") spindle=false
            if (line.startsWith("G0 X")) assertEquals(5.0,z)
            if (line.startsWith("G1 Z")) {
                assertTrue(spindle); assertEquals(200.0,words(line).getValue("F"))
            }
            if (line.startsWith("G1 X")) {
                assertTrue(z!!<0); assertEquals(600.0,words(line).getValue("F"))
            }
            if (line.startsWith("G0 ") || line.startsWith("G1 ")) words(line)["Z"]?.let { z=it }
        }
        assertTrue(code.contains("G21\nG90\nG17\nG94\n"))
        assertTrue(code.endsWith("G0 Z5\nM5\nM2\n"))
    }

    @Test fun `open line starts at first endpoint without closing and restarts every pass`() {
        val c=Contour(1,"line",doubleArrayOf(-10.0,5.0,0.0,15.0,20.0,10.0),false)
        CutSettings.applyTo(listOf(c)) { it.copy(type=CutType.ON_PATH,bitMm=1.0,depthMm=6.0,passDepthMm=2.0) }
        val code=export(listOf(c))
        assertEquals(3,code.lines().count { it=="G0 X-10 Y5" })
        assertEquals(6,code.lines().count { it.startsWith("G1 X") })
        assertFalse(code.contains("G1 X-10 Y5"))
    }

    @Test fun `scope and model order preserve different parameters and spindle changes`() {
        val a=rectangle(1); val b=rectangle(2,x=80.0)
        settings(a,CutType.INSIDE,depth=1.0,step=1.0,speed=9000)
        settings(b,depth=3.0,step=2.0,feed=850.0,plunge=150.0,speed=15000)
        assertEquals(ExportScope.SELECTED,GCodeExporter.defaultScope(listOf(b)))
        assertEquals(ExportScope.ENTIRE_PROJECT,GCodeExporter.defaultScope(emptyList()))
        val all=export(listOf(a,b),listOf(b,a))
        assertTrue(all.indexOf("(Contours 1")<all.indexOf("(Contours 2"))
        assertTrue(all.contains("M3 S9000"));assertTrue(all.contains("M3 S15000"))
        assertTrue(all.contains("G1 Z-3 F150"));assertTrue(all.contains("F850"))
        val one=export(listOf(a,b),listOf(b),ExportScope.SELECTED)
        assertFalse(one.contains("(Contours 1")); assertTrue(one.contains("(Contours 2"))
        assertFalse(one.contains("S9000"))
        settings(a,CutType.POCKET)
        assertDoesNotThrow { export(listOf(a,b),listOf(b),ExportScope.SELECTED) }
        assertDoesNotThrow { export(listOf(a,b)) }
    }

    @Test fun `compound holes need complete selection and consistent per path cutting parameters`() {
        val outer=rectangle(source="path #ring")
        val hole=rectangle(2,x=25.0,y=30.0,width=10.0,height=10.0,source="path #ring")
        settings(outer,CutType.INSIDE);settings(hole,CutType.INSIDE)
        val all=listOf(outer,hole)
        assertEquals(6,export(all).lines().count { it.startsWith("G1 Z") })
        assertThrows(IllegalArgumentException::class.java) { export(all,listOf(outer),ExportScope.SELECTED) }
        settings(hole,CutType.INSIDE,depth=3.0)
        assertThrows(IllegalArgumentException::class.java) { export(all) }
    }

    @Test fun `invalid or hidden paths cancel without creating or replacing files`() {
        val valid=rectangle();settings(valid)
        val pocket=rectangle(2,height=2.0);settings(pocket,CutType.POCKET)
        val thin=rectangle(3,width=40.0,height=5.0);settings(thin,CutType.INSIDE)
        val empty=Contour(4,"empty",doubleArrayOf(),false)
        val bad=Contour(5,"bad",doubleArrayOf(Double.NaN,0.0,5.0,5.0),false)
        val zero=Contour(6,"zero",doubleArrayOf(0.0,0.0,0.0,0.0),false)
        for (invalid in listOf(pocket,thin,empty,bad,zero)) {
            val file=dir.resolve("new.nc")
            assertThrows(IllegalArgumentException::class.java) {
                GCodeExporter.write(file,listOf(valid,invalid),emptyList(),ExportScope.ENTIRE_PROJECT,true)
            }
            assertFalse(Files.exists(file))
            val existing=dir.resolve("existing.nc");Files.writeString(existing,"KEEP")
            assertThrows(IllegalArgumentException::class.java) {
                GCodeExporter.write(existing,listOf(valid,invalid),emptyList(),ExportScope.ENTIRE_PROJECT,true)
            }
            assertEquals("KEEP",Files.readString(existing))
        }
        assertThrows(IllegalArgumentException::class.java) { export(listOf(valid),visible=false) }
        assertThrows(IllegalArgumentException::class.java) { export(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { export(listOf(valid),emptyList(),ExportScope.SELECTED) }
    }

    @Test fun `fractional last pass is clamped decimal formatting is locale independent`() {
        val c=rectangle();settings(c,depth=5.0,step=2.0)
        assertEquals(listOf(-2.0,-4.0,-5.0),export(listOf(c)).lines().filter { it.startsWith("G1 Z") }.map { words(it).getValue("Z") })
        settings(c,depth=0.07,step=0.01)
        val old=Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val code=export(listOf(c))
            assertEquals(7,code.lines().count { it.startsWith("G1 Z") })
            assertTrue(code.contains("Z-0.07"));assertFalse(code.contains("Z-0,"))
        } finally { Locale.setDefault(old) }
    }

    @Test fun `invalid cutting parameters and excessive output are rejected`() {
        for (v in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { CutSettings(depthMm=v) }
            assertThrows(IllegalArgumentException::class.java) { CutSettings(passDepthMm=v) }
            assertThrows(IllegalArgumentException::class.java) { CutSettings(feedMmMin=v) }
            assertThrows(IllegalArgumentException::class.java) { CutSettings(plungeMmMin=v) }
        }
        assertThrows(IllegalArgumentException::class.java) { CutSettings(spindleRpm=0) }
        val c=rectangle();settings(c,depth=100.0,step=0.000001)
        assertThrows(IllegalArgumentException::class.java) { export(listOf(c)) }
        settings(c,depth=1e-10)
        assertThrows(IllegalArgumentException::class.java) { export(listOf(c)) }
    }

    @Test fun `write complete nc and produce reproducible example artifact`() {
        val c=rectangle();settings(c)
        val code=export(listOf(c))
        val file=dir.resolve("valid.nc")
        GCodeExporter.write(file,listOf(c),listOf(c),ExportScope.SELECTED,true)
        assertEquals(code,Files.readString(file))
        Files.writeString(file,"OLD")
        GCodeExporter.write(file,listOf(c),listOf(c),ExportScope.SELECTED,true)
        assertEquals(code,Files.readString(file))
        Files.createDirectories(Path.of("target"))
        Files.writeString(Path.of("target/task8-outside-40x30-6mm.nc"),code)
    }
}
