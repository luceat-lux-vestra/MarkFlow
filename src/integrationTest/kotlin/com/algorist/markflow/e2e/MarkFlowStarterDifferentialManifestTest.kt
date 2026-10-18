package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.WaitForException
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.components.common.jcef
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Stage D: capture proof only. Missing captures and visual acceptance never become PASS. */
class MarkFlowStarterDifferentialManifestTest {
    private class Case(
        val id: String, val parent: String, val domain: String, val classification: String,
        val path: String, val bytes: ByteArray, val line: Int, val blockHash: String?,
        val visualExpectation: String, val comparable: Boolean
    )

    private data class AcceptanceDecision(
        val caseId: String,
        val manifestId: String,
        val sourceSha256: String,
        val blockSha256: String,
        val classification: String,
        val visualExpectation: String,
        val reviewResult: String,
        val evidenceSourceHead: String,
        val evidenceRunId: String,
        val reviewNote: String,
    )

    private val reviewedAcceptanceLedgerHeader =
        "case_id\tmanifest_case_id\tsource_sha256\tblock_sha256\tclassification\t" +
            "visual_reference_expectation\treview_result\tevidence_source_head\t" +
            "evidence_run_id\treview_note"

    @Test
    fun boundedManifestCasesCaptureActualReferenceAndNative() {
        val root = Path.of("").toAbsolutePath().normalize()
        val manifest = Files.readAllBytes(root.resolve("fixtures/differential-acceptance/corpus.tsv"))
        val inventory = inventory(root, String(manifest, StandardCharsets.UTF_8))
        check(inventory.map { it.parent }.toSet().size == 88)
        check(inventory.map { it.id }.toSet().size == inventory.size)
        check(headingSourceIdentityNegativeControls()) { "Stage E2 heading-geometry negative controls failed" }
        check(renderedHeadingBoxNegativeControls()) { "Stage E5 native/DOM box identity negative controls failed" }
        check(followingTextAnchorNegativeControls()) { "Stage E6 paragraph-anchor negative controls failed" }
        check(candidateRasterScaleNegativeControls()) { "Stage E3 raster scale controls failed" }
        check(headingInkRasterNegativeControls()) { "Stage E16 heading raster-ink negative controls failed" }
        check(headingInkVerticalNegativeControls()) { "Stage E19 heading raster Y negative controls failed" }
        check(acceptanceLedgerNegativeControls()) {
            "Full #353 acceptance-ledger negative controls failed"
        }
        // Stage D capture completion and full #353 acceptance are separate
        // contracts. Never infer release approval from a successful bounded
        // source-identical capture of only a bounded subset of 89 expanded cases.
        val contract = System.getProperty("markflow.differential.contract") ?: "capture"
        require(contract == "capture" || contract == "acceptance") {
            "Unknown differential contract: $contract"
        }
        // Expand one sequential real-IDE session rather than adding CI shards.
        // These ten cases remain CAPTURED_UNVERIFIED, not full #353 release acceptance.
        // #352's retained subset covers Gantt + sequence + class/state plus both large-flowchart
        // blocks so actual native geometry/scroll evidence includes normal, wide and tall diagrams.
        val defaultIds = "md-atx-headings-h1-h6,mermaid-flowchart-minimal,mermaid-gantt" +
            ",md-setext-headings,md-tight-loose-lists,mermaid-sequence" +
            ",mermaid-class,mermaid-state,mermaid-flowchart-large--block-01" +
            ",mermaid-flowchart-large--block-02"
        val explicitCaseIds = System.getProperty("markflow.differential.caseIds")
        val selectedIds = if (contract == "acceptance") {
            check(explicitCaseIds == null) {
                "Full #353 acceptance cannot be reduced with differentialCaseIds"
            }
            inventory.map { it.id }
        } else {
            (explicitCaseIds ?: defaultIds).split(',').map { it.trim() }
        }
        check(selectedIds.isNotEmpty() && selectedIds.toSet().size == selectedIds.size)
        if (contract == "capture" && explicitCaseIds == null) {
            val mermaidLayoutCohort = setOf(
                "mermaid-gantt",
                "mermaid-sequence",
                "mermaid-class",
                "mermaid-state",
                "mermaid-flowchart-large--block-01",
                "mermaid-flowchart-large--block-02",
            )
            check(mermaidLayoutCohort.all(selectedIds::contains)) {
                "#352 default layout cohort was silently reduced"
            }
        }
        val indexed = inventory.associateBy { it.id }
        val reviewFixture = "fixtures/visual-review/typical-markdown-document.md"
        val reviewBytes = Files.readAllBytes(root.resolve(reviewFixture))
        val reviewCases = if (contract == "capture" && explicitCaseIds == null) {
            listOf(
                Case("md-general-document-start", "review-only-general-document",
                    "markdown", "supported", reviewFixture, reviewBytes, 0, null,
                    "source-anchored-geometry", true),
                Case("md-general-document-middle", "review-only-general-document",
                    "markdown", "supported", reviewFixture, reviewBytes, 30, null,
                    "source-anchored-geometry", true),
                Case("md-general-document-end", "review-only-general-document",
                    "markdown", "supported", reviewFixture, reviewBytes, 60, null,
                    "source-anchored-geometry", true),
            )
        } else emptyList()
        check(reviewCases.map { it.id }.toSet().size == reviewCases.size &&
            reviewCases.none { it.id in indexed })
        val selected = reviewCases +
            selectedIds.map { requireNotNull(indexed[it]) { "Unknown case: " + it } }
        if (contract == "capture") {
            check(selected.all { it.comparable && it.classification == "supported" })
        } else {
            check(reviewCases.isEmpty() && selected.size == inventory.size)
            check(selected.map { it.id }.toSet() == inventory.map { it.id }.toSet())
        }
        val sourceHead = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(root.toFile()).start().let {
                val actual = it.inputStream.bufferedReader().readText().trim()
                check(it.waitFor() == 0 && actual.matches(Regex("[0-9a-f]{40}")))
                actual
            }
        System.getenv("STARTER_SOURCE_SHA")?.takeIf { it.isNotBlank() }?.let {
            check(it == sourceHead) { "Non-authoritative Starter source checkout" }
        }
        val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
        val version = requireNotNull(System.getProperty("markflow.test.platformVersion"))
        check(version == "2026.2.3")
        val output = root.resolve("build/e2e-evidence/derived-content/differential-manifest")
        Files.createDirectories(output)
        val progressFile = System.getenv("MARKFLOW_DIFFERENTIAL_PROGRESS_FILE")
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                val candidate = Path.of(raw)
                if (candidate.isAbsolute) candidate.normalize() else root.resolve(candidate).normalize()
            }
        fun heartbeat(phase: String, caseId: String = "-") {
            val target = progressFile ?: return
            target.parent?.let { parent -> Files.createDirectories(parent) }
            Files.writeString(
                target,
                System.currentTimeMillis().toString() + "\t" + phase + "\t" + caseId + "\n",
                StandardCharsets.UTF_8,
            )
        }
        heartbeat("test-start")
        val states = inventory.associate { it.id to "NOT_EXECUTED" }.toMutableMap()
        val structuralFailures = mutableListOf<String>()
        val sandbox = Files.createTempDirectory("markflow-353-manifest-")
        val project = sandbox.resolve("project")
        var failure: Throwable? = null
        // Fatal per-case driver failures are deferred until the IDE has closed cleanly.
        // Throwing them from inside useDriverAndCloseIde makes Starter start expensive
        // failure diagnostics against an already-degraded IDE before evidence is staged.
        var deferredDriverFailure: Throwable? = null
        try {
            val base = root.resolve("src/integrationTest/resources/projects/markdown-smoke")
            check(base.toFile().copyRecursively(project.toFile(), overwrite = true))
            // Full-corpus fixtures intentionally keep source bytes unchanged while
            // resolving their reviewed relative local-image references inside the
            // sandbox. Host-resource behavior must not degrade merely because the
            // original fixture lived in a different repository directory.
            writeDeterministicLocalImage(project.resolve("visual-proof.png"))
            writeDeterministicLocalImage(project.resolve("images/example.png"))
            // A and B are byte-identical but distinct file-editor lifecycles.
            // Only this exact B filename is opted into source-line replacement.
            val abHeading = selected.firstOrNull { it.id == "md-atx-headings-h1-h6" }
            if (abHeading != null) {
                val bFixture = project.resolve("heading-layout-b.md")
                check(!Files.exists(bFixture))
                Files.write(bFixture, abHeading.bytes)
                val productFixture = project.resolve("heading-layout-product.md")
                check(!Files.exists(productFixture))
                Files.write(productFixture, abHeading.bytes)
            }
            selected.forEach { entry ->
                val target = project.resolve(entry.id + ".md")
                check(!Files.exists(target)) { "Capture fixture collision" }
                Files.write(target, entry.bytes)
                val dest = output.resolve(entry.id)
                Files.createDirectories(dest)
                Files.write(dest.resolve("source.md"), entry.bytes)
                Files.writeString(dest.resolve("identity.txt"),
                    "case_id=" + entry.id + "\nmanifest_id=" + entry.parent +
                    "\nclassification=" + entry.classification +
                    "\nvisual_reference_expectation=" + entry.visualExpectation +
                    "\nintellij_preview=" + (if (entry.comparable) "yes" else "no") +
                    "\nfixture=" + entry.path +
                    "\nsource_sha256=" + sha(entry.bytes) +
                    "\nblock_sha256=" + (entry.blockHash ?: "none") +
                    "\nsource_line_zero_based=" + entry.line +
                    "\nsource_head=" + sourceHead +
                    "\nmanifest_sha256=" + sha(manifest) + "\n")
            }
            heartbeat("starter-launch")
            Starter.newContext(
                testName = if (contract == "acceptance") {
                    "markflow-353-full-manifest-real-pairs"
                } else {
                    "markflow-353-bounded-manifest-real-pairs"
                },
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project)).useRelease(version)
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("ide.browser.jcef.jsQueryPoolSize", "10000")
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                if (abHeading != null) {
                    addSystemProperty("markflow.starter.headingLayoutAbPilot", "true")
                    addSystemProperty("markflow.starter.headingLegacyBaselinePilot", "true")
                }
                addSystemProperty("sun.java2d.uiScale", "1.0")
                addSystemProperty("ide.ui.scale", "1.0")
            }.runIdeWithDriver(
                // IDEA Starter defaults the whole IDE process to 10 minutes. That is appropriate
                // for the bounded PR capture, but the complete 89-case acceptance corpus cannot
                // finish inside that process lifetime. Keep the ordinary contract bounded while
                // leaving cleanup headroom under the workflow's 85-minute outer timeout.
                runTimeout = if (contract == "acceptance") 75.minutes else 12.minutes,
            ).useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                heartbeat("ide-ready")
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val visual = driver.utility(DifferentialManifestVisualRemote::class)
                val preview = driver.utility(DifferentialManifestPreviewRemote::class)
                val rendererLifecycle = driver.utility(DifferentialRendererLifecycleRemote::class)
                val rendererBaseline = driver.withContext(OnDispatcher.EDT) {
                    rendererLifecycle.liveRuntimeCount()
                }
                check(rendererBaseline == 0) {
                    "MarkFlow derived JCEF runtime leaked before #353 corpus start: $rendererBaseline"
                }
                val diagnostics = MarkFlowStarterDifferentialPreviewSmokeTest()
                val robot = Robot()
                check(driver.withContext(OnDispatcher.EDT) {
                    preview.rasterGeometryNegativeControls()
                }) { "Stage D raster geometry negative controls failed" }
                check(driver.withContext(OnDispatcher.EDT) {
                    preview.nativeHeadingGeometryNegativeControls()
                }) { "Stage E1 source-span geometry negative controls failed" }
                check(driver.withContext(OnDispatcher.EDT) {
                    preview.rasterScrollNegativeControls()
                }) { "Stage E18 scroll-reachability negative controls failed" }
                driver.withContext(OnDispatcher.EDT) { visual.prepareEditorChromeBeforeOpen() }
                for (entry in selected) {
                    val dest = output.resolve(entry.id)
                    states[entry.id] = "CAPTURE_FAILED"
                    heartbeat("case-start", entry.id)
                    var caseEditor:
                        com.intellij.driver.sdk.ui.components.common.JEditorUiComponent? = null
                    var caseRemoteEditor: Editor? = null
                    var caseStamp: Long? = null
                    var caseFailure: Throwable? = null
                    var toleratedStructuralFailure = false
                    try {
                        val editor = markFlow.openMarkdown(entry.id + ".md")
                        caseEditor = editor
                        markFlow.assertProductionProjectionAttached(editor)
                        val remoteEditor = editor.editor
                        caseRemoteEditor = remoteEditor
                        val source = String(entry.bytes, StandardCharsets.UTF_8)
                        check(markFlow.source(editor) == source)
                        val stamp = markFlow.modificationStamp(editor)
                        caseStamp = stamp
                        heartbeat("case-opened", entry.id)
                        val runtime = driver.withContext(OnDispatcher.EDT) {
                            preview.jcefRuntimeEvidence()
                        }
                        Files.writeString(dest.resolve("jcef-runtime-evidence.txt"), runtime)
                        check(runtime.lineSequence().any {
                            it.startsWith("idea_apparmor_current=") &&
                                it.contains("/idea-IU-262.10968.63/bin/idea")
                        }) { "Unconfined IDEA runtime" }
                        driver.withContext(OnDispatcher.EDT) { visual.prepare(remoteEditor) }
                        // Caret outside the Mermaid fence avoids hiding its rendered inlay.
                        markFlow.resetToSingleCaret(editor, source.length)
                        driver.withContext(OnDispatcher.EDT) {
                            preview.showNativeAtSourceLine(remoteEditor, entry.line)
                        }
                        waitFor(
                            message = "Native derived capture readiness: " + entry.id,
                            timeout = 50.seconds,
                            getter = { driver.withContext(OnDispatcher.EDT) {
                                visual.visualEvidence(remoteEditor)
                            } },
                            checker = { evidence ->
                                val values = evidence.split(';').mapNotNull { token ->
                                    val pieces = token.split('=')
                                    if (pieces.size == 2) pieces[1].toIntOrNull()?.let {
                                        pieces[0] to it
                                    } else null
                                }.toMap()
                                values["derivedPending"] == 0 &&
                                    (entry.domain != "mermaid" ||
                                        (values["derivedDecoded"] ?: 0) > 0)
                            }
                        )
                        heartbeat("case-native-ready", entry.id)
                        driver.withContext(OnDispatcher.EDT) {
                            visual.normalizeEditorChromeForCapture(remoteEditor)
                        }
                        val nativeEnvironment = driver.withContext(OnDispatcher.EDT) {
                            visual.environmentIdentity(remoteEditor)
                        }
                        val nativeRasterGeometry = driver.withContext(OnDispatcher.EDT) {
                            preview.nativeRasterGeometry(remoteEditor)
                        }
                        // Source-line coordinates and real heading inlay dimensions are
                        // observed only from the installed native production projection.
                        // These are NOT matched to platform DOM coordinates yet.
                        val nativeHeadingGeometry = driver.withContext(OnDispatcher.EDT) {
                            preview.nativeHeadingSourceGeometry(remoteEditor)
                        }
                        val measuredHeadings = nativeHeadingGeometry.lineSequence()
                            .firstOrNull { it.startsWith("measured_atx_heading_count=") }
                            ?.substringAfter('=')?.toIntOrNull()
                            ?: error("Stage E1 heading ledger is not parseable")
                        if (entry.id == "md-atx-headings-h1-h6") {
                            check(measuredHeadings == 6) {
                                "Stage E1 requires all six installed H1-H6 heading inlays"
                            }
                        }
                        Files.writeString(
                            dest.resolve("native-heading-source-geometry.txt"),
                            nativeHeadingGeometry, StandardCharsets.UTF_8
                        )
                        val rasterCount = nativeRasterGeometry.lineSequence()
                            .firstOrNull { it.startsWith("raster_count=") }
                            ?.substringAfter('=')?.toIntOrNull()
                            ?: error("Stage D native raster geometry is not parseable")
                        val rasterRecords = nativeRasterGeometry.lineSequence()
                            .filter { it.startsWith("index=") }
                            .map { row ->
                                row.split('\t').associate { field ->
                                    val parts = field.split('=', limit = 2)
                                    check(parts.size == 2) { "Malformed native raster geometry" }
                                    parts[0] to parts[1]
                                }
                            }.toList()
                        check(rasterRecords.size == rasterCount) {
                            "Stage D raster count does not match measured records"
                        }
                        val targetRasterRecord = if (entry.domain == "mermaid") {
                            check(rasterCount >= 1) {
                                "Stage D Mermaid case has no measured native raster inlays"
                            }
                            // A manifest case identifies one Mermaid fence even when its Markdown
                            // file contains multiple fences. Native raster inlays are installed at
                            // each projection's source end, so resolve the exact target by that
                            // fence's closing source line instead of assuming one raster per file.
                            val sourceLines = source.split('\n')
                            val opening = Regex("^ {0,3}(`{3,}|~{3,})mermaid[ \\t]*$")
                                .matchEntire(sourceLines.getOrElse(entry.line) { "" })
                                ?: error("#352 selected Mermaid case does not point at its opening fence")
                            val fence = opening.groupValues[1]
                            val closingLine = (entry.line + 1 until sourceLines.size).firstOrNull { line ->
                                val closing = sourceLines[line].trim()
                                closing.length >= fence.length && closing.all { it == fence[0] }
                            } ?: error("#352 selected Mermaid case has no closing fence")
                            val matches = rasterRecords.filter { record ->
                                record["source_line_zero_based"]?.toIntOrNull() == closingLine
                            }
                            check(matches.size == 1) {
                                "#352 target Mermaid block has no unique installed raster: " +
                                    entry.id + " closingLine=" + closingLine +
                                    " rasterLines=" + rasterRecords.map { it["source_line_zero_based"] }
                            }
                            matches.single()
                        } else null
                        Files.writeString(
                            dest.resolve("native-raster-geometry.txt"),
                            nativeRasterGeometry, StandardCharsets.UTF_8
                        )
                        if (entry.id == "mermaid-gantt") {
                            check(rasterCount == 1) { "Gantt must own exactly one measured native raster" }
                            val fields = requireNotNull(targetRasterRecord)
                            fun size(name: String): Int =
                                requireNotNull(fields[name]?.toIntOrNull()) { "Missing Gantt " + name }
                            val iw = size("intrinsic_width")
                            val ih = size("intrinsic_height")
                            val dw = size("displayed_width")
                            val dh = size("displayed_height")
                            if (ganttRasterExceededCandidateScale(iw, ih, dw, dh)) {
                                structuralFailures += listOf(
                                    entry.id, "CANDIDATE_GANTT_MAX_UPSCALE_X2",
                                    dw.toString() + "x" + dh,
                                    (iw * 2L).toString() + "x" + (ih * 2L)
                                ).joinToString("\t")
                            }
                            // E7: a proper display scale cannot recover labels
                            // already compressed into a tiny intrinsic Gantt SVG.
                            // This bound is scoped ONLY to the pinned 4-day Gantt
                            // fixture at a 1200px editor width and is candidate
                            // evidence, not an all-diagram width requirement.
                            if (ganttIntrinsicRasterWidthTooNarrow(iw)) {
                                structuralFailures += listOf(
                                    entry.id, "CANDIDATE_GANTT_MIN_INTRINSIC_WIDTH_800",
                                    iw.toString(), "800"
                                ).joinToString("\t")
                            }
                        }
                        val native = driver.withContext(OnDispatcher.EDT) {
                            Rectangle(
                                visual.contentScreenX(remoteEditor), visual.contentScreenY(remoteEditor),
                                visual.contentWidth(remoteEditor), visual.contentHeight(remoteEditor)
                            )
                        }
                        check(native.width == 1200 && native.height == 760)
                        robot.waitForIdle()
                        check(ImageIO.write(robot.createScreenCapture(native), "png",
                            dest.resolve("markflow.png").toFile()))
                        // E18: physically scroll to each boundary of the same native
                        // decoded inlay, then retain the observable viewport and
                        // screenshots. Never infer true reachability from only the
                        // first (1200x760) screenshot. The source/caret are immutable.
                        if (entry.domain == "mermaid") {
                            val targetRasterIndex = requireNotNull(
                                targetRasterRecord?.get("index")?.toIntOrNull()
                            ) { "#352 target Mermaid raster index is unavailable" }
                            check(targetRasterIndex in 0 until rasterCount)
                            val beforeScroll = driver.withContext(OnDispatcher.EDT) {
                                preview.nativeVerticalScrollOffset(remoteEditor)
                            }
                            val beforeCaret = driver.withContext(OnDispatcher.EDT) {
                                preview.primaryCaretOffset(remoteEditor)
                            }
                            try {
                                for (edge in listOf("TOP", "BOTTOM")) {
                                    waitFor(
                                        message = "E18 real inlay " + edge + " reachable: " + entry.id,
                                        timeout = 15.seconds,
                                        getter = { driver.withContext(OnDispatcher.EDT) {
                                            preview.nativeRasterScrollProbe(
                                                remoteEditor, targetRasterIndex, edge
                                            )
                                        } },
                                        checker = { ledger -> ledger.lineSequence().any {
                                            it == "edge_visible=true"
                                        } }
                                    )
                                    val ledger = driver.withContext(OnDispatcher.EDT) {
                                        preview.nativeRasterScrollProbe(
                                            remoteEditor, targetRasterIndex, edge
                                        )
                                    }
                                    check(ledger.lineSequence().any { it == "edge_visible=true" }) {
                                        "E18 native raster " + edge + " never entered the viewport"
                                    }
                                    Files.writeString(
                                        dest.resolve("native-scroll-" + edge.lowercase() + ".txt"),
                                        ledger, StandardCharsets.UTF_8
                                    )
                                    robot.waitForIdle()
                                    check(ImageIO.write(
                                        robot.createScreenCapture(native), "png",
                                        dest.resolve("native-scroll-" + edge.lowercase() + ".png").toFile()
                                    ))
                                }
                            } finally {
                                driver.withContext(OnDispatcher.EDT) {
                                    preview.restoreNativeVerticalScroll(remoteEditor, beforeScroll)
                                }
                                waitFor(
                                    message = "E18 native scroll restored: " + entry.id,
                                    timeout = 15.seconds,
                                    getter = { driver.withContext(OnDispatcher.EDT) {
                                        preview.nativeVerticalScrollOffset(remoteEditor)
                                    } },
                                    checker = { it == beforeScroll }
                                )
                            }
                            check(driver.withContext(OnDispatcher.EDT) {
                                preview.sourceText(remoteEditor) == source &&
                                    preview.sourceStamp(remoteEditor) == stamp &&
                                    preview.primaryCaretOffset(remoteEditor) == beforeCaret
                            }) { "E18 scroll probe altered the authoritative source/caret" }
                        }
                        heartbeat("case-native-evidence-complete", entry.id)
                        var refIdentity = "NOT_APPLICABLE_BY_MANIFEST"
                        var reference: Rectangle? = null
                        if (entry.comparable) {
                            heartbeat("case-reference-open", entry.id)
                            refIdentity = driver.withContext(OnDispatcher.EDT) {
                                preview.showReferenceAtSourceLine(remoteEditor, entry.line)
                            }
                            waitFor(
                                message = "Bundled preview visible: " + entry.id,
                                timeout = 30.seconds,
                                getter = { driver.withContext(OnDispatcher.EDT) {
                                    preview.referenceShowing(remoteEditor)
                                } },
                                checker = { it }
                            )
                            waitFor(
                                message = "Bundled Preview panel ready: " + entry.id,
                                timeout = 20.seconds,
                                getter = { driver.withContext(OnDispatcher.EDT) {
                                    preview.referencePanelShowing(remoteEditor)
                                } },
                                checker = { it }
                            )
                            heartbeat("case-reference-panel-ready", entry.id)
                            reference = driver.withContext(OnDispatcher.EDT) {
                                val xywh = preview.referenceBounds(remoteEditor).split(',').map(String::toInt)
                                check(xywh.size == 4)
                                Rectangle(xywh[0], xywh[1], xywh[2], xywh[3])
                            }
                            heartbeat("case-reference-visible", entry.id)
                            if (entry.parent == "review-only-general-document") {
                                waitFor(
                                    message = "Preview shows SAME general-document section: " + entry.id,
                                    timeout = 35.seconds,
                                    getter = { driver.ui.jcef().callJs(
                                        generalDocumentPreviewScrollProbe(entry.line)
                                    ) },
                                    checker = { it.contains("target_visible=true") &&
                                        it.startsWith("schema=markflow-real-jcef-review-scroll/v1") }
                                )
                                Files.writeString(
                                    dest.resolve("preview-real-scroll-position.txt"),
                                    driver.ui.jcef().callJs(
                                        generalDocumentPreviewScrollProbe(entry.line)
                                    ) + "\n"
                                )
                            }
                            fun captureReferencePaint(): Boolean {
                                robot.waitForIdle()
                                val image = robot.createScreenCapture(requireNotNull(reference))
                                check(ImageIO.write(
                                    image, "png", dest.resolve("intellij-preview.png").toFile()
                                ))
                                return diagnostics.referenceHasVisibleContent(image)
                            }
                            if (contract == "acceptance") {
                                // FULL_CORPUS_REFERENCE_BLANK_RECOVERY_ONCE:
                                // after many sequential document switches the bundled JCEF
                                // Preview can remain visibly blank while its Swing surface is
                                // still showing. One source-preserving native->preview cycle is
                                // allowed before failing closed. This is reference-side recovery,
                                // never acceptance relaxation or a MarkFlow renderer retry.
                                when (val firstPaintFailure = runCatching {
                                    waitFor(
                                        message = "Bundled JCEF first paint: " + entry.id,
                                        timeout = 20.seconds,
                                        getter = ::captureReferencePaint,
                                        checker = { it }
                                    )
                                }.exceptionOrNull()) {
                                    null -> Unit
                                    is WaitForException -> {
                                        val beforeRecoveryCaret = driver.withContext(OnDispatcher.EDT) {
                                            preview.primaryCaretOffset(remoteEditor)
                                        }
                                        val firstCapture = dest.resolve("intellij-preview.png")
                                        if (Files.exists(firstCapture)) {
                                            Files.write(
                                                dest.resolve("intellij-preview-before-recovery.png"),
                                                Files.readAllBytes(firstCapture)
                                            )
                                        }
                                        val firstFailureLifecycle =
                                            driver.withContext(OnDispatcher.EDT) {
                                                preview.previewLifecycleEvidence(remoteEditor)
                                            }
                                        System.err.println(
                                            "FULL_DIFFERENTIAL_REFERENCE_FIRST_PAINT_FAILURE case=" +
                                                entry.id + "\n" + firstFailureLifecycle
                                        )
                                        Files.writeString(
                                            dest.resolve("reference-recovery.txt"),
                                            "schema=markflow-reference-blank-recovery/v1\n" +
                                                "case_id=" + entry.id + "\n" +
                                                "initial_result=BLANK_AFTER_20S\n" +
                                                "recovery_attempts=1\n" +
                                                "phase=first-paint-failure\n" +
                                                firstFailureLifecycle,
                                            StandardCharsets.UTF_8
                                        )
                                        val recoveryCloseMarker = dest.resolve(
                                            "reference-native-close-recovery.marker"
                                        ).toAbsolutePath().normalize()
                                        driver.withContext(OnDispatcher.EDT) {
                                            preview.armDetachedReferenceBrowserCloseMarker(
                                                recoveryCloseMarker.toString()
                                            )
                                            preview.restoreNative(remoteEditor)
                                        }
                                        waitFor(
                                            message = "Bundled Preview panel detached for recovery: " +
                                                entry.id,
                                            timeout = 20.seconds,
                                            getter = { driver.withContext(OnDispatcher.EDT) {
                                                preview.nativeShowing(remoteEditor) &&
                                                    !preview.referencePanelAttached(remoteEditor)
                                            } },
                                            checker = { it }
                                        )
                                        Files.writeString(
                                            dest.resolve("reference-recovery.txt"),
                                            "detached_before_retry=true\n" +
                                                driver.withContext(OnDispatcher.EDT) {
                                                    preview.previewLifecycleEvidence(remoteEditor)
                                                } +
                                                "phase=native-close-wait-start\n" +
                                                driver.withContext(OnDispatcher.EDT) {
                                                    preview.detachedReferenceBrowserEvidence()
                                                },
                                            StandardCharsets.UTF_8,
                                            java.nio.file.StandardOpenOption.APPEND
                                        )
                                        waitFor(
                                            message = "Bundled Preview native browser closed for recovery: " +
                                                entry.id,
                                            timeout = 20.seconds,
                                            getter = { Files.exists(recoveryCloseMarker) },
                                            checker = { it }
                                        )
                                        heartbeat("case-reference-browser-closed", entry.id)
                                        Files.writeString(
                                            dest.resolve("reference-recovery.txt"),
                                            "phase=native-close-complete\n" +
                                                driver.withContext(OnDispatcher.EDT) {
                                                    preview.detachedReferenceBrowserEvidence()
                                                },
                                            StandardCharsets.UTF_8,
                                            java.nio.file.StandardOpenOption.APPEND
                                        )
                                        check(driver.withContext(OnDispatcher.EDT) {
                                            preview.sourceText(remoteEditor) == source &&
                                                preview.sourceStamp(remoteEditor) == stamp &&
                                                preview.primaryCaretOffset(remoteEditor) ==
                                                    beforeRecoveryCaret &&
                                                !preview.sourceUnsaved(remoteEditor)
                                        }) {
                                            "Bundled Preview recovery altered authoritative source state: " +
                                                entry.id
                                        }
                                        refIdentity = driver.withContext(OnDispatcher.EDT) {
                                            preview.showReferenceAtSourceLine(remoteEditor, entry.line)
                                        }
                                        waitFor(
                                            message = "Bundled preview visible after recovery: " +
                                                entry.id,
                                            timeout = 30.seconds,
                                            getter = { driver.withContext(OnDispatcher.EDT) {
                                                preview.referenceShowing(remoteEditor)
                                            } },
                                            checker = { it }
                                        )
                                        waitFor(
                                            message = "Bundled Preview panel ready after recovery: " +
                                                entry.id,
                                            timeout = 20.seconds,
                                            getter = { driver.withContext(OnDispatcher.EDT) {
                                                preview.referencePanelShowing(remoteEditor)
                                            } },
                                            checker = { it }
                                        )
                                        val retryLifecycle = driver.withContext(OnDispatcher.EDT) {
                                            preview.previewLifecycleEvidence(remoteEditor)
                                        }
                                        System.err.println(
                                            "FULL_DIFFERENTIAL_REFERENCE_REATTACHED case=" +
                                                entry.id + "\n" + retryLifecycle
                                        )
                                        Files.writeString(
                                            dest.resolve("reference-recovery.txt"),
                                            "reattached_after_retry=true\n" +
                                                "phase=after-reattach\n" +
                                                retryLifecycle,
                                            StandardCharsets.UTF_8,
                                            java.nio.file.StandardOpenOption.APPEND
                                        )
                                        reference = driver.withContext(OnDispatcher.EDT) {
                                            val xywh = preview.referenceBounds(remoteEditor)
                                                .split(',').map(String::toInt)
                                            check(xywh.size == 4)
                                            Rectangle(xywh[0], xywh[1], xywh[2], xywh[3])
                                        }
                                        try {
                                            waitFor(
                                                message = "Bundled JCEF paints after single recovery: " +
                                                    entry.id,
                                                timeout = 60.seconds,
                                                getter = ::captureReferencePaint,
                                                checker = { it }
                                            )
                                        } catch (cause: WaitForException) {
                                            val failedRetryLifecycle =
                                                driver.withContext(OnDispatcher.EDT) {
                                                    preview.previewLifecycleEvidence(remoteEditor)
                                                }
                                            System.err.println(
                                                "FULL_DIFFERENTIAL_REFERENCE_RECOVERY_PAINT_FAILURE case=" +
                                                    entry.id + "\n" + failedRetryLifecycle
                                            )
                                            Files.writeString(
                                                dest.resolve("reference-recovery.txt"),
                                                "phase=recovery-paint-failure\n" +
                                                    failedRetryLifecycle,
                                                StandardCharsets.UTF_8,
                                                java.nio.file.StandardOpenOption.APPEND
                                            )
                                            throw cause
                                        }
                                        check(driver.withContext(OnDispatcher.EDT) {
                                            preview.sourceText(remoteEditor) == source &&
                                                preview.sourceStamp(remoteEditor) == stamp &&
                                                preview.primaryCaretOffset(remoteEditor) ==
                                                    beforeRecoveryCaret &&
                                                !preview.sourceUnsaved(remoteEditor)
                                        }) {
                                            "Bundled Preview recovery changed authoritative source state: " +
                                                entry.id
                                        }
                                        Files.writeString(
                                            dest.resolve("reference-recovery.txt"),
                                            "recovery_result=PAINTED\n",
                                            StandardCharsets.UTF_8,
                                            java.nio.file.StandardOpenOption.APPEND
                                        )
                                    }
                                    else -> throw firstPaintFailure
                                }
                            } else {
                                waitFor(
                                    message = "Bundled JCEF paints nonblank source: " + entry.id,
                                    timeout = 60.seconds,
                                    getter = ::captureReferencePaint,
                                    checker = { it }
                                )
                            }
                            heartbeat("case-reference-painted", entry.id)
                        } else {
                            check(entry.classification == "degraded" &&
                                entry.visualExpectation == "diagnostic-only") {
                                "Only manifest-declared diagnostic cases may omit IntelliJ Preview"
                            }
                            Files.writeString(
                                dest.resolve("reference-disposition.txt"),
                                "schema=markflow-differential-reference-disposition/v1\n" +
                                    "case_id=" + entry.id + "\n" +
                                    "intellij_preview=NOT_APPLICABLE_BY_MANIFEST\n" +
                                    "visual_reference_expectation=" + entry.visualExpectation + "\n" +
                                    "classification=" + entry.classification + "\n"
                            )
                        }
                        // E10: independently measure the genuine Preview Mermaid SVG.
                        // Source-identifying text selects a real diagram rather than any
                        // incidental IDE/browser icon. These are DIAGNOSTIC values until
                        // a maintainer has reviewed a structural viewport contract.
                        if (entry.id == "mermaid-flowchart-minimal" || entry.id == "mermaid-gantt") {
                            val requiredLabel = if (entry.id == "mermaid-gantt") {
                                "MarkFlow Example Schedule"
                            } else {
                                "Rendered correctly?"
                            }
                            val svgEvidence = driver.ui.jcef().callJs(
                                mermaidReferenceSvgProbe(requiredLabel)
                            )
                            Files.writeString(
                                dest.resolve("intellij-mermaid-svg-dom-geometry.txt"),
                                svgEvidence + "\n", StandardCharsets.UTF_8
                            )
                            check(svgEvidence.startsWith(
                                "schema=markflow-reference-mermaid-svg-dom-geometry/v1\n"
                            )) {
                                "E10 genuine bundled Mermaid SVG not source-identifiable; " +
                                    "retained reference probe: " + svgEvidence.take(500)
                            }
                            val nativeRow = nativeRasterGeometry.lineSequence().single {
                                it.startsWith("index=")
                            }.split('\t').associate { property ->
                                val fields = property.split('=', limit = 2)
                                check(fields.size == 2) { "E10 native raster field malformed" }
                                fields[0] to fields[1]
                            }
                            val svgRows = svgEvidence.lineSequence()
                                .filter { '=' in it }.associate {
                                    it.substringBefore('=') to it.substringAfter('=')
                                }
                            fun nativeDimension(name: String): Int =
                                requireNotNull(nativeRow[name]?.toIntOrNull()) {
                                    "E10 native raster missing " + name
                                }
                            fun svgDimension(name: String): Double =
                                requireNotNull(svgRows[name]?.toDoubleOrNull()) {
                                    "E10 reference SVG missing " + name
                                }
                            val nativeDisplayedWidth = nativeDimension("displayed_width")
                            val nativeDisplayedHeight = nativeDimension("displayed_height")
                            val svgCssWidth = svgDimension("svg_css_width")
                            val svgCssHeight = svgDimension("svg_css_height")
                            check(svgCssWidth.isFinite() && svgCssWidth > 0.0 &&
                                svgCssHeight.isFinite() && svgCssHeight > 0.0)
                            Files.writeString(
                                dest.resolve("mermaid-native-reference-size-diagnostic.tsv"),
                                "schema=markflow-mermaid-native-reference-size/v1\n" +
                                    "gate=DIAGNOSTIC_ONLY_NO_REVIEWED_THRESHOLD\n" +
                                    "case_id\tartifact_width_px\tartifact_height_px\t" +
                                    "native_width_px\tnative_height_px\t" +
                                    "reference_svg_width_css_px\treference_svg_height_css_px\t" +
                                    "native_to_reference_width_ratio\tnative_to_reference_height_ratio\n" +
                                    listOf(entry.id,
                                        nativeDimension("intrinsic_width"),
                                        nativeDimension("intrinsic_height"),
                                        nativeDisplayedWidth, nativeDisplayedHeight,
                                        svgCssWidth, svgCssHeight,
                                        nativeDisplayedWidth / svgCssWidth,
                                        nativeDisplayedHeight / svgCssHeight
                                    ).joinToString("\t") + "\n"
                            )
                        }
                        // Stage E2: real bundled JCEF DOM headings, identified by exact
                        // source text and ordered source lines, not screenshot offsets.
                        // Driver JCEF JS queries run against the loaded platform preview.
                        if (entry.id == "md-atx-headings-h1-h6") {
                            val dom = driver.ui.jcef()
                            val referenceHeadingLedger = dom.callJs(headingReferenceDomProbe)
                            check(referenceHeadingLedger.startsWith(
                                "schema=markflow-reference-heading-dom-geometry/v1\n"
                            )) { "Platform JCEF DOM heading probe not authoritative" }
                            val result = compareHeadingSourceCheckpoints(
                                nativeHeadingGeometry, referenceHeadingLedger
                            )
                            Files.writeString(
                                dest.resolve("intellij-heading-dom-geometry.txt"),
                                referenceHeadingLedger + "\n", StandardCharsets.UTF_8
                            )
                            Files.writeString(
                                dest.resolve("source-heading-relative-drift.tsv"),
                                result, StandardCharsets.UTF_8
                            )
                            // Stage E5: never gate a source-line Y against a DOM
                            // element top. They describe different layout objects.
                            // Preserve the legacy diagnostic series, but compare the
                            // actual installed native inlay rectangles separately.
                            val boxLedger = compareRenderedHeadingBoxes(
                                nativeHeadingGeometry, referenceHeadingLedger
                            )
                            Files.writeString(
                                dest.resolve("source-heading-rendered-box-drift.tsv"),
                                boxLedger, StandardCharsets.UTF_8
                            )
                            // E6: match actual following source paragraphs to the real
                            // Preview DOM Range text rectangles, not outer CSS boxes.
                            val sourceLines = source.split('\n')
                            for (index in 0 until 6) {
                                val sourceLine = 4 * index + 2
                                check(sourceLines[sourceLine] == "Paragraph after H" + (index + 1) + ".") {
                                    "Stage E6 fixture source identity changed"
                                }
                            }
                            val paragraphDom = dom.callJs(followingParagraphDomProbe)
                            check(paragraphDom.startsWith(
                                "schema=markflow-reference-paragraph-text-range/v1\n"
                            )) { "Genuine platform paragraph text ranges unavailable" }
                            val paragraphDelta = compareFollowingTextAnchors(
                                nativeHeadingGeometry, paragraphDom
                            )
                            Files.writeString(
                                dest.resolve("intellij-following-paragraph-dom-geometry.txt"),
                                paragraphDom + "\n", StandardCharsets.UTF_8
                            )
                            Files.writeString(
                                dest.resolve("source-following-text-line-drift.tsv"),
                                paragraphDelta, StandardCharsets.UTF_8
                            )
                            Files.writeString(
                                dest.resolve("heading-layout-reference-policy.txt"),
                                "schema=markflow-heading-reference-policy/v1\n" +
                                    "policy=PREVIEW_DIAGNOSTIC_ONLY_MARKFLOW_FIRST\n" +
                                    "markflow_heading_contract=OWNED_SOURCE_GEOMETRY_AND_REVIEWED_GOLDEN\n" +
                                    "preview_pixel_or_y_parity=NOT_REQUIRED\n"
                            )
                        }
                        check(driver.withContext(OnDispatcher.EDT) {
                            preview.sourceText(remoteEditor)
                        } == source)
                        check(driver.withContext(OnDispatcher.EDT) {
                            preview.sourceStamp(remoteEditor)
                        } == stamp)
                        check(!driver.withContext(OnDispatcher.EDT) {
                            preview.sourceUnsaved(remoteEditor)
                        })
                        if (entry.comparable) {
                            driver.withContext(OnDispatcher.EDT) { preview.restoreNative(remoteEditor) }
                            waitFor(
                                message = "Native restored: " + entry.id, timeout = 20.seconds,
                                getter = { driver.withContext(OnDispatcher.EDT) {
                                    preview.nativeShowing(remoteEditor)
                                } },
                                checker = { it }
                            )
                        } else {
                            check(driver.withContext(OnDispatcher.EDT) {
                                preview.nativeShowing(remoteEditor)
                            }) { "Native editor disappeared without opening a reference surface" }
                        }
                        check(markFlow.source(editor) == source)
                        check(Files.readAllBytes(project.resolve(entry.id + ".md")).contentEquals(entry.bytes))
                        Files.writeString(dest.resolve("identity.txt"),
                            Files.readString(dest.resolve("identity.txt")) +
                                "reference=" + refIdentity + "\nnative_viewport=" +
                                native.width + "x" + native.height + "\npreview_viewport=" +
                                (reference?.let { it.width.toString() + "x" + it.height }
                                    ?: "NOT_APPLICABLE") + "\n" + nativeEnvironment)
                        if (entry.comparable) {
                            diagnostics.writeDiagnosticPair(dest, sha(entry.bytes), entry.line)
                        }
                        // E11: keep the measured FIT_TO_VIEWPORT capture as A, then
                        // measure the *same* production Editor with the existing
                        // SHRINK_TO_FIT setting as B. This is a test-only A/B:
                        // no new rendering implementation or relaxed acceptance.
                        if (entry.id == "mermaid-flowchart-minimal" || entry.id == "mermaid-gantt") {
                            val originalSource = source
                            val originalStamp = stamp
                            driver.withContext(OnDispatcher.EDT) {
                                visual.setMermaidSizeModeForDifferential(
                                    remoteEditor, "SHRINK_TO_FIT"
                                )
                            }
                            fun rasterRecord(geometry: String): Map<String, Int> {
                                val rows = geometry.lineSequence()
                                    .filter { it.startsWith("index=") }.toList()
                                check(rows.size == 1) { "E11 requires one real native raster" }
                                return rows.single().split('\t').associate { field ->
                                    val values = field.split('=', limit = 2)
                                    check(values.size == 2) { "E11 raster ledger malformed" }
                                    values[0] to requireNotNull(values[1].toIntOrNull()) {
                                        "E11 raster ledger value missing"
                                    }
                                }
                            }
                            val originalRaster = rasterRecord(nativeRasterGeometry)
                            // Mermaid's size mode also changes SVG useMaxWidth;
                            // the renderer may legitimately generate a different
                            // *intrinsic* PNG. Never assume FIT and SHRINK rasters
                            // are byte-/dimension-identical. Compare each mode
                            // against its own genuine decoded PNG instead.
                            waitFor(
                                message = "E11 real-IDE native SHRINK_TO_FIT settled",
                                timeout = 50.seconds,
                                getter = { driver.withContext(OnDispatcher.EDT) {
                                    runCatching {
                                        val visualState = visual.visualEvidence(remoteEditor)
                                        val raster = rasterRecord(
                                            preview.nativeRasterGeometry(remoteEditor)
                                        )
                                        visualState.contains("derivedPending=0") &&
                                            raster.getValue("displayed_width") in
                                                1..raster.getValue("intrinsic_width") &&
                                            raster.getValue("displayed_height") in
                                                1..raster.getValue("intrinsic_height")
                                    }.getOrDefault(false)
                                } },
                                checker = { it }
                            )
                            val shrinkEvidence = driver.withContext(OnDispatcher.EDT) {
                                preview.nativeRasterGeometry(remoteEditor)
                            }
                            val shrinkRaster = rasterRecord(shrinkEvidence)
                            check(shrinkRaster.getValue("displayed_width") in
                                    1..shrinkRaster.getValue("intrinsic_width") &&
                                shrinkRaster.getValue("displayed_height") in
                                    1..shrinkRaster.getValue("intrinsic_height")) {
                                "E11 SHRINK_TO_FIT enlarged or collapsed the rendered PNG"
                            }
                            Files.writeString(
                                dest.resolve("mermaid-shrink-native-raster-geometry.txt"),
                                shrinkEvidence
                            )
                            robot.waitForIdle()
                            check(ImageIO.write(robot.createScreenCapture(native), "png",
                                dest.resolve("mermaid-shrink-native.png").toFile()))
                            Files.writeString(
                                dest.resolve("mermaid-fit-versus-shrink-diagnostic.tsv"),
                                "schema=markflow-mermaid-real-ide-size-mode-ab/v1\n" +
                                    "gate=DIAGNOSTIC_ONLY_NO_REVIEWED_VISUAL_BASELINE\n" +
                                    "case_id\tmode\tintrinsic_width_px\tintrinsic_height_px\t" +
                                    "displayed_width_px\tdisplayed_height_px\n" +
                                    listOf(entry.id, "FIT_TO_VIEWPORT",
                                        originalRaster.getValue("intrinsic_width"),
                                        originalRaster.getValue("intrinsic_height"),
                                        originalRaster.getValue("displayed_width"),
                                        originalRaster.getValue("displayed_height"))
                                        .joinToString("\t") + "\n" +
                                    listOf(entry.id, "SHRINK_TO_FIT",
                                        shrinkRaster.getValue("intrinsic_width"),
                                        shrinkRaster.getValue("intrinsic_height"),
                                        shrinkRaster.getValue("displayed_width"),
                                        shrinkRaster.getValue("displayed_height"))
                                        .joinToString("\t") + "\n"
                            )
                            // Source authority, caret and mod stamp must survive
                            // the size-mode change; restore the original mode so
                            // subsequent cases are not coupled through settings.
                            check(driver.withContext(OnDispatcher.EDT) {
                                preview.sourceText(remoteEditor) == originalSource &&
                                    preview.sourceStamp(remoteEditor) == originalStamp
                            }) { "E11 mode A/B mutated the authoritative source" }
                            driver.withContext(OnDispatcher.EDT) {
                                visual.setMermaidSizeModeForDifferential(
                                    remoteEditor, "FIT_TO_VIEWPORT"
                                )
                            }
                        }

                        // #352: FIT_TO_VIEWPORT is intentionally the overview mode. For the
                        // representative wide/tall large-flowchart blocks, prove the documented
                        // detail mode really preserves the decoded raster size and that every
                        // oversized edge is reachable in the actual IntelliJ editor.
                        if (entry.id == "mermaid-flowchart-large--block-01" ||
                            entry.id == "mermaid-flowchart-large--block-02") {
                            val originalSource = source
                            val originalStamp = stamp
                            val targetSourceLine = requireNotNull(
                                targetRasterRecord?.get("source_line_zero_based")?.toIntOrNull()
                            ) { "#352 target source line unavailable before ACTUAL_SIZE_SCROLL" }
                            val beforeVertical = driver.withContext(OnDispatcher.EDT) {
                                preview.nativeVerticalScrollOffset(remoteEditor)
                            }
                            val beforeHorizontal = driver.withContext(OnDispatcher.EDT) {
                                preview.nativeHorizontalScrollOffset(remoteEditor)
                            }
                            var actualFailure: Throwable? = null
                            try {
                                driver.withContext(OnDispatcher.EDT) {
                                    visual.setMermaidSizeModeForDifferential(
                                        remoteEditor, "ACTUAL_SIZE_SCROLL"
                                    )
                                }
                                fun actualRecords(geometry: String): List<Map<String, String>> =
                                    geometry.lineSequence().filter { it.startsWith("index=") }
                                        .map { row ->
                                            row.split('\t').associate { field ->
                                                val values = field.split('=', limit = 2)
                                                check(values.size == 2) {
                                                    "#352 ACTUAL raster ledger malformed"
                                                }
                                                values[0] to values[1]
                                            }
                                        }.toList()
                                waitFor(
                                    message = "#352 ACTUAL_SIZE_SCROLL preserves intrinsic raster: " + entry.id,
                                    timeout = 50.seconds,
                                    getter = { driver.withContext(OnDispatcher.EDT) {
                                        runCatching {
                                            val state = visual.visualEvidence(remoteEditor)
                                            val rows = actualRecords(
                                                preview.nativeRasterGeometry(remoteEditor)
                                            )
                                            val target = rows.single {
                                                it["source_line_zero_based"]?.toIntOrNull() ==
                                                    targetSourceLine
                                            }
                                            state.contains("derivedPending=0") &&
                                                target["displayed_width"] == target["intrinsic_width"] &&
                                                target["displayed_height"] == target["intrinsic_height"]
                                        }.getOrDefault(false)
                                    } },
                                    checker = { it }
                                )
                                val actualGeometry = driver.withContext(OnDispatcher.EDT) {
                                    preview.nativeRasterGeometry(remoteEditor)
                                }
                                val actualRows = actualRecords(actualGeometry)
                                val actualTarget = actualRows.single {
                                    it["source_line_zero_based"]?.toIntOrNull() == targetSourceLine
                                }
                                val actualIndex = requireNotNull(
                                    actualTarget["index"]?.toIntOrNull()
                                )
                                check(actualTarget["displayed_width"] == actualTarget["intrinsic_width"] &&
                                    actualTarget["displayed_height"] == actualTarget["intrinsic_height"]) {
                                    "#352 ACTUAL_SIZE_SCROLL did not retain exact decoded raster dimensions"
                                }
                                Files.writeString(
                                    dest.resolve("mermaid-actual-native-raster-geometry.txt"),
                                    actualGeometry, StandardCharsets.UTF_8
                                )
                                driver.withContext(OnDispatcher.EDT) {
                                    preview.restoreNativeVerticalScroll(remoteEditor, beforeVertical)
                                    preview.restoreNativeHorizontalScroll(remoteEditor, beforeHorizontal)
                                }
                                robot.waitForIdle()
                                check(ImageIO.write(
                                    robot.createScreenCapture(native), "png",
                                    dest.resolve("mermaid-actual-native.png").toFile()
                                ))
                                for (edge in listOf("TOP", "BOTTOM")) {
                                    val ledger = waitFor(
                                        message = "#352 ACTUAL vertical edge reachable: " +
                                            entry.id + " " + edge,
                                        timeout = 20.seconds,
                                        getter = { driver.withContext(OnDispatcher.EDT) {
                                            preview.nativeRasterScrollProbe(
                                                remoteEditor, actualIndex, edge
                                            )
                                        } },
                                        checker = { it.lineSequence().any { row ->
                                            row == "edge_visible=true"
                                        } }
                                    )
                                    Files.writeString(
                                        dest.resolve(
                                            "mermaid-actual-scroll-" + edge.lowercase() + ".txt"
                                        ),
                                        ledger, StandardCharsets.UTF_8
                                    )
                                }
                                for (edge in listOf("LEFT", "RIGHT")) {
                                    val ledger = waitFor(
                                        message = "#352 ACTUAL horizontal edge reachable: " +
                                            entry.id + " " + edge,
                                        timeout = 20.seconds,
                                        getter = { driver.withContext(OnDispatcher.EDT) {
                                            preview.nativeRasterHorizontalScrollProbe(
                                                remoteEditor, actualIndex, edge
                                            )
                                        } },
                                        checker = { it.lineSequence().any { row ->
                                            row == "edge_visible=true"
                                        } }
                                    )
                                    Files.writeString(
                                        dest.resolve(
                                            "mermaid-actual-scroll-" + edge.lowercase() + ".txt"
                                        ),
                                        ledger, StandardCharsets.UTF_8
                                    )
                                }
                                check(driver.withContext(OnDispatcher.EDT) {
                                    preview.sourceText(remoteEditor) == originalSource &&
                                        preview.sourceStamp(remoteEditor) == originalStamp
                                }) { "#352 ACTUAL size/scroll probes mutated authoritative source" }
                            } catch (cause: Throwable) {
                                actualFailure = cause
                                throw cause
                            } finally {
                                val cleanup = runCatching {
                                    driver.withContext(OnDispatcher.EDT) {
                                        preview.restoreNativeVerticalScroll(
                                            remoteEditor, beforeVertical
                                        )
                                        preview.restoreNativeHorizontalScroll(
                                            remoteEditor, beforeHorizontal
                                        )
                                        visual.setMermaidSizeModeForDifferential(
                                            remoteEditor, "FIT_TO_VIEWPORT"
                                        )
                                    }
                                }
                                val cleanupError = cleanup.exceptionOrNull()
                                if (cleanupError != null) {
                                    if (actualFailure == null) throw cleanupError
                                    actualFailure.addSuppressed(cleanupError)
                                }
                            }
                        }
                        // FULL_CORPUS_CASE_EDITOR_LIFECYCLE_SINGLE_OWNER:
                        // Do not close the primary case editor here. The finally block below owns
                        // the only lifecycle transition for it: hide bundled Preview, prove the
                        // Markdown JCEF panel detached, then close the exact FileEditor/native Editor.
                        // Auxiliary H1 A/B editors use different files and retain their own cleanup.
                        if (entry.id == "md-atx-headings-h1-h6") {
                            // C is the actual product default; A is the frozen
                            // legacy baseline, B the pinned JCEF typography probe.
                            // The same authoritative source bytes drive all three.
                            val cDest = output.resolve("heading-layout-product")
                            Files.createDirectories(cDest)
                            val candidate = markFlow.openMarkdown("heading-layout-product.md")
                            val cEditor = candidate.editor
                            var candidateFailure: Throwable? = null
                            try {
                                markFlow.assertProductionProjectionAttached(candidate)
                                check(markFlow.source(candidate) == source)
                                val cStamp = markFlow.modificationStamp(candidate)
                                driver.withContext(OnDispatcher.EDT) { visual.prepare(cEditor) }
                                markFlow.resetToSingleCaret(candidate, source.length)
                                driver.withContext(OnDispatcher.EDT) {
                                    preview.showNativeAtSourceLine(cEditor, entry.line)
                                }
                                waitFor(
                                    message = "Real product Heading line box settled",
                                    timeout = 50.seconds,
                                    getter = { driver.withContext(OnDispatcher.EDT) {
                                        visual.visualEvidence(cEditor)
                                    } },
                                    checker = { it.contains("derivedPending=0") }
                                )
                                val cLedger = driver.withContext(OnDispatcher.EDT) {
                                    preview.nativeHeadingReplacementGeometry(cEditor)
                                }
                                Files.writeString(cDest.resolve("native-product-heading-geometry.txt"),
                                    cLedger)
                                check(cLedger.contains("legacy_heading_inlay_count=0")) {
                                    "Product Heading unexpectedly contains an additive block inlay"
                                }
                                check(cLedger.contains("co_start_inline_pilot_count=1")) {
                                    "H1 co-start foreign section fallback is missing"
                                }
                                val customs = cLedger.lineSequence().single {
                                    it.startsWith("measured_atx_heading_count=")
                                }.substringAfter('=').toInt()
                                check(customs == 5) {
                                    "Product Heading H2-H6 replacement count is $customs, not 5"
                                }
                                val area = driver.withContext(OnDispatcher.EDT) {
                                    Rectangle(
                                        visual.contentScreenX(cEditor), visual.contentScreenY(cEditor),
                                        visual.contentWidth(cEditor), visual.contentHeight(cEditor)
                                    )
                                }
                                check(area.width == 1200 && area.height == 760)
                                robot.waitForIdle()
                                check(ImageIO.write(robot.createScreenCapture(area), "png",
                                    cDest.resolve("markflow-production.png").toFile()))
                                check(markFlow.source(candidate) == source &&
                                    markFlow.modificationStamp(candidate) == cStamp &&
                                    Files.readAllBytes(project.resolve("heading-layout-product.md"))
                                        .contentEquals(entry.bytes))
                                Files.writeString(cDest.resolve("identity.txt"),
                                    "schema=markflow-heading-product-evidence/v1\n" +
                                        "source_head=" + sourceHead + "\n" +
                                        "source_sha256=" + sha(entry.bytes) + "\n" +
                                        "mode=DEFAULT_PRODUCT_NATIVE\n" +
                                        "visual_acceptance=UNVERIFIED\n")
                                // Validate actual live IDE source-owner dimensions
                                // at each font setting, not only em-policy math.
                                // Per-editor scheme is restored in finally and the
                                // authoritative Markdown source/stamp never changes.
                                val observed = mutableListOf<Pair<Int, Int>>()
                                var fontProbeFailure: Throwable? = null
                                try {
                                    for (fontPx in listOf(11, 13, 16, 20)) {
                                        val applied = driver.withContext(OnDispatcher.EDT) {
                                            preview.setNativeEditorFontSize(cEditor, fontPx)
                                        }
                                        val geometry = driver.withContext(OnDispatcher.EDT) {
                                            preview.nativeHeadingReplacementGeometry(cEditor)
                                        }
                                        Files.writeString(
                                            cDest.resolve("font-$fontPx-native-geometry.txt"),
                                            applied + geometry
                                        )
                                        val actualPx = applied.lineSequence().single {
                                            it.startsWith("actual_native_font_px=")
                                        }.substringAfter('=').toFloat()
                                        check(kotlin.math.abs(actualPx - fontPx) <= 1.0f) {
                                            "Editor font changed to unexpected size: $actualPx vs $fontPx"
                                        }
                                        check(geometry.contains("legacy_heading_inlay_count=0") &&
                                            geometry.contains("co_start_inline_pilot_count=1")) {
                                            "Product Heading owner topology changed during font reflow"
                                        }
                                        val h2 = geometry.lineSequence().single {
                                            it.startsWith("level=2\t")
                                        }
                                        val heightPx = requireNotNull(
                                            Regex("fold_height_px=(\\d+)").find(h2)
                                        ) {
                                            "H2 custom fold height absent at $fontPx editor px"
                                        }.groupValues[1].toInt()
                                        check(heightPx > 0)
                                        observed += fontPx to heightPx
                                        robot.waitForIdle()
                                        check(ImageIO.write(
                                            robot.createScreenCapture(area), "png",
                                            cDest.resolve("font-$fontPx-product.png").toFile()
                                        ))
                                        check(markFlow.source(candidate) == source &&
                                            markFlow.modificationStamp(candidate) == cStamp)
                                    }
                                    check(observed.zipWithNext().all { (smaller, larger) ->
                                        smaller.first < larger.first &&
                                            smaller.second < larger.second
                                    }) {
                                        "CustomFold did not reflow proportionally with actual IDE font size: $observed"
                                    }
                                    Files.writeString(
                                        cDest.resolve("font-size-reflow.txt"),
                                        "schema=markflow-heading-real-ide-reflow/v1\n" +
                                            "source_head=$sourceHead\n" +
                                            "observed_h2_fold_heights=" +
                                            observed.joinToString(",") { (f, h) -> "$f:$h" } + "\n"
                                    )
                                } catch (cause: Throwable) {
                                    fontProbeFailure = cause
                                    throw cause
                                } finally {
                                    val restored = runCatching {
                                        driver.withContext(OnDispatcher.EDT) {
                                            preview.restoreNativeEditorFontSize(cEditor)
                                        }
                                    }
                                    val restoreError = restored.exceptionOrNull()
                                    if (restoreError != null) {
                                        if (fontProbeFailure == null) throw restoreError
                                        fontProbeFailure.addSuppressed(restoreError)
                                    }
                                }
                                check(markFlow.source(candidate) == source &&
                                    markFlow.modificationStamp(candidate) == cStamp)

                            } catch (cause: Throwable) {
                                candidateFailure = cause
                                throw cause
                            } finally {
                                val cleanup = runCatching { markFlow.close(candidate) }
                                val cleanupError = cleanup.exceptionOrNull()
                                if (cleanupError != null) {
                                    if (candidateFailure == null) throw cleanupError
                                    candidateFailure.addSuppressed(cleanupError)
                                }
                            }
                            val abDest = output.resolve("heading-layout-ab")
                            Files.createDirectories(abDest)
                            val b = markFlow.openMarkdown("heading-layout-b.md")
                            val bEditor = b.editor
                            var bFailure: Throwable? = null
                            try {
                                markFlow.assertProductionProjectionAttached(b)
                                check(markFlow.source(b) == source)
                                val bStamp = markFlow.modificationStamp(b)
                                driver.withContext(OnDispatcher.EDT) { visual.prepare(bEditor) }
                                markFlow.resetToSingleCaret(b, source.length)
                                val bCaretBefore = markFlow.primaryCaretOffset(b)
                                check(bCaretBefore == source.length)
                                driver.withContext(OnDispatcher.EDT) {
                                    preview.showNativeAtSourceLine(bEditor, entry.line)
                                }
                                waitFor(
                                    message = "CustomFold IDE lifecycle settled",
                                    timeout = 50.seconds,
                                    getter = { driver.withContext(OnDispatcher.EDT) {
                                        visual.visualEvidence(bEditor)
                                    } },
                                    checker = { it.contains("derivedPending=0") }
                                )
                                val nativeB = driver.withContext(OnDispatcher.EDT) {
                                    preview.nativeHeadingReplacementGeometry(bEditor)
                                }
                                // Retain the actual five/six-fold observation BEFORE
                                // any later screen or JCEF probe can fail.
                                Files.writeString(
                                    abDest.resolve("native-b-customfold.txt"), nativeB
                                )
                                val bCustomCount = nativeB.lineSequence().single {
                                    it.startsWith("measured_atx_heading_count=")
                                }.substringAfter('=').toInt()
                                val bMissing = nativeB.lineSequence().single {
                                    it.startsWith("missing_heading_levels=")
                                }.substringAfter('=')
                                val bShowing = nativeB.lineSequence().single {
                                    it.startsWith("editor_is_showing=")
                                }.substringAfter('=') == "true"
                                // A CustomFold at H1's 0-offset is rejected by IDEA
                                // because the platform owns an expanded 0..EOF
                                // section. The explicit opt-in candidate uses a
                                // same-line InlineElement + a collapsed *owned*
                                // ordinary FoldRegion instead. Demand evidence
                                // for both owners; 5 CustomFolds alone is NOT 6/6.
                                val bInlineCount = nativeB.lineSequence().single {
                                    it.startsWith("co_start_inline_pilot_count=")
                                }.substringAfter('=').toInt()
                                val h1Evidence = nativeB.lineSequence().single {
                                    it.startsWith("level=1\t")
                                }.split('\t').associate { part ->
                                    val pair = part.split('=', limit = 2)
                                    check(pair.size == 2) { "Malformed H1 geometry evidence" }
                                    pair[0] to pair[1]
                                }
                                val h1InlineInstalled =
                                    bInlineCount == 1 &&
                                    h1Evidence["fold_status"] == "MISSING" &&
                                    h1Evidence["inline_pilot_status"] == "INSTALLED_UNREVIEWED" &&
                                    h1Evidence["inline_pilot_source_fold"] == "COLLAPSED_OWN_CANDIDATE" &&
                                    (h1Evidence["inline_pilot_width_px"]?.toIntOrNull() ?: 0) > 1 &&
                                    (h1Evidence["inline_pilot_height_px"]?.toIntOrNull() ?: 0) >=
                                        nativeB.lineSequence().single {
                                            it.startsWith("plain_line_height_px=")
                                        }.substringAfter('=').toInt()
                                val compositeCount = bCustomCount + (if (h1InlineInstalled) 1 else 0)
                                Files.writeString(abDest.resolve("capture-status.txt"),
                                    "candidate=UNVERIFIED\n" +
                                        "installed_heading_customfolds=" + bCustomCount + "\n" +
                                        "installed_heading_inline_pilot=" + (if (h1InlineInstalled) 1 else 0) + "\n" +
                                        "installed_heading_composite=" + compositeCount + "\n" +
                                        "missing_customfold_levels=" + bMissing + "\n" +
                                        "editor_is_showing=" + bShowing + "\n")
                                // A candidate screen obtained while the native Editor is
                                // hidden is not comparable with the JCEF screenshot.
                                check(bShowing) { "E9 B native editor is hidden; diagnostic retained" }
                                val bEnv = driver.withContext(OnDispatcher.EDT) {
                                    visual.environmentIdentity(bEditor)
                                }
                                val bBounds = driver.withContext(OnDispatcher.EDT) {
                                    Rectangle(
                                        visual.contentScreenX(bEditor), visual.contentScreenY(bEditor),
                                        visual.contentWidth(bEditor), visual.contentHeight(bEditor)
                                    )
                                }
                                check(bBounds.width == native.width && bBounds.height == native.height)
                                val nativeBScrollY = driver.withContext(OnDispatcher.EDT) {
                                    preview.nativeVerticalScrollOffset(bEditor)
                                }
                                check(nativeBScrollY >= 0)
                                robot.waitForIdle()
                                check(ImageIO.write(robot.createScreenCapture(bBounds), "png",
                                    abDest.resolve("native-b.png").toFile()))
                                driver.withContext(OnDispatcher.EDT) {
                                    preview.showReferenceAtSourceLine(bEditor, entry.line)
                                }
                                waitFor(
                                    message = "B bundled Preview visible",
                                    timeout = 30.seconds,
                                    getter = { driver.withContext(OnDispatcher.EDT) {
                                        preview.referenceShowing(bEditor)
                                    } },
                                    checker = { it }
                                )
                                val refBounds = driver.withContext(OnDispatcher.EDT) {
                                    val p = preview.referenceBounds(bEditor).split(',').map(String::toInt)
                                    check(p.size == 4)
                                    Rectangle(p[0], p[1], p[2], p[3])
                                }
                                waitFor(
                                    message = "B bundled JCEF rendered nonblank",
                                    timeout = 60.seconds,
                                    getter = {
                                        robot.waitForIdle()
                                        val img = robot.createScreenCapture(refBounds)
                                        check(ImageIO.write(img, "png",
                                            abDest.resolve("preview-b.png").toFile()))
                                        diagnostics.referenceHasVisibleContent(img)
                                    },
                                    checker = { it }
                                )
                                val domB = driver.ui.jcef()
                                val headingB = domB.callJs(headingReferenceDomProbe)
                                val paragraphB = domB.callJs(followingParagraphDomProbe)
                                check(headingB.startsWith(
                                    "schema=markflow-reference-heading-dom-geometry/v1\n"
                                ) && paragraphB.startsWith(
                                    "schema=markflow-reference-paragraph-text-range/v1\n"
                                )) { "Missing actual B source-aligned JCEF DOM evidence" }
                                // Unlike Editor Insets or drawString inputs, this
                                // evidence reads actual ink from both retained
                                // screen captures. It remains diagnostic-only:
                                // raster offsets and DOM Range bounds are not
                                // an approved common box-model contract.
                                val nativeImage = requireNotNull(ImageIO.read(
                                    abDest.resolve("native-b.png").toFile()
                                )) { "Native B screenshot could not be decoded" }
                                val previewImage = requireNotNull(ImageIO.read(
                                    abDest.resolve("preview-b.png").toFile()
                                )) { "JCEF B screenshot could not be decoded" }
                                Files.writeString(
                                    abDest.resolve("heading-ink-x-diagnostic.tsv"),
                                    headingInkXDiagnostic(nativeImage, previewImage, nativeB, headingB)
                                )
                                Files.writeString(
                                    abDest.resolve("heading-ink-y-baseline-diagnostic.tsv"),
                                    headingInkYBaselineDiagnostic(
                                        nativeImage, previewImage, nativeB, headingB, nativeBScrollY
                                    )
                                )
                                val driftB = compareFollowingTextAnchors(nativeB, paragraphB)
                                val driftA = Files.readString(
                                    dest.resolve("source-following-text-line-drift.tsv"))
                                fun tableRows(text: String): List<List<String>> =
                                    text.lineSequence().filter {
                                        it.firstOrNull() in '1'..'6' && it.contains('\t')
                                    }.map { it.split('\t') }.toList()
                                val rowsA = tableRows(driftA)
                                val rowsB = tableRows(driftB)
                                check(rowsA.size == 6 && rowsB.size == 6)
                                val abRows = rowsA.zip(rowsB).mapIndexed { index, (a, bb) ->
                                    check(a.size == 5 && bb.size == 5 &&
                                        a[0] == (index + 1).toString() &&
                                        bb[0] == a[0] &&
                                        a[1] == (4 * index + 2).toString() &&
                                        bb[1] == a[1]) { "A/B source identity mismatch" }
                                    val ya = a[2].toInt()
                                    val yb = bb[2].toInt()
                                    check(a[3].toDouble().isFinite() && bb[3].toDouble().isFinite())
                                    listOf(
                                        a[0], a[1], ya.toString(), yb.toString(),
                                        a[3], bb[3], (ya - yb).toString(),
                                        a[4], bb[4]
                                    ).joinToString("\t")
                                }
                                Files.writeString(abDest.resolve("ab-paragraph-deltas.tsv"),
                                    "schema=markflow-heading-ab-relative-diagnostic/v1\n" +
                                        "gate=DIAGNOSTIC_ONLY_NO_REVIEWED_THRESHOLD\n" +
                                        "level\tsource_line\tnative_a_px\tnative_b_px\tpreview_a_css_px\tpreview_b_css_px\tnative_a_minus_b_px\tdelta_a_px\tdelta_b_px\n" +
                                        abRows.joinToString("\n", postfix = "\n"))
                                Files.writeString(abDest.resolve("native-a-inlay.txt"),
                                    Files.readString(dest.resolve("native-heading-source-geometry.txt")))
                                Files.writeString(abDest.resolve("native-b-customfold.txt"), nativeB)
                                Files.writeString(abDest.resolve("preview-a-heading-dom.txt"),
                                    Files.readString(dest.resolve("intellij-heading-dom-geometry.txt")))
                                Files.writeString(abDest.resolve("preview-b-heading-dom.txt"), headingB)
                                Files.writeString(abDest.resolve("preview-a-paragraph-dom.txt"),
                                    Files.readString(dest.resolve("intellij-following-paragraph-dom-geometry.txt")))
                                Files.writeString(abDest.resolve("preview-b-paragraph-dom.txt"), paragraphB)
                                // SHOW_PREVIEW deliberately hides EditorComponentImpl.
                                // Use the exact remote Editor captured before the switch,
                                // not MarkFlowIdeDriver's visible UI locator.
                                val hiddenIdentity = driver.withContext(OnDispatcher.EDT) {
                                    listOf(
                                        preview.sourceText(bEditor),
                                        preview.sourceStamp(bEditor).toString(),
                                        preview.primaryCaretOffset(bEditor).toString(),
                                    )
                                }
                                check(hiddenIdentity[0] == source)
                                check(hiddenIdentity[1] == bStamp.toString())
                                check(hiddenIdentity[2] == bCaretBefore.toString()) {
                                    "B JCEF capture unexpectedly moved the native caret"
                                }
                                check(!driver.withContext(OnDispatcher.EDT) {
                                    preview.sourceUnsaved(bEditor)
                                })
                                check(Files.readAllBytes(project.resolve("heading-layout-b.md"))
                                    .contentEquals(entry.bytes))
                                Files.writeString(abDest.resolve("identity.txt"),
                                    "schema=markflow-heading-layout-ab/v1\n" +
                                        "source_sha256_a=" + sha(entry.bytes) + "\n" +
                                        "source_sha256_b=" + sha(Files.readAllBytes(
                                            project.resolve("heading-layout-b.md"))) + "\n" +
                                        "source_head=" + sourceHead + "\n" +
                                        "ide_version=" + version + "\n" +
                                        "caret_offset_before=" + bCaretBefore + "\n" +
                                        "caret_offset_after=" + hiddenIdentity[2] + "\n" +
                                        "native_a_environment=" + nativeEnvironment + "\n" +
                                        "native_b_environment=" + bEnv + "\n" +
                                        "legacy_a_fixture=sourceLineReplacement_false\n" +
                                         "production_default=sourceLineReplacement_true\n" +
                                        "full_differential_acceptance=false\n")
                                // The candidate contract is six *owned heading
                                // replacements*, not six instances of the same
                                // JetBrains folding subtype. Every owner must be
                                // individually observed. Preview visual parity is
                                // still separately blocked by the unreviewed
                                // source-aligned DOM/typography anchor contract.
                                if (compositeCount != 6 || bCustomCount != 5 ||
                                    bMissing != "1" ||
                                    !nativeB.contains("legacy_heading_inlay_count=0")) {
                                    structuralFailures += listOf(
                                        entry.id, "HEADING_REPLACEMENT_OWNERSHIP_INCOMPLETE",
                                        "custom=" + bCustomCount + ";inline=" +
                                            (if (h1InlineInstalled) 1 else 0) +
                                            ";missing_custom=" + bMissing,
                                        "5_CUSTOM_PLUS_1_OWNED_INLINE"
                                    ).joinToString("\t")
                                    error("E9 HEADING_REPLACEMENT_OWNERSHIP_INCOMPLETE: " +
                                        compositeCount + "/6; foreign fold evidence retained")
                                }
                            } catch (cause: Throwable) {
                                bFailure = cause
                                throw cause
                            } finally {
                                // SHOW_PREVIEW hides EditorComponentImpl. The normal
                                // Driver close() locates only a visible editor; restore
                                // native first so cleanup cannot mask the authoritative
                                // diagnostic error with a 15s UI locator timeout.
                                val cleanup = runCatching {
                                    driver.withContext(OnDispatcher.EDT) {
                                        preview.restoreNative(bEditor)
                                    }
                                    waitFor(
                                        message = "B native editor restored for safe close",
                                        timeout = 20.seconds,
                                        getter = { driver.withContext(OnDispatcher.EDT) {
                                            preview.nativeShowing(bEditor)
                                        } },
                                        checker = { it }
                                    )
                                    markFlow.close(b)
                                }
                                val cleanupError = cleanup.exceptionOrNull()
                                if (cleanupError != null) {
                                    val originalError = bFailure
                                    if (originalError == null) throw cleanupError
                                    originalError.addSuppressed(cleanupError)
                                }
                            }
                        }
                        // Do not report a successful capture if its mandatory
                        // same-source A/B evidence failed earlier in this case.
                        heartbeat("case-evidence-complete", entry.id)
                        states[entry.id] = "CAPTURED_UNVERIFIED"
                        heartbeat("case-complete", entry.id)
                    } catch (cause: Throwable) {
                        caseFailure = cause
                        heartbeat("case-failed", entry.id)
                        Files.writeString(dest.resolve("failure.txt"),
                            cause.javaClass.name + ": " + cause.message + "\n")
                        // A missing H1 replacement (whether CustomFold or the
                        // explicit opt-in owned inline fallback) remains a hard
                        // structural FAIL. Keep independent Mermaid captures,
                        // but do not suppress unexpected exceptions.
                        if (entry.id == "md-atx-headings-h1-h6" &&
                            cause is IllegalStateException &&
                            cause.message?.startsWith(
                                "E9 HEADING_REPLACEMENT_OWNERSHIP_INCOMPLETE:"
                            ) == true) {
                            toleratedStructuralFailure = true
                        } else {
                            deferredDriverFailure = cause
                        }
                    } finally {
                        // FULL_CORPUS_CASE_EDITOR_LIFECYCLE_CLOSE:
                        // Every manifest case owns a TextEditorWithPreview. Leaving
                        // prior cases open accumulates bundled JCEF clients until the
                        // platform remote CEF router fails. Restore the native editor,
                        // prove source identity is still intact, then close the whole
                        // editor before the next case creates another Preview.
                        val opened = caseEditor
                        val remote = caseRemoteEditor
                        if (opened != null && remote != null &&
                            (caseFailure == null || toleratedStructuralFailure)) {
                            heartbeat("case-cleanup-start", entry.id)
                            val cleanup = runCatching {
                                val expectedStamp = caseStamp
                                check(driver.withContext(OnDispatcher.EDT) {
                                    preview.sourceText(remote) ==
                                        String(entry.bytes, StandardCharsets.UTF_8) &&
                                        (expectedStamp == null ||
                                            preview.sourceStamp(remote) == expectedStamp) &&
                                        !preview.sourceUnsaved(remote)
                                }) {
                                    "Case lifecycle close observed source identity drift: " +
                                        entry.id
                                }
                                // Hide the exact retained Preview first. IntelliJ's
                                // MarkdownPreviewFileEditor disposes its JCEF-backed panel from the
                                // componentHidden lifecycle callback; prove that detach completed
                                // before closing the owning TextEditorWithPreview.
                                Files.writeString(
                                    dest.resolve("reference-lifecycle.txt"),
                                    "phase=before-hide\n" +
                                        driver.withContext(OnDispatcher.EDT) {
                                            preview.previewLifecycleEvidence(remote)
                                        },
                                    StandardCharsets.UTF_8
                                )
                                val cleanupCloseMarker = dest.resolve(
                                    "reference-native-close-cleanup.marker"
                                ).toAbsolutePath().normalize()
                                driver.withContext(OnDispatcher.EDT) {
                                    preview.armDetachedReferenceBrowserCloseMarker(
                                        cleanupCloseMarker.toString()
                                    )
                                    preview.restoreNative(remote)
                                }
                                waitFor(
                                    message = "Bundled Preview panel detached before close: " +
                                        entry.id,
                                    timeout = 20.seconds,
                                    getter = { driver.withContext(OnDispatcher.EDT) {
                                        preview.nativeShowing(remote) &&
                                            !preview.referencePanelAttached(remote)
                                    } },
                                    checker = { it }
                                )
                                Files.writeString(
                                    dest.resolve("reference-lifecycle.txt"),
                                    "phase=after-hide\n" +
                                        driver.withContext(OnDispatcher.EDT) {
                                            preview.previewLifecycleEvidence(remote)
                                        } +
                                        "phase=native-close-wait-start\n" +
                                        driver.withContext(OnDispatcher.EDT) {
                                            preview.detachedReferenceBrowserEvidence()
                                        },
                                    StandardCharsets.UTF_8,
                                    java.nio.file.StandardOpenOption.APPEND
                                )
                                waitFor(
                                    message = "Bundled Preview native browser closed before next case: " +
                                        entry.id,
                                    timeout = 20.seconds,
                                    getter = { Files.exists(cleanupCloseMarker) },
                                    checker = { it }
                                )
                                heartbeat("case-reference-native-close-complete", entry.id)
                                Files.writeString(
                                    dest.resolve("reference-lifecycle.txt"),
                                    "phase=native-close-complete\n" +
                                        driver.withContext(OnDispatcher.EDT) {
                                            preview.detachedReferenceBrowserEvidence()
                                        },
                                    StandardCharsets.UTF_8,
                                    java.nio.file.StandardOpenOption.APPEND
                                )
                                markFlow.closeMarkdown(remote, entry.id + ".md")
                                waitFor(
                                    message = "MarkFlow derived JCEF runtime returns to baseline: " +
                                        entry.id,
                                    timeout = 20.seconds,
                                    getter = {
                                        driver.withContext(OnDispatcher.EDT) {
                                            rendererLifecycle.liveRuntimeCount()
                                        }
                                    },
                                    checker = { live -> live == rendererBaseline },
                                )
                                heartbeat("case-cleanup-complete", entry.id)
                            }
                            val cleanupError = cleanup.exceptionOrNull()
                            if (cleanupError != null) {
                                states[entry.id] = "CAPTURE_FAILED"
                                heartbeat("case-failed", entry.id)
                                val original = caseFailure
                                if (original == null || toleratedStructuralFailure) {
                                    deferredDriverFailure = cleanupError
                                } else {
                                    original.addSuppressed(cleanupError)
                                }
                            }
                        }
                        if (opened != null && remote != null &&
                            caseFailure != null && !toleratedStructuralFailure) {
                            heartbeat("case-cleanup-deferred-to-ide-close", entry.id)
                            Files.writeString(
                                dest.resolve("reference-lifecycle.txt"),
                                "phase=deferred-to-ide-close-after-fatal-case\n" +
                                    "reason=preserve-primary-failure-and-exit-driver-cleanly\n",
                                StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND,
                            )
                        }
                    }
                    if (deferredDriverFailure != null) {
                        heartbeat("case-fatal-deferred-to-ide-close", entry.id)
                        return@useDriverAndCloseIde
                    }
                    if (toleratedStructuralFailure) continue
                }
                heartbeat("case-loop-complete")
            }
            deferredDriverFailure?.let { throw it }
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            val records = inventory.joinToString(",\n") {
                "    {\"case_id\":\"" + it.id + "\",\"manifest_case_id\":\"" +
                    it.parent + "\",\"classification\":\"" + it.classification +
                    "\",\"capture_status\":\"" + states.getValue(it.id) + "\"}"
            }
            val captured = states.values.count { it == "CAPTURED_UNVERIFIED" }
            val failed = states.values.count { it == "CAPTURE_FAILED" }
            // Every retained structural finding is now a MarkFlow-owned failure.
            // Preview-only geometry differences stay diagnostic and never enter
            // this failure ledger.
            val captureFailures = structuralFailures.toList()
            Files.writeString(
                output.resolve("candidate-structural-failures.tsv"),
                "case_id\tcandidate_gate\tobserved\tlimit\n" +
                    structuralFailures.joinToString("\n", postfix = "\n")
            )
            Files.writeString(output.resolve("summary.json"),
                "{\n\"schema\":\"markflow-stage-d-capture/v1\",\n" +
                    "\"source_head\":\"" + sourceHead + "\",\n" +
                    "\"manifest_sha256\":\"" + sha(manifest) + "\",\n" +
                    "\"manifest_cases\":88,\n\"expanded_cases\":" + inventory.size + ",\n" +
                    "\"selected_cases\":" + selected.size + ",\n" +
                    "\"review_only_cases\":" + reviewCases.size + ",\n" +
                    "\"captured_unverified\":" + captured + ",\n" +
                    "\"capture_failed\":" + failed + ",\n" +
                    "\"not_executed\":" +
                        inventory.count { states[it.id] == "NOT_EXECUTED" } + ",\n" +
                    "\"geometry_gate\":\"" +
                        (if (captureFailures.isEmpty()) "PENDING_REVIEW_AND_FULL_CORPUS"
                         else "FAIL_KNOWN_DEFECT_CANDIDATE_BOUNDS") + "\",\n" +
                    "\"capture_contract\":\"" + contract + "\",\n" +
                    "\"capture_failure_count\":" + captureFailures.size + ",\n" +
                    "\"acceptance_review_required_count\":" +
                        (structuralFailures.size - captureFailures.size) + ",\n" +
                    "\"release_eligible\":false,\n" +
                    "\"candidate_structural_failure_count\":" + structuralFailures.size + ",\n" +
                    "\"full_differential_acceptance\":false,\n" +
                    "\"cases\":[\n" + records + "\n]}\n")
            sandbox.toFile().deleteRecursively()
        }
        if (failure != null) throw failure
        val incomplete = selected.filter { states[it.id] != "CAPTURED_UNVERIFIED" }
        check(incomplete.isEmpty()) {
            "Stage E9 selected-case capture remains FAIL: " +
                incomplete.joinToString { it.id + "=" + states.getValue(it.id) } +
                "; retained structural failures: " + structuralFailures.joinToString("; ")
        }
        // Capture CI remains strict about missing/invalid captures and
        // unexpected real structural defects. It must NOT claim that reviewed
        // box-model parity, or all 89 manifest cases, passed.
        val captureFailures = structuralFailures.toList()
        check(captureFailures.isEmpty()) {
            "Stage D actual structural capture failures: " +
                captureFailures.joinToString("; ")
        }
        if (contract == "acceptance") {
            check(structuralFailures.isEmpty() &&
                selected.size == inventory.size &&
                inventory.all { states[it.id] == "CAPTURED_UNVERIFIED" }) {
                "Full differential acceptance BLOCKED: " +
                    (inventory.size - states.values.count { it == "CAPTURED_UNVERIFIED" }) +
                    " source cases not captured, " + structuralFailures.size +
                    " unresolved structural/review findings. No threshold bypass permitted."
            }
            // Complete execution is only the evidence-generation milestone.
            // Bind the final verdict to an explicit repository-owned review ledger.
            // The ledger may reference an earlier evidence run, but every source,
            // manifest identity and Mermaid block must still match this exact checkout.
            val ledgerPath =
                root.resolve("fixtures/differential-acceptance/acceptance-ledger.tsv")
            val ledgerBytes = Files.readAllBytes(ledgerPath)
            val decisions = parseReviewedAcceptanceLedger(
                String(ledgerBytes, StandardCharsets.UTF_8), inventory
            )
            val rejected = decisions.values.filter { it.reviewResult != "ACCEPT" }
            check(rejected.isEmpty()) {
                "Full #353 reviewed acceptance ledger contains rejected cases: " +
                    rejected.joinToString { it.caseId + "=" + it.reviewResult }
            }
            val reviewedEvidenceHead =
                decisions.values.map { it.evidenceSourceHead }.toSet().single()
            val reviewedEvidenceRunId =
                decisions.values.map { it.evidenceRunId }.toSet().single()
            Files.writeString(
                output.resolve("acceptance.json"),
                "{\n" +
                    "\"schema\":\"markflow-full-differential-acceptance/v1\",\n" +
                    "\"source_head\":\"" + sourceHead + "\",\n" +
                    "\"manifest_sha256\":\"" + sha(manifest) + "\",\n" +
                    "\"acceptance_ledger_sha256\":\"" + sha(ledgerBytes) + "\",\n" +
                    "\"expanded_cases\":" + inventory.size + ",\n" +
                    "\"reviewed_cases\":" + decisions.size + ",\n" +
                    "\"reviewed_evidence_source_head\":\"" +
                        reviewedEvidenceHead + "\",\n" +
                    "\"reviewed_evidence_run_id\":" + reviewedEvidenceRunId + ",\n" +
                    "\"full_differential_acceptance\":true,\n" +
                    "\"release_eligible\":false,\n" +
                    "\"remaining_release_chain\":\"#348 -> #309 -> #156 -> #84 -> #52\"\n" +
                    "}\n",
                StandardCharsets.UTF_8
            )
        }
    }

    private fun writeDeterministicLocalImage(path: Path) {
        Files.createDirectories(requireNotNull(path.parent))
        val image = BufferedImage(240, 96, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color(248, 249, 250)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = java.awt.Color(32, 95, 180)
            graphics.fillRect(16, 16, 64, 64)
            graphics.color = java.awt.Color(37, 150, 120)
            graphics.fillRect(88, 16, 64, 64)
            graphics.color = java.awt.Color(203, 83, 73)
            graphics.fillRect(160, 16, 64, 64)
        } finally {
            graphics.dispose()
        }
        check(ImageIO.write(image, "png", path.toFile())) { "PNG writer unavailable" }
    }

    private fun parseReviewedAcceptanceLedger(
        raw: String,
        inventory: List<Case>,
    ): Map<String, AcceptanceDecision> {
        check(raw.endsWith("\n") && !raw.contains('\r')) {
            "Reviewed acceptance ledger must use complete LF lines"
        }
        val rows = raw.trimEnd('\n').split('\n')
        check(rows.firstOrNull() == reviewedAcceptanceLedgerHeader) {
            "Reviewed acceptance ledger schema changed"
        }
        val indexed = inventory.associateBy { it.id }
        check(indexed.size == inventory.size) { "Duplicate expanded acceptance case identity" }
        val decisions = linkedMapOf<String, AcceptanceDecision>()
        val expandedId = Regex("[a-z0-9]+(?:-[a-z0-9]+)*(?:--block-[0-9]{2})?")
        val manifestId = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val sha256 = Regex("[0-9a-f]{64}")
        val gitSha = Regex("[0-9a-f]{40}")
        val runId = Regex("[1-9][0-9]*")
        for (row in rows.drop(1)) {
            check(row.isNotBlank()) { "Reviewed acceptance ledger contains a blank row" }
            val fields = row.split('\t', limit = 10)
            check(fields.size == 10) { "Malformed reviewed acceptance ledger row" }
            val decision = AcceptanceDecision(
                caseId = fields[0],
                manifestId = fields[1],
                sourceSha256 = fields[2],
                blockSha256 = fields[3],
                classification = fields[4],
                visualExpectation = fields[5],
                reviewResult = fields[6],
                evidenceSourceHead = fields[7],
                evidenceRunId = fields[8],
                reviewNote = fields[9],
            )
            check(expandedId.matches(decision.caseId)) {
                "Unsafe reviewed acceptance case ID: " + decision.caseId
            }
            check(manifestId.matches(decision.manifestId)) {
                "Unsafe reviewed acceptance manifest ID: " + decision.manifestId
            }
            check(sha256.matches(decision.sourceSha256)) {
                "Malformed reviewed source SHA-256: " + decision.caseId
            }
            check(decision.blockSha256 == "none" || sha256.matches(decision.blockSha256)) {
                "Malformed reviewed Mermaid block SHA-256: " + decision.caseId
            }
            check(decision.reviewResult == "ACCEPT" || decision.reviewResult == "REJECT") {
                "Unknown reviewed acceptance result: " + decision.caseId
            }
            check(gitSha.matches(decision.evidenceSourceHead)) {
                "Malformed reviewed evidence source HEAD: " + decision.caseId
            }
            check(runId.matches(decision.evidenceRunId)) {
                "Malformed reviewed evidence run ID: " + decision.caseId
            }
            check(decision.reviewNote.isNotBlank() && !decision.reviewNote.contains('\t')) {
                "Reviewed acceptance note is required and must be one TSV field: " + decision.caseId
            }
            check(decisions.put(decision.caseId, decision) == null) {
                "Duplicate reviewed acceptance case: " + decision.caseId
            }
            val entry = requireNotNull(indexed[decision.caseId]) {
                "Unknown reviewed acceptance case: " + decision.caseId
            }
            check(decision.manifestId == entry.parent) {
                "Reviewed acceptance manifest identity drifted: " + decision.caseId
            }
            check(decision.sourceSha256 == sha(entry.bytes)) {
                "Reviewed acceptance source bytes drifted: " + decision.caseId
            }
            check(decision.blockSha256 == (entry.blockHash ?: "none")) {
                "Reviewed acceptance Mermaid block drifted: " + decision.caseId
            }
            check(decision.classification == entry.classification) {
                "Reviewed acceptance classification drifted: " + decision.caseId
            }
            check(decision.visualExpectation == entry.visualExpectation) {
                "Reviewed acceptance visual contract drifted: " + decision.caseId
            }
        }
        check(decisions.keys == indexed.keys) {
            val missing = indexed.keys - decisions.keys
            val extra = decisions.keys - indexed.keys
            "Reviewed acceptance ledger coverage mismatch: reviewed=" + decisions.size +
                " expected=" + indexed.size + " missing=" + missing.joinToString(",") +
                " extra=" + extra.joinToString(",")
        }
        check(decisions.values.map { it.evidenceSourceHead }.toSet().size == 1) {
            "Reviewed acceptance ledger must bind one evidence source HEAD"
        }
        check(decisions.values.map { it.evidenceRunId }.toSet().size == 1) {
            "Reviewed acceptance ledger must bind one evidence workflow run"
        }
        return decisions
    }

    private fun acceptanceLedgerNegativeControls(): Boolean {
        val markdownBytes = "# reviewed\n".toByteArray(StandardCharsets.UTF_8)
        val mermaidBytes =
            "```mermaid\ngraph TD\nA-->B\n```\n".toByteArray(StandardCharsets.UTF_8)
        val inventory = listOf(
            Case(
                "md-reviewed", "md-reviewed", "markdown", "supported",
                "fixtures/md-reviewed.md", markdownBytes, 0, null,
                "source-anchored-geometry", true
            ),
            Case(
                "mermaid-reviewed", "mermaid-reviewed", "mermaid", "supported",
                "fixtures/mermaid-reviewed.md", mermaidBytes, 0,
                sha("graph TD\nA-->B".toByteArray(StandardCharsets.UTF_8)),
                "diagram-semantic-perceptual", true
            ),
        )
        val evidenceHead = "1".repeat(40)
        fun row(
            entry: Case,
            result: String = "ACCEPT",
            sourceHash: String = sha(entry.bytes),
            parent: String = entry.parent,
            run: String = "38599999999",
            head: String = evidenceHead,
        ): String =
            listOf(
                entry.id,
                parent,
                sourceHash,
                entry.blockHash ?: "none",
                entry.classification,
                entry.visualExpectation,
                result,
                head,
                run,
                "reviewed-source-bound-evidence",
            ).joinToString("\t")

        val valid = reviewedAcceptanceLedgerHeader + "\n" +
            inventory.joinToString("\n") { row(it) } + "\n"
        val missing = reviewedAcceptanceLedgerHeader + "\n" + row(inventory.first()) + "\n"
        val duplicate = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first()) + "\n" + row(inventory.first()) + "\n"
        val staleSource = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first(), sourceHash = "0".repeat(64)) + "\n" +
            row(inventory.last()) + "\n"
        val wrongParent = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first(), parent = "md-other") + "\n" +
            row(inventory.last()) + "\n"
        val malformedResult = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first(), result = "PASS") + "\n" +
            row(inventory.last()) + "\n"
        val malformedRun = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first(), run = "0") + "\n" +
            row(inventory.last()) + "\n"
        val mixedRun = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first(), run = "38599999999") + "\n" +
            row(inventory.last(), run = "38600000000") + "\n"
        val mixedHead = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first()) + "\n" +
            row(inventory.last(), head = "2".repeat(40)) + "\n"
        val explicitReject = reviewedAcceptanceLedgerHeader + "\n" +
            row(inventory.first(), result = "REJECT") + "\n" +
            row(inventory.last()) + "\n"

        return runCatching { parseReviewedAcceptanceLedger(valid, inventory) }.isSuccess &&
            runCatching { parseReviewedAcceptanceLedger(missing, inventory) }.isFailure &&
            runCatching { parseReviewedAcceptanceLedger(duplicate, inventory) }.isFailure &&
            runCatching { parseReviewedAcceptanceLedger(staleSource, inventory) }.isFailure &&
            runCatching { parseReviewedAcceptanceLedger(wrongParent, inventory) }.isFailure &&
            runCatching { parseReviewedAcceptanceLedger(malformedResult, inventory) }.isFailure &&
            runCatching { parseReviewedAcceptanceLedger(malformedRun, inventory) }.isFailure &&
            runCatching { parseReviewedAcceptanceLedger(mixedRun, inventory) }.isFailure &&
            runCatching { parseReviewedAcceptanceLedger(mixedHead, inventory) }.isFailure &&
            runCatching {
                val decisions = parseReviewedAcceptanceLedger(explicitReject, inventory)
                check(decisions.values.any { it.reviewResult == "REJECT" })
            }.isSuccess
    }

    private data class MarkdownAnchor(val line: Int, val text: String)

    private fun markdownAnchors(root: Path): Map<String, MarkdownAnchor> {
        val path = root.resolve("fixtures/differential-acceptance/markdown-anchors.tsv")
        val raw = Files.readString(path, StandardCharsets.UTF_8)
        check(raw.endsWith("\n") && !raw.contains('\r')) {
            "Markdown anchor ledger must use complete LF lines"
        }
        val rows = raw.trimEnd('\n').split('\n')
        check(rows.first() == "case_id\tsource_line_zero_based\tanchor_text") {
            "Markdown anchor ledger schema changed"
        }
        val result = linkedMapOf<String, MarkdownAnchor>()
        for (row in rows.drop(1)) {
            val fields = row.split('\t', limit = 3)
            check(fields.size == 3 && fields[2].isNotEmpty()) {
                "Malformed Markdown anchor ledger row"
            }
            val caseId = fields[0]
            check(caseId.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) {
                "Unsafe Markdown anchor case ID"
            }
            val line = fields[1].toIntOrNull()
            check(line != null && line >= 0) { "Invalid Markdown anchor line: " + caseId }
            check(result.put(caseId, MarkdownAnchor(line, fields[2])) == null) {
                "Duplicate Markdown anchor: " + caseId
            }
        }
        check(result.size == 49) { "Reviewed Markdown anchor coverage changed" }
        return result
    }

    private fun inventory(root: Path, manifest: String): List<Case> {
        check(manifest.endsWith("\n") && !manifest.contains('\r'))
        val rows = manifest.trimEnd('\n').split('\n')
        check(rows.first().split('\t').size == 12)
        val anchors = markdownAnchors(root)
        val markdownIds = mutableSetOf<String>()
        val items = mutableListOf<Case>()
        val ids = mutableSetOf<String>()
        for (row in rows.drop(1)) {
            val parts = row.split('\t')
            check(parts.size == 12 && ids.add(parts[0]))
            val id = parts[0]
            check(id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
            val domain = parts[1]
            check(domain == "markdown" || domain == "mermaid")
            val classification = parts[3]
            check(classification in setOf("supported", "degraded", "unsupported"))
            val visualExpectation = parts[7]
            check(visualExpectation in setOf(
                "source-anchored-geometry", "diagram-semantic-perceptual",
                "diagnostic-only", "no-parity-expected"
            ))
            check(parts[4] == "covered" && parts[6] == "exact-source")
            val path = parts[5]
            check(path.matches(Regex("[A-Za-z0-9._/-]+")) &&
                !path.startsWith("/") && path.split('/').none { it == "." || it == ".." })
            val file = root.resolve(path).toRealPath()
            check(file.startsWith(root.toRealPath()) && Files.isRegularFile(file))
            val bytes = Files.readAllBytes(file)
            check(bytes.isNotEmpty())
            val source = String(bytes, StandardCharsets.UTF_8)
            check(source.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes))
            check(parts[8] == "yes" || parts[8] == "no")
            val comparable = parts[8] == "yes"
            if (domain == "markdown") {
                check(markdownIds.add(id))
                val anchor = requireNotNull(anchors[id]) {
                    "Missing reviewed Markdown source anchor: " + id
                }
                val sourceLines = source.split('\n')
                check(sourceLines.getOrNull(anchor.line) == anchor.text) {
                    "Reviewed Markdown source anchor drifted: " + id +
                        " line=" + anchor.line
                }
                items += Case(
                    id, id, domain, classification, path, bytes, anchor.line, null,
                    visualExpectation, comparable
                )
            } else {
                val lines = source.split('\n')
                val blocks = mutableListOf<Pair<Int, String>>()
                var pos = 0
                val opener = Regex("^ {0,3}(\u0060{3,}|~{3,})mermaid[ \\t]*$")
                while (pos < lines.size) {
                    val opening = opener.matchEntire(lines[pos])
                    if (opening == null) { pos++; continue }
                    val fence = opening.groupValues[1]
                    var end = pos + 1
                    while (end < lines.size) {
                        val closing = lines[end].trim()
                        if (closing.length >= fence.length &&
                            closing.all { it == fence[0] }) break
                        end++
                    }
                    check(end < lines.size && end > pos + 1) { "Unclosed Mermaid block: " + path }
                    val body = lines.subList(pos + 1, end).joinToString("\n")
                    check(body.isNotBlank())
                    blocks += pos to sha(body.toByteArray(StandardCharsets.UTF_8))
                    pos = end + 1
                }
                check(blocks.isNotEmpty()) { "Mermaid blocks omitted: " + path }
                blocks.forEachIndexed { index, block ->
                    val caseId = if (blocks.size == 1) id else
                        id + "--block-" + (index + 1).toString().padStart(2, '0')
                    items += Case(caseId, id, domain, classification, path,
                        bytes, block.first, block.second, visualExpectation, comparable)
                }
            }
        }
        check(anchors.keys == markdownIds) {
            "Markdown anchor ledger must cover exactly the manifest Markdown cases"
        }
        return items
    }

    /**
     * From the actual bundled Markdown preview DOM. Each heading must match the
     * review-owned H1-H6 source fixture exactly; a blank or unrelated JCEF page
     * is not an oracle. Relative bounding coordinates are invariant under a
     * shared browser scroll displacement (unlike viewport screenshot offsets).
     */
    /** Source-backed visual oracle: real Mermaid SVG, not generated fallback HTML. */
    private fun mermaidReferenceSvgProbe(sourceLabel: String): String {
        require(sourceLabel == "Rendered correctly?" ||
            sourceLabel == "MarkFlow Example Schedule")
        val label = sourceLabel // fixed in-repository fixture labels; no arbitrary JS input
        return """
            (() => {
              const expected = '${label}';
              const candidates = Array.from(document.querySelectorAll('svg')).filter(svg => {
                const text = (svg.textContent || '').replace(/\s+/g, ' ').trim();
                const rect = svg.getBoundingClientRect();
                return text.includes(expected) && rect.width > 40 && rect.height > 40;
              });
              if (candidates.length !== 1) {
                return 'FAIL: source-identifiable Mermaid SVG count=' + candidates.length +
                    ';expected=' + expected + ';allSvg=' + document.querySelectorAll('svg').length;
              }
              const svg = candidates[0];
              const bounds = svg.getBoundingClientRect();
              const viewport = { width: window.innerWidth, height: window.innerHeight };
              if (!Number.isFinite(bounds.width) || !Number.isFinite(bounds.height)) {
                return 'FAIL: Mermaid SVG bounds nonfinite';
              }
              const fmt = n => Number(n).toFixed(3);
              return [
                'schema=markflow-reference-mermaid-svg-dom-geometry/v1',
                'source_label=' + expected,
                'svg_css_width=' + fmt(bounds.width),
                'svg_css_height=' + fmt(bounds.height),
                'svg_css_left=' + fmt(bounds.left),
                'svg_css_top=' + fmt(bounds.top),
                'preview_window_inner_width_css=' + viewport.width,
                'preview_window_inner_height_css=' + viewport.height,
                'browser_device_pixel_ratio=' + fmt(window.devicePixelRatio),
                'svg_viewbox=' + (svg.getAttribute('viewBox') || 'UNAVAILABLE')
              ].join('\n');
            })()
        """.trimIndent()
    }


    /**
     * Stage E16: actual screenshot-ink left edge for each source-identified
     * heading, not Editor Insets or an inferred drawString paint origin.
     *
     * The screenshot origins are the individual content-area captures, so
     * positions are reported within their respective crop coordinate spaces.
     * A small 400px left strip and the *real* fold/DOM text vertical boxes
     * limit unrelated UI pixels. Pinned Islands Light only; a threshold of
     * 205 is a raster feature detector, NEVER a visual parity tolerance.
     *
     * Fail closed on a missing source row or an unidentifiable ink box. No
     * pixel match or geometry threshold is accepted as release parity here.
     */
    private fun firstHeadingInkPixelX(
        image: BufferedImage,
        top: Int,
        bottomExclusive: Int,
    ): Int? {
        require(top >= 0 && bottomExclusive > top && bottomExclusive <= image.height) {
            "Heading raster vertical bounds outside actual screenshot"
        }
        val xLimit = minOf(image.width, 400)
        require(xLimit > 0) { "Empty heading raster scan" }
        for (x in 0 until xLimit) {
            for (y in top until bottomExclusive) {
                val color = image.getRGB(x, y)
                val red = (color ushr 16) and 255
                val green = (color ushr 8) and 255
                val blue = color and 255
                if (red < 205 && green < 205 && blue < 205) return x
            }
        }
        return null
    }

    private fun headingInkRasterNegativeControls(): Boolean {
        val image = BufferedImage(80, 24, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = java.awt.Color(235, 235, 235)
            graphics.fillRect(1, 2, 2, 6)
            graphics.color = java.awt.Color(115, 118, 124)
            graphics.fillRect(7, 2, 3, 6)
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(15, 14, 4, 4)
        } finally {
            graphics.dispose()
        }
        return firstHeadingInkPixelX(image, 0, 10) == 7 &&
            firstHeadingInkPixelX(image, 13, 20) == 15 &&
            firstHeadingInkPixelX(image, 19, 23) == null &&
            runCatching { firstHeadingInkPixelX(image, -1, 10) }.isFailure &&
            runCatching { firstHeadingInkPixelX(image, 10, 25) }.isFailure
    }

    /**
     * Actual screenshot vertical ink pixels. Restrict the scan to columns
     * eight pixels right of the previously detected heading leftmost ink so
     * thin gutter/caret artifacts cannot masquerade as the heading glyph.
     * RGB<205 is a pinned light-theme feature detector, NOT a parity tolerance.
     */
    private fun headingPaintedInkY(
        image: BufferedImage, leftInkX: Int, top: Int, bottomExclusive: Int
    ): IntRange? {
        require(top >= 0 && bottomExclusive > top && bottomExclusive <= image.height)
        val fromX = leftInkX + 8
        val toX = minOf(image.width, leftInkX + 300)
        require(leftInkX >= 0 && fromX < toX)
        var first = -1
        var last = -1
        for (y in top until bottomExclusive) {
            for (x in fromX until toX) {
                val color = image.getRGB(x, y)
                if (((color ushr 16) and 255) < 205 &&
                    ((color ushr 8) and 255) < 205 &&
                    (color and 255) < 205) {
                    if (first < 0) first = y
                    last = y
                    break
                }
            }
        }
        return if (first < 0) null else first..last
    }

    private fun headingInkVerticalNegativeControls(): Boolean {
        val image = BufferedImage(80, 30, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, 80, 30)
            g.color = java.awt.Color.BLACK
            g.fillRect(1, 2, 2, 24) // gutter-only ink
            g.fillRect(15, 8, 10, 4) // actual heading text
        } finally {
            g.dispose()
        }
        return headingPaintedInkY(image, 1, 0, 25) == (8..11) &&
            headingPaintedInkY(image, 1, 14, 26) == null &&
            runCatching { headingPaintedInkY(image, 1, -1, 20) }.isFailure &&
            runCatching { headingPaintedInkY(image, 1, 2, 31) }.isFailure &&
            runCatching { headingPaintedInkY(image, 78, 0, 20) }.isFailure
    }

    /**
     * Source-aligned H1-H6 paired B-pilot ink-Y and text baseline evidence.
     * Native baseline is a renderer paint input; reference baseline is an
     * actual JCEF DOM marker. Both are normalized to their own H1. This
     * RESEARCH-only pilot does not establish production #350 visual parity.
     */
    private fun headingInkYBaselineDiagnostic(
        native: BufferedImage, reference: BufferedImage,
        nativeLedger: String, previewLedger: String, nativeScrollY: Int
    ): String {
        check(nativeLedger.startsWith("schema=markflow-native-heading-customfold-geometry/v2\n"))
        check(previewLedger.startsWith("schema=markflow-reference-heading-dom-geometry/v1\n"))
        require(nativeScrollY >= 0)
        fun rows(text: String): List<Map<String, String>> =
            text.lineSequence().filter { it.startsWith("level=") }.map { row ->
                row.split('\t').associate { token ->
                    val pair = token.split('=', limit = 2)
                    require(pair.size == 2) { "E19 malformed heading geometry" }
                    pair[0] to pair[1]
                }
            }.toList()
        val nativeRows = rows(nativeLedger)
        val referenceRows = rows(previewLedger)
        check(nativeRows.size == 6 && referenceRows.size == 6)
        val result = mutableListOf(
            "schema=markflow-heading-painted-ink-y-baseline/v1",
            "gate=DIAGNOSTIC_ONLY_NO_REVIEWED_THRESHOLD",
            "native_scope=RESEARCH_ONLY_B_HEADING_PAINT_NOT_PRODUCTION_DEFAULT",
            "comparison=RELATIVE_TO_H1_WITHIN_INDEPENDENT_SCREENSHOT_CROPS",
            "native_scroll_offset_px=" + nativeScrollY,
            "level\tsource_line\tnative_ink_top_px\tpreview_ink_top_px" +
                "\tnative_ink_height_px\tpreview_ink_height_px" +
                "\tnative_paint_baseline_px\tpreview_dom_baseline_px" +
                "\tnative_relative_ink_top_px\tpreview_relative_ink_top_px" +
                "\trelative_ink_drift_px\tnative_relative_baseline_px" +
                "\tpreview_relative_baseline_px\trelative_baseline_drift_px" +
                "\tbaseline_beyond_native_allocated_bottom_px"
        )
        var firstNativeInk = 0
        var firstPreviewInk = 0
        var firstNativeBaseline = 0
        var firstPreviewBaseline = 0.0
        var priorNativeInk = -1
        var priorPreviewInk = -1
        nativeRows.zip(referenceRows).forEachIndexed { index, (n, p) ->
            val level = index + 1
            val sourceLine = index * 4
            check(n.getValue("level") == level.toString() &&
                p.getValue("level") == level.toString() &&
                n.getValue("source_line_zero_based") == sourceLine.toString() &&
                p.getValue("source_line_zero_based") == sourceLine.toString()) {
                "E19 source identity mismatch"
            }
            val documentTop = if (level == 1) {
                check(n.getValue("inline_pilot_status") == "INSTALLED_UNREVIEWED")
                n.getValue("inline_pilot_bounds_y_px").toInt()
            } else {
                check(n.getValue("fold_status") == "INSTALLED")
                n.getValue("fold_origin_y_document_px").toInt()
            }
            val height = if (level == 1) n.getValue("inline_pilot_height_px").toInt()
                else n.getValue("fold_height_px").toInt()
            val top = documentTop - nativeScrollY
            val nominalBottom = top + height
            val nativeBaseline = n.getValue("native_paint_baseline_document_px").toInt() -
                nativeScrollY
            val descent = n.getValue("native_paint_font_descent_px").toInt()
            // H1's live B font can paint BELOW its pre-style inline
            // allocation. The real #389 screenshot shows 22px allocated,
            // but glyph ink persists through y=31. Do not silently crop
            // diagnostic ink to that known-defective original allocation.
            val bottom = if (level == 1)
                maxOf(nominalBottom, nativeBaseline + descent + 1, top + 35)
            else nominalBottom
            val refTop = floor(p.getValue("heading_text_range_top_css_px").toDouble()).toInt()
            val refBottom = ceil(
                p.getValue("heading_text_range_top_css_px").toDouble() +
                    p.getValue("heading_text_range_height_css_px").toDouble()
            ).toInt()
            val nativeLeft = requireNotNull(firstHeadingInkPixelX(native, top, bottom)) {
                "E19 H" + level + " lacks native raster ink"
            }
            val previewLeft = requireNotNull(firstHeadingInkPixelX(
                reference, refTop, refBottom
            )) { "E19 H" + level + " lacks JCEF raster ink" }
            val nativeInk = requireNotNull(headingPaintedInkY(native, nativeLeft, top, bottom)) {
                "E19 native heading lacks text ink beyond gutter"
            }
            val previewInk = requireNotNull(headingPaintedInkY(
                reference, previewLeft, refTop, refBottom
            )) { "E19 preview heading lacks text ink beyond gutter" }
            val previewBaseline = p.getValue("reference_baseline_marker_css_px").toDouble()
            check(previewBaseline.isFinite() && nativeBaseline in top..bottom &&
                previewBaseline >= refTop && previewBaseline <= refBottom + 10) {
                "E19 declared paint baseline outside real text allocation"
            }
            check(index == 0 ||
                (nativeInk.first > priorNativeInk && previewInk.first > priorPreviewInk)) {
                "E19 painted glyph anchors collapsed"
            }
            if (index == 0) {
                firstNativeInk = nativeInk.first
                firstPreviewInk = previewInk.first
                firstNativeBaseline = nativeBaseline
                firstPreviewBaseline = previewBaseline
            }
            val nInk = nativeInk.first - firstNativeInk
            val pInk = previewInk.first - firstPreviewInk
            val nBase = nativeBaseline - firstNativeBaseline
            val pBase = previewBaseline - firstPreviewBaseline
            fun fmt(v: Double): String = "%.3f".format(java.util.Locale.ROOT, v)
            result += listOf(
                level, sourceLine, nativeInk.first, previewInk.first,
                nativeInk.last - nativeInk.first + 1,
                previewInk.last - previewInk.first + 1,
                nativeBaseline, fmt(previewBaseline),
                nInk, pInk, nInk - pInk, nBase, fmt(pBase), fmt(nBase - pBase),
                maxOf(0, nativeBaseline - nominalBottom)
            ).joinToString("\t")
            priorNativeInk = nativeInk.first
            priorPreviewInk = previewInk.first
        }
        return result.joinToString("\n", postfix = "\n")
    }

    private fun headingInkXDiagnostic(
        nativeImage: BufferedImage,
        referenceImage: BufferedImage,
        nativeLedger: String,
        referenceLedger: String,
    ): String {
        check(nativeLedger.startsWith("schema=markflow-native-heading-customfold-geometry/v2\n"))
        check(referenceLedger.startsWith("schema=markflow-reference-heading-dom-geometry/v1\n"))
        fun rows(value: String): List<Map<String, String>> =
            value.lineSequence().filter { it.startsWith("level=") }.map { line ->
                line.split('\t').associate { token ->
                    val keyAndValue = token.split('=', limit = 2)
                    require(keyAndValue.size == 2) { "Malformed heading identity/geometry row" }
                    keyAndValue[0] to keyAndValue[1]
                }
            }.toList()
        val nativeRows = rows(nativeLedger)
        val referenceRows = rows(referenceLedger)
        check(nativeRows.size == 6 && referenceRows.size == 6) {
            "Stage E16 needs all six real source-aligned headings"
        }
        val values = mutableListOf(
            "schema=markflow-heading-raster-ink-x/v1",
            "gate=DIAGNOSTIC_ONLY_NO_REVIEWED_THRESHOLD",
            "coordinate_space=NATIVE_AND_JCEF_INDEPENDENT_SCREENSHOT_CROPS",
            "ink_detector=LIGHT_THEME_RGB_CHANNELS_BELOW_205_FIRST_PIXEL_IN_HEADING_VERTICAL_BOX",
            "level\tsource_line\tnative_raster_ink_left_px\tpreview_raster_ink_left_px" +
                "\tpreview_minus_native_raster_px\tnative_declared_paint_origin_px" +
                "\tpreview_jcef_dom_range_left_css_px"
        )
        nativeRows.zip(referenceRows).forEachIndexed { index, (nativeRow, referenceRow) ->
            val level = index + 1
            val sourceLine = index * 4
            check(nativeRow.getValue("level") == level.toString() &&
                referenceRow.getValue("level") == level.toString() &&
                nativeRow.getValue("source_line_zero_based") == sourceLine.toString() &&
                referenceRow.getValue("source_line_zero_based") == sourceLine.toString()) {
                "Stage E16 source-identified raster heading mismatch"
            }
            val (top, bottom) = if (level == 1) {
                check(nativeRow.getValue("inline_pilot_status") == "INSTALLED_UNREVIEWED" &&
                    nativeRow.getValue("inline_pilot_source_fold") == "COLLAPSED_OWN_CANDIDATE")
                val origin = nativeRow.getValue("inline_pilot_bounds_y_px").toInt()
                origin to origin + maxOf(35, nativeRow.getValue("inline_pilot_height_px").toInt())
            } else {
                check(nativeRow.getValue("fold_status") == "INSTALLED")
                val origin = nativeRow.getValue("fold_origin_y_document_px").toInt()
                origin to origin + nativeRow.getValue("fold_height_px").toInt()
            }
            val textTop = referenceRow.getValue("heading_text_range_top_css_px").toDouble()
            val textHeight = referenceRow.getValue("heading_text_range_height_css_px").toDouble()
            check(textTop.isFinite() && textHeight.isFinite() && textHeight > 0)
            val nativeX = requireNotNull(firstHeadingInkPixelX(nativeImage, top, bottom)) {
                "No actual native screenshot ink at H" + level
            }
            val previewX = requireNotNull(firstHeadingInkPixelX(
                referenceImage, floor(textTop).toInt(), ceil(textTop + textHeight).toInt()
            )) { "No actual JCEF screenshot ink at H" + level }
            val paintInputX = nativeRow.getValue("native_paint_text_origin_x_document_px").toInt()
            val domTextX = referenceRow.getValue("heading_text_range_left_css_px").toDouble()
            check(paintInputX >= 0 && domTextX.isFinite() && domTextX >= 0)
            values += listOf(
                level, sourceLine, nativeX, previewX, previewX - nativeX, paintInputX,
                "%.3f".format(java.util.Locale.ROOT, domTextX)
            ).joinToString("\t")
        }
        return values.joinToString("\n", postfix = "\n")
    }

    /**
     * Review-only JCEF location probe; does not modify Markdown source or
     * platform CSS. The target text, not a guessed pixel scroll offset, owns
     * the Preview scroll anchor. Produces a verifiable DOM viewport ledger.
     */
    private fun generalDocumentPreviewScrollProbe(sourceLine: Int): String {
        val (tag, marker) = when (sourceLine) {
            0 -> "h1" to "Reliable Release Checklist"
            30 -> "pre" to "./gradlew buildPlugin"
            60 -> "h2" to "4. Publish and monitor"
            else -> error("Unsupported full-document review anchor: $sourceLine")
        }
        return """
            (() => {
              const matched = Array.from(document.querySelectorAll('$tag'))
                .filter(el => (el.textContent || '').includes('$marker'));
              if (matched.length !== 1) return 'FAIL: expected one $tag containing $marker, found ' + matched.length;
              const target = matched[0];
              target.scrollIntoView({block: 'start', inline: 'nearest', behavior: 'instant'});
              const box = target.getBoundingClientRect();
              const visible = box.height > 0 && box.bottom > 0 &&
                box.top < window.innerHeight;
              const origin = document.scrollingElement;
              return [
                'schema=markflow-real-jcef-review-scroll/v1',
                'source_line_zero_based=$sourceLine',
                'tag=' + target.tagName,
                'target_visible=' + visible,
                'target_top_css_px=' + box.top.toFixed(2),
                'viewport_height_css_px=' + window.innerHeight,
                'document_scroll_top=' + (origin ? origin.scrollTop : -1),
              ].join('\\n');
            })()
        """.trimIndent()
    }

    private val headingReferenceDomProbe = """
        (() => {
          const rows = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6'))
            .filter(h => /^Heading level [1-6]$/.test(h.textContent.trim()));
          if (rows.length !== 6) return 'FAIL: DOM heading count=' + rows.length;
          const firstY = rows[0].getBoundingClientRect().top;
          const lines = [
            'schema=markflow-reference-heading-dom-geometry/v1',
            'geometry_scope=PLATFORM_PREVIEW_JCEF_DOM',
            'heading_count=6',
            'device_pixel_ratio=' + window.devicePixelRatio,
          ];
          for (let index = 0; index < 6; index++) {
            const el = rows[index];
            const level = index + 1;
            if (el.tagName.toLowerCase() !== 'h' + level ||
                el.textContent.trim() !== 'Heading level ' + level)
              return 'FAIL: DOM/source heading mismatch at ' + level;
            const rect = el.getBoundingClientRect();
            const range = document.createRange();
            range.selectNodeContents(el);
            const textRect = range.getBoundingClientRect();
            const css = getComputedStyle(el);
            // Read the real JCEF inline baseline with a zero-sized marker.
            // It is removed immediately; any layout shift fails closed.
            const marker = document.createElement('span');
            marker.style.cssText =
              'display:inline-block;width:0;height:0;padding:0;margin:0;border:0;line-height:0;vertical-align:baseline';
            el.appendChild(marker);
            const baseline = marker.getBoundingClientRect().top;
            marker.remove();
            const restored = el.getBoundingClientRect();
            if (!Number.isFinite(baseline) ||
                Math.abs(restored.top - rect.top) > 0.05 ||
                Math.abs(restored.height - rect.height) > 0.05)
              return 'FAIL: JCEF baseline marker disturbed heading layout ' + level;
            if (!(rect.height > 0) || !(rect.width > 0) ||
                !(textRect.width > 0) || !(textRect.height > 0))
              return 'FAIL: DOM collapsed heading or text range ' + level;
            lines.push('level=' + level +
              '\tsource_line_zero_based=' + (index * 4) +
              '\treference_y_relative_css_px=' + (rect.top - firstY).toFixed(3) +
              '\treference_height_css_px=' + rect.height.toFixed(3) +
              '\tcomputed_margin_top_css_px=' + parseFloat(css.marginTop).toFixed(3) +
              '\tcomputed_margin_bottom_css_px=' + parseFloat(css.marginBottom).toFixed(3) +
              '\tcomputed_font_family=' + css.fontFamily.replace(/[\t\r\n]/g, ' ') +
              '\tcomputed_font_size_css_px=' + css.fontSize +
              '\tcomputed_font_weight=' + css.fontWeight +
              '\tcomputed_line_height_css_px=' + css.lineHeight +
              '\tcomputed_color=' + css.color +
              '\tcomputed_letter_spacing=' + css.letterSpacing +
              '\theading_box_left_css_px=' + rect.left.toFixed(3) +
              '\theading_text_range_left_css_px=' + textRect.left.toFixed(3) +
              '\theading_text_range_top_css_px=' + textRect.top.toFixed(3) +
              '\theading_text_range_height_css_px=' + textRect.height.toFixed(3) +
              '\treference_baseline_marker_css_px=' + baseline.toFixed(3));
          }
          return lines.join('\n');
        })()
    """.trimIndent()

    /**
     * E6: inspect real source-identical <p> text Range rectangles. The range
     * top is a browser inline-layout observation, NOT guaranteed font ink top.
     */
    private val followingParagraphDomProbe = """
        (() => {
          const paragraphs = Array.from(document.querySelectorAll('p'))
            .filter(p => /^Paragraph after H[1-6]\.$/.test(p.textContent.trim()));
          if (paragraphs.length !== 6)
            return 'FAIL: paragraph count ' + paragraphs.length;
          const lines = [
            'schema=markflow-reference-paragraph-text-range/v1',
            'geometry_scope=PLATFORM_PREVIEW_JCEF_TEXT_RANGE',
            'paragraph_count=6',
            'device_pixel_ratio=' + window.devicePixelRatio,
          ];
          let originY = null;
          let previousY = null;
          for (let i = 0; i < 6; i++) {
            const p = paragraphs[i];
            if (p.textContent.trim() !== 'Paragraph after H' + (i + 1) + '.')
              return 'FAIL: paragraph text/source mismatch ' + (i + 1);
            const range = document.createRange();
            range.selectNodeContents(p);
            const rect = range.getBoundingClientRect();
            const box = p.getBoundingClientRect();
            const css = getComputedStyle(p);
            if (!(rect.height > 0 && rect.width > 0 &&
                  box.height > 0 && box.width > 0))
              return 'FAIL: collapsed paragraph range ' + (i + 1);
            if (originY === null) originY = rect.top;
            if (previousY !== null && !(rect.top > previousY))
              return 'FAIL: unordered paragraph text ranges';
            previousY = rect.top;
            lines.push('paragraph_index=' + (i + 1) +
              '\tsource_line_zero_based=' + (4 * i + 2) +
              '\tpreview_text_range_top_relative_css_px=' + (rect.top - originY).toFixed(3) +
              '\tpreview_text_range_height_css_px=' + rect.height.toFixed(3) +
              '\tpreview_p_box_top_relative_css_px=' + (box.top - originY).toFixed(3) +
              '\tpreview_text_range_left_css_px=' + rect.left.toFixed(3) +
              '\tcomputed_font_family=' + css.fontFamily.replace(/[\t\r\n]/g, ' ') +
              '\tcomputed_font_size_css_px=' + css.fontSize +
              '\tcomputed_font_weight=' + css.fontWeight +
              '\tcomputed_line_height_css_px=' + css.lineHeight +
              '\tcomputed_color=' + css.color);
          }
          return lines.join('\n');
        })()
    """.trimIndent()

    /**
     * Same-source paragraph position deltas, NOT DOM-vs-native baseline parity.
     * Native logical line top and DOM text Range top can differ by font metrics.
     */
    private fun compareFollowingTextAnchors(native: String, preview: String): String {
        check(native.lineSequence().any { it == "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY" })
        check(preview.lineSequence().any { it == "geometry_scope=PLATFORM_PREVIEW_JCEF_TEXT_RANGE" })
        fun rows(ledger: String, prefix: String): List<Map<String, String>> =
            ledger.lineSequence().filter { it.startsWith(prefix) }.map { line ->
                line.split('\t').associate { field ->
                    val pair = field.split('=', limit = 2)
                    require(pair.size == 2) { "Malformed paragraph geometry" }
                    pair[0] to pair[1]
                }
            }.toList()
        val nativeRows = rows(native, "level=")
        val previewRows = rows(preview, "paragraph_index=")
        check(nativeRows.size == 6 && previewRows.size == 6) {
            "Stage E6 needs all six source-identical following text lines"
        }
        val nativeOrigin = nativeRows.first().getValue("following_y_document_px").toInt()
        var previousNative = -1
        var previousPreview = -1.0
        val lines = mutableListOf(
            "schema=markflow-following-text-line-drift/v1",
            "comparison_scope=DIAGNOSTIC_NATIVE_LOGICAL_LINE_VS_JCEF_TEXT_RANGE",
            "level\tsource_line_zero_based\tnative_text_line_relative_px\tpreview_text_range_relative_css_px\trelative_delta_px"
        )
        nativeRows.zip(previewRows).forEachIndexed { i, (n, p) ->
            val level = i + 1
            val sourceLine = 4 * i + 2
            check(n.getValue("level") == level.toString() &&
                p.getValue("paragraph_index") == level.toString() &&
                n.getValue("following_line_zero_based") == sourceLine.toString() &&
                p.getValue("source_line_zero_based") == sourceLine.toString()) {
                "Stage E6 unmatched paragraph source identity"
            }
            val nativeY = n.getValue("following_y_document_px").toInt() - nativeOrigin
            val previewY = p.getValue("preview_text_range_top_relative_css_px").toDouble()
            val previewHeight = p.getValue("preview_text_range_height_css_px").toDouble()
            check(previewY.isFinite() && previewHeight.isFinite() && previewHeight > 0)
            check(if (i == 0) nativeY == 0 && previewY == 0.0
                else nativeY > previousNative && previewY > previousPreview) {
                "Stage E6 collapsed/unsorted paragraph text"
            }
            lines += listOf(
                level.toString(), sourceLine.toString(), nativeY.toString(),
                "%.3f".format(java.util.Locale.ROOT, previewY),
                "%.3f".format(java.util.Locale.ROOT, nativeY - previewY),
            ).joinToString("\t")
            previousNative = nativeY
            previousPreview = previewY
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun followingTextAnchorNegativeControls(): Boolean {
        fun native() = "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY\n" +
            (0..5).joinToString("\n") { i ->
                "level=" + (i + 1) + "\tfollowing_line_zero_based=" + (4 * i + 2) +
                    "\tfollowing_y_document_px=" + (i * 120 + 50)
            }
        fun preview() = "geometry_scope=PLATFORM_PREVIEW_JCEF_TEXT_RANGE\n" +
            (0..5).joinToString("\n") { i ->
                "paragraph_index=" + (i + 1) + "\tsource_line_zero_based=" + (4 * i + 2) +
                    "\tpreview_text_range_top_relative_css_px=" + (i * 90) + ".0" +
                    "\tpreview_text_range_height_css_px=16.0"
            }
        val valid = compareFollowingTextAnchors(native(), preview())
        check(valid.lineSequence().any { it == "6\t22\t600\t450.000\t150.000" })
        val wrongSource = preview().replace(
            "paragraph_index=3\tsource_line_zero_based=10",
            "paragraph_index=3\tsource_line_zero_based=11"
        )
        val collapsed = preview().replace(
            "paragraph_index=3\tsource_line_zero_based=10\tpreview_text_range_top_relative_css_px=180.0",
            "paragraph_index=3\tsource_line_zero_based=10\tpreview_text_range_top_relative_css_px=90.0"
        )
        val zeroHeight = preview().replace(
            "preview_text_range_height_css_px=16.0", "preview_text_range_height_css_px=0.0"
        )
        return runCatching { compareFollowingTextAnchors(native(), wrongSource) }.isFailure &&
            runCatching { compareFollowingTextAnchors(native(), collapsed) }.isFailure &&
            runCatching { compareFollowingTextAnchors(native(), zeroHeight) }.isFailure &&
            runCatching { compareFollowingTextAnchors(native(), "NOT_A_PREVIEW") }.isFailure
    }

    /**
     * Compare source-identical heading anchors in their OWN coordinate systems:
     * relative to H1, so absolute scroll positioning is irrelevant.
     * Deliberately diagnostic-only until a reviewed cross-layout threshold
     * exists. No arbitrary tolerance can silently turn this into PASS.
     */
    private fun compareHeadingSourceCheckpoints(native: String, reference: String): String {
        fun rows(value: String): List<Map<String, String>> =
            value.lineSequence().filter { it.startsWith("level=") }.map { line ->
                line.split('\t').associate { token ->
                    val parts = token.split('=', limit = 2)
                    require(parts.size == 2) { "Malformed geometry row" }
                    parts[0] to parts[1]
                }
            }.toList()
        val nativeRows = rows(native)
        val referenceRows = rows(reference)
        check(nativeRows.size == 6 && referenceRows.size == 6) {
            "Six genuine source-aligned heading checkpoints required"
        }
        val nativeOrigin = nativeRows.first().getValue("heading_y_document_px").toInt()
        val result = mutableListOf(
            "schema=markflow-heading-relative-drift/v1",
            "gate=DIAGNOSTIC_ONLY_NO_REVIEWED_THRESHOLD",
            "level\tsource_line_zero_based\tnative_relative_px\treference_relative_css_px\tdelta_px"
        )
        var previousNative = -1
        var previousReference = -1.0
        nativeRows.zip(referenceRows).forEachIndexed { index, (nativeRow, referenceRow) ->
            val level = index + 1
            val line = (index * 4).toString()
            check(nativeRow.getValue("level") == level.toString() &&
                referenceRow.getValue("level") == level.toString() &&
                nativeRow.getValue("source_line_zero_based") == line &&
                referenceRow.getValue("source_line_zero_based") == line) {
                "Native/reference source heading identity mismatch"
            }
            val nativeY = nativeRow.getValue("heading_y_document_px").toInt() - nativeOrigin
            val referenceY = referenceRow.getValue("reference_y_relative_css_px").toDouble()
            check(nativeY >= 0 && nativeY > previousNative &&
                referenceY >= 0 && referenceY > previousReference || index == 0 &&
                    nativeY == 0 && referenceY == 0.0) {
                "Native/reference heading anchors are collapsed, unordered, or unaligned"
            }
            result += listOf(level, index * 4, nativeY,
                "%.3f".format(java.util.Locale.ROOT, referenceY),
                "%.3f".format(java.util.Locale.ROOT, nativeY - referenceY)
            ).joinToString("\t")
            previousNative = nativeY
            previousReference = referenceY
        }
        return result.joinToString("\n", postfix = "\n")
    }

    /**
     * Stage E5: measured installed native *block inlay rectangle* versus
     * measured bundled Preview *heading element rectangle*. This is a
     * substantially closer pair of geometric objects than source-line Y
     * versus DOM top, but these still have DIFFERENT box models, font metrics
     * and CSS margins. Do not interpret their difference as text-baseline
     * parity or as an approved acceptance tolerance.
     */
    private fun compareRenderedHeadingBoxes(native: String, reference: String): String {
        check(native.lineSequence().any {
            it == "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY"
        })
        check(reference.lineSequence().any {
            it == "geometry_scope=PLATFORM_PREVIEW_JCEF_DOM"
        })
        fun parseRows(content: String): List<Map<String, String>> =
            content.lineSequence().filter { it.startsWith("level=") }.map { line ->
                line.split('\t').associate { token ->
                    val pair = token.split('=', limit = 2)
                    require(pair.size == 2) { "Malformed installed heading geometry" }
                    pair[0] to pair[1]
                }
            }.toList()
        val nativeRows = parseRows(native)
        val referenceRows = parseRows(reference)
        check(nativeRows.size == 6 && referenceRows.size == 6) {
            "Stage E5 needs all six source-identical heading block boxes"
        }
        val firstNativeTop = nativeRows.first().getValue("inlay_bounds_y").toInt()
        val result = mutableListOf(
            "schema=markflow-heading-rendered-box-drift/v1",
            "comparison_scope=DIAGNOSTIC_BOX_VS_BOX_NOT_APPROVED_BASELINE",
            "level\tsource_line_zero_based\tnative_inlay_top_relative_px\tpreview_heading_box_top_relative_css_px\tbox_top_delta_px\tnative_inlay_height_px\tpreview_heading_height_css_px",
        )
        var previousNativeTop = -1
        var previousReferenceTop = -1.0
        nativeRows.zip(referenceRows).forEachIndexed { index, (n, ref) ->
            val level = index + 1
            val line = index * 4
            check(n.getValue("level") == "$level" &&
                ref.getValue("level") == "$level" &&
                n.getValue("source_line_zero_based") == "$line" &&
                ref.getValue("source_line_zero_based") == "$line") {
                "Stage E5 box/source identity mismatch"
            }
            val absoluteNativeTop = n.getValue("inlay_bounds_y").toInt()
            val nativeHeight = n.getValue("inlay_bounds_height").toInt()
            val reportedHeight = n.getValue("inlay_height_px").toInt()
            val referenceTop = ref.getValue("reference_y_relative_css_px").toDouble()
            val referenceHeight = ref.getValue("reference_height_css_px").toDouble()
            val nativeTop = absoluteNativeTop - firstNativeTop
            check(nativeHeight == reportedHeight && nativeHeight > 0 &&
                referenceHeight > 0 && referenceTop.isFinite())
            check(if (index == 0) nativeTop == 0 && referenceTop == 0.0
                else nativeTop > previousNativeTop && referenceTop > previousReferenceTop) {
                "Stage E5 collapsed or unordered visible heading rectangles"
            }
            result += listOf(
                level.toString(), line.toString(), nativeTop.toString(),
                "%.3f".format(java.util.Locale.ROOT, referenceTop),
                "%.3f".format(java.util.Locale.ROOT, nativeTop - referenceTop),
                nativeHeight.toString(),
                "%.3f".format(java.util.Locale.ROOT, referenceHeight),
            ).joinToString("\t")
            previousNativeTop = nativeTop
            previousReferenceTop = referenceTop
        }
        return result.joinToString("\n", postfix = "\n")
    }

    private fun renderedHeadingBoxNegativeControls(): Boolean {
        fun native() = "geometry_scope=NATIVE_ONLY_NO_PLATFORM_PARITY\n" +
            (0..5).joinToString("\n") { i ->
                "level=${i + 1}\tsource_line_zero_based=${i * 4}\t" +
                    "inlay_bounds_y=${i * 110}\tinlay_bounds_height=30\tinlay_height_px=30"
            }
        fun reference() = "geometry_scope=PLATFORM_PREVIEW_JCEF_DOM\n" +
            (0..5).joinToString("\n") { i ->
                "level=${i + 1}\tsource_line_zero_based=${i * 4}\t" +
                    "reference_y_relative_css_px=${i * 100}.0\treference_height_css_px=22.0"
            }
        val result = compareRenderedHeadingBoxes(native(), reference())
        check(result.lineSequence().any {
            it == "6\t20\t550\t500.000\t50.000\t30\t22.000"
        })
        return runCatching {
            compareRenderedHeadingBoxes(
                native().replace("inlay_bounds_height=30", "inlay_bounds_height=31"), reference()
            )
        }.isFailure && runCatching {
            compareRenderedHeadingBoxes(
                native().replace("level=4\tsource_line_zero_based=12",
                    "level=4\tsource_line_zero_based=13"), reference()
            )
        }.isFailure && runCatching {
            compareRenderedHeadingBoxes(
                native(), reference().replace(
                    "level=3\tsource_line_zero_based=8\treference_y_relative_css_px=200.0",
                    "level=3\tsource_line_zero_based=8\treference_y_relative_css_px=100.0"
                )
            )
        }.isFailure && runCatching {
            compareRenderedHeadingBoxes(native(), "NOT_A_REAL_PREVIEW")
        }.isFailure
    }

    /**
     * Real comparator adversarial fixtures: a changed source-line identity, a
     * collapsed preview anchor, or an unrelated page must never look aligned.
     * No threshold or golden update is inferred from the positive fixture.
     */
    private fun headingSourceIdentityNegativeControls(): Boolean {
        val native = (0..5).joinToString("\n") { i ->
            "level=" + (i + 1) + "\tsource_line_zero_based=" + (4 * i) +
                "\theading_y_document_px=" + (40 + 100 * i)
        }
        val reference = (0..5).joinToString("\n") { i ->
            "level=" + (i + 1) + "\tsource_line_zero_based=" + (4 * i) +
                "\treference_y_relative_css_px=" + (90.0 * i)
        }
        val valid = compareHeadingSourceCheckpoints(native, reference)
        val terminal = valid.lineSequence().firstOrNull { it.startsWith("6\t20\t") }
        if (terminal != "6\t20\t500\t450.000\t50.000") return false
        val wrongSource = reference.replace(
            "level=3\tsource_line_zero_based=8",
            "level=3\tsource_line_zero_based=7"
        )
        val collapsed = reference.replace(
            "level=3\tsource_line_zero_based=8\treference_y_relative_css_px=180.0",
            "level=3\tsource_line_zero_based=8\treference_y_relative_css_px=90.0"
        )
        return runCatching { compareHeadingSourceCheckpoints(native, wrongSource) }.isFailure &&
            runCatching { compareHeadingSourceCheckpoints(native, collapsed) }.isFailure &&
            runCatching { compareHeadingSourceCheckpoints(native, "NOT_READY") }.isFailure
    }

    /**
     * Conservative CI repro guard for the known #352 Gantt pixelation defect.
     * x2 is a CANDIDATE engineering ceiling at UI scale 1, NOT a reviewed
     * renderer parity or a substitute for semantic text-overlap checks.
     */
    private fun ganttRasterExceededCandidateScale(
        intrinsicWidth: Int, intrinsicHeight: Int, displayedWidth: Int, displayedHeight: Int
    ): Boolean {
        require(intrinsicWidth > 0 && intrinsicHeight > 0)
        require(displayedWidth > 0 && displayedHeight > 0)
        return displayedWidth.toLong() > intrinsicWidth.toLong() * 2L ||
            displayedHeight.toLong() > intrinsicHeight.toLong() * 2L
    }

    // Scoped Gantt-only fixture safeguard against a hidden 284px JCEF canvas.
    private fun ganttIntrinsicRasterWidthTooNarrow(width: Int): Boolean {
        require(width > 0)
        return width < 800
    }

    private fun candidateRasterScaleNegativeControls(): Boolean =
        !ganttRasterExceededCandidateScale(320, 180, 640, 360) &&
            ganttRasterExceededCandidateScale(320, 180, 641, 360) &&
            ganttRasterExceededCandidateScale(320, 180, 640, 361) &&
            !ganttRasterExceededCandidateScale(320, 180, 320, 180) &&
            ganttIntrinsicRasterWidthTooNarrow(799) &&
            !ganttIntrinsicRasterWidthTooNarrow(800) &&
            !ganttIntrinsicRasterWidthTooNarrow(960)

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}

@Remote(value = "com.algorist.markflow.editor.native.NativeVisualAcceptanceE2EBridge",
    plugin = "com.algorist.markflow")
private interface DifferentialManifestVisualRemote {
    fun prepareEditorChromeBeforeOpen()
    fun prepare(editor: Editor): String
    fun setMermaidSizeModeForDifferential(editor: Editor, mode: String): String
    fun normalizeEditorChromeForCapture(editor: Editor): Int
    fun environmentIdentity(editor: Editor): String
    fun contentScreenX(editor: Editor): Int
    fun contentScreenY(editor: Editor): Int
    fun contentWidth(editor: Editor): Int
    fun contentHeight(editor: Editor): Int
    fun visualEvidence(editor: Editor): String
}

@Remote(value = "com.algorist.markflow.renderer.jcef.JcefDerivedRendererLifecycleE2EBridge",
    plugin = "com.algorist.markflow")
private interface DifferentialRendererLifecycleRemote {
    fun liveRuntimeCount(): Int
}

@Remote(value = "com.algorist.markflow.editor.native.NativeDifferentialPreviewE2EBridge",
    plugin = "com.algorist.markflow")
private interface DifferentialManifestPreviewRemote {
    fun showNativeAtSourceLine(editor: Editor, line: Int): String
    fun showReferenceAtSourceLine(editor: Editor, line: Int): String
    fun referenceShowing(editor: Editor): Boolean
    fun referenceBounds(editor: Editor): String
    fun referencePanelAttached(editor: Editor): Boolean
    fun referencePanelShowing(editor: Editor): Boolean
    fun previewLifecycleEvidence(editor: Editor): String
    fun jcefRuntimeEvidence(): String
    fun nativeRasterGeometry(editor: Editor): String
    fun rasterGeometryNegativeControls(): Boolean
    fun nativeRasterScrollProbe(editor: Editor, index: Int, edge: String): String
    fun nativeRasterHorizontalScrollProbe(editor: Editor, index: Int, edge: String): String
    fun nativeVerticalScrollOffset(editor: Editor): Int
    fun nativeHorizontalScrollOffset(editor: Editor): Int
    fun restoreNativeVerticalScroll(editor: Editor, offset: Int)
    fun restoreNativeHorizontalScroll(editor: Editor, offset: Int)
    fun rasterScrollNegativeControls(): Boolean
    fun nativeHeadingSourceGeometry(editor: Editor): String
    fun nativeHeadingReplacementGeometry(editor: Editor): String
    fun setNativeEditorFontSize(editor: Editor, fontPx: Int): String
    fun restoreNativeEditorFontSize(editor: Editor): String
    fun nativeHeadingGeometryNegativeControls(): Boolean
    fun sourceText(editor: Editor): String
    fun sourceStamp(editor: Editor): Long
    fun primaryCaretOffset(editor: Editor): Int
    fun sourceUnsaved(editor: Editor): Boolean
    fun armDetachedReferenceBrowserCloseMarker(markerPath: String)
    fun restoreNative(editor: Editor)
    fun detachedReferenceBrowserClosed(): Boolean
    fun detachedReferenceBrowserEvidence(): String
    fun nativeShowing(editor: Editor): Boolean
}
