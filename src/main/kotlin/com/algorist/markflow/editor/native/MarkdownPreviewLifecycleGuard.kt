package com.algorist.markflow.editor.native

import com.algorist.markflow.file.MarkFlowFileSupport
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * #353 pinned-upstream compatibility guard, installed only with the optional JCEF plugin.
 *
 * In IDEA 262.10968.63 MarkdownPreviewFileEditor.attachHtmlPanel() adds the
 * next panel to a BorderLayout.CENTER without disposing any previous CENTER
 * component. Two queued attaches can orphan the old MarkdownJCEFHtmlPanel:
 * MarkdownPreviewFileEditor.dispose() subsequently disposes only its last panel.
 *
 * Never dispose the layout's current CENTER panel. Only recover an older
 * MarkdownJCEFHtmlPanel whose own JBCefBrowser instance can be identified in
 * the same bundled Markdown Preview wrapper. Unknown browser providers fail
 * closed, and no JetBrains private field is accessed or mutated.
 */
internal class MarkdownPreviewLifecycleGuard : FileEditorManagerListener {
    private val tracked = mutableMapOf<VirtualFile, MutableList<PanelOwner>>()

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!MarkFlowFileSupport.isMarkFlowTarget(file) || !pinnedIdeaBuild()) return
        val owners = tracked.getOrPut(file) { mutableListOf() }
        source.getEditors(file).forEach { candidate ->
            val split = candidate as? TextEditorWithPreview ?: return@forEach
            if (split.javaClass.name != BUNDLED_SPLIT_EDITOR ||
                split.previewEditor.javaClass.name != BUNDLED_PREVIEW_EDITOR
            ) return@forEach
            val wrapper = split.previewEditor.component as? JPanel ?: return@forEach
            if (wrapper.layout !is BorderLayout ||
                owners.any { it.wrapper === wrapper }
            ) return@forEach
            owners += PanelOwner(wrapper, file.name)
        }
        if (owners.isEmpty()) tracked.remove(file)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        tracked.remove(file)?.forEach(PanelOwner::stop)
    }

    private class PanelOwner(val wrapper: JPanel, private val fileName: String) {
        private var stopped = false
        private var sweepScheduled = false

        private val listener = object : ContainerAdapter() {
            override fun componentAdded(e: ContainerEvent) = scheduleSweep()
            override fun componentRemoved(e: ContainerEvent) = scheduleSweep()
        }

        init {
            wrapper.addContainerListener(listener)
            scheduleSweep()
        }

        private fun scheduleSweep() {
            if (stopped || sweepScheduled) return
            sweepScheduled = true
            ApplicationManager.getApplication().invokeLater {
                sweepScheduled = false
                if (!stopped) sweep()
            }
        }

        private fun sweep() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                val recovered = StaleMarkdownPreviewPanelPolicy.reclaim(
                    wrapper,
                    resolver = ::findBundledBrowser,
                    release = { browser ->
                        if (!browser.isDisposed) Disposer.dispose(browser)
                    },
                )
                if (recovered > 0) {
                    LOG.warn(
                        "Recovered $recovered detached bundled Markdown JCEF panel(s) " +
                            "for $fileName on IDEA 262.10968.63",
                    )
                }
            } catch (failure: Throwable) {
                LOG.warn("Detached bundled Markdown JCEF panel recovery failed for $fileName", failure)
            }
        }

        fun stop() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (stopped) return
            wrapper.removeContainerListener(listener)
            // At file close the platform still owns the last CENTER panel.
            // Reclaim only older, no-longer-CENTER panels.
            sweep()
            stopped = true
        }
    }

    private fun pinnedIdeaBuild(): Boolean =
        ApplicationInfo.getInstance().build.asString().endsWith("262.10968.63")

    companion object {
        private const val BUNDLED_SPLIT_EDITOR =
            "org.intellij.plugins.markdown.ui.preview.MarkdownEditorWithPreview"
        private const val BUNDLED_PREVIEW_EDITOR =
            "org.intellij.plugins.markdown.ui.preview.MarkdownPreviewFileEditor"
        private const val BUNDLED_JCEF_PANEL =
            "com.intellij.markdown.jcef.preview.MarkdownJCEFHtmlPanel"
        private val LOG = Logger.getInstance(MarkdownPreviewLifecycleGuard::class.java)

        private fun findBundledBrowser(root: Component): JBCefBrowser? =
            StaleMarkdownPreviewPanelPolicy.findDescendant(root) { component ->
                val candidate = (component as? JComponent)?.getClientProperty(
                    JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP,
                ) as? JBCefBrowser
                candidate?.takeIf { it.javaClass.name == BUNDLED_JCEF_PANEL }
            }
    }
}

/** Pure Swing ownership policy, independently testable without launching Chromium. */
internal object StaleMarkdownPreviewPanelPolicy {
    fun <T : Any> findDescendant(root: Component, lookup: (Component) -> T?): T? {
        lookup(root)?.let { return it }
        if (root is Container) {
            for (child in root.components) {
                findDescendant(child, lookup)?.let { return it }
            }
        }
        return null
    }

    fun <T : Any> reclaim(
        wrapper: JPanel,
        resolver: (Component) -> T?,
        release: (T) -> Unit,
    ): Int {
        val layout = wrapper.layout as? BorderLayout ?: return 0
        val current = layout.getLayoutComponent(BorderLayout.CENTER)
        // A current component managed by another parent is an unsupported layout.
        if (current != null && current.parent !== wrapper) return 0
        var reclaimed = 0
        for (candidate in wrapper.components.toList()) {
            if (candidate === current || candidate.parent !== wrapper) continue
            val owned = resolver(candidate) ?: continue
            // Release first: failure must never silently discard the only
            // remaining container handle for retry/diagnostic evidence.
            release(owned)
            if (candidate.parent === wrapper) wrapper.remove(candidate)
            reclaimed++
        }
        if (reclaimed > 0) {
            wrapper.revalidate()
            wrapper.repaint()
        }
        return reclaimed
    }
}
