package org.example.danicnc

import javafx.application.Platform
import javafx.event.Event
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ToolBar
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Pane
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.imageio.ImageIO

class AboutSimpleCamTest {
    companion object {
        @JvmStatic @BeforeAll fun startFx() {
            try { Platform.startup {} } catch (_: IllegalStateException) { }
            Platform.setImplicitExit(false)
        }
    }

    private fun onFx(block: () -> Unit) {
        var result: Result<Unit>? = null
        val latch = CountDownLatch(1)
        Platform.runLater { result = runCatching(block); latch.countDown() }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        result!!.getOrThrow()
    }

    private fun aboutWindows() = Window.getWindows().filter {
        it.scene?.root?.id == "aboutSimpleCAMDialog" && it.isShowing
    }

    @Test fun `version comes from Maven and bundled licenses contain actual runtime components`() {
        val pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("pom.xml"))
        val version = (0 until pom.documentElement.childNodes.length)
            .map { pom.documentElement.childNodes.item(it) }.first { it.nodeName == "version" }.textContent.trim()
        assertEquals(version, ApplicationInfo.version)
        val notices = ApplicationInfo.notices()
        for (expected in listOf("Clipper2-java 2.0.1b", "Boost Software License", "OpenJFX 25.0.1",
            "Classpath", "Kotlin", "ControlsFX", "FormsFX", "ValidatorFX", "Ikonli", "BootstrapFX",
            "TilesFX", "Countries", "HeatMap", "ToolboxFX", "JetBrains annotations")) {
            assertTrue(notices.contains(expected), "Missing notice: $expected")
        }
    }

    @Test fun `toolbar button opens one owned modal About with exact credits and Close and Escape work`() = onFx {
        val stage = Stage()
        try {
            SimpleCamApp().start(stage)
            assertNull(stage.scene.lookup(".menu-bar"))
            val toolbar = stage.scene.lookup(".tool-bar") as ToolBar
            val item = toolbar.items.filterIsInstance<Button>().single { it.id == "aboutSimpleCAM" }
            assertEquals("About SimpleCAM", item.text)
            item.fire()
            val window = aboutWindows().single() as Stage
            val pane = window.scene.root as DialogPane
            pane.applyCss(); pane.layout()
            assertEquals(stage, window.owner)
            assertEquals(Modality.WINDOW_MODAL, window.modality)
            item.fire()
            assertSame(window, aboutWindows().single())
            val tabs = pane.content as TabPane
            val credits = tabs.tabs[0].content
            val labels = credits.lookupAll(".label").filterIsInstance<Label>().map { it.text }
            for (text in listOf("SimpleCAM 1830", "Version ${ApplicationInfo.version}",
                "Simple 2D CAM for CNC hobbyists.", "Responsible Person", "Daniel Denkov",
                "Software Development", "Claude — Anthropic\nCodex — OpenAI",
                "Architecture & Technical Assistance", "ChatGPT — OpenAI", "Open-Source Components",
                "Clipper2 — Polygon clipping and offset geometry", "Special Thanks",
                "To the open-source community and everyone making useful tools freely available.",
                "Made for makers, not subscriptions. 🍒")) assertTrue(text in labels, "Missing credit: $text")
            assertFalse(labels.any { it == "Author" || it == "Developer" })
            ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                File("target/about-simplecam.png"))
            tabs.selectionModel.select(1)
            val licenses = tabs.tabs[1].content as TextArea
            assertFalse(licenses.isEditable)
            assertTrue(licenses.text.contains("Boost Software License - Version 1.0"))
            (pane.lookupButton(ButtonType.CLOSE) as Button).fire()
            assertTrue(aboutWindows().isEmpty())
            item.fire()
            val reopened = aboutWindows().single()
            Event.fireEvent(reopened.scene.root, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                false, false, false, false))
            assertTrue(aboutWindows().isEmpty(), "Esc must close About")
        } finally {
            aboutWindows().forEach { (it as Stage).close() }
            stage.close()
        }
    }

    @Test fun `GitHub hyperlink opens the exact responsible person profile`() = onFx {
        val owner = Stage()
        owner.scene = Scene(Pane(), 600.0, 700.0)
        owner.show()
        var opened: String? = null
        try {
            AboutSimpleCam { opened = it }.show(owner)
            val window = aboutWindows().single()
            val link = window.scene.lookup("#responsiblePersonGitHub") as Hyperlink
            link.fire()
            assertEquals("https://github.com/lamilami", opened)
        } finally {
            aboutWindows().forEach { (it as Stage).close() }
            owner.close()
        }
    }
}
