package org.example.danicnc

import javafx.animation.AnimationTimer
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import java.util.Locale

/** A fixed G-code snapshot: editing the main drawing cannot change a running simulation. */
class GCodeSimulationPane(private val source: String) : BorderPane() {
    private val viewport=Pane()
    private val paths=Canvas()
    private val segment=Line().apply { stroke=Color.web("#ff1744"); strokeWidth=3.0; isMouseTransparent=true }
    private val marker=Circle(7.0).apply { id="simulationMarker"; stroke=Color.WHITE; strokeWidth=2.0; isMouseTransparent=true }
    private val play=Button("Play").apply { id="simulationPlay" }
    private val reset=Button("Reset").apply { id="simulationReset" }
    private val speed=ComboBox<Int>(FXCollections.observableArrayList(1,2,4,8,16,32,64)).apply {
        id="simulationSpeed"; value=1
        converter=object : javafx.util.StringConverter<Int>() {
            override fun toString(value: Int?) = "${value ?: 1}×"
            override fun fromString(value: String) = value.removeSuffix("×").toInt()
        }
    }
    private val rapid=TextField("1500").apply { id="simulationRapid"; prefColumnCount=6 }
    private val timeline=Slider().apply { id="simulationTimeline"; min=0.0 }
    private val status=Label().apply { id="simulationStatus" }
    private val code=Label().apply { id="simulationLine"; style="-fx-font-family: monospace;" }
    private val error=Label().apply { style="-fx-text-fill: #b71c1c;"; isWrapText=true }
    internal var playback=SimulationPlayback(GCodeSimulation.parse(source,1500.0))
        private set
    private var updating=false
    private var scale=1.0; private var tx=0.0; private var ty=0.0
    private var lastX=0.0; private var lastY=0.0
    private var previousNanos=0L
    private val timer=object : AnimationTimer() {
        override fun handle(now: Long) {
            if(previousNanos!=0L) playback.advance((now-previousNanos)/1e9)
            previousNanos=now
            updateFrame()
            if(!playback.playing) { stop(); previousNanos=0L }
        }
    }

    init {
        viewport.id="simulationViewport"
        paths.isManaged=false; segment.isManaged=false; marker.isManaged=false
        viewport.children.addAll(paths,segment,marker)
        viewport.minWidth=200.0; viewport.minHeight=200.0
        center=viewport
        val fit=Button("Fit").apply { setOnAction { fit() } }
        val applyRapid=Button("Apply G0").apply { id="simulationApplyRapid"; setOnAction { rebuild() } }
        val row=HBox(8.0,play,reset,Label("Speed:"),speed,fit,Separator(),Label("G0, mm/min:"),rapid,applyRapid).apply {
            alignment=Pos.CENTER_LEFT
        }
        top=VBox(6.0,row,Label("G-code snapshot · 1× = programmed feed, constant speed; acceleration is not modelled."),
            Label("Start assumed X0 Y0 Z+5 · Orange: G0 · Green: G1 · Purple dot: plunge · Red dot: cutting"),error).apply {
            padding=Insets(10.0)
        }
        bottom=VBox(6.0,timeline,status,code).apply { padding=Insets(10.0) }
        timeline.max=playback.program.totalSeconds
        timeline.valueProperty().addListener { _,_,value ->
            if(!updating) { playback.seek(value.toDouble()); previousNanos=0L; updateFrame() }
        }
        play.setOnAction {
            if(playback.playing) pause() else { playback.play(); previousNanos=0L; timer.start(); updateFrame() }
        }
        reset.setOnAction { pause(); playback.reset(); updateFrame() }
        speed.valueProperty().addListener { _,_,value -> playback.speed=value.toDouble(); previousNanos=0L }
        rapid.setOnAction { rebuild() }
        viewport.widthProperty().addListener { _,_,_ -> fit() }
        viewport.heightProperty().addListener { _,_,_ -> fit() }
        viewport.setOnScroll { event ->
            val next=(scale*if(event.deltaY>0) 1.15 else 1/1.15).coerceIn(0.01,5000.0)
            val factor=next/scale
            tx=event.x-(event.x-tx)*factor; ty=event.y-(event.y-ty)*factor; scale=next
            drawPaths(); updateFrame(); event.consume()
        }
        viewport.setOnMousePressed { event -> lastX=event.x; lastY=event.y }
        viewport.setOnMouseDragged { event ->
            tx+=event.x-lastX; ty+=event.y-lastY; lastX=event.x; lastY=event.y
            drawPaths(); updateFrame()
        }
        updateFrame()
    }

    fun pause() { playback.pause(); timer.stop(); previousNanos=0L; updateFrame() }
    fun dispose() { pause() }

    private fun rebuild() {
        pause()
        try {
            val rate=rapid.text.trim().replace(',','.').toDoubleOrNull()
            require(rate!=null && rate.isFinite() && rate>0) { "Enter a positive G0 speed in mm/min." }
            val model=GCodeSimulation.parse(source,rate)
            playback=SimulationPlayback(model).also { it.speed=speed.value.toDouble() }
            timeline.max=model.totalSeconds
            error.text=""; updateFrame()
        } catch(e: IllegalArgumentException) { error.text=e.message }
    }

    private fun fit() {
        val moves=playback.program.moves
        val x0=moves.minOf { minOf(it.from.x,it.to.x) }; val x1=moves.maxOf { maxOf(it.from.x,it.to.x) }
        val y0=moves.minOf { minOf(it.from.y,it.to.y) }; val y1=moves.maxOf { maxOf(it.from.y,it.to.y) }
        val w=viewport.width; val h=viewport.height
        if(w<=0 || h<=0) return
        scale=minOf((w-80).coerceAtLeast(1.0)/(x1-x0).coerceAtLeast(1.0),
            (h-80).coerceAtLeast(1.0)/(y1-y0).coerceAtLeast(1.0)).coerceIn(0.01,5000.0)
        tx=w/2-(x0+x1)/2*scale; ty=h/2+(y0+y1)/2*scale
        drawPaths(); updateFrame()
    }
    private fun sx(x: Double)=tx+x*scale
    private fun sy(y: Double)=ty-y*scale

    private fun drawPaths() {
        paths.width=viewport.width; paths.height=viewport.height
        val g=paths.graphicsContext2D
        g.fill=Color.web("#14202b"); g.fillRect(0.0,0.0,paths.width,paths.height)
        g.stroke=Color.web("#607d8b"); g.lineWidth=1.0
        g.strokeLine(sx(0.0)-10,sy(0.0),sx(0.0)+10,sy(0.0))
        g.strokeLine(sx(0.0),sy(0.0)-10,sx(0.0),sy(0.0)+10)
        g.fill=Color.WHITE; g.fillText("0,0",sx(0.0)+12,sy(0.0)+16)
        // Two batches keep rendering efficient even for a large Pocket program.
        for(rapidMove in listOf(true,false)) {
            g.stroke=Color.web(if(rapidMove) "#ffab40" else "#69d88c")
            g.lineWidth=if(rapidMove) 1.0 else 1.4
            if(rapidMove) g.setLineDashes(5.0,5.0) else g.setLineDashes()
            g.beginPath()
            for(move in playback.program.moves) if(move.rapid==rapidMove) {
                g.moveTo(sx(move.from.x),sy(move.from.y)); g.lineTo(sx(move.to.x),sy(move.to.y))
            }
            g.stroke()
        }
        g.setLineDashes()
    }

    private fun updateFrame() {
        val frame=playback.program.frame(playback.seconds)
        val p=frame.position; val move=frame.move
        marker.centerX=sx(p.x); marker.centerY=sy(p.y)
        val plunge=!move.rapid && move.from.x==move.to.x && move.from.y==move.to.y
        marker.fill=Color.web(if(move.rapid) "#ffab40" else if(plunge) "#d580ff" else "#ff1744")
        segment.startX=sx(move.from.x); segment.startY=sy(move.from.y)
        segment.endX=sx(p.x); segment.endY=sy(p.y)
        segment.stroke=marker.fill
        updating=true
        timeline.value=playback.seconds
        updating=false
        play.text=if(playback.playing) "Pause" else if(playback.seconds>=playback.program.totalSeconds) "Replay" else "Play"
        val state=if(playback.seconds>=playback.program.totalSeconds) "Finished" else if(playback.playing) "Playing" else "Paused"
        status.text=String.format(Locale.ROOT,"%s  %s / %s   X %.3f  Y %.3f  Z %.3f mm   %s %.0f mm/min",
            state,time(playback.seconds),time(playback.program.totalSeconds),p.x,p.y,p.z,if(move.rapid) "G0" else "G1",move.rateMmMin)
        code.text="Line ${move.line}: ${playback.program.sourceLines[move.line-1]}"
    }

    private fun time(seconds: Double): String {
        val whole=seconds.toLong()
        return "%02d:%02d:%02d".format(Locale.ROOT,whole/3600,(whole/60)%60,whole%60)
    }
}
