package com.algorist.markflow.renderer

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Base-safe host contract for the isolated derived-renderer runtime.
 *
 * This API contains no JCEF, browser-editor, Document, editor-session, or source-mutation types.
 * Implementations live behind optional dependency descriptors.
 */
interface DerivedRendererRuntime : Disposable {
    fun render(request: DerivedRendererRuntimeRequest, callback: (DerivedRendererRuntimeResult) -> Unit)
    fun cancel(requestId: String)
}

data class DerivedRendererRuntimeRequest(
    val requestId: String,
    val kind: DerivedRendererKind,
    val source: String,
    val configJson: String,
    val identity: DerivedRendererIdentity,
)

enum class DerivedRendererKind(val wireName: String) {
    MERMAID("mermaid"),
    KATEX("katex"),
}

data class DerivedRendererIdentity(
    val sourceGeneration: String,
    val configGeneration: String,
)

/**
 * Optional inert artifact already converted into a bounded host-paintable representation.
 *
 * The selected #144 runtime emits PNG bytes as Base64 so native presentation never needs JCEF,
 * DOM, Mermaid, KaTeX, navigation, or filesystem authority. The original SVG/HTML artifact remains
 * available on [DerivedRendererRuntimeResult] for fidelity/evidence and non-native consumers.
 */
data class DerivedRendererPresentationArtifact(
    val mediaType: String,
    val contentBase64: String,
    val width: Int,
    val height: Int,
)

data class DerivedRendererRuntimeResult(
    val requestId: String,
    val status: String,
    val kind: String?,
    val identity: DerivedRendererIdentity,
    val mediaType: String? = null,
    val content: String? = null,
    val presentationArtifact: DerivedRendererPresentationArtifact? = null,
    val code: String? = null,
    val retryable: Boolean = false,
    val message: String? = null,
)

interface DerivedRendererRuntimeFactory {
    fun create(createImmediatelyForDiagnostics: Boolean = false): DerivedRendererRuntime

    companion object {
        val EP_NAME: ExtensionPointName<DerivedRendererRuntimeFactory> =
            ExtensionPointName.create("com.algorist.markflow.derivedRendererRuntimeFactory")
    }
}

object DerivedRendererRuntimeProvider {
    fun createOrNull(createImmediatelyForDiagnostics: Boolean = false): DerivedRendererRuntime? {
        val factory = DerivedRendererRuntimeFactory.EP_NAME.extensionList.singleOrNull() ?: return null
        return factory.create(createImmediatelyForDiagnostics)
    }
}
