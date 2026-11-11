package org.example.danicnc

import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.shape.Circle
import javafx.stage.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class GCodeSimulationPaneTest {
    companion object {
        @JvmStatic @BeforeAll fun startFx() {
            try { Platform.startup {} } catch (_: IllegalStateException) { }
        }
    }
    private fun onFx(block: () -> Unit) {
        var result: Result<Unit>?=null
        val latch=CountDownLatch(1)
        Platform.runLater { result=runCatching(block); latch.countDown() }
        assertTrue(latch.await(20,TimeUnit.SECONDS)); result!!.getOrThrow()
    }
    @Test fun `animation timer advances in real time and remains stationary while paused`() {
        lateinit var pane: GCodeSimulationPane
        lateinit var stage: Stage
        var pausedAt=0.0
        onFx {
            pane=GCodeSimulationPane("G21 G90 G94\nG1 X100 F60\nM2")
            stage=Stage().apply { scene=Scene(pane,1050.0,740.0); show() }
            (pane.lookup("#simulationPlay") as Button).fire()
        }
        try {
            Thread.sleep(400)
            onFx {
                assertTrue(pane.playback.seconds>0.05,"AnimationTimer must advance the G-code clock")
                pane.pause(); pausedAt=pane.playback.seconds
            }
            Thread.sleep(100)
            onFx {
                assertEquals(pausedAt,pane.playback.seconds)
                (pane.lookup("#simulationSpeed") as ComboBox<Int>).value=8
                (pane.lookup("#simulationPlay") as Button).fire()
            }
            Thread.sleep(400)
            onFx {
                pane.pause()
                assertTrue(pane.playback.seconds-pausedAt>1.0,"8x playback must advance the program faster")
            }
        } finally { onFx { pane.dispose(); stage.close() } }
    }
    @Test fun `controls move marker pause reset seek and change rapid rate`() = onFx {
        val c=Contour(1,"rect",doubleArrayOf(0.0,0.0,80.0,0.0,80.0,40.0,0.0,40.0,0.0,0.0),true)
        c.cutSettings=CutSettings(type=CutType.POCKET,bitMm=6.0,depthMm=3.0)
        val pane=GCodeSimulationPane(GCodeExporter.generate(listOf(c),listOf(c),ExportScope.SELECTED,true))
        val stage=Stage().apply { scene=Scene(pane,1050.0,740.0); show() }
        try {
            pane.applyCss(); pane.layout()
            val marker=pane.lookup("#simulationMarker") as Circle
            val x=marker.centerX; val y=marker.centerY
            val slider=pane.lookup("#simulationTimeline") as Slider
            slider.value=10.0
            assertTrue(marker.centerX!=x || marker.centerY!=y)
            assertEquals(10.0,pane.playback.seconds,1e-9)
            (pane.lookup("#simulationSpeed") as ComboBox<Int>).value=8
            assertEquals(8.0,pane.playback.speed)
            (pane.lookup("#simulationPlay") as Button).fire(); assertTrue(pane.playback.playing)
            (pane.lookup("#simulationPlay") as Button).fire(); assertFalse(pane.playback.playing)
            assertEquals(10.0,pane.playback.seconds)
            assertTrue((pane.lookup("#simulationStatus") as Label).text.contains("Z "))
            assertTrue((pane.lookup("#simulationLine") as Label).text.startsWith("Line "))
            val duration=pane.playback.program.totalSeconds
            (pane.lookup("#simulationRapid") as TextField).text="3000"
            (pane.lookup("#simulationApplyRapid") as Button).fire()
            assertTrue(pane.playback.program.totalSeconds<duration)
            assertEquals(0.0,pane.playback.seconds)
            slider.value=20.0
            Files.createDirectories(Path.of("target"))
            ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null,null),null),"png",Path.of("target/gcode-simulation.png").toFile())
            (pane.lookup("#simulationReset") as Button).fire()
            assertEquals(0.0,pane.playback.seconds)
            assertEquals(x,marker.centerX,1e-6); assertEquals(y,marker.centerY,1e-6)
        } finally { pane.dispose(); stage.close() }
    }
}
