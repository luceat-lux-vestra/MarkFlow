package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.intellij.plugins.markdown.settings.MarkdownSettings
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.WindowConstants

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
        val dialog: JDialog,
        val journal: Path,
        val closeMarker: Path,
    ) {
        val created = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
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
            "dialog_showing=" + dialog.isShowing,
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
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file)) {
            "Controlled JCEF fixture has no Document"
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
        val markdownPanel = provider.createHtmlPanel(project, file)
        val panel = markdownPanel as? JBCefBrowser
            ?: error("Bundled Markdown panel is not a JBCefBrowser")
        check(panel.javaClass.name == JCEF_PANEL) { "Unexpected bundled panel type" }
        val cef = panel.getCefBrowser()
        val owner = WindowManager.getInstance().getFrame(project)
            ?: error("No IDE owner frame for visible controlled JCEF reference")
        val dialog = JDialog(owner, "MarkFlow #353 controlled JCEF reference", Dialog.ModalityType.MODELESS)
        val state = OwnedReference(project, file, panel, cef, dialog, journal, marker)
        val handler = object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser) {
                if (browser !== cef || !state.created.compareAndSet(false, true)) {
                    state.callbackFailure.compareAndSet(null, "UNEXPECTED_ON_AFTER_CREATED")
                }
                state.record("on-after-created", browser)
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
        dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(panel.component, BorderLayout.CENTER)
        dialog.setSize(1200, 800)
        dialog.setLocationRelativeTo(owner)
        active = state
        state.record("component-attached")
        dialog.isVisible = true
        check(panel.component.isShowing) { "Controlled reference is not visible in IDE" }
        // This is the same provider preprocessing and setHtml interface used
        // in MarkdownPreviewFileEditor.doUpdateHtml, not raw Markdown-as-HTML.
        markdownPanel.setHtml(html, 0, 0, file)
        state.record("html-submitted")
        return state.snapshot()
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
        state.disposalRequested = true
        state.record("dispose-request")
        state.dialog.contentPane.remove(state.panel.component)
        Disposer.dispose(state.panel)
        state.dialog.dispose()
        state.record("disposed-wrapper")
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
        check(!state.dialog.isDisplayable && state.callbackFailure.get() == null) {
            "Controlled JCEF owner window or callback state is invalid"
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
