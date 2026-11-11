package org.example.danicnc

import kotlin.math.sqrt

data class SimPosition(val x: Double, val y: Double, val z: Double) {
    fun interpolate(to: SimPosition, t: Double) = SimPosition(x+(to.x-x)*t,y+(to.y-y)*t,z+(to.z-z)*t)
    fun distance(to: SimPosition) = sqrt((to.x-x)*(to.x-x)+(to.y-y)*(to.y-y)+(to.z-z)*(to.z-z))
}

data class SimMove(val from: SimPosition, val to: SimPosition, val rapid: Boolean,
                   val rateMmMin: Double, val line: Int, val endSeconds: Double, val durationSeconds: Double)
data class SimFrame(val position: SimPosition, val move: SimMove)

/** Strict reader for the linear, absolute millimetre G-code emitted by our exporter. */
class GCodeSimulation private constructor(val moves: List<SimMove>, val sourceLines: List<String>) {
    val totalSeconds: Double get() = moves.last().endSeconds

    fun frame(seconds: Double): SimFrame {
        require(seconds.isFinite())
        val time = seconds.coerceIn(0.0,totalSeconds)
        if(time>=totalSeconds) return SimFrame(moves.last().to,moves.last())
        var low=0; var high=moves.lastIndex
        while (low<high) {
            val mid=(low+high)/2
            if (moves[mid].endSeconds<=time) low=mid+1 else high=mid
        }
        val move=moves[low]
        val fraction=((time-(move.endSeconds-move.durationSeconds))/move.durationSeconds).coerceIn(0.0,1.0)
        return SimFrame(move.from.interpolate(move.to,fraction),move)
    }

    companion object {
        // Work origin and safe Z are assumptions for preview, not a machine position readout.
        val INITIAL_POSITION = SimPosition(0.0,0.0,5.0)
        private val word = Regex("([A-Z])([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))")

        fun parse(program: String, rapidMmMin: Double): GCodeSimulation {
            require(rapidMmMin.isFinite() && rapidMmMin>0) { "G0 speed must be positive." }
            val lines=program.lines()
            val moves=mutableListOf<SimMove>()
            var position=INITIAL_POSITION
            var feed: Double?=null
            var motion: Int?=null
            var mm=false; var absolute=false; var perMinute=false; var ended=false
            var time=0.0
            lines.forEachIndexed { index, raw ->
                val line=raw.substringBefore(';').replace(Regex("\\([^()]*\\)"),"").uppercase().trim()
                if (line.isEmpty()) return@forEachIndexed
                require(!ended) { "Unexpected command after M2 at line ${index+1}" }
                val words=word.findAll(line).toList()
                require(line.replace(word,"").isBlank()) { "Invalid G-code at line ${index+1}" }
                val axes=mutableMapOf<Char,Double>()
                var lineMotion: Int?=null
                for (match in words) {
                    val letter=match.groupValues[1][0]
                    val value=match.groupValues[2].toDouble()
                    require(value.isFinite()) { "Invalid number at line ${index+1}" }
                    when(letter) {
                        'G' -> when(value) {
                            0.0,1.0 -> { require(lineMotion==null); lineMotion=value.toInt(); motion=lineMotion }
                            17.0 -> Unit
                            21.0 -> mm=true
                            90.0 -> absolute=true
                            94.0 -> perMinute=true
                            else -> error("Unsupported G$value at line ${index+1}")
                        }
                        'M' -> { require(value in listOf(2.0,3.0,5.0)) { "Unsupported M$value" }; if(value==2.0) ended=true }
                        'S' -> require(value>=0) { "Invalid spindle speed" }
                        'F' -> { require(value>0) { "Invalid feed at line ${index+1}" }; feed=value }
                        'X','Y','Z' -> { require(axes.put(letter,value)==null) { "Duplicate axis at line ${index+1}" } }
                        else -> error("Unsupported word $letter at line ${index+1}")
                    }
                }
                if (axes.isNotEmpty()) {
                    require(mm && absolute && perMinute && motion!=null) { "Expected G21 G90 G94 and G0/G1 before motion" }
                    val to=SimPosition(axes['X'] ?: position.x,axes['Y'] ?: position.y,axes['Z'] ?: position.z)
                    val rapid=motion==0
                    val rate=if(rapid) rapidMmMin else requireNotNull(feed) { "Missing feed at line ${index+1}" }
                    val duration=position.distance(to)/rate*60
                    require(duration.isFinite() && (time+duration).isFinite()) { "Motion time out of range" }
                    if(duration>0) {
                        time+=duration
                        moves.add(SimMove(position,to,rapid,rate,index+1,time,duration))
                    }
                    position=to
                }
            }
            require(moves.isNotEmpty()) { "No movements to simulate." }
            return GCodeSimulation(moves.toList(),lines)
        }
    }
}

/** Playback uses elapsed monotonic time, so frame rate never changes the feed rate. */
class SimulationPlayback(val program: GCodeSimulation) {
    var seconds=0.0
        private set
    var playing=false
        private set
    var speed=1.0
        set(value) { require(value in listOf(1.0,2.0,4.0,8.0,16.0,32.0,64.0)); field=value }
    fun play() { if(seconds>=program.totalSeconds) seconds=0.0; playing=true }
    fun pause() { playing=false }
    fun reset() { playing=false; seconds=0.0 }
    fun seek(value: Double) {
        require(value.isFinite())
        seconds=value.coerceIn(0.0,program.totalSeconds)
        if(seconds>=program.totalSeconds) playing=false
    }
    fun advance(realSeconds: Double) {
        require(realSeconds.isFinite() && realSeconds>=0)
        if(playing) seek(seconds+realSeconds*speed)
    }
}
