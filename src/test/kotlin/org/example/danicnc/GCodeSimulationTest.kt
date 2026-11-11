package org.example.danicnc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GCodeSimulationTest {
    private val program="G21\nG90\nG17\nG94\nM5\nG0 Z5\nG0 X30 Y40\nM3 S12000\nG1 Z-2 F120\nG1 X60 Y80 F600\nG1 X90\nG0 Z5\nM5\nM2"

    @Test fun `timing follows XYZ distance rapid and modal feed in minutes`() {
        val model=GCodeSimulation.parse(program,1500.0)
        assertEquals(5,model.moves.size)
        assertEquals(2.0,model.moves[0].durationSeconds,1e-9)
        assertEquals(3.5,model.moves[1].durationSeconds,1e-9)
        assertEquals(5.0,model.moves[2].durationSeconds,1e-9)
        assertEquals(3.0,model.moves[3].durationSeconds,1e-9)
        assertEquals(0.28,model.moves[4].durationSeconds,1e-9)
        assertEquals(13.78,model.totalSeconds,1e-9)
        assertEquals(SimPosition(15.0,20.0,5.0),model.frame(1.0).position)
        assertEquals(1.5,model.frame(3.75).position.z,1e-9)
        assertEquals(SimPosition(45.0,60.0,-2.0),model.frame(8.0).position)
        assertEquals(SimPosition(90.0,80.0,5.0),model.frame(1000.0).position)
        assertEquals(7,model.frame(0.0).move.line)
        assertTrue(model.frame(0.0).move.rapid)
        assertFalse(model.frame(2.0).move.rapid)
    }

    @Test fun `play pause multipliers seek and replay never lose position`() {
        val playback=SimulationPlayback(GCodeSimulation.parse(program,1500.0))
        playback.advance(10.0); assertEquals(0.0,playback.seconds)
        playback.play(); playback.advance(1.0); assertEquals(1.0,playback.seconds)
        playback.pause(); playback.advance(10.0); assertEquals(1.0,playback.seconds)
        playback.play(); playback.speed=2.0; playback.advance(1.0); assertEquals(3.0,playback.seconds)
        playback.speed=4.0; playback.advance(0.5); assertEquals(5.0,playback.seconds)
        playback.speed=8.0; playback.advance(0.5); assertEquals(9.0,playback.seconds)
        playback.seek(4.0); assertEquals(4.0,playback.seconds)
        playback.advance(100.0); assertEquals(playback.program.totalSeconds,playback.seconds); assertFalse(playback.playing)
        playback.play(); assertEquals(0.0,playback.seconds); assertTrue(playback.playing)
        playback.reset(); assertEquals(0.0,playback.seconds); assertFalse(playback.playing)
    }

    @Test fun `frame count does not change elapsed program time`() {
        val model=GCodeSimulation.parse(program,1500.0)
        val a=SimulationPlayback(model).apply { play(); advance(1.0) }
        val b=SimulationPlayback(model).apply { play(); repeat(120) { advance(1.0/120) } }
        assertEquals(a.seconds,b.seconds,1e-9)
        assertEquals(model.frame(a.seconds).position.x,model.frame(b.seconds).position.x,1e-9)
    }

    @Test fun `rapid rate affects only G0 and feed changes remain modal`() {
        val a=GCodeSimulation.parse(program,1500.0); val b=GCodeSimulation.parse(program,3000.0)
        a.moves.zip(b.moves).forEach { (x,y) ->
            assertEquals(x.durationSeconds/(if(x.rapid) 2 else 1),y.durationSeconds,1e-9)
        }
        val compact=GCodeSimulation.parse("G21 G90 G94\nG1X3Y4F60\nX6Y8\nM2",1500.0)
        assertEquals(10.0,compact.totalSeconds,1e-9)
    }

    @Test fun `unsupported modes malformed code and absent feed fail explicitly`() {
        for (bad in listOf("G20","G91","G93","G2 X10 I5","G28","G1 X5","G1 X5 F0","G1 X5 F10 Q3","G1 X5 X6 F10","G1 X10 F10 garbage")) {
            assertThrows(Exception::class.java) { GCodeSimulation.parse("G21 G90 G94\n$bad",1500.0) }
        }
        assertThrows(IllegalArgumentException::class.java) { GCodeSimulation.parse("G0 X5",1500.0) }
        assertThrows(IllegalArgumentException::class.java) { GCodeSimulation.parse(program,0.0) }
    }

    @Test fun `exported spiral plays all depth passes and final retract`() {
        val c=Contour(1,"rect",doubleArrayOf(0.0,0.0,40.0,0.0,40.0,20.0,0.0,20.0,0.0,0.0),true)
        c.cutSettings=CutSettings(type=CutType.POCKET,bitMm=2.0,depthMm=2.5,passDepthMm=1.0)
        val code=GCodeExporter.generate(listOf(c),listOf(c),ExportScope.SELECTED,true)
        val model=GCodeSimulation.parse(code,1500.0)
        assertEquals(listOf(-1.0,-2.0,-2.5),model.moves.filter { it.from.z>it.to.z }.map { it.to.z })
        assertEquals(5.0,model.frame(model.totalSeconds).position.z)
        assertTrue(model.moves.filter { !it.rapid }.all { it.rateMmMin==200.0 || it.rateMmMin==600.0 })
    }
}
