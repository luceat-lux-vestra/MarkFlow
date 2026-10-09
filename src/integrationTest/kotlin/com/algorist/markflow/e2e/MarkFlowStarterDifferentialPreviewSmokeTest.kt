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

/**
 * First bounded real-platform capture for #353. This proves UI access and emits
 * raw screenshots. It DOES NOT certify convergence, geometry or visual parity.
 */
class MarkFlowStarterDifferentialPreviewSmokeTest {
    @Test
    fun capturesNativeAndBundledPreviewWithoutChangingAuthoritativeSource() {
        val plugin = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: error("Starter requires path.to.build.plugin")
        val version = System.getProperty("markflow.test.platformVersion")
            ?: error("Starter requires markflow.test.platformVersion")
        check(version == "2026.2.3") { "Platform preview is pinned to IDEA 2026.2.3" }
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath().normalize()
        val sandbox = Files.createTempDirectory("markflow-differential-preview-")
        val project = sandbox.resolve("project")
        check(fixtureProject.toFile().copyRecursively(project.toFile(), overwrite = true))
        val file = project.resolve("VISUAL-DERIVED.md")
        val expectedBytes = Files.readAllBytes(file)
        val expectedSource = String(expectedBytes, StandardCharsets.UTF_8)
        check(expectedSource.toByteArray(StandardCharsets.UTF_8).contentEquals(expectedBytes))
        val anchor = expectedSource.indexOf("graph LR")
        check(anchor >= 0 && expectedSource.lastIndexOf("graph LR") == anchor)
        val sourceLine = expectedSource.take(anchor).count { it == '\n' }
        val output = Path.of("build/e2e-evidence/derived-content/differential-preview-smoke")
            .toAbsolutePath().normalize()
        Files.createDirectories(output)

        try {
            Starter.newContext(
                testName = "markflow-353-platform-preview-raw-pair",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project))
                    .useRelease(version)
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
                val visual = driver.utility(DifferentialVisualBridgeRemote::class)
                val preview = driver.utility(DifferentialPreviewBridgeRemote::class)

                driver.withContext(OnDispatcher.EDT) {
                    visual.prepareEditorChromeBeforeOpen()
                }
                val editor = markFlow.openMarkdown("VISUAL-DERIVED.md")
                markFlow.assertProductionProjectionAttached(editor)
                val sourceEditor = editor.editor // cached before SHOW_PREVIEW hides EditorComponentImpl
                check(markFlow.source(editor) == expectedSource)
                val initialStamp = markFlow.modificationStamp(editor)
                driver.withContext(OnDispatcher.EDT) { visual.prepare(editor.editor) }
                markFlow.resetToSingleCaret(editor, expectedSource.indexOf("Visual acceptance anchor.") + 3)

                waitFor(
                    message = "native derived presentation reaches decoded, zero-pending state",
                    timeout = 45.seconds,
                    getter = {
                        driver.withContext(OnDispatcher.EDT) {
                            visual.visualEvidence(editor.editor)
                        }
                    },
                    checker = { raw ->
                        val entries = raw.split(';').mapNotNull {
                            val parts = it.split('=')
                            if (parts.size == 2) parts[1].toIntOrNull()?.let { n -> parts[0] to n }
                            else null
                        }.toMap()
                        entries["derivedPending"] == 0 &&
                            (entries["derivedDecoded"] ?: 0) >= 3 &&
                            (entries["derivedInlays"] ?: 0) >= 3
                    }
                )
                driver.withContext(OnDispatcher.EDT) {
                    visual.normalizeEditorChromeForCapture(editor.editor)
                }
                val nativeEnvironment = driver.withContext(OnDispatcher.EDT) {
                    visual.environmentIdentity(editor.editor)
                }
                val native = driver.withContext(OnDispatcher.EDT) {
                    Rectangle(
                        visual.contentScreenX(editor.editor),
                        visual.contentScreenY(editor.editor),
                        visual.contentWidth(editor.editor),
                        visual.contentHeight(editor.editor)
                    )
                }
                check(native.width == 1200 && native.height == 760)
                val robot = Robot()
                robot.waitForIdle()
                check(ImageIO.write(robot.createScreenCapture(native), "png",
                    output.resolve("markflow.png").toFile()))

                val referenceIdentity = driver.withContext(OnDispatcher.EDT) {
                    preview.showReferenceAtSourceLine(sourceEditor, sourceLine)
                }
                waitFor(
                    message = "real bundled Markdown Preview is visible",
                    timeout = 30.seconds,
                    getter = {
                        driver.withContext(OnDispatcher.EDT) {
                            preview.referenceShowing(sourceEditor)
                        }
                    },
                    checker = { shown -> shown }
                )
                val reference = driver.withContext(OnDispatcher.EDT) {
                    val parts = preview.referenceBounds(sourceEditor).split(',').map(String::toInt)
                    check(parts.size == 4)
                    Rectangle(parts[0], parts[1], parts[2], parts[3])
                }
                robot.waitForIdle()
                check(ImageIO.write(robot.createScreenCapture(reference), "png",
                    output.resolve("intellij-preview.png").toFile()))

                // EditorComponentImpl is intentionally absent during SHOW_PREVIEW;
                // verify exact source through the cached remote Editor before restore.
                check(driver.withContext(OnDispatcher.EDT) { preview.sourceText(sourceEditor) } == expectedSource)
                check(driver.withContext(OnDispatcher.EDT) { preview.sourceStamp(sourceEditor) } == initialStamp) {
                    "Bundled preview mutated Document modification stamp"
                }
                check(!driver.withContext(OnDispatcher.EDT) { preview.sourceUnsaved(sourceEditor) }) {
                    "Bundled preview dirtied the source Document"
                }
                driver.withContext(OnDispatcher.EDT) {
                    preview.restoreNative(sourceEditor)
                }
                check(markFlow.source(editor) == expectedSource)
                check(Files.readAllBytes(file).contentEquals(expectedBytes))
                Files.write(output.resolve("source.md"), expectedBytes)
                Files.writeString(
                    output.resolve("identity.txt"),
                    "idea=" + version + "\nreference=" + referenceIdentity + "\n" +
                        "fixture_sha256=" + sha256(expectedBytes) + "\n" +
                        "source_line_zero_based=" + sourceLine + "\n" +
                        "native_viewport=" + native.width + "x" + native.height + "\n" +
                        "preview_viewport=" + reference.width + "x" + reference.height + "\n" +
                        nativeEnvironment,
                    StandardCharsets.UTF_8
                )
                Files.writeString(
                    output.resolve("metrics.json"),
                    """{"schema":"markflow-raw-pair/v1","capture":"raw-pair","preview_convergence":"unverified","anchor_alignment":"unverified","geometry":"unverified","visual_parity":"unverified","differential_pass":false}""" + "\n",
                    StandardCharsets.UTF_8
                )
                markFlow.close(editor)
            }
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeVisualAcceptanceE2EBridge",
    plugin = "com.algorist.markflow"
)
private interface DifferentialVisualBridgeRemote {
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

@Remote(
    value = "com.algorist.markflow.editor.native.NativeDifferentialPreviewE2EBridge",
    plugin = "com.algorist.markflow"
)
private interface DifferentialPreviewBridgeRemote {
    fun showReferenceAtSourceLine(editor: Editor, line: Int): String
    fun referenceShowing(editor: Editor): Boolean
    fun referenceBounds(editor: Editor): String
    fun sourceText(editor: Editor): String
    fun sourceStamp(editor: Editor): Long
    fun sourceUnsaved(editor: Editor): Boolean
    fun restoreNative(editor: Editor)
}
