package org.example.danicnc

import javafx.geometry.Point2D
import kotlin.math.abs

/** Locations are relative to the owner bounds, so explicit geometry moves retain the chosen start. */
data class PathTraversal(
    val key: Long,
    val referenceCentre: Point2D,
    val closed: Boolean,
    val winding: Int,
    var anchor: Point2D? = null,
    var reversed: Boolean = false,
)

object ToolpathTraversal {
    data class Projection(val point: Point2D, val segment: Int, val distance: Double)
    private fun vertices(path: PreviewPath): List<Point2D> {
        val p = path.points.toList().chunked(2).filter { it.size == 2 }.map { Point2D(it[0],it[1]) }.toMutableList()
        if (path.closed && p.size > 1 && p.first() == p.last()) p.removeAt(p.lastIndex)
        return p
    }
    private fun origin(c: Contour) = Point2D(c.bounds.minX,c.bounds.minY)
    private fun centre(p: PreviewPath): Point2D {
        val v=vertices(p)
        return Point2D((v.minOf { it.x }+v.maxOf { it.x })/2,(v.minOf { it.y }+v.maxOf { it.y })/2)
    }
    private fun winding(p: PreviewPath): Int {
        if (!p.closed) return 0
        val polygon=com.github.micycle1.clipper2.core.PathD()
        vertices(p).forEach { polygon.add(com.github.micycle1.clipper2.core.PointD(it.x,it.y)) }
        return if (com.github.micycle1.clipper2.Clipper.area(polygon)>=0) 1 else -1
    }
    /** Segment projection is only start placement; Clipper2 remains the sole offset engine. */
    fun nearest(path: PreviewPath, at: Point2D): Projection {
        val v=vertices(path)
        require(v.size>=2) { "No valid trajectory for start point." }
        if (!path.closed) {
            val p=if (at.distance(v.first())<=at.distance(v.last())) v.first() else v.last()
            return Projection(p,if (p==v.first()) 0 else v.lastIndex,at.distance(p))
        }
        return v.indices.map { i ->
            val a=v[i];val b=v[(i+1)%v.size];val d=b.subtract(a)
            val length=d.dotProduct(d)
            val t=if (length==0.0) 0.0 else (at.subtract(a).dotProduct(d)/length).coerceIn(0.0,1.0)
            val p=a.add(d.multiply(t))
            Projection(p,i,at.distance(p))
        }.minBy { it.distance }
    }
    /** For mouse hit testing, open paths also use their full line, not just endpoints. */
    fun distance(path: PreviewPath, at: Point2D): Double {
        if (path.closed) return nearest(path,at).distance
        val v=vertices(path)
        if(v.size<2) return Double.POSITIVE_INFINITY
        return v.zipWithNext().minOf { (a,b) ->
            val d=b.subtract(a);val length=d.dotProduct(d)
            val t=if(length==0.0) 0.0 else (at.subtract(a).dotProduct(d)/length).coerceIn(0.0,1.0)
            at.distance(a.add(d.multiply(t)))
        }
    }
    fun setStart(path: PreviewPath, contours: List<Contour>, at: Point2D) {
        val owner=owner(path,contours)
        val state=state(path,owner)
        state.anchor=nearest(path,at).point.subtract(origin(owner))
    }
    fun reverse(path: PreviewPath, contours: List<Contour>) {
        val owner=owner(path,contours)
        val state=state(path,owner)
        state.reversed=!state.reversed
        // Closed paths keep their start; an open path must start from the opposite endpoint.
        val v=vertices(path)
        state.anchor=(if(path.closed) v.first() else v.last()).subtract(origin(owner))
    }
    private fun owner(path: PreviewPath,contours: List<Contour>): Contour {
        path.traversalOwnerId?.let { id -> return contours.first { it.id==id } }
        return contours.filter { it.id in path.contourIds }.minBy { c ->
            vertices(path).map { c.distanceTo(it.x,it.y) }.average()
        }
    }
    private fun state(path: PreviewPath, owner: Contour): PathTraversal {
        path.traversalKey?.let { key -> owner.pathTraversals.find { it.key==key }?.let { return it } }
        val key=(owner.pathTraversals.maxOfOrNull { it.key } ?: 0L)+1
        return PathTraversal(key,centre(path).subtract(origin(owner)),path.closed,winding(path))
            .also { owner.pathTraversals.add(it) }
    }
    fun apply(paths: List<PreviewPath>, contours: List<Contour>): List<PreviewPath> {
        val result=paths.toMutableList()
        val assigned=mutableSetOf<Int>()
        for (owner in contours) {
            val available=paths.indices.filter { owner.id in paths[it].contourIds && it !in assigned }.toMutableSet()
            // Match edited components one-to-one by winding and position, not Clipper's changing output order.
            for(state in owner.pathTraversals) {
                val compatible=available.filter { paths[it].closed==state.closed }
                val sameWinding=compatible.filter { winding(paths[it])==state.winding }
                val i=(sameWinding.ifEmpty { compatible })
                    .minByOrNull { centre(paths[it]).subtract(origin(owner)).distance(state.referenceCentre) } ?: continue
                available.remove(i)
                assigned.add(i)
                result[i]=applyState(paths[i],state,origin(owner)).copy(traversalOwnerId=owner.id)
            }
        }
        return result
    }
    private fun applyState(path: PreviewPath,state: PathTraversal,origin: Point2D): PreviewPath {
        val v=vertices(path)
        if(v.size<2) return path
        val ordered: List<Point2D>
        if(!path.closed) {
            val anchor=state.anchor?.add(origin)
            val fromEnd=if(anchor!=null) anchor.distance(v.last())<anchor.distance(v.first()) else state.reversed
            ordered=if(fromEnd) v.reversed() else v
        } else {
            val projection=nearest(path,state.anchor?.add(origin) ?: v.first())
            val forward=mutableListOf(projection.point)
            for(j in 1..v.size) {
                val p=v[(projection.segment+j)%v.size]
                if(p.distance(projection.point)>1e-10) forward.add(p)
            }
            val desiredWinding=state.winding*(if(state.reversed) -1 else 1)
            ordered=if(winding(path)!=desiredWinding) listOf(forward.first())+forward.drop(1).reversed() else forward
        }
        val xy=DoubleArray(ordered.size*2)
        ordered.forEachIndexed { i,p -> xy[2*i]=p.x;xy[2*i+1]=p.y }
        return path.copy(points=xy,traversalKey=state.key)
    }
}
