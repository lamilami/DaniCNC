package org.example.danicnc

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import javafx.stage.Window
import javafx.stage.WindowEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class ProjectWorkspaceTest {
    @TempDir lateinit var temp: Path
    companion object {
        @JvmStatic @BeforeAll fun startFx() { try { Platform.startup {} } catch(_: IllegalStateException) { } }
    }
    private fun onFx(block: () -> Unit) {
        var result: Result<Unit>?=null
        val latch=CountDownLatch(1)
        Platform.runLater { result=runCatching(block); latch.countDown() }
        assertTrue(latch.await(20,TimeUnit.SECONDS)); result!!.getOrThrow()
    }
    private fun answerDialog(owner: Stage, answer: String) {
        Platform.runLater {
            val dialog=Window.getWindows().filterIsInstance<Stage>().first { it.owner==owner && it.scene.root is DialogPane }
            val pane=dialog.scene.root as DialogPane
            (pane.lookupButton(pane.buttonTypes.first { it.text==answer }) as Button).fire()
        }
    }

    @Test fun `save and reopen restores workspace and unsaved guard supports cancel discard and save`() = onFx {
        val svg=temp.resolve("test.svg")
        Files.writeString(svg,"""<svg xmlns="http://www.w3.org/2000/svg" width="100mm" height="60mm" viewBox="0 0 100 60"><rect x="5" y="5" width="60" height="40"/></svg>""")
        val stage=Stage()
        try {
            val app=SimpleCamApp(); app.start(stage); app.loadFile(svg.toFile())
            val area=(stage.scene.root as BorderPane).center as WorkArea
            val contour=area.projectContours.single()
            contour.cutSettings=CutSettings(type=CutType.POCKET,bitMm=2.0,stepOverPercent=30.0)
            contour.translate(3.0,4.0); area.refreshPreview()
            val snap=stage.scene.lookup("#snapToGrid") as CheckBox
            snap.fire()
            val grid=stage.scene.lookup("#gridStep") as ComboBox<Double>
            grid.value=5.0
            assertTrue(stage.title.endsWith("*"))
            val saved=temp.resolve("work.dcnc")
            app.saveProjectTo(saved.toFile())
            assertFalse(stage.title.endsWith("*"))
            Files.delete(svg)
            app.loadFile(saved.toFile())
            assertEquals(CutType.POCKET,area.projectContours.single().cutSettings.type)
            assertEquals(30.0,area.projectContours.single().cutSettings.stepOverPercent)
            assertArrayEquals(contour.points,area.projectContours.single().points)
            assertEquals(5.0,grid.value); assertFalse(snap.isSelected)
            assertFalse(stage.title.endsWith("*"))
            assertNotNull(stage.scene.lookup("#openProject")); assertNotNull(stage.scene.lookup("#saveProject"))
            area.projectContours.single().cutSettings=contour.cutSettings.copy(depthMm=3.0)
            answerDialog(stage,"Cancel"); app.loadFile(saved.toFile())
            assertEquals(3.0,area.projectContours.single().cutSettings.depthMm)
            assertTrue(stage.title.endsWith("*"))
            answerDialog(stage,"Discard"); app.loadFile(saved.toFile())
            assertEquals(1.0,area.projectContours.single().cutSettings.depthMm)
            area.projectContours.single().cutSettings=contour.cutSettings.copy(depthMm=2.0)
            answerDialog(stage,"Save"); app.loadFile(saved.toFile())
            assertEquals(2.0,ProjectIO.load(saved).drawing.contours.single().cutSettings.depthMm)
            assertEquals(2.0,area.projectContours.single().cutSettings.depthMm)
            val menu=stage.scene.lookup(".menu-bar") as MenuBar
            val save=menu.menus.single().items.first { it.text=="Save Project" }
            area.projectContours.single().cutSettings=contour.cutSettings.copy(depthMm=4.0)
            save.fire()
            assertEquals(4.0,ProjectIO.load(saved).drawing.contours.single().cutSettings.depthMm)
            assertFalse(stage.title.endsWith("*"))
            stage.scene.root.applyCss(); stage.scene.root.layout()
            Files.createDirectories(Path.of("target"))
            ImageIO.write(SwingFXUtils.fromFXImage(stage.scene.snapshot(null),null),"png",Path.of("target/project-workspace.png").toFile())
            area.projectContours.single().cutSettings=contour.cutSettings.copy(depthMm=5.0)
            answerDialog(stage,"Cancel")
            stage.fireEvent(WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST))
            assertTrue(stage.isShowing)
            assertEquals(4.0,ProjectIO.load(saved).drawing.contours.single().cutSettings.depthMm)
            answerDialog(stage,"Save")
            stage.fireEvent(WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST))
            assertFalse(stage.isShowing)
            assertEquals(5.0,ProjectIO.load(saved).drawing.contours.single().cutSettings.depthMm)
        } finally { stage.hide() }
    }
}
