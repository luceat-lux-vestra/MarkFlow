package com.algorist.markflow.editor.native

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Point
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
            val labelStart = source.indexOf("allowed")
            val targetOffset = labelStart + 2
            val textPoint = editor.offsetToXY(targetOffset)
            val x = textPoint.x
            val y = textPoint.y + editor.lineHeight / 2
            val resolvedOffset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(Point(x, y)))
            assertTrue(resolvedOffset in labelStart until labelStart + "allowed".length)

            fun deliverClick(modifiers: Int, button: Int): MouseEvent {
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
                host.handleExplicitNavigation(event)
                return event
            }

            val plainLeft = deliverClick(0, MouseEvent.BUTTON1)
            assertFalse(plainLeft.isConsumed)
            assertTrue(opened.isEmpty())

            val modifiedRight = deliverClick(InputEvent.CTRL_DOWN_MASK, MouseEvent.BUTTON3)
            assertFalse(modifiedRight.isConsumed)
            assertTrue(opened.isEmpty())

            val controlLeft = deliverClick(InputEvent.CTRL_DOWN_MASK, MouseEvent.BUTTON1)
            assertTrue(controlLeft.isConsumed)
            assertEquals(listOf("https://example.com/docs"), opened)

            val metaLeft = deliverClick(InputEvent.META_DOWN_MASK, MouseEvent.BUTTON1)
            assertTrue(metaLeft.isConsumed)
            assertEquals(listOf("https://example.com/docs", "https://example.com/docs"), opened)
            assertEquals(2L, host.evidenceSnapshot().navigationAccepted)
        } finally {
            Disposer.dispose(controller)
        }
    }
}
