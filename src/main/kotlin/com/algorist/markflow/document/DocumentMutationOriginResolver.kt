package com.algorist.markflow.document

import com.intellij.openapi.editor.event.DocumentEvent

/**
 * Narrow seam for origin metadata that IntelliJ's public DocumentEvent does not always expose.
 *
 * The default classification is IDE/HOST. Undo/redo and VFS integrations can provide an
 * explicit scoped context through [DocumentSession.withMutationOrigin], or inject a resolver
 * while those integrations are being migrated. This seam never creates another revision stream.
 */
fun interface DocumentMutationOriginResolver {
    fun resolve(event: DocumentEvent): MutationOrigin

    companion object {
        val DEFAULT = DocumentMutationOriginResolver { MutationOrigin.IDE_HOST }
    }
}
