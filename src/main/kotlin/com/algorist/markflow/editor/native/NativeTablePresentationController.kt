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
import com.intellij.util.ui.accessibility.ScreenReader
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

internal data class NativeTablePresentationEvidence(
    val planIdentity: ProjectionSourceIdentity?,
    val tableModels: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
)

internal data class NativeTableRowModel(
    val header: Boolean,
    val cells: List<String>,
    val sourceRange: ProjectionRange,
    val firstContentOffset: Int,
)

internal data class NativeTableModel(
    val sourceRange: ProjectionRange,
    val rows: List<NativeTableRowModel>,
) {
    val firstContentOffset: Int
        get() = rows.firstOrNull()?.firstContentOffset ?: sourceRange.startOffset
}

/**
 * Derives a table model only from parser-proven GFM TABLE/HEADER/ROW/CELL ranges.
 * No pipe splitting, serializer, or reconstructed Markdown participates in the model.
 */
internal object NativeTableProjectionPlanner {
    fun plan(plan: NativeProjectionPlan): List<NativeTableModel> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = plan.identity.source
        val rows = plan.projections.filter {
            it.kind == NativeProjectionKind.TABLE_HEADER || it.kind == NativeProjectionKind.TABLE_ROW
        }
        return plan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.TABLE }
            .mapNotNull { table ->
                val tableRows = rows
                    .asSequence()
                    .filter { row ->
                        row.sourceRange.startOffset >= table.sourceRange.startOffset &&
                            row.sourceRange.endOffset <= table.sourceRange.endOffset
                    }
                    .sortedBy { it.sourceRange.startOffset }
                    .mapNotNull { row -> row.toTableRow(source) }
                    .toList()
                if (tableRows.isEmpty()) null else NativeTableModel(table.sourceRange, tableRows)
            }
            .toList()
    }

    private fun NativeProjection.toTableRow(source: String): NativeTableRowModel? {
        if (contentRanges.isEmpty()) return null
        val cells = contentRanges.map { range ->
            check(range.isInside(source))
            source.substring(range.startOffset, range.endOffset).trim()
        }
        return NativeTableRowModel(
            header = kind == NativeProjectionKind.TABLE_HEADER,
            cells = cells,
            sourceRange = sourceRange,
            firstContentOffset = contentRanges.first().startOffset,
        )
    }
}

/**
 * Source-neutral GFM table presentation for one editor.
 *
 * Inactive tables are folded and represented by a native block inlay. Any caret/selection inside
 * the table reveals exact source. A direct left click on the inlay also reveals the authoritative
 * source and places the primary caret at the first parser-proven cell. Screen-reader mode keeps the
 * Markdown source visible and installs no table fold/inlay.
 */
internal class NativeTablePresentationController(
    private val editor: Editor,
    private val richPresentationEnabled: () -> Boolean = { !ScreenReader.isActive() },
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeTableModel>()
    private val owned = LinkedHashMap<TableKey, OwnedTablePresentation>()
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

    fun applyPlan(plan: NativeProjectionPlan) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearOwned()
        currentPlanIdentity = plan.identity
        currentModels = NativeTableProjectionPlanner.plan(plan)
        if (plan.status != ProjectionPlanStatus.READY || currentModels.isEmpty()) return
        if (!runCatching(richPresentationEnabled).getOrDefault(false)) {
            accessibilityFallbacks += currentModels.size
            return
        }
        currentModels.filterNot(::isActive).forEach { model -> installIfCurrent(plan.identity, model) }
    }

    fun refreshActivity(plan: NativeProjectionPlan) {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (plan.identity != currentPlanIdentity || !matchesCurrentIdentity(plan.identity)) return
        if (!runCatching(richPresentationEnabled).getOrDefault(false)) {
            clearOwned()
            return
        }
        currentModels.forEach { model ->
            val key = TableKey.of(model)
            if (isActive(model)) removeOwned(key) else installIfCurrent(plan.identity, model)
        }
    }

    fun evidenceSnapshot(): NativeTablePresentationEvidence = NativeTablePresentationEvidence(
        planIdentity = currentPlanIdentity,
        tableModels = currentModels.size,
        ownedInlays = owned.values.count { it.inlay.isValid },
        ownedFolds = owned.values.count { it.fold.isValid },
        accessibilityFallbacks = accessibilityFallbacks,
        mouseReveals = mouseReveals,
    )

    internal fun revealTableAt(point: java.awt.Point): Boolean {
        if (disposed || editor.isDisposed) return false
        val inlay = editor.inlayModel.getElementAt(point, NativeTableInlayRenderer::class.java) ?: return false
        val renderer = inlay.renderer as? NativeTableInlayRenderer ?: return false
        return reveal(renderer)
    }

    /**
     * Uses the public EditorMouseEvent inlay identity that IntelliJ computes before dispatch. This
     * avoids re-deriving editor geometry after earlier mouse listeners may have changed folding or
     * inlay state, while still accepting only an owned MarkFlow table renderer.
     */
    internal fun revealTableAt(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeTableInlayRenderer ?: return false
        return reveal(renderer)
    }

    private fun reveal(renderer: NativeTableInlayRenderer): Boolean {
        val key = TableKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false
        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(renderer.firstContentOffset)
        mouseReveals += 1
        return true
    }

    /** Exact callback path used by the registered [EditorMouseListener], exposed internally for real-IDE proof. */
    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        val revealed = revealTableAt(event)
        if (revealed) event.consume()
        return revealed
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeTableModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = TableKey.of(model)
        if (owned[key]?.let { it.fold.isValid && it.inlay.isValid } == true) return
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        var fold: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            fold = editor.foldingModel.addFoldRegion(
                model.sourceRange.startOffset,
                model.sourceRange.endOffset,
                ZERO_WIDTH_PLACEHOLDER,
            )
            fold?.isExpanded = false
        }
        val installedFold = fold ?: return
        if (installedFold.isExpanded) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (installedFold.isValid) editor.foldingModel.removeFoldRegion(installedFold)
            }
            return
        }

        val renderer = NativeTableInlayRenderer(editor, model)
        val inlay = editor.inlayModel.addBlockElement(
            model.sourceRange.endOffset,
            true,
            true,
            0,
            renderer,
        )
        if (inlay == null) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (installedFold.isValid) editor.foldingModel.removeFoldRegion(installedFold)
            }
            return
        }
        owned[key] = OwnedTablePresentation(installedFold, inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native table presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native table presentation changed the authoritative Document source"
        }
    }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(model: NativeTableModel): Boolean {
        val range = model.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            range.contains(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwned() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: TableKey) {
        val presentation = owned.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        if (presentation.fold.isValid && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (presentation.fold.isValid) editor.foldingModel.removeFoldRegion(presentation.fold)
            }
        }
    }

    private fun requireAlive() {
        check(!disposed) { "native table presentation controller is disposed" }
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

    private data class TableKey(
        val startOffset: Int,
        val endOffset: Int,
    ) {
        companion object {
            fun of(model: NativeTableModel) = TableKey(model.sourceRange.startOffset, model.sourceRange.endOffset)
        }
    }

    private data class OwnedTablePresentation(
        val fold: FoldRegion,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal class NativeTableInlayRenderer(
    private val editor: Editor,
    private val model: NativeTableModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange
        get() = model.sourceRange
    val firstContentOffset: Int
        get() = model.firstContentOffset

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = dimensions().first

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = dimensions().second

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g as? Graphics2D ?: return
        val component = editor.contentComponent
        val normalFont = component.font
        val metrics = component.getFontMetrics(normalFont)
        val boldFont = normalFont.deriveFont(Font.BOLD)
        val boldMetrics = component.getFontMetrics(boldFont)
        val columnWidths = columnWidths(metrics, boldMetrics)
        val rowHeight = rowHeight(metrics)
        val foreground = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
        val background = textAttributes.backgroundColor ?: editor.colorsScheme.defaultBackground

        g2.color = background
        g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        g2.color = foreground

        var y = targetRegion.y
        model.rows.forEach { row ->
            var x = targetRegion.x
            row.cells.forEachIndexed { index, cell ->
                val width = columnWidths.getOrElse(index) { MIN_COLUMN_WIDTH }
                g2.drawRect(x, y, width, rowHeight)
                g2.font = if (row.header) boldFont else normalFont
                val cellMetrics = if (row.header) boldMetrics else metrics
                val rendered = clip(cell, cellMetrics, max(1, width - CELL_HORIZONTAL_PADDING * 2))
                val baseline = y + max(cellMetrics.ascent + 2, (rowHeight + cellMetrics.ascent - cellMetrics.descent) / 2)
                g2.drawString(rendered, x + CELL_HORIZONTAL_PADDING, baseline)
                x += width
            }
            y += rowHeight
        }
    }

    private fun dimensions(): Pair<Int, Int> {
        val component = editor.contentComponent
        val metrics = component.getFontMetrics(component.font)
        val boldMetrics = component.getFontMetrics(component.font.deriveFont(Font.BOLD))
        val widths = columnWidths(metrics, boldMetrics)
        val required = widths.sum().coerceAtLeast(MIN_TABLE_WIDTH)
        val viewport = max(MIN_TABLE_WIDTH, component.visibleRect.width - 16)
        val width = min(required, viewport.coerceAtLeast(MIN_TABLE_WIDTH))
        val height = rowHeight(metrics) * model.rows.size.coerceAtLeast(1)
        return width to height
    }

    private fun columnWidths(normal: java.awt.FontMetrics, bold: java.awt.FontMetrics): List<Int> {
        val columns = model.rows.maxOfOrNull { it.cells.size } ?: 1
        val raw = MutableList(columns) { MIN_COLUMN_WIDTH }
        model.rows.forEach { row ->
            row.cells.forEachIndexed { index, cell ->
                val metrics = if (row.header) bold else normal
                val desired = metrics.stringWidth(cell) + CELL_HORIZONTAL_PADDING * 2
                raw[index] = max(raw[index], min(MAX_COLUMN_WIDTH, desired))
            }
        }
        val viewport = max(MIN_TABLE_WIDTH, editor.contentComponent.visibleRect.width - 16)
        val total = raw.sum()
        if (total <= viewport || total == 0) return raw
        val scale = viewport.toDouble() / total.toDouble()
        return raw.map { width -> max(MIN_COLUMN_WIDTH, (width * scale).toInt()) }
    }

    private fun rowHeight(metrics: java.awt.FontMetrics): Int = max(editor.lineHeight + 4, metrics.height + 8)

    private fun clip(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        val target = max(0, maxWidth - metrics.stringWidth(ellipsis))
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > target) end -= 1
        return text.substring(0, end) + ellipsis
    }

    companion object {
        private const val MIN_TABLE_WIDTH = 160
        private const val MIN_COLUMN_WIDTH = 72
        private const val MAX_COLUMN_WIDTH = 320
        private const val CELL_HORIZONTAL_PADDING = 8
    }
}
