package org.example.danicnc

import javafx.application.Application
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceDialog
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.control.ScrollPane
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.ToolBar
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.File
import java.util.Locale

/** SimpleCAM 1830 — основен прозорец. */
class SimpleCamApp : Application() {

    private val workArea = WorkArea()
    private val sizeLabel = Label("No drawing")
    private val fileLabel = Label("")
    private val docLabel = Label("")
    private val countLabel = Label("")
    private val cursorLabel = Label("")
    private val zoomLabel = Label("")
    private val selSummary = Label("Nothing selected")
    private val selRows = FXCollections.observableArrayList<Contour>()
    private val clearBtn = Button("Clear Selection")
    private val xField = TextField().apply { id = "selectionX"; prefColumnCount = 10 }
    private val yField = TextField().apply { id = "selectionY"; prefColumnCount = 10 }
    private val positionBtn = Button("Apply X/Y").apply { id = "applyPosition" }
    private val positionError = Label().apply { style = "-fx-text-fill: #b71c1c;"; isWrapText = true }
    private val cutSettingsPanel = CutSettingsPanel()
    private val previewStatus = Label("Toolpath: cutter centre").apply {
        id = "previewStatus"; isWrapText = true; style = "-fx-text-fill: #d5006d;"
    }
    private var lastDir: File? = null
    private lateinit var stage: Stage
    private val aboutDialog = AboutSimpleCam { hostServices.showDocument(it) }

    override fun start(stage: Stage) {
        this.stage = stage
        workArea.onExportSelected = { exportGCode(ExportScope.SELECTED) }

        val openBtn = Button("Open SVG").apply { setOnAction { openSvg() } }
        val exportBtn = Button("Export G-code").apply { id = "exportGCode"; setOnAction { exportGCode() } }
        val fitBtn = Button("Fit").apply { setOnAction { workArea.fit() } }
        clearBtn.setOnAction { workArea.clearSelection() }
        clearBtn.isDisable = true
        sizeLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val snap = CheckBox("Snap to Grid").apply {
            isSelected = workArea.snapToGrid
            setOnAction { workArea.snapToGrid = isSelected }
        }
        val grid = ComboBox<Double>(FXCollections.observableArrayList(1.0, 5.0, 10.0)).apply {
            value = workArea.gridStep
            setOnAction { workArea.gridStep = value }
        }
        val preview = CheckBox("Preview ON/OFF").apply {
            id = "previewToggle"; isSelected = workArea.previewVisible
            setOnAction { workArea.previewVisible = isSelected }
        }
        workArea.onPreviewChanged = { result ->
            previewStatus.text = if (result.warnings.isEmpty()) "Toolpath: ${result.paths.size} XY path(s) — cutter centre"
                else result.warnings.joinToString("\n")
        }
        val aboutBtn = Button("About SimpleCAM").apply {
            id = "aboutSimpleCAM"
            setOnAction { aboutDialog.show(stage) }
        }
        val toolBar = ToolBar(openBtn, exportBtn, fitBtn, Separator(), clearBtn, Separator(), preview, snap,
            Label("Grid, mm:"), grid, Separator(), sizeLabel, Separator(), aboutBtn)

        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val statusBar = HBox(16.0, fileLabel, docLabel, countLabel, spacer, cursorLabel, zoomLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 10.0, 4.0, 10.0)
            style = "-fx-background-color: #eceff1; -fx-border-color: #cfd8dc; -fx-border-width: 1 0 0 0;"
        }
        cursorLabel.minWidth = 190.0
        zoomLabel.minWidth = 90.0

        workArea.cursorMm.addListener { _, _, p ->
            cursorLabel.text = p?.let { "X: ${mm(it.x)}  Y: ${mm(it.y)} mm" } ?: ""
        }
        workArea.zoomPercent.addListener { _, _, z -> zoomLabel.text = "Zoom: %.0f%%".format(Locale.ROOT, z) }
        workArea.onSelectionChanged = ::selectionChanged
        workArea.onGeometryMoved = ::updatePosition

        val root = BorderPane(workArea, toolBar, createSelectionPanel(), statusBar, null)
        stage.title = "SimpleCAM 1830"
        stage.scene = Scene(root, 1280.0, 800.0)
        stage.minWidth = 800.0
        stage.minHeight = 500.0
        stage.show()

        // по желание: файл от командния ред
        parameters?.raw?.firstOrNull()?.let { loadFile(File(it)) }
    }

    private fun createSelectionPanel(): ScrollPane {
        val title = Label("Selection").apply { style = "-fx-font-weight: bold; -fx-font-size: 13px;" }

        val table = TableView(selRows).apply {
            prefHeight = 120.0
            minHeight = 90.0
            placeholder = Label("Click a contour to select it.\nCtrl + Click to add or remove.").apply {
                style = "-fx-text-fill: #78909c;"
            }
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            columns.addAll(
                column("#", 36.0) { it.id.toString() },
                column("Element", 110.0) { it.source },
                statusColumn(),
                column("Length, mm", 90.0) { mm(it.length) }.apply { style = "-fx-alignment: CENTER-RIGHT;" },
            )
        }
        VBox.setVgrow(table, Priority.ALWAYS)

        val legend = VBox(4.0,
            legendRow(WorkArea.CLOSED_COLOR, "Closed contour"),
            legendRow(WorkArea.OPEN_COLOR, "Open contour (○ = end points)"),
            legendRow(WorkArea.SELECTED_COLOR, "Selected"),
            legendRow(WorkArea.TOOLPATH_COLOR, "Toolpath — cutter centre (dashed)"),
        )

        positionBtn.setOnAction { applyPosition() }
        xField.setOnAction { applyPosition() }
        yField.setOnAction { applyPosition() }
        updatePosition()
        val position = VBox(6.0, Label("Position — bottom-left corner, mm"),
            HBox(8.0, Label("X:"), xField), HBox(8.0, Label("Y:"), yField), positionBtn, positionError)

        val content = VBox(8.0, title, table, selSummary, Separator(), position,
            Separator(), cutSettingsPanel, previewStatus, Separator(), legend).apply {
            padding = Insets(10.0)
            prefWidth = 340.0
            style = "-fx-background-color: #f5f7f8; -fx-border-color: #cfd8dc; -fx-border-width: 0 0 0 1;"
        }
        return ScrollPane(content).apply {
            isFitToWidth = true
            prefWidth = 360.0
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }
    }

    private fun column(name: String, width: Double, value: (Contour) -> String) =
        TableColumn<Contour, String>(name).apply {
            prefWidth = width
            isSortable = false
            setCellValueFactory { ReadOnlyStringWrapper(value(it.value)) }
        }

    private fun statusColumn() = TableColumn<Contour, Contour>("Type").apply {
        prefWidth = 70.0
        isSortable = false
        setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
        setCellFactory {
            object : TableCell<Contour, Contour>() {
                override fun updateItem(item: Contour?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) { text = null; style = ""; return }
                    text = if (item.closed) "Closed" else "Open"
                    style = "-fx-font-weight: bold; -fx-text-fill: " +
                        (if (item.closed) "#1565c0" else "#8e24aa") + ";"
                }
            }
        }
    }

    private fun legendRow(color: Color, text: String) =
        HBox(8.0, Line(0.0, 0.0, 22.0, 0.0).apply { stroke = color; strokeWidth = 3.0 }, Label(text)).apply {
            alignment = Pos.CENTER_LEFT
        }

    private fun selectionChanged(sel: List<Contour>) {
        cutSettingsPanel.showSelection(sel)
        updatePosition()
        selRows.setAll(sel)
        clearBtn.isDisable = sel.isEmpty()
        selSummary.text = if (sel.isEmpty()) "Nothing selected" else {
            val closed = sel.count { it.closed }
            "Selected: ${sel.size}  (closed $closed, open ${sel.size - closed})\n" +
                "Total length: ${mm(sel.sumOf { it.length })} mm"
        }
    }

    private fun updatePosition() {
        val b = workArea.selectionBounds
        xField.isDisable = b == null
        yField.isDisable = b == null
        positionBtn.isDisable = b == null
        xField.text = b?.minX?.toString() ?: ""
        yField.text = b?.minY?.toString() ?: ""
        positionError.text = ""
    }

    private fun applyPosition() {
        val x = xField.text.trim().replace(',', '.').toDoubleOrNull()
        val y = yField.text.trim().replace(',', '.').toDoubleOrNull()
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
            positionError.text = "Enter finite X and Y coordinates in mm."
            return
        }
        workArea.moveSelectionTo(x, y)
    }

    private fun openSvg() {
        val chooser = FileChooser().apply {
            title = "Open SVG"
            extensionFilters.add(FileChooser.ExtensionFilter("SVG files (*.svg)", "*.svg"))
            lastDir?.takeIf { it.isDirectory }?.let { initialDirectory = it }
        }
        chooser.showOpenDialog(stage)?.let { loadFile(it) }
    }

    private fun exportGCode(requestedScope: ExportScope? = null) {
        val scope = requestedScope ?: ChoiceDialog(GCodeExporter.defaultScope(workArea.selected), ExportScope.entries).apply {
            initOwner(stage)
            title = "Export G-code"
            headerText = "Choose export scope"
            contentText = "Scope:"
        }.showAndWait().orElse(null) ?: return
        try {
            // Validate all operations before presenting a destination or touching any file.
            val program = GCodeExporter.generate(workArea.projectContours,workArea.selected,scope,workArea.previewVisible)
            val chooser = FileChooser().apply {
                title = "Save GRBL G-code"
                extensionFilters.add(FileChooser.ExtensionFilter("GRBL G-code (*.nc)","*.nc"))
                initialFileName = (fileLabel.text.substringBeforeLast('.').ifBlank { "simplecam" }) + ".nc"
                lastDir?.takeIf { it.isDirectory }?.let { initialDirectory = it }
            }
            val chosen = chooser.showSaveDialog(stage) ?: return
            val file = if (chosen.extension.equals("nc",true)) chosen else File(chosen.parentFile,chosen.name + ".nc")
            GCodeExporter.writeProgram(file.toPath(),program)
            Alert(Alert.AlertType.INFORMATION,"Saved ${file.name}\nZ0 = material top. Verify Z+5 retract height for your setup.").apply {
                initOwner(stage); headerText = "G-code exported"
            }.showAndWait()
        } catch (e: Exception) {
            Alert(Alert.AlertType.ERROR,e.message ?: "Export failed.").apply {
                initOwner(stage); headerText = "Export cancelled — no partial file generated"
            }.showAndWait()
        }
    }

    private fun loadFile(file: File) {
        try {
            val d = SvgLoader.load(file)
            lastDir = file.parentFile
            workArea.setDrawing(d)
            stage.title = "SimpleCAM 1830 — ${file.name}"
            fileLabel.text = file.name
            sizeLabel.text = d.geometryBounds
                ?.let { "Drawing: ${mm(it.width)} × ${mm(it.height)} mm" }
                ?: "Drawing: (no supported geometry)"
            docLabel.text = if (d.docWidthMm > 0 && d.docHeightMm > 0)
                "Page: ${mm(d.docWidthMm)} × ${mm(d.docHeightMm)} mm" else ""
            val closed = d.contours.count { it.closed }
            countLabel.text = "Contours: ${d.contours.size} (closed $closed, open ${d.contours.size - closed})" +
                (if (d.skipped.isNotEmpty()) "   Skipped: " + d.skipped.entries.joinToString { "${it.value}× <${it.key}>" } else "")
        } catch (e: Exception) {
            Alert(Alert.AlertType.ERROR, "Cannot open ${file.name}:\n${e.message}").apply {
                initOwner(stage)
                headerText = null
            }.showAndWait()
        }
    }

    private fun mm(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(SimpleCamApp::class.java, *args)
        }
    }
}
