package org.example.danicnc

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/** Blank/mixed fields preserve each contour's existing value on Apply. */
class CutSettingsPanel : VBox(6.0) {
    private var selection = emptyList<Contour>()
    private var refreshing = false
    private val type = ComboBox<CutType>().apply { id = "cutType"; promptText = "Mixed — keep" }
    private val bit = ComboBox<Double>(FXCollections.observableArrayList(CutSettings.BITS)).apply {
        id = "cutBit"; promptText = "Mixed — keep"
    }
    private fun field(name: String) = TextField().apply { id = name; prefColumnCount = 8; promptText = "Mixed — keep" }
    private val depth = field("cutDepth")
    private val feed = field("cutFeed")
    private val plunge = field("cutPlunge")
    private val pass = field("cutPassDepth")
    private val rpm = field("cutRpm")
    private val stepover = field("cutStepOver")
    private val message = Label().apply { isWrapText = true }
    private val apply = Button("Apply to selection").apply { id = "applyCutSettings" }

    init {
        id = "cutSettingsPanel"
        val grid = GridPane().apply { hgap = 8.0; vgap = 5.0 }
        val controls = listOf(
            "Cut Type" to type, "Cut Depth, mm" to depth, "Bit, mm" to bit,
            "Feed Rate, mm/min" to feed, "Plunge Rate, mm/min" to plunge,
            "Depth per Pass, mm" to pass, "Spindle Speed, RPM" to rpm,
            "Pocket Step Over, %" to stepover,
        )
        controls.forEachIndexed { row, (label, control) ->
            grid.add(Label(label), 0, row); grid.add(control, 1, row)
            control.maxWidth = Double.MAX_VALUE
            GridPane.setHgrow(control, Priority.ALWAYS)
        }
        children.addAll(Label("Cut Settings").apply { style = "-fx-font-weight: bold;" }, grid,
            Label("Blank / mixed = keep existing values").apply { style = "-fx-font-size: 10px;" }, apply, message)
        padding = Insets(4.0, 0.0, 4.0, 0.0)
        apply.setOnAction { applySettings() }
        listOf(depth, feed, plunge, pass, rpm, stepover).forEach { it.setOnAction { applySettings() } }
        type.valueProperty().addListener { _, _, value ->
            if (!refreshing && value != null) updateOperation { it.copy(type = value) }
        }
        bit.valueProperty().addListener { _, _, value ->
            if (!refreshing && value != null) updateOperation { it.copy(bitMm = value) }
        }
        showSelection(emptyList())
    }

    fun showSelection(contours: List<Contour>) {
        refreshing = true
        selection = contours.toList()
        isVisible = selection.isNotEmpty(); isManaged = isVisible
        type.items.setAll(if (selection.all { it.closed }) CutType.entries else listOf(CutType.ON_PATH))
        val settings = selection.map { it.cutSettings }
        fun <T> common(value: (CutSettings) -> T): T? = settings.map(value).distinct().singleOrNull()
        type.value = common { it.type }
        bit.value = common { it.bitMm }
        depth.text = common { it.depthMm }?.toString() ?: ""
        feed.text = common { it.feedMmMin }?.toString() ?: ""
        plunge.text = common { it.plungeMmMin }?.toString() ?: ""
        pass.text = common { it.passDepthMm }?.toString() ?: ""
        rpm.text = common { it.spindleRpm }?.toString() ?: ""
        stepover.text = common { it.stepOverPercent }?.toString() ?: ""
        stepover.isDisable = selection.none { it.cutSettings.type == CutType.POCKET }
        message.text = if (selection.any { !it.closed }) "Open contours: On Path only." else ""
        message.style = ""
        refreshing = false
    }

    private fun updateOperation(update: (CutSettings) -> CutSettings) {
        try {
            CutSettings.applyTo(selection, update)
            stepover.isDisable = selection.none { it.cutSettings.type == CutType.POCKET }
            message.text = "Cut Type / Bit updated."
            message.style = ""
        } catch (e: IllegalArgumentException) {
            message.text = e.message
            message.style = "-fx-text-fill: #b71c1c;"
        }
    }

    private fun applySettings() {
        if (selection.isEmpty()) return
        try {
            fun number(field: TextField, label: String): Double? {
                val text = field.text.trim().replace(',', '.')
                if (text.isEmpty()) return null
                return text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                    ?: throw IllegalArgumentException("$label must be a positive finite number.")
            }
            val d = number(depth, "Cut Depth")
            val f = number(feed, "Feed Rate")
            val p = number(plunge, "Plunge Rate")
            val step = number(pass, "Depth per Pass")
            val overlap = if (stepover.isDisable) null else number(stepover, "Pocket Step Over")
            val speed = rpm.text.trim().let { text ->
                if (text.isEmpty()) null else text.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("Spindle Speed must be a positive whole number.")
            }
            CutSettings.applyTo(selection) { old -> old.copy(
                type = type.value ?: old.type, bitMm = bit.value ?: old.bitMm,
                depthMm = d ?: old.depthMm, feedMmMin = f ?: old.feedMmMin,
                plungeMmMin = p ?: old.plungeMmMin, passDepthMm = step ?: old.passDepthMm,
                spindleRpm = speed ?: old.spindleRpm,
                stepOverPercent = overlap ?: old.stepOverPercent,
            ) }
            showSelection(selection)
            message.text = "Settings applied to ${selection.size} contour(s)."
        } catch (e: IllegalArgumentException) {
            message.text = e.message
            message.style = "-fx-text-fill: #b71c1c;"
        }
    }
}
