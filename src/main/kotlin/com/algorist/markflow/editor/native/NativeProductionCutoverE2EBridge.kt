package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorProvider

/** Diagnostic-only read seam for #153 Starter/Driver production-opening-path acceptance. */
@Suppress("unused")
internal object NativeProductionCutoverE2EBridge {
    fun isProductionOwned(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return NativeProductionPresentationManager.getInstance().isAttached(editor)
    }

    fun productionOwnedEditors(): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return NativeProductionPresentationManager.getInstance().attachedEditors()
    }

    fun legacyBrowserProviderRegistered(): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.any { provider ->
            provider.javaClass.name == LEGACY_PROVIDER_CLASS
        }
    }

    fun planReady(editor: Editor): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return NativeProductionPresentationManager.getInstance()
            .controllerFor(editor)
            ?.currentPlan
            ?.status == ProjectionPlanStatus.READY
    }

    fun rawHtmlBlockedPreviews(editor: Editor): Long {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return NativeProductionPresentationManager.getInstance()
            .controllerFor(editor)
            ?.rawHtmlEvidenceSnapshot()
            ?.blockedPreviews
            ?: 0L
    }

    private const val LEGACY_PROVIDER_CLASS = "com.algorist.markflow.editor.MarkFlowEditorProvider"
}
