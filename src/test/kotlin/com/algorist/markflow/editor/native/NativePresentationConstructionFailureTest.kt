package com.algorist.markflow.editor.native

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativePresentationConstructionFailureTest : BasePlatformTestCase() {
    fun testConstructorFailureDisposesRegisteredDocumentListener() {
        myFixture.configureByText("construction-failure.md", "# failure\n")
        val editor = myFixture.editor
        var plannerCalls = 0

        try {
            NativePresentationController(
                editor = editor,
                planner = {
                    plannerCalls += 1
                    error("synthetic construction failure")
                },
            )
            fail("synthetic planner failure must escape construction")
        } catch (failure: IllegalStateException) {
            assertEquals("synthetic construction failure", failure.message)
        }
        assertEquals(1, plannerCalls)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "after-failure\n")
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(
            "failed construction must not leave a DocumentListener that schedules another refresh",
            1,
            plannerCalls,
        )
    }
}
