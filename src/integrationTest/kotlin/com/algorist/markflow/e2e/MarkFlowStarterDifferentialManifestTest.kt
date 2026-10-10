package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Stage D: capture proof only. Missing captures and visual acceptance never become PASS. */
class MarkFlowStarterDifferentialManifestTest {
    private class Case(
        val id: String, val parent: String, val domain: String, val classification: String,
        val path: String, val bytes: ByteArray, val line: Int, val blockHash: String?,
        val comparable: Boolean
    )

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
        val selectedIds = (System.getProperty("markflow.differential.caseIds")
            ?: "md-atx-headings-h1-h6,mermaid-flowchart-minimal,mermaid-gantt").split(',').map { it.trim() }
        check(selectedIds.isNotEmpty() && selectedIds.toSet().size == selectedIds.size)
        val indexed = inventory.associateBy { it.id }
        val selected = selectedIds.map { requireNotNull(indexed[it]) { "Unknown case: " + it } }
        check(selected.all { it.comparable && it.classification == "supported" })
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
        val states = inventory.associate { it.id to "NOT_EXECUTED" }.toMutableMap()
        val structuralFailures = mutableListOf<String>()
        val sandbox = Files.createTempDirectory("markflow-353-manifest-")
        val project = sandbox.resolve("project")
        var failure: Throwable? = null
        try {
            val base = root.resolve("src/integrationTest/resources/projects/markdown-smoke")
            check(base.toFile().copyRecursively(project.toFile(), overwrite = true))
            selected.forEach { entry ->
                val target = project.resolve(entry.id + ".md")
                check(!Files.exists(target)) { "Capture fixture collision" }
                Files.write(target, entry.bytes)
                val dest = output.resolve(entry.id)
                Files.createDirectories(dest)
                Files.write(dest.resolve("source.md"), entry.bytes)
                Files.writeString(dest.resolve("identity.txt"),
                    "case_id=" + entry.id + "\nmanifest_id=" + entry.parent +
                    "\nclassification=" + entry.classification + "\nfixture=" + entry.path +
                    "\nsource_sha256=" + sha(entry.bytes) +
                    "\nblock_sha256=" + (entry.blockHash ?: "none") +
                    "\nsource_line_zero_based=" + entry.line +
                    "\nsource_head=" + sourceHead +
                    "\nmanifest_sha256=" + sha(manifest) + "\n")
            }
            Starter.newContext(
                testName = "markflow-353-bounded-manifest-real-pairs",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project)).useRelease(version)
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("ide.browser.jcef.jsQueryPoolSize", "10000")
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("sun.java2d.uiScale", "1.0")
                addSystemProperty("ide.ui.scale", "1.0")
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val visual = driver.utility(DifferentialManifestVisualRemote::class)
                val preview = driver.utility(DifferentialManifestPreviewRemote::class)
                val diagnostics = MarkFlowStarterDifferentialPreviewSmokeTest()
                val robot = Robot()
                check(driver.withContext(OnDispatcher.EDT) {
                    preview.rasterGeometryNegativeControls()
                }) { "Stage D raster geometry negative controls failed" }
                check(driver.withContext(OnDispatcher.EDT) {
                    preview.nativeHeadingGeometryNegativeControls()
                }) { "Stage E1 source-span geometry negative controls failed" }
                driver.withContext(OnDispatcher.EDT) { visual.prepareEditorChromeBeforeOpen() }
                for (entry in selected) {
                    val dest = output.resolve(entry.id)
                    states[entry.id] = "CAPTURE_FAILED"
                    try {
                        val editor = markFlow.openMarkdown(entry.id + ".md")
                        markFlow.assertProductionProjectionAttached(editor)
                        val remoteEditor = editor.editor
                        val source = String(entry.bytes, StandardCharsets.UTF_8)
                        check(markFlow.source(editor) == source)
                        val stamp = markFlow.modificationStamp(editor)
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
                        if (entry.domain == "mermaid") {
                            check(rasterCount >= 1) {
                                "Stage D Mermaid case has no measured native raster inlays"
                            }
                        }
                        Files.writeString(
                            dest.resolve("native-raster-geometry.txt"),
                            nativeRasterGeometry, StandardCharsets.UTF_8
                        )
                        if (entry.id == "mermaid-gantt") {
                            check(rasterCount == 1) { "Gantt must own exactly one measured native raster" }
                            val row = nativeRasterGeometry.lineSequence().single {
                                it.startsWith("index=")
                            }
                            val fields = row.split('\t').associate { field ->
                                val parts = field.split('=', limit = 2)
                                check(parts.size == 2) { "Malformed Gantt raster dimension" }
                                parts[0] to parts[1]
                            }
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
                        val refIdentity = driver.withContext(OnDispatcher.EDT) {
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
                        val reference = driver.withContext(OnDispatcher.EDT) {
                            val xywh = preview.referenceBounds(remoteEditor).split(',').map(String::toInt)
                            check(xywh.size == 4)
                            Rectangle(xywh[0], xywh[1], xywh[2], xywh[3])
                        }
                        waitFor(
                            message = "Bundled JCEF paints nonblank source: " + entry.id,
                            timeout = 60.seconds,
                            getter = {
                                robot.waitForIdle()
                                val image = robot.createScreenCapture(reference)
                                check(ImageIO.write(image, "png", dest.resolve("intellij-preview.png").toFile()))
                                diagnostics.referenceHasVisibleContent(image)
                            },
                            checker = { it }
                        )
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
                            // Neither native inlay block nor HTML heading DOM box
                            // defines an agreed shared text/baseline anchor yet.
                            // Thus no arbitrary box delta is certified as parity.
                            // Keep the work intentionally fail-closed, not falsely
                            // FAIL_KNOWN_DEFECT on a dimensionally invalid comparison.
                            structuralFailures += listOf(
                                entry.id, "HEADING_LAYOUT_ANCHOR_CONTRACT_UNREVIEWED",
                                "NATIVE_INLAY_BOX_VS_PREVIEW_DOM_HEADING_BOX",
                                "REVIEW_SHARED_TEXT_BASELINE_AND_BOX_MODEL"
                            ).joinToString("\t")
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
                        driver.withContext(OnDispatcher.EDT) { preview.restoreNative(remoteEditor) }
                        waitFor(
                            message = "Native restored: " + entry.id, timeout = 20.seconds,
                            getter = { driver.withContext(OnDispatcher.EDT) {
                                preview.nativeShowing(remoteEditor)
                            } },
                            checker = { it }
                        )
                        check(markFlow.source(editor) == source)
                        check(Files.readAllBytes(project.resolve(entry.id + ".md")).contentEquals(entry.bytes))
                        Files.writeString(dest.resolve("identity.txt"),
                            Files.readString(dest.resolve("identity.txt")) +
                                "reference=" + refIdentity + "\nnative_viewport=" +
                                native.width + "x" + native.height + "\npreview_viewport=" +
                                reference.width + "x" + reference.height + "\n" + nativeEnvironment)
                        diagnostics.writeDiagnosticPair(dest, sha(entry.bytes), entry.line)
                        states[entry.id] = "CAPTURED_UNVERIFIED"
                        markFlow.close(editor)
                    } catch (cause: Throwable) {
                        Files.writeString(dest.resolve("failure.txt"),
                            cause.javaClass.name + ": " + cause.message + "\n")
                        throw cause
                    }
                }
            }
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
                    "\"captured_unverified\":" + captured + ",\n" +
                    "\"capture_failed\":" + failed + ",\n" +
                    "\"not_executed\":" + (inventory.size - captured - failed) + ",\n" +
                    "\"geometry_gate\":\"" +
                        (if (structuralFailures.isEmpty()) "PENDING_REVIEW_AND_FULL_CORPUS"
                         else "FAIL_KNOWN_DEFECT_CANDIDATE_BOUNDS") + "\",\n" +
                    "\"candidate_structural_failure_count\":" + structuralFailures.size + ",\n" +
                    "\"full_differential_acceptance\":false,\n" +
                    "\"cases\":[\n" + records + "\n]}\n")
            sandbox.toFile().deleteRecursively()
        }
        if (failure != null) throw failure
        check(selected.all { states[it.id] == "CAPTURED_UNVERIFIED" })
        check(structuralFailures.isEmpty()) {
            "Stage E3 deterministic known-defect repros exceeded candidate bounds: " +
                structuralFailures.joinToString("; ") +
                "; artifacts retained, thresholds require maintainer review before merge"
        }
    }

    private fun inventory(root: Path, manifest: String): List<Case> {
        check(manifest.endsWith("\n") && !manifest.contains('\r'))
        val rows = manifest.trimEnd('\n').split('\n')
        check(rows.first().split('\t').size == 12)
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
                items += Case(id, id, domain, classification, path, bytes, 0, null, comparable)
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
                        bytes, block.first, block.second, comparable)
                }
            }
        }
        return items
    }

    /**
     * From the actual bundled Markdown preview DOM. Each heading must match the
     * review-owned H1-H6 source fixture exactly; a blank or unrelated JCEF page
     * is not an oracle. Relative bounding coordinates are invariant under a
     * shared browser scroll displacement (unlike viewport screenshot offsets).
     */
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
            if (!(rect.height > 0) || !(rect.width > 0))
              return 'FAIL: DOM collapsed heading ' + level;
            lines.push('level=' + level +
              '\tsource_line_zero_based=' + (index * 4) +
              '\treference_y_relative_css_px=' + (rect.top - firstY).toFixed(3) +
              '\treference_height_css_px=' + rect.height.toFixed(3) +
              '\tcomputed_margin_top_css_px=' + parseFloat(getComputedStyle(el).marginTop).toFixed(3) +
              '\tcomputed_margin_bottom_css_px=' + parseFloat(getComputedStyle(el).marginBottom).toFixed(3));
          }
          return lines.join('\n');
        })()
    """.trimIndent()

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

    private fun candidateRasterScaleNegativeControls(): Boolean =
        !ganttRasterExceededCandidateScale(320, 180, 640, 360) &&
            ganttRasterExceededCandidateScale(320, 180, 641, 360) &&
            ganttRasterExceededCandidateScale(320, 180, 640, 361) &&
            !ganttRasterExceededCandidateScale(320, 180, 320, 180)

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
    fun normalizeEditorChromeForCapture(editor: Editor): Int
    fun environmentIdentity(editor: Editor): String
    fun contentScreenX(editor: Editor): Int
    fun contentScreenY(editor: Editor): Int
    fun contentWidth(editor: Editor): Int
    fun contentHeight(editor: Editor): Int
    fun visualEvidence(editor: Editor): String
}

@Remote(value = "com.algorist.markflow.editor.native.NativeDifferentialPreviewE2EBridge",
    plugin = "com.algorist.markflow")
private interface DifferentialManifestPreviewRemote {
    fun showNativeAtSourceLine(editor: Editor, line: Int): String
    fun showReferenceAtSourceLine(editor: Editor, line: Int): String
    fun referenceShowing(editor: Editor): Boolean
    fun referenceBounds(editor: Editor): String
    fun jcefRuntimeEvidence(): String
    fun nativeRasterGeometry(editor: Editor): String
    fun rasterGeometryNegativeControls(): Boolean
    fun nativeHeadingSourceGeometry(editor: Editor): String
    fun nativeHeadingGeometryNegativeControls(): Boolean
    fun sourceText(editor: Editor): String
    fun sourceStamp(editor: Editor): Long
    fun sourceUnsaved(editor: Editor): Boolean
    fun restoreNative(editor: Editor)
    fun nativeShowing(editor: Editor): Boolean
}
