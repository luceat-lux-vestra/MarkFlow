package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JPanel

class MarkdownPreviewLifecycleGuardTest : BasePlatformTestCase() {
    private fun panelWithBrowser(identity: String): JPanel =
        JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                add(JPanel().apply { putClientProperty("test-jcef-owner", identity) })
            }, BorderLayout.CENTER)
        }

    private fun resolve(root: Component): String? =
        StaleMarkdownPreviewPanelPolicy.findDescendant(root) {
            (it as? JPanel)?.getClientProperty("test-jcef-owner") as? String
        }

    fun testRecoversOnlyOlderOwnedPanelsAfterRepeatedCenterReplacement() {
        val wrapper = JPanel(BorderLayout())
        val first = panelWithBrowser("first")
        val second = panelWithBrowser("second")
        val active = panelWithBrowser("active")
        wrapper.add(first, BorderLayout.CENTER)
        wrapper.add(second, BorderLayout.CENTER)
        wrapper.add(active, BorderLayout.CENTER)
        val closed = mutableListOf<String>()

        assertEquals(2, StaleMarkdownPreviewPanelPolicy.reclaim(wrapper, ::resolve, closed::add))
        assertEquals(listOf("first", "second"), closed)
        assertEquals(1, wrapper.componentCount)
        assertSame(active, (wrapper.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
        assertSame(wrapper, active.parent)
        assertEquals(0, StaleMarkdownPreviewPanelPolicy.reclaim(wrapper, ::resolve, closed::add))
        assertEquals(2, closed.size)
    }

    fun testUnknownOrphanAndActiveBrowserAreNeverDisposed() {
        val wrapper = JPanel(BorderLayout())
        val unknown = JPanel()
        val stale = panelWithBrowser("stale")
        val active = panelWithBrowser("active")
        wrapper.add(unknown, BorderLayout.CENTER)
        wrapper.add(stale, BorderLayout.CENTER)
        wrapper.add(active, BorderLayout.CENTER)
        val closed = mutableListOf<String>()

        assertEquals(1, StaleMarkdownPreviewPanelPolicy.reclaim(wrapper, ::resolve, closed::add))
        assertEquals(listOf("stale"), closed)
        assertEquals(2, wrapper.componentCount)
        assertSame(wrapper, unknown.parent)
        assertSame(wrapper, active.parent)
        assertSame(active, (wrapper.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
    }

    fun testReleaseFailureRetainsOldComponentForDiagnosisAndRetry() {
        val wrapper = JPanel(BorderLayout())
        val old = panelWithBrowser("old")
        val active = panelWithBrowser("active")
        wrapper.add(old, BorderLayout.CENTER)
        wrapper.add(active, BorderLayout.CENTER)

        try {
            StaleMarkdownPreviewPanelPolicy.reclaim(wrapper, ::resolve) {
                error("diagnostic release failure")
            }
            fail("Expected a failed release")
        } catch (expected: IllegalStateException) {
            assertEquals("diagnostic release failure", expected.message)
        }
        assertSame(wrapper, old.parent)
        assertSame(wrapper, active.parent)
        val closed = mutableListOf<String>()
        assertEquals(1, StaleMarkdownPreviewPanelPolicy.reclaim(wrapper, ::resolve, closed::add))
        assertEquals(listOf("old"), closed)
    }

    fun testUnsupportedLayoutIsNeverChanged() {
        val wrapper = JPanel(FlowLayout())
        val old = panelWithBrowser("old")
        wrapper.add(old)
        val closed = mutableListOf<String>()
        assertEquals(0, StaleMarkdownPreviewPanelPolicy.reclaim(wrapper, ::resolve, closed::add))
        assertTrue(closed.isEmpty())
        assertSame(wrapper, old.parent)
    }
}
