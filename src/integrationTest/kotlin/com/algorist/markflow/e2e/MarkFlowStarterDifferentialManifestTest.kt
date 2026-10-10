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
        val selectedIds = (System.getProperty("markflow.differential.caseIds")
            ?: "md-atx-headings-h1-h6,mermaid-flowchart-minimal").split(',').map { it.trim() }
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
            Files.writeString(output.resolve("summary.json"),
                "{\n\"schema\":\"markflow-stage-d-capture/v1\",\n" +
                    "\"source_head\":\"" + sourceHead + "\",\n" +
                    "\"manifest_sha256\":\"" + sha(manifest) + "\",\n" +
                    "\"manifest_cases\":88,\n\"expanded_cases\":" + inventory.size + ",\n" +
                    "\"selected_cases\":" + selected.size + ",\n" +
                    "\"captured_unverified\":" + captured + ",\n" +
                    "\"capture_failed\":" + failed + ",\n" +
                    "\"not_executed\":" + (inventory.size - captured - failed) + ",\n" +
                    "\"geometry_gate\":\"NOT_IMPLEMENTED\",\n" +
                    "\"full_differential_acceptance\":false,\n" +
                    "\"cases\":[\n" + records + "\n]}\n")
            sandbox.toFile().deleteRecursively()
        }
        if (failure != null) throw failure
        check(selected.all { states[it.id] == "CAPTURED_UNVERIFIED" })
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
