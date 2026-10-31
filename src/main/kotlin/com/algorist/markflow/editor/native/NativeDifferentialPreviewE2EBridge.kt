package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ContainerEvent
import java.lang.ref.WeakReference
import com.intellij.openapi.project.ProjectManager
import javax.swing.JLabel
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefApp
import com.intellij.openapi.util.registry.Registry
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.browser.CefFrame
import org.cef.network.CefRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path

/**
 * #353 Starter-only seam to the genuine bundled IntelliJ Markdown Preview.
 * No product registration, source mutation, or synthetic renderer.
 * An upstream 2026.2.3 internal change fails closed at the inspected boundary.
 */
@Suppress("unused")
internal object NativeDifferentialPreviewE2EBridge {
    private const val MARKDOWN_SPLIT = "org.intellij.plugins.markdown.ui.preview.MarkdownEditorWithPreview"
    private const val MARKDOWN_PREVIEW = "org.intellij.plugins.markdown.ui.preview.MarkdownPreviewFileEditor"
    private val fontOriginals =
        java.util.WeakHashMap<Editor, Float>()

    // #353 Full Differential close barrier. MarkdownPreviewFileEditor nulls its
    // panel after Disposer.dispose(panel), but JBCefBrowser closes the native
    // CefBrowser asynchronously via close(true). Retain the most recently hidden
    // browser long enough to prove native close completion before the corpus
    // creates another bundled Preview.
    private var lastDetachedReferenceBrowser: JBCefBrowser? = null
    private var lastDetachedReferencePanelIdentity: Int? = null
    private var lastDetachedReferenceBeforeClose = AtomicBoolean(true)
    private var nextDetachedReferenceCloseMarker: Path? = null
    private var lastDetachedReferenceCloseMarker: Path? = null
    // Exactly one diagnostic listener per live bundled Preview; removed before
    // that panel's normal disposal so successive iterations do not accumulate it.
    private data class ReferenceLoadTrace(
        val browser: JBCefBrowser,
        val handler: CefLoadHandlerAdapter,
        val path: Path,
    )
    private var referenceLoadTrace: ReferenceLoadTrace? = null

    // #353 2-open, direct_split-only ownership probe. Capture the platform's
    // synchronous ContainerEvent at the point MarkdownPreviewFileEditor has
    // stored its current panel but before a subsequent attach can overwrite it.
    // No panel creation/disposal, no waiting, no synthetic CEF events.
    private data class ObservedPanelOwner(
        val wrapperId: Int,
        val componentId: Int,
        val panelId: Int,
        val browserId: Int,
        val cefId: Int,
        val firstObservedEvent: String,
        val cefNativeIdAtCapture: String,
        val browser: WeakReference<JBCefBrowser>,
        val lifeSpanHandler: CefLifeSpanHandlerAdapter,
    )
    private class PanelOwnerTrace(
        val path: Path,
        val filename: String,
        val listener: AWTEventListener,
        val observed: MutableList<ObservedPanelOwner> = mutableListOf(),
        var iteration: Int = 0,
        var eagerNativeCreate: Boolean = false,
        var eagerCreateAttempts: Int = 0,
        var eagerCreateReturns: Int = 0,
        var eagerCreateFailure: String = "NONE",
    )
    private var panelOwnerTrace: PanelOwnerTrace? = null

    /** Serialize EDT and native CEF callback evidence into one event journal. */
    private fun appendPanelOwnerTrace(trace: PanelOwnerTrace, line: String) {
        synchronized(trace) {
            runCatching {
                Files.writeString(trace.path, line + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)
            }.onFailure { error ->
                runCatching {
                    Files.writeString(
                        trace.path.resolveSibling(trace.path.fileName.toString() + ".write-error"),
                        "error_class=" + error.javaClass.name + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND
                    )
                }
            }
        }
    }

    /** Locate the real platform preview on EDT; never instantiate an editor. */
    private fun resolvePreviewWrapperOwner(
        wrapper: Container, child: Component, filename: String
    ): JBCefBrowser? {
        for (project in ProjectManager.getInstance().openProjects) {
            val manager = FileEditorManager.getInstance(project)
            for (file in manager.openFiles) {
                if (file.name != filename) continue
                for (selected in manager.getEditors(file).filterIsInstance<TextEditorWithPreview>()) {
                    if (selected.javaClass.name != MARKDOWN_SPLIT) continue
                    val field = TextEditorWithPreview::class.java.getDeclaredField("myPreview")
                    check(field.trySetAccessible()) { "Preview field reflection changed" }
                    val preview = field.get(selected) as? FileEditor ?: continue
                    if (preview.javaClass.name != MARKDOWN_PREVIEW ||
                        preview.component !== wrapper) continue
                    val panel = previewPanel(preview) as? JBCefBrowser ?: continue
                    if (panel.component === child) return panel
                }
            }
        }
        return null
    }

    private fun cefNativeId(browser: JBCefBrowser): String = runCatching {
        val cef = browser.getCefBrowser()
        cef.javaClass.getMethod("getIdentifier").invoke(cef).toString()
    }.getOrElse { "UNAVAILABLE_" + it.javaClass.simpleName }

    /**
     * Register before opening the Markdown file. Restrict capture to actual
     * JBLoadingPanel additions/removals. Resolve owners on both events:
     * IDEA may not expose the FileEditor during COMPONENT_ADDED, but its
     * soon-to-be-replaced panel is still inspectable at COMPONENT_REMOVED.
     * Record unsuccessful lookups instead of treating them as no attachment.
     */
    fun startReferencePanelOwnerTrace(filename: String, outputPath: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(panelOwnerTrace == null) { "Panel owner trace already armed" }
        require(filename == "JCEF-LIFECYCLE-STRESS.md")
        val path = Path.of(outputPath).toAbsolutePath().normalize()
        Files.createDirectories(requireNotNull(path.parent))
        Files.deleteIfExists(path)
        lateinit var listener: AWTEventListener
        listener = AWTEventListener { event ->
            val containerEvent = event as? ContainerEvent ?: return@AWTEventListener
            val child = containerEvent.child ?: return@AWTEventListener
            if (child.javaClass.name != "com.intellij.ui.components.JBLoadingPanel") {
                return@AWTEventListener
            }
            val parent = containerEvent.container
            val eventName = when (containerEvent.id) {
                ContainerEvent.COMPONENT_ADDED -> "component-added"
                ContainerEvent.COMPONENT_REMOVED -> "component-removed"
                else -> return@AWTEventListener
            }
            val trace = panelOwnerTrace ?: return@AWTEventListener
            // Observation errors are recorded, not propagated to platform EDT.
            val line = runCatching {
                val owner = resolvePreviewWrapperOwner(parent, child, trace.filename)
                // AWT emits COMPONENT_ADDED before the bundled preview's
                // FileEditor is necessarily discoverable through FileEditorManager.
                // At COMPONENT_REMOVED the old 'panel' field may still reference
                // that exact first panel, just before it is overwritten/disposed.
                // Capture either event, exactly once per actual panel identity.
                val newOwnerCaptured = if (owner != null &&
                    trace.observed.none {
                        it.wrapperId == System.identityHashCode(parent) &&
                            it.componentId == System.identityHashCode(child) &&
                            it.panelId == System.identityHashCode(owner)
                    }
                ) {
                    val cef = owner.getCefBrowser()
                    val weakOwner = WeakReference(owner)
                    val panelId = System.identityHashCode(owner)
                    val cefId = System.identityHashCode(cef)
                    // Registration happens at the first resolvable container event,
                    // before native creation for the problematic first Preview.
                    // Do not block the CEF callback or mutate browser lifecycle.
                    val handler = object : CefLifeSpanHandlerAdapter() {
                        private fun recordNative(phase: String, actual: CefBrowser) {
                            appendPanelOwnerTrace(trace,
                                "timestamp_ms=" + System.currentTimeMillis() +
                                    "\tevent=" + phase +
                                    "\tpanel_identity=" + panelId +
                                    "\tcef_identity=" + cefId +
                                    "\tcallback_cef_identity=" + System.identityHashCode(actual) +
                                    "\tcallback_cef_same=" + (actual === cef) +
                                    "\tcef_native_id=" + actual.identifier +
                                    "\tjbcef_disposed_at_callback=" +
                                        (weakOwner.get()?.isDisposed?.toString() ?: "UNKNOWN") +
                                    "\tcef_closing=" + actual.isClosing +
                                    "\tcef_closed=" + actual.isClosed
                            )
                        }
                        override fun onAfterCreated(browser: CefBrowser) {
                            recordNative("cef-on-after-created", browser)
                        }
                        override fun onBeforeClose(browser: CefBrowser) {
                            recordNative("cef-on-before-close", browser)
                        }
                    }
                    owner.getJBCefClient().addLifeSpanHandler(handler, cef)
                    trace.observed.add(ObservedPanelOwner(
                        System.identityHashCode(parent),
                        System.identityHashCode(child),
                        panelId,
                        panelId,
                        cefId,
                        eventName,
                        cefNativeId(owner),
                        weakOwner,
                        handler,
                    ))
                    appendPanelOwnerTrace(trace,
                        "timestamp_ms=" + System.currentTimeMillis() +
                            "\tevent=owner-handler-registered" +
                            "\titeration=" + trace.iteration +
                            "\tpanel_identity=" + panelId +
                            "\tcef_identity=" + cefId +
                            "\tcef_native_id=" + cefNativeId(owner) +
                            "\tjbcef_disposed_at_registration=" + owner.isDisposed +
                            "\tcef_closing=" + cef.isClosing +
                            "\tcef_closed=" + cef.isClosed)
                    // IDEA 2026.2.3 detaches using remove(panel.component)
                    // immediately before Disposer.dispose(panel). This AWT
                    // callback is synchronous with the component mutation.
                    // Request immediate creation here without waiting on EDT.
                    // This does not promise native creation completion.
                    if (trace.iteration == 2 && trace.eagerNativeCreate &&
                        trace.eagerCreateAttempts == 0
                    ) {
                        trace.eagerCreateAttempts++
                        appendPanelOwnerTrace(trace,
                            "timestamp_ms=" + System.currentTimeMillis() +
                                "\tevent=eager-create-request" +
                                "\titeration=" + trace.iteration +
                                "\tfirst_observed_event=" + eventName +
                                "\tpanel_identity=" + panelId +
                                "\tcef_identity=" + cefId +
                                "\tjbcef_disposed_before_request=" + owner.isDisposed +
                                "\tcef_closing_before_request=" + cef.isClosing +
                                "\tcef_native_id_before_request=" + cefNativeId(owner))
                        val attempt = runCatching {
                            check(!owner.isDisposed && !cef.isClosing) {
                                "First observed bundled Preview is already disposing"
                            }
                            owner.createImmediately()
                        }
                        if (attempt.isSuccess) trace.eagerCreateReturns++
                        else trace.eagerCreateFailure =
                            attempt.exceptionOrNull()?.javaClass?.simpleName ?: "UNKNOWN"
                        appendPanelOwnerTrace(trace,
                            "timestamp_ms=" + System.currentTimeMillis() +
                                "\tevent=eager-create-return" +
                                "\titeration=" + trace.iteration +
                                "\tpanel_identity=" + panelId +
                                "\tresult=" + (if (attempt.isSuccess) "RETURNED" else "FAILED") +
                                "\tjbcef_disposed_after_request=" + owner.isDisposed +
                                "\tcef_native_id_after_request=" + cefNativeId(owner) +
                                "\terror=" + trace.eagerCreateFailure)
                    }
                    true
                } else {
                    false
                }
                "timestamp_ms=" + System.currentTimeMillis() +
                    "\tevent=" + eventName +
                    "\twrapper_identity=" + System.identityHashCode(parent) +
                    "\tcomponent_identity=" + System.identityHashCode(child) +
                    "\towner_resolved=" + (owner != null) +
                    "\tnew_owner_captured=" + newOwnerCaptured +
                    "\tpanel_identity=" + (owner?.let { System.identityHashCode(it) } ?: "NONE") +
                    "\tcef_identity=" + (owner?.let { System.identityHashCode(it.getCefBrowser()) } ?: "NONE") +
                    "\tcef_native_id=" + (owner?.let(::cefNativeId) ?: "NONE") +
                    "\tjbcef_disposed_at_container_event=" +
                        (owner?.isDisposed?.toString() ?: "UNKNOWN")
            }.getOrElse { error ->
                "timestamp_ms=" + System.currentTimeMillis() +
                    "\tevent=" + eventName +
                    "\twrapper_identity=" + System.identityHashCode(parent) +
                    "\tcomponent_identity=" + System.identityHashCode(child) +
                    "\tobservation_error=" + error.javaClass.name
            }
            appendPanelOwnerTrace(trace, line)
        }
        panelOwnerTrace = PanelOwnerTrace(path, filename, listener)
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.CONTAINER_EVENT_MASK)
    }

    /**
     * #353 opt-in counterfactual: keep iteration 001 as the unmodified control.
     * Only iteration 002 may request eager creation, and only for the first
     * real platform panel resolved on an AWT container event.
     */
    fun configureReferencePanelOwnerTraceIteration(iteration: Int, eagerNativeCreate: Boolean) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val trace = checkNotNull(panelOwnerTrace) { "Panel owner trace was not armed" }
        require(iteration in 1..2)
        require(!eagerNativeCreate || iteration == 2)
        trace.iteration = iteration
        trace.eagerNativeCreate = eagerNativeCreate
        appendPanelOwnerTrace(trace,
            "timestamp_ms=" + System.currentTimeMillis() +
                "\tevent=iteration-start" +
                "\titeration=" + iteration +
                "\teager_native_create=" + eagerNativeCreate)
    }

    fun snapshotReferencePanelOwnerTrace(phase: String): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val trace = checkNotNull(panelOwnerTrace) { "Panel owner trace was not armed" }
        require(phase == "after-detach" || phase == "after-file-close" || phase == "final")
        val lines = trace.observed.mapIndexed { index, owner ->
            val browser = owner.browser.get()
            listOf(
                "index=" + index,
                "wrapper_identity=" + owner.wrapperId,
                "component_identity=" + owner.componentId,
                "panel_identity=" + owner.panelId,
                "cef_identity=" + owner.cefId,
                "first_observed_event=" + owner.firstObservedEvent,
                "cef_native_id_at_capture=" + owner.cefNativeIdAtCapture,
                "browser_reachable=" + (browser != null),
                "jbcef_disposed=" + (browser?.isDisposed?.toString() ?: "UNKNOWN"),
                "cef_closing=" + (browser?.getCefBrowser()?.isClosing?.toString() ?: "UNKNOWN"),
                "cef_closed=" + (browser?.getCefBrowser()?.isClosed?.toString() ?: "UNKNOWN"),
                "cef_native_id=" + (browser?.let(::cefNativeId) ?: "UNKNOWN"),
            ).joinToString(",")
        }
        val evidence = "schema=markflow-preview-owner-trace/v1\n" +
            "phase=$phase\nobserved_panels=" + trace.observed.size + "\n" +
            "eager_create_attempts=" + trace.eagerCreateAttempts + "\n" +
            "eager_create_returns=" + trace.eagerCreateReturns + "\n" +
            "eager_create_failure=" + trace.eagerCreateFailure + "\n" +
            lines.joinToString("\n", postfix = "\n")
        appendPanelOwnerTrace(trace,
            "timestamp_ms=" + System.currentTimeMillis() +
                "\tevent=snapshot\tphase=" + phase +
                "\tobserved_panels=" + trace.observed.size +
                "\teager_create_attempts=" + trace.eagerCreateAttempts +
                "\teager_create_returns=" + trace.eagerCreateReturns + "\n" +
                lines.joinToString("\n"))
        return evidence
    }

    fun finishReferencePanelOwnerTrace(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val evidence = snapshotReferencePanelOwnerTrace("final")
        val trace = checkNotNull(panelOwnerTrace)
        Toolkit.getDefaultToolkit().removeAWTEventListener(trace.listener)
        // The two-open diagnostic must not retain additional CEF listeners.
        trace.observed.forEach { entry ->
            entry.browser.get()?.let { browser ->
                runCatching {
                    browser.getJBCefClient().removeLifeSpanHandler(
                        entry.lifeSpanHandler, browser.getCefBrowser())
                }.onFailure { error ->
                    appendPanelOwnerTrace(trace,
                        "timestamp_ms=" + System.currentTimeMillis() +
                            "\tevent=handler-removal-failed" +
                            "\tpanel_identity=" + entry.panelId +
                            "\terror_class=" + error.javaClass.name)
                }
            }
        }
        panelOwnerTrace = null
        return evidence
    }

    /**
     * Actual IDEA EditorImpl font-size probe for #350 Starter acceptance.
     * Exercises the editor's own font-size setter and restores its original
     * size; never edits the Markdown Document or JCEF Preview CSS reference.
     */
    fun setNativeEditorFontSize(editor: Editor, fontPx: Int): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(fontPx in 8..40)
        val ex = editor as? com.intellij.openapi.editor.impl.EditorImpl
            ?: error("Real native Heading editor is not EditorImpl")
        fontOriginals.getOrPut(editor) { ex.colorsScheme.editorFontSize2D }
        ex.setFontSize(fontPx.toFloat())
        return "requested_font_px=" + fontPx + "\n" +
            "actual_native_font_px=" + editor.colorsScheme.editorFontSize2D + "\n" +
            "actual_native_line_height_px=" + editor.lineHeight + "\n"
    }

    /** Restore only the source editor's previously owned editor scheme. */
    fun restoreNativeEditorFontSize(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val original = fontOriginals.remove(editor)
            ?: error("No prior font-size probe to restore")
        val ex = editor as? com.intellij.openapi.editor.impl.EditorImpl
            ?: error("Real native Heading editor is not EditorImpl")
        ex.setFontSize(original)
        return "restored_native_font_px=" + editor.colorsScheme.editorFontSize2D + "\n"
    }

    private fun editors(editor: Editor): Pair<TextEditorWithPreview, FileEditor> {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val project = requireNotNull(editor.project) { "Differential editor has no project" }
        val file = requireNotNull(FileDocumentManager.getInstance().getFile(editor.document)) {
            "Differential editor document has no VirtualFile"
        }
        val selected = FileEditorManager.getInstance(project).getEditors(file)
            .filterIsInstance<TextEditorWithPreview>()
            .singleOrNull {
                it.javaClass.name == MARKDOWN_SPLIT && it.textEditor.editor === editor
            }
            ?: error("Exact platform Markdown split editor unavailable for " + file.name)
        // myPreview is protected in TextEditorWithPreview, with no public preview accessor.
        // Reflection is deliberately confined to this test-only compatibility boundary.
        val field = TextEditorWithPreview::class.java.getDeclaredField("myPreview")
        check(field.trySetAccessible()) { "Platform preview field access changed" }
        val preview = field.get(selected) as? FileEditor
            ?: error("Bundled Markdown preview FileEditor unavailable")
        check(preview.javaClass.name == MARKDOWN_PREVIEW) {
            "Reference is not MarkdownPreviewFileEditor: " + preview.javaClass.name
        }
        check(preview.isValid) { "Platform Markdown preview is invalid" }
        return selected to preview
    }

    private fun previewPanel(preview: FileEditor): Any? {
        val field = preview.javaClass.getDeclaredField("panel")
        check(field.trySetAccessible()) { "Markdown preview panel field access changed" }
        return field.get(preview)
    }

    private fun previewDisposed(preview: FileEditor): Boolean {
        val field = preview.javaClass.getDeclaredField("isDisposed")
        check(field.trySetAccessible()) { "Markdown preview disposed field access changed" }
        return field.getBoolean(preview)
    }

    /**
     * Evidence-only source-to-rendered-row mapping for fully concealed native lists/tables.
     * Each row is selected by its parser-bounded source span, never by viewport Y alone.
     * No caret movement, fold expansion, or synthetic screenshot is performed here.
     */
    private data class NativeAnchorRow(
        val kind: String,
        val sourceRange: ProjectionRange,
        val rowIndex: Int,
        val rowCount: Int,
        val inlayBounds: Rectangle,
        val top: Int,
        val bottom: Int,
        val allowOversizeSlice: Boolean = false,
    )

    /** An unambiguous source row must be wholly contained in the requested physical line. */
    private fun sourceRowIndex(
        lineStart: Int, lineEnd: Int, rows: List<ProjectionRange>,
    ): Int? {
        val matches = rows.indices.filter { index ->
            val range = rows[index]
            range.endOffset > range.startOffset &&
                range.startOffset >= lineStart && range.endOffset <= lineEnd
        }
        return matches.singleOrNull()
    }

    private fun ProjectionRange.intersectsPhysicalLine(lineStart: Int, lineEnd: Int): Boolean {
        val effectiveEnd = if (lineEnd > lineStart) lineEnd else lineStart + 1
        return endOffset > lineStart && startOffset < effectiveEnd
    }

    private fun wholeTarget(
        kind: String,
        range: ProjectionRange,
        bounds: Rectangle,
        allowOversizeSlice: Boolean = true,
    ): NativeAnchorRow? {
        if (range.endOffset <= range.startOffset || bounds.width <= 0 || bounds.height <= 0) {
            return null
        }
        return NativeAnchorRow(
            kind = kind,
            sourceRange = range,
            rowIndex = 0,
            rowCount = 1,
            inlayBounds = bounds,
            top = bounds.y,
            bottom = bounds.y + bounds.height,
            allowOversizeSlice = allowOversizeSlice,
        )
    }

    /**
     * Full target visibility is mandatory whenever the painted target fits.
     * If the genuine target is taller than one viewport, accept only a centered
     * slice; this proves the screenshot belongs to that source target without
     * pretending the whole oversized artifact was reviewed in one image.
     */
    private fun sourceAnchorVisibilityMode(
        top: Int, bottom: Int, viewportTop: Int, viewportHeight: Int,
        allowOversizeSlice: Boolean,
    ): String? {
        if (viewportHeight <= 0 || bottom <= top) return null
        val viewportBottom = viewportTop.toLong() + viewportHeight
        val targetHeight = bottom.toLong() - top
        if (targetHeight <= viewportHeight) {
            return if (top >= viewportTop && bottom <= viewportBottom) "FULL_TARGET" else null
        }
        if (!allowOversizeSlice) return null
        val viewportCenter = viewportTop.toLong() + viewportHeight / 2L
        val intersects = top < viewportBottom && bottom > viewportTop
        return if (intersects && top <= viewportCenter && bottom >= viewportCenter) {
            "OVERSIZE_CENTERED_SLICE"
        } else {
            null
        }
    }

    /** Geometry alone is never sufficient: both ownership and current source are required. */
    private fun inlayRowFullyVisible(
        top: Int, bottom: Int, bounds: Rectangle, viewport: Rectangle,
        sourceCurrent: Boolean, inlayValid: Boolean,
    ): Boolean = sourceCurrent && inlayValid &&
        bounds.width > 0 && bounds.height > 0 &&
        viewport.width > 0 && viewport.height > 0 &&
        bounds.x < viewport.x.toLong() + viewport.width &&
        bounds.x.toLong() + bounds.width > viewport.x &&
        top >= bounds.y && bottom <= bounds.y.toLong() + bounds.height &&
        sourceAnchorFullyVisible(top, bottom, viewport.y, viewport.height)

    /**
     * Resolve the narrowest current MarkFlow presentation that actually owns the
     * requested source line. Lists/tables retain row-level identity; other rich
     * owners use their exact parser/planner source range. Multiple equal-span
     * owners are ambiguous and fail closed.
     */
    private fun nativeSourceBoundInlayRow(editor: Editor, line: Int): NativeAnchorRow? {
        val controller = NativeMarkFlowProductionLifecycle.controller(editor) ?: return null
        val plan = controller.currentPlan ?: return null
        if (plan.status != ProjectionPlanStatus.READY ||
            ProjectionSnapshot.capture(editor.document, plan.identity.configGeneration).identity != plan.identity
        ) return null

        val document = editor.document
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val blockInlays = editor.inlayModel.getBlockElementsInRange(0, document.textLength)
            .filter { it.isValid && it.bounds != null }
        val inlineInlays = editor.inlayModel.getInlineElementsInRange(0, document.textLength)
            .filter { it.isValid && it.bounds != null }
        val allInlays = blockInlays + inlineInlays
        val candidates = mutableListOf<NativeAnchorRow>()

        NativeListProjectionPlanner.plan(plan).forEach { model ->
            val rowRanges = model.rows.map { row ->
                ProjectionRange(row.markerRange.startOffset, row.contentRange.endOffset)
            }
            val rowIndex = sourceRowIndex(lineStart, lineEnd, rowRanges) ?: return@forEach
            val inlay = blockInlays.singleOrNull { candidate ->
                candidate.offset == model.sourceRange.endOffset &&
                    (candidate.renderer as? NativeListInlayRenderer)?.sourceRange == model.sourceRange
            } ?: return@forEach
            val renderer = inlay.renderer as NativeListInlayRenderer
            if (renderer.row(rowIndex).markerRange != model.rows[rowIndex].markerRange ||
                renderer.row(rowIndex).contentRange != model.rows[rowIndex].contentRange
            ) return@forEach
            val bounds = inlay.bounds ?: return@forEach
            if (bounds.width <= 0 || bounds.height <= 0) return@forEach
            val ys = (bounds.y until bounds.y + bounds.height).filter { y ->
                renderer.rowAt(java.awt.Point(bounds.x, y), bounds) == rowIndex
            }
            if (ys.isEmpty()) return@forEach
            candidates += NativeAnchorRow(
                "list_inlay_row", rowRanges[rowIndex], rowIndex, model.rows.size,
                bounds, ys.first(), ys.last() + 1,
            )
        }

        NativeTableProjectionPlanner.plan(plan).forEach { model ->
            val rowIndex = sourceRowIndex(lineStart, lineEnd, model.rows.map { it.sourceRange })
                ?: return@forEach
            val inlay = blockInlays.singleOrNull { candidate ->
                candidate.offset == model.sourceRange.endOffset &&
                    (candidate.renderer as? NativeTableInlayRenderer)?.sourceRange == model.sourceRange
            } ?: return@forEach
            val bounds = inlay.bounds ?: return@forEach
            if (bounds.width <= 0 || bounds.height <= 0 || model.rows.isEmpty() ||
                bounds.height % model.rows.size != 0
            ) return@forEach
            val height = bounds.height / model.rows.size
            if (height <= 0) return@forEach
            candidates += NativeAnchorRow(
                "table_inlay_row", model.rows[rowIndex].sourceRange, rowIndex, model.rows.size,
                bounds, bounds.y + height * rowIndex, bounds.y + height * (rowIndex + 1),
            )
        }

        blockInlays.forEach { inlay ->
            val renderer = inlay.renderer
            val owned = when (renderer) {
                is NativeHeadingInlayRenderer -> "heading_inlay" to renderer.sourceRange
                is NativeBlockQuoteInlayRenderer -> "blockquote_inlay" to renderer.sourceRange
                is NativeFencedCodeInlayRenderer -> "fenced_code_inlay" to renderer.sourceRange
                is NativeIndentedCodeInlayRenderer -> "indented_code_inlay" to renderer.sourceRange
                else -> null
            } ?: return@forEach
            val range = owned.second
            if (!range.intersectsPhysicalLine(lineStart, lineEnd)) return@forEach
            wholeTarget(owned.first, range, inlay.bounds ?: return@forEach)?.let(candidates::add)
        }

        editor.foldingModel.allFoldRegions
            .filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
            .filter { it.isValid && !it.isExpanded }
            .forEach { fold ->
                val renderer = fold.renderer as? NativeHeadingCustomFoldRenderer ?: return@forEach
                val range = renderer.sourceRange
                if (!range.intersectsPhysicalLine(lineStart, lineEnd)) return@forEach
                val origin = editor.offsetToXY(fold.startOffset)
                wholeTarget(
                    "heading_custom_fold", range,
                    Rectangle(origin.x, origin.y, fold.widthInPixels, fold.heightInPixels),
                )?.let(candidates::add)
            }

        inlineInlays.forEach { inlay ->
            val renderer = inlay.renderer as? NativeHeadingInlineFoldRenderer ?: return@forEach
            val range = renderer.sourceRange
            if (!range.intersectsPhysicalLine(lineStart, lineEnd)) return@forEach
            wholeTarget("heading_inline_fold", range, inlay.bounds ?: return@forEach)
                ?.let(candidates::add)
        }

        allInlays.forEach { inlay ->
            val renderer = inlay.renderer
            if (!renderer.javaClass.name.endsWith(".NativeRasterInlayRenderer")) return@forEach
            val field = renderer.javaClass.getDeclaredField("projection")
            check(field.trySetAccessible()) { "Cannot inspect exact derived source projection" }
            val projection = field.get(renderer) as? NativeDerivedProjection
                ?: error("Unexpected native derived projection type")
            val range = projection.sourceRange
            if (!range.intersectsPhysicalLine(lineStart, lineEnd)) return@forEach
            wholeTarget(
                "derived_" + projection.kind.name.lowercase(),
                range, inlay.bounds ?: return@forEach,
            )?.let(candidates::add)
        }

        NativeHostResourceProjectionPlanner.plan(plan)
            .filter { it.kind == NativeHostResourceKind.LOCAL_IMAGE }
            .filter { it.sourceRange.intersectsPhysicalLine(lineStart, lineEnd) }
            .forEach { projection ->
                val inlay = allInlays.singleOrNull {
                    it.offset == projection.sourceRange.endOffset &&
                        it.renderer.javaClass.name.endsWith(".NativeLocalImageInlayRenderer")
                } ?: return@forEach
                wholeTarget(
                    "local_image_inlay", projection.sourceRange,
                    inlay.bounds ?: return@forEach,
                )?.let(candidates::add)
            }

        NativeRawHtmlProjectionPlanner.plan(plan)
            .filter { it.sourceRange.intersectsPhysicalLine(lineStart, lineEnd) }
            .forEach { projection ->
                val inlay = allInlays.singleOrNull {
                    it.offset == projection.sourceRange.endOffset &&
                        it.renderer.javaClass.name.endsWith(".NativeRawHtmlRasterInlayRenderer")
                } ?: return@forEach
                wholeTarget(
                    "raw_html_inlay", projection.sourceRange,
                    inlay.bounds ?: return@forEach,
                )?.let(candidates::add)
            }

        plan.projections
            .filter { it.kind == NativeProjectionKind.THEMATIC_BREAK }
            .filter { it.sourceRange.intersectsPhysicalLine(lineStart, lineEnd) }
            .forEach { projection ->
                val fold = editor.foldingModel.allFoldRegions.singleOrNull {
                    it.isValid && !it.isExpanded &&
                        it.startOffset == projection.sourceRange.startOffset &&
                        it.endOffset == projection.sourceRange.endOffset
                } ?: return@forEach
                val point = editor.offsetToXY(fold.startOffset)
                wholeTarget(
                    "thematic_break_fold", projection.sourceRange,
                    Rectangle(point.x, point.y, 1, editor.lineHeight),
                    allowOversizeSlice = false,
                )?.let(candidates::add)
            }

        if (candidates.isEmpty()) return null
        val ordered = candidates.sortedWith(
            compareBy<NativeAnchorRow> { it.sourceRange.endOffset - it.sourceRange.startOffset }
                .thenBy { it.sourceRange.startOffset }
                .thenBy { it.kind }
        )
        val first = ordered.first()
        val firstSpan = first.sourceRange.endOffset - first.sourceRange.startOffset
        if (ordered.drop(1).any {
                it.sourceRange.endOffset - it.sourceRange.startOffset == firstSpan
            }
        ) return null
        return first
    }

    /**
     * Request source-line navigation without moving the caret into a folded
     * construct. For a rich list/table, center its actual source-bound painted
     * row rather than the invisible logical source line.
     */
    fun showNativeAtSourceLine(editor: Editor, line: Int): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(line in 0 until editor.document.lineCount) { "Invalid native source anchor" }
        val row = nativeSourceBoundInlayRow(editor, line)
        if (row != null) {
            val viewport = editor.scrollingModel.visibleArea
            editor.scrollingModel.scrollVertically(
                (row.top - (viewport.height - (row.bottom - row.top)) / 2).coerceAtLeast(0)
            )
            return "line=" + line + ";kind=" + row.kind + ";row=" + row.rowIndex
        }
        val offset = editor.document.getLineStartOffset(line)
        editor.scrollingModel.scrollTo(
            editor.offsetToLogicalPosition(offset),
            com.intellij.openapi.editor.ScrollType.CENTER,
        )
        return "line=" + line + ";offset=" + offset
    }

    /** A requested source position must have the complete represented row visible. */
    private fun sourceAnchorFullyVisible(
        top: Int, bottom: Int, viewportTop: Int, viewportHeight: Int,
    ): Boolean = sourceAnchorVisibilityMode(
        top, bottom, viewportTop, viewportHeight, allowOversizeSlice = false
    ) == "FULL_TARGET"

    /** Positive, missing/wrong/ambiguous row, stale owner, cropped and oversize controls. */
    fun sourceAnchorViewportNegativeControls(): Boolean {
        val rows = listOf(ProjectionRange(10, 20), ProjectionRange(30, 40))
        val bounds = Rectangle(0, 150, 500, 200)
        val viewport = Rectangle(0, 100, 600, 400)
        return sourceAnchorFullyVisible(200, 220, 100, 400) &&
            !sourceAnchorFullyVisible(80, 110, 100, 400) &&
            !sourceAnchorFullyVisible(490, 510, 100, 400) &&
            !sourceAnchorFullyVisible(200, 220, 100, 0) &&
            sourceAnchorVisibilityMode(50, 550, 100, 400, true) ==
                "OVERSIZE_CENTERED_SLICE" &&
            sourceAnchorVisibilityMode(50, 550, 100, 400, false) == null &&
            sourceAnchorVisibilityMode(-450, 50, 100, 400, true) == null &&
            sourceRowIndex(30, 40, rows) == 1 &&
            sourceRowIndex(50, 60, rows) == null &&
            sourceRowIndex(10, 20, listOf(rows[0], rows[0])) == null &&
            inlayRowFullyVisible(200, 220, bounds, viewport, true, true) &&
            !inlayRowFullyVisible(200, 220, bounds, viewport, false, true) &&
            !inlayRowFullyVisible(200, 220, bounds, viewport, true, false) &&
            !inlayRowFullyVisible(120, 220, bounds, viewport, true, true) &&
            !inlayRowFullyVisible(340, 360, bounds, viewport, true, true) &&
            !inlayRowFullyVisible(490, 510, Rectangle(0, 150, 500, 400), viewport, true, true) &&
            !inlayRowFullyVisible(200, 220, Rectangle(800, 150, 100, 200), viewport, true, true)
    }

    /**
     * Find an actually exposed source character for ordinary text and autolinks.
     * Rich native list/table inlays use the separate strict source-bound row path.
     */
    private fun sourceAnchorProbeOffset(editor: Editor, line: Int): Int? {
        val document = editor.document
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        if (lineEnd <= lineStart) {
            return lineStart.takeIf {
                editor.foldingModel.getCollapsedRegionAtOffset(it) == null
            }
        }
        var candidate = lineStart
        while (candidate < lineEnd) {
            val collapsed = editor.foldingModel.getCollapsedRegionAtOffset(candidate)
                ?: return candidate
            candidate = maxOf(candidate + 1, collapsed.endOffset)
        }
        return null
    }

    fun nativeSourceAnchorViewport(editor: Editor, line: Int): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(line in 0 until editor.document.lineCount) { "Invalid native source anchor" }
        val lineStart = editor.document.getLineStartOffset(line)
        val lineEnd = editor.document.getLineEndOffset(line)
        val probeOffset = sourceAnchorProbeOffset(editor, line)
        val richRow = nativeSourceBoundInlayRow(editor, line)
        val viewport = editor.scrollingModel.visibleArea
        val geometryOffset = probeOffset ?: lineStart
        val visual = editor.offsetToVisualPosition(geometryOffset)
        val point = editor.visualPositionToXY(visual)
        val top = richRow?.top ?: point.y
        val bottom = richRow?.bottom ?: (point.y + editor.lineHeight)
        val kind = richRow?.kind ?: "literal_source"
        val sourceCurrent = richRow != null
        val visibilityMode = if (richRow != null) {
            val geometryCurrent = sourceCurrent &&
                richRow.inlayBounds.width > 0 && richRow.inlayBounds.height > 0 &&
                viewport.width > 0 && viewport.height > 0 &&
                richRow.inlayBounds.x < viewport.x.toLong() + viewport.width &&
                richRow.inlayBounds.x.toLong() + richRow.inlayBounds.width > viewport.x
            if (!geometryCurrent) {
                null
            } else if (richRow.allowOversizeSlice) {
                sourceAnchorVisibilityMode(
                    top, bottom, viewport.y, viewport.height, allowOversizeSlice = true
                )
            } else if (inlayRowFullyVisible(
                    top, bottom, richRow.inlayBounds, viewport, sourceCurrent, true,
                )
            ) {
                "FULL_TARGET"
            } else {
                null
            }
        } else if (probeOffset != null &&
            sourceAnchorFullyVisible(top, bottom, viewport.y, viewport.height)
        ) {
            "FULL_SOURCE_ROW"
        } else {
            null
        }
        val visible = visibilityMode != null
        return listOf(
            "schema=markflow-native-source-anchor-viewport/v1",
            "source_line_zero_based=" + line,
            "source_offset=" + lineStart,
            "source_line_end_offset=" + lineEnd,
            "source_probe_offset=" + (probeOffset ?: -1),
            "line_start_collapsed=" +
                (editor.foldingModel.getCollapsedRegionAtOffset(lineStart) != null),
            "anchor_kind=" + kind,
            "anchor_source_range=" + (richRow?.sourceRange?.let {
                it.startOffset.toString() + "-" + it.endOffset
            } ?: "none"),
            "anchor_row_index=" + (richRow?.rowIndex ?: -1),
            "anchor_row_count=" + (richRow?.rowCount ?: 0),
            "anchor_inlay_bounds=" + (richRow?.inlayBounds?.let {
                it.x.toString() + "," + it.y + "," + it.width + "," + it.height
            } ?: "none"),
            "anchor_visibility_mode=" + (visibilityMode ?: "NOT_VISIBLE"),
            "anchor_y_document_px=" + top,
            "anchor_bottom_document_px=" + bottom,
            "anchor_visual_line=" + visual.line,
            "viewport_y_document_px=" + viewport.y,
            "viewport_height_px=" + viewport.height,
            "document_modification_stamp=" + editor.document.modificationStamp,
            "anchor_visible=" + visible,
        ).joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * Stage D: read-only native raster dimensions from the actual installed inlays.
     * Private renderer image access is limited to this test-only bridge and fails
     * closed if its runtime contract changes. The viewport does not imply a clipped
     * artifact: a tall inlay may remain reachable by scrolling.
     */
    fun nativeRasterGeometry(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val region = editor.scrollingModel.visibleArea
        val rasterInlays = editor.inlayModel
            .getBlockElementsInRange(0, editor.document.textLength)
            .filter { it.renderer.javaClass.name.endsWith(".NativeRasterInlayRenderer") }
        val records = rasterInlays.mapIndexed { index, inlay ->
            val renderer = inlay.renderer
            val field = renderer.javaClass.getDeclaredField("image")
            check(field.trySetAccessible()) { "Stage D native raster image field changed" }
            val image = field.get(renderer) as? java.awt.image.BufferedImage
                ?: error("Stage D inlay does not contain a decoded BufferedImage")
            val displayedWidth = inlay.widthInPixels
            val displayedHeight = inlay.heightInPixels
            val intrinsicWidth = image.width
            val intrinsicHeight = image.height
            check(rasterAspectPreserved(intrinsicWidth, intrinsicHeight, displayedWidth, displayedHeight)) {
                "Stage D detected nonuniform native raster scaling or collapsed dimensions"
            }
            val bounds = requireNotNull(inlay.bounds) {
                "Stage D decoded raster lacks a real inlay bounds rectangle"
            }
            val line = editor.document.getLineNumber(inlay.offset.coerceAtMost(editor.document.textLength))
            listOf(
                "index=" + index,
                "source_line_zero_based=" + line,
                "source_offset=" + inlay.offset,
                "intrinsic_width=" + intrinsicWidth,
                "intrinsic_height=" + intrinsicHeight,
                "displayed_width=" + displayedWidth,
                "displayed_height=" + displayedHeight,
                "bounds_x=" + bounds.x,
                "bounds_y=" + bounds.y,
                "bounds_width=" + bounds.width,
                "bounds_height=" + bounds.height,
            ).joinToString(separator = "\t")
        }
        val header = listOf(
            "schema=markflow-native-raster-geometry/v1",
            "visible_x=" + region.x,
            "visible_y=" + region.y,
            "visible_width=" + region.width,
            "visible_height=" + region.height,
            "raster_count=" + records.size,
        ).joinToString(separator = "\n")
        return header + "\n" + records.joinToString(separator = "\n", postfix = "\n")
    }


    /**
     * E18: move the *actual* native editor scrollbar to each edge of an installed
     * raster inlay. A viewport screenshot alone cannot prove whether a tall
     * Mermaid diagram is cropped or reachable by scrolling.
     *
     * The inlay bounds and visibleArea share editor document coordinates; do
     * not infer reachability from the raster's decoded height or reference DOM.
     * This bridge never changes source, selection, inlay ownership or size mode.
     */
    fun nativeRasterScrollProbe(editor: Editor, index: Int, edge: String): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(edge == "TOP" || edge == "BOTTOM") { "Unknown raster scroll edge" }
        val inlays = editor.inlayModel
            .getBlockElementsInRange(0, editor.document.textLength)
            .filter { it.renderer.javaClass.name.endsWith(".NativeRasterInlayRenderer") }
        require(index in inlays.indices) { "No native raster at index " + index }
        val inlay = inlays[index]
        val bounds = requireNotNull(inlay.bounds) { "E18 installed raster bounds unavailable" }
        check(bounds.height > 0 && bounds.width > 0 && inlay.isValid) {
            "E18 native raster is invalid or collapsed"
        }
        val before = editor.scrollingModel.visibleArea
        check(before.height > 0 && before.width > 0) { "E18 native viewport is unavailable" }
        val target = if (edge == "TOP") bounds.y.toLong()
            else bounds.y.toLong() + bounds.height - before.height
        editor.scrollingModel.scrollVertically(target.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        val after = editor.scrollingModel.visibleArea
        val edgeVisible = rasterEdgeIsVisible(
            bounds.y, bounds.height, after.y, after.height, edge
        )
        val line = editor.document.getLineNumber(
            inlay.offset.coerceAtMost(editor.document.textLength)
        )
        return listOf(
            "schema=markflow-native-raster-scroll-reachability/v1",
            "geometry_scope=REAL_NATIVE_EDITOR_SCROLL_VIEWPORT",
            "raster_index=" + index,
            "source_line_zero_based=" + line,
            "source_offset=" + inlay.offset,
            "edge=" + edge,
            "edge_visible=" + edgeVisible,
            "bounds_y=" + bounds.y,
            "bounds_height=" + bounds.height,
            "viewport_y_before=" + before.y,
            "viewport_y_after=" + after.y,
            "viewport_height=" + after.height,
            "requested_scroll_y=" + target,
        ).joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * #352: prove a wide native Mermaid raster is reachable in the real editor,
     * not merely present outside the first screenshot. This mirrors E18's
     * vertical probe but operates on the installed inlay's horizontal bounds.
     */
    fun nativeRasterHorizontalScrollProbe(editor: Editor, index: Int, edge: String): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(edge == "LEFT" || edge == "RIGHT") { "Unknown raster horizontal scroll edge" }
        val inlays = editor.inlayModel
            .getBlockElementsInRange(0, editor.document.textLength)
            .filter { it.renderer.javaClass.name.endsWith(".NativeRasterInlayRenderer") }
        require(index in inlays.indices) { "No native raster at index " + index }
        val inlay = inlays[index]
        val bounds = requireNotNull(inlay.bounds) { "#352 installed raster bounds unavailable" }
        check(bounds.height > 0 && bounds.width > 0 && inlay.isValid) {
            "#352 native raster is invalid or collapsed"
        }
        val before = editor.scrollingModel.visibleArea
        check(before.height > 0 && before.width > 0) { "#352 native viewport is unavailable" }
        val target = if (edge == "LEFT") bounds.x.toLong()
            else bounds.x.toLong() + bounds.width - before.width
        editor.scrollingModel.scrollHorizontally(target.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        val after = editor.scrollingModel.visibleArea
        val edgeVisible = rasterHorizontalEdgeIsVisible(
            bounds.x, bounds.width, after.x, after.width, edge
        )
        val line = editor.document.getLineNumber(
            inlay.offset.coerceAtMost(editor.document.textLength)
        )
        return listOf(
            "schema=markflow-native-raster-horizontal-scroll-reachability/v1",
            "geometry_scope=REAL_NATIVE_EDITOR_SCROLL_VIEWPORT",
            "raster_index=" + index,
            "source_line_zero_based=" + line,
            "source_offset=" + inlay.offset,
            "edge=" + edge,
            "edge_visible=" + edgeVisible,
            "bounds_x=" + bounds.x,
            "bounds_width=" + bounds.width,
            "viewport_x_before=" + before.x,
            "viewport_x_after=" + after.x,
            "viewport_width=" + after.width,
            "requested_scroll_x=" + target,
        ).joinToString(separator = "\n", postfix = "\n")
    }

    fun nativeVerticalScrollOffset(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.scrollingModel.verticalScrollOffset
    }

    fun nativeHorizontalScrollOffset(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.scrollingModel.horizontalScrollOffset
    }

    fun restoreNativeVerticalScroll(editor: Editor, offset: Int) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(offset >= 0)
        editor.scrollingModel.scrollVertically(offset)
    }

    fun restoreNativeHorizontalScroll(editor: Editor, offset: Int) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(offset >= 0)
        editor.scrollingModel.scrollHorizontally(offset)
    }

    /** Pure geometry controls: inclusive last painted raster pixel, exclusive viewport bottom. */
    private fun rasterEdgeIsVisible(
        rasterTop: Int, rasterHeight: Int, viewportTop: Int, viewportHeight: Int, edge: String
    ): Boolean {
        require(edge == "TOP" || edge == "BOTTOM")
        if (rasterHeight <= 0 || viewportHeight <= 0) return false
        val pixel = if (edge == "TOP") rasterTop.toLong()
            else rasterTop.toLong() + rasterHeight - 1L
        return pixel >= viewportTop.toLong() &&
            pixel < viewportTop.toLong() + viewportHeight.toLong()
    }

    private fun rasterHorizontalEdgeIsVisible(
        rasterLeft: Int, rasterWidth: Int, viewportLeft: Int, viewportWidth: Int, edge: String
    ): Boolean {
        require(edge == "LEFT" || edge == "RIGHT")
        if (rasterWidth <= 0 || viewportWidth <= 0) return false
        val pixel = if (edge == "LEFT") rasterLeft.toLong()
            else rasterLeft.toLong() + rasterWidth - 1L
        return pixel >= viewportLeft.toLong() &&
            pixel < viewportLeft.toLong() + viewportWidth.toLong()
    }

    fun rasterScrollNegativeControls(): Boolean =
        rasterEdgeIsVisible(40, 300, 40, 100, "TOP") &&
            !rasterEdgeIsVisible(40, 300, 40, 100, "BOTTOM") &&
            rasterEdgeIsVisible(40, 300, 240, 100, "BOTTOM") &&
            !rasterEdgeIsVisible(40, 300, 240, 100, "TOP") &&
            !rasterEdgeIsVisible(40, 300, 0, 100, "BOTTOM") &&
            !rasterEdgeIsVisible(40, 0, 40, 100, "TOP") &&
            !rasterEdgeIsVisible(40, 300, 240, 0, "BOTTOM") &&
            rasterHorizontalEdgeIsVisible(40, 300, 40, 100, "LEFT") &&
            !rasterHorizontalEdgeIsVisible(40, 300, 40, 100, "RIGHT") &&
            rasterHorizontalEdgeIsVisible(40, 300, 240, 100, "RIGHT") &&
            !rasterHorizontalEdgeIsVisible(40, 300, 240, 100, "LEFT") &&
            !rasterHorizontalEdgeIsVisible(40, 0, 40, 100, "LEFT")

    private fun rasterAspectPreserved(iw: Int, ih: Int, dw: Int, dh: Int): Boolean {
        if (iw <= 0 || ih <= 0 || dw <= 0 || dh <= 0) return false
        if ((iw >= 16 && dw < 2) || (ih >= 16 && dh < 2)) return false
        // Integer-pixel rounding at each axis can contribute at most ~1 pixel.
        return kotlin.math.abs(dw.toLong() * ih - dh.toLong() * iw) <= iw.toLong() + ih
    }

    fun rasterGeometryNegativeControls(): Boolean =
        rasterAspectPreserved(320, 180, 640, 360) &&
            rasterAspectPreserved(333, 201, 666, 403) &&
            !rasterAspectPreserved(320, 180, 640, 240) &&
            !rasterAspectPreserved(320, 180, 0, 360) &&
            !rasterAspectPreserved(320, 180, 1, 1)

    /**
     * #350/#353 source-line geometry ledger, measured from installed production
     * NativeHeadingInlayRenderer instances (never from estimated CSS or screenshots).
     * These native-only measurements are NOT platform-preview parity evidence.
     * No source mutation, renderer substitution, or tolerance-based acceptance.
     */
    fun nativeHeadingSourceGeometry(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val document = editor.document
        val heading = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
        fun lineText(line: Int): String = document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(line), document.getLineEndOffset(line),
            )
        )
        fun lineY(line: Int): Int {
            val offset = document.getLineStartOffset(line)
            return editor.logicalPositionToXY(editor.offsetToLogicalPosition(offset)).y
        }
        val baseHeight = editor.lineHeight
        check(baseHeight > 0) { "Native editor line height is unavailable" }
        val inlays = editor.inlayModel.getBlockElementsInRange(0, document.textLength)
            .filter { it.renderer is NativeHeadingInlayRenderer }
            .sortedBy { (it.renderer as NativeHeadingInlayRenderer).sourceRange.startOffset }
        // Stable first installed ATX inlay is the cumulative reference point.
        // Avoid mutable origin state inside mapNotNull: Qodana correctly rejects
        // constant-condition checks that appear to initialize on each iteration.
        val firstHeadingLine = inlays.asSequence().mapNotNull { inlay ->
            val sourceLine = document.getLineNumber(
                (inlay.renderer as NativeHeadingInlayRenderer).sourceRange.startOffset
            )
            sourceLine.takeIf { heading.containsMatchIn(lineText(it)) }
        }.firstOrNull()
        val firstHeadingY = firstHeadingLine?.let(::lineY)
        val cumulativeNativeExcess = mutableListOf<Long>()
        val rows = inlays.mapNotNull { inlay ->
            val renderer = inlay.renderer as NativeHeadingInlayRenderer
            val sourceLine = document.getLineNumber(renderer.sourceRange.startOffset)
            val match = heading.find(lineText(sourceLine))?.takeIf { it.range.first == 0 }
                ?: return@mapNotNull null // Setext is handled in a later corpus slice.
            val endLine = document.getLineNumber(renderer.sourceRange.endOffset)
            val nextContentLine = (endLine + 1 until document.lineCount)
                .firstOrNull { lineText(it).isNotBlank() }
                ?: error("No following content checkpoint for ATX source line " + sourceLine)
            val startY = lineY(sourceLine)
            val nextY = lineY(nextContentLine)
            check(nextY > startY) { "Non-monotone installed native heading source anchors" }
            val logicalSpan = nextContentLine - sourceLine
            val expectedPlainPx = logicalSpan.toLong() * baseHeight
            val measuredPx = (nextY - startY).toLong()
            val excessPx = sourceSpanExcess(logicalSpan, nextY - startY, baseHeight)
            val cumulativePx = sourceSpanExcess(
                nextContentLine - requireNotNull(firstHeadingLine),
                nextY - requireNotNull(firstHeadingY), baseHeight,
            )
            cumulativeNativeExcess += cumulativePx
            val bounds = inlay.bounds
            listOf(
                "level=" + match.groupValues[1].length,
                "source_line_zero_based=" + sourceLine,
                "source_offset=" + renderer.sourceRange.startOffset,
                "following_line_zero_based=" + nextContentLine,
                "following_offset=" + document.getLineStartOffset(nextContentLine),
                "heading_y_document_px=" + startY,
                "following_y_document_px=" + nextY,
                "source_span_lines=" + logicalSpan,
                "plain_line_height_px=" + baseHeight,
                "plain_span_px=" + expectedPlainPx,
                "measured_span_px=" + measuredPx,
                "native_extra_px=" + excessPx,
                "cumulative_native_extra_px=" + cumulativePx,
                "inlay_height_px=" + inlay.heightInPixels,
                "inlay_bounds_y=" + (bounds?.y?.toString() ?: "UNAVAILABLE"),
                "inlay_bounds_height=" + (bounds?.height?.toString() ?: "UNAVAILABLE"),
            ).joinToString(separator = "\t")
        }
        val atxSourceCount = (0 until document.lineCount)
            .count { heading.find(lineText(it))?.range?.first == 0 }
        // Raw prefix count is diagnostic only: ATX-looking lines inside fenced
        // code are not real Markdown headings. The selected H1–H6 fixture
        // applies an exact expected installed-inlay count in the Starter test.
        return listOf(
            "schema=markflow-native-heading-source-geometry/v1",
            "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY",
            "base_line_height_px=" + baseHeight,
            "raw_atx_prefixed_line_count=" + atxSourceCount,
            "measured_atx_heading_count=" + rows.size,
            "worst_abs_cumulative_native_extra_px=" +
                (cumulativeNativeExcess.maxOfOrNull { kotlin.math.abs(it) } ?: 0L),
        ).joinToString(separator = "\n", postfix = "\n") +
            rows.joinToString(separator = "\n", postfix = "\n")
    }


    /**
     * #353 Stage E9: retain an *observational* six-heading ledger even when
     * IDEA rejects a CustomFoldRegion due to platform-owned folding geometry.
     * Never remove/expand another plugin's fold to manufacture success.
     *
     * A missing fold is measured as its actual source-line fallback, not as a
     * fabricated CustomFold rectangle. The Starter caller classifies it as FAIL.
     */
    @Suppress("UnstableApiUsage")
    fun nativeHeadingReplacementGeometry(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val doc = editor.document
        fun lineText(line: Int) = doc.getText(
            com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(line), doc.getLineEndOffset(line),
            ),
        )
        val folds = editor.foldingModel.allFoldRegions.filter { it.isValid }
        val custom = folds.filterIsInstance<com.intellij.openapi.editor.CustomFoldRegion>()
            .filter { !it.isExpanded && it.renderer is NativeHeadingCustomFoldRenderer }
            .sortedBy { it.startOffset }
        val foreign = folds.filterNot {
            it is com.intellij.openapi.editor.CustomFoldRegion &&
                it.renderer is NativeHeadingCustomFoldRenderer
        }
        val additiveInlays = editor.inlayModel.getBlockElementsInRange(0, doc.textLength)
            .count { it.renderer is NativeHeadingInlayRenderer }
        val coStartInlines = editor.inlayModel.getInlineElementsInRange(0, doc.textLength)
            .filter { it.isValid && it.renderer is NativeHeadingInlineFoldRenderer }
        val headingPattern = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
        val rows = (0 until 6).map { index ->
            val line = index * 4
            val nextLine = line + 2
            check(nextLine < doc.lineCount &&
                lineText(nextLine) == "Paragraph after H" + (index + 1) + ".") {
                "Stage E9 six-heading fixture source mismatch"
            }
            val match = headingPattern.find(lineText(line))
            check(match != null && match.range.first == 0 &&
                match.groupValues[1].length == index + 1) {
                "Stage E9 ATX H1–H6 fixture identity mismatch"
            }
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            val headingY = editor.logicalPositionToXY(
                editor.offsetToLogicalPosition(start)
            ).y
            val afterOffset = doc.getLineStartOffset(nextLine)
            val followingY = editor.logicalPositionToXY(
                editor.offsetToLogicalPosition(afterOffset)
            ).y
            val installed = custom.singleOrNull {
                (it.renderer as NativeHeadingCustomFoldRenderer).sourceRange.startOffset == start
            }
            val inlinePilot = coStartInlines.singleOrNull {
                (it.renderer as NativeHeadingInlineFoldRenderer).sourceRange.startOffset == start
            }
            val pilotSourceFold = if (inlinePilot == null) null else folds.singleOrNull {
                it !is com.intellij.openapi.editor.CustomFoldRegion &&
                    it.isValid && !it.isExpanded &&
                    it.startOffset == start && it.endOffset == end
            }
            val rendered = installed?.renderer as? NativeHeadingCustomFoldRenderer
            if (rendered != null) {
                check(rendered.sourceRange.endOffset == end) {
                    "Installed CustomFold source end does not match expected H" + (index + 1)
                }
            }
            val overlap = foreign.filter { it.startOffset < end && it.endOffset > start }
            val foreignRecord = overlap.joinToString(",") {
                it.javaClass.simpleName + ":" + it.startOffset + "-" + it.endOffset +
                    ":expanded=" + it.isExpanded
            }
            val origin = installed?.let { editor.offsetToXY(it.startOffset) }
            // Same declared native font as the opt-in heading renderer.
            // Records paint inputs, NOT inferred ink boundaries.
            val font = NativeHeadingPreviewTypographyPilot.font(editor, index + 1)
            val text = lineText(line).trimStart('#', ' ', '\t')
            val fm = editor.contentComponent.getFontMetrics(font)
            // Match the real B-pilot drawString baseline inputs for the
            // installed CustomFold (H2-H6) or Inline (H1) renderer.
            // Paint input is NOT an independently observed raster baseline.
            val paintBaseline = if (installed != null) {
                val pad = when (index + 1) {
                    1 -> 10
                    2 -> 8
                    3 -> 7
                    4 -> 6
                    5 -> 5
                    else -> 4
                }
                requireNotNull(origin).y + maxOf(
                    fm.ascent + pad,
                    (installed.heightInPixels + fm.ascent - fm.descent) / 2,
                )
            } else {
                val inline = requireNotNull(inlinePilot) {
                    "E19 H1 pilot is not installed for paint baseline evidence"
                }
                requireNotNull(inline.bounds).y + maxOf(
                    fm.ascent, (inline.heightInPixels + fm.ascent - fm.descent) / 2,
                )
            }
            listOf(
                "level=" + (index + 1),
                "source_line_zero_based=" + line,
                "source_start_offset=" + start,
                "source_end_offset=" + end,
                "fold_status=" + (if (installed != null) "INSTALLED" else "MISSING"),
                "fold_start_offset=" + (installed?.startOffset ?: "UNAVAILABLE"),
                "fold_end_offset=" + (installed?.endOffset ?: "UNAVAILABLE"),
                "fold_origin_x_document_px=" + (origin?.x ?: "UNAVAILABLE"),
                "fold_origin_y_document_px=" + (origin?.y ?: "UNAVAILABLE"),
                "fold_width_px=" + (installed?.widthInPixels ?: "UNAVAILABLE"),
                "fold_height_px=" + (installed?.heightInPixels ?: "UNAVAILABLE"),
                "inline_pilot_status=" + (if (inlinePilot != null) "INSTALLED_UNREVIEWED" else "ABSENT"),
                "inline_pilot_source_fold=" + (if (pilotSourceFold != null) "COLLAPSED_OWN_CANDIDATE" else "ABSENT"),
                "inline_pilot_width_px=" + (inlinePilot?.widthInPixels ?: "UNAVAILABLE"),
                "inline_pilot_height_px=" + (inlinePilot?.heightInPixels ?: "UNAVAILABLE"),
                "inline_pilot_bounds_y_px=" + (inlinePilot?.bounds?.y ?: "UNAVAILABLE"),
                "heading_y_document_px=" + headingY,
                "native_paint_font_family=" + font.family,
                "native_paint_font_size_px=" + "%.3f".format(java.util.Locale.ROOT, font.size2D),
                "native_paint_font_weight=BOLD",
                "native_paint_text_width_px=" + fm.stringWidth(text),
                "native_paint_text_origin_x_document_px=" + ((origin?.x ?: editor.offsetToXY(start).x) + 4),
                "native_paint_font_ascent_px=" + fm.ascent,
                "native_paint_font_descent_px=" + fm.descent,
                "native_paint_baseline_document_px=" + paintBaseline,
                "following_line_zero_based=" + nextLine,
                "following_offset=" + afterOffset,
                "following_y_document_px=" + followingY,
                "paragraph_after_heading=" + (followingY > headingY),
                "foreign_fold_overlap_count=" + overlap.size,
                "foreign_fold_overlaps=" + foreignRecord,
            ).joinToString("\t")
        }
        val absent = rows.filter { it.contains("fold_status=MISSING") }
            .map { it.substringAfter("level=").substringBefore('\t') }
        return listOf(
            "schema=markflow-native-heading-customfold-geometry/v2",
            "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY",
            "measured_atx_heading_count=" + custom.size,
            "missing_heading_levels=" + absent.joinToString(","),
            "legacy_heading_inlay_count=" + additiveInlays,
            "co_start_inline_pilot_count=" + coStartInlines.size,
            "foreign_fold_count_including_pilot_owned_normal_fold=" + foreign.size,
            "editor_is_showing=" + editor.contentComponent.isShowing,
            "plain_line_height_px=" + editor.lineHeight,
            "native_body_font_family=" + editor.contentComponent.font.family,
            "native_body_font_size_px=" + editor.colorsScheme.editorFontSize2D,
        ).joinToString("\n", postfix = "\n") +
            rows.joinToString("\n", postfix = "\n")
    }

    // Negative controls for the source-span to actual-pixel excess calculation.
    // The large-drift fixture must not be confused with a plain-line baseline.
    private fun sourceSpanExcess(lineDistance: Int, pixelDistance: Int, baseHeight: Int): Long {
        require(lineDistance > 0 && pixelDistance >= 0 && baseHeight > 0)
        return pixelDistance.toLong() - lineDistance.toLong() * baseHeight
    }

    fun nativeHeadingGeometryNegativeControls(): Boolean =
        sourceSpanExcess(2, 40, 20) == 0L &&
            sourceSpanExcess(2, 88, 20) == 48L &&
            sourceSpanExcess(2, 20, 20) == -20L &&
            sourceSpanExcess(8, 260, 20) == 100L

    fun showReferenceAtSourceLine(editor: Editor, line: Int): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(line in 0 until editor.document.lineCount) { "Invalid source anchor line" }
        val (selected, preview) = editors(editor)
        selected.setLayout(TextEditorWithPreview.Layout.SHOW_PREVIEW)
        // This is intentionally panel-independent. The bundled Preview attaches its
        // JCEF panel asynchronously, so initial navigation must not require browser
        // ownership before referencePanelShowing() has proven it is ready.
        preview.javaClass.getMethod(
            "scrollToLine", Editor::class.java, Int::class.javaPrimitiveType
        ).invoke(preview, editor, line)
        return selected.javaClass.name + ";" + preview.javaClass.name + ";line=" + line
    }

    /**
     * #353 post-paint compatibility guard for the real bundled Preview.
     * Call only after the harness has proven the platform panel is showing.
     * Browser ownership stays with IDEA; only the pinned stale-scroll workaround
     * is shared with the controlled reference path.
     */
    fun reResolveReferenceSourceAnchor(editor: Editor, line: Int): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(line in 0 until editor.document.lineCount) { "Invalid source anchor line" }
        val (_, preview) = editors(editor)
        val panel = checkNotNull(previewPanel(preview)) {
            "Bundled Markdown Preview panel is not attached"
        } as? JBCefBrowser ?: error(
            "Bundled Markdown panel is not JBCefBrowser: " + previewPanel(preview)?.javaClass?.name
        )
        check(referencePanelShowing(editor)) {
            "Bundled Markdown Preview source re-resolution requires a showing panel"
        }
        val sourceOffset = editor.document.getLineStartOffset(line)
        MarkdownPreviewSourceAnchorNavigator.reResolveCurrentDom(
            panel.getCefBrowser(), sourceOffset
        )
        return "schema=markflow-bundled-preview-source-anchor-reresolve/v1\n" +
            "source_line_zero_based=" + line + "\n" +
            "source_offset=" + sourceOffset + "\n" +
            "scroll_api=MarkdownPreviewSourceAnchorNavigator.reResolveCurrentDom\n"
    }

    fun referenceShowing(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (_, preview) = editors(editor)
        val component = preview.component
        return component.isShowing && component.width > 100 && component.height > 100
    }

    fun referencePanelAttached(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (_, preview) = editors(editor)
        return previewPanel(preview) != null
    }

    fun referencePanelShowing(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (_, preview) = editors(editor)
        val panel = previewPanel(preview) ?: return false
        val panelComponent = panel.javaClass.methods
            .firstOrNull { it.name == "getComponent" && it.parameterCount == 0 }
            ?.invoke(panel) as? Component ?: return false
        return preview.component.isShowing &&
            panelComponent.isShowing &&
            panelComponent.width > 100 &&
            panelComponent.height > 100
    }

    // JBCefApp.isRemoteEnabled() is package-private in pinned IDEA 2026.2.3.
    // Fail closed if JetBrains changes this diagnostic-only reflection boundary.
    private fun jcefRemoteEnabled(): Boolean {
        val app = JBCefApp.getInstance()
        val method = app.javaClass.getDeclaredMethod("isRemoteEnabled")
        check(method.trySetAccessible()) { "Cannot inspect actual JCEF execution mode" }
        return method.invoke(app) as? Boolean
            ?: error("Unexpected JBCefApp.isRemoteEnabled() result")
    }

    /**
     * #353: use the public CEF readiness source called by the pinned platform
     * JBCefBrowserBase instead of its @ApiStatus.Internal convenience method.
     * This is a current-state probe, so registering a lifespan listener late
     * cannot manufacture native creation evidence. The native identifier is
     * assigned onAfterCreated in Remote JCEF; require it to be valid too.
     *
     * Nonstandard delegate-backed implementations are not presumed ready:
     * unknown readiness must fail closed, never be inferred from Swing paint.
     */
    private fun referenceNativeBrowserCreated(browser: JBCefBrowser): Boolean {
        val cef = browser.getCefBrowser()
        return CefClient.isNativeBrowserCreated(cef) && cef.identifier > 0
    }

    /**
     * Read-only identity of the active *bundled* Markdown Preview browser.
     * The diagnostic can distinguish repeated native browser creation from
     * accidental session reuse without changing the production lifecycle.
     */
    fun referenceBrowserIdentity(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (_, preview) = editors(editor)
        val panel = checkNotNull(previewPanel(preview)) {
            "Bundled Markdown Preview has no active browser panel"
        }
        val browser = panel as? JBCefBrowser
            ?: error("Bundled Markdown panel is not JBCefBrowser: " + panel.javaClass.name)
        return listOf(
            "schema=markflow-bundled-preview-active-browser/v1",
            "preview_identity=" + System.identityHashCode(preview),
            "panel_identity=" + System.identityHashCode(panel),
            "browser_identity=" + System.identityHashCode(browser),
            "cef_identity=" + System.identityHashCode(browser.getCefBrowser()),
            "native_browser_created=" + referenceNativeBrowserCreated(browser),
            "cef_browser_closing=" + browser.getCefBrowser().isClosing,
            "cef_browser_closed=" + browser.getCefBrowser().isClosed,
            "jbcef_disposed=" + browser.isDisposed,
            "jcef_remote_enabled=" + jcefRemoteEnabled(),
            "registry_markdown_osr=" + Registry.`is`("ide.browser.jcef.markdownView.osr.enabled"),
            "browser_offscreen_rendering=" + browser.isOffScreenRendering,
            "panel_showing=" + referencePanelShowing(editor),
        ).joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * #353 diagnostic only: correlate CEF page-load events with getText callback
     * timestamps. Called after bundled panel attachment and before waiting for
     * native creation; never changes page loading or the production Preview.
     * Missing load-start events are possible if registration was late; the
     * registration timestamp is always present to make that visible.
     */
    fun installReferenceLoadTrace(editor: Editor, traceFile: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(referenceLoadTrace == null) { "Previous bundled Preview load trace was not removed" }
        val (_, preview) = editors(editor)
        val panel = checkNotNull(previewPanel(preview)) {
            "Bundled Preview was not attached for load trace"
        }
        val browser = panel as? JBCefBrowser
            ?: error("Bundled Markdown panel is not JBCefBrowser: " + panel.javaClass.name)
        check(!browser.isDisposed) { "Cannot trace an already disposed bundled browser" }
        val cefBrowser = browser.getCefBrowser()
        val path = Path.of(traceFile).toAbsolutePath().normalize()
        Files.createDirectories(requireNotNull(path.parent) { "Load trace has no parent" })
        check(!Files.exists(path)) { "Bundled load trace already exists: $path" }
        val traceLock = Any()
        fun trace(event: String, detail: String = "") {
            val atMs = System.currentTimeMillis()
            synchronized(traceLock) {
                runCatching {
                    Files.writeString(
                        path,
                        "timestamp_ms=" + atMs +
                            "\tevent=" + event +
                            "\tthread=" + Thread.currentThread().name +
                            "\tbrowser_identity=" + System.identityHashCode(browser) +
                            "\tcef_identity=" + System.identityHashCode(cefBrowser) +
                            "\t" + detail + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND,
                    )
                }
            }
        }
        val handler = object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                callbackBrowser: CefBrowser, isLoading: Boolean,
                canGoBack: Boolean, canGoForward: Boolean,
            ) {
                if (callbackBrowser === cefBrowser) {
                    trace("loading-state-change", "is_loading=" + isLoading)
                }
            }

            override fun onLoadStart(
                callbackBrowser: CefBrowser, frame: CefFrame,
                transitionType: CefRequest.TransitionType,
            ) {
                if (callbackBrowser === cefBrowser) {
                    // No synchronous frame RPC from CEF's callback thread.
                    trace("load-start", "frame_identity=" + System.identityHashCode(frame) +
                        "\ttransition=" + transitionType)
                }
            }

            override fun onLoadEnd(
                callbackBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int,
            ) {
                if (callbackBrowser === cefBrowser) {
                    trace("load-end", "frame_identity=" + System.identityHashCode(frame) +
                        "\thttp_status=" + httpStatusCode)
                }
            }

            override fun onLoadError(
                callbackBrowser: CefBrowser, frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String,
            ) {
                if (callbackBrowser === cefBrowser) {
                    trace("load-error", "frame_identity=" + System.identityHashCode(frame) +
                        "\terror_code=" + errorCode)
                }
            }
        }
        trace("load-handler-registration-start",
            "native_created=" + referenceNativeBrowserCreated(browser))
        browser.getJBCefClient().addLoadHandler(handler, cefBrowser)
        referenceLoadTrace = ReferenceLoadTrace(browser, handler, path)
        trace("load-handler-registered")
    }

    /**
     * An asynchronous, exact-bundled-CEF DOM-text probe. The host must not
     * block the IDE/EDT waiting for CefStringVisitor: on-screen text is only
     * accepted after the native browser is created and CEF returns the actual
     * rendered page text. Each request owns a unique host-visible evidence file.
     */
    fun requestReferenceRenderedTextProbe(editor: Editor, probePath: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (_, preview) = editors(editor)
        val panel = checkNotNull(previewPanel(preview)) { "No bundled Preview for DOM text probe" }
        val browser = panel as? JBCefBrowser
            ?: error("Bundled Markdown panel is not JBCefBrowser: " + panel.javaClass.name)
        check(!browser.isDisposed && referenceNativeBrowserCreated(browser)) {
            "Cannot request rendered text before native browser creation"
        }
        val cef = browser.getCefBrowser()
        check(!cef.isClosing && !cef.isClosed) { "Cannot request rendered text from closing browser" }
        val path = Path.of(probePath).toAbsolutePath().normalize()
        Files.createDirectories(requireNotNull(path.parent) { "Probe path has no parent" })
        check(!Files.exists(path)) { "Rendered text probe result already exists: $path" }
        val timingPath = path.resolveSibling("rendered-text-ide-timing.tsv")
        fun traceCef(phase: String, detail: String = "") {
            // Trace files are diagnostic-only. A tracing failure must not change
            // the actual JCEF callback contract or prevent the text response.
            runCatching {
                Files.writeString(
                    timingPath,
                    "timestamp_ms=" + System.currentTimeMillis() +
                        "\tphase=" + phase +
                        "\tprobe=" + path.fileName +
                        "\tthread=" + Thread.currentThread().name +
                        "\t" + detail + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
        }
        traceCef("cef-get-text-invoke")
        cef.getText { text ->
            // CefStringVisitor is asynchronous: do not dispatch back to the EDT.
            // Record callback ARRIVAL before writing the result file.
            traceCef("cef-text-callback", "chars=" + (text?.length ?: 0))
            val write = runCatching {
                Files.writeString(
                    path, text ?: "",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                )
            }
            if (write.isFailure) {
                traceCef("cef-text-file-write-failed",
                    "error=" + write.exceptionOrNull()?.javaClass?.name)
                runCatching {
                    Files.writeString(
                        path.resolveSibling(path.fileName.toString() + ".write-error"),
                        "callback_write_failure=" +
                            write.exceptionOrNull()?.javaClass?.name + "\n"
                    )
                }
            } else {
                traceCef("cef-text-file-written", "chars=" + (text?.length ?: 0))
            }
        }
        traceCef("cef-get-text-return")
    }

    fun previewLifecycleEvidence(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (selected, preview) = editors(editor)
        val panel = previewPanel(preview)
        val panelComponent = panel?.javaClass?.methods
            ?.firstOrNull { it.name == "getComponent" && it.parameterCount == 0 }
            ?.invoke(panel) as? Component
        // IDEA 2026.2.3 MarkdownPreviewFileEditor adds each newly created
        // panel component to its BorderLayout wrapper and stores only the last
        // panel reference. A replaced CENTER component is not automatically
        // removed from the container or disposed. Inspect ownership directly
        // without modifying JetBrains' lifecycle.
        val wrapper = preview.component
        val directChildren = wrapper.components.toList()
        val unownedChildren = directChildren.filter { it !== panelComponent }
        val children = directChildren.joinToString(";") { child ->
            listOf(
                "identity=" + System.identityHashCode(child),
                "class=" + child.javaClass.name,
                "is_active_panel=" + (child === panelComponent),
                "is_showing=" + child.isShowing,
                "is_displayable=" + child.isDisplayable,
            ).joinToString(",")
        }
        return listOf(
            "schema=markflow-bundled-preview-lifecycle/v1",
            "layout=" + (selected.getLayout()?.name ?: "NULL"),
            "preview_disposed=" + previewDisposed(preview),
            "preview_component_showing=" + preview.component.isShowing,
            "panel_attached=" + (panel != null),
            "panel_identity=" +
                (panel?.let { System.identityHashCode(it).toString() } ?: "NONE"),
            "panel_class=" + (panel?.javaClass?.name ?: "NONE"),
            "panel_component_showing=" + (panelComponent?.isShowing ?: false),
            "wrapper_identity=" + System.identityHashCode(wrapper),
            "wrapper_direct_child_count=" + directChildren.size,
            "wrapper_active_child_count=" + directChildren.count { it === panelComponent && panelComponent != null },
            "wrapper_unowned_child_count=" + unownedChildren.size,
            "wrapper_direct_children=" + children,
        ).joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * #353 direct-split counterfactual: the prior iteration's SHOW_EDITOR layout
     * can be restored when this same Markdown file is reopened. Establish split
     * without an EditorOnly roundtrip, and disclose the actual transition.
     * This test-only path never creates/disposes a browser explicitly.
     */
    fun prepareReferenceSplitAtOpen(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (selected, preview) = editors(editor)
        check(!previewDisposed(preview)) { "New bundled Markdown Preview is already disposed" }
        val before = checkNotNull(selected.getLayout()) { "Bundled Preview layout is null at open" }
        check(before == TextEditorWithPreview.Layout.SHOW_EDITOR ||
            before == TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW) {
            "Direct split began from unexpected layout: $before"
        }
        if (before == TextEditorWithPreview.Layout.SHOW_EDITOR) {
            selected.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
        }
        val after = checkNotNull(selected.getLayout()) { "Bundled Preview layout is null after split" }
        check(after == TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW) {
            "Direct split layout not established: before=$before after=$after"
        }
        return "layout_before=" + before.name + "\n" +
            "layout_after=" + after.name + "\n" +
            "split_transition_applied=" + (before != after) + "\n"
    }

    // SHOW_PREVIEW hides the Swing editor; callers must cache the remote Editor
    // before that transition and inspect authoritative Document state through it.
    fun sourceText(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.document.text
    }
    fun sourceStamp(editor: Editor): Long {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.document.modificationStamp
    }
    // Unlike JEditorUiComponent's locator-based accessor, the exact Editor
    // reference remains authoritative when SHOW_PREVIEW hides its Swing view.
    fun primaryCaretOffset(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return editor.caretModel.primaryCaret.offset
    }
    fun sourceUnsaved(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)
    }
    private fun hasSuspendedBrowserStub(component: Component): Boolean =
        when (component) {
            is JLabel -> component.text?.contains("Embedded Browser is suspended", ignoreCase = true) == true
            is Container -> component.components.any(::hasSuspendedBrowserStub)
            else -> false
        }

    /**
     * Executed inside the actual Starter IDEA process, not Gradle's test JVM.
     * Distinguish a loaded-but-unused AppArmor profile from a genuine JCEF failure.
     * Read-only observations; never alter the kernel, JCEF settings or source.
     */
    fun jcefRuntimeEvidence(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        fun read(path: String): String = runCatching {
            Files.readString(Path.of(path)).trim()
        }.getOrElse { error ->
            "UNAVAILABLE:" + error.javaClass.simpleName
        }
        val command = ProcessHandle.current().info().command().orElse("UNKNOWN")
        val executable = runCatching {
            Files.readSymbolicLink(Path.of("/proc/self/exe")).toString()
        }.getOrElse { error ->
            "UNAVAILABLE:" + error.javaClass.simpleName
        }
        return listOf(
            "idea_process_command=" + command,
            "idea_proc_self_exe=" + executable,
            "idea_java_home=" + System.getProperty("java.home", "UNKNOWN"),
            "idea_jcef_remote_property=" + System.getProperty("jcef.remote.enabled", "UNSET"),
            "idea_jcef_remote_enabled=" + jcefRemoteEnabled(),
            "idea_cef_server_trace_flag=" +
                System.getenv("CEF_SERVER_TRACE_RemoteLoadHandler").orEmpty(),
            "idea_cef_server_log_level=" +
                System.getenv("CEF_SERVER_LOG_LEVEL").orEmpty(),
            "idea_cef_server_log_path=" +
                System.getenv("CEF_SERVER_LOG_PATH").orEmpty(),
            "idea_apparmor_current=" + read("/proc/self/attr/current"),
            "kernel_apparmor_restrict_unprivileged_userns=" +
                read("/proc/sys/kernel/apparmor_restrict_unprivileged_userns"),
            "kernel_apparmor_restrict_unprivileged_unconfined=" +
                read("/proc/sys/kernel/apparmor_restrict_unprivileged_unconfined"),
        ).joinToString(separator = "\n", postfix = "\n")
    }

    fun referenceBounds(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(referenceShowing(editor)) { "Actual platform Markdown Preview is not showing" }
        val component = editors(editor).second.component
        check(!hasSuspendedBrowserStub(component)) {
            "Bundled Markdown Preview JCEF is suspended; sandbox stub is not a valid visual oracle.\n" +
                jcefRuntimeEvidence()
        }
        val point = component.locationOnScreen
        val rect = Rectangle(point.x, point.y, component.width, component.height)
        check(rect.width > 100 && rect.height > 100)
        return listOf(rect.x, rect.y, rect.width, rect.height).joinToString(",")
    }

    /**
     * Arm a one-shot host-visible marker for the next bundled Preview close.
     * The CEF life-span callback writes this file from the IDE process, while
     * the Starter test polls Files.exists() in its own process. This keeps the
     * native-close timeout independent of Driver RPC / EDT responsiveness.
     */
    fun armDetachedReferenceBrowserCloseMarker(markerPath: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val marker = Path.of(markerPath).toAbsolutePath().normalize()
        val parent = requireNotNull(marker.parent) { "Native-close marker has no parent" }
        Files.createDirectories(parent)
        Files.deleteIfExists(marker)
        nextDetachedReferenceCloseMarker = marker
    }

    fun restoreNative(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (selected, preview) = editors(editor)
        val closeMarker = nextDetachedReferenceCloseMarker
        nextDetachedReferenceCloseMarker = null
        lastDetachedReferenceCloseMarker = closeMarker
        // Stop diagnostic load listeners before the genuine JCEF disposal path.
        // Do not retain one per iteration on a shared client.
        referenceLoadTrace?.let { loadTrace ->
            val stillAttached = previewPanel(preview)
            check(stillAttached === loadTrace.browser) {
                "Load trace browser identity differs from the bundled Preview being closed"
            }
            loadTrace.browser.getJBCefClient().removeLoadHandler(
                loadTrace.handler, loadTrace.browser.getCefBrowser()
            )
            Files.writeString(
                loadTrace.path,
                "timestamp_ms=" + System.currentTimeMillis() +
                    "\tevent=load-handler-removed\tthread=" +
                    Thread.currentThread().name + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
            referenceLoadTrace = null
        }
        // Capture the actual JBCefBrowser before MarkdownPreviewFileEditor hides
        // and nulls its panel. Do not clear an existing tracker when the panel is
        // already detached: normal acceptance may restore native earlier and the
        // finally block still owns the native-close barrier.
        previewPanel(preview)?.let { panel ->
            val browser = panel as? JBCefBrowser
                ?: error("Bundled Markdown panel is not JBCefBrowser: " + panel.javaClass.name)
            val cefBrowser = browser.getCefBrowser()
            val beforeClose = AtomicBoolean(false)
            val panelIdentity = System.identityHashCode(panel)
            val browserIdentity = System.identityHashCode(browser)
            // Diagnostic-only event journal. In particular, a missing marker alone
            // cannot distinguish a missing native callback from a swallowed
            // filesystem write failure. Persist native callbacks on their own
            // thread; the host timeout must not make a Driver/EDT round-trip.
            val eventLog = closeMarker?.resolveSibling("native-close.events")
            fun recordNativeCloseEvent(phase: String, closingBrowser: CefBrowser? = null) {
                eventLog?.let { log ->
                    runCatching {
                        Files.writeString(
                            log,
                            "timestamp_ms=" + System.currentTimeMillis() +
                                " phase=" + phase +
                                " thread=" + Thread.currentThread().name +
                                " panel_identity=" + panelIdentity +
                                " browser_identity=" + browserIdentity +
                                " cef_identity=" + System.identityHashCode(cefBrowser) +
                                " callback_cef_identity=" +
                                    (closingBrowser?.let { System.identityHashCode(it) } ?: "NONE") +
                                " cef_same=" + (closingBrowser === cefBrowser) + "\n",
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND,
                        )
                    }
                }
            }
            recordNativeCloseEvent("handler-registration-start")
            browser.getJBCefClient().addLifeSpanHandler(
                object : CefLifeSpanHandlerAdapter() {
                    override fun doClose(closingBrowser: CefBrowser): Boolean {
                        recordNativeCloseEvent("cef-do-close", closingBrowser)
                        return false // Preserve CefLifeSpanHandlerAdapter default behavior.
                    }

                    override fun onBeforeClose(closingBrowser: CefBrowser) {
                        recordNativeCloseEvent("cef-on-before-close", closingBrowser)
                        if (closingBrowser === cefBrowser && beforeClose.compareAndSet(false, true)) {
                            closeMarker?.let { marker ->
                                val write = runCatching {
                                    Files.writeString(
                                        marker,
                                        "schema=markflow-bundled-preview-native-close-marker/v1\n" +
                                            "panel_identity=" + panelIdentity + "\n" +
                                            "browser_identity=" + browserIdentity + "\n" +
                                            "cef_before_close_observed=true\n"
                                    )
                                }
                                recordNativeCloseEvent(
                                    if (write.isSuccess) "native-marker-written" else
                                        "native-marker-write-failed-" +
                                            write.exceptionOrNull()?.javaClass?.simpleName
                                )
                            }
                        }
                    }
                },
                cefBrowser,
            )
            recordNativeCloseEvent("handler-registered")
            lastDetachedReferenceBrowser = browser
            lastDetachedReferencePanelIdentity = panelIdentity
            lastDetachedReferenceBeforeClose = beforeClose
        }
        selected.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR)
        // Swing layout and native CefBrowser close are asynchronous. Callers that
        // need a hard wall-clock bound should arm a host marker before this call
        // and wait for that marker after proving panel detach.
    }

    fun detachedReferenceBrowserEvidence(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val browser = lastDetachedReferenceBrowser
        val beforeCloseObserved = lastDetachedReferenceBeforeClose.get()
        return listOf(
            "schema=markflow-bundled-preview-native-close/v1",
            "tracked=" + (browser != null),
            "panel_identity=" + (lastDetachedReferencePanelIdentity?.toString() ?: "NONE"),
            "browser_identity=" + (browser?.let { System.identityHashCode(it) }?.toString() ?: "NONE"),
            "jbcef_disposed=" + (browser?.isDisposed ?: true),
            "cef_before_close_observed=" + beforeCloseObserved,
            "close_marker=" + (lastDetachedReferenceCloseMarker?.toString() ?: "NONE"),
            "close_marker_exists=" +
                (lastDetachedReferenceCloseMarker?.let { Files.exists(it) } ?: false),
            "native_close_complete=" + (browser == null ||
                (browser.isDisposed && beforeCloseObserved)),
        ).joinToString(separator = "\n", postfix = "\n")
    }

    fun nativeShowing(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val (selected, _) = editors(editor)
        return selected.textEditor.editor === editor && editor.contentComponent.isShowing
    }
}
