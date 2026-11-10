package org.example.danicnc

import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCombination
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.stage.Stage

/**
 * Шаблон за главен прозорец на нов проект:
 * меню горе, празна работна зона в центъра, статус лента долу.
 */
class EmptyApplication : Application() {

    private val status = Label("Готов")

    override fun start(stage: Stage) {
        val root = BorderPane().apply {
            top = createMenuBar(stage)
            center = createContent()
            bottom = createStatusBar()
        }

        stage.title = "DaniCNC"
        stage.scene = Scene(root, 1024.0, 768.0)
        stage.minWidth = 640.0
        stage.minHeight = 480.0
        stage.show()
    }

    private fun createMenuBar(stage: Stage): MenuBar {
        val newItem = MenuItem("Нов").apply {
            accelerator = KeyCombination.keyCombination("Shortcut+N")
            setOnAction { status.text = "Нов проект" }
        }
        val openItem = MenuItem("Отвори...").apply {
            accelerator = KeyCombination.keyCombination("Shortcut+O")
            setOnAction { status.text = "Отвори" }
        }
        val saveItem = MenuItem("Запиши").apply {
            accelerator = KeyCombination.keyCombination("Shortcut+S")
            setOnAction { status.text = "Запиши" }
        }
        val exitItem = MenuItem("Изход").apply {
            setOnAction { Platform.exit() }
        }
        val fileMenu = Menu("Файл", null, newItem, openItem, saveItem, SeparatorMenuItem(), exitItem)

        val aboutItem = MenuItem("Относно").apply {
            setOnAction {
                Alert(Alert.AlertType.INFORMATION, "DaniCNC 1.0").apply {
                    initOwner(stage)
                    headerText = null
                    title = "Относно"
                }.showAndWait()
            }
        }
        val helpMenu = Menu("Помощ", null, aboutItem)

        return MenuBar(fileMenu, helpMenu)
    }

    /** Празна работна зона — тук се слага съдържанието на проекта. */
    private fun createContent(): Pane = Pane().apply {
        style = "-fx-background-color: white;"
    }

    private fun createStatusBar(): HBox = HBox(status).apply {
        padding = Insets(4.0, 8.0, 4.0, 8.0)
        style = "-fx-background-color: #eeeeee; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;"
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Application.launch(EmptyApplication::class.java, *args)
        }
    }
}
