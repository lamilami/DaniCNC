package org.example.danicnc

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.abs

enum class ExportScope(private val label: String) {
    SELECTED("Selected Contours"), ENTIRE_PROJECT("Entire Project");
    override fun toString() = label
}

/** Builds the entire program before any file is touched. No machine communication. */
object GCodeExporter {
    const val RETRACT_MM = 5.0
    private const val MAX_LINES = 1_000_000L
    fun defaultScope(selected: List<Contour>) = if (selected.isEmpty()) ExportScope.ENTIRE_PROJECT else ExportScope.SELECTED

    fun generate(project: List<Contour>, selected: List<Contour>, scope: ExportScope, previewVisible: Boolean): String {
        require(previewVisible) { "Preview is hidden. Turn Preview ON before exporting visible toolpaths." }
        val selectedSet = selected.toSet()
        val contours = if (scope == ExportScope.ENTIRE_PROJECT) project else project.filter { it in selectedSet }
        require(contours.isNotEmpty()) { "No contours in export scope." }
        require(project.map { it.id }.distinct().size == project.size) { "Duplicate contour IDs in project." }
        // Compensated compound SVG paths must retain their holes and complete boundaries.
        for (c in contours) {
            if (c.cutSettings.type != CutType.ON_PATH) {
                require(project.filter { it.source == c.source }.all { it in contours }) {
                    "${c.source}: Select all subpaths of this compound contour, including holes."
                }
            }
            validateSettings(c.cutSettings)
        }
        // Use the same calculator and its actual points as the preview, never a second offset implementation.
        val preview = ToolpathPreview.calculate(contours)
        require(preview.warnings.isEmpty()) { "Export cancelled:\n${preview.warnings.joinToString("\n")}" }
        require(preview.paths.isNotEmpty()) { "No toolpaths to export." }
        val byId = contours.associateBy { it.id }
        val modelOrder = contours.mapIndexed { i, c -> c.id to i }.toMap()
        val paths = preview.paths.sortedBy { p -> p.contourIds.minOf { modelOrder.getValue(it) } }
        require(contours.all { c -> paths.any { c.id in it.contourIds } }) { "A contour has no valid toolpath." }
        var lineCount = 0L
        val jobs = paths.map { path ->
            validatePath(path)
            val owners = path.contourIds.map { byId.getValue(it) }
            val settings = owners.first().cutSettings
            require(owners.all { it.cutSettings == settings }) {
                "Compound contour has different cutting parameters; apply common settings to all its subpaths."
            }
            val passes = BigDecimal.valueOf(settings.depthMm).divide(BigDecimal.valueOf(settings.passDepthMm),0,RoundingMode.CEILING)
            require(passes >= BigDecimal.ONE && passes <= BigDecimal.valueOf(10000)) { "Too many depth passes (maximum 10000)." }
            lineCount += passes.toLong() * (path.points.size / 2 + 7)
            require(lineCount <= MAX_LINES) { "Export exceeds one million lines." }
            Triple(path, settings, passes.toInt())
        }
        return buildString {
            appendLine("(SimpleCAM 1830 - GRBL XY toolpaths)")
            appendLine("(Z0 material top. Retract Z+5 must be checked for your setup.)")
            appendLine("G21"); appendLine("G90"); appendLine("G17"); appendLine("G94")
            appendLine("M5"); appendLine("G0 Z5")
            var spindle: Int? = null
            for ((path, settings, passes) in jobs) {
                appendLine("(Contours ${path.contourIds.joinToString(",")} - ${settings.type} - bit ${number(settings.bitMm)} mm)")
                for (pass in 1..passes) {
                    val depth = BigDecimal.valueOf(settings.passDepthMm).multiply(BigDecimal.valueOf(pass.toLong()))
                        .min(BigDecimal.valueOf(settings.depthMm)).toDouble()
                    val x0 = number(path.points[0]); val y0 = number(path.points[1])
                    appendLine("(Pass $pass/$passes)")
                    appendLine("G0 Z5")
                    appendLine("G0 X$x0 Y$y0")
                    if (spindle != settings.spindleRpm) {
                        appendLine("M3 S${settings.spindleRpm}"); spindle = settings.spindleRpm
                    }
                    appendLine("G1 Z-${number(depth)} F${number(settings.plungeMmMin)}")
                    for (i in 2 until path.points.size step 2) {
                        appendLine("G1 X${number(path.points[i])} Y${number(path.points[i + 1])} F${number(settings.feedMmMin)}")
                    }
                    if (path.closed && (path.points[path.points.size - 2] != path.points[0] || path.points.last() != path.points[1])) {
                        appendLine("G1 X$x0 Y$y0 F${number(settings.feedMmMin)}")
                    }
                    appendLine("G0 Z5")
                }
            }
            appendLine("M5"); appendLine("M2")
        }
    }

    private fun validateSettings(s: CutSettings) {
        require(listOf(s.depthMm,s.passDepthMm,s.feedMmMin,s.plungeMmMin).all { it.isFinite() && it >= 0.000001 && it <= 1e8 }) {
            "Depth, pass depth, feed and plunge must be positive and representable at six decimal places."
        }
        require(s.spindleRpm > 0 && s.bitMm in CutSettings.BITS) { "Invalid spindle speed or bit." }
    }

    private fun validatePath(p: PreviewPath) {
        require(p.points.size >= 4 && p.points.size % 2 == 0) { "Empty or invalid toolpath." }
        require(p.points.all { it.isFinite() && abs(it) <= 1e8 }) { "Nonfinite or out-of-range toolpath coordinates." }
        val vertices = p.points.toList().chunked(2).map { number(it[0]) to number(it[1]) }.distinct()
        require(vertices.size >= if (p.closed) 3 else 2) { "Toolpath collapses at export precision." }
        if (p.closed) {
            val polygon = com.github.micycle1.clipper2.core.PathD()
            vertices.forEach { polygon.add(com.github.micycle1.clipper2.core.PointD(it.first.toDouble(),it.second.toDouble())) }
            require(abs(com.github.micycle1.clipper2.Clipper.area(polygon)) > 1e-12) { "Closed toolpath has no area." }
        }
    }

    private fun number(v: Double): String = BigDecimal.valueOf(if (v == 0.0) 0.0 else v)
        .setScale(6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Replace the destination only after a complete, validated program has been written. */
    fun write(destination: Path, project: List<Contour>, selected: List<Contour>, scope: ExportScope, previewVisible: Boolean) {
        val program = generate(project,selected,scope,previewVisible)
        writeProgram(destination,program)
    }

    internal fun writeProgram(destination: Path, program: String) {
        val target = destination.toAbsolutePath()
        val temp = Files.createTempFile(target.parent,".simplecam-", ".tmp")
        try {
            Files.write(temp,program.toByteArray(StandardCharsets.US_ASCII))
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}
