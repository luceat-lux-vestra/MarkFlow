package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import kotlin.math.max

internal data class NativeListRow(
    val markerRange: ProjectionRange,
    val contentRange: ProjectionRange,
    val depth: Int,
    val marker: String,
    val text: String,
)

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
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
)

/**
 * Conservative parser-bounded list model for corrective #318.
 *
 * Supported lists contain only single-line plain-text unordered/ordered items plus nested lists.
 * Task-list markers, multiline continuations and inline/block-rich item content remain exact source.
 */
internal object NativeListProjectionPlanner {
    private val listKinds = setOf(
        NativeProjectionKind.UNORDERED_LIST,
        NativeProjectionKind.ORDERED_LIST,
    )
    private val unsupportedNestedKinds = setOf(
        NativeProjectionKind.HEADING,
        NativeProjectionKind.EMPHASIS,
        NativeProjectionKind.STRONG,
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
    private val taskMarker = Regex("""^\[[ xX]](?:\s|$)""")

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

            val rows = items.map { item -> rowFor(item, items, lists, source) }
            if (rows.any { it == null }) return@mapNotNull null
            val concrete = rows.filterNotNull()
            if (concrete.firstOrNull()?.depth != 0) return@mapNotNull null
            if (concrete.zipWithNext().any { (left, right) -> right.depth > left.depth + 1 }) {
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

        val text = source.substring(start, end)
        if (taskMarker.containsMatchIn(text) || text.any(::isPresentationSensitiveInlineChar)) return null

        val nestedLists = lists.filter { nested ->
            nested.sourceRange.startOffset >= item.sourceRange.startOffset &&
                nested.sourceRange.endOffset <= item.sourceRange.endOffset &&
                nested.sourceRange.startOffset > marker.endOffset
        }
        if (hasNonWhitespaceOutside(source, lineEnd, item.sourceRange.endOffset, nestedLists.map { it.sourceRange })) {
            return null
        }

        val depth = items.count { ancestor ->
            ancestor !== item &&
                ancestor.sourceRange.startOffset <= marker.startOffset &&
                ancestor.sourceRange.endOffset >= item.sourceRange.endOffset
        }
        return NativeListRow(
            markerRange = marker,
            contentRange = ProjectionRange(start, end),
            depth = depth,
            marker = source.substring(marker.startOffset, marker.endOffset),
            text = text,
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
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeListModel>()
    private val owned = LinkedHashMap<ListKey, OwnedListPresentation>()
    private var accessibilityFallbacks = 0L
    private var mouseReveals = 0L
    private var disposed = false

    private val mouseListener = object : EditorMouseListener {
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
            accessibilityFallbacks = accessibilityFallbacks,
            mouseReveals = mouseReveals,
        )
    }

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeListInlayRenderer ?: return false
        val key = ListKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false

        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(renderer.firstContentOffset)
        mouseReveals += 1
        event.consume()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeListModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = ListKey.of(model)
        if (owned[key]?.let(::isFullyConcealed) == true) return
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val foldRanges = listFoldRanges(model) ?: return
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

                val overlappingForeignFold = editor.foldingModel.allFoldRegions.any { fold ->
                    fold.isValid &&
                        fold.startOffset < range.endOffset &&
                        fold.endOffset > range.startOffset
                }
                if (overlappingForeignFold) {
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
        val inlay = editor.inlayModel.addBlockElement(
            model.sourceRange.endOffset,
            true,
            true,
            0,
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

    private fun listFoldRanges(model: NativeListModel): List<ProjectionRange>? {
        val range = model.sourceRange
        val source = editor.document.immutableCharSequence.toString()
        if (!range.isInside(source)) return null

        val boundaries = linkedSetOf(range.startOffset, range.endOffset)
        editor.foldingModel.allFoldRegions
            .asSequence()
            .filter(FoldRegion::isValid)
            .filter { fold -> fold.startOffset < range.endOffset && fold.endOffset > range.startOffset }
            .forEach { fold ->
                if (fold.startOffset > range.startOffset && fold.startOffset < range.endOffset) boundaries += fold.startOffset
                if (fold.endOffset > range.startOffset && fold.endOffset < range.endOffset) boundaries += fold.endOffset
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

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        max(MIN_WIDTH, editor.scrollingModel.visibleArea.width - OUTER_PADDING * 2)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        rowHeight() * model.rows.size.coerceAtLeast(1) + VERTICAL_PADDING * 2

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

        model.rows.forEachIndexed { index, row ->
            val y = targetRegion.y + VERTICAL_PADDING + index * rowHeight()
            val baseline = y + max(metrics.ascent + 2, (rowHeight() + metrics.ascent - metrics.descent) / 2)
            val indent = OUTER_PADDING + row.depth * DEPTH_INDENT
            val markerX = targetRegion.x + indent
            g2.font = bold
            g2.drawString(row.marker, markerX, baseline)
            val markerWidth = g2.getFontMetrics(bold).stringWidth(row.marker)
            g2.font = font
            g2.drawString(row.text, markerX + markerWidth + MARKER_GAP, baseline)
        }
    }

    private fun rowHeight(): Int {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        return max(editor.lineHeight + ROW_VERTICAL_PADDING * 2, metrics.height + ROW_VERTICAL_PADDING * 2)
    }

    companion object {
        private const val MIN_WIDTH = 160
        private const val OUTER_PADDING = 8
        private const val VERTICAL_PADDING = 4
        private const val ROW_VERTICAL_PADDING = 2
        private const val DEPTH_INDENT = 24
        private const val MARKER_GAP = 8
    }
}

internal fun nativeListIndentPixels(depth: Int): Int {
    require(depth >= 0)
    return depth * 24
}
