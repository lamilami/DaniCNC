package org.example.danicnc

import javafx.geometry.Point2D
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectIOTest {
    @TempDir lateinit var temp: Path
    private fun fixture(): ProjectState {
        val svg="""<svg xmlns="http://www.w3.org/2000/svg" width="100mm" height="60mm" viewBox="0 0 100 60"><path id="ring" d="M0 0H40V30H0Z M10 10H20V20H10Z"/><rect x="50" y="0" width="30" height="20"/><path d="M0 45H50"/></svg>"""
        val source=temp.resolve("чертеж.svg")
        Files.writeString(source,svg)
        val drawing=SvgLoader.load(source.toFile())
        drawing.contours.take(2).forEach { it.cutSettings=CutSettings(type=CutType.POCKET,bitMm=2.0,depthMm=2.5,passDepthMm=1.0,stepOverPercent=25.0) }
        drawing.contours[2].cutSettings=CutSettings(type=CutType.OUTSIDE,bitMm=6.0,feedMmMin=850.0,spindleRpm=9000)
        val path=ToolpathPreview.calculate(drawing.contours).paths.first { 3 in it.contourIds }
        ToolpathTraversal.setStart(path,drawing.contours,Point2D(60.0,37.0))
        val updated=ToolpathPreview.calculate(drawing.contours).paths.first { 3 in it.contourIds }
        ToolpathTraversal.reverse(updated,drawing.contours)
        drawing.contours.forEach { it.translate(12.345678901,-3.123456789) }
        return ProjectState(drawing,"чертеж.svg",5.0,false,false)
    }

    @Test fun `project is self contained and preserves exact geometry settings starts and gcode`() {
        val original=fixture()
        val before=GCodeExporter.generate(original.drawing.contours,emptyList(),ExportScope.ENTIRE_PROJECT,true)
        val destination=temp.resolve("детайл.dcnc")
        ProjectIO.save(destination,original)
        Files.delete(temp.resolve("чертеж.svg"))
        val restored=ProjectIO.load(destination)
        assertEquals(original.sourceName,restored.sourceName)
        assertEquals(original.gridStep,restored.gridStep)
        assertFalse(restored.snapToGrid); assertFalse(restored.previewVisible)
        assertArrayEquals(original.drawing.originalSvg,restored.drawing.originalSvg)
        assertEquals(original.drawing.docWidthMm,restored.drawing.docWidthMm)
        assertEquals(original.drawing.docHeightMm,restored.drawing.docHeightMm)
        original.drawing.contours.zip(restored.drawing.contours).forEach { (a,b) ->
            assertEquals(a.id,b.id); assertEquals(a.source,b.source); assertEquals(a.closed,b.closed)
            assertArrayEquals(a.points,b.points)
            assertEquals(a.cutSettings,b.cutSettings)
            assertEquals(a.pathTraversals,b.pathTraversals)
        }
        val after=GCodeExporter.generate(restored.drawing.contours,emptyList(),ExportScope.ENTIRE_PROJECT,true)
        assertEquals(before,after)
        assertArrayEquals(ProjectIO.encode(original),ProjectIO.encode(restored))
    }

    @Test fun `xml escaping skipped elements and optional original svg round trip`() {
        val c=Contour(1,"path #ъгъл & <\"special\">",doubleArrayOf(1.0,2.0,3.0,4.0),false)
        c.pathTraversals.add(PathTraversal(5,Point2D(1.0,1.0),false,0,null,true))
        val state=ProjectState(SvgDrawing(listOf(c),0.0,0.0,mapOf("text & image" to 3)),"дъска & <x>.svg")
        val restored=ProjectIO.decode(ProjectIO.encode(state))
        assertEquals(state.sourceName,restored.sourceName)
        assertEquals(c.source,restored.drawing.contours.single().source)
        assertEquals(c.pathTraversals,restored.drawing.contours.single().pathTraversals)
        assertEquals(state.drawing.skipped,restored.drawing.skipped)
        assertNull(restored.drawing.originalSvg)
    }

    @Test fun `malformed versions invalid geometry and settings are rejected`() {
        val xml=ProjectIO.encode(fixture()).toString(Charsets.UTF_8)
        for(bad in listOf(xml.replace("version=\"1\"","version=\"99\""),
            xml.replace("units=\"mm\"","units=\"inch\""),
            xml.replaceFirst("id=\"2\"","id=\"1\""),
            xml.replaceFirst("stepOverPercent=\"25.0\"","stepOverPercent=\"99.0\""),
            xml.replaceFirst("<points>","<points>NaN "),
            xml.replaceFirst("previewVisible=\"false\"","previewVisible=\"maybe\""),
            "<dcnc version=\"1\" units=\"mm\"/>","<svg/>",xml.take(40))) {
            assertThrows(Exception::class.java) { ProjectIO.decode(bad.toByteArray()) }
        }
    }

    @Test fun `external entities and doctypes are rejected`() {
        val xml="""<?xml version="1.0"?><!DOCTYPE dcnc [<!ENTITY xxe SYSTEM "file:///C:/Windows/win.ini">]><dcnc version="1" units="mm" sourceName="&xxe;"/>"""
        assertThrows(Exception::class.java) { ProjectIO.decode(xml.toByteArray()) }
    }

    @Test fun `saving replaces complete project and failed validation leaves old file intact`() {
        val destination=temp.resolve("work.dcnc")
        val state=fixture()
        ProjectIO.save(destination,state)
        val before=Files.readAllBytes(destination)
        state.drawing.contours.first().points[0]=Double.NaN
        assertThrows(IllegalArgumentException::class.java) { ProjectIO.save(destination,state) }
        assertArrayEquals(before,Files.readAllBytes(destination))
        val newState=ProjectState(SvgDrawing(emptyList(),10.0,20.0,emptyMap()),"empty.svg")
        ProjectIO.save(destination,newState)
        assertEquals(0,ProjectIO.load(destination).drawing.contours.size)
        Files.list(temp).use { entries -> assertFalse(entries.anyMatch { it.fileName.toString().startsWith(".dcnc-") }) }
    }
}
