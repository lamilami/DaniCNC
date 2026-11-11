package org.example.danicnc

import javafx.geometry.Point2D
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLOutputFactory
import kotlin.math.abs

data class ProjectState(val drawing: SvgDrawing, val sourceName: String,
                        val gridStep: Double = 10.0, val snapToGrid: Boolean = true,
                        val previewVisible: Boolean = true)

/** Versioned, self-contained XML. Toolpaths are recalculated from exact saved doubles. */
object ProjectIO {
    private const val MAX_BYTES = 64 * 1024 * 1024

    fun encode(project: ProjectState): ByteArray {
        validate(project)
        val output=ByteArrayOutputStream()
        val xml=XMLOutputFactory.newFactory().createXMLStreamWriter(output,"UTF-8")
        fun start(name: String, vararg attributes: Pair<String,Any>) {
            xml.writeStartElement(name)
            attributes.forEach { (key,value) -> xml.writeAttribute(key,value.toString()) }
        }
        fun text(name: String, value: String) { start(name); xml.writeCharacters(value); xml.writeEndElement() }
        xml.writeStartDocument("UTF-8","1.0")
        start("dcnc","version" to 1,"units" to "mm","sourceName" to project.sourceName)
        start("workspace","gridStep" to project.gridStep,"snapToGrid" to project.snapToGrid,"previewVisible" to project.previewVisible)
        xml.writeEndElement()
        project.drawing.originalSvg?.let { text("original-svg",Base64.getEncoder().encodeToString(it)) }
        start("drawing","width" to project.drawing.docWidthMm,"height" to project.drawing.docHeightMm)
        start("skipped")
        project.drawing.skipped.forEach { (name,count) -> start("element","name" to name,"count" to count); xml.writeEndElement() }
        xml.writeEndElement()
        for(c in project.drawing.contours) {
            start("contour","id" to c.id,"source" to c.source,"closed" to c.closed)
            val s=c.cutSettings
            start("cut","type" to s.type.name,"depth" to s.depthMm,"bit" to s.bitMm,"feed" to s.feedMmMin,
                "plunge" to s.plungeMmMin,"passDepth" to s.passDepthMm,"rpm" to s.spindleRpm,"stepOverPercent" to s.stepOverPercent)
            xml.writeEndElement()
            text("points",c.points.joinToString(" "))
            start("traversals")
            c.pathTraversals.forEach { t ->
                start("traversal","key" to t.key,"cx" to t.referenceCentre.x,"cy" to t.referenceCentre.y,
                    "closed" to t.closed,"winding" to t.winding,"reversed" to t.reversed)
                t.anchor?.let { start("anchor","x" to it.x,"y" to it.y); xml.writeEndElement() }
                xml.writeEndElement()
            }
            xml.writeEndElement(); xml.writeEndElement()
        }
        xml.writeEndElement(); xml.writeEndElement(); xml.writeEndDocument(); xml.close()
        return output.toByteArray().also { require(it.size<=MAX_BYTES) { "Project exceeds 64 MB." } }
    }

    fun decode(bytes: ByteArray): ProjectState {
        require(bytes.size<=MAX_BYTES) { "Project exceeds 64 MB." }
        val factory=DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
            isXIncludeAware=false; isExpandEntityReferences=false
        }
        val root=factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement
        require(root.tagName=="dcnc") { "This is not a DCNC project." }
        require(root.attr("version")=="1") { "Unsupported DCNC project version." }
        require(root.attr("units")=="mm") { "Unsupported project units." }
        val workspace=root.one("workspace")
        val drawing=root.one("drawing")
        val original=root.children("original-svg").let { entries ->
            require(entries.size<=1) { "Duplicate original SVG." }
            entries.singleOrNull()?.let { Base64.getDecoder().decode(it.textContent) }
        }
        val skipped=linkedMapOf<String,Int>()
        drawing.one("skipped").children("element").forEach {
            require(skipped.put(it.attr("name"),it.attr("count").toInt())==null) { "Duplicate skipped element." }
        }
        var pointValues=0
        val contours=drawing.children("contour").map { element ->
            val tokens=element.one("points").textContent.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            pointValues+=tokens.size
            require(pointValues<=2_000_000) { "Project exceeds one million points." }
            val points=tokens.map { it.toDouble() }.toDoubleArray()
            val c=Contour(element.attr("id").toInt(),element.attr("source"),points,element.bool("closed"))
            val s=element.one("cut")
            c.cutSettings=CutSettings(CutType.valueOf(s.attr("type")),s.number("depth"),s.number("bit"),
                s.number("feed"),s.number("plunge"),s.number("passDepth"),s.attr("rpm").toInt(),s.number("stepOverPercent"))
            element.one("traversals").children("traversal").forEach { t ->
                val anchors=t.children("anchor")
                require(anchors.size<=1) { "Duplicate traversal anchor." }
                val anchor=anchors.singleOrNull()?.let { Point2D(it.number("x"),it.number("y")) }
                c.pathTraversals.add(PathTraversal(t.attr("key").toLong(),Point2D(t.number("cx"),t.number("cy")),
                    t.bool("closed"),t.attr("winding").toInt(),anchor,t.bool("reversed")))
            }
            c
        }
        return ProjectState(SvgDrawing(contours,drawing.number("width"),drawing.number("height"),skipped,original),
            root.attr("sourceName"),workspace.number("gridStep"),workspace.bool("snapToGrid"),workspace.bool("previewVisible"))
            .also(::validate)
    }

    fun load(path: Path): ProjectState {
        require(Files.size(path)<=MAX_BYTES) { "Project exceeds 64 MB." }
        return decode(Files.readAllBytes(path))
    }

    fun save(path: Path, project: ProjectState) {
        // Finish serialization before creating a temporary file or replacing the destination.
        val bytes=encode(project)
        val destination=path.toAbsolutePath()
        val temp=Files.createTempFile(destination.parent,".dcnc-",".tmp")
        try {
            Files.write(temp,bytes)
            try { Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch(_: AtomicMoveNotSupportedException) { Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

    private fun validate(project: ProjectState) {
        fun coordinate(value: Double)=value.isFinite() && abs(value)<=1e8
        require(project.gridStep in listOf(1.0,5.0,10.0)) { "Invalid grid step." }
        val d=project.drawing
        require(coordinate(d.docWidthMm) && coordinate(d.docHeightMm) && d.docWidthMm>=0 && d.docHeightMm>=0) { "Invalid document size." }
        require(d.contours.map { it.id }.distinct().size==d.contours.size) { "Duplicate contour IDs." }
        require(d.skipped.values.all { it>=0 }) { "Invalid skipped element count." }
        require(d.contours.sumOf { it.points.size.toLong() }<=2_000_000) { "Project exceeds one million points." }
        for(c in d.contours) {
            require(c.id>0 && c.points.size>=4 && c.points.size%2==0 && c.points.all(::coordinate)) { "Invalid contour geometry." }
            require(!c.closed || c.points.size>=6) { "Closed contour needs at least three points." }
            require(c.closed || c.cutSettings.type==CutType.ON_PATH) { "Open contours support On Path only." }
            require(c.pathTraversals.map { it.key }.distinct().size==c.pathTraversals.size) { "Duplicate traversal keys." }
            for(t in c.pathTraversals) {
                require(t.key>0 && t.winding in -1..1 && coordinate(t.referenceCentre.x) && coordinate(t.referenceCentre.y)) { "Invalid traversal." }
                t.anchor?.let { require(coordinate(it.x) && coordinate(it.y)) { "Invalid start point." } }
            }
        }
    }

    private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName==name }
    private fun Element.one(name: String)=children(name).singleOrNull()
        ?: throw IllegalArgumentException("Expected one <$name> in <$tagName>.")
    private fun Element.attr(name: String): String {
        require(hasAttribute(name)) { "Missing $name in <$tagName>." }; return getAttribute(name)
    }
    private fun Element.number(name: String)=attr(name).toDouble()
    private fun Element.bool(name: String)=attr(name).toBooleanStrict()
}
