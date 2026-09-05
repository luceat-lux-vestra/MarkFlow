package com.algorist.markflow.runtime

import com.algorist.markflow.browser.MarkFlowWebviewResourceManager
import com.algorist.markflow.document.DocumentRevision
import com.algorist.markflow.sync.AttachmentId
import com.algorist.markflow.sync.AttachmentWireCodec
import com.algorist.markflow.sync.AttachmentWireMessage
import com.algorist.markflow.sync.RecoveryId
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic-only real-JCEF probe for #108.
 *
 * This code is inert unless `-Dmarkflow.jcefEnvelopeProbe=true` is supplied. It deliberately uses
 * [JcefSourceNativeRuntimeTransport] rather than a fake so request/response integrity, rejection,
 * timeout behaviour, and large host->web source delivery are observed in the launched IDE's real
 * JCEF runtime. Reported evidence contains only sizes/checksums/outcomes, never source payloads.
 */
internal object JcefTransportEnvelopeProbe {
    private const val ENABLED_PROPERTY = "markflow.jcefEnvelopeProbe"
    private const val REPORT_PROPERTY = "markflow.jcefEnvelopeProbe.report"
    private const val REQUIRED_MAX_UTF16 = 4 * 1024 * 1024
    private const val EXPLORATORY_UTF16 = 8 * 1024 * 1024
    private const val CASE_TIMEOUT_SECONDS = 45L
    private const val PROBE_PAGE = "jcef-envelope-probe.html"

    private val LOG = Logger.getInstance(JcefTransportEnvelopeProbe::class.java)
    private val started = AtomicBoolean(false)

    val enabled: Boolean
        get() = java.lang.Boolean.getBoolean(ENABLED_PROPERTY)

    fun start() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!enabled || !started.compareAndSet(false, true)) return

        val reportPath = System.getProperty(REPORT_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: Path.of("build", "jcef-envelope", "report.json").toAbsolutePath()
        ProbeSession(reportPath).start()
    }

    private class ProbeSession(
        private val reportPath: Path,
    ) {
        private val gson: Gson = Gson()
        private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()
        private val results = JsonArray()
        private val finished = AtomicBoolean(false)
        private var resourceAcquired = false
        private var transport: JcefSourceNativeRuntimeTransport? = null
        private var currentCase: ProbeCase? = null
        private var nextCaseIndex = 0

        private val cases: List<ProbeCase> = buildList {
            add(ProbeCase("reject-malformed", ProbeKind.REJECT, 0, required = true))
            listOf(1_024, 65_536, 1_048_576, REQUIRED_MAX_UTF16).forEach { size ->
                add(ProbeCase("request-$size", ProbeKind.REQUEST, size, required = true))
                add(ProbeCase("response-$size", ProbeKind.RESPONSE, size, required = true))
            }
            listOf(1_024, 1_048_576, REQUIRED_MAX_UTF16).forEach { size ->
                add(ProbeCase("bootstrap-$size", ProbeKind.BOOTSTRAP_DELIVERY, size, required = true))
                add(ProbeCase("recovery-$size", ProbeKind.RECOVERY_DELIVERY, size, required = true))
            }
            add(ProbeCase("request-$EXPLORATORY_UTF16", ProbeKind.REQUEST, EXPLORATORY_UTF16, required = false))
            add(ProbeCase("response-$EXPLORATORY_UTF16", ProbeKind.RESPONSE, EXPLORATORY_UTF16, required = false))
            add(ProbeCase("bootstrap-$EXPLORATORY_UTF16", ProbeKind.BOOTSTRAP_DELIVERY, EXPLORATORY_UTF16, required = false))
            add(ProbeCase("recovery-$EXPLORATORY_UTF16", ProbeKind.RECOVERY_DELIVERY, EXPLORATORY_UTF16, required = false))
        }

        fun start() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                val port = MarkFlowWebviewResourceManager.acquire()
                    ?: error("failed to acquire bundled webview resource server")
                resourceAcquired = true

                val createdTransport = JcefSourceNativeRuntimeTransport()
                transport = createdTransport
                createdTransport.setTransportMessageHandler(::handleTransportMessage)
                createdTransport.setReadinessMessageHandler(::handleProbeResult)
                createdTransport.setLoadStartHandler { }
                createdTransport.setLoadEndHandler {
                    ApplicationManager.getApplication().invokeLater {
                        if (finished.get()) return@invokeLater
                        try {
                            createdTransport.executeJavaScript(createdTransport.buildBridgeGlueScript())
                            runNextCase()
                        } catch (failure: Throwable) {
                            finishInfrastructureFailure("bridge initialization failed", failure)
                        }
                    }
                }
                createdTransport.loadUrl("http://127.0.0.1:$port/$PROBE_PAGE")
            } catch (failure: Throwable) {
                finishInfrastructureFailure("probe startup failed", failure)
            }
        }

        private fun handleTransportMessage(raw: String): String? {
            return try {
                val root = JsonParser.parseString(raw)
                if (!root.isJsonObject) return null
                val json = root.asJsonObject
                val kind = json.get("kind")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                val id = json.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                when (kind) {
                    "request" -> {
                        val payload = json.get("payload")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                        JsonObject().apply {
                            addProperty("id", id)
                            addProperty("length", payload.length)
                            addProperty("checksum", checksum(payload))
                        }.toString()
                    }
                    "response" -> {
                        val size = json.get("size")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
                        if (size < 0 || size > EXPLORATORY_UTF16) return null
                        makePayload(size)
                    }
                    "reject" -> null
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun handleProbeResult(raw: String): String? {
            if (finished.get()) return null
            val copy = raw
            ApplicationManager.getApplication().invokeLater {
                if (finished.get()) return@invokeLater
                consumeProbeResult(copy)
            }
            return "{\"type\":\"probeAck\"}"
        }

        private fun consumeProbeResult(raw: String) {
            ApplicationManager.getApplication().assertIsDispatchThread()
            val probeCase = currentCase ?: return
            val result = try {
                JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Throwable) {
                null
            }
            if (result == null || result.get("id")?.asString != probeCase.id) return

            val passed = when (probeCase.kind) {
                ProbeKind.REJECT -> result.get("outcome")?.asString == "expected-failure"
                ProbeKind.REQUEST -> verifyRequestResult(probeCase, result)
                ProbeKind.RESPONSE -> verifyLengthChecksum(probeCase, result, makePayload(probeCase.size))
                ProbeKind.BOOTSTRAP_DELIVERY,
                ProbeKind.RECOVERY_DELIVERY -> verifyDeliveryResult(probeCase, result)
            }

            appendResult(probeCase, if (passed) "PASS" else "FAIL", result)
            currentCase = null
            if (!passed && probeCase.required) {
                finish("BLOCKED — TRANSPORT REDESIGN REQUIRED")
                return
            }
            runNextCase()
        }

        private fun verifyRequestResult(probeCase: ProbeCase, result: JsonObject): Boolean {
            if (result.get("outcome")?.asString != "success") return false
            val responseRaw = result.get("response")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
            val response = try {
                JsonParser.parseString(responseRaw).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Throwable) {
                null
            } ?: return false
            val expected = makePayload(probeCase.size)
            return response.get("id")?.asString == probeCase.id &&
                response.get("length")?.asInt == expected.length &&
                response.get("checksum")?.asString == checksum(expected)
        }

        private fun verifyDeliveryResult(probeCase: ProbeCase, result: JsonObject): Boolean {
            if (result.get("outcome")?.asString != "success") return false
            val expectedRaw = deliveryPayload(probeCase)
            return result.get("responseLength")?.asInt == expectedRaw.length &&
                result.get("responseChecksum")?.asString == checksum(expectedRaw)
        }

        private fun verifyLengthChecksum(probeCase: ProbeCase, result: JsonObject, expected: String): Boolean {
            return result.get("outcome")?.asString == "success" &&
                result.get("responseLength")?.asInt == expected.length &&
                result.get("responseChecksum")?.asString == checksum(expected)
        }

        private fun runNextCase() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (finished.get() || currentCase != null) return
            if (nextCaseIndex >= cases.size) {
                // Finite measurements establish a concrete observed envelope, but the product has no
                // approved arbitrary full-document size cap. Do not silently turn this probe into a
                // production-cutover approval by extrapolating beyond what actually ran.
                finish("MEASURED — PRODUCTION CUTOVER STILL REQUIRES EXPLICIT ENVELOPE POLICY")
                return
            }

            val probeCase = cases[nextCaseIndex++]
            currentCase = probeCase
            scheduleTimeout(probeCase)
            try {
                when (probeCase.kind) {
                    ProbeKind.REQUEST,
                    ProbeKind.RESPONSE,
                    ProbeKind.REJECT -> runWebCase(probeCase)
                    ProbeKind.BOOTSTRAP_DELIVERY,
                    ProbeKind.RECOVERY_DELIVERY -> runDeliveryCase(probeCase)
                }
            } catch (failure: Throwable) {
                appendResult(probeCase, "FAIL", JsonObject().apply {
                    addProperty("outcome", "host-exception")
                    addProperty("exceptionClass", failure.javaClass.name)
                })
                currentCase = null
                if (probeCase.required) {
                    finish("BLOCKED — TRANSPORT REDESIGN REQUIRED")
                } else {
                    runNextCase()
                }
            }
        }

        private fun runWebCase(probeCase: ProbeCase) {
            val transport = transport ?: error("transport unavailable")
            val spec = JsonObject().apply {
                addProperty("id", probeCase.id)
                addProperty("kind", probeCase.kind.wireName)
                addProperty("size", probeCase.size)
            }.toString()
            transport.executeJavaScript(
                "window.__markflowEnvelopeRun(${gson.toJson(spec)});",
            )
        }

        private fun runDeliveryCase(probeCase: ProbeCase) {
            val transport = transport ?: error("transport unavailable")
            val raw = deliveryPayload(probeCase)
            val payloadLiteral = gson.toJson(raw)
            transport.executeJavaScript("window.__markflowEnvelopeDeliveryId = ${gson.toJson(probeCase.id)};")
            // Keep this mechanism intentionally identical to SourceNativeEditorRuntime.deliverToWeb:
            // JSON-string-literal escaping followed by one executeJavaScript call into the current realm.
            transport.executeJavaScript(
                """
                (function(payload) {
                    if (typeof window.__markflowSourceNativeReceive === 'function') {
                        window.__markflowSourceNativeReceive(payload);
                    }
                })($payloadLiteral);
                """.trimIndent(),
            )
        }

        private fun deliveryPayload(probeCase: ProbeCase): String {
            val source = makePayload(probeCase.size)
            val attachmentId = AttachmentId.of("jcef-envelope-probe")
            val revision = DocumentRevision(0)
            return when (probeCase.kind) {
                ProbeKind.BOOTSTRAP_DELIVERY -> AttachmentWireCodec.encode(
                    AttachmentWireMessage.BootstrapSnapshot(
                        attachmentId = attachmentId,
                        documentRevision = revision,
                        source = source,
                    ),
                )
                ProbeKind.RECOVERY_DELIVERY -> AttachmentWireCodec.encode(
                    AttachmentWireMessage.RecoverySnapshot(
                        attachmentId = attachmentId,
                        recoveryId = RecoveryId.of("jcef-envelope-probe-recovery"),
                        documentRevision = revision,
                        source = source,
                    ),
                )
                else -> error("not a delivery case")
            }
        }

        private fun scheduleTimeout(probeCase: ProbeCase) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    if (finished.get() || currentCase?.id != probeCase.id) return@schedule
                    ApplicationManager.getApplication().invokeLater {
                        if (finished.get() || currentCase?.id != probeCase.id) return@invokeLater
                        appendResult(probeCase, "TIMEOUT", JsonObject().apply {
                            addProperty("outcome", "timeout")
                            addProperty("timeoutSeconds", CASE_TIMEOUT_SECONDS)
                        })
                        currentCase = null
                        if (probeCase.required) {
                            finish("BLOCKED — TRANSPORT REDESIGN REQUIRED")
                        } else {
                            finish("MEASURED — EXPLORATORY BOUNDARY REACHED; CUTOVER NOT PROVEN")
                        }
                    }
                },
                CASE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }

        private fun appendResult(probeCase: ProbeCase, status: String, detail: JsonObject) {
            results.add(JsonObject().apply {
                addProperty("id", probeCase.id)
                addProperty("kind", probeCase.kind.wireName)
                addProperty("payloadUtf16Units", probeCase.size)
                addProperty("required", probeCase.required)
                addProperty("status", status)
                add("detail", detail.deepCopy())
            })
        }

        private fun finishInfrastructureFailure(message: String, failure: Throwable) {
            LOG.error("MARKFLOW_JCEF_ENVELOPE $message: ${failure.message}", failure)
            if (finished.get()) return
            results.add(JsonObject().apply {
                addProperty("id", "infrastructure")
                addProperty("status", "FAIL")
                addProperty("message", message)
                addProperty("exceptionClass", failure.javaClass.name)
            })
            finish("BLOCKED — JCEF RUNTIME EVIDENCE UNAVAILABLE")
        }

        private fun finish(conclusion: String) {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (!finished.compareAndSet(false, true)) return

            var cleanupFailure: Throwable? = null
            try {
                transport?.dispose()
            } catch (failure: Throwable) {
                cleanupFailure = failure
            } finally {
                transport = null
            }
            if (resourceAcquired) {
                try {
                    MarkFlowWebviewResourceManager.release()
                } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
                } finally {
                    resourceAcquired = false
                }
            }

            val report = JsonObject().apply {
                addProperty("schema", 1)
                addProperty("generatedAt", Instant.now().toString())
                addProperty("ideBuild", ApplicationInfo.getInstance().build.asString())
                addProperty("osName", System.getProperty("os.name"))
                addProperty("osVersion", System.getProperty("os.version"))
                addProperty("javaVersion", System.getProperty("java.version"))
                addProperty("requiredMaxUtf16Units", REQUIRED_MAX_UTF16)
                addProperty("exploratoryUtf16Units", EXPLORATORY_UTF16)
                addProperty("conclusion", if (cleanupFailure == null) conclusion else "BLOCKED — PROBE CLEANUP FAILED")
                cleanupFailure?.let { addProperty("cleanupExceptionClass", it.javaClass.name) }
                add("cases", results)
            }

            try {
                reportPath.parent?.let(Files::createDirectories)
                Files.writeString(reportPath, prettyGson.toJson(report))
                LOG.info("MARKFLOW_JCEF_ENVELOPE report=$reportPath conclusion=${report.get("conclusion").asString}")
            } catch (failure: Throwable) {
                LOG.error("MARKFLOW_JCEF_ENVELOPE failed to write report $reportPath", failure)
            } finally {
                // Dedicated probe runs are non-interactive. Exit only under the explicit system property;
                // normal MarkFlow editor sessions never execute this branch.
                ApplicationManager.getApplication().exit(true, false, false)
            }
        }
    }

    private enum class ProbeKind(val wireName: String) {
        REQUEST("request"),
        RESPONSE("response"),
        REJECT("reject"),
        BOOTSTRAP_DELIVERY("bootstrap"),
        RECOVERY_DELIVERY("recovery"),
    }

    private data class ProbeCase(
        val id: String,
        val kind: ProbeKind,
        val size: Int,
        val required: Boolean,
    )

    private fun makePayload(size: Int): String {
        require(size >= 0)
        val seed = "A\"\\\r\nΩ😀</script>"
        return buildString(size) {
            while (length < size) append(seed)
            if (length > size) delete(size, length)
        }
    }

    /** FNV-1a over UTF-16 code units, matching the probe page's charCodeAt/Math.imul implementation. */
    private fun checksum(value: String): String {
        var hash = 0x811c9dc5.toInt()
        value.forEach { char ->
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return hash.toUInt().toString(16).padStart(8, '0')
    }
}
