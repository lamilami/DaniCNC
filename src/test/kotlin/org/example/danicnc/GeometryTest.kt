package org.example.danicnc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI

class GeometryTest {
    private fun load(name: String) = SvgLoader.load(File("samples/$name"))
    private fun svg(body: String, attrs: String = """width="100mm" height="100mm" viewBox="0 0 100 100""""): SvgDrawing {
        val f = File.createTempFile("svgtest", ".svg").apply { deleteOnExit() }
        f.writeText("""<svg xmlns="http://www.w3.org/2000/svg" $attrs>$body</svg>""")
        return SvgLoader.load(f)
    }

    @Test fun `rect 100x50 mm in mm units`() {
        val b = load("rect_100x50mm.svg").geometryBounds!!
        assertEquals(100.0, b.width, 1e-9); assertEquals(50.0, b.height, 1e-9)
    }

    @Test fun `rect 100x50 mm in px units`() {
        val b = load("rect_100x50mm_px.svg").geometryBounds!!
        assertEquals(100.0, b.width, 0.01); assertEquals(50.0, b.height, 0.01)
    }

    @Test fun `three rects and open line - closed flags and lengths`() {
        val c = load("three_rects_open_line.svg").contours
        assertEquals(4, c.size)
        assertEquals(listOf(true, true, true, false), c.map { it.closed })
        assertEquals(listOf(300.0, 200.0, 220.0, 110.0), c.map { Math.round(it.length * 1e6) / 1e6 })
    }

    @Test fun `circle length is 2 pi r`() {
        val c = svg("""<circle cx="50" cy="50" r="20"/>""").contours.single()
        assertTrue(c.closed)
        assertEquals(2 * PI * 20, c.length, 0.001)
    }

    @Test fun `path without Z but coincident ends is closed, open path is open`() {
        val c = svg("""<path d="M10 10 L60 10 L60 40 L10 10"/><path d="M0 0 L10 0 L10 10"/>""").contours
        assertTrue(c[0].closed); assertFalse(c[1].closed)
        assertEquals(20.0, c[1].length, 1e-9)
    }

    @Test fun `path with two subpaths gives two contours`() {
        val c = svg("""<path d="M0 0 h10 v10 h-10 z m20 0 h10"/>""").contours
        assertEquals(2, c.size)
        assertTrue(c[0].closed); assertFalse(c[1].closed)
        assertEquals(40.0, c[0].length, 1e-9); assertEquals(10.0, c[1].length, 1e-9)
    }

    @Test fun `half circle arc length`() {
        val c = svg("""<path d="M0 50 A 25 25 0 0 1 50 50"/>""").contours.single()
        assertFalse(c.closed)
        assertEquals(PI * 25, c.length, 0.001)
    }

    @Test fun `group transform scales geometry`() {
        val c = svg("""<g transform="scale(2)"><line x1="0" y1="0" x2="10" y2="0"/></g>""").contours.single()
        assertEquals(20.0, c.length, 1e-9)
    }
}
