package com.algorist.markflow

import com.intellij.openapi.vfs.VirtualFile

object MarkFlowFileSupport {
    const val EDITOR_TYPE_ID = "MarkFlowEditor"

    fun isMarkFlowTarget(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        return file.fileType.name.equals("Markdown", ignoreCase = true)
            || ext == "md"
            || ext == "markdown"
            || ext == "mdown"
            || ext == "mkdn"
    }
}

