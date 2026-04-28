package com.algorist.markflow

import com.algorist.markflow.browser.MarkFlowBrowserLeasePool
import com.algorist.markflow.browser.MarkFlowWebviewResourceManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class MarkFlowSharedBrowserService(@Suppress("UNUSED_PARAMETER") _project: Project) : Disposable {

    private val browserLeasePool = MarkFlowBrowserLeasePool()

    @Volatile
    private var disposed = false

    init {
        synchronized(serviceLock) {
            activeServices.add(this)
        }

        try {
            MarkFlowWebviewResourceManager.acquire()
        } catch (ex: Throwable) {
            LOG.error("MARKFLOW_UI failed to acquire shared web resources: ${ex.message}", ex)
        }
    }

    fun preWarm() {
        browserLeasePool.preWarm()
    }

    fun registerEditor(editor: MarkFlowEditor) {
        browserLeasePool.registerEditor(editor)
    }

    fun unregisterEditor(editor: MarkFlowEditor) {
        browserLeasePool.unregisterEditor(editor)
    }

    fun attach(editor: MarkFlowEditor, host: JPanel) {
        browserLeasePool.attach(editor, host)
    }

    fun detach(editor: MarkFlowEditor, host: JPanel?) {
        browserLeasePool.detach(editor, host)
    }

    fun pushMarkdownFromEditor(editor: MarkFlowEditor, markdown: String) {
        browserLeasePool.pushMarkdownFromEditor(editor, markdown)
    }

    fun executeForEditor(editor: MarkFlowEditor, script: String): Boolean {
        return browserLeasePool.executeForEditor(editor, script)
    }

    fun reapplyRuntimeSettingsForEditor(editor: MarkFlowEditor, forceReload: Boolean) {
        browserLeasePool.reapplyRuntimeSettingsForEditor(editor, forceReload)
    }

    fun setEditorActive(editor: MarkFlowEditor, active: Boolean) {
        browserLeasePool.setEditorActive(editor, active)
    }

    fun getCurrentMarkdown(editor: MarkFlowEditor): String? {
        return browserLeasePool.getCurrentMarkdown(editor)
    }

    internal fun reapplyRuntimeSettingsForAllAttachedLeases(forceReload: Boolean) {
        browserLeasePool.reapplyRuntimeSettingsForAllAttachedLeases(forceReload)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        browserLeasePool.dispose()
        MarkFlowWebviewResourceManager.release()

        synchronized(serviceLock) {
            activeServices.remove(this)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(MarkFlowSharedBrowserService::class.java)
        private val serviceLock = Any()
        private val activeServices = mutableSetOf<MarkFlowSharedBrowserService>()

        fun notifyRuntimeSettingsChanged(forceReload: Boolean = false) {
            val snapshot = synchronized(serviceLock) {
                activeServices.toList()
            }
            val app = ApplicationManager.getApplication()
            snapshot.forEach { service ->
                app.invokeLater {
                    if (!service.disposed) {
                        service.reapplyRuntimeSettingsForAllAttachedLeases(forceReload)
                    }
                }
            }
        }
    }
}
