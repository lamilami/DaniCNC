package org.example.danicnc

import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.ScrollEvent
import javafx.geometry.Point2D
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.scene.control.ComboBox
import javafx.scene.control.CheckBox
import javafx.stage.Stage
import javafx.scene.layout.BorderPane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Подава истински JavaFX MouseEvent-и към WorkArea (клик и Ctrl+клик). */
class SelectionTest {
    companion object {
        @JvmStatic @BeforeAll fun startFx() {
            try { Platform.startup {} } catch (_: IllegalStateException) { }
        }
    }

    private fun <T> onFx(block: () -> T): T {
        var r: Result<T>? = null
        val latch = CountDownLatch(1)
        Platform.runLater { r = runCatching(block); latch.countDown() }
        assertEquals(true, latch.await(10, TimeUnit.SECONDS))
        return r!!.getOrThrow()
    }

    private fun WorkArea.click(xMm: Double, yMm: Double, ctrl: Boolean = false) {
        // The fixture's coordinates are SVG coordinates (Y down).
        val p = mmToLocal(xMm, 140.0 - yMm)
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
            Event.fireEvent(this, MouseEvent(type, p.x, p.y, p.x, p.y, MouseButton.PRIMARY, 1,
                false, ctrl, false, false, type == MouseEvent.MOUSE_PRESSED, false, false, true, false, true, null))
        }
    }

    private fun WorkArea.mouse(type: javafx.event.EventType<MouseEvent>, p: Point2D,
                               button: MouseButton = MouseButton.PRIMARY) {
        val scenePoint = localToScene(0.0, 0.0).add(p)
        Event.fireEvent(this, MouseEvent(type, scenePoint.x, scenePoint.y, scenePoint.x, scenePoint.y, button, 1,
            false, false, false, false, button == MouseButton.PRIMARY,
            button == MouseButton.MIDDLE, false, true, false, true, null))
    }

    private fun WorkArea.drag(from: Point2D, to: Point2D, button: MouseButton = MouseButton.PRIMARY) {
        mouse(MouseEvent.MOUSE_PRESSED, from, button)
        mouse(MouseEvent.MOUSE_DRAGGED, to, button)
        mouse(MouseEvent.MOUSE_RELEASED, to, button)
    }

    private fun area(drawing: SvgDrawing) = WorkArea().apply {
        Scene(this, 1000.0, 700.0)
        resize(1000.0, 700.0); layout(); setDrawing(drawing)
    }

    @Test fun `acceptance - drag rectangle to 20 30 then return to origin`() = onFx {
        val drawing = SvgLoader.load(File("samples/rect_100x50mm.svg"))
        val area = area(drawing)
        val before = drawing.contours.single().points.copyOf()
        area.drag(area.mmToLocal(60.0, 10.0), area.mmToLocal(70.0, 30.0))
        assertEquals(20.0, area.selectionBounds!!.minX, 1e-9)
        assertEquals(30.0, area.selectionBounds!!.minY, 1e-9)
        val points = drawing.contours.single().points
        for (i in points.indices step 2) {
            assertEquals(before[i] + 10.0, points[i], 1e-9)
            assertEquals(before[i + 1] + 20.0, points[i + 1], 1e-9)
        }
        area.moveSelectionTo(0.0, 0.0)
        val b = drawing.geometryBounds!!
        assertEquals(0.0, b.minX, 1e-9); assertEquals(0.0, b.minY, 1e-9)
        assertEquals(100.0, b.width, 1e-9); assertEquals(50.0, b.height, 1e-9)
    }

    @Test fun `group movement preserves relative geometry and unselected contours`() = onFx {
        val drawing = SvgLoader.load(File("samples/three_rects_open_line.svg"))
        val area = area(drawing)
        area.click(60.0, 10.0)
        area.click(190.0, 30.0, ctrl = true)
        val before = drawing.contours.map { it.points.copyOf() }
        area.snapToGrid = false
        area.drag(area.mmToLocal(60.0, 130.0), area.mmToLocal(62.3, 134.7))
        drawing.contours.forEachIndexed { index, c ->
            val moved = c in area.selected
            for (i in c.points.indices step 2) {
                assertEquals(before[index][i] + if (moved) 2.3 else 0.0, c.points[i], 1e-8)
                assertEquals(before[index][i + 1] + if (moved) 4.7 else 0.0, c.points[i + 1], 1e-8)
            }
        }
        area.moveSelectionTo(-12.25, 0.125)
        assertEquals(-12.25, area.selectionBounds!!.minX, 1e-9)
        assertEquals(0.125, area.selectionBounds!!.minY, 1e-9)
    }

    @Test fun `snap steps and pan zoom retain machine coordinates`() = onFx {
        for (step in listOf(1.0, 5.0, 10.0)) {
            val drawing = SvgLoader.load(File("samples/rect_100x50mm.svg"))
            val area = area(drawing)
            area.gridStep = step
            area.drag(area.mmToLocal(60.0, 10.0), area.mmToLocal(62.3, 14.7))
            assertEquals(area.snap(12.3), area.selectionBounds!!.minX, 1e-9)
            assertEquals(area.snap(14.7), area.selectionBounds!!.minY, 1e-9)
            val before = drawing.contours.single().points.copyOf()
            val anchor = area.mmToLocal(20.0, 30.0)
            val anchorMm = area.toMm(anchor.x, anchor.y)
            Event.fireEvent(area, ScrollEvent(ScrollEvent.SCROLL, anchor.x, anchor.y, anchor.x, anchor.y,
                false, false, false, false, false, false, 0.0, 40.0, 0.0, 40.0,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0.0, 0, null))
            assertEquals(anchorMm.x, area.toMm(anchor.x, anchor.y).x, 1e-9)
            assertEquals(anchorMm.y, area.toMm(anchor.x, anchor.y).y, 1e-9)
            val origin = area.mmToLocal(0.0, 0.0)
            area.drag(Point2D(100.0, 100.0), Point2D(120.0, 130.0), MouseButton.MIDDLE)
            assertEquals(origin.x + 20, area.mmToLocal(0.0, 0.0).x, 1e-9)
            assertEquals(origin.y + 30, area.mmToLocal(0.0, 0.0).y, 1e-9)
            org.junit.jupiter.api.Assertions.assertArrayEquals(before, drawing.contours.single().points, 1e-9)
        }
    }

    @Test fun `manual XY controls position at origin without snapping`() = onFx {
        Platform.setImplicitExit(false)
        val app = SimpleCamApp()
        val stage = Stage()
        try {
            app.start(stage)
            val area = (stage.scene.root as BorderPane).center as WorkArea
            val drawing = SvgLoader.load(File("samples/rect_100x50mm.svg"))
            area.setDrawing(drawing)
            area.drag(area.mmToLocal(60.0, 10.0), area.mmToLocal(70.0, 30.0))
            val x = stage.scene.lookup("#selectionX") as TextField
            val y = stage.scene.lookup("#selectionY") as TextField
            val apply = stage.scene.lookup("#applyPosition") as Button
            assertEquals(20.0, x.text.toDouble(), 1e-9)
            assertEquals(30.0, y.text.toDouble(), 1e-9)
            x.text = "0"; y.text = "0"; apply.fire()
            assertEquals(0.0, drawing.geometryBounds!!.minX, 1e-9)
            assertEquals(0.0, drawing.geometryBounds!!.minY, 1e-9)
            x.text = "1,25"; y.text = "-2.75"; apply.fire()
            assertEquals(1.25, drawing.geometryBounds!!.minX, 1e-9)
            assertEquals(-2.75, drawing.geometryBounds!!.minY, 1e-9)
            x.text = "NaN"; apply.fire()
            assertEquals(1.25, drawing.geometryBounds!!.minX, 1e-9)
        } finally { stage.close() }
    }

    @Test fun `click, ctrl+click, click inside and clear`() = onFx {
        val area = WorkArea()
        Scene(area, 1000.0, 700.0)
        area.resize(1000.0, 700.0); area.layout()
        area.setDrawing(SvgLoader.load(File("samples/three_rects_open_line.svg")))
        fun ids() = area.selected.map { it.source }

        area.click(60.0, 10.0)                 // горен ръб на A
        assertEquals(listOf("rect #A"), ids())
        area.click(160.0, 95.0, ctrl = true)   // вертикалната отсечка на отворената линия D
        assertEquals(listOf("rect #A", "polyline #D"), ids())
        area.click(160.0, 30.0, ctrl = true)   // вътре в B, далеч от линията → нищо
        assertEquals(listOf("rect #A", "polyline #D"), ids())
        area.click(190.0, 30.0, ctrl = true)   // десен ръб на B
        area.click(50.0, 110.0, ctrl = true)   // долен ръб на C
        assertEquals(listOf("rect #A", "polyline #D", "path #B", "polygon #C"), ids())
        area.click(60.0, 10.0, ctrl = true)    // Ctrl+клик върху избран → махане
        assertEquals(listOf("polyline #D", "path #B", "polygon #C"), ids())
        area.click(160.0, 30.0)                // обикновен клик в празно → изчиства
        assertEquals(emptyList<String>(), ids())
        area.click(190.0, 30.0)
        area.clearSelection()
        assertEquals(emptyList<String>(), ids())
    }

    @Test fun `cut panel recalls three contours and applies common settings`() = onFx {
        val drawing = SvgLoader.load(File("samples/three_rects_open_line.svg"))
        val area = area(drawing)
        val panel = CutSettingsPanel()
        area.onSelectionChanged = panel::showSelection
        fun field(id: String) = panel.lookup("#$id") as TextField
        @Suppress("UNCHECKED_CAST")
        val type = panel.lookup("#cutType") as ComboBox<CutType>
        @Suppress("UNCHECKED_CAST")
        val bit = panel.lookup("#cutBit") as ComboBox<Double>
        val apply = panel.lookup("#applyCutSettings") as Button
        val expected = listOf(Triple(60.0, 10.0, CutType.OUTSIDE),
            Triple(190.0, 30.0, CutType.POCKET), Triple(50.0, 110.0, CutType.ON_PATH))
        val depths = listOf(6.0, 3.0, 1.0)
        assertEquals(CutSettings.BITS, bit.items.toList())
        expected.forEachIndexed { i, (x, y, cut) ->
            area.click(x, y)
            type.value = cut; field("cutDepth").text = depths[i].toString(); apply.fire()
        }
        expected.forEachIndexed { i, (x, y, cut) ->
            area.click(x, y)
            assertEquals(cut, type.value)
            assertEquals(depths[i], field("cutDepth").text.toDouble())
        }
        area.click(60.0, 10.0)
        area.click(190.0, 30.0, ctrl = true)
        assertEquals(null, type.value)
        assertEquals("", field("cutDepth").text)
        field("cutFeed").text = "950"; field("cutPlunge").text = "250"
        field("cutPassDepth").text = "0,5"; field("cutRpm").text = "15000"
        bit.value = 2.0; apply.fire()
        drawing.contours.take(2).forEachIndexed { i, c ->
            assertEquals(depths[i], c.cutSettings.depthMm)
            assertEquals(expected[i].third, c.cutSettings.type)
            assertEquals(950.0, c.cutSettings.feedMmMin)
            assertEquals(250.0, c.cutSettings.plungeMmMin)
            assertEquals(0.5, c.cutSettings.passDepthMm)
            assertEquals(15000, c.cutSettings.spindleRpm)
            assertEquals(2.0, c.cutSettings.bitMm)
        }
        val saved = drawing.contours.map { it.cutSettings }
        field("cutDepth").text = "NaN"; apply.fire()
        assertEquals(saved, drawing.contours.map { it.cutSettings })
        area.click(160.0, 95.0, ctrl = true)
        assertEquals(listOf(CutType.ON_PATH), type.items.toList())
        type.value = CutType.ON_PATH; field("cutDepth").text = "2"; apply.fire()
        assertEquals(3, area.selected.size)
        assertEquals(true, area.selected.all { it.cutSettings.type == CutType.ON_PATH && it.cutSettings.depthMm == 2.0 })
        area.clearSelection()
        assertEquals(false, panel.isVisible)
        area.click(50.0, 110.0)
        assertEquals(1.0, field("cutDepth").text.toDouble())
    }

    @Test fun `context menu hits toolpath and original preserves selection and invokes selected export`() = onFx {
        Platform.setImplicitExit(false)
        val a=Contour(1,"rect 1",doubleArrayOf(0.0,0.0,40.0,0.0,40.0,30.0,0.0,30.0,0.0,0.0),true)
        val b=Contour(2,"rect 2",doubleArrayOf(60.0,0.0,100.0,0.0,100.0,30.0,60.0,30.0,60.0,0.0),true)
        CutSettings.applyTo(listOf(a,b)) { it.copy(type=CutType.OUTSIDE,bitMm=6.0) }
        val area=area(SvgDrawing(listOf(a,b),120.0,60.0,emptyMap()))
        val stage=Stage()
        try {
            stage.scene=area.scene;stage.show()
            val p=area.mmToLocal(12.5,-3.0)
            val screen=area.localToScreen(p)
            Event.fireEvent(area,ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,p.x,p.y,screen.x,screen.y,false,null))
            assertEquals(true,area.contourContextMenu.isShowing)
            assertEquals(listOf("Set Start Point Here","Reverse Direction","Export Selected G-code"),area.contourContextMenu.items.map { it.text })
            area.contourContextMenu.hide()
            area.contourContextMenu.items[0].fire()
            assertEquals(12.5,area.previewResult.paths.first { 1 in it.contourIds }.points[0],1e-6)
            assertEquals(-3.0,area.previewResult.paths.first { 1 in it.contourIds }.points[1],1e-6)
            // A right-click on the original line, away from the offset, is also accepted.
            val original=area.mmToLocal(20.0,0.0)
            assertEquals(true,area.prepareContourMenu(original.x,original.y))
            area.click(80.0,140.0,ctrl=true)
            assertEquals(2,area.selected.size)
            assertEquals(true,area.prepareContourMenu(original.x,original.y))
            var exported=emptyList<Contour>()
            area.onExportSelected={ exported=area.selected }
            area.contourContextMenu.items[2].fire()
            assertEquals(listOf(a,b),exported)
            assertEquals(2,area.selected.size)
        } finally { area.contourContextMenu.hide();stage.close() }
    }

    @Test fun `original contour allows start and direction edits with preview hidden`() = onFx {
        val c=Contour(1,"rect",doubleArrayOf(0.0,0.0,40.0,0.0,40.0,30.0,0.0,30.0),true)
        val original=c.points.copyOf()
        CutSettings.applyTo(listOf(c)) { it.copy(type=CutType.OUTSIDE,bitMm=6.0) }
        val area=area(SvgDrawing(listOf(c),80.0,60.0,emptyMap()))
        area.previewVisible=false
        val at=area.mmToLocal(12.5,0.0)
        assertEquals(true,area.prepareContourMenu(at.x,at.y))
        assertEquals(false,area.contourContextMenu.items[0].isDisable)
        assertEquals(false,area.contourContextMenu.items[1].isDisable)
        area.contourContextMenu.items[0].fire()
        assertEquals(12.5,area.previewResult.paths.single().points[0],1e-6)
        assertEquals(-3.0,area.previewResult.paths.single().points[1],1e-6)
        assertEquals(true,area.prepareContourMenu(at.x,at.y))
        val before=area.previewResult.paths.single().points.copyOf()
        area.contourContextMenu.items[1].fire()
        val after=area.previewResult.paths.single().points
        assertEquals(before[0],after[0]);assertEquals(before[1],after[1])
        assertEquals(before[before.size-2],after[2]);assertEquals(before.last(),after[3])
        assertArrayEquals(original,c.points)
        // Hidden toolpaths must not become mouse targets away from the SVG contour.
        area.clearSelection()
        val hidden=area.mmToLocal(12.5,-3.0)
        assertEquals(false,area.prepareContourMenu(hidden.x,hidden.y))
    }

    @Test fun `preview updates immediately from controls moves with geometry and renders separately`() = onFx {
        Platform.setImplicitExit(false)
        val app = SimpleCamApp()
        val stage = Stage()
        try {
            app.start(stage)
            val area = (stage.scene.root as BorderPane).center as WorkArea
            val c = Contour(1,"rect #preview",doubleArrayOf(10.0,10.0,50.0,10.0,50.0,40.0,10.0,40.0,10.0,10.0),true)
            area.setDrawing(SvgDrawing(listOf(c),80.0,60.0,emptyMap()))
            val before = c.points.copyOf()
            area.drag(area.mmToLocal(30.0,10.0),area.mmToLocal(30.0,10.0))
            @Suppress("UNCHECKED_CAST")
            val type = stage.scene.lookup("#cutType") as ComboBox<CutType>
            @Suppress("UNCHECKED_CAST")
            val bit = stage.scene.lookup("#cutBit") as ComboBox<Double>
            val toggle = stage.scene.lookup("#previewToggle") as CheckBox
            fun previewBounds() = area.previewResult.paths.single().let { Contour(0,"preview",it.points,it.closed).bounds }
            type.value=CutType.OUTSIDE; bit.value=6.0
            assertEquals(46.0,previewBounds().width,0.0021)
            assertEquals(7.0,previewBounds().minX,0.0021)
            bit.value=2.0 // no Apply button needed
            assertEquals(42.0,previewBounds().width,0.0021)
            type.value=CutType.INSIDE
            assertEquals(38.0,previewBounds().width,1e-6)
            assertArrayEquals(before,c.points)
            fun pinkPixels(): Int {
                val image=area.snapshot(null,null)
                val reader=image.pixelReader
                var count=0
                for (y in 0 until image.height.toInt()) for (x in 0 until image.width.toInt()) {
                    val color=reader.getColor(x,y)
                    if (color.red>0.65 && color.green<0.1 && color.blue>0.3 && color.blue<0.6) count++
                }
                return count
            }
            assertEquals(true,pinkPixels()>0,"Calculated path must be rendered")
            toggle.fire()
            assertEquals(false,area.previewVisible)
            assertEquals(0,pinkPixels(),"Preview OFF must hide the overlay")
            toggle.fire()
            assertEquals(true,pinkPixels()>0)
            val oldBounds=previewBounds()
            area.moveSelectionTo(20.0,30.0)
            assertEquals(oldBounds.minX+10,previewBounds().minX,1e-6)
            assertEquals(oldBounds.minY+20,previewBounds().minY,1e-6)
            val prior=area.previewResult
            area.drag(Point2D(100.0,100.0),Point2D(130.0,120.0),MouseButton.MIDDLE)
            assertSame(prior,area.previewResult,"Pan must not recalculate machine geometry")
            type.value=CutType.POCKET
            assertEquals(true,area.previewResult.paths.isEmpty())
            assertEquals(true,area.previewResult.warnings.single().contains("Pocket preview not implemented"))
            type.value=CutType.ON_PATH
            assertArrayEquals(c.points,area.previewResult.paths.single().points)
        } finally { stage.close() }
    }
}
