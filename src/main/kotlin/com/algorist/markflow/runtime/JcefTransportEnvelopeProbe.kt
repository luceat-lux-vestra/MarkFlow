package com.algorist.markflow.runtime

import com.algorist.markflow.document.DocumentRevision
import com.algorist.markflow.document.SourceEdit
import com.algorist.markflow.document.SourceEditCollection
import com.algorist.markflow.sync.AttachmentId
import com.algorist.markflow.sync.AttachmentProtocolBounds
import com.algorist.markflow.sync.AttachmentWireCodec
import com.algorist.markflow.sync.AttachmentWireDecodeResult
import com.algorist.markflow.sync.AttachmentWireMessage
import com.algorist.markflow.sync.RecoveryId
import com.algorist.markflow.sync.RequestId
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostic-only real-JCEF evidence runner for #109.
 *
 * The runner is unreachable unless [OUTPUT_PROPERTY] is explicitly set for an IDE process. It
 * exercises the production [JcefSourceNativeRuntimeTransport] directly, but never acquires a
 * DocumentSession and never participates in source/revision authority. Evidence is written as JSON
 * so CI can prove which payloads actually crossed JCEF rather than inferring behavior from fakes.
 */
internal object JcefTransportEnvelopeProbe {
    const val OUTPUT_PROPERTY = "markflow.jcefTransportProbe.output"

    private val started = AtomicBoolean(false)

    /** Returns true whenever probe mode was requested, including after another caller started it. */
    fun startIfRequested(): Boolean {
        val output = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true

        LOG.warn("JCEF envelope probe requested; output=$output")
        ApplicationManager.getApplication().invokeLater {
            Runner(Paths.get(output)).start()
        }
        return true
    }

    private class Runner(
        private val output: Path,
    ) {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private val cases = buildCases()
        private val results = mutableListOf<CaseResult>()
        private val lateMessages = AtomicInteger(0)
        private var transport: JcefSourceNativeRuntimeTransport? = null
        private var baselineTransportCount = 0
        private var caseIndex = 0
        private var currentCase: ProbeCase? = null
        private var timeout: ScheduledFuture<*>? = null
        private var finished = false
        private var pageReady = false

        fun start() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            baselineTransportCount = JcefSourceNativeRuntimeTransport.liveInstanceCount
            LOG.warn("JCEF envelope probe starting; baselineTransportCount=$baselineTransportCount")
            armTimeout("probe page did not become ready", PAGE_READY_TIMEOUT_SECONDS)

            try {
                val created = JcefSourceNativeRuntimeTransport()
                transport = created
                created.setTransportMessageHandler(::handleTransportMessage)
                created.setReadinessMessageHandler { "{\"type\":\"probeReady\"}" }
                created.setLoadEndHandler(::onAnyPageLoaded)
                // A distinct data URL avoids relying on whether JBCefBrowser emits a load event for
                // its constructor's initial about:blank realm. The diagnostic command has no
                // Swing editor surface, so explicitly realize the native browser after queuing the
                // target URL; production surfaces continue to realize through the UI hierarchy.
                created.loadUrl(PROBE_PAGE_URL)
                created.createImmediatelyForDiagnostics()
            } catch (failure: Throwable) {
                finishIncomplete("transport construction/load failed: ${failure.javaClass.name}: ${failure.message}")
            }
        }

        private fun onAnyPageLoaded() {
            if (finished || pageReady) return
            val currentTransport = transport ?: return finishIncomplete("transport missing after page load")
            try {
                // An initial about:blank load can race the explicit data URL. Install the bridge on
                // every observed realm, but only begin cases when JavaScript reports our marker URL.
                currentTransport.executeJavaScript(currentTransport.buildBridgeGlueScript())
                currentTransport.executeJavaScript(PROBE_HELPERS_SCRIPT)
                currentTransport.executeJavaScript(
                    "window.__mfProbePageReady(String(window.location.href));",
                )
            } catch (failure: Throwable) {
                finishIncomplete("bridge/helper installation failed: ${failure.javaClass.name}: ${failure.message}")
            }
        }

        private fun handleTransportMessage(raw: String): String? {
            val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
            if (parsed.string("type") == "mutationRequest") {
                return handleProductionMutationRequest(raw)
            }
            return when (parsed.string("op")) {
                "pageReady" -> {
                    handlePageReady(parsed)
                    "{\"accepted\":true}"
                }
                "request" -> handleWebRequest(parsed, raw)
                "response" -> handleSuccessResponseRequest(parsed)
                "report" -> {
                    handleReport(parsed)
                    "{\"accepted\":true}"
                }
                "reject" -> null
                "throw" -> error("intentional JCEF probe handler exception")
                "late" -> {
                    lateMessages.incrementAndGet()
                    "{\"accepted\":true}"
                }
                else -> null
            }
        }

        private fun handlePageReady(message: JsonObject) {
            val href = message.string("href") ?: return
            if (!href.contains(PROBE_PAGE_MARKER)) return
            ApplicationManager.getApplication().invokeLater {
                if (finished || pageReady) return@invokeLater
                pageReady = true
                cancelTimeout()
                LOG.warn("JCEF envelope probe page ready: $href")
                runNextCase()
            }
        }

        private fun runNextCase() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (finished) return
            if (caseIndex >= cases.size) {
                runLateAfterDisposeCase()
                return
            }

            val probeCase = cases[caseIndex++]
            currentCase = probeCase
            armTimeout("case ${probeCase.id} produced no terminal callback", CASE_TIMEOUT_SECONDS)

            val script = when (probeCase.kind) {
                Kind.WEB_REQUEST -> webRequestScript(probeCase)
                Kind.MUTATION_REQUEST -> productionMutationRequestScript(probeCase)
                Kind.SUCCESS_RESPONSE -> successResponseScript(probeCase)
                Kind.HOST_BOOTSTRAP -> hostSnapshotScript(probeCase, recovery = false)
                Kind.HOST_RECOVERY -> hostSnapshotScript(probeCase, recovery = true)
                Kind.REJECTION -> rejectionScript(probeCase)
                Kind.HANDLER_EXCEPTION -> handlerExceptionScript(probeCase)
            }

            try {
                transport?.executeJavaScript(script)
                    ?: completeCurrent(internalFailure(probeCase, "transport missing before executeJavaScript"))
            } catch (failure: Throwable) {
                completeCurrent(
                    internalFailure(
                        probeCase,
                        "executeJavaScript threw ${failure.javaClass.name}: ${failure.message}",
                    ),
                )
            }
        }

        private fun handleWebRequest(message: JsonObject, raw: String): String? {
            val probeCase = currentCase ?: return null
            if (probeCase.kind != Kind.WEB_REQUEST || message.string("caseId") != probeCase.id) return null
            val value = message.string("payload") ?: return null
            if (value.length != probeCase.size || fnv1aUtf16(value) != expectedHash(probeCase)) return null
            return gson.toJson(
                mapOf(
                    "accepted" to true,
                    "payloadUtf16Units" to value.length,
                    "payloadHash" to fnv1aUtf16(value),
                    "wireChars" to raw.length,
                ),
            )
        }

        private fun handleProductionMutationRequest(raw: String): String? {
            val probeCase = currentCase ?: return null
            if (probeCase.kind != Kind.MUTATION_REQUEST) return null
            val decoded = AttachmentWireCodec.decode(raw)
            val message = (decoded as? AttachmentWireDecodeResult.Decoded)?.message as? AttachmentWireMessage.MutationRequest
                ?: return null
            if (message.attachmentId != PROBE_ATTACHMENT_ID) return null
            if (message.requestId != RequestId.of(probeCase.id)) return null
            if (message.baseDocumentRevision != DocumentRevision.INITIAL) return null

            val edits = message.edits.edits
            if (edits.size != AttachmentProtocolBounds.MAX_EDIT_COUNT) return null
            if (edits.sumOf { it.replacement.length.toLong() } != probeCase.size.toLong()) return null

            val chunkSize = mutationChunkSize(probeCase)
            val expectedChunk = payload(chunkSize, complex = true)
            val exactEdits = edits.withIndex().all { (index, edit) ->
                edit.startOffset == index &&
                    edit.endOffset == index &&
                    edit.replacement == expectedChunk
            }
            if (!exactEdits) return null

            val expectedWireChars = productionMutationWire(probeCase).length
            if (raw.length != expectedWireChars) return null

            return gson.toJson(
                mapOf(
                    "accepted" to true,
                    "editCount" to edits.size,
                    "insertedUtf16Units" to probeCase.size,
                    "wireChars" to raw.length,
                ),
            )
        }

        private fun handleSuccessResponseRequest(message: JsonObject): String? {
            val probeCase = currentCase ?: return null
            if (probeCase.kind != Kind.SUCCESS_RESPONSE || message.string("caseId") != probeCase.id) return null
            return payload(probeCase.size, probeCase.complex)
        }

        private fun handleReport(message: JsonObject) {
            val caseId = message.string("caseId") ?: return
            ApplicationManager.getApplication().invokeLater {
                val probeCase = currentCase ?: return@invokeLater
                if (probeCase.id != caseId || finished) return@invokeLater

                val callbackOutcome = message.string("outcome") ?: "missing"
                val observedLength = message.int("length")
                val observedHash = message.long("hash")
                val detail = message.string("detail")
                val passed = when (probeCase.kind) {
                    Kind.WEB_REQUEST,
                    Kind.MUTATION_REQUEST,
                    -> callbackOutcome == "success"
                    Kind.SUCCESS_RESPONSE,
                    Kind.HOST_BOOTSTRAP,
                    Kind.HOST_RECOVERY,
                    -> callbackOutcome == "success" &&
                        observedLength == probeCase.size &&
                        observedHash == expectedHash(probeCase)
                    Kind.REJECTION,
                    Kind.HANDLER_EXCEPTION,
                    -> callbackOutcome == "failure"
                }

                val envelope = envelopeMetrics(probeCase)
                completeCurrent(
                    CaseResult(
                        id = probeCase.id,
                        kind = probeCase.kind.name,
                        payloadUtf16Units = probeCase.size,
                        encodedJsonChars = envelope.encodedJsonChars,
                        outerJavaScriptLiteralChars = envelope.outerJavaScriptLiteralChars,
                        requiredForCurrentEnvelope = probeCase.required,
                        outcome = if (passed) "PASS" else "FAIL",
                        observedUtf16Units = observedLength,
                        observedHash = observedHash,
                        detail = detail ?: "callback=$callbackOutcome",
                    ),
                )
            }
        }

        private fun completeCurrent(result: CaseResult) {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (currentCase?.id != result.id || finished) return
            cancelTimeout()
            results += result
            LOG.warn("JCEF envelope probe ${result.id}: ${result.outcome}")
            currentCase = null
            runNextCase()
        }

        private fun runLateAfterDisposeCase() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            cancelTimeout()
            val currentTransport = transport ?: return finishIncomplete("transport missing before late-dispose case")
            val before = JcefSourceNativeRuntimeTransport.liveInstanceCount
            val script = """
                window.setTimeout(function() {
                    window.__markflowSourceNativeSend(
                        JSON.stringify({op: 'late', caseId: 'late-after-dispose'}),
                        function() {},
                        function() {}
                    );
                }, 150);
            """.trimIndent()

            try {
                currentTransport.executeJavaScript(script)
                currentTransport.dispose()
                transport = null
            } catch (failure: Throwable) {
                return finishIncomplete("late-dispose setup failed: ${failure.javaClass.name}: ${failure.message}")
            }

            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    ApplicationManager.getApplication().invokeLater {
                        if (finished) return@invokeLater
                        val after = JcefSourceNativeRuntimeTransport.liveInstanceCount
                        results += CaseResult(
                            id = "late-after-dispose",
                            kind = "LIFECYCLE",
                            payloadUtf16Units = 0,
                            encodedJsonChars = 0,
                            outerJavaScriptLiteralChars = 0,
                            requiredForCurrentEnvelope = true,
                            outcome = if (lateMessages.get() == 0 && after == baselineTransportCount) "PASS" else "FAIL",
                            detail = "lateMessages=${lateMessages.get()}, liveBeforeDispose=$before, liveAfterDispose=$after, baseline=$baselineTransportCount",
                        )
                        runRepeatedConstructionDisposalEvidence()
                    }
                },
                LATE_DISPOSE_OBSERVATION_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun runRepeatedConstructionDisposalEvidence() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            val before = JcefSourceNativeRuntimeTransport.liveInstanceCount
            var failureDetail: String? = null
            repeat(REPEATED_DISPOSAL_CYCLES) { index ->
                if (failureDetail != null) return@repeat
                try {
                    JcefSourceNativeRuntimeTransport().dispose()
                } catch (failure: Throwable) {
                    failureDetail = "cycle=$index ${failure.javaClass.name}: ${failure.message}"
                }
            }
            val after = JcefSourceNativeRuntimeTransport.liveInstanceCount
            results += CaseResult(
                id = "repeated-create-dispose",
                kind = "LIFECYCLE",
                payloadUtf16Units = 0,
                encodedJsonChars = 0,
                outerJavaScriptLiteralChars = 0,
                requiredForCurrentEnvelope = true,
                outcome = if (failureDetail == null && before == after && after == baselineTransportCount) "PASS" else "FAIL",
                detail = failureDetail ?: "cycles=$REPEATED_DISPOSAL_CYCLES, liveBefore=$before, liveAfter=$after, baseline=$baselineTransportCount",
            )
            finish()
        }

        private fun armTimeout(detail: String, seconds: Long) {
            cancelTimeout()
            timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    ApplicationManager.getApplication().invokeLater {
                        if (finished) return@invokeLater
                        val active = currentCase
                        if (active?.kind == Kind.HANDLER_EXCEPTION) {
                            val envelope = envelopeMetrics(active)
                            completeCurrent(
                                CaseResult(
                                    id = active.id,
                                    kind = active.kind.name,
                                    payloadUtf16Units = active.size,
                                    encodedJsonChars = envelope.encodedJsonChars,
                                    outerJavaScriptLiteralChars = envelope.outerJavaScriptLiteralChars,
                                    requiredForCurrentEnvelope = active.required,
                                    outcome = "FAIL",
                                    detail = "$detail after ${seconds}s; handler exception produced no JS failure callback",
                                ),
                            )
                        } else {
                            finishIncomplete("$detail after ${seconds}s")
                        }
                    }
                },
                seconds,
                TimeUnit.SECONDS,
            )
        }

        private fun cancelTimeout() {
            timeout?.cancel(false)
            timeout = null
        }

        private fun finishIncomplete(detail: String) {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (finished) return
            LOG.warn("JCEF transport envelope probe incomplete: $detail")
            val active = currentCase
            results += CaseResult(
                id = active?.id ?: "probe-internal",
                kind = active?.kind?.name ?: "INTERNAL",
                payloadUtf16Units = active?.size ?: 0,
                encodedJsonChars = active?.let(::envelopeMetrics)?.encodedJsonChars ?: 0,
                outerJavaScriptLiteralChars = active?.let(::envelopeMetrics)?.outerJavaScriptLiteralChars ?: 0,
                requiredForCurrentEnvelope = true,
                outcome = "INCOMPLETE",
                detail = detail,
            )
            finish()
        }

        private fun finish() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (finished) return
            finished = true
            cancelTimeout()

            var disposalFailure: String? = null
            transport?.let { currentTransport ->
                try {
                    currentTransport.dispose()
                } catch (failure: Throwable) {
                    disposalFailure = "${failure.javaClass.name}: ${failure.message}"
                }
            }
            transport = null

            if (disposalFailure != null) {
                results += CaseResult(
                    id = "final-dispose",
                    kind = "LIFECYCLE",
                    payloadUtf16Units = 0,
                    encodedJsonChars = 0,
                    outerJavaScriptLiteralChars = 0,
                    requiredForCurrentEnvelope = true,
                    outcome = "INCOMPLETE",
                    detail = disposalFailure,
                )
            }

            val evidenceComplete = results.none { it.outcome == "TIMEOUT" || it.outcome == "INCOMPLETE" }
            val requiredEnvelopePass = evidenceComplete && results
                .filter(CaseResult::requiredForCurrentEnvelope)
                .all { it.outcome == "PASS" }
            val verdict = when {
                !evidenceComplete -> "INCOMPLETE"
                requiredEnvelopePass -> "COMPLETE_REQUIRED_ENVELOPE_PASS"
                else -> "COMPLETE_CUTOVER_BLOCKED"
            }

            val evidence = Evidence(
                schemaVersion = 2,
                generatedAt = Instant.now().toString(),
                ideBuild = ApplicationInfo.getInstance().build.asString(),
                javaRuntime = System.getProperty("java.runtime.version") ?: "unknown",
                osName = System.getProperty("os.name") ?: "unknown",
                osVersion = System.getProperty("os.version") ?: "unknown",
                osArch = System.getProperty("os.arch") ?: "unknown",
                baselineTransportCount = baselineTransportCount,
                finalTransportCount = JcefSourceNativeRuntimeTransport.liveInstanceCount,
                verdict = verdict,
                cutoverAuthorized = false,
                cases = results.toList(),
            )

            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(output, gson.toJson(evidence), StandardCharsets.UTF_8)
                LOG.warn("JCEF transport envelope probe wrote $output with verdict=$verdict")
            } catch (failure: Throwable) {
                LOG.error("Failed to write JCEF transport envelope evidence to $output", failure)
            } finally {
                ApplicationManager.getApplication().exit(true, true, false)
            }
        }

        private fun internalFailure(probeCase: ProbeCase, detail: String): CaseResult {
            val envelope = envelopeMetrics(probeCase)
            return CaseResult(
                id = probeCase.id,
                kind = probeCase.kind.name,
                payloadUtf16Units = probeCase.size,
                encodedJsonChars = envelope.encodedJsonChars,
                outerJavaScriptLiteralChars = envelope.outerJavaScriptLiteralChars,
                requiredForCurrentEnvelope = probeCase.required,
                outcome = "INCOMPLETE",
                detail = detail,
            )
        }

        private fun envelopeMetrics(probeCase: ProbeCase): EnvelopeMetrics = when (probeCase.kind) {
            Kind.MUTATION_REQUEST -> EnvelopeMetrics(productionMutationWire(probeCase).length, 0)
            Kind.HOST_BOOTSTRAP,
            Kind.HOST_RECOVERY,
            -> {
                val raw = snapshotWire(probeCase, probeCase.kind == Kind.HOST_RECOVERY)
                EnvelopeMetrics(raw.length, gson.toJson(raw).length)
            }
            else -> {
                val value = payload(probeCase.size, probeCase.complex)
                EnvelopeMetrics(gson.toJson(value).length, 0)
            }
        }

        private fun expectedHash(probeCase: ProbeCase): Long = fnv1aUtf16(payload(probeCase.size, probeCase.complex))

        private fun webRequestScript(probeCase: ProbeCase): String = """
            (function() {
                const caseId = ${gson.toJson(probeCase.id)};
                const payload = window.__mfProbePayload(${probeCase.size}, ${probeCase.complex});
                window.__markflowSourceNativeSend(
                    JSON.stringify({op: 'request', caseId: caseId, payload: payload}),
                    function(response) {
                        window.__mfProbeReport({caseId: caseId, outcome: 'success', detail: response});
                    },
                    function(code, message) {
                        window.__mfProbeReport({caseId: caseId, outcome: 'failure', detail: String(code) + ':' + String(message)});
                    }
                );
            })();
        """.trimIndent()

        private fun productionMutationRequestScript(probeCase: ProbeCase): String {
            val chunkSize = mutationChunkSize(probeCase)
            return """
                (function() {
                    const caseId = ${gson.toJson(probeCase.id)};
                    const inserted = window.__mfProbePayload($chunkSize, true);
                    const edits = [];
                    for (let index = 0; index < ${AttachmentProtocolBounds.MAX_EDIT_COUNT}; index += 1) {
                        edits.push({from: index, to: index, inserted: inserted});
                    }
                    const raw = JSON.stringify({
                        type: 'mutationRequest',
                        attachmentId: ${gson.toJson(PROBE_ATTACHMENT_ID.value)},
                        requestId: caseId,
                        baseDocumentRevision: '0',
                        edits: edits
                    });
                    window.__markflowSourceNativeSend(
                        raw,
                        function(response) {
                            window.__mfProbeReport({caseId: caseId, outcome: 'success', detail: response});
                        },
                        function(code, message) {
                            window.__mfProbeReport({caseId: caseId, outcome: 'failure', detail: String(code) + ':' + String(message)});
                        }
                    );
                })();
            """.trimIndent()
        }

        private fun productionMutationWire(probeCase: ProbeCase): String {
            val inserted = payload(mutationChunkSize(probeCase), complex = true)
            val edits = SourceEditCollection.of(
                (0 until AttachmentProtocolBounds.MAX_EDIT_COUNT).map { index ->
                    SourceEdit(index, index, inserted)
                },
            )
            return AttachmentWireCodec.encode(
                AttachmentWireMessage.MutationRequest(
                    attachmentId = PROBE_ATTACHMENT_ID,
                    requestId = RequestId.of(probeCase.id),
                    baseDocumentRevision = DocumentRevision.INITIAL,
                    edits = edits,
                ),
            )
        }

        private fun mutationChunkSize(probeCase: ProbeCase): Int {
            require(probeCase.size % AttachmentProtocolBounds.MAX_EDIT_COUNT == 0) {
                "mutation envelope size must divide evenly across maximum edit count"
            }
            return probeCase.size / AttachmentProtocolBounds.MAX_EDIT_COUNT
        }

        private fun successResponseScript(probeCase: ProbeCase): String = """
            (function() {
                const caseId = ${gson.toJson(probeCase.id)};
                window.__markflowSourceNativeSend(
                    JSON.stringify({op: 'response', caseId: caseId}),
                    function(response) {
                        window.__mfProbeReport({
                            caseId: caseId,
                            outcome: 'success',
                            length: response.length,
                            hash: window.__mfProbeHash(response)
                        });
                    },
                    function(code, message) {
                        window.__mfProbeReport({caseId: caseId, outcome: 'failure', detail: String(code) + ':' + String(message)});
                    }
                );
            })();
        """.trimIndent()

        /** Mirrors SourceNativeEditorRuntime.deliverToWeb: wire JSON first, then outer JS literal. */
        private fun hostSnapshotScript(probeCase: ProbeCase, recovery: Boolean): String {
            val rawWire = snapshotWire(probeCase, recovery)
            val payloadLiteral = gson.toJson(rawWire)
            return """
                (function(raw) {
                    try {
                        const message = JSON.parse(raw);
                        const source = message.source;
                        window.__mfProbeReport({
                            caseId: ${gson.toJson(probeCase.id)},
                            outcome: 'success',
                            length: source.length,
                            hash: window.__mfProbeHash(source),
                            detail: 'wireChars=' + raw.length + ',outerLiteralChars=${payloadLiteral.length}'
                        });
                    } catch (error) {
                        window.__mfProbeReport({
                            caseId: ${gson.toJson(probeCase.id)},
                            outcome: 'failure',
                            detail: String(error)
                        });
                    }
                })($payloadLiteral);
            """.trimIndent()
        }

        private fun snapshotWire(probeCase: ProbeCase, recovery: Boolean): String {
            val source = payload(probeCase.size, probeCase.complex)
            val message = if (recovery) {
                AttachmentWireMessage.RecoverySnapshot(
                    attachmentId = PROBE_ATTACHMENT_ID,
                    recoveryId = PROBE_RECOVERY_ID,
                    documentRevision = DocumentRevision.INITIAL,
                    source = source,
                )
            } else {
                AttachmentWireMessage.BootstrapSnapshot(
                    attachmentId = PROBE_ATTACHMENT_ID,
                    documentRevision = DocumentRevision.INITIAL,
                    source = source,
                )
            }
            return AttachmentWireCodec.encode(message)
        }

        private fun rejectionScript(probeCase: ProbeCase): String = """
            (function() {
                const caseId = ${gson.toJson(probeCase.id)};
                window.__markflowSourceNativeSend(
                    JSON.stringify({op: 'reject', caseId: caseId}),
                    function() {
                        window.__mfProbeReport({caseId: caseId, outcome: 'success', detail: 'unexpected success callback'});
                    },
                    function(code, message) {
                        window.__mfProbeReport({caseId: caseId, outcome: 'failure', detail: String(code) + ':' + String(message)});
                    }
                );
            })();
        """.trimIndent()

        private fun handlerExceptionScript(probeCase: ProbeCase): String = """
            (function() {
                const caseId = ${gson.toJson(probeCase.id)};
                window.__markflowSourceNativeSend(
                    JSON.stringify({op: 'throw', caseId: caseId}),
                    function() {
                        window.__mfProbeReport({caseId: caseId, outcome: 'success', detail: 'unexpected success callback'});
                    },
                    function(code, message) {
                        window.__mfProbeReport({caseId: caseId, outcome: 'failure', detail: String(code) + ':' + String(message)});
                    }
                );
            })();
        """.trimIndent()
    }

    private enum class Kind {
        WEB_REQUEST,
        MUTATION_REQUEST,
        SUCCESS_RESPONSE,
        HOST_BOOTSTRAP,
        HOST_RECOVERY,
        REJECTION,
        HANDLER_EXCEPTION,
    }

    private data class ProbeCase(
        val id: String,
        val kind: Kind,
        val size: Int,
        val complex: Boolean = false,
        val required: Boolean = true,
    )

    private data class EnvelopeMetrics(
        val encodedJsonChars: Int,
        val outerJavaScriptLiteralChars: Int,
    )

    private data class CaseResult(
        val id: String,
        val kind: String,
        val payloadUtf16Units: Int,
        val encodedJsonChars: Int,
        val outerJavaScriptLiteralChars: Int,
        val requiredForCurrentEnvelope: Boolean,
        val outcome: String,
        val observedUtf16Units: Int? = null,
        val observedHash: Long? = null,
        val detail: String? = null,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val generatedAt: String,
        val ideBuild: String,
        val javaRuntime: String,
        val osName: String,
        val osVersion: String,
        val osArch: String,
        val baselineTransportCount: Int,
        val finalTransportCount: Int,
        val verdict: String,
        val cutoverAuthorized: Boolean,
        val cases: List<CaseResult>,
    )

    private fun buildCases(): List<ProbeCase> = listOf(
        ProbeCase("request-ascii-64k", Kind.WEB_REQUEST, 64 * 1024),
        ProbeCase("request-complex-64k", Kind.WEB_REQUEST, 64 * 1024, complex = true),
        ProbeCase("request-ascii-1m", Kind.WEB_REQUEST, 1024 * 1024),
        ProbeCase("request-ascii-4m", Kind.WEB_REQUEST, 4 * 1024 * 1024),
        ProbeCase(
            "mutation-max-envelope",
            Kind.MUTATION_REQUEST,
            AttachmentProtocolBounds.MAX_INSERTED_UTF16_CODE_UNITS,
            complex = true,
        ),
        ProbeCase("response-ascii-4m", Kind.SUCCESS_RESPONSE, 4 * 1024 * 1024),
        ProbeCase("bootstrap-complex-4m", Kind.HOST_BOOTSTRAP, 4 * 1024 * 1024, complex = true),
        ProbeCase("recovery-complex-4m", Kind.HOST_RECOVERY, 4 * 1024 * 1024, complex = true),
        ProbeCase("request-oversize", Kind.WEB_REQUEST, 4 * 1024 * 1024 + 256 * 1024, required = false),
        ProbeCase("response-oversize", Kind.SUCCESS_RESPONSE, 4 * 1024 * 1024 + 256 * 1024, required = false),
        ProbeCase("recovery-oversize", Kind.HOST_RECOVERY, 8 * 1024 * 1024, complex = true, required = false),
        ProbeCase("handler-rejection", Kind.REJECTION, 0),
        ProbeCase("handler-exception", Kind.HANDLER_EXCEPTION, 0),
    )

    private fun payload(size: Int, complex: Boolean): String {
        if (size == 0) return ""
        if (!complex) return "x".repeat(size)

        val unit = "A한🙂\"\\\n"
        val builder = StringBuilder(size)
        while (builder.length + unit.length <= size) builder.append(unit)
        while (builder.length < size) builder.append('x')
        return builder.toString()
    }

    private fun fnv1aUtf16(value: String): Long {
        var hash = 0x811c9dc5.toInt()
        for (character in value) {
            hash = (hash xor character.code) * 0x01000193
        }
        return hash.toUInt().toLong()
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.int(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt
    private fun JsonObject.long(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }?.asLong

    private const val PAGE_READY_TIMEOUT_SECONDS = 45L
    private const val CASE_TIMEOUT_SECONDS = 30L
    private const val LATE_DISPOSE_OBSERVATION_MILLIS = 500L
    private const val REPEATED_DISPOSAL_CYCLES = 3
    private const val PROBE_PAGE_MARKER = "markflow-jcef-envelope-probe"
    private const val PROBE_PAGE_URL =
        "data:text/html;charset=utf-8,%3Chtml%3E%3Cbody%20id%3D%22markflow-jcef-envelope-probe%22%3Eprobe%3C%2Fbody%3E%3C%2Fhtml%3E#markflow-jcef-envelope-probe"

    private val PROBE_ATTACHMENT_ID = AttachmentId.of("jcef-envelope-probe-attachment")
    private val PROBE_RECOVERY_ID = RecoveryId.of("jcef-envelope-probe-recovery")
    private val LOG = Logger.getInstance(JcefTransportEnvelopeProbe::class.java)

    private val PROBE_HELPERS_SCRIPT = """
        window.__mfProbeHash = function(value) {
            let hash = 0x811c9dc5 >>> 0;
            for (let index = 0; index < value.length; index += 1) {
                hash ^= value.charCodeAt(index);
                hash = Math.imul(hash, 0x01000193) >>> 0;
            }
            return hash >>> 0;
        };
        window.__mfProbePayload = function(size, complex) {
            if (!complex) return 'x'.repeat(size);
            const unit = 'A' + String.fromCharCode(0xD55C) + String.fromCodePoint(0x1F642) + '"' + '\\' + '\n';
            let value = '';
            while (value.length + unit.length <= size) value += unit;
            while (value.length < size) value += 'x';
            return value;
        };
        window.__mfProbeReport = function(report) {
            window.__markflowSourceNativeSend(
                JSON.stringify(Object.assign({op: 'report'}, report)),
                function() {},
                function() {}
            );
        };
        window.__mfProbePageReady = function(href) {
            window.__markflowSourceNativeSend(
                JSON.stringify({op: 'pageReady', href: href}),
                function() {},
                function() {}
            );
        };
    """.trimIndent()
}
