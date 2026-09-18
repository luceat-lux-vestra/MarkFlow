package com.algorist.markflow.renderer.jcef

import com.algorist.markflow.browser.MarkFlowWebviewResourceManager
import com.algorist.markflow.renderer.DerivedRendererIdentity
import com.algorist.markflow.renderer.DerivedRendererKind
import com.algorist.markflow.renderer.DerivedRendererRuntimeFactory
import com.algorist.markflow.renderer.DerivedRendererRuntimeProvider
import com.algorist.markflow.renderer.DerivedRendererRuntimeRequest
import com.algorist.markflow.renderer.DerivedRendererRuntimeResult
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object DerivedRendererProbe {
    const val OUTPUT_PROPERTY = "markflow.derivedRendererProbe.output"

    private val started = AtomicBoolean(false)

    fun startIfRequested(): Boolean {
        val output = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true

        ApplicationManager.getApplication().invokeLater {
            Runner(Paths.get(output)).start()
        }
        return true
    }

    private class Runner(
        private val output: Path,
    ) {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val cases = mutableListOf<CaseResult>()
        private var runtime: JcefDerivedRendererRuntime? = null
        private var baselineLiveInstances = 0
        private var timeout: ScheduledFuture<*>? = null
        private var finished = false

        fun start() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            baselineLiveInstances = JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics
            armTimeout()

            try {
                val baselineResources = MarkFlowWebviewResourceManager.diagnosticsSnapshot()
                check(baselineResources.ownerCount == 0)
                check(!baselineResources.serverRunning)
                check(!baselineResources.extractedRootPresent)
                check(!baselineResources.tempRootPresent)

                MarkFlowWebviewResourceManager.failNextServerStartForDiagnostics()
                check(MarkFlowWebviewResourceManager.acquire() == null) {
                    "diagnostic renderer resource startup failure unexpectedly acquired an owner"
                }
                val rollbackResources = MarkFlowWebviewResourceManager.diagnosticsSnapshot()
                check(rollbackResources.ownerCount == 0)
                check(!rollbackResources.serverRunning)
                check(!rollbackResources.extractedRootPresent)
                check(!rollbackResources.tempRootPresent)
                cases += CaseResult(
                    id = "resource-start-failure-rollback",
                    outcome = "PASS",
                    detail = "resourceOwners=0 serverRunning=false extractedRoot=false tempRoot=false",
                )

                val factories = DerivedRendererRuntimeFactory.EP_NAME.extensionList
                check(factories.size == 1) { "expected exactly one isolated renderer runtime factory" }
                cases += CaseResult(
                    id = "runtime-factory-registered",
                    outcome = "PASS",
                    detail = "factoryCount=1 implementation=${factories.single().javaClass.name}",
                )

                JcefDerivedRendererRuntime.failNextBrowserConstructionForDiagnostics()
                val createFailure = runCatching {
                    DerivedRendererRuntimeProvider.createOrNull(createImmediatelyForDiagnostics = true)
                }.exceptionOrNull()
                check(createFailure != null) { "diagnostic renderer runtime creation failure did not fail" }
                check(JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics == baselineLiveInstances) {
                    "failed renderer construction changed live-instance count"
                }
                val createFailureResources = MarkFlowWebviewResourceManager.diagnosticsSnapshot()
                check(createFailureResources.ownerCount == 0)
                check(!createFailureResources.serverRunning)
                check(!createFailureResources.extractedRootPresent)
                check(!createFailureResources.tempRootPresent)
                cases += CaseResult(
                    id = "runtime-create-failure-rollback",
                    outcome = "PASS",
                    detail = "failure=${createFailure.javaClass.simpleName} liveInstances=${JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics} resourceOwners=0 serverRunning=false extractedRoot=false tempRoot=false",
                )

                val cleanupFailureRuntime =
                    DerivedRendererRuntimeProvider.createOrNull(createImmediatelyForDiagnostics = true)
                check(cleanupFailureRuntime is JcefDerivedRendererRuntime) {
                    "renderer runtime unavailable before temp-root cleanup recovery proof"
                }
                MarkFlowWebviewResourceManager.failNextTempRootDeleteForDiagnostics()
                cleanupFailureRuntime.dispose()
                check(JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics == baselineLiveInstances) {
                    "diagnostic temp-root cleanup failure changed live-instance baseline"
                }
                val retainedCleanupResources = MarkFlowWebviewResourceManager.diagnosticsSnapshot()
                check(retainedCleanupResources.ownerCount == 0)
                check(!retainedCleanupResources.serverRunning)
                check(retainedCleanupResources.extractedRootPresent)
                check(retainedCleanupResources.tempRootPresent)
                val retainedRoot = requireNotNull(MarkFlowWebviewResourceManager.extractedRootForDiagnostics())

                val recoveredRuntime =
                    DerivedRendererRuntimeProvider.createOrNull(createImmediatelyForDiagnostics = true)
                check(recoveredRuntime is JcefDerivedRendererRuntime) {
                    "renderer runtime did not recover after retained temp-root cleanup failure"
                }
                val recoveredRoot = requireNotNull(MarkFlowWebviewResourceManager.extractedRootForDiagnostics())
                check(recoveredRoot != retainedRoot) { "renderer acquire reused a failed-cleanup temp root" }
                recoveredRuntime.dispose()
                val recoveredCleanupResources = MarkFlowWebviewResourceManager.diagnosticsSnapshot()
                check(recoveredCleanupResources.ownerCount == 0)
                check(!recoveredCleanupResources.serverRunning)
                check(!recoveredCleanupResources.extractedRootPresent)
                check(!recoveredCleanupResources.tempRootPresent)
                check(JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics == baselineLiveInstances)
                cases += CaseResult(
                    id = "temp-root-cleanup-retry",
                    outcome = "PASS",
                    detail = "retainedAfterFailure=true rootChanged=true liveInstances=${JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics} resourceOwners=0 serverRunning=false extractedRoot=false tempRoot=false",
                )

                val createStartedAt = System.nanoTime()
                val created = DerivedRendererRuntimeProvider.createOrNull(createImmediatelyForDiagnostics = true)
                val createElapsedMs = (System.nanoTime() - createStartedAt) / 1_000_000L
                check(created is JcefDerivedRendererRuntime) { "isolated JCEF renderer runtime factory unavailable" }
                runtime = created
                cases += CaseResult(
                    id = "runtime-create-latency",
                    outcome = "PASS",
                    detail = "elapsedMs=$createElapsedMs",
                )
                runMermaidSuccess()
            } catch (failure: Throwable) {
                finishIncomplete("renderer probe startup failed: ${failure.javaClass.name}")
            }
        }

        private fun runMermaidSuccess() {
            runRenderCase(
                id = "mermaid-success",
                request = request(
                    id = "mermaid-success",
                    kind = DerivedRendererKind.MERMAID,
                    source = "graph TD; A-->B",
                    configJson = MERMAID_CONFIG,
                ),
                verify = { result ->
                    check(result.status == "success")
                    check(result.mediaType == "image/svg+xml")
                    check(result.content?.contains("<svg") == true)
                    checkPresentationArtifact(result)
                },
                next = ::runMermaidFailure,
            )
        }

        private fun runMermaidFailure() {
            runRenderCase(
                id = "mermaid-syntax-failure",
                request = request(
                    id = "mermaid-syntax-failure",
                    kind = DerivedRendererKind.MERMAID,
                    source = "graph TD; A-->",
                    configJson = MERMAID_CONFIG,
                ),
                verify = { result ->
                    check(result.status == "failure")
                    check(result.code == "RENDER_FAILED")
                    check(result.content == null)
                    check(result.presentationArtifact == null)
                },
                next = ::runKatexInline,
            )
        }

        private fun runKatexInline() {
            runRenderCase(
                id = "katex-inline-success",
                request = request(
                    id = "katex-inline-success",
                    kind = DerivedRendererKind.KATEX,
                    source = "a^2 + b^2 = c^2",
                    configJson = KATEX_INLINE_CONFIG,
                ),
                verify = { result ->
                    check(result.status == "success")
                    check(result.mediaType == "text/html")
                    check(result.content?.contains("katex") == true)
                    check(!result.content.contains("katex-display"))
                    checkPresentationArtifact(result)
                },
                next = ::runKatexDisplay,
            )
        }

        private fun runKatexDisplay() {
            runRenderCase(
                id = "katex-display-success",
                request = request(
                    id = "katex-display-success",
                    kind = DerivedRendererKind.KATEX,
                    source = "\\int_0^1 x^2\\,dx = \\frac{1}{3}",
                    configJson = KATEX_DISPLAY_CONFIG,
                ),
                verify = { result ->
                    check(result.status == "success")
                    check(result.mediaType == "text/html")
                    check(result.content?.contains("katex-display") == true)
                    checkPresentationArtifact(result)
                },
                next = ::runKatexFailure,
            )
        }

        private fun runKatexFailure() {
            runRenderCase(
                id = "katex-malformed-degradation",
                request = request(
                    id = "katex-malformed-degradation",
                    kind = DerivedRendererKind.KATEX,
                    source = "\\frac{1}{",
                    configJson = KATEX_DISPLAY_CONFIG,
                ),
                verify = { result ->
                    check(result.status == "success")
                    check(result.mediaType == "text/html")
                    check(result.content?.contains("katex-error") == true)
                    checkPresentationArtifact(result)
                },
                next = ::runNavigationBoundary,
            )
        }

        private fun runNavigationBoundary() {
            val current = runtime ?: return finishIncomplete("renderer runtime missing before navigation proof")
            val before = current.blockedNavigationCountForDiagnostics
            current.probeBlockedNavigationForDiagnostics()
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    ApplicationManager.getApplication().invokeLater {
                        if (finished) return@invokeLater
                        try {
                            val after = current.blockedNavigationCountForDiagnostics
                            check(after > before) { "cross-origin navigation was not blocked" }
                            cases += CaseResult(
                                id = "cross-origin-navigation-blocked",
                                outcome = "PASS",
                                detail = "blockedBefore=$before blockedAfter=$after",
                            )
                            runLifecycleProof()
                        } catch (failure: Throwable) {
                            failCase("cross-origin-navigation-blocked", failure)
                        }
                    }
                },
                NAVIGATION_OBSERVATION_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun runLifecycleProof() {
            try {
                runtime?.dispose()
                runtime = null
                val lifecycleStartedAt = System.nanoTime()
                repeat(REPEATED_DISPOSAL_CYCLES) {
                    val current = DerivedRendererRuntimeProvider.createOrNull(createImmediatelyForDiagnostics = true)
                    check(current is JcefDerivedRendererRuntime)
                    current.dispose()
                }
                val lifecycleElapsedMs = (System.nanoTime() - lifecycleStartedAt) / 1_000_000L
                val finalLiveInstances = JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics
                check(finalLiveInstances == baselineLiveInstances) {
                    "renderer runtime live-instance count did not return to baseline"
                }
                val finalResources = MarkFlowWebviewResourceManager.diagnosticsSnapshot()
                check(finalResources.ownerCount == 0) { "renderer resource owner count leaked: $finalResources" }
                check(!finalResources.serverRunning) { "renderer resource server remained live: $finalResources" }
                check(!finalResources.extractedRootPresent) { "renderer extracted root remained owned: $finalResources" }
                check(!finalResources.tempRootPresent) { "renderer temp extraction remained on disk: $finalResources" }
                cases += CaseResult(
                    id = "repeated-create-dispose",
                    outcome = "PASS",
                    detail = "cycles=$REPEATED_DISPOSAL_CYCLES baseline=$baselineLiveInstances final=$finalLiveInstances elapsedMs=$lifecycleElapsedMs resourceOwners=${finalResources.ownerCount} serverRunning=${finalResources.serverRunning} extractedRoot=${finalResources.extractedRootPresent} tempRoot=${finalResources.tempRootPresent}",
                )
                finish("PASS")
            } catch (failure: Throwable) {
                failCase("repeated-create-dispose", failure)
            }
        }

        private fun runRenderCase(
            id: String,
            request: DerivedRendererRuntimeRequest,
            verify: (DerivedRendererRuntimeResult) -> Unit,
            next: () -> Unit,
        ) {
            val current = runtime ?: return finishIncomplete("renderer runtime missing before $id")
            val renderStartedAt = System.nanoTime()
            current.render(request) { result ->
                ApplicationManager.getApplication().invokeLater {
                    if (finished) return@invokeLater
                    try {
                        check(result.requestId == request.requestId)
                        check(result.kind == request.kind.wireName)
                        check(result.identity == request.identity)
                        verify(result)
                        val elapsedMs = (System.nanoTime() - renderStartedAt) / 1_000_000L
                        cases += CaseResult(id, "PASS", "${resultSummary(result)} elapsedMs=$elapsedMs")
                        next()
                    } catch (failure: Throwable) {
                        failCase(id, failure)
                    }
                }
            }
        }

        private fun checkPresentationArtifact(result: DerivedRendererRuntimeResult) {
            val artifact = requireNotNull(result.presentationArtifact) { "native presentation artifact missing" }
            check(artifact.mediaType == "image/png")
            check(artifact.width > 0 && artifact.height > 0)
            val bytes = Base64.getDecoder().decode(artifact.contentBase64)
            check(bytes.size >= PNG_SIGNATURE.size)
            check(PNG_SIGNATURE.indices.all { index -> bytes[index] == PNG_SIGNATURE[index] }) {
                "native presentation artifact is not PNG"
            }
        }

        private fun failCase(id: String, failure: Throwable) {
            if (cases.none { it.id == id }) {
                cases += CaseResult(id, "FAIL", failure.javaClass.name)
            }
            finish("INCOMPLETE")
        }

        private fun finishIncomplete(detail: String) {
            if (cases.none { it.id == "probe-internal-failure" }) {
                cases += CaseResult("probe-internal-failure", "INCOMPLETE", detail.take(400))
            }
            finish("INCOMPLETE")
        }

        private fun finish(verdict: String) {
            if (finished) return
            finished = true
            timeout?.cancel(false)
            timeout = null
            runCatching { runtime?.dispose() }
            runtime = null

            val finalLiveInstances = JcefDerivedRendererRuntime.liveInstanceCountForDiagnostics
            val effectiveVerdict = if (verdict == "PASS" && finalLiveInstances == baselineLiveInstances) {
                "PASS"
            } else {
                "INCOMPLETE"
            }

            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(
                    output,
                    gson.toJson(
                        Evidence(
                            schemaVersion = 1,
                            selectedRuntime = "ISOLATED_JCEF",
                            ideBuild = ApplicationInfo.getInstance().build.asString(),
                            verdict = effectiveVerdict,
                            baselineLiveInstances = baselineLiveInstances,
                            finalLiveInstances = finalLiveInstances,
                            cases = cases,
                        )
                    ),
                    StandardCharsets.UTF_8,
                )
            } finally {
                ApplicationManager.getApplication().exit(true, true, false)
            }
        }

        private fun armTimeout() {
            timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    ApplicationManager.getApplication().invokeLater {
                        if (!finished) finishIncomplete("derived renderer probe timed out")
                    }
                },
                PROBE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }

        private fun request(
            id: String,
            kind: DerivedRendererKind,
            source: String,
            configJson: String,
        ) = DerivedRendererRuntimeRequest(
            requestId = id,
            kind = kind,
            source = source,
            configJson = configJson,
            identity = DerivedRendererIdentity(
                sourceGeneration = "source-$id",
                configGeneration = "config-$id",
            ),
        )

        private fun resultSummary(result: DerivedRendererRuntimeResult): String {
            val artifact = result.presentationArtifact
            val presentation = if (artifact == null) "none" else "${artifact.mediaType}:${artifact.width}x${artifact.height}"
            return "status=${result.status} mediaType=${result.mediaType ?: "none"} presentation=$presentation code=${result.code ?: "none"}"
        }
    }

    private data class CaseResult(
        val id: String,
        val outcome: String,
        val detail: String,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val selectedRuntime: String,
        val ideBuild: String,
        val verdict: String,
        val baselineLiveInstances: Int,
        val finalLiveInstances: Int,
        val cases: List<CaseResult>,
    )

    private const val PROBE_TIMEOUT_SECONDS = 60L
    private const val NAVIGATION_OBSERVATION_MILLIS = 500L
    private const val REPEATED_DISPOSAL_CYCLES = 3
    private const val MERMAID_CONFIG = """{"runtimeSettings":{"themeSource":"LIGHT","ideColorScheme":{"foreground":"#112233"},"ideDark":false,"mermaidSizeMode":"FIT_TO_VIEWPORT","mermaidZoomPercent":100}}"""
    private const val KATEX_INLINE_CONFIG = """{"displayMode":false,"displayDensity":"COMPACT","baseFontSizePx":16,"foreground":"#112233"}"""
    private const val KATEX_DISPLAY_CONFIG = """{"displayMode":true,"displayDensity":"COMFORTABLE","baseFontSizePx":16,"foreground":"#112233"}"""
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
