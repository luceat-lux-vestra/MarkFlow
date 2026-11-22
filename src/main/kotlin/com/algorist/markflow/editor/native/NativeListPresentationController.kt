package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import javax.swing.JCheckBox
import javax.swing.UIManager
import kotlin.math.max

internal data class NativeTaskMarker(
    val markerRange: ProjectionRange,
    val stateRange: ProjectionRange,
    val checked: Boolean,
    val sourceState: Char,
)

internal data class NativeListContinuation(
    val contentRange: ProjectionRange,
    val text: String,
)

internal enum class NativeListInlineStyle(val fontMask: Int) {
    PLAIN(Font.PLAIN),
    EMPHASIS(Font.ITALIC),
    STRONG(Font.BOLD),
    STRONG_EMPHASIS(Font.BOLD or Font.ITALIC),
}

internal data class NativeListTextRun(
    val text: String,
    val style: NativeListInlineStyle,
)

internal data class NativeListRow(
    val markerRange: ProjectionRange,
    val contentRange: ProjectionRange,
    val depth: Int,
    val marker: String,
    val text: String,
    val displayRuns: List<NativeListTextRun>,
    val continuations: List<NativeListContinuation> = emptyList(),
    val task: NativeTaskMarker? = null,
) {
    val displayText: String
        get() = displayRuns.joinToString(separator = "") { it.text }
}

internal data class NativeListModel(
    val sourceRange: ProjectionRange,
    val rows: List<NativeListRow>,
)

internal data class NativeListPresentationEvidence(
    val models: Int,
    val rows: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val fullyConcealed: Int,
    val maxDepth: Int,
    val depths: List<Int>,
    val markers: List<String>,
    val taskRows: Int,
    val checkedTasks: Int,
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
    val taskToggles: Long,
)

/**
 * Conservative parser-bounded list model for corrective #318.
 *
 * Supported ordinary lists contain unordered/ordered items, nested lists, parser-proven emphasis/strong
 * on each item's first line, and indented plain-text continuation lines owned by their parent item.
 * The task-list slice accepts unordered task rows, including mixed ordinary/task lists,
 * nested unordered tasks, parser-proven emphasis/strong on each task's first line, and indented
 * plain-text continuation lines. Ordered tasks, rich continuations, and all other inline/block-rich
 * content fail closed.
 */
internal object NativeListProjectionPlanner {
    private val listKinds = setOf(
        NativeProjectionKind.UNORDERED_LIST,
        NativeProjectionKind.ORDERED_LIST,
    )
    private val supportedInlineKinds = setOf(
        NativeProjectionKind.EMPHASIS,
        NativeProjectionKind.STRONG,
    )
    private val unsupportedNestedKinds = setOf(
        NativeProjectionKind.HEADING,
        NativeProjectionKind.STRIKETHROUGH,
        NativeProjectionKind.LINK,
        NativeProjectionKind.BLOCK_QUOTE,
        NativeProjectionKind.INLINE_CODE,
        NativeProjectionKind.CODE_FENCE,
        NativeProjectionKind.CODE_BLOCK,
        NativeProjectionKind.THEMATIC_BREAK,
        NativeProjectionKind.TABLE,
        NativeProjectionKind.TABLE_HEADER,
        NativeProjectionKind.TABLE_ROW,
    )
    fun plan(plan: NativeProjectionPlan): List<NativeListModel> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = plan.identity.source
        val projections = plan.projections
        val lists = projections.filter { it.kind in listKinds }
        val topLevelLists = lists.filter { candidate ->
            lists.none { other ->
                other !== candidate &&
                    other.sourceRange.startOffset <= candidate.sourceRange.startOffset &&
                    other.sourceRange.endOffset >= candidate.sourceRange.endOffset
            }
        }

        return topLevelLists.mapNotNull { list ->
            if (!list.sourceRange.isInside(source)) return@mapNotNull null
            val contained = projections.filter { it.sourceRange.isInside(list.sourceRange) }
            if (contained.any { it.kind in unsupportedNestedKinds }) return@mapNotNull null

            val items = contained
                .filter { it.kind == NativeProjectionKind.LIST_ITEM }
                .sortedBy { it.sourceRange.startOffset }
            if (items.isEmpty()) return@mapNotNull null

            val inlineProjections = contained.filter { it.kind in supportedInlineKinds }
            val rows = items.map { item -> rowFor(item, items, lists, inlineProjections, source) }
            if (rows.any { it == null }) return@mapNotNull null
            val concrete = rows.filterNotNull()
            if (concrete.firstOrNull()?.depth != 0) return@mapNotNull null
            if (concrete.zipWithNext().any { (left, right) -> right.depth > left.depth + 1 }) {
                return@mapNotNull null
            }
            if (
                concrete.any { row ->
                    row.task != null && row.marker !in setOf("-", "*", "+")
                }
            ) {
                return@mapNotNull null
            }

            NativeListModel(list.sourceRange, concrete)
        }
    }

    fun sourceRanges(plan: NativeProjectionPlan): List<ProjectionRange> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val lists = plan.projections.filter { it.kind in listKinds }
        return lists
            .filter { candidate ->
                lists.none { other ->
                    other !== candidate &&
                        other.sourceRange.startOffset <= candidate.sourceRange.startOffset &&
                        other.sourceRange.endOffset >= candidate.sourceRange.endOffset
                }
            }
            .map { it.sourceRange }
    }

    private fun rowFor(
        item: NativeProjection,
        items: List<NativeProjection>,
        lists: List<NativeProjection>,
        inlineProjections: List<NativeProjection>,
        source: String,
    ): NativeListRow? {
        val marker = item.syntaxRanges.singleOrNull() ?: return null
        if (!marker.isInside(source) || !item.sourceRange.isInside(source)) return null
        if (marker.startOffset < item.sourceRange.startOffset || marker.endOffset > item.sourceRange.endOffset) return null

        var start = marker.endOffset
        while (start < item.sourceRange.endOffset && (source[start] == ' ' || source[start] == '\t')) start += 1

        var lineEnd = start
        while (lineEnd < item.sourceRange.endOffset && source[lineEnd] != '\n' && source[lineEnd] != '\r') lineEnd += 1
        var end = lineEnd
        while (end > start && (source[end - 1] == ' ' || source[end - 1] == '\t')) end -= 1
        if (end <= start) return null

        val task = taskMarkerFor(source, start, end)
        if (task != null) {
            start = task.markerRange.endOffset
            while (start < end && (source[start] == ' ' || source[start] == '\t')) start += 1
            if (start >= end) return null
        }
        val contentRange = ProjectionRange(start, end)
        val text = source.substring(start, end)
        val intersectingInline = inlineProjections.filter { projection ->
            projection.sourceRange.startOffset < contentRange.endOffset &&
                projection.sourceRange.endOffset > contentRange.startOffset
        }
        if (intersectingInline.any { !it.sourceRange.isInside(contentRange) }) return null
        val displayRuns = inlineRunsFor(source, contentRange, intersectingInline) ?: return null

        val nestedLists = lists.filter { nested ->
            nested.sourceRange.startOffset >= item.sourceRange.startOffset &&
                nested.sourceRange.endOffset <= item.sourceRange.endOffset &&
                nested.sourceRange.startOffset > marker.endOffset
        }
        val continuations = continuationLinesFor(
            source = source,
            start = lineEnd,
            end = item.sourceRange.endOffset,
            nestedRanges = nestedLists.map { it.sourceRange },
        ) ?: return null

        val depth = items.count { ancestor ->
            ancestor !== item &&
                ancestor.sourceRange.startOffset <= marker.startOffset &&
                ancestor.sourceRange.endOffset >= item.sourceRange.endOffset
        }
        return NativeListRow(
            markerRange = marker,
            contentRange = contentRange,
            depth = depth,
            marker = source.substring(marker.startOffset, marker.endOffset),
            text = text,
            displayRuns = displayRuns,
            continuations = continuations,
            task = task,
        )
    }

    private fun inlineRunsFor(
        source: String,
        contentRange: ProjectionRange,
        projections: List<NativeProjection>,
    ): List<NativeListTextRun>? {
        if (!contentRange.isInside(source)) return null
        if (projections.any { projection ->
                projection.kind !in supportedInlineKinds ||
                    !projection.sourceRange.isInside(contentRange) ||
                    projection.syntaxRanges.isEmpty() ||
                    projection.syntaxRanges.any { syntax ->
                        !syntax.isInside(source) || !syntax.isInside(projection.sourceRange)
                    }
            }) {
            return null
        }

        val syntaxRanges = projections.flatMap(NativeProjection::syntaxRanges)
        val runs = mutableListOf<NativeListTextRun>()
        val buffer = StringBuilder()
        var currentStyle: NativeListInlineStyle? = null

        fun flush() {
            val style = currentStyle ?: return
            if (buffer.isNotEmpty()) {
                runs += NativeListTextRun(buffer.toString(), style)
                buffer.setLength(0)
            }
        }

        for (offset in contentRange.startOffset until contentRange.endOffset) {
            if (syntaxRanges.any { offset >= it.startOffset && offset < it.endOffset }) continue
            val char = source[offset]
            if (isPresentationSensitiveInlineChar(char)) return null

            val bold = projections.any {
                it.kind == NativeProjectionKind.STRONG &&
                    offset >= it.sourceRange.startOffset &&
                    offset < it.sourceRange.endOffset
            }
            val italic = projections.any {
                it.kind == NativeProjectionKind.EMPHASIS &&
                    offset >= it.sourceRange.startOffset &&
                    offset < it.sourceRange.endOffset
            }
            val style = when {
                bold && italic -> NativeListInlineStyle.STRONG_EMPHASIS
                bold -> NativeListInlineStyle.STRONG
                italic -> NativeListInlineStyle.EMPHASIS
                else -> NativeListInlineStyle.PLAIN
            }
            if (currentStyle != style) {
                flush()
                currentStyle = style
            }
            buffer.append(char)
        }
        flush()
        return runs.takeIf { it.isNotEmpty() }
    }

    private fun continuationLinesFor(
        source: String,
        start: Int,
        end: Int,
        nestedRanges: List<ProjectionRange>,
    ): List<NativeListContinuation>? {
        val continuations = mutableListOf<NativeListContinuation>()
        var cursor = start
        while (cursor < end) {
            if (source[cursor] == '\r') {
                cursor += 1
                if (cursor < end && source[cursor] == '\n') cursor += 1
            } else if (source[cursor] == '\n') {
                cursor += 1
            }
            if (cursor >= end) break

            val lineStart = cursor
            var lineEnd = lineStart
            while (lineEnd < end && source[lineEnd] != '\n' && source[lineEnd] != '\r') lineEnd += 1

            val intersectsNested = nestedRanges.any { range ->
                range.startOffset < lineEnd && range.endOffset > lineStart
            }
            if (intersectsNested) {
                if (hasNonWhitespaceOutside(source, lineStart, lineEnd, nestedRanges)) return null
            } else {
                var contentStart = lineStart
                while (contentStart < lineEnd && (source[contentStart] == ' ' || source[contentStart] == '\t')) {
                    contentStart += 1
                }
                var contentEnd = lineEnd
                while (contentEnd > contentStart && (source[contentEnd - 1] == ' ' || source[contentEnd - 1] == '\t')) {
                    contentEnd -= 1
                }
                if (contentStart < contentEnd) {
                    // This bounded slice owns explicit indented continuation lines only.
                    if (contentStart == lineStart) return null
                    val text = source.substring(contentStart, contentEnd)
                    if (text.any(::isPresentationSensitiveInlineChar)) return null
                    continuations += NativeListContinuation(
                        contentRange = ProjectionRange(contentStart, contentEnd),
                        text = text,
                    )
                }
            }
            cursor = lineEnd
        }
        return continuations
    }

    private fun taskMarkerFor(source: String, start: Int, end: Int): NativeTaskMarker? {
        if (end - start < 4) return null
        if (source[start] != '[' || source[start + 2] != ']') return null
        val state = source[start + 1]
        if (state != ' ' && state != 'x' && state != 'X') return null
        if (source[start + 3] != ' ' && source[start + 3] != '\t') return null
        return NativeTaskMarker(
            markerRange = ProjectionRange(start, start + 3),
            stateRange = ProjectionRange(start + 1, start + 2),
            checked = state == 'x' || state == 'X',
            sourceState = state,
        )
    }

    private fun hasNonWhitespaceOutside(
        source: String,
        start: Int,
        end: Int,
        allowedRanges: List<ProjectionRange>,
    ): Boolean {
        var offset = start
        while (offset < end) {
            val covering = allowedRanges
                .filter { range -> range.startOffset <= offset && range.endOffset > offset }
                .maxByOrNull { it.endOffset }
            if (covering != null) {
                offset = covering.endOffset
                continue
            }
            if (!source[offset].isWhitespace()) return true
            offset += 1
        }
        return false
    }

    private fun ProjectionRange.isInside(other: ProjectionRange): Boolean =
        startOffset >= other.startOffset && endOffset <= other.endOffset

    private fun isPresentationSensitiveInlineChar(char: Char): Boolean =
        char == '\\' ||
            char == '*' ||
            char == '_' ||
            char == '[' ||
            char == ']' ||
            char.code == 96 ||
            char == '<' ||
            char == '>' ||
            char == '&' ||
            char == '~' ||
            char == '!'
}

/** Native source-neutral presentation for one editor's supported inactive lists. */
internal class NativeListPresentationController(
    private val editor: Editor,
    private val postMouseActivityReconciliation: () -> Unit = {},
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeListModel>()
    private val owned = LinkedHashMap<ListKey, OwnedListPresentation>()
    private var accessibilityFallbacks = 0L
    private var mouseReveals = 0L
    private var taskToggles = 0L
    private var disposed = false

    private val mouseListener = object : EditorMouseListener {
        override fun mousePressed(event: EditorMouseEvent) {
            handleTaskTogglePress(event)
        }

        override fun mouseClicked(event: EditorMouseEvent) {
            handleMouseReveal(event)
        }
    }

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        editor.addEditorMouseListener(mouseListener)
    }

    fun applyPlan(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearOwned()
        currentPlanIdentity = plan.identity
        currentModels = NativeListProjectionPlanner.plan(plan)
        if (plan.status != ProjectionPlanStatus.READY || currentModels.isEmpty()) return
        if (!richPresentationEnabled) {
            accessibilityFallbacks += currentModels.size
            return
        }
        currentModels.filterNot(::isActive).forEach { installIfCurrent(plan.identity, it) }
    }

    fun refreshActivity(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (plan.identity != currentPlanIdentity || !matchesCurrentIdentity(plan.identity)) return
        if (!richPresentationEnabled) {
            clearOwned()
            return
        }
        currentModels.forEach { model ->
            val key = ListKey.of(model)
            if (isActive(model)) removeOwned(key) else installIfCurrent(plan.identity, model)
        }
    }

    fun clearPresentation() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearOwned()
        currentModels = emptyList()
        currentPlanIdentity = null
    }

    fun evidenceSnapshot(): NativeListPresentationEvidence {
        val rows = currentModels.flatMap(NativeListModel::rows)
        return NativeListPresentationEvidence(
            models = currentModels.size,
            rows = rows.size,
            ownedInlays = owned.values.count { it.inlay.isValid },
            ownedFolds = owned.values.sumOf { presentation -> presentation.folds.count(FoldRegion::isValid) },
            fullyConcealed = owned.values.count(::isFullyConcealed),
            maxDepth = rows.maxOfOrNull(NativeListRow::depth) ?: 0,
            depths = rows.map(NativeListRow::depth),
            markers = rows.map(NativeListRow::marker),
            taskRows = rows.count { it.task != null },
            checkedTasks = rows.count { it.task?.checked == true },
            accessibilityFallbacks = accessibilityFallbacks,
            mouseReveals = mouseReveals,
            taskToggles = taskToggles,
        )
    }

    /**
     * Read-only #563 visual evidence. Records the existing list model, live block-inlay
     * allocation and intersecting IntelliJ folds without changing source or presentation.
     * Row source text is deliberately excluded from diagnostics.
     */
    fun visualStructureGeometryEvidence(): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        requireAlive()
        val font = editor.contentComponent.font
        val metrics = editor.contentComponent.getFontMetrics(font)
        val ownedFolds = owned.values.flatMap(OwnedListPresentation::folds)
        val coverageFolds = owned.values.flatMap(OwnedListPresentation::coverageFolds)
        return buildString {
            appendLine("schema=markflow-visual-structure-list-geometry/v1")
            appendLine("document_stamp=${editor.document.modificationStamp}")
            appendLine("document_length=${editor.document.textLength}")
            appendLine("editor_line_height_px=${editor.lineHeight}")
            appendLine("editor_font_name=${font.name}")
            appendLine("editor_font_size_px=${font.size}")
            appendLine("font_metrics_height_px=${metrics.height}")
            appendLine("font_metrics_ascent_px=${metrics.ascent}")
            appendLine("font_metrics_descent_px=${metrics.descent}")
            appendLine("model_count=${currentModels.size}")
            appendLine("owned_list_inlay_count=${owned.values.count { it.inlay.isValid }}")
            currentModels.forEachIndexed { modelIndex, model ->
                val inlay = owned[ListKey.of(model)]?.inlay
                val valid = inlay?.isValid == true
                val bounds = if (valid) inlay.bounds else null
                val rect = bounds?.let { "${it.x},${it.y},${it.width},${it.height}" }
                    ?: "ABSENT"
                appendLine(
                    "model=$modelIndex\tstart=${model.sourceRange.startOffset}" +
                        "\tend=${model.sourceRange.endOffset}\trows=${model.rows.size}" +
                        "\tinlay_valid=$valid\tinlay_bounds_xywh=$rect"
                )
                val renderer = if (valid) inlay.renderer as? NativeListInlayRenderer else null
                if (renderer != null) {
                    append(renderer.visualRowGeometryEvidence(bounds))
                } else {
                    model.rows.forEachIndexed { rowIndex, row ->
                        appendLine(
                            "row=$rowIndex\tdepth=${row.depth}\tmarker=${row.marker}" +
                                "\tmarker_start=${row.markerRange.startOffset}" +
                                "\tmarker_end=${row.markerRange.endOffset}" +
                                "\tcontent_start=${row.contentRange.startOffset}" +
                                "\tcontent_end=${row.contentRange.endOffset}" +
                                "\ttask=${row.task != null}\tpaint_geometry=ABSENT"
                        )
                    }
                }
            }
            val relevantFolds = editor.foldingModel.allFoldRegions.asSequence()
                .filter(FoldRegion::isValid)
                .filter { fold ->
                    currentModels.any { model ->
                        fold.startOffset < model.sourceRange.endOffset &&
                            fold.endOffset > model.sourceRange.startOffset
                    }
                }
                .sortedWith(compareBy<FoldRegion> { it.startOffset }.thenBy { it.endOffset })
                .toList()
            appendLine("relevant_fold_count=${relevantFolds.size}")
            relevantFolds.forEachIndexed { index, fold ->
                val owner = if (ownedFolds.any { it === fold }) "LIST_OWNED" else "FOREIGN_TO_LIST"
                appendLine(
                    "fold=$index\tstart=${fold.startOffset}\tend=${fold.endOffset}" +
                        "\texpanded=${fold.isExpanded}\towner=$owner" +
                        "\tused_as_list_coverage=${coverageFolds.any { it === fold }}"
                )
            }
        }
    }

    internal fun handleTaskTogglePress(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeListInlayRenderer ?: return false
        val key = ListKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false
        val bounds = presentation.inlay.bounds ?: return false
        val taskRow = renderer.taskRowAt(event.mouseEvent.point, bounds) ?: return false
        if (!applyTaskToggle(key, renderer.row(taskRow))) return false

        // Intercept the press before the editor's normal caret-placement path claims the concealed
        // task source. A checkbox is an interactive presentation control, not a request to reveal
        // source. Consuming the press keeps the pre-click caret outside the inactive task block;
        // the Document refresh can therefore restore the checkbox inlay after the one-char edit.
        event.consume()
        return true
    }

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeListInlayRenderer ?: return false
        val key = ListKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false

        val bounds = presentation.inlay.bounds
        val rowIndex = bounds?.let { renderer.rowAt(event.mouseEvent.point, it) } ?: 0
        return revealSource(key, renderer, rowIndex, event)
    }

    internal fun toggleTask(sourceRange: ProjectionRange, rowIndex: Int): Boolean {
        val key = ListKey(sourceRange.startOffset, sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        val renderer = presentation.inlay.renderer as? NativeListInlayRenderer ?: return false
        val row = renderer.row(rowIndex)
        return applyTaskToggle(key, row)
    }

    private fun applyTaskToggle(key: ListKey, row: NativeListRow): Boolean {
        val task = row.task ?: return false
        val identity = currentPlanIdentity ?: return false
        if (!isCurrent(identity) || !editor.document.isWritable) return false
        val project = editor.project ?: return false
        val stateRange = task.stateRange
        val document = editor.document
        if (!stateRange.isInside(document.immutableCharSequence.toString())) return false
        if (document.getRangeGuard(stateRange.startOffset, stateRange.endOffset) != null) return false
        val current = document.immutableCharSequence[stateRange.startOffset]
        if (current != task.sourceState) return false

        val sourceBefore = document.immutableCharSequence.toString()
        val replacement = if (task.checked) " " else "x"
        val expected = sourceBefore.replaceRange(stateRange.startOffset, stateRange.endOffset, replacement)

        removeOwned(key)
        WriteCommandAction.writeCommandAction(project)
            .withName(TASK_TOGGLE_COMMAND)
            .run<RuntimeException> {
                check(currentPlanIdentity == identity) { "task-list presentation identity changed before toggle" }
                check(document.immutableCharSequence.toString() == sourceBefore) {
                    "authoritative Markdown changed before task toggle command"
                }
                document.replaceString(stateRange.startOffset, stateRange.endOffset, replacement)
            }
        check(document.immutableCharSequence.toString() == expected) {
            "task toggle changed more than the parser/source-bounded state character"
        }
        taskToggles += 1
        return true
    }

    private fun revealSource(
        key: ListKey,
        renderer: NativeListInlayRenderer,
        rowIndex: Int,
        event: EditorMouseEvent,
    ): Boolean {
        removeOwned(key)
        editor.selectionModel.removeSelection()
        val contentOffset = renderer.contentOffset(rowIndex).coerceIn(0, editor.document.textLength)
        editor.caretModel.primaryCaret.moveToOffset(contentOffset)
        mouseReveals += 1
        event.consume()
        postMouseActivityReconciliation()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeListModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = ListKey.of(model)
        if (owned[key]?.let(::isFullyConcealed) == true) return
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val presentationRange = nativePresentationRangeWithFollowingBlankLine(model.sourceRange, sourceBefore)
        val foldRanges = listFoldRanges(model, presentationRange, sourceBefore) ?: return
        val installedFolds = mutableListOf<FoldRegion>()
        val coverageFolds = mutableListOf<FoldRegion>()
        var foldsInstalled = true

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foldRanges.forEach { range ->
                if (!foldsInstalled) return@forEach
                val coveredByForeignMarkerFold = editor.foldingModel.allFoldRegions.firstOrNull { fold ->
                    fold.isValid &&
                        !fold.isExpanded &&
                        fold.startOffset <= range.startOffset &&
                        fold.endOffset >= range.endOffset &&
                        model.rows.any { row ->
                            range.startOffset >= row.markerRange.startOffset &&
                                range.endOffset <= row.markerRange.endOffset
                        }
                }
                if (coveredByForeignMarkerFold != null) {
                    coverageFolds += coveredByForeignMarkerFold
                    return@forEach
                }

                val blockingForeignFold = editor.foldingModel.allFoldRegions.any { fold ->
                    if (
                        !fold.isValid ||
                        fold.startOffset >= range.endOffset ||
                        fold.endOffset <= range.startOffset
                    ) {
                        return@any false
                    }

                    val containsRange =
                        fold.startOffset <= range.startOffset && fold.endOffset >= range.endOffset
                    val exactRange =
                        fold.startOffset == range.startOffset && fold.endOffset == range.endOffset

                    // IntelliJ may recreate an expanded whole-list fold after a one-character task
                    // edit. A strictly nested MarkFlow fold is safe: we never mutate the foreign
                    // region, and removing our fold later leaves the expanded exact source visible.
                    // Exact collisions, partial overlaps and collapsed non-marker owners remain
                    // fail-closed because MarkFlow could not guarantee independent source reveal.
                    !fold.isExpanded || !containsRange || exactRange
                }
                if (blockingForeignFold) {
                    foldsInstalled = false
                    return@forEach
                }

                val fold = editor.foldingModel.addFoldRegion(
                    range.startOffset,
                    range.endOffset,
                    ZERO_WIDTH_PLACEHOLDER,
                )
                if (fold == null) {
                    foldsInstalled = false
                    return@forEach
                }
                installedFolds += fold
                coverageFolds += fold
                fold.isExpanded = false
                if (fold.isExpanded) foldsInstalled = false
            }
            if (!foldsInstalled) {
                installedFolds.asReversed().forEach { fold ->
                    if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
                }
                installedFolds.clear()
                coverageFolds.clear()
            }
        }
        if (!foldsInstalled || coverageFolds.size != foldRanges.size) return

        val renderer = NativeListInlayRenderer(editor, model)
        // Compacting adjacent list groups can collapse every list anchor onto one visual line.
        // IntelliJ orders ABOVE_LINE block inlays by ascending priority (lower = farther from the
        // line), so assign negative source-order ranks: the first list stays visually first and
        // later ordinary block inlays at the default priority remain after the compacted lists.
        val modelIndex = currentModels.indexOf(model)
        check(modelIndex >= 0) { "native list model is not part of the current projection plan" }
        val presentationPriority = modelIndex - currentModels.size
        // The verified following blank line may end exactly where the next Markdown block starts.
        // showWhenFolded keeps the replacement allocated at that shared folded boundary; explicit
        // priority above preserves document order when several such anchors share one visual line.
        val inlay = editor.inlayModel.addBlockElement(
            presentationRange.endOffset,
            InlayProperties()
                .relatesToPrecedingText(true)
                .showAbove(true)
                .showWhenFolded(true)
                .priority(presentationPriority),
            renderer,
        )
        if (inlay == null) {
            removeFolds(installedFolds)
            return
        }
        owned[key] = OwnedListPresentation(installedFolds.toList(), coverageFolds.toList(), inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native list presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native list presentation changed the authoritative Document source"
        }
    }

    private fun listFoldRanges(
        model: NativeListModel,
        range: ProjectionRange,
        source: String,
    ): List<ProjectionRange>? {
        if (!range.isInside(source) || !model.sourceRange.isInside(source)) return null
        if (
            range.startOffset != model.sourceRange.startOffset ||
            range.endOffset < model.sourceRange.endOffset
        ) return null

        val boundaries = linkedSetOf(range.startOffset, range.endOffset)
        // Keep our folds strictly nested when IntelliJ owns an expanded whole-list fold. Splitting
        // at the final Unicode code point avoids an exact-range collision without bisecting a
        // surrogate pair; additional foreign-fold boundaries below prevent partial overlap.
        if (source.codePointCount(range.startOffset, range.endOffset) >= 2) {
            val tailStart = Character.offsetByCodePoints(source, range.endOffset, -1)
            if (tailStart > range.startOffset && tailStart < range.endOffset) {
                boundaries += tailStart
            }
        }
        editor.foldingModel.allFoldRegions
            .asSequence()
            .filter(FoldRegion::isValid)
            .filter { fold -> fold.startOffset < range.endOffset && fold.endOffset > range.startOffset }
            .forEach { fold ->
                if (fold.startOffset > range.startOffset && fold.startOffset < range.endOffset) {
                    boundaries += fold.startOffset
                }
                if (fold.endOffset > range.startOffset && fold.endOffset < range.endOffset) {
                    boundaries += fold.endOffset
                }

                // An expanded platform fold is compatible only when every MarkFlow fold is
                // strictly nested inside it. Merely adding the foreign start/end boundaries can
                // manufacture one exact-range segment (notably for nested Markdown-list folds),
                // which addFoldRegion cannot own independently and causes the whole list
                // presentation to fail closed. Split that overlap at a Unicode-safe interior
                // boundary so the generated folds remain strictly nested. Collapsed foreign
                // folds are deliberately not relaxed here; only the existing marker-fold
                // coverage exception may reuse those.
                if (fold.isExpanded) {
                    val overlapStart = maxOf(range.startOffset, fold.startOffset)
                    val overlapEnd = minOf(range.endOffset, fold.endOffset)
                    if (source.codePointCount(overlapStart, overlapEnd) >= 2) {
                        val overlapTailStart = Character.offsetByCodePoints(source, overlapEnd, -1)
                        if (overlapTailStart > overlapStart && overlapTailStart < overlapEnd) {
                            boundaries += overlapTailStart
                        }
                    }
                }
            }
        return boundaries
            .sorted()
            .zipWithNext()
            .mapNotNull { (start, end) -> if (end > start) ProjectionRange(start, end) else null }
    }

    private fun isFullyConcealed(presentation: OwnedListPresentation): Boolean =
        presentation.inlay.isValid &&
            presentation.coverageFolds.isNotEmpty() &&
            presentation.coverageFolds.all { it.isValid && !it.isExpanded }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(model: NativeListModel): Boolean {
        val range = model.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            range.touches(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwned() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: ListKey) {
        val presentation = owned.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        removeFolds(presentation.folds)
    }

    private fun removeFolds(folds: List<FoldRegion>) {
        if (editor.isDisposed || folds.none(FoldRegion::isValid)) return
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            folds.asReversed().forEach { fold ->
                if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
            }
        }
    }

    private fun requireAlive() {
        check(!disposed) { "native list presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { dispose() }
            return
        }
        editor.removeEditorMouseListener(mouseListener)
        clearOwned()
        currentModels = emptyList()
        currentPlanIdentity = null
        disposed = true
    }

    private data class ListKey(val startOffset: Int, val endOffset: Int) {
        companion object {
            fun of(model: NativeListModel) = ListKey(model.sourceRange.startOffset, model.sourceRange.endOffset)
        }
    }

    private data class OwnedListPresentation(
        val folds: List<FoldRegion>,
        val coverageFolds: List<FoldRegion>,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
        private const val TASK_TOGGLE_COMMAND = "MarkFlow Toggle Task"
    }
}

internal class NativeListInlayRenderer(
    private val editor: Editor,
    private val model: NativeListModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange
        get() = model.sourceRange
    val firstContentOffset: Int
        get() = model.rows.first().contentRange.startOffset
    val taskRowCount: Int
        get() = model.rows.count { it.task != null }

    fun row(index: Int): NativeListRow = model.rows[index]

    internal fun displayText(index: Int): String = model.rows[index].displayText

    internal fun displayFontMasks(index: Int): List<Int> =
        model.rows[index].displayRuns.map { it.style.fontMask }

    /**
     * The same row-size/indent/baseline formulas used by paint, observed without painting.
     * Bounds are in the native editor component coordinate space, not screen coordinates.
     */
    internal fun visualRowGeometryEvidence(bounds: Rectangle?): String {
        val font = editor.contentComponent.font
        val metrics = editor.contentComponent.getFontMetrics(font)
        val height = rowHeight()
        val verticalPadding = verticalPadding()
        val layout = layoutMetrics()
        return buildString {
            appendLine(
                "renderer_row_height_px=$height\tvertical_padding_px=$verticalPadding" +
                    "\touter_padding_px=${layout.outerPadding}" +
                    "\tdepth_indent_px=${layout.depthIndent}" +
                    "\tmarker_gap_px=${layout.markerGap}" +
                    "\tcalculated_inlay_height_px=${height * model.rows.sumOf(::rowVisualLineCount).coerceAtLeast(1) +
                        verticalPadding * 2}"
            )
            model.rows.forEachIndexed { index, row ->
                val rowTop = bounds?.let { it.y + verticalPadding + rowTopOffset(index, height) }
                val baseline = rowTop?.let {
                    it + max(metrics.ascent, (height + metrics.ascent - metrics.descent) / 2)
                }
                val indent = layout.outerPadding + row.depth * layout.depthIndent
                val markerX = bounds?.let { it.x + indent }
                appendLine(
                    "row=$index\tdepth=${row.depth}\tmarker=${row.marker}" +
                        "\tmarker_start=${row.markerRange.startOffset}" +
                        "\tmarker_end=${row.markerRange.endOffset}" +
                        "\tcontent_start=${row.contentRange.startOffset}" +
                        "\tcontent_end=${row.contentRange.endOffset}" +
                        "\tcontinuations=${row.continuations.size}" +
                        "\tvisual_lines=${rowVisualLineCount(row)}" +
                        "\ttask=${row.task != null}\trow_height_px=$height" +
                        "\trow_top_px=${rowTop ?: "ABSENT"}" +
                        "\tbaseline_px=${baseline ?: "ABSENT"}" +
                        "\tindent_px=$indent\tmarker_x_px=${markerX ?: "ABSENT"}"
                )
            }
        }
    }

    fun contentOffset(index: Int): Int =
        model.rows.getOrNull(index)?.contentRange?.startOffset ?: firstContentOffset

    fun rowAt(point: Point, bounds: Rectangle): Int? {
        if (!bounds.contains(point)) return null
        val relative = point.y - bounds.y - verticalPadding()
        if (relative < 0) return null
        val height = rowHeight()
        var rowTop = 0
        model.rows.forEachIndexed { index, row ->
            val rowBottom = rowTop + rowVisualLineCount(row) * height
            if (relative < rowBottom) return index
            rowTop = rowBottom
        }
        return null
    }

    fun taskRowAt(point: Point, bounds: Rectangle): Int? {
        val index = rowAt(point, bounds) ?: return null
        if (model.rows[index].task == null) return null
        return index.takeIf { checkboxBounds(index, bounds).contains(point) }
    }

    fun firstTaskCheckboxCenter(bounds: Rectangle): Point? {
        val index = model.rows.indexOfFirst { it.task != null }
        if (index < 0) return null
        val box = checkboxBounds(index, bounds)
        return Point(box.x + box.width / 2, box.y + box.height / 2)
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val layout = layoutMetrics()
        val availableWidth = max(
            editor.scrollingModel.visibleArea.width,
            editor.contentComponent.width,
        ) - layout.outerPadding * 2
        return max(editor.lineHeight, availableWidth)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        rowHeight() * model.rows.sumOf(::rowVisualLineCount).coerceAtLeast(1) + verticalPadding() * 2

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g as? Graphics2D ?: return
        val font = editor.contentComponent.font
        val bold = font.deriveFont(Font.BOLD)
        val metrics = g2.getFontMetrics(font)
        val foreground = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
        g2.color = foreground

        val height = rowHeight()
        val layout = layoutMetrics()
        model.rows.forEachIndexed { index, row ->
            val y = targetRegion.y + verticalPadding() + rowTopOffset(index, height)
            val baseline = y + max(metrics.ascent, (height + metrics.ascent - metrics.descent) / 2)
            val indent = layout.outerPadding + row.depth * layout.depthIndent
            val markerX = targetRegion.x + indent
            val task = row.task
            val contentX: Int
            if (task != null) {
                val checkbox = checkboxBounds(index, targetRegion)
                paintTaskCheckbox(g2, task, checkbox, foreground)
                contentX = checkbox.x + checkbox.width + layout.markerGap
                drawRuns(g2, row.displayRuns, contentX, baseline, font)
            } else {
                val displayMarker = nativeListDisplayMarker(row.marker)
                g2.font = bold
                g2.drawString(displayMarker, markerX, baseline)
                // Keep the existing content column stable: only the marker glyph is semantic.
                val markerWidth = g2.getFontMetrics(bold).stringWidth(row.marker)
                contentX = markerX + markerWidth + layout.markerGap
                drawRuns(g2, row.displayRuns, contentX, baseline, font)
            }
            g2.font = font
            row.continuations.forEachIndexed { continuationIndex, continuation ->
                g2.drawString(
                    continuation.text,
                    contentX,
                    baseline + (continuationIndex + 1) * height,
                )
            }
        }
    }

    private fun drawRuns(
        g2: Graphics2D,
        runs: List<NativeListTextRun>,
        startX: Int,
        baseline: Int,
        baseFont: Font,
    ) {
        var x = startX
        runs.forEach { run ->
            val style = baseFont.style or run.style.fontMask
            val runFont = if (style == baseFont.style) baseFont else baseFont.deriveFont(style)
            g2.font = runFont
            g2.drawString(run.text, x, baseline)
            x += g2.getFontMetrics(runFont).stringWidth(run.text)
        }
        g2.font = baseFont
    }

    internal fun firstTaskCheckboxBounds(bounds: Rectangle): Rectangle? {
        val index = model.rows.indexOfFirst { it.task != null }
        return index.takeIf { it >= 0 }?.let { checkboxBounds(it, bounds) }
    }

    internal fun taskCheckboxUsesCurrentLafIcon(): Boolean =
        currentCheckboxIcon() != null

    private fun paintTaskCheckbox(
        g2: Graphics2D,
        task: NativeTaskMarker,
        bounds: Rectangle,
        fallbackForeground: java.awt.Color,
    ) {
        val icon = currentCheckboxIcon()
        if (icon != null) {
            val rollover = runCatching {
                editor.contentComponent.mousePosition?.let { point -> bounds.contains(point) } == true
            }.getOrDefault(false)
            val checkbox = JCheckBox().apply {
                isOpaque = false
                isSelected = task.checked
                isEnabled = editor.contentComponent.isEnabled
                // The inlay does not own a Swing button press/focus lifecycle. Preserve the
                // LAF hover state, but do not synthesize ButtonModel.armed from editor focus:
                // armed is a press/action state and some LAFs render it as a pressed checkbox.
                model.isRollover = rollover
            }
            val x = bounds.x + max(0, (bounds.width - icon.iconWidth) / 2)
            val y = bounds.y + max(0, (bounds.height - icon.iconHeight) / 2)
            icon.paintIcon(checkbox, g2, x, y)
            return
        }

        val oldColor = g2.color
        try {
            g2.color = UIManager.getColor(CHECKBOX_FOREGROUND_KEY) ?: fallbackForeground
            g2.drawRect(bounds.x, bounds.y, bounds.width, bounds.height)
            if (task.checked) {
                val inset = max(1, bounds.width / 4)
                val left = bounds.x + inset
                val middleX = bounds.x + bounds.width / 2
                val middleY = bounds.y + bounds.height - inset
                val right = bounds.x + bounds.width - inset
                val top = bounds.y + inset
                g2.drawLine(left, bounds.y + bounds.height / 2, middleX, middleY)
                g2.drawLine(middleX, middleY, right, top)
            }
        } finally {
            g2.color = oldColor
        }
    }

    private fun checkboxBounds(index: Int, bounds: Rectangle): Rectangle {
        val row = model.rows[index]
        val layout = layoutMetrics()
        val indent = layout.outerPadding + row.depth * layout.depthIndent
        val size = checkboxSize()
        val height = rowHeight()
        val rowTop = bounds.y + verticalPadding() + rowTopOffset(index, height)
        val y = rowTop + max(0, (height - size) / 2)
        return Rectangle(bounds.x + indent, y, size, size)
    }

    private fun rowVisualLineCount(row: NativeListRow): Int = 1 + row.continuations.size

    private fun rowTopOffset(index: Int, height: Int): Int =
        model.rows.take(index).sumOf(::rowVisualLineCount) * height

    private fun layoutMetrics(): NativeListLayoutMetrics {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        val spaceWidth = max(1, metrics.charWidth(' '))
        return NativeListLayoutMetrics(
            outerPadding = spaceWidth,
            depthIndent = nativeListIndentPixels(editor, 1),
            markerGap = spaceWidth,
        )
    }

    private fun rowHeight(): Int {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        return max(editor.lineHeight, metrics.height)
    }

    private fun verticalPadding(): Int {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        return max(0, (editor.lineHeight - metrics.height) / 2)
    }

    private fun checkboxSize(): Int {
        val icon = currentCheckboxIcon()
        if (icon != null) return max(icon.iconWidth, icon.iconHeight)
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        return max(1, metrics.height)
    }

    private fun currentCheckboxIcon() =
        UIManager.getIcon(CHECKBOX_ICON_KEY)
            ?.takeIf { icon -> icon.iconWidth > 0 && icon.iconHeight > 0 }

    private data class NativeListLayoutMetrics(
        val outerPadding: Int,
        val depthIndent: Int,
        val markerGap: Int,
    )

    companion object {
        private const val CHECKBOX_ICON_KEY = "CheckBox.icon"
        private const val CHECKBOX_FOREGROUND_KEY = "CheckBox.foreground"
    }
}

internal fun nativePresentationRangeWithFollowingBlankLine(
    range: ProjectionRange,
    source: String,
): ProjectionRange {
    if (range.startOffset < 0 || range.endOffset < range.startOffset || range.endOffset > source.length) {
        return range
    }
    if (range.endOffset >= source.length) return range

    var cursor = range.endOffset
    val alreadyAtNextLine =
        cursor > 0 && (source[cursor - 1] == '\n' || source[cursor - 1] == '\r')
    if (!alreadyAtNextLine) {
        cursor = consumeLineSeparator(source, cursor) ?: return range
    }

    var blankEnd = cursor
    while (blankEnd < source.length && source[blankEnd] != '\n' && source[blankEnd] != '\r') {
        if (source[blankEnd] != ' ' && source[blankEnd] != '\t') return range
        blankEnd += 1
    }
    val afterBlankLine = consumeLineSeparator(source, blankEnd) ?: return range
    return ProjectionRange(range.startOffset, afterBlankLine)
}

private fun consumeLineSeparator(source: String, offset: Int): Int? {
    if (offset !in source.indices) return null
    return when (source[offset]) {
        '\n' -> offset + 1
        '\r' -> if (offset + 1 < source.length && source[offset + 1] == '\n') offset + 2 else offset + 1
        else -> null
    }
}

internal fun nativeListDisplayMarker(marker: String): String =
    when (marker) {
        "-", "*", "+" -> "•"
        else -> marker
    }

internal fun nativeListIndentPixels(editor: Editor, depth: Int): Int {
    require(depth >= 0)
    val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
    val spaceWidth = max(1, metrics.charWidth(' '))
    val tabColumns = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    return depth * spaceWidth * tabColumns
}
