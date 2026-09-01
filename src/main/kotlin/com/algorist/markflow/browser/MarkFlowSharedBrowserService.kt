package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowDiagnostics
import com.algorist.markflow.editor.MarkFlowEditor
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class MarkFlowSharedBrowserService(@Suppress("UNUSED_PARAMETER") _project: Project) : Disposable {

    private val browserLeasePool = MarkFlowBrowserLeasePool()
    private val gson = Gson()
    private val localDocumentBindings = IdentityHashMap<MarkFlowEditor, LocalDocumentBinding>()
    private val localDocumentBindingSequence = AtomicLong(0)

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
        clearLocalDocumentBinding(editor)
        browserLeasePool.unregisterEditor(editor)
    }

    fun attach(editor: MarkFlowEditor, host: JPanel): Boolean {
        if (disposed || !browserLeasePool.attach(editor, host)) {
            return false
        }

        val binding = createLocalDocumentBinding(editor)
        applyLocalDocumentBaseWhenReady(editor, binding)
        return true
    }

    fun detach(editor: MarkFlowEditor, host: JPanel?) {
        clearLocalDocumentBinding(editor)
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

    fun hasLease(editor: MarkFlowEditor): Boolean {
        return browserLeasePool.hasLease(editor)
    }

    internal fun reapplyRuntimeSettingsForAllAttachedLeases(forceReload: Boolean) {
        browserLeasePool.reapplyRuntimeSettingsForAllAttachedLeases(forceReload)
    }

    private fun createLocalDocumentBinding(editor: MarkFlowEditor): LocalDocumentBinding {
        clearLocalDocumentBinding(editor)
        val registration = MarkFlowWebviewResourceManager.registerLocalDocument(editor.getFile().path)
        val binding = LocalDocumentBinding(
            generation = localDocumentBindingSequence.incrementAndGet(),
            registration = registration
        )
        synchronized(localDocumentBindings) {
            localDocumentBindings[editor] = binding
        }
        return binding
    }

    private fun clearLocalDocumentBinding(editor: MarkFlowEditor, expectedGeneration: Long? = null) {
        val removed = synchronized(localDocumentBindings) {
            val current = localDocumentBindings[editor] ?: return@synchronized null
            if (expectedGeneration != null && current.generation != expectedGeneration) {
                return@synchronized null
            }
            localDocumentBindings.remove(editor)
        }
        MarkFlowWebviewResourceManager.unregisterLocalDocument(removed?.registration?.token)
    }

    private fun applyLocalDocumentBaseWhenReady(
        editor: MarkFlowEditor,
        binding: LocalDocumentBinding,
        attempt: Int = 0
    ) {
        if (disposed || !isCurrentLocalDocumentBinding(editor, binding.generation)) {
            return
        }

        val applied = browserLeasePool.executeForEditor(
            editor,
            buildLocalDocumentBaseScript(binding.registration?.baseUrl)
        )
        if (applied || attempt >= LOCAL_DOCUMENT_BASE_MAX_RETRIES) {
            if (!applied && MarkFlowDiagnostics.enabled) {
                LOG.warn("MARKFLOW_UI local image base injection timed out for ${editor.getFile().path}")
            }
            return
        }

        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                ApplicationManager.getApplication().invokeLater {
                    applyLocalDocumentBaseWhenReady(editor, binding, attempt + 1)
                }
            },
            LOCAL_DOCUMENT_BASE_RETRY_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun isCurrentLocalDocumentBinding(editor: MarkFlowEditor, generation: Long): Boolean {
        return synchronized(localDocumentBindings) {
            localDocumentBindings[editor]?.generation == generation
        }
    }

    private fun buildLocalDocumentBaseScript(baseUrl: String?): String {
        val baseUrlLiteral = gson.toJson(baseUrl)
        return """
            (function(baseHref) {
                var selector = 'base[data-markflow-document-base]';
                var base = document.head ? document.head.querySelector(selector) : null;
                if (baseHref) {
                    if (!base && document.head) {
                        base = document.createElement('base');
                        base.setAttribute('data-markflow-document-base', 'true');
                        document.head.insertBefore(base, document.head.firstChild);
                    }
                    if (base) {
                        base.setAttribute('href', baseHref);
                    }
                } else if (base) {
                    base.remove();
                }

                document.querySelectorAll('img[src]').forEach(function(image) {
                    var rawSrc = image.getAttribute('src');
                    if (!rawSrc || /^(?:[a-z][a-z0-9+.-]*:|\/\/|\/|#)/i.test(rawSrc)) {
                        return;
                    }
                    image.setAttribute('src', rawSrc);
                });
            })($baseUrlLiteral);
        """.trimIndent()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        val registrations = synchronized(localDocumentBindings) {
            val snapshot = localDocumentBindings.values.mapNotNull { it.registration?.token }
            localDocumentBindings.clear()
            snapshot
        }
        registrations.forEach(MarkFlowWebviewResourceManager::unregisterLocalDocument)

        browserLeasePool.dispose()
        MarkFlowWebviewResourceManager.release()

        synchronized(serviceLock) {
            activeServices.remove(this)
        }
    }

    private data class LocalDocumentBinding(
        val generation: Long,
        val registration: LocalDocumentRegistration?
    )

    companion object {
        private val LOG = Logger.getInstance(MarkFlowSharedBrowserService::class.java)
        private val serviceLock = Any()
        private val activeServices = mutableSetOf<MarkFlowSharedBrowserService>()

        private const val LOCAL_DOCUMENT_BASE_RETRY_MS = 100L
        private const val LOCAL_DOCUMENT_BASE_MAX_RETRIES = 100

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
