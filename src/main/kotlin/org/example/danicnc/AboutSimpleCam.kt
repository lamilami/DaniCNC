package org.example.danicnc

import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import java.util.Properties

internal object ApplicationInfo {
    val version: String by lazy {
        val properties = Properties()
        checkNotNull(javaClass.getResourceAsStream("application.properties")) {
            "Missing build metadata. Build the project with Maven to generate application.properties."
        }.use { properties.load(it) }
        properties.getProperty("version").also {
            check(!it.isNullOrBlank() && !it.contains('$')) { "Unfiltered application version." }
        }
    }

    fun notices(): String = checkNotNull(javaClass.getResourceAsStream("THIRD-PARTY-NOTICES.txt")) {
        "Missing third-party license notices."
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/** One owned modal dialog, reused while open. The browser opener is injectable for UI tests. */
internal class AboutSimpleCam(private val openBrowser: (String) -> Unit) {
    private var current: Dialog<Void>? = null

    fun show(owner: Window) {
        current?.takeIf { it.isShowing }?.let {
            (it.dialogPane.scene.window as? Stage)?.apply { toFront(); requestFocus() }
            return
        }
        val dialog = Dialog<Void>()
        dialog.initOwner(owner)
        dialog.initModality(Modality.WINDOW_MODAL)
        dialog.title = "About SimpleCAM"
        dialog.dialogPane.id = "aboutSimpleCAMDialog"
        dialog.dialogPane.buttonTypes.add(ButtonType.CLOSE)

        fun label(text: String, bold: Boolean = false) = Label(text).apply {
            isWrapText = true
            if (bold) style = "-fx-font-weight: bold;"
        }
        fun section(title: String, text: String) = VBox(3.0, label(title, true), label(text))
        val github = Hyperlink("https://github.com/lamilami").apply {
            id = "responsiblePersonGitHub"
            padding = Insets.EMPTY
            setOnAction { openBrowser(text) }
        }
        val credits = VBox(12.0,
            label("SimpleCAM 1830", true).apply { style += "-fx-font-size: 20px;" },
            label("Version ${ApplicationInfo.version}").apply { id = "applicationVersion" },
            label("Simple 2D CAM for CNC hobbyists.").apply { style = "-fx-font-style: italic;" },
            VBox(3.0, label("Responsible Person", true), label("Daniel Denkov"), github),
            section("Software Development", "Claude — Anthropic\nCodex — OpenAI"),
            section("Architecture & Technical Assistance", "ChatGPT — OpenAI"),
            section("Open-Source Components", "Clipper2 — Polygon clipping and offset geometry"),
            section("Special Thanks", "To the open-source community and everyone making useful tools freely available."),
            label("Made for makers, not subscriptions. 🍒").apply { style = "-fx-font-style: italic;" }
        ).apply { padding = Insets(16.0) }
        val licenses = TextArea(ApplicationInfo.notices()).apply {
            id = "thirdPartyNotices"
            isEditable = false
            isWrapText = true
            style = "-fx-font-family: monospace; -fx-font-size: 11px;"
        }
        dialog.dialogPane.content = TabPane(
            Tab("About", credits), Tab("Licenses", licenses)
        ).apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            prefWidth = 510.0
            prefHeight = 560.0
        }
        // CLOSE is a cancel button: JavaFX closes the dialog with Esc as well.
        dialog.setOnHidden { if (current === dialog) current = null }
        current = dialog
        dialog.show()
    }
}
