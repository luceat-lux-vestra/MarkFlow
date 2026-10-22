package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
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
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.intellij.plugins.markdown.settings.MarkdownSettings
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanelProvider
import java.awt.BorderLayout
import java.awt.Rectangle
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
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
        val sourceEditor: Editor?,
        val sourceSha256: String?,
        val sourceLine: Int,
        val sourceStamp: Long?,
    ) {
        val created = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val abortRequested = AtomicBoolean(false)
        val abortScheduled = AtomicBoolean(false)
        val callbackFailure = AtomicReference<String?>(null)
        val domProbePending = AtomicBoolean(false)
        val domProbeCompleted = AtomicInteger(0)
        var disposalRequested = false
        @Volatile var nativeCloseTarget: JcefNativeCloseBarrier.Target? = null
        @Volatile var synchronousClosePrepared = false
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
            "scope=" + (if (sourceEditor == null) "TEST_ONLY_NOT_BUNDLED_FILE_EDITOR"
                else "CONTROLLED_CORPUS_EXISTING_NATIVE_EDITOR"),
            "source_sha256=" + (sourceSha256 ?: "NOT_APPLICABLE"),
            "source_line_zero_based=" + sourceLine,
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
            "dom_probe_pending=" + domProbePending.get(),
            "dom_probe_completed=" + domProbeCompleted.get(),
            "callback_failure=" + (callbackFailure.get() ?: "NONE"),
        ).joinToString("\n", postfix = "\n")
    }

    private var active: OwnedReference? = null

    /**
     * Original isolated lifecycle fixture: no FileEditor may own this file.
     * Keep this contract unchanged so its 50/50 evidence remains comparable.
     */
    fun open(filename: String, journalPath: String, closeMarkerPath: String): String =
        openOwned(filename, journalPath, closeMarkerPath, null, null, 0)

    /**
     * #353 first 89-case integration seam: an independent bundled Markdown
     * reference for an already-open *native source Editor*. The caller must
     * provide the exact manifest case ID and source SHA, not an arbitrary path.
     * This does not yet change the full manifest runner or certify parity.
     *
     * Once this owned reference is created the caller must wait for native
     * creation, rendered content and visible paint, then await onBeforeClose,
     * cef.isClosed and finish() before opening the next case.
     */
    fun openCorpusReference(
        editor: Editor, caseId: String, expectedSourceSha256: String, sourceLine: Int,
        journalPath: String, closeMarkerPath: String,
    ): String {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(caseId)) {
            "Invalid differential corpus case ID"
        }
        require(Regex("[0-9a-f]{64}").matches(expectedSourceSha256)) {
            "Missing exact differential corpus source SHA-256"
        }
        return openOwned(
            "$caseId.md", journalPath, closeMarkerPath,
            editor, expectedSourceSha256, sourceLine,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private fun openOwned(
        filename: String, journalPath: String, closeMarkerPath: String,
        sourceEditor: Editor?, expectedSourceSha256: String?, sourceLine: Int,
    ): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(active == null) { "Previous controlled reference has not completed native close" }
        if (sourceEditor == null) {
            require(filename == "JCEF-LIFECYCLE-STRESS.md") { "Unapproved controlled fixture" }
            check(expectedSourceSha256 == null && sourceLine == 0)
        } else {
            check(expectedSourceSha256 != null) { "Corpus reference requires source identity" }
            check(filename != "JCEF-LIFECYCLE-STRESS.md") {
                "Corpus reference must not weaken isolated stress fixture"
            }
        }

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
        val fileIsOpen = FileEditorManager.getInstance(project).isFileOpen(file)
        val document = ReadAction.compute<Document, RuntimeException> {
            checkNotNull(FileDocumentManager.getInstance().getDocument(file)) {
                "Controlled JCEF fixture has no Document"
            }
        }
        if (sourceEditor == null) {
            check(!fileIsOpen) {
                "Controlled JCEF fixture must not instantiate the bundled FileEditor"
            }
        } else {
            val editorProjectSame = sourceEditor.project === project
            val editorDocumentSame = sourceEditor.document === document
            val editorFile = FileDocumentManager.getInstance().getFile(sourceEditor.document)
            val editorShowing = sourceEditor.contentComponent.isShowing
            check(fileIsOpen && editorProjectSame && editorDocumentSame && editorShowing) {
                "Corpus reference must bind an existing visible native source Editor: " +
                    "fileIsOpen=" + fileIsOpen +
                    " projectSame=" + editorProjectSame +
                    " documentSame=" + editorDocumentSame +
                    " editorShowing=" + editorShowing +
                    " targetFile=" + file.path +
                    " editorFile=" + (editorFile?.path ?: "NONE") +
                    " targetDocumentIdentity=" + System.identityHashCode(document) +
                    " editorDocumentIdentity=" + System.identityHashCode(sourceEditor.document) +
                    " targetFileIdentity=" + System.identityHashCode(file) +
                    " editorFileIdentity=" + (editorFile?.let(System::identityHashCode) ?: 0)
            }
        }
        check(!FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            "Controlled fixture was edited before reference rendering"
        }
        val sourceStamp = if (sourceEditor != null) {
            check(sourceEditor.document === document) {
                "Corpus reference Document differs from the authoritative native Editor"
            }
            require(sourceLine in 0 until document.lineCount) {
                "Corpus reference source line is outside the Document"
            }
            val actualSha = sha256(Files.readAllBytes(root.resolve(filename)))
            check(actualSha == expectedSourceSha256) {
                "Corpus reference bytes differ from the manifest source SHA-256"
            }
            check(document.text == Files.readString(root.resolve(filename))) {
                "Corpus reference Document differs from the saved manifest fixture"
            }
            document.modificationStamp
        } else null

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
        check(html.contains("<html") &&
            (sourceEditor != null || html.contains("Minimal reference preview lifecycle"))
        ) {
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
        val state = OwnedReference(
            project, file, panel, cef, overlay, layeredPane, journal, marker,
            sourceEditor, expectedSourceSha256, sourceLine, sourceStamp,
        )
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
            val sourceOffset = if (sourceEditor == null) 0 else document.getLineStartOffset(sourceLine)
            markdownPanel.setHtml(html, sourceOffset, sourceLine, file)
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


    /**
     * Execute DOM JavaScript in the exact owned CefBrowser, not Driver's global
     * JCEF selector. The callback emits immutable source/browser identity and
     * the output. The IDE EDT must not block on this asynchronous JCEF result.
     */
    fun requestDomProbe(probeName: String, script: String, outputPath: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(probeName in setOf(
            "heading-dom-geometry", "following-paragraph-dom-geometry",
            "mermaid-svg-dom-geometry", "generic-dom-geometry"
        )) { "Unapproved controlled DOM probe" }
        require(script.isNotBlank()) { "Empty controlled DOM probe" }
        val state = checkNotNull(active) { "No active controlled reference" }
        check(state.sourceEditor != null && state.sourceSha256 != null) {
            "Corpus DOM probe requires a source-bound native Editor"
        }
        requestDomProbeOnExactBrowser(state, probeName, script, outputPath)
    }

    /**
     * Separate #353 diagnostic only. Exercise the same JBCefJSQuery implementation
     * in the test-owned 50-cycle fixture; never relax corpus source binding.
     */
    fun requestStressDomProbe(outputPath: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active) { "No active controlled reference" }
        check(state.sourceEditor == null && state.sourceSha256 == null &&
            state.file.name == "JCEF-LIFECYCLE-STRESS.md"
        ) { "DOM resource stress requires the original independent lifecycle fixture" }
        requestDomProbeOnExactBrowser(
            state, "lifecycle-dom-text",
            "document.body && document.body.innerText || ''", outputPath,
        )
    }

    private fun requestDomProbeOnExactBrowser(
        state: OwnedReference, probeName: String, script: String, outputPath: String,
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(state.created.get() && !state.disposalRequested &&
            !state.cef.isClosing && !state.cef.isClosed &&
            state.callbackFailure.get() == null
        ) { "DOM probe outside exact native browser lifetime" }
        check(state.domProbePending.compareAndSet(false, true)) {
            "Overlapping DOM queries have ambiguous callback ownership"
        }
        val target = Path.of(outputPath).toAbsolutePath().normalize()
        val identity = Path.of(target.toString() + ".identity")
        check(target.startsWith(state.journal.parent) &&
            !Files.exists(target) && !Files.exists(identity)
        ) { "DOM probe output must be unique inside owned evidence" }
        val nonce = UUID.randomUUID().toString()
        // The bundled Markdown JCEF panel reserves 20 JSQuery slots on its
        // client. Do not force a process-wide pool across unrelated clients.
        val query = JBCefJSQuery.create(state.panel as JBCefBrowserBase)
        Disposer.register(state.panel, query) // fallback if native close races callback
        query.addHandler { wire ->
            try {
                check(active === state && state.created.get() &&
                    !state.disposalRequested && !state.cef.isClosing &&
                    !state.cef.isClosed
                ) { "Controlled DOM callback outside exact browser lifetime" }
                val fields = wire.split('\t', limit = 3)
                check(fields.size == 3 && fields[0] == nonce) {
                    "Controlled DOM callback nonce mismatch"
                }
                check(fields[1] == "OK") {
                    "Controlled DOM JavaScript error: " +
                        URLDecoder.decode(fields[2], Charsets.UTF_8)
                }
                val payload = URLDecoder.decode(fields[2], Charsets.UTF_8)
                check(payload.isNotBlank()) { "Empty controlled DOM callback" }
                Files.writeString(target, payload, StandardOpenOption.CREATE_NEW)
                Files.writeString(
                    identity,
                    "schema=markflow-controlled-jcef-dom-callback/v1\n" +
                        "probe_name=$probeName\n" +
                        "case_id=" + state.file.name.removeSuffix(".md") + "\n" +
                        "source_sha256=" + (state.sourceSha256 ?: "NOT_APPLICABLE") + "\n" +
                        "browser_identity=" + System.identityHashCode(state.panel) + "\n" +
                        "cef_identity=" + System.identityHashCode(state.cef) + "\n" +
                        "cef_native_id=" + state.cef.identifier + "\n" +
                        "query_browser_bound=true\n" +
                        "nonce=$nonce\n" +
                        "status=OK\n",
                    StandardOpenOption.CREATE_NEW,
                )
                state.domProbeCompleted.incrementAndGet()
                state.record("dom-probe-return-" + probeName)
            } catch (error: Exception) {
                state.callbackFailure.compareAndSet(null,
                    "DOM_PROBE_" + error.javaClass.name +
                        "_" + (error.message ?: "UNKNOWN").take(120))
                state.record("dom-probe-failed-" + probeName)
            } finally {
                state.domProbePending.set(false)
                // JCEF router callbacks are not the correct place to remove
                // their own message router. Release after callback dispatch on
                // EDT instead of retaining every handler until browser close.
                // The parent panel remains the fallback owner if close wins.
                ApplicationManager.getApplication().invokeLater {
                    if (!query.isDisposed) {
                        runCatching { Disposer.dispose(query) }
                            .onSuccess { state.record("dom-query-disposed-" + probeName) }
                            .onFailure { failure ->
                                state.callbackFailure.compareAndSet(
                                    null, "DOM_QUERY_DISPOSE_" + failure.javaClass.name,
                                )
                                state.record("dom-query-dispose-failed-" + probeName)
                            }
                    }
                }
            }
            null
        }
        state.record("dom-probe-request-" + probeName)
        val javascript = """
            (() => {
              const nonce = '$nonce';
              let status = 'OK';
              let result;
              try {
                result = String(($script));
              } catch (error) {
                status = 'ERROR';
                result = String(error && (error.stack || error));
              }
              const wire = nonce + '\t' + status + '\t' + encodeURIComponent(result);
              ${query.inject("wire")}
            })();
        """.trimIndent()
        state.cef.executeJavaScript(javascript, state.cef.url, 0)
    }

    /** Read a synchronous native server snapshot off EDT, without changing its state. */
    fun nativeCloseBarrierSnapshot(phase: String): String {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        val state = checkNotNull(active)
        check(state.created.get())
        val target = state.nativeCloseTarget ?: JcefNativeCloseBarrier.capture(state.cef).also {
            state.nativeCloseTarget = it
        }
        return JcefNativeCloseBarrier.snapshot(state.cef, target, phase)
    }

    /** Detach the visible component before the optional synchronous native close. */
    fun prepareSynchronousNativeClose() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active)
        check(state.created.get() && !state.closed.get() &&
            !state.domProbePending.get() && !state.disposalRequested &&
            !state.synchronousClosePrepared)
        state.layeredPane.remove(state.overlay)
        state.layeredPane.revalidate()
        state.layeredPane.repaint()
        state.synchronousClosePrepared = true
        state.record("sync-close-component-detached")
    }

    /** One test-only synchronous Thrift RPC; never block the IDE EDT. */
    fun requestSynchronousNativeClose(): String {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        val state = checkNotNull(active)
        check(state.synchronousClosePrepared && !state.disposalRequested)
        val target = checkNotNull(state.nativeCloseTarget)
        val result = JcefNativeCloseBarrier.requestSynchronousClose(state.cef, target)
        state.record("sync-close-rpc-returned")
        return result
    }

    /** Must be invoked after onAfterCreated, not after a fixed delay. */
    fun close(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active)
        check(state.created.get() &&
            (!state.closed.get() || state.synchronousClosePrepared)
        ) {
            "Cannot dispose before native creation or after native close"
        }
        check(state.callbackFailure.get() == null) { "CEF callback diagnostic failed" }
        check(!state.domProbePending.get()) { "Cannot close with pending DOM callback" }
        check(!state.disposalRequested) { "Repeated controlled JCEF dispose" }
        disposeOwnedReference(state, "normal-close")
        return state.snapshot()
    }

    /** Strict close barrier; another browser cannot be opened before this. */
    fun finish(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val state = checkNotNull(active)
        check(!state.domProbePending.get()) { "DOM callback pending at finish" }
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
        if (state.sourceEditor == null) {
            check(!FileEditorManager.getInstance(state.project).isFileOpen(state.file)) {
                "Controlled reference accidentally opened a platform FileEditor"
            }
        } else {
            val document = state.sourceEditor.document
            val targetDocument = ReadAction.compute<Document, RuntimeException> {
                checkNotNull(FileDocumentManager.getInstance().getDocument(state.file)) {
                    "Corpus source file lost its authoritative Document at finish"
                }
            }
            check(FileEditorManager.getInstance(state.project).isFileOpen(state.file) &&
                targetDocument === document &&
                document.modificationStamp == state.sourceStamp &&
                !FileDocumentManager.getInstance().isDocumentUnsaved(document) &&
                sha256(Files.readAllBytes(Path.of(requireNotNull(state.project.basePath), state.file.name))) ==
                    state.sourceSha256
            ) { "Corpus reference changed or lost the authoritative source Editor" }
        }
        val evidence = state.snapshot()
        state.record("finish")
        active = null
        return evidence
    }
}
