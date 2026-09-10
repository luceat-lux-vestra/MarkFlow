package com.algorist.markflow.editor.native

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

class NativeHostResourceMouseNavigationTest : BasePlatformTestCase() {
    fun testEditorMouseListenerRequiresModifierLeftClick() {
        val source = "[allowed](https://example.com/docs)\n"
        myFixture.configureByText("mouse-navigation.md", source)
        val opened = mutableListOf<String>()
        val host = NativeHostResourcePresentationController(
            editor = myFixture.editor,
            documentPathProvider = { null },
            externalNavigator = { uri -> opened += uri.toString() },
            richPresentationEnabled = { false },
        )
        val controller = NativePresentationController(
            editor = myFixture.editor,
            hostResources = host,
        )
        try {
            val editor = myFixture.editor
            val offset = source.indexOf("allowed") + 2
            val textPoint = editor.offsetToXY(offset)
            val x = textPoint.x + 1
            val y = textPoint.y + editor.lineHeight / 2

            fun dispatchClick(modifiers: Int, button: Int): MouseEvent {
                val event = MouseEvent(
                    editor.contentComponent,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    modifiers,
                    x,
                    y,
                    1,
                    false,
                    button,
                )
                editor.contentComponent.dispatchEvent(event)
                return event
            }

            val plainLeft = dispatchClick(0, MouseEvent.BUTTON1)
            assertFalse(plainLeft.isConsumed)
            assertTrue(opened.isEmpty())

            val modifiedRight = dispatchClick(InputEvent.CTRL_DOWN_MASK, MouseEvent.BUTTON3)
            assertFalse(modifiedRight.isConsumed)
            assertTrue(opened.isEmpty())

            val modifiedLeft = dispatchClick(InputEvent.CTRL_DOWN_MASK, MouseEvent.BUTTON1)
            assertTrue(modifiedLeft.isConsumed)
            assertEquals(listOf("https://example.com/docs"), opened)
            assertEquals(1L, host.evidenceSnapshot().navigationAccepted)
        } finally {
            Disposer.dispose(controller)
        }
    }
}
