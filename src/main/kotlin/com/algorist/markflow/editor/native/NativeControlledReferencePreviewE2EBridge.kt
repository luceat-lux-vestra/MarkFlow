package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.IdeGlassPane
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.intellij.plugins.markdown.settings.MarkdownSettings
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import java.awt.BorderLayout
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * #353 opt-in Starter diagnostic ONLY. Not registered as an IntelliJ extension.
 *
 * Own exactly one genuine bundled JCEF MarkdownHtmlPanel, without constructing
 * MarkdownPreviewFileEditor or mutating its private fields. This is intentionally
 * NOT a substitute for the platform's split-file-editor lifecycle regression.
 *
 * Every method except CEF callbacks is called on the IDE EDT by the Driver.
 * The test process polls native callback marker files without blocking EDT.
 */
@Suppress("unused")
internal object NativeControlledReferencePreviewE2EBridge {
    private const val JCEF_PROVIDER = "com.intellij.markdown.jcef.preview.JCEFHtmlPanelProvider"
    private const val JCEF_PANEL = "com.intellij.markdown.jcef.preview.MarkdownJCEFHtmlPanel"

    private class OwnedReference(
        val project: Project,
        val file: VirtualFile,
        val panel: JBCefBrowser,
        val cef: CefBrowser,
        val overlay: JPanel,
        val layeredPane: JLayeredPane,
        val journal: Path,
        val closeMarker: Path,
    ) {
        val created = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val abortRequested = AtomicBoolean(false)
        val abortScheduled = AtomicBoolean(false)
        val callbackFailure = AtomicReference<String?>(null)
        var disposalRequested = false
        private val journalLock = Any()

        fun record(event: String, callback: CefBrowser? = null) {
            try {
                synchronized(journalLock) {
                    Files.writeString(
                        journal,
                        "timestamp_ms=" + System.currentTimeMillis() +
                            "\tevent=" + event +
                            "\tcef_identity=" + System.identityHashCode(cef) +
                            "\tcallback_same=" + (callback === cef) +
                            "\tcef_native_id=" + cef.identifier +
                            "\tthread=" + Thread.currentThread().name + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    )
                }
            } catch (error: Exception) {
                callbackFailure.compareAndSet(null, "JOURNAL_WRITE_" + error.javaClass.name)
            }
        }

        fun snapshot(): String = listOf(
            "schema=markflow-controlled-reference-jcef/v1",
            "scope=TEST_ONLY_NOT_BUNDLED_FILE_EDITOR",
            "provider=" + JCEF_PROVIDER,
            "panel=" + panel.javaClass.name,
            "browser_identity=" + System.identityHashCode(panel),
            "cef_identity=" + System.identityHashCode(cef),
            "cef_native_id=" + (if (created.get()) cef.identifier else "PENDING"),
            "native_created=" + created.get(),
            "native_before_close=" + closed.get(),
            "cef_closing=" + cef.isClosing,
            "cef_closed=" + cef.isClosed,
            "jbcef_disposed=" + panel.isDisposed,
            "dispose_requested=" + disposalRequested,
            "host_showing=" + overlay.isShowing,
            "host_ide_glass_pane=" + (SwingUtilities.getRootPane(overlay)?.glassPane is IdeGlassPane),
            "panel_showing=" + panel.component.isShowing,
            "panel_width=" + panel.component.width,
            "panel_height=" + panel.component.height,
            "callback_failure=" + (callbackFailure.get() ?: "NONE"),
        ).joinToString("\n", postfix = "\n")
    }

    private var active: OwnedReference? = null

    /**
     * No open FileEditor is allowed for this fixture: otherwise JetBrains may
     * silently create the second browser via its existing init/componentShown
     * path, invalidating this experiment.
     */
    fun open(filename: String, journalPath: String, closeMarkerPath: String): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(active == null) { "Previous controlled reference has not completed native close" }
        require(filename == "JCEF-LIFECYCLE-STRESS.md") { "Unapproved controlled fixture" }

        val matching = ProjectManager.getInstance().openProjects.filter { project ->
            val root = project.basePath ?: return@filter false
            Files.isRegularFile(Path.of(root, filename))
        }
        check(matching.size == 1) { "Expected exactly one project with controlled JCEF fixture" }
        val project = matching.single()
        val root = Path.of(requireNotNull(project.basePath))
        val file = checkNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(filename))
        ) { "Controlled JCEF fixture has no VirtualFile" }
        check(!FileEditorManager.getInstance(project).isFileOpen(file)) {
            "Controlled JCEF fixture must not instantiate the bundled FileEditor"
        }
        val document = ReadAction.compute<Document, RuntimeException> {
            checkNotNull(FileDocumentManager.getInstance().getDocument(file)) {
                "Controlled JCEF fixture has no Document"
            }
        }
        check(!FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            "Controlled fixture was edited before reference rendering"
        }

        val provider = MarkdownHtmlPanelProvider.createFromInfo(
            MarkdownSettings.getInstance(project).previewPanelProviderInfo
        )
        check(provider.javaClass.name == JCEF_PROVIDER) {
            "Reference is not the pinned bundled JCEF provider: " + provider.javaClass.name
        }
        check(provider.isAvailable === MarkdownHtmlPanelProvider.AvailabilityInfo.AVAILABLE) {
            "Bundled JCEF provider unavailable"
        }
        val html = ReadAction.compute<String, RuntimeException> {
            provider.sourceTextPreprocessor.preprocessText(project, document, file)
        }
        check(html.contains("<html") && html.contains("Minimal reference preview lifecycle")) {
            "Bundled Markdown HTML preprocessing did not run"
        }

        val journal = Path.of(journalPath).toAbsolutePath().normalize()
        val marker = Path.of(closeMarkerPath).toAbsolutePath().normalize()
        check(!Files.exists(journal) && !Files.exists(marker)) {
            "A previous controlled reference marker was reused"
        }
        Files.createDirectories(requireNotNull(journal.parent))
        Files.createDirectories(requireNotNull(marker.parent))
        // The bundled panel installs JBSplitter painters via IdeGlassPaneUtil.
        // A bare JDialog supplies a JPanel glass pane; the live IDE frame does not.
        val owner = WindowManager.getInstance().getFrame(project)
            ?: error("No IDE frame for the controlled reference")
        val layeredPane = owner.layeredPane
        check(owner.rootPane.glassPane is IdeGlassPane && layeredPane.isShowing) {
            "Controlled JCEF must use a visible IDE-owned glass pane"
        }
        val width = minOf(1200, layeredPane.width - 32)
        val height = minOf(800, layeredPane.height - 32)
        check(width > 100 && height > 100) { "IDE viewport too small for controlled JCEF" }

        val markdownPanel = provider.createHtmlPanel(project, file)
        val panel = markdownPanel as? JBCefBrowser
            ?: error("Bundled Markdown panel is not a JBCefBrowser")
        if (panel.javaClass.name != JCEF_PANEL) {
            Disposer.dispose(panel) // not attached, native create not requested
            error("Unexpected bundled Markdown panel type: " + panel.javaClass.name)
        }
        val cef = panel.getCefBrowser()
        val overlay = JPanel(BorderLayout()).apply {
            isOpaque = true
            add(panel.component, BorderLayout.CENTER)
            setBounds(
                (layeredPane.width - width) / 2,
                (layeredPane.height - height) / 2,
                width, height,
            )
        }
        val state = OwnedReference(project, file, panel, cef, overlay, layeredPane, journal, marker)
        val handler = object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser) {
                if (browser !== cef || !state.created.compareAndSet(false, true)) {
                    state.callbackFailure.compareAndSet(null, "UNEXPECTED_ON_AFTER_CREATED")
                }
                state.record("on-after-created", browser)
                if (state.abortRequested.get()) scheduleInitFailureCleanup(state)
            }

            override fun onBeforeClose(browser: CefBrowser) {
                if (browser !== cef || !state.created.get() || !state.closed.compareAndSet(false, true)) {
                    state.callbackFailure.compareAndSet(null, "UNEXPECTED_ON_BEFORE_CLOSE")
                }
                state.record("on-before-close", browser)
                try {
                    Files.writeString(
                        marker,
                        "schema=markflow-controlled-native-close/v1\n" +
                            "cef_identity=" + System.identityHashCode(browser) + "\n" +
                            "browser_identity=" + System.identityHashCode(panel) + "\n" +
                            "on_before_close=true\n",
                        StandardOpenOption.CREATE_NEW,
                    )
                } catch (error: Exception) {
                    state.callbackFailure.compareAndSet(null, "CLOSE_MARKER_WRITE_" + error.javaClass.name)
                }
            }
        }
        // Browser construction is lazy on this pinned platform. Register the
        // exact-CEF handler before attaching the component to a visible owner.
        panel.getJBCefClient().addLifeSpanHandler(handler, cef)
        state.record("handler-installed")
        active = state
        try {
            // One attachment to the existing IDE root (with its IdeGlassPane).
            layeredPane.add(overlay, JLayeredPane.POPUP_LAYER)
            layeredPane.revalidate()
            layeredPane.repaint()
            state.record("component-attached")
            check(SwingUtilities.getRootPane(panel.component) === owner.rootPane &&
                panel.component.isShowing
            ) { "Controlled JCEF component is not showing under the IDE root pane" }
            // Use the bundled Markdown provider's HTML, not raw Markdown.
            markdownPanel.setHtml(html, 0, 0, file)
            state.record("html-submitted")
            return state.snapshot()
        } catch (failure: Throwable) {
            // Native creation may be in flight. Do not close prematurely.
            state.abortRequested.set(true)
            state.record("initialization-failed-" + failure.javaClass.simpleName)
            scheduleInitFailureCleanup(state)
            throw failure
        }
    }

    /**
     * Initialization can fail while Remote JCEF native creation is pending.
     * Await the exact onAfterCreated callback before scheduling EDT disposal.
     * An uncreated browser remains a test failure; never fabricate close proof.
     */
    private fun scheduleInitFailureCleanup(state: OwnedReference) {
        if (!state.abortRequested.get() || !state.created.get() ||
            !state.abortScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            if (active === state && !state.disposalRequested) {
                runCatching { disposeOwnedReference(state, "initialization-failure") }
                    .onFailure { failure ->
                        state.callbackFailure.compareAndSet(
                            null, "INITIALIZATION_CLEANUP_" + failure.javaClass.name
                        )
                        state.record("initialization-cleanup-failed")
                    }
            }
        }
    }

    private fun disposeOwnedReference(state: OwnedReference, reason: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(state.created.get()) { "Cannot dispose before native onAfterCreated" }
        check(!state.disposalRequested) { "Controlled browser already disposed" }
        state.disposalRequested = true
        state.record("dispose-request-" + reason)
        state.layeredPane.remove(state.overlay)
        state.layeredPane.revalidate()
        state.layeredPane.repaint()
        Disposer.dispose(state.panel)
        state.record("disposed-wrapper")
    }

    fun snapshot(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return checkNotNull(active) { "No controlled reference" }.snapshot()
    }

    fun bounds(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active)
        check(state.created.get() && !state.disposalRequested && state.panel.component.isShowing) {
            "Controlled reference must be native-created and showing before capture"
        }
        val component = state.panel.component
        val point = component.locationOnScreen
        val rect = Rectangle(point.x, point.y, component.width, component.height)
        check(rect.width > 100 && rect.height > 100) { "Controlled JCEF viewport is too small" }
        return listOf(rect.x, rect.y, rect.width, rect.height).joinToString(",")
    }

    /** CEF callback writes the result directly; never waits for it on EDT. */
    fun requestRenderedText(probePath: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active)
        check(state.created.get() && !state.disposalRequested && !state.cef.isClosing) {
            "Controlled JCEF text probe called outside the created browser lifetime"
        }
        val probe = Path.of(probePath).toAbsolutePath().normalize()
        Files.createDirectories(requireNotNull(probe.parent))
        check(!Files.exists(probe)) { "Controlled JCEF text probe path reused" }
        state.record("cef-get-text-request")
        state.cef.getText { text ->
            try {
                Files.writeString(probe, text ?: "", StandardOpenOption.CREATE_NEW)
                state.record("cef-get-text-return")
            } catch (error: Exception) {
                state.callbackFailure.compareAndSet(null, "TEXT_PROBE_WRITE_" + error.javaClass.name)
            }
        }
    }

    /** Must be invoked after onAfterCreated, not after a fixed delay. */
    fun close(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active)
        check(state.created.get() && !state.closed.get()) {
            "Cannot dispose before native creation or after native close"
        }
        check(state.callbackFailure.get() == null) { "CEF callback diagnostic failed" }
        check(!state.disposalRequested) { "Repeated controlled JCEF dispose" }
        disposeOwnedReference(state, "normal-close")
        return state.snapshot()
    }

    /** Strict close barrier; another browser cannot be opened before this. */
    fun finish(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active)
        check(state.disposalRequested && state.created.get() && state.closed.get()) {
            "Controlled JCEF browser never reached native onBeforeClose"
        }
        check(Files.exists(state.closeMarker) && state.cef.isClosed && state.panel.isDisposed) {
            "Controlled JCEF browser did not complete native and wrapper close"
        }
        check(state.overlay.parent == null && !state.overlay.isDisplayable &&
            state.callbackFailure.get() == null && !state.abortRequested.get()
        ) {
            "Controlled JCEF overlay or callback state is invalid"
        }
        check(!FileEditorManager.getInstance(state.project).isFileOpen(state.file)) {
            "Controlled reference accidentally opened a platform FileEditor"
        }
        val evidence = state.snapshot()
        state.record("finish")
        active = null
        return evidence
    }
}
