package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

internal enum class NativeMarkdownRichEditKind(
    val prefix: String,
    val suffix: String,
    val commandName: String,
) {
    STRONG("**", "**", "MarkFlow Apply Strong"),
    EMPHASIS("*", "*", "MarkFlow Apply Emphasis"),
    INLINE_CODE("`", "`", "MarkFlow Apply Inline Code"),
}

internal enum class NativeMarkdownRichEditResult {
    APPLIED,
    NO_SELECTION,
    AMBIGUOUS_SELECTIONS,
    UNSUPPORTED_SELECTION,
    READ_ONLY,
    GUARDED_SELECTION,
}

internal data class NativeEditRange(
    val startOffset: Int,
    val endOffset: Int,
) {
    init {
        require(startOffset >= 0)
        require(endOffset > startOffset)
    }
}

/** Source-local rich edit primitives for #146. No whole-document reconstruction is permitted. */
internal object NativeMarkdownRichEdit {
    fun apply(project: Project, editor: Editor, kind: NativeMarkdownRichEditKind): NativeMarkdownRichEditResult {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!editor.document.isWritable) return NativeMarkdownRichEditResult.READ_ONLY

        val carets = editor.caretModel.allCarets
        if (carets.isEmpty() || carets.any { !it.hasSelection() }) {
            return NativeMarkdownRichEditResult.NO_SELECTION
        }

        val selections = carets.map { caret ->
            SelectionEdit(
                caret = caret,
                range = NativeEditRange(caret.selectionStart, caret.selectionEnd),
            )
        }.sortedBy { it.range.startOffset }

        if (hasAmbiguousOverlap(selections.map(SelectionEdit::range))) {
            return NativeMarkdownRichEditResult.AMBIGUOUS_SELECTIONS
        }

        val document = editor.document
        if (selections.any { selection ->
                document.getRangeGuard(selection.range.startOffset, selection.range.endOffset) != null
            }) {
            return NativeMarkdownRichEditResult.GUARDED_SELECTION
        }

        if (selections.any { selection ->
                val selected = document.getText(TextRange(selection.range.startOffset, selection.range.endOffset))
                !kind.supports(selected)
            }) {
            return NativeMarkdownRichEditResult.UNSUPPORTED_SELECTION
        }

        WriteCommandAction.writeCommandAction(project)
            .withName(kind.commandName)
            .run<RuntimeException> {
                selections.asReversed().forEach { selection ->
                    val range = selection.range
                    val selected = document.getText(TextRange(range.startOffset, range.endOffset))
                    document.replaceString(
                        range.startOffset,
                        range.endOffset,
                        kind.prefix + selected + kind.suffix,
                    )
                }
            }

        var precedingDelta = 0
        val delimiterDelta = kind.prefix.length + kind.suffix.length
        selections.forEach { selection ->
            val innerStart = selection.range.startOffset + precedingDelta + kind.prefix.length
            val innerEnd = selection.range.endOffset + precedingDelta + kind.prefix.length
            selection.caret.setSelection(innerStart, innerEnd)
            selection.caret.moveToOffset(innerEnd)
            precedingDelta += delimiterDelta
        }

        return NativeMarkdownRichEditResult.APPLIED
    }

    fun hasAmbiguousOverlap(ranges: List<NativeEditRange>): Boolean =
        ranges.sortedBy(NativeEditRange::startOffset)
            .zipWithNext()
            .any { (left, right) -> left.endOffset > right.startOffset }

    private fun NativeMarkdownRichEditKind.supports(selected: String): Boolean = when (this) {
        NativeMarkdownRichEditKind.STRONG,
        NativeMarkdownRichEditKind.EMPHASIS,
        -> selected.isNotEmpty()
        NativeMarkdownRichEditKind.INLINE_CODE ->
            selected.isNotEmpty() && '\n' !in selected && '\r' !in selected && '`' !in selected
    }

    private data class SelectionEdit(
        val caret: Caret,
        val range: NativeEditRange,
    )
}
