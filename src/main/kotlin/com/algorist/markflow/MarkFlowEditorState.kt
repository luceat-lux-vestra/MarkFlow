package com.algorist.markflow

import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import org.jdom.Element

data class MarkFlowEditorState(
    val version: Int = CURRENT_VERSION,
    val scrollTop: Int = 0,
    val cursorOffset: Int = -1,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1
) : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
        if (otherState !is MarkFlowEditorState) return false
        if (otherState.version != version) return false

        return when (level) {
            FileEditorStateLevel.NAVIGATION -> true
            else -> otherState == this
        }
    }

    fun writeTo(target: Element) {
        target.setAttribute(ATTR_VERSION, version.toString())
        target.setAttribute(ATTR_SCROLL_TOP, scrollTop.toString())
        target.setAttribute(ATTR_CURSOR_OFFSET, cursorOffset.toString())
        target.setAttribute(ATTR_SELECTION_START, selectionStart.toString())
        target.setAttribute(ATTR_SELECTION_END, selectionEnd.toString())
    }

    companion object {
        const val CURRENT_VERSION = 1

        private const val ATTR_VERSION = "version"
        private const val ATTR_SCROLL_TOP = "scrollTop"
        private const val ATTR_CURSOR_OFFSET = "cursorOffset"
        private const val ATTR_SELECTION_START = "selectionStart"
        private const val ATTR_SELECTION_END = "selectionEnd"

        fun readFrom(source: Element): MarkFlowEditorState? {
            if (!source.hasAttributes()) return null

            val version = source.getAttributeValue(ATTR_VERSION)?.toIntOrNull() ?: CURRENT_VERSION
            val scrollTop = source.getAttributeValue(ATTR_SCROLL_TOP)?.toIntOrNull() ?: 0
            val cursorOffset = source.getAttributeValue(ATTR_CURSOR_OFFSET)?.toIntOrNull() ?: -1
            val selectionStart = source.getAttributeValue(ATTR_SELECTION_START)?.toIntOrNull() ?: -1
            val selectionEnd = source.getAttributeValue(ATTR_SELECTION_END)?.toIntOrNull() ?: -1

            return MarkFlowEditorState(
                version = version,
                scrollTop = scrollTop,
                cursorOffset = cursorOffset,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd
            )
        }
    }
}
