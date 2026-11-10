package org.example.danicnc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class CutSettingsTest {
    @Test fun `per contour settings are project state and never alter geometry`() {
        val drawing = SvgLoader.load(File("samples/three_rects_open_line.svg"))
        val before = drawing.contours.map { it.points.copyOf() }
        val expected = listOf(CutType.OUTSIDE to 6.0, CutType.POCKET to 3.0, CutType.ON_PATH to 1.0)
        expected.forEachIndexed { i, (type, depth) ->
            CutSettings.applyTo(listOf(drawing.contours[i])) { it.copy(type = type, depthMm = depth) }
        }
        expected.forEachIndexed { i, (type, depth) ->
            assertEquals(type, drawing.contours[i].cutSettings.type)
            assertEquals(depth, drawing.contours[i].cutSettings.depthMm)
        }
        drawing.contours.forEachIndexed { i, c -> assertArrayEquals(before[i], c.points) }
        drawing.contours[0].translate(20.0, 30.0)
        assertEquals(CutType.OUTSIDE, drawing.contours[0].cutSettings.type)
        assertEquals(6.0, drawing.contours[0].cutSettings.depthMm)
    }

    @Test fun `mixed open selection rejects closed operations atomically`() {
        val drawing = SvgLoader.load(File("samples/three_rects_open_line.svg"))
        val selected = listOf(drawing.contours.first(), drawing.contours.last())
        val before = selected.map { it.cutSettings }
        for (type in listOf(CutType.INSIDE, CutType.OUTSIDE, CutType.POCKET)) {
            assertThrows(IllegalArgumentException::class.java) {
                CutSettings.applyTo(selected) { it.copy(type = type, depthMm = 6.0) }
            }
            assertEquals(before, selected.map { it.cutSettings })
        }
        CutSettings.applyTo(selected) { it.copy(depthMm = 2.0, bitMm = 6.0) }
        assertTrue(selected.all { it.cutSettings.depthMm == 2.0 && it.cutSettings.bitMm == 6.0 })
        assertEquals(1.0, drawing.contours[1].cutSettings.depthMm)
    }

    @Test fun `invalid cutting parameters are rejected`() {
        for (value in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { CutSettings(depthMm = value) }
            assertThrows(IllegalArgumentException::class.java) { CutSettings(feedMmMin = value) }
            assertThrows(IllegalArgumentException::class.java) { CutSettings(plungeMmMin = value) }
            assertThrows(IllegalArgumentException::class.java) { CutSettings(passDepthMm = value) }
        }
        assertThrows(IllegalArgumentException::class.java) { CutSettings(bitMm = 4.0) }
        assertThrows(IllegalArgumentException::class.java) { CutSettings(spindleRpm = 0) }
    }
}
