package com.algorist.markflow.file

import com.intellij.openapi.vfs.VirtualFile

object MarkFlowFileSupport {
    fun isMarkFlowTarget(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        return file.fileType.name.equals("Markdown", ignoreCase = true)
            || ext == "md"
            || ext == "markdown"
            || ext == "mdown"
            || ext == "mkdn"
    }
}
