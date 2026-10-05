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
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import kotlin.math.max

internal data class NativeIndentedCodeModel(
    val sourceRange: ProjectionRange,
    val contentOffset: Int,
    val code: String,
)

internal data class NativeIndentedCodePresentationEvidence(
    val models: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val fullyConcealed: Int,
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
)

/**
 * Conservative parser-bounded indented-code presentation for #332.
 *
 * The JetBrains Markdown parser remains the syntax authority. MarkFlow only promotes parser-proven
 * CODE_BLOCK ranges whose non-blank lines carry an exact four-space presentation prefix. Other
 * parser-proven code blocks stay exact source rather than being re-parsed or reconstructed.
 */
internal object NativeIndentedCodeProjectionPlanner {
    private const val MAX_SOURCE_CHARS = 64 * 1024
    private const val MAX_CODE_LINES = 200
    private const val INDENT = "    "

    fun sourceRanges(basePlan: NativeProjectionPlan): List<ProjectionRange> =
        if (basePlan.status != ProjectionPlanStatus.READY) {
            emptyList()
        } else {
            basePlan.projections
                .asSequence()
                .filter { projection -> projection.kind == NativeProjectionKind.CODE_BLOCK }
                .map(NativeProjection::sourceRange)
                .toList()
        }

    fun plan(basePlan: NativeProjectionPlan): List<NativeIndentedCodeModel> {
        if (basePlan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = basePlan.identity.source
        return basePlan.projections
            .asSequence()
            .filter { projection -> projection.kind == NativeProjectionKind.CODE_BLOCK }
            .mapNotNull { projection -> modelFor(projection, source) }
            .toList()
    }

    private fun modelFor(
        projection: NativeProjection,
        source: String,
    ): NativeIndentedCodeModel? {
        val range = projection.sourceRange
        if (!range.isInside(source)) return null
        if (range.endOffset - range.startOffset > MAX_SOURCE_CHARS) return null

        val raw = source.substring(range.startOffset, range.endOffset)
        val lines = raw.split('\n')
        if (lines.size > MAX_CODE_LINES) return null

        var cursor = range.startOffset
        var contentOffset: Int? = null
        val display = StringBuilder()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            when {
                line.isBlank() -> Unit
                line.startsWith(INDENT) -> {
                    if (contentOffset == null) contentOffset = cursor + INDENT.length
                    display.append(line.substring(INDENT.length))
                }
                else -> return null
            }
            if (index < lines.lastIndex) display.append('\n')
            cursor += rawLine.length + 1
        }

        val code = display.toString()
        if (code.isBlank()) return null
        val firstContentOffset = contentOffset ?: return null
        return NativeIndentedCodeModel(
            sourceRange = range,
            contentOffset = firstContentOffset,
            code = code,
        )
    }
}

/**
 * Per-editor native block owner for supported indented code.
 *
 * Inactive source is concealed only by owned folds and represented by one native block inlay.
 * Caret/selection activity or an inlay click removes presentation and immediately exposes the exact
 * authoritative Markdown bytes. No presentation path writes or normalizes source.
 */
internal class NativeIndentedCodePresentationController(
    private val editor: Editor,
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeIndentedCodeModel>()
    private val owned = LinkedHashMap<CodeKey, OwnedCodePresentation>()
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
        currentModels = NativeIndentedCodeProjectionPlanner.plan(plan)
        if (plan.status != ProjectionPlanStatus.READY || currentModels.isEmpty()) return
        if (!richPresentationEnabled) {
            accessibilityFallbacks += currentModels.size
            return
        }
        currentModels.filterNot(::isActive).forEach { model -> installIfCurrent(plan.identity, model) }
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
            val key = CodeKey.of(model)
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

    fun evidenceSnapshot(): NativeIndentedCodePresentationEvidence =
        NativeIndentedCodePresentationEvidence(
            models = currentModels.size,
            ownedInlays = owned.values.count { it.inlay.isValid },
            ownedFolds = owned.values.sumOf { presentation -> presentation.folds.count(FoldRegion::isValid) },
            fullyConcealed = owned.values.count(::isFullyConcealed),
            accessibilityFallbacks = accessibilityFallbacks,
            mouseReveals = mouseReveals,
        )

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeIndentedCodeInlayRenderer ?: return false
        val key = CodeKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false

        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(renderer.contentOffset)
        mouseReveals += 1
        event.consume()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeIndentedCodeModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = CodeKey.of(model)
        if (owned[key]?.let(::isFullyConcealed) == true) return
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val foldRanges = codeFoldRanges(model, sourceBefore) ?: return
        val installedFolds = mutableListOf<FoldRegion>()
        val coverageFolds = mutableListOf<FoldRegion>()
        var foldsInstalled = true

        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            foldRanges.forEach { range ->
                if (!foldsInstalled) return@forEach
                val blockingForeignFold = editor.foldingModel.allFoldRegions.any { fold ->
                    if (!fold.isValid || fold.startOffset >= range.endOffset || fold.endOffset <= range.startOffset) {
                        return@any false
                    }
                    val containsRange =
                        fold.startOffset <= range.startOffset && fold.endOffset >= range.endOffset
                    val exactRange =
                        fold.startOffset == range.startOffset && fold.endOffset == range.endOffset
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

        val inlay = editor.inlayModel.addBlockElement(
            model.sourceRange.endOffset,
            true,
            true,
            0,
            NativeIndentedCodeInlayRenderer(editor, model),
        )
        if (inlay == null) {
            removeFolds(installedFolds)
            return
        }
        owned[key] = OwnedCodePresentation(installedFolds.toList(), coverageFolds.toList(), inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native indented-code presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native indented-code presentation changed the authoritative Document source"
        }
    }

    private fun codeFoldRanges(
        model: NativeIndentedCodeModel,
        source: String,
    ): List<ProjectionRange>? {
        val range = model.sourceRange
        if (!range.isInside(source)) return null
        if (source.codePointCount(range.startOffset, range.endOffset) < 2) return null

        val boundaries = linkedSetOf(range.startOffset, range.endOffset)
        val tailStart = Character.offsetByCodePoints(source, range.endOffset, -1)
        if (tailStart > range.startOffset && tailStart < range.endOffset) boundaries += tailStart
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
            }

        return boundaries
            .sorted()
            .zipWithNext()
            .mapNotNull { (start, end) -> if (end > start) ProjectionRange(start, end) else null }
    }

    private fun isFullyConcealed(presentation: OwnedCodePresentation): Boolean =
        presentation.inlay.isValid &&
            presentation.coverageFolds.isNotEmpty() &&
            presentation.coverageFolds.all { fold -> fold.isValid && !fold.isExpanded }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(model: NativeIndentedCodeModel): Boolean {
        val range = model.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            range.touches(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwned() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: CodeKey) {
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
        check(!disposed) { "native indented-code presentation controller is disposed" }
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

    private data class CodeKey(val startOffset: Int, val endOffset: Int) {
        companion object {
            fun of(model: NativeIndentedCodeModel) =
                CodeKey(model.sourceRange.startOffset, model.sourceRange.endOffset)
        }
    }

    private data class OwnedCodePresentation(
        val folds: List<FoldRegion>,
        val coverageFolds: List<FoldRegion>,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal class NativeIndentedCodeInlayRenderer(
    private val editor: Editor,
    private val model: NativeIndentedCodeModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange
        get() = model.sourceRange

    val contentOffset: Int
        get() = model.contentOffset

    val displayCode: String
        get() = model.code

    private val lines: List<String>
        get() = model.code
            .removeSuffix("\n")
            .split('\n')
            .map { line -> line.removeSuffix("\r") }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        max(MIN_WIDTH, editor.scrollingModel.visibleArea.width - OUTER_MARGIN * 2)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        return VERTICAL_PADDING * 2 + max(1, lines.size) * max(editor.lineHeight, metrics.height)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g as? Graphics2D ?: return
        val foreground = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
        val background = Color(foreground.red, foreground.green, foreground.blue, BACKGROUND_ALPHA)
        val font = editor.contentComponent.font
        val metrics = g2.getFontMetrics(font)
        val lineHeight = max(editor.lineHeight, metrics.height)
        val left = targetRegion.x + OUTER_MARGIN
        val width = max(1, targetRegion.width - OUTER_MARGIN * 2)

        g2.color = background
        g2.fillRoundRect(left, targetRegion.y, width, targetRegion.height, ARC, ARC)
        g2.font = font
        g2.color = foreground

        val available = max(1, width - HORIZONTAL_PADDING * 2)
        var y = targetRegion.y + VERTICAL_PADDING
        lines.forEach { line ->
            g2.drawString(clip(line, metrics, available), left + HORIZONTAL_PADDING, y + metrics.ascent)
            y += lineHeight
        }
    }

    private fun clip(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val suffix = "..."
        val target = max(0, maxWidth - metrics.stringWidth(suffix))

        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (metrics.stringWidth(text.substring(0, mid)) <= target) {
                low = mid
            } else {
                high = mid - 1
            }
        }

        var end = low
        if (
            end in 1 until text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end -= 1
        }
        return text.substring(0, end) + suffix
    }

    companion object {
        private const val MIN_WIDTH = 180
        private const val OUTER_MARGIN = 6
        private const val HORIZONTAL_PADDING = 10
        private const val VERTICAL_PADDING = 8
        private const val ARC = 8
        private const val BACKGROUND_ALPHA = 18
    }
}
