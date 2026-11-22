package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.roundToInt

internal sealed interface NativeFootnotePresentationModel {
    val sourceRange: ProjectionRange
    val labelRange: ProjectionRange
    val label: String
    val number: Int

    data class Reference(
        override val sourceRange: ProjectionRange,
        override val labelRange: ProjectionRange,
        override val label: String,
        override val number: Int,
    ) : NativeFootnotePresentationModel

    data class Definition(
        override val sourceRange: ProjectionRange,
        override val labelRange: ProjectionRange,
        override val label: String,
        override val number: Int,
        val bodyRange: ProjectionRange,
    ) : NativeFootnotePresentationModel
}

internal object NativeFootnoteProjectionPlanner {
    fun plan(plan: NativeProjectionPlan): List<NativeFootnotePresentationModel> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = plan.identity.source

        return plan.projections.mapNotNull { projection ->
            val metadata = projection.footnote ?: return@mapNotNull null
            if (
                !projection.sourceRange.isInside(source) ||
                !metadata.labelRange.isInside(source) ||
                metadata.label.isEmpty() ||
                metadata.number <= 0
            ) {
                return@mapNotNull null
            }
            if (
                metadata.labelRange.startOffset < projection.sourceRange.startOffset ||
                metadata.labelRange.endOffset > projection.sourceRange.endOffset
            ) {
                return@mapNotNull null
            }

            when (projection.kind) {
                NativeProjectionKind.FOOTNOTE_REFERENCE -> {
                    if (metadata.bodyRange != null || projection.block) return@mapNotNull null
                    NativeFootnotePresentationModel.Reference(
                        sourceRange = projection.sourceRange,
                        labelRange = metadata.labelRange,
                        label = metadata.label,
                        number = metadata.number,
                    )
                }

                NativeProjectionKind.FOOTNOTE_DEFINITION -> {
                    val bodyRange = metadata.bodyRange ?: return@mapNotNull null
                    if (
                        !projection.block ||
                        !bodyRange.isInside(source) ||
                        bodyRange.startOffset <= projection.sourceRange.startOffset ||
                        bodyRange.endOffset > projection.sourceRange.endOffset
                    ) {
                        return@mapNotNull null
                    }
                    NativeFootnotePresentationModel.Definition(
                        sourceRange = projection.sourceRange,
                        labelRange = metadata.labelRange,
                        label = metadata.label,
                        number = metadata.number,
                        bodyRange = bodyRange,
                    )
                }

                else -> null
            }
        }
    }
}

internal data class NativeFootnotePresentationEvidence(
    val models: Int,
    val references: Int,
    val definitions: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val ownedHighlighters: Int,
    val fullyPresented: Int,
    val mouseReveals: Long,
    val accessibilityFallbacks: Long,
)

/**
 * Source-neutral native presentation for parser-validated footnotes.
 *
 * A reference is fully concealed behind one MarkFlow-owned fold and replaced by a compact inline
 * marker. A definition conceals only its parser-proven label/colon prefix; the exact body bytes stay
 * in the editor and receive secondary, theme-derived styling. Any caret/selection inside the source
 * range or a click on the marker removes the presentation and exposes the exact lexical Markdown.
 */
internal class NativeFootnotePresentationController(
    private val editor: Editor,
    private val postMouseActivityReconciliation: () -> Unit = {},
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeFootnotePresentationModel>()
    private val owned = LinkedHashMap<FootnoteKey, OwnedFootnotePresentation>()
    private var mouseReveals = 0L
    private var accessibilityFallbacks = 0L
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
        currentModels = NativeFootnoteProjectionPlanner.plan(plan)
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
            val key = FootnoteKey.of(model)
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

    fun evidenceSnapshot(): NativeFootnotePresentationEvidence =
        NativeFootnotePresentationEvidence(
            models = currentModels.size,
            references = currentModels.count { model -> model is NativeFootnotePresentationModel.Reference },
            definitions = currentModels.count { model -> model is NativeFootnotePresentationModel.Definition },
            ownedInlays = owned.values.count { presentation -> presentation.inlay.isValid },
            ownedFolds = owned.values.count { presentation -> presentation.fold.isValid },
            ownedHighlighters = owned.values.count { presentation -> presentation.highlighter?.isValid == true },
            fullyPresented = owned.values.count(::isFullyPresented),
            mouseReveals = mouseReveals,
            accessibilityFallbacks = accessibilityFallbacks,
        )

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed || event.isConsumed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeFootnoteMarkerRenderer ?: return false
        val key = FootnoteKey(renderer.role, renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false
        if (currentPlanIdentity?.let(::matchesCurrentIdentity) != true) return false

        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(
            renderer.revealOffset.coerceIn(0, editor.document.textLength)
        )
        mouseReveals += 1
        event.consume()
        postMouseActivityReconciliation()
        return true
    }

    private fun installIfCurrent(
        identity: ProjectionSourceIdentity,
        model: NativeFootnotePresentationModel,
    ) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = FootnoteKey.of(model)
        if (owned[key]?.let(::isFullyPresented) == true) return
        removeOwned(key)

        when (model) {
            is NativeFootnotePresentationModel.Reference -> installReference(identity, key, model)
            is NativeFootnotePresentationModel.Definition -> installDefinition(identity, key, model)
        }
    }

    private fun installReference(
        identity: ProjectionSourceIdentity,
        key: FootnoteKey,
        model: NativeFootnotePresentationModel.Reference,
    ) {
        if (!isCurrent(identity) || hasForeignFoldOverlap(model.sourceRange)) return
        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp

        val fold = addCollapsedFold(model.sourceRange) ?: return
        val renderer = NativeFootnoteMarkerRenderer(
            editor = editor,
            sourceRange = model.sourceRange,
            revealOffset = model.labelRange.startOffset,
            number = model.number,
            role = FootnoteRole.REFERENCE,
        )
        val inlay = editor.inlayModel.addInlineElement(model.sourceRange.endOffset, true, renderer)
        if (inlay == null || !inlay.isValid) {
            inlay?.takeIf { it.isValid }?.dispose()
            removeFold(fold)
            return
        }

        owned[key] = OwnedFootnotePresentation(
            fold = fold,
            inlay = inlay,
            highlighter = null,
        )
        checkSourceUnchanged(sourceBefore, stampBefore)
    }

    private fun installDefinition(
        identity: ProjectionSourceIdentity,
        key: FootnoteKey,
        model: NativeFootnotePresentationModel.Definition,
    ) {
        if (!isCurrent(identity)) return
        val prefixRange = ProjectionRange(model.sourceRange.startOffset, model.bodyRange.startOffset)
        if (hasForeignFoldOverlap(prefixRange)) return

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val fold = addCollapsedFold(prefixRange) ?: return

        val renderer = NativeFootnoteMarkerRenderer(
            editor = editor,
            sourceRange = model.sourceRange,
            revealOffset = model.labelRange.startOffset,
            number = model.number,
            role = FootnoteRole.DEFINITION,
        )
        val inlay = editor.inlayModel.addInlineElement(model.bodyRange.startOffset, false, renderer)
        if (inlay == null || !inlay.isValid) {
            inlay?.takeIf { it.isValid }?.dispose()
            removeFold(fold)
            return
        }

        val highlighter = editor.markupModel.addRangeHighlighter(
            DefaultLanguageHighlighterColors.LINE_COMMENT,
            model.bodyRange.startOffset,
            model.bodyRange.endOffset,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            HighlighterTargetArea.EXACT_RANGE,
        )
        owned[key] = OwnedFootnotePresentation(
            fold = fold,
            inlay = inlay,
            highlighter = highlighter,
        )
        checkSourceUnchanged(sourceBefore, stampBefore)
    }

    private fun addCollapsedFold(range: ProjectionRange): FoldRegion? {
        var installed: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            installed = editor.foldingModel.addFoldRegion(
                range.startOffset,
                range.endOffset,
                ZERO_WIDTH_PLACEHOLDER,
            )
            installed?.isExpanded = false
        }
        val fold = installed ?: return null
        if (!fold.isValid || fold.isExpanded) {
            removeFold(fold)
            return null
        }
        return fold
    }

    private fun hasForeignFoldOverlap(range: ProjectionRange): Boolean =
        editor.foldingModel.allFoldRegions.any { fold ->
            fold.isValid &&
                fold.startOffset < range.endOffset &&
                fold.endOffset > range.startOffset
        }

    private fun isFullyPresented(presentation: OwnedFootnotePresentation): Boolean =
        presentation.fold.isValid &&
            !presentation.fold.isExpanded &&
            presentation.inlay.isValid &&
            (presentation.highlighter == null || presentation.highlighter.isValid)

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(model: NativeFootnotePresentationModel): Boolean =
        editor.caretModel.allCarets.any { caret ->
            model.sourceRange.touches(caret.offset) ||
                (caret.hasSelection() &&
                    model.sourceRange.intersects(caret.selectionStart, caret.selectionEnd))
        }

    private fun clearOwned() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: FootnoteKey) {
        val presentation = owned.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        presentation.highlighter?.let { highlighter ->
            if (highlighter.isValid && !editor.isDisposed) editor.markupModel.removeHighlighter(highlighter)
        }
        removeFold(presentation.fold)
    }

    private fun removeFold(fold: FoldRegion) {
        if (editor.isDisposed || !fold.isValid) return
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
        }
    }

    private fun checkSourceUnchanged(sourceBefore: String, stampBefore: Long) {
        check(editor.document.modificationStamp == stampBefore) {
            "native footnote presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native footnote presentation changed the authoritative Document source"
        }
    }

    private fun requireAlive() {
        check(!disposed) { "native footnote presentation controller is disposed" }
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

    private data class FootnoteKey(
        val role: FootnoteRole,
        val startOffset: Int,
        val endOffset: Int,
    ) {
        companion object {
            fun of(model: NativeFootnotePresentationModel): FootnoteKey =
                FootnoteKey(
                    role = when (model) {
                        is NativeFootnotePresentationModel.Reference -> FootnoteRole.REFERENCE
                        is NativeFootnotePresentationModel.Definition -> FootnoteRole.DEFINITION
                    },
                    startOffset = model.sourceRange.startOffset,
                    endOffset = model.sourceRange.endOffset,
                )
        }
    }

    private data class OwnedFootnotePresentation(
        val fold: FoldRegion,
        val inlay: Inlay<*>,
        val highlighter: RangeHighlighter?,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal enum class FootnoteRole {
    REFERENCE,
    DEFINITION,
}

internal class NativeFootnoteMarkerRenderer(
    private val editor: Editor,
    val sourceRange: ProjectionRange,
    val revealOffset: Int,
    number: Int,
    val role: FootnoteRole,
) : EditorCustomElementRenderer {
    private val displayText = "[$number]"

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = presentationFont()
        val metrics = editor.contentComponent.getFontMetrics(font)
        return metrics.stringWidth(displayText) + trailingGap(metrics.charWidth(' '))
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g as? Graphics2D ?: return
        val oldFont = g2.font
        val oldColor = g2.color
        try {
            val font = presentationFont()
            g2.font = font
            val metrics = g2.getFontMetrics(font)
            g2.color = markerForeground(textAttributes)
            val centeredBaseline =
                targetRegion.y + (targetRegion.height + metrics.ascent - metrics.descent) / 2
            val baseline = if (role == FootnoteRole.REFERENCE) {
                centeredBaseline - max(1, editor.lineHeight / REFERENCE_RAISE_DIVISOR)
            } else {
                centeredBaseline
            }
            g2.drawString(displayText, targetRegion.x, baseline)
        } finally {
            g2.font = oldFont
            g2.color = oldColor
        }
    }

    internal fun presentationFont(): Font {
        val base = editor.contentComponent.font
        val scale = if (role == FootnoteRole.REFERENCE) REFERENCE_FONT_SCALE else DEFINITION_FONT_SCALE
        return base.deriveFont(max(MIN_FONT_SIZE, base.size2D * scale))
    }

    private fun markerForeground(fallback: TextAttributes): java.awt.Color =
        when (role) {
            FootnoteRole.REFERENCE ->
                editor.colorsScheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR).foregroundColor
            FootnoteRole.DEFINITION ->
                editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT).foregroundColor
        } ?: fallback.foregroundColor
        ?: editor.colorsScheme.defaultForeground

    private fun trailingGap(spaceWidth: Int): Int =
        if (role == FootnoteRole.DEFINITION) max(1, spaceWidth) else 0

    companion object {
        private const val REFERENCE_FONT_SCALE = 0.78f
        private const val DEFINITION_FONT_SCALE = 0.90f
        private const val MIN_FONT_SIZE = 8f
        private const val REFERENCE_RAISE_DIVISOR = 6
    }
}
