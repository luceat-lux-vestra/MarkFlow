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
import java.awt.Color
import java.awt.Font
import java.awt.Robot
import java.awt.image.BufferedImage
import java.util.Locale
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
                // Persist the actual IDE process identity BEFORE any JCEF failure.
                // IDEA may use a different Java binary from the archived JBR.
                val actualRuntime = driver.withContext(OnDispatcher.EDT) {
                    preview.jcefRuntimeEvidence()
                }
                Files.writeString(
                    output.resolve("jcef-runtime-evidence.txt"),
                    actualRuntime,
                    StandardCharsets.UTF_8
                )
                val expectedIdeaExecutable = "/idea-IU-262.10968.63/bin/idea"
                check(actualRuntime.lineSequence().any {
                    it.startsWith("idea_apparmor_current=") && it.contains(expectedIdeaExecutable)
                }) {
                    "Actual IDEA process is not attached to the pinned AppArmor userns profile; " +
                        "see retained jcef-runtime-evidence.txt"
                }
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
                // A visible Swing/JCEF panel can still be an empty background while
                // Chromium is loading. This exact fixture must have substantial text
                // AND derived content; blank or partial paint is fail-closed.
                waitFor(
                    message = "real platform Markdown Preview paints nonblank source-anchored content",
                    timeout = 60.seconds,
                    getter = {
                        robot.waitForIdle()
                        val image = robot.createScreenCapture(reference)
                        check(ImageIO.write(image, "png",
                            output.resolve("intellij-preview-probe.png").toFile()))
                        referenceHasVisibleContent(image)
                    },
                    checker = { ready -> ready }
                )
                Files.copy(
                    output.resolve("intellij-preview-probe.png"),
                    output.resolve("intellij-preview.png"),
                    StandardCopyOption.REPLACE_EXISTING
                )

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
                waitFor(
                    message = "source editor returns to visible native layout after preview",
                    timeout = 20.seconds,
                    getter = {
                        driver.withContext(OnDispatcher.EDT) { preview.nativeShowing(sourceEditor) }
                    },
                    checker = { showing -> showing }
                )
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
                // Raw pairs are now proven to originate from exact same source.
                // Produce diagnostic images/metrics without declaring visual parity.
                writeDiagnosticPair(output, sha256(expectedBytes), sourceLine)
                markFlow.close(editor)
            }
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    /**
     * Hard nonblank smoke invariant for VISUAL-DERIVED.md, not a parity score.
     * Samples away from UI edges and compares against the actual bottom-right
     * viewport background, so a uniform JCEF surface cannot pass. Both axes
     * need a meaningful occupied span; an isolated border/caret does not pass.
     * Thresholds are fixed, never adapted from a failing candidate.
     */
    private fun referenceHasVisibleContent(image: BufferedImage): Boolean {
        if (image.width < 400 || image.height < 300) return false
        val background = image.getRGB(image.width - 25, image.height - 25)
        val br = (background ushr 16) and 0xff
        val bg = (background ushr 8) and 0xff
        val bb = background and 0xff
        var occupied = 0
        var minX = image.width
        var maxX = 0
        var minY = image.height
        var maxY = 0
        for (y in 18 until image.height - 18 step 3) {
            for (x in 18 until image.width - 18 step 3) {
                val rgb = image.getRGB(x, y)
                val dr = kotlin.math.abs(((rgb ushr 16) and 0xff) - br)
                val dg = kotlin.math.abs(((rgb ushr 8) and 0xff) - bg)
                val db = kotlin.math.abs((rgb and 0xff) - bb)
                if (dr + dg + db > 150) {
                    occupied++
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        return occupied >= 150 && maxX - minX >= 160 && maxY - minY >= 60
    }

    /**
     * Diagnostic only: raw viewport pixels are NOT registered by source anchor,
     * typography or Mermaid engine. Their RGB delta must never be release-gating.
     * This deliberately records the limitation in both the metrics and PNGs.
     */
    private fun writeDiagnosticPair(output: Path, sourceHash: String, anchorLine: Int) {
        val native = requireNotNull(ImageIO.read(output.resolve("markflow.png").toFile())) {
            "Captured native PNG is unreadable"
        }
        val reference = requireNotNull(ImageIO.read(output.resolve("intellij-preview.png").toFile())) {
            "Captured real IntelliJ Markdown Preview PNG is unreadable"
        }
        check(native.width == 1200 && native.height == 760)
        check(referenceHasVisibleContent(reference)) { "Blank Preview cannot produce accepted diagnostics" }
        val header = 36
        val gap = 12
        val side = BufferedImage(native.width + gap + reference.width,
            header + maxOf(native.height, reference.height), BufferedImage.TYPE_INT_RGB)
        val g = side.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, side.width, side.height)
            g.color = Color.BLACK
            g.font = Font("Dialog", Font.PLAIN, 14)
            g.drawString("MarkFlow native (source-backed)", 12, 22)
            g.drawString("IntelliJ 2026.2.3 bundled Markdown Preview", native.width + gap + 12, 22)
            g.drawImage(native, 0, header, null)
            g.drawImage(reference, native.width + gap, header, null)
        } finally {
            g.dispose()
        }
        check(ImageIO.write(side, "png", output.resolve("side-by-side.png").toFile()))

        // Rescale only for a qualitative, explicitly UNREGISTERED raw-pixel diff.
        // Different font and viewport geometries are not semantically aligned.
        val scaled = BufferedImage(native.width, native.height, BufferedImage.TYPE_INT_RGB)
        val sg = scaled.createGraphics()
        try {
            sg.drawImage(reference, 0, 0, native.width, native.height, null)
        } finally {
            sg.dispose()
        }
        val diff = BufferedImage(native.width, native.height, BufferedImage.TYPE_INT_RGB)
        var absoluteDelta = 0L
        for (y in 0 until native.height) {
            for (x in 0 until native.width) {
                val a = native.getRGB(x, y)
                val b = scaled.getRGB(x, y)
                val dr = kotlin.math.abs(((a ushr 16) and 255) - ((b ushr 16) and 255))
                val dg = kotlin.math.abs(((a ushr 8) and 255) - ((b ushr 8) and 255))
                val db = kotlin.math.abs((a and 255) - (b and 255))
                absoluteDelta += (dr + dg + db).toLong()
                diff.setRGB(x, y, (dr shl 16) or (dg shl 8) or db)
            }
        }
        check(ImageIO.write(diff, "png", output.resolve("diff.png").toFile()))
        val mae = absoluteDelta.toDouble() / (native.width.toLong() * native.height * 3)
        val maeJson = String.format(Locale.ROOT, "%.4f", mae)
        // Never promote this diagnostic MAE into an image acceptance threshold.
        val metrics = """{"schema":"markflow-raw-pair/v2","source_sha256":"$sourceHash","source_anchor_line_zero_based":$anchorLine,"native_viewport":"${native.width}x${native.height}","preview_viewport":"${reference.width}x${reference.height}","preview_convergence":"nonblank-smoke-only","anchor_alignment":"unverified","geometry":"unverified","raw_pixel_registration":"none","unregistered_normalized_rgb_mae_diagnostic_only":$maeJson,"hard_structural_gate":"not-implemented","visual_parity":"unverified","differential_pass":false}"""
        Files.writeString(output.resolve("metrics.json"), metrics + "\n", StandardCharsets.UTF_8)
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
    fun jcefRuntimeEvidence(): String
    fun sourceText(editor: Editor): String
    fun sourceStamp(editor: Editor): Long
    fun sourceUnsaved(editor: Editor): Boolean
    fun restoreNative(editor: Editor)
    fun nativeShowing(editor: Editor): Boolean
}
