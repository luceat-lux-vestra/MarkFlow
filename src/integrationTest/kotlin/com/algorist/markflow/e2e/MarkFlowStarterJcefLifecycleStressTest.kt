package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Rectangle
import java.awt.Robot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #353 diagnostic only: isolate repeated real IntelliJ Markdown Preview
 * create/paint/hide/native-close from the 89-case differential corpus.
 *
 * Opt in explicitly:
 * MARKFLOW_JCEF_LIFECYCLE_STRESS=1 ./gradlew integrationTest -x buildPlugin
 *   -PplatformVersion=2026.2.3 -PstarterPackagedPlugin=true
 *   --tests com.algorist.markflow.e2e.MarkFlowStarterJcefLifecycleStressTest
 *
 * Use the same pinned JCEF/AppArmor/Xvfb/plugin-package setup as the existing
 * Full Differential worker. This test is not a visual acceptance or release gate.
 * No retry, sharding, native-close timeout expansion, or corpus modification.
 */
class MarkFlowStarterJcefLifecycleStressTest {
    private val fileName = "JCEF-LIFECYCLE-STRESS.md"
    private val renderedTextAnchorA = "One plain Markdown document is reopened for every iteration."
    private val renderedTextAnchorB = "The fourth simple paragraph keeps the visible vertical span nontrivial."
    private val fixture = """
        # Minimal reference preview lifecycle

        One plain Markdown document is reopened for every iteration.
        The rendered text must become visible before the browser is closed.

        ## Stable sample

        Repeated text exercises the exact same Markdown Preview content.
        There are no Mermaid diagrams, images, tables, or external resources.
        Only the Preview creation and disposal sequence changes over time.

        The first simple paragraph has sufficient width to be captured.
        The second simple paragraph supplies another visible line of content.
        The third simple paragraph helps distinguish text from a blank panel.
        The fourth simple paragraph keeps the visible vertical span nontrivial.
    """.trimIndent() + "\n"

    @Test
    @EnabledIfEnvironmentVariable(named = "MARKFLOW_JCEF_LIFECYCLE_STRESS", matches = "1")
    fun repeatedBundledPreviewCloseCompletesInOneIdeSession() {
        val root = Path.of("").toAbsolutePath().normalize()
        val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")) {
            "Starter requires path.to.build.plugin"
        })
        val version = requireNotNull(System.getProperty("markflow.test.platformVersion")) {
            "Starter requires markflow.test.platformVersion"
        }
        check(version == "2026.2.3") { "Use the pinned Full Differential platform" }
        val renderMode = System.getenv("MARKFLOW_JCEF_STRESS_RENDER_MODE") ?: "osr"
        require(renderMode == "osr" || renderMode == "windowed")
        val executionMode = System.getenv("MARKFLOW_JCEF_STRESS_EXECUTION_MODE") ?: "remote"
        require(executionMode == "remote" || executionMode == "in_process")
        val initialOpenMode = System.getenv("MARKFLOW_JCEF_STRESS_OPEN_MODE") ?: "editor_only_roundtrip"
        require(initialOpenMode == "editor_only_roundtrip" ||
            initialOpenMode == "direct_split" || initialOpenMode == "hidden_attach_then_split")
        val splitOpen = initialOpenMode == "direct_split" ||
            initialOpenMode == "hidden_attach_then_split"
        // 2-iteration preflight is opt-in for either split counterfactual.
        // The default remains
        // the full 50-iteration lifecycle stress contract; no arbitrary count.
        val iterationMode = System.getenv("MARKFLOW_JCEF_STRESS_ITERATION_MODE") ?: "stress_50"
        require(iterationMode == "stress_50" || iterationMode == "preflight_2") {
            "Unsupported JCEF lifecycle diagnostic iteration mode: $iterationMode"
        }
        require(iterationMode != "preflight_2" || splitOpen) {
            "Two-iteration preflight requires a split counterfactual opening mode"
        }
        val iterations = if (iterationMode == "preflight_2") 2 else 50
        val expectedRemote = executionMode == "remote"
        val expectedOsr = renderMode == "osr"
        require(!expectedRemote || expectedOsr) {
            "Pinned IDEA 2026.2.3 forces OSR for out-of-process JCEF"
        }
        val sourceHead = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(root.toFile()).start().let { process ->
                val sha = process.inputStream.bufferedReader().readText().trim()
                check(process.waitFor() == 0 && sha.matches(Regex("[0-9a-f]{40}")))
                sha
            }
        System.getenv("STARTER_SOURCE_SHA")?.takeIf(String::isNotBlank)?.let {
            check(it == sourceHead) { "Diagnostic plugin checkout is not exact-source" }
        }

        val output = root.resolve("build/e2e-evidence/derived-content/jcef-lifecycle-stress")
        Files.createDirectories(output)
        Files.writeString(output.resolve("run-contract.txt"),
            "schema=markflow-jcef-lifecycle-stress/v1\n" +
                "source_head=$sourceHead\nide_version=$version\n" +
                "mode=$iterationMode\niterations=$iterations\nfixture=$fileName\n" +
                "scope=diagnostic-only-not-full-differential-acceptance\n" +
                "render_mode=$renderMode\nexecution_mode=$executionMode\n" +
                "initial_open_mode=$initialOpenMode\n" +
                "expected_jcef_remote=$expectedRemote\nretries=0\n", StandardCharsets.UTF_8)

        val sandbox = Files.createTempDirectory("markflow-353-jcef-stress-")
        val project = sandbox.resolve("project")
        var completed = 0
        var deferredFailure: Throwable? = null
        try {
            val base = root.resolve("src/integrationTest/resources/projects/markdown-smoke")
            check(base.toFile().copyRecursively(project.toFile(), overwrite = true))
            val fixturePath = project.resolve(fileName)
            check(!Files.exists(fixturePath)) { "Lifecycle fixture name collision" }
            val fixtureBytes = fixture.toByteArray(StandardCharsets.UTF_8)
            Files.write(fixturePath, fixtureBytes)

            Starter.newContext(
                testName = "markflow-353-minimal-jcef-lifecycle-stress",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project))
                    .useRelease(version)
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("ide.browser.jcef.jsQueryPoolSize", "10000")
                // Default Remote JCEF must use IDEA's untouched production setting.
                // The in-process variant is a separate, explicitly requested experiment.
                if (!expectedRemote) addSystemProperty("jcef.remote.enabled", false)
                if (!expectedOsr) addSystemProperty("ide.browser.jcef.markdownView.osr.enabled", false)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("sun.java2d.uiScale", "1.0")
                addSystemProperty("ide.ui.scale", "1.0")
            }.runIdeWithDriver(runTimeout = 35.minutes).useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val preview = driver.utility(JcefStressPreviewRemote::class)
                val chrome = driver.utility(JcefStressVisualRemote::class)
                driver.withContext(OnDispatcher.EDT) {
                    chrome.prepareEditorChromeBeforeOpen()
                }
                val jcefRuntime = driver.withContext(OnDispatcher.EDT) {
                    preview.jcefRuntimeEvidence()
                }
                Files.writeString(output.resolve("jcef-runtime-evidence.txt"),
                    jcefRuntime, StandardCharsets.UTF_8)
                check(jcefRuntime.contains("idea_jcef_remote_enabled=$expectedRemote\n") &&
                    (expectedRemote ||
                        jcefRuntime.contains("idea_jcef_remote_property=false\n"))) {
                    "JCEF execution mode differs from $executionMode:\n$jcefRuntime"
                }
                // Prove the native trace settings reached the IDE process that
                // launches cef_server; don't confuse missing logs with fast RPC.
                if (expectedRemote) {
                    check(jcefRuntime.contains("idea_cef_server_trace_flag=true\n") &&
                        jcefRuntime.contains("idea_cef_server_log_level=trace\n") &&
                        jcefRuntime.contains("idea_cef_server_log_path=" +
                            output.resolve("cef-server-trace.log").toAbsolutePath().normalize() +
                            "\n")) {
                        "Remote JCEF native load trace was not configured in the IDE:\n$jcefRuntime"
                    }
                }
                val robot = Robot()
                val nonblank = MarkFlowStarterDifferentialPreviewSmokeTest()
                for (number in 1..iterations) {
                    val iteration = number.toString().padStart(3, '0')
                    val dest = output.resolve("iteration-$iteration")
                    Files.createDirectories(dest)
                    fun record(phase: String, evidence: String = "") {
                        Files.writeString(dest.resolve("lifecycle.txt"),
                            "phase=$phase\n" + evidence,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        Files.writeString(output.resolve("progress.tsv"),
                            "$iteration\t$phase\t" + System.currentTimeMillis() + "\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    }

                    record("open-start")
                    try {
                        // Close the whole FileEditor after every iteration so the
                        // next open necessarily exercises the bundled browser path.
                        val opened = if (splitOpen) {
                            markFlow.openMarkdownWithoutEditorOnlyRoundTrip(fileName)
                        } else {
                            markFlow.openMarkdown(fileName)
                        }
                        markFlow.assertProductionProjectionAttached(opened)
                        val remoteEditor = opened.editor
                        check(markFlow.source(opened) == fixture)
                        val stamp = markFlow.modificationStamp(opened)
                        record("source-opened", "initial_open_mode=$initialOpenMode\n")
                        // Capture actual per-open state BEFORE a panel wait or layout change.
                        // In particular, show whether the prior SHOW_EDITOR layout was restored.
                        val openedPreviewState = driver.withContext(OnDispatcher.EDT) {
                            preview.previewLifecycleEvidence(remoteEditor)
                        }
                        record("source-opened-preview-state", openedPreviewState)
                        var initialPanelIdentity: String? = null
                        var hiddenPanelIdentity: String? = null
                        if (initialOpenMode == "hidden_attach_then_split") {
                            // Distinguishing counterfactual: wait for the platform's
                            // initialization attach while the Preview is still hidden,
                            // then expose that same panel. No sleeps or forced disposal.
                            if (number > 1) {
                                record("hidden-layout-precondition", openedPreviewState)
                                check(openedPreviewState.contains("layout=SHOW_EDITOR\n")) {
                                    "HIDDEN_LAYOUT_PRECONDITION_NOT_MET: stress iteration $iteration"
                                }
                            }
                            if (openedPreviewState.contains("layout=SHOW_EDITOR\n")) {
                                record("hidden-panel-wait-start", openedPreviewState)
                                try {
                                    waitFor(
                                        message = "Hidden initial Preview panel attached: stress iteration $iteration",
                                        timeout = 20.seconds,
                                        getter = {
                                            driver.withContext(OnDispatcher.EDT) {
                                                preview.referencePanelAttached(remoteEditor)
                                            }
                                        },
                                        checker = { it }
                                    )
                                } catch (cause: com.intellij.driver.sdk.WaitForException) {
                                    val atTimeout = driver.withContext(OnDispatcher.EDT) {
                                        preview.previewLifecycleEvidence(remoteEditor)
                                    }
                                    record("hidden-panel-timeout",
                                        "failure_class=HIDDEN_PANEL_NOT_ATTACHED\n" + atTimeout)
                                    throw IllegalStateException(
                                        "HIDDEN_PANEL_NOT_ATTACHED: stress iteration $iteration", cause
                                    )
                                }
                                val hidden = driver.withContext(OnDispatcher.EDT) {
                                    preview.previewLifecycleEvidence(remoteEditor)
                                }
                                record("hidden-panel-attached", hidden)
                                check(hidden.contains("layout=SHOW_EDITOR\n") &&
                                    hidden.contains("panel_attached=true\n") &&
                                    hidden.contains("preview_component_showing=false\n")) {
                                    "HIDDEN_PANEL_STATE_CHANGED: stress iteration $iteration: $hidden"
                                }
                                val hiddenBrowser = driver.withContext(OnDispatcher.EDT) {
                                    preview.referenceBrowserIdentity(remoteEditor)
                                }
                                record("hidden-browser", hiddenBrowser)
                                hiddenPanelIdentity = hiddenBrowser.lineSequence()
                                    .first { it.startsWith("browser_identity=") }.substringAfter('=')
                            } else {
                                record("hidden-panel-not-applicable", openedPreviewState)
                            }
                        }
                        if (splitOpen) {
                            // direct_split: request split immediately after opening.
                            // hidden_attach_then_split: prove hidden panel attachment
                            // first and preserve its browser through split and show.
                            val splitPreparation = driver.withContext(OnDispatcher.EDT) {
                                preview.prepareReferenceSplitAtOpen(remoteEditor)
                            }
                            record("split-layout-prepared", splitPreparation)
                            val initialWaitState = driver.withContext(OnDispatcher.EDT) {
                                preview.previewLifecycleEvidence(remoteEditor)
                            }
                            record("initial-panel-wait-start", initialWaitState)
                            check(initialWaitState.contains("layout=SHOW_EDITOR_AND_PREVIEW\n")) {
                                "Direct split initial layout was not established: $iteration: $initialWaitState"
                            }
                            // Prove the attached panel is reused on the subsequent
                            // SHOW_PREVIEW transition, not silently replaced.
                            try {
                                waitFor(
                                    message = "Initial split Preview panel attached: stress iteration $iteration",
                                    timeout = 20.seconds,
                                    getter = {
                                        driver.withContext(OnDispatcher.EDT) {
                                            preview.referencePanelAttached(remoteEditor)
                                        }
                                    },
                                    checker = { it }
                                )
                            } catch (cause: com.intellij.driver.sdk.WaitForException) {
                                val timeoutState = runCatching {
                                    driver.withContext(OnDispatcher.EDT) {
                                        preview.previewLifecycleEvidence(remoteEditor)
                                    }
                                }.getOrElse { error ->
                                    "evidence_error_class=" + error.javaClass.name +
                                        "\nevidence_error_message=" + (error.message ?: "NONE") + "\n"
                                }
                                record("initial-panel-timeout",
                                    "failure_class=INITIAL_PANEL_NOT_ATTACHED\n" + timeoutState)
                                throw IllegalStateException(
                                    "INITIAL_PANEL_NOT_ATTACHED: stress iteration $iteration", cause
                                )
                            }
                            val initial = driver.withContext(OnDispatcher.EDT) {
                                preview.previewLifecycleEvidence(remoteEditor)
                            }
                            record("pre-show-split-panel", initial)
                            check(initial.contains("layout=SHOW_EDITOR_AND_PREVIEW\n") &&
                                initial.contains("panel_attached=true\n")) {
                                "Direct split counterfactual did not preserve an initially attached Preview: $iteration: $initial"
                            }
                            val beforeIdentity = driver.withContext(OnDispatcher.EDT) {
                                preview.referenceBrowserIdentity(remoteEditor)
                            }
                            record("pre-show-browser", beforeIdentity)
                            if (hiddenPanelIdentity != null) {
                                check(beforeIdentity.contains(
                                    "browser_identity=$hiddenPanelIdentity\n"
                                )) {
                                    "HIDDEN_PANEL_REPLACED_ON_SPLIT: stress iteration $iteration"
                                }
                                record("hidden-browser-reused-after-split",
                                    "browser_identity=$hiddenPanelIdentity\n")
                            }
                            initialPanelIdentity = beforeIdentity.lineSequence()
                                .first { it.startsWith("browser_identity=") }.substringAfter('=')
                        }
                        driver.withContext(OnDispatcher.EDT) {
                            preview.showReferenceAtSourceLine(remoteEditor, 0)
                        }
                        waitFor(
                            message = "Stress Preview visible: $iteration",
                            timeout = 20.seconds,
                            getter = { driver.withContext(OnDispatcher.EDT) {
                                preview.referenceShowing(remoteEditor) &&
                                    preview.referencePanelShowing(remoteEditor)
                            } },
                            checker = { it }
                        )
                        // Attach the per-browser CEF load tracer while the real
                        // bundled panel is live. No retries or extra load requests.
                        val loadTrace = dest.resolve("jcef-load-events.tsv")
                        driver.withContext(OnDispatcher.EDT) {
                            preview.installReferenceLoadTrace(remoteEditor, loadTrace.toString())
                        }
                        record("load-trace-installed", "load_events_file=" + loadTrace + "\n")
                        val identity = driver.withContext(OnDispatcher.EDT) {
                            preview.referenceBrowserIdentity(remoteEditor)
                        }
                        record("panel-showing", identity)
                        if (splitOpen) {
                            check(identity.contains("browser_identity=$initialPanelIdentity\n")) {
                                "Split Preview browser was replaced before content proof: $iteration"
                            }
                            record("initial-browser-reused",
                                "browser_identity=$initialPanelIdentity\n")
                        }
                        check(identity.contains("jcef_remote_enabled=$expectedRemote\n") &&
                            identity.contains("registry_markdown_osr=$expectedOsr\n") &&
                            identity.contains("browser_offscreen_rendering=$expectedOsr\n")) {
                            "JCEF execution/rendering mode mismatch for $executionMode/$renderMode iteration $iteration: $identity"
                        }
                        check(identity.contains("jbcef_disposed=false\n") &&
                            identity.contains("panel_showing=true\n")) {
                            "Stress iteration has no live bundled JCEF browser: $iteration"
                        }
                        val browserIdentity = identity.lineSequence()
                            .first { it.startsWith("browser_identity=") }
                            .substringAfter('=')
                        // Swing panel visibility is not proof of native CefBrowser creation.
                        // Preserve the exact onAfterCreated-equivalent readiness boundary.
                        record("native-creation-wait")
                        try {
                            waitFor(
                                message = "Bundled native browser created: stress iteration $iteration",
                                timeout = 20.seconds,
                                getter = {
                                    driver.withContext(OnDispatcher.EDT) {
                                        preview.referenceBrowserIdentity(remoteEditor)
                                    }.contains("native_browser_created=true\n")
                                },
                                checker = { it }
                            )
                        } catch (cause: com.intellij.driver.sdk.WaitForException) {
                            record("native-creation-timeout", "failure_class=NATIVE_CREATION_TIMEOUT\n")
                            throw IllegalStateException(
                                "NATIVE_CREATION_TIMEOUT: stress iteration $iteration", cause
                            )
                        }
                        val createdIdentity = driver.withContext(OnDispatcher.EDT) {
                            preview.referenceBrowserIdentity(remoteEditor)
                        }
                        record("native-created", createdIdentity)
                        check(createdIdentity.contains("native_browser_created=true\n") &&
                            createdIdentity.contains("jbcef_disposed=false\n") &&
                            createdIdentity.contains("cef_browser_closed=false\n")) {
                            "Native browser creation evidence is not authoritative: $iteration"
                        }

                        // CEF text callback validates document-specific DOM content.
                        // A system notification, toolbar, or 'Loading...' pixels cannot pass.
                        record("content-ready-wait")
                        val domWaitStartNs = System.nanoTime()
                        var requestedTextProbes = 0
                        var domPolls = 0
                        var pendingTextProbe: Path? = null
                        fun traceHostDom(phase: String, probe: Path? = pendingTextProbe,
                                         detail: String = "") {
                            // Keep the original wait/poll cadence and verdict unchanged.
                            Files.writeString(
                                dest.resolve("rendered-text-host-timing.tsv"),
                                "timestamp_ms=" + System.currentTimeMillis() +
                                    "\telapsed_ms=" + (System.nanoTime() - domWaitStartNs) / 1_000_000 +
                                    "\tphase=" + phase +
                                    "\tprobe=" + (probe?.fileName ?: "NONE") +
                                    "\tpolls=" + domPolls +
                                    "\t" + detail + "\n",
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND,
                            )
                        }
                        traceHostDom("host-dom-wait-start")
                        try {
                            waitFor(
                                message = "Bundled Markdown DOM text ready: stress iteration $iteration",
                                timeout = 20.seconds,
                                getter = {
                                    domPolls++
                                    val pending = pendingTextProbe
                                    traceHostDom("host-poll", pending)
                                    when {
                                        pending == null -> {
                                            requestedTextProbes++
                                            val requestPath = dest.resolve(
                                                "rendered-text-probe-" +
                                                    requestedTextProbes.toString().padStart(4, '0') + ".txt"
                                            )
                                            traceHostDom("host-request-start", requestPath)
                                            driver.withContext(OnDispatcher.EDT) {
                                                preview.requestReferenceRenderedTextProbe(
                                                    remoteEditor, requestPath.toString()
                                                )
                                            }
                                            traceHostDom("host-request-return", requestPath)
                                            pendingTextProbe = requestPath
                                            false
                                        }
                                        !Files.exists(pending) -> false
                                        else -> {
                                            val visibleText = Files.readString(pending)
                                            val matched = visibleText.contains(renderedTextAnchorA) &&
                                                visibleText.contains(renderedTextAnchorB)
                                            traceHostDom("host-response-evaluated", pending,
                                                "chars=" + visibleText.length + "\tanchors_matched=" + matched)
                                            if (matched) {
                                                Files.writeString(
                                                    dest.resolve("rendered-content-proof.txt"),
                                                    "schema=markflow-bundled-jcef-rendered-text/v1\n" +
                                                        "probe=" + pending.fileName + "\n" +
                                                        "anchor_a_present=true\nanchor_b_present=true\n"
                                                )
                                            } else {
                                                pendingTextProbe = null
                                            }
                                            matched
                                        }
                                    }
                                },
                                checker = { it }
                            )
                        } catch (cause: com.intellij.driver.sdk.WaitForException) {
                            traceHostDom("host-dom-wait-timeout", pendingTextProbe,
                                "text_probes=" + requestedTextProbes +
                                    "\tpending_exists=" + (pendingTextProbe?.let(Files::exists) ?: false))
                            record("content-not-ready",
                                "failure_class=CONTENT_NOT_READY\ntext_probes=$requestedTextProbes\n")
                            throw IllegalStateException(
                                "CONTENT_NOT_READY: stress iteration $iteration", cause
                            )
                        }
                        traceHostDom("host-dom-wait-success", pendingTextProbe,
                            "text_probes=" + requestedTextProbes)
                        record("content-ready", "dom_anchor_a=true\ndom_anchor_b=true\n" +
                            "text_probes=$requestedTextProbes\n")

                        val xywh = driver.withContext(OnDispatcher.EDT) {
                            preview.referenceBounds(remoteEditor).split(',').map(String::toInt)
                        }
                        check(xywh.size == 4)
                        val bounds = Rectangle(xywh[0], xywh[1], xywh[2], xywh[3])
                        record("first-paint-wait")
                        try {
                            waitFor(
                                message = "Bundled JCEF fixture-content viewport paint: stress iteration $iteration",
                                timeout = 20.seconds,
                                getter = {
                                    robot.waitForIdle()
                                    val image = robot.createScreenCapture(bounds)
                                    check(ImageIO.write(image, "png",
                                        dest.resolve("reference-paint-probe.png").toFile()))
                                    // Exclude edges, notification balloons and the lower-right
                                    // IDE chrome; the two exact CEF DOM anchors were checked above.
                                    val contentRegion = image.getSubimage(
                                        image.width / 20,
                                        image.height / 8,
                                        image.width * 3 / 4,
                                        image.height * 5 / 8,
                                    )
                                    nonblank.referenceHasVisibleContent(contentRegion)
                                },
                                checker = { it }
                            )
                        } catch (cause: com.intellij.driver.sdk.WaitForException) {
                            record("content-not-ready",
                                "failure_class=CONTENT_NOT_READY\nreason=fixture_viewport_not_painted\n")
                            throw IllegalStateException(
                                "CONTENT_NOT_READY: fixture viewport paint iteration $iteration", cause
                            )
                        }
                        record("first-paint-observed",
                            "rendered_text_proof=true\ncontent_region_nonblank=true\n")
                        check(driver.withContext(OnDispatcher.EDT) {
                            preview.sourceText(remoteEditor) == fixture &&
                                preview.sourceStamp(remoteEditor) == stamp &&
                                !preview.sourceUnsaved(remoteEditor)
                        }) { "Reference paint changed the source: $iteration" }

                        val marker = dest.resolve("native-close.marker").toAbsolutePath().normalize()
                        record("hide-start", "close_marker=$marker\n")
                        driver.withContext(OnDispatcher.EDT) {
                            preview.armDetachedReferenceBrowserCloseMarker(marker.toString())
                            preview.restoreNative(remoteEditor)
                        }
                        waitFor(
                            message = "Bundled JCEF panel detached: stress iteration $iteration",
                            timeout = 20.seconds,
                            getter = { driver.withContext(OnDispatcher.EDT) {
                                preview.nativeShowing(remoteEditor) &&
                                    !preview.referencePanelAttached(remoteEditor)
                            } },
                            checker = { it }
                        )
                        record("panel-detached", driver.withContext(OnDispatcher.EDT) {
                            preview.detachedReferenceBrowserEvidence()
                        })
                        // Capture any former CENTER child that outlives the active
                        // panel's detach; keep all native-close gates unchanged.
                        record("post-detach-wrapper", driver.withContext(OnDispatcher.EDT) {
                            preview.previewLifecycleEvidence(remoteEditor)
                        })
                        // This is host process polling only: do not call Driver/EDT
                        // from the wait getter. A missing callback is a hard FAIL.
                        try {
                            waitFor(
                                message = "Bundled native onBeforeClose: stress iteration $iteration",
                                timeout = 20.seconds,
                                getter = { Files.exists(marker) },
                                checker = { it }
                            )
                        } catch (cause: com.intellij.driver.sdk.WaitForException) {
                            record("native-close-timeout", "failure_class=NATIVE_CLOSE_TIMEOUT\n")
                            throw IllegalStateException(
                                "NATIVE_CLOSE_TIMEOUT: stress iteration $iteration", cause
                            )
                        }
                        val markerBody = Files.readString(marker)
                        check(markerBody.contains("cef_before_close_observed=true\n") &&
                            markerBody.contains("browser_identity=$browserIdentity\n")) {
                            "Native close marker is not for this exact browser: $iteration"
                        }
                        val closed = driver.withContext(OnDispatcher.EDT) {
                            preview.detachedReferenceBrowserEvidence()
                        }
                        record("native-close-observed", closed)
                        check(closed.contains("jbcef_disposed=true\n") &&
                            closed.contains("cef_before_close_observed=true\n") &&
                            closed.contains("native_close_complete=true\n")) {
                            "JBCef wrapper/native close did not both complete: $iteration"
                        }
                        check(driver.withContext(OnDispatcher.EDT) {
                            preview.sourceText(remoteEditor) == fixture &&
                                preview.sourceStamp(remoteEditor) == stamp &&
                                !preview.sourceUnsaved(remoteEditor)
                        }) { "Lifecycle close changed the source: $iteration" }
                        check(Files.readAllBytes(fixturePath).contentEquals(fixtureBytes))
                        markFlow.closeMarkdown(remoteEditor, fileName)
                        completed++
                        record("file-editor-closed")
                    } catch (cause: Throwable) {
                        // Preserve the first substantive failure and its last
                        // screenshot/marker evidence. Do not rerun or recover.
                        record("failed", "exception=" + cause.javaClass.name +
                            "\nmessage=" + (cause.message ?: "NONE") + "\n")
                        deferredFailure = cause
                        break
                    }
                }
                // As in Full Differential, defer a fatal exception until the
                // Starter IDE shutdown boundary so diagnostics aren't gathered
                // through a potentially dead Driver/EDT.
            }
            deferredFailure?.let { throw it }
            check(completed == iterations) {
                "Stress diagnostic executed only $completed/$iterations lifecycle iterations"
            }
            Files.writeString(output.resolve("result.txt"),
                "result=PASS\nmode=$iterationMode\ncompleted=$completed\n" +
                    "scope=JCEF_LIFECYCLE_ONLY_NOT_VISUAL_ACCEPTANCE\n",
                StandardCharsets.UTF_8)
        } catch (cause: Throwable) {
            Files.writeString(output.resolve("failure.txt"),
                "result=FAIL\nmode=$iterationMode\ncompleted=$completed\n" +
                    "exception=" + cause.javaClass.name + "\n" +
                    "message=" + (cause.message ?: "NONE") + "\n",
                StandardCharsets.UTF_8)
            throw cause
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeVisualAcceptanceE2EBridge",
    plugin = "com.algorist.markflow"
)
private interface JcefStressVisualRemote {
    fun prepareEditorChromeBeforeOpen()
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeDifferentialPreviewE2EBridge",
    plugin = "com.algorist.markflow"
)
private interface JcefStressPreviewRemote {
    fun showReferenceAtSourceLine(editor: Editor, line: Int): String
    fun referenceShowing(editor: Editor): Boolean
    fun referencePanelShowing(editor: Editor): Boolean
    fun referencePanelAttached(editor: Editor): Boolean
    fun referenceBrowserIdentity(editor: Editor): String
    fun previewLifecycleEvidence(editor: Editor): String
    fun prepareReferenceSplitAtOpen(editor: Editor): String
    fun installReferenceLoadTrace(editor: Editor, traceFile: String)
    fun requestReferenceRenderedTextProbe(editor: Editor, probePath: String)
    fun referenceBounds(editor: Editor): String
    fun jcefRuntimeEvidence(): String
    fun sourceText(editor: Editor): String
    fun sourceStamp(editor: Editor): Long
    fun sourceUnsaved(editor: Editor): Boolean
    fun armDetachedReferenceBrowserCloseMarker(markerPath: String)
    fun restoreNative(editor: Editor)
    fun detachedReferenceBrowserEvidence(): String
    fun nativeShowing(editor: Editor): Boolean
}
