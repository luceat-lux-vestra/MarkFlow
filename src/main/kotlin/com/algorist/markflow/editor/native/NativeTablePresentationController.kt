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

internal enum class NativeTableInlineStyle(val fontMask: Int) {
    PLAIN(Font.PLAIN),
    EMPHASIS(Font.ITALIC),
    STRONG(Font.BOLD),
    STRONG_EMPHASIS(Font.BOLD or Font.ITALIC),
}

internal data class NativeTableTextRun(
    val text: String,
    val style: NativeTableInlineStyle,
)

internal data class NativeTableCellModel(
    val sourceRange: ProjectionRange,
    val sourceText: String,
    val displayRuns: List<NativeTableTextRun>,
) {
    val displayText: String
        get() = displayRuns.joinToString(separator = "") { it.text }
}

internal data class NativeTableRowModel(
    val header: Boolean,
    val cells: List<NativeTableCellModel>,
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
    private val supportedInlineKinds = setOf(
        NativeProjectionKind.EMPHASIS,
        NativeProjectionKind.STRONG,
    )
    private val tableInlineKinds = supportedInlineKinds + setOf(
        NativeProjectionKind.STRIKETHROUGH,
        NativeProjectionKind.LINK,
        NativeProjectionKind.INLINE_CODE,
    )

    fun plan(plan: NativeProjectionPlan): List<NativeTableModel> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = plan.identity.source
        val rows = plan.projections.filter {
            it.kind == NativeProjectionKind.TABLE_HEADER || it.kind == NativeProjectionKind.TABLE_ROW
        }
        val inlineProjections = plan.projections.filter { it.kind in tableInlineKinds }
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
                    .mapNotNull { row -> row.toTableRow(source, inlineProjections) }
                    .toList()
                if (tableRows.isEmpty()) null else NativeTableModel(table.sourceRange, tableRows)
            }
            .toList()
    }

    private fun NativeProjection.toTableRow(
        source: String,
        inlineProjections: List<NativeProjection>,
    ): NativeTableRowModel? {
        if (contentRanges.isEmpty()) return null
        val cells = contentRanges.map { range ->
            tableCellFor(source, range, inlineProjections) ?: return null
        }
        return NativeTableRowModel(
            header = kind == NativeProjectionKind.TABLE_HEADER,
            cells = cells,
            sourceRange = sourceRange,
            firstContentOffset = contentRanges.first().startOffset,
        )
    }

    private fun tableCellFor(
        source: String,
        sourceRange: ProjectionRange,
        inlineProjections: List<NativeProjection>,
    ): NativeTableCellModel? {
        if (!sourceRange.isInside(source)) return null
        var start = sourceRange.startOffset
        var end = sourceRange.endOffset
        while (start < end && source[start].isWhitespace()) start += 1
        while (end > start && source[end - 1].isWhitespace()) end -= 1
        val sourceText = source.substring(start, end)
        if (start == end) {
            return NativeTableCellModel(sourceRange, sourceText, emptyList())
        }

        val visibleRange = ProjectionRange(start, end)
        val intersecting = inlineProjections.filter { projection ->
            projection.sourceRange.intersects(visibleRange.startOffset, visibleRange.endOffset)
        }
        if (
            intersecting.any { projection ->
                !projection.sourceRange.isInside(source) ||
                    projection.sourceRange.startOffset < visibleRange.startOffset ||
                    projection.sourceRange.endOffset > visibleRange.endOffset
            }
        ) {
            return null
        }

        val supported = intersecting.filter { it.kind in supportedInlineKinds }
        val displayRuns = if (intersecting.any { it.kind !in supportedInlineKinds }) {
            listOf(NativeTableTextRun(sourceText, NativeTableInlineStyle.PLAIN))
        } else {
            inlineRunsFor(source, visibleRange, supported) ?: return null
        }
        return NativeTableCellModel(sourceRange, sourceText, displayRuns)
    }

    private fun inlineRunsFor(
        source: String,
        contentRange: ProjectionRange,
        projections: List<NativeProjection>,
    ): List<NativeTableTextRun>? {
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
        if (projections.isEmpty()) {
            return listOf(
                NativeTableTextRun(
                    source.substring(contentRange.startOffset, contentRange.endOffset),
                    NativeTableInlineStyle.PLAIN,
                )
            )
        }

        val syntaxRanges = projections.flatMap(NativeProjection::syntaxRanges)
        val runs = mutableListOf<NativeTableTextRun>()
        val buffer = StringBuilder()
        var currentStyle: NativeTableInlineStyle? = null

        fun flush() {
            val style = currentStyle ?: return
            if (buffer.isNotEmpty()) {
                runs += NativeTableTextRun(buffer.toString(), style)
                buffer.setLength(0)
            }
        }

        for (offset in contentRange.startOffset until contentRange.endOffset) {
            if (syntaxRanges.any { offset >= it.startOffset && offset < it.endOffset }) continue
            val bold = projections.any {
                it.kind == NativeProjectionKind.STRONG && it.sourceRange.contains(offset)
            }
            val italic = projections.any {
                it.kind == NativeProjectionKind.EMPHASIS && it.sourceRange.contains(offset)
            }
            val style = when {
                bold && italic -> NativeTableInlineStyle.STRONG_EMPHASIS
                bold -> NativeTableInlineStyle.STRONG
                italic -> NativeTableInlineStyle.EMPHASIS
                else -> NativeTableInlineStyle.PLAIN
            }
            if (currentStyle != style) {
                flush()
                currentStyle = style
            }
            buffer.append(source[offset])
        }
        flush()
        return runs.takeIf { it.isNotEmpty() }
    }

    private fun ProjectionRange.isInside(other: ProjectionRange): Boolean =
        startOffset >= other.startOffset && endOffset <= other.endOffset
}

/**
 * Source-neutral GFM table presentation for one editor.
 *
 * Inactive tables are folded and represented by a native block inlay. Any caret/selection inside
 * the table reveals exact source. A direct left click on the inlay also reveals the authoritative
 * source and places the primary caret at the first parser-proven cell. Screen-reader mode keeps the
 * Markdown source visible and installs no table fold/inlay.
 *
 * IntelliJ Markdown owns a whole-table language fold over the same parser range. MarkFlow never
 * removes, expands, collapses, or otherwise claims that foreign fold. Instead, it partitions the
 * exact table source into two adjacent owned folds that are strictly contained by the platform
 * whole-table fold. This keeps ownership deterministic regardless of which folding owner runs first
 * while still concealing the complete inactive table source for MarkFlow's block presentation.
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
        ownedFolds = owned.values.sumOf { presentation -> presentation.folds.count(FoldRegion::isValid) },
        accessibilityFallbacks = accessibilityFallbacks,
        mouseReveals = mouseReveals,
    )

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
        if (
            owned[key]?.let { presentation ->
                presentation.inlay.isValid &&
                    presentation.folds.isNotEmpty() &&
                    presentation.folds.all(FoldRegion::isValid)
            } == true
        ) {
            return
        }
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val foldRanges = tableFoldRanges(model, sourceBefore)
        val installedFolds = mutableListOf<FoldRegion>()
        var foldsInstalled = true
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            for (range in foldRanges) {
                val fold = editor.foldingModel.addFoldRegion(
                    range.startOffset,
                    range.endOffset,
                    ZERO_WIDTH_PLACEHOLDER,
                )
                if (fold == null) {
                    foldsInstalled = false
                    break
                }
                installedFolds += fold
                fold.isExpanded = false
                if (fold.isExpanded) {
                    foldsInstalled = false
                    break
                }
            }
            if (!foldsInstalled) {
                installedFolds.asReversed().forEach { fold ->
                    if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
                }
                installedFolds.clear()
            }
        }
        if (!foldsInstalled || installedFolds.size != foldRanges.size) return

        val renderer = NativeTableInlayRenderer(editor, model)
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
        owned[key] = OwnedTablePresentation(installedFolds.toList(), inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native table presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native table presentation changed the authoritative Document source"
        }
    }

    /**
     * Cover the entire table with two owned normal folds while avoiding an exact-range collision
     * with IntelliJ Markdown's whole-table language fold. The tail starts at the final Unicode code
     * point so the split never bisects a UTF-16 surrogate pair.
     */
    private fun tableFoldRanges(model: NativeTableModel, source: String): List<ProjectionRange> {
        val range = model.sourceRange
        check(range.isInside(source))
        val tailStart = Character.offsetByCodePoints(source, range.endOffset, -1)
        check(tailStart > range.startOffset) { "parser-proven table range is too small to partition safely" }
        return listOf(
            ProjectionRange(range.startOffset, tailStart),
            ProjectionRange(tailStart, range.endOffset),
        )
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
        val folds: List<FoldRegion>,
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
                val baseFont = if (row.header) boldFont else normalFont
                val cellMetrics = if (row.header) boldMetrics else metrics
                val baseline = y + max(cellMetrics.ascent + 2, (rowHeight + cellMetrics.ascent - cellMetrics.descent) / 2)
                drawRunsClipped(
                    g2 = g2,
                    runs = cell.displayRuns,
                    startX = x + CELL_HORIZONTAL_PADDING,
                    baseline = baseline,
                    maxWidth = max(1, width - CELL_HORIZONTAL_PADDING * 2),
                    baseFont = baseFont,
                )
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
                val baseFont = if (row.header) bold.font else normal.font
                val desired = runsWidth(cell.displayRuns, baseFont) + CELL_HORIZONTAL_PADDING * 2
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

    private fun runsWidth(runs: List<NativeTableTextRun>, baseFont: Font): Int =
        runs.sumOf { run ->
            val font = styledFont(baseFont, run.style)
            editor.contentComponent.getFontMetrics(font).stringWidth(run.text)
        }

    private fun drawRunsClipped(
        g2: Graphics2D,
        runs: List<NativeTableTextRun>,
        startX: Int,
        baseline: Int,
        maxWidth: Int,
        baseFont: Font,
    ) {
        if (runs.isEmpty() || maxWidth <= 0) return
        var x = startX
        var remaining = maxWidth
        val fullWidth = runsWidth(runs, baseFont)
        val ellipsis = "…"
        val ellipsisMetrics = editor.contentComponent.getFontMetrics(baseFont)
        val ellipsisWidth = ellipsisMetrics.stringWidth(ellipsis)
        val clip = fullWidth > maxWidth
        val textBudget = if (clip) max(0, maxWidth - ellipsisWidth) else maxWidth

        var consumed = 0
        for (run in runs) {
            val font = styledFont(baseFont, run.style)
            val metrics = editor.contentComponent.getFontMetrics(font)
            val buffer = StringBuilder()
            var offset = 0
            var clippedRun = false
            while (offset < run.text.length) {
                val codePoint = run.text.codePointAt(offset)
                val glyph = String(Character.toChars(codePoint))
                val glyphWidth = metrics.stringWidth(glyph)
                if (consumed + glyphWidth > textBudget) {
                    clippedRun = true
                    break
                }
                buffer.append(glyph)
                consumed += glyphWidth
                offset += Character.charCount(codePoint)
            }
            if (buffer.isNotEmpty()) {
                g2.font = font
                g2.drawString(buffer.toString(), x, baseline)
                val drawn = metrics.stringWidth(buffer.toString())
                x += drawn
                remaining -= drawn
            }
            if (clippedRun) break
        }
        if (clip && remaining >= ellipsisWidth) {
            g2.font = baseFont
            g2.drawString(ellipsis, x, baseline)
        }
        g2.font = baseFont
    }

    private fun styledFont(baseFont: Font, style: NativeTableInlineStyle): Font {
        val targetStyle = baseFont.style or style.fontMask
        return if (targetStyle == baseFont.style) baseFont else baseFont.deriveFont(targetStyle)
    }

    companion object {
        private const val MIN_TABLE_WIDTH = 160
        private const val MIN_COLUMN_WIDTH = 72
        private const val MAX_COLUMN_WIDTH = 320
        private const val CELL_HORIZONTAL_PADDING = 8
    }
}
