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
import java.awt.Color
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #339 deterministic visual oracle.
 *
 * Source/fidelity and semantic/runtime correctness remain independently asserted elsewhere. This
 * suite captures only the native editor content surface after production presentation has converged.
 */
class MarkFlowStarterVisualAcceptanceTest {
    @Test
    fun comparatorRejectsMeaningfulPerturbation() {
        val expected = BufferedImage(128, 96, BufferedImage.TYPE_INT_ARGB)
        val actual = BufferedImage(128, 96, BufferedImage.TYPE_INT_ARGB)
        expected.createGraphics().use { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, expected.width, expected.height)
        }
        actual.createGraphics().use { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, actual.width, actual.height)
            graphics.color = Color.BLACK
            graphics.fillRect(32, 24, 48, 32)
        }

        val result = VisualGoldenComparator.compare(expected, actual)
        check(!result.passed) {
            "known meaningful visual perturbation unexpectedly passed: $result"
        }

        val identical = VisualGoldenComparator.compare(expected, expected)
        check(identical.passed) {
            "identical visual image unexpectedly failed: $identical"
        }
    }

    @Test
    fun representativeCorpusMatchesVersionedGoldens() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val platformVersion = System.getProperty("markflow.test.platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("markflow.test.platformVersion must be supplied by the Gradle integrationTest task")
        check(platformVersion == "2026.3") {
            "visual acceptance is authoritative only on stable IDEA 2026.3, got $platformVersion"
        }

        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val goldenRoot = Path.of("src/integrationTest/resources/visual-goldens")
            .toAbsolutePath()
            .normalize()
        val outputRoot = Path.of(
            System.getProperty("markflow.visual.outputDir") ?: "build/visual-acceptance"
        ).toAbsolutePath().normalize()
        val actualRoot = outputRoot.resolve("actual")
        val expectedRoot = outputRoot.resolve("expected")
        val diffRoot = outputRoot.resolve("diff")
        Files.createDirectories(actualRoot)
        Files.createDirectories(expectedRoot)
        Files.createDirectories(diffRoot)

        val tempRoot = Files.createTempDirectory("markflow-starter-visual-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic visual fixture project"
        }
        writeDeterministicLocalImage(projectPath.resolve("visual-proof.png"))

        val captures = linkedMapOf<String, Path>()
        var observedEnvironment: String? = null

        try {
            Starter.newContext(
                testName = "markflow-starter-visual-acceptance",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath))
                    .useRelease(platformVersion),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("ide.enable.notification.trace.data.sharing", false)
                addSystemProperty("sun.java2d.uiScale", "1.0")
                addSystemProperty("ide.ui.scale", "1.0")
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val visual = driver.utility(NativeVisualAcceptanceE2EBridgeRemote::class)

                fun capture(spec: VisualCase) {
                    driver.withContext(OnDispatcher.EDT) {
                        visual.prepareEditorChromeBeforeOpen()
                    }
                    val editor = markFlow.openMarkdown(spec.fileName)
                    markFlow.assertProductionProjectionAttached(editor)

                    driver.withContext(OnDispatcher.EDT) {
                        visual.prepare(editor.editor)
                    }

                    val source = markFlow.source(editor)
                    val anchor = source.indexOf("Visual acceptance anchor.")
                    check(anchor >= 0) { "visual fixture lacks inactive caret anchor: ${spec.fileName}" }
                    markFlow.resetToSingleCaret(editor, anchor + 3)

                    waitFor(
                        message = "visual presentation converges for ${spec.name}",
                        timeout = if (spec.derived) 45.seconds else 15.seconds,
                        getter = {
                            driver.withContext(OnDispatcher.EDT) {
                                visual.visualEvidence(editor.editor)
                            }
                        },
                        checker = { evidence -> spec.ready(parseEvidence(evidence)) },
                    )

                    val robot = Robot()
                    var environment: String? = null
                    for (attempt in 1..3) {
                        driver.withContext(OnDispatcher.EDT) {
                            visual.normalizeEditorChromeForCapture(editor.editor)
                            visual.normalizeViewportForCapture(editor.editor)
                        }
                        robot.waitForIdle()
                        waitFor(
                            message = "visual presentation re-converges after normalization for ${spec.name}",
                            timeout = if (spec.derived) 45.seconds else 15.seconds,
                            getter = {
                                driver.withContext(OnDispatcher.EDT) {
                                    visual.visualEvidence(editor.editor)
                                }
                            },
                            checker = { evidence -> spec.ready(parseEvidence(evidence)) },
                        )
                        robot.waitForIdle()
                        environment = driver.withContext(OnDispatcher.EDT) {
                            visual.environmentIdentity(editor.editor)
                        }
                        if (observedEnvironment == null || observedEnvironment == environment) {
                            break
                        }
                    }

                    val stableEnvironment = checkNotNull(environment)
                    if (observedEnvironment == null) {
                        observedEnvironment = stableEnvironment
                        Files.writeString(
                            actualRoot.resolve("environment.txt"),
                            stableEnvironment,
                            StandardCharsets.UTF_8,
                        )
                    } else {
                        check(observedEnvironment == stableEnvironment) {
                            "visual environment changed within one IDEA process after bounded normalization\n" +
                                "expected:\n$observedEnvironment" +
                                "actual:\n$stableEnvironment"
                        }
                    }

                    val actualPath = actualRoot.resolve("${spec.name}.png")
                    val captureEvidence = driver.withContext(OnDispatcher.EDT) {
                        visual.captureViewportWithEvidence(
                            editor.editor,
                            actualPath.toAbsolutePath().normalize().toString(),
                        )
                    }
                    check(spec.ready(parseEvidence(captureEvidence))) {
                        "visual presentation drifted at the atomic capture boundary for ${spec.name}"
                    }
                    Files.writeString(
                        actualRoot.resolve("${spec.name}.evidence.txt"),
                        captureEvidence + "\n",
                        StandardCharsets.UTF_8,
                    )
                    val image = requireNotNull(ImageIO.read(actualPath.toFile())) {
                        "atomic visual capture is not a readable PNG: $actualPath"
                    }
                    if (spec.name == "structure") {
                        // Keep the screen capture unchanged; sample existing native layout only
                        // after the captured raster and before closing this exact editor.
                        val geometry = driver.withContext(OnDispatcher.EDT) {
                            visual.structureGeometryEvidence(editor.editor)
                        }
                        Files.writeString(
                            actualRoot.resolve("structure-list-geometry.txt"),
                            geometry,
                            StandardCharsets.UTF_8,
                        )
                        assertStructureNativeListCoverage(geometry)
                    }
                    val candidate = actualRoot.resolve("${spec.name}.candidate.svg")
                    writeGoldenSvg(image, candidate, spec.name)
                    captures[spec.name] = actualPath
                    markFlow.close(editor)
                }

                VISUAL_CASES.forEach(::capture)
            }

            val failures = mutableListOf<String>()
            val environment = checkNotNull(observedEnvironment)
            val expectedEnvironment = goldenRoot.resolve("environment-2026.3.txt")
            if (!Files.exists(expectedEnvironment)) {
                Files.writeString(
                    diffRoot.resolve("environment-actual.txt"),
                    environment,
                    StandardCharsets.UTF_8,
                )
                failures += "missing stable 2026.3 visual environment baseline"
            } else {
                val expected = Files.readString(expectedEnvironment, StandardCharsets.UTF_8)
                if (expected != environment) {
                    Files.writeString(
                        diffRoot.resolve("environment-actual.txt"),
                        environment,
                        StandardCharsets.UTF_8,
                    )
                    failures += "visual environment identity differs from the reviewed baseline"
                }
            }

            VISUAL_CASES.forEach { spec ->
                val actualPath = checkNotNull(captures[spec.name])
                val versionedGolden = goldenRoot.resolve(platformVersion).resolve("${spec.name}.svg")
                val goldenPath = if (Files.exists(versionedGolden)) {
                    versionedGolden
                } else if (spec.name in STABLE_263_ROOT_REFERENCE_CASES) {
                    goldenRoot.resolve("${spec.name}.svg")
                } else {
                    versionedGolden
                }
                if (!Files.exists(goldenPath)) {
                    failures += "missing versioned golden ${goldenPath.toAbsolutePath().normalize()}"
                    return@forEach
                }

                val expected = readGoldenSvg(goldenPath)
                val actual = requireNotNull(ImageIO.read(actualPath.toFile())) {
                    "fresh visual capture is not a readable PNG: $actualPath"
                }
                ImageIO.write(expected, "png", expectedRoot.resolve("${spec.name}.png").toFile())
                val result = VisualGoldenComparator.compare(expected, actual)
                Files.writeString(
                    diffRoot.resolve("${spec.name}.metrics.txt"),
                    result.toDiagnosticText(),
                    StandardCharsets.UTF_8,
                )
                if (!result.passed) {
                    VisualGoldenComparator.writeDiff(
                        expected,
                        actual,
                        diffRoot.resolve("${spec.name}.png"),
                    )
                    failures += "${spec.name} visual mismatch: ${result.toDiagnosticText().trim()}"
                }
            }

            check(failures.isEmpty()) {
                "deterministic visual acceptance failed:\n" +
                    failures.joinToString(separator = "\n") { " - $it" } +
                    "\nFresh actual/candidate/diff artifacts are under $outputRoot"
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun parseEvidence(raw: String): Map<String, Int> =
        raw.split(';')
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.indexOf('=')
                check(separator > 0) { "malformed visual evidence entry: $entry" }
                entry.substring(0, separator) to entry.substring(separator + 1).toInt()
            }

    /**
     * Fail closed on the #563 structure contract before trusting its pixel golden.
     *
     * The bridge emits the live list models, actual inlay bounds, renderer row geometry,
     * and list-owned/foreign folds from the same editor. No source text is logged or changed.
     */
    private fun assertStructureNativeListCoverage(raw: String) {
        val lines = raw.lineSequence().filter(String::isNotBlank).toList()
        check(lines.firstOrNull() == "schema=markflow-visual-structure-list-geometry/v1") {
            "unexpected structure geometry evidence version"
        }
        fun fields(line: String): Map<String, String> =
            line.split('\t').associate { field ->
                val separator = field.indexOf('=')
                check(separator > 0) { "malformed structure geometry entry: $field" }
                field.substring(0, separator) to field.substring(separator + 1)
            }
        fun count(name: String): Int =
            checkNotNull(lines.singleOrNull { it.startsWith("$name=") }) {
                "missing or duplicate structure geometry count: $name"
            }.substringAfter('=').toInt()

        check(count("model_count") == 3 && count("owned_list_inlay_count") == 3) {
            "structure must render all three native list groups, including the nested unordered list"
        }
        val models = lines.filter { it.startsWith("model=") }.map(::fields)
        check(models.map { it.getValue("model").toInt() } == listOf(0, 1, 2)) {
            "structure list models must remain in source order"
        }
        check(models.map { it.getValue("rows").toInt() } == listOf(4, 2, 2)) {
            "structure list row counts must match nested, ordered and task fixtures"
        }
        check(models.all { model ->
            model.getValue("inlay_valid") == "true" &&
                model.getValue("inlay_bounds_xywh") != "ABSENT"
        }) {
            "structure list model lacks a valid, allocated native inlay"
        }

        val rows = List(3) { mutableListOf<Map<String, String>>() }
        var currentModel = -1
        lines.forEach { line ->
            when {
                line.startsWith("model=") -> currentModel = fields(line).getValue("model").toInt()
                line.startsWith("row=") -> {
                    check(currentModel in 0..2) { "structure row without a known owning model" }
                    rows[currentModel] += fields(line)
                }
            }
        }
        check(rows.map { group -> group.map { it.getValue("depth").toInt() } } ==
            listOf(listOf(0, 1, 2, 0), listOf(0, 0), listOf(0, 0))
        ) {
            "structure nested hierarchy or ordinary/task list shape differs from fixture"
        }
        check(rows.flatten().all { row ->
            row["row_height_px"]?.toIntOrNull()?.let { it > 0 } == true &&
                row["baseline_px"]?.toIntOrNull() != null
        }) {
            "structure row has no native renderer height/baseline evidence"
        }
        check(rows[0].all { it.getValue("task") == "false" } &&
            rows[2].all { it.getValue("task") == "true" }
        ) {
            "structure task markers are not confined to the task list"
        }

        val nestedStart = models[0].getValue("start").toInt()
        val nestedEnd = models[0].getValue("end").toInt()
        check(nestedStart < nestedEnd) { "invalid nested list source range" }
        val ownedCollapsedCoverage = lines.asSequence()
            .filter { it.startsWith("fold=") }
            .map(::fields)
            .filter {
                it["owner"] == "LIST_OWNED" &&
                    it["expanded"] == "false" &&
                    it["used_as_list_coverage"] == "true"
            }
            .map { it.getValue("start").toInt() to it.getValue("end").toInt() }
            .filter { (start, end) -> start < nestedEnd && end > nestedStart }
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        var coveredEnd = nestedStart
        ownedCollapsedCoverage.forEach { (start, end) ->
            if (coveredEnd in start until end) coveredEnd = end
        }
        check(coveredEnd >= nestedEnd) {
            "nested unordered list must be fully concealed by collapsed MarkFlow-owned folds"
        }
    }

    private fun writeDeterministicLocalImage(path: Path) {
        val image = BufferedImage(240, 96, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().use { graphics ->
            graphics.color = Color(248, 249, 250)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(32, 95, 180)
            graphics.fillRect(16, 16, 64, 64)
            graphics.color = Color(37, 150, 120)
            graphics.fillRect(88, 16, 64, 64)
            graphics.color = Color(203, 83, 73)
            graphics.fillRect(160, 16, 64, 64)
        }
        check(ImageIO.write(image, "png", path.toFile())) { "PNG writer unavailable" }
    }

    private fun writeGoldenSvg(image: BufferedImage, path: Path, name: String) {
        val png = ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG writer unavailable" }
            output.toByteArray()
        }
        val encoded = Base64.getEncoder().encodeToString(png)
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="${image.width}" height="${image.height}" viewBox="0 0 ${image.width} ${image.height}">
              <metadata>MarkFlow #339 deterministic candidate: $name; IDEA 2026.3</metadata>
              <image x="0" y="0" width="${image.width}" height="${image.height}" href="data:image/png;base64,$encoded"/>
            </svg>
        """.trimIndent() + "\n"
        Files.writeString(path, svg, StandardCharsets.UTF_8)
    }

    private fun readGoldenSvg(path: Path): BufferedImage {
        val svg = Files.readString(path, StandardCharsets.UTF_8)
        val marker = "data:image/png;base64,"
        val start = svg.indexOf(marker)
        check(start >= 0) { "visual golden does not contain an embedded PNG: $path" }
        val dataStart = start + marker.length
        val end = svg.indexOf('"', dataStart)
        check(end > dataStart) { "visual golden embedded PNG is unterminated: $path" }
        val bytes = Base64.getDecoder().decode(svg.substring(dataStart, end))
        return requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) {
            "visual golden embedded PNG is unreadable: $path"
        }
    }

    private data class VisualCase(
        val name: String,
        val fileName: String,
        val derived: Boolean = false,
        val ready: (Map<String, Int>) -> Boolean,
    )

    companion object {
        // Stable 263 changed typography/structure/code painting enough to require dedicated
        // reviewed rasters. Derived/resources independently reproduced within the existing
        // strict comparator tolerance on two 263 runs, so only those cases may use the
        // retained root (2026.2.3-origin) reference pixels.
        private val STABLE_263_ROOT_REFERENCE_CASES = setOf("derived", "resources")

        private val VISUAL_CASES = listOf(
            VisualCase("typography", "VISUAL-TYPOGRAPHY.md") { evidence ->
                // Six ATX plus two parser-proven simple Setext headings:
                // all eight now own non-additive native CustomFold line boxes.
                // This is structural readiness only; pixel goldens still fail
                // closed until independently reviewed and re-baselined.
                evidence.getValue("headingModels") == 8 &&
                    evidence.getValue("headingFolds") == 8 &&
                    evidence.getValue("headingInlays") == 0 &&
                    evidence.getValue("inlineHighlighters") >= 5 &&
                    evidence.getValue("linkModels") == 1 &&
                    evidence.getValue("linkContentRanges") == 1 &&
                    evidence.getValue("linkSemanticHighlighters") == 1 &&
                    evidence.getValue("linkAdditionalSyntaxLayers") == 1 &&
                    evidence.getValue("linkExactRangeTargets") == 1 &&
                    evidence.getValue("linkMaintainedAttributes") == 1 &&
                    evidence.getValue("linkSemanticReady") == 1
            },
            VisualCase("structure", "VISUAL-STRUCTURE.md") { evidence ->
                evidence.getValue("blockquoteInlays") >= 1 &&
                    // This fixture includes a nested unordered list, ordered list and task list.
                    // Two inlays once allowed the nested list to remain raw source in the golden.
                    evidence.getValue("listModels") == 3 &&
                    evidence.getValue("listInlays") == 3 &&
                    evidence.getValue("taskRows") == 2 &&
                    evidence.getValue("tableInlays") >= 1
            },
            VisualCase("code", "VISUAL-CODE.md") { evidence ->
                evidence.getValue("fencedInlays") >= 1 &&
                    evidence.getValue("indentedInlays") >= 1
            },
            VisualCase("derived", "VISUAL-DERIVED.md", derived = true) { evidence ->
                evidence.getValue("derivedFragments") >= 3 &&
                    evidence.getValue("derivedPending") == 0 &&
                    evidence.getValue("derivedDecoded") >= 3 &&
                    evidence.getValue("derivedInlays") >= 3
            },
            VisualCase("resources", "VISUAL-RESOURCES.md", derived = true) { evidence ->
                evidence.getValue("localImages") >= 1 &&
                    evidence.getValue("imagePending") == 0 &&
                    evidence.getValue("decodedImages") >= 1 &&
                    evidence.getValue("imageInlays") >= 1 &&
                    evidence.getValue("rawFragments") >= 2 &&
                    evidence.getValue("rawPending") == 0 &&
                    evidence.getValue("rawDecoded") + evidence.getValue("rawBlocked") >= 2
            },
        )
    }
}

private object VisualGoldenComparator {
    private const val PER_CHANNEL_TOLERANCE = 8
    private const val MAX_DIFFERING_PIXEL_RATIO = 0.0002
    private const val MIN_ALLOWED_DIFFERING_PIXELS = 64

    data class Result(
        val passed: Boolean,
        val width: Int,
        val height: Int,
        val differingPixels: Int,
        val allowedDifferingPixels: Int,
        val maxChannelDelta: Int,
        val dimensionMismatch: Boolean,
    ) {
        fun toDiagnosticText(): String =
            "passed=$passed\n" +
                "size=$width x $height\n" +
                "differing_pixels=$differingPixels\n" +
                "allowed_differing_pixels=$allowedDifferingPixels\n" +
                "max_channel_delta=$maxChannelDelta\n" +
                "per_channel_tolerance=$PER_CHANNEL_TOLERANCE\n" +
                "dimension_mismatch=$dimensionMismatch\n"
    }

    fun compare(expected: BufferedImage, actual: BufferedImage): Result {
        if (expected.width != actual.width || expected.height != actual.height) {
            return Result(
                passed = false,
                width = actual.width,
                height = actual.height,
                differingPixels = Int.MAX_VALUE,
                allowedDifferingPixels = 0,
                maxChannelDelta = 255,
                dimensionMismatch = true,
            )
        }

        val totalPixels = expected.width.toLong() * expected.height.toLong()
        val allowed = maxOf(
            MIN_ALLOWED_DIFFERING_PIXELS,
            ceil(totalPixels * MAX_DIFFERING_PIXEL_RATIO).toInt(),
        )
        var differing = 0
        var maxDelta = 0

        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val expectedRgb = expected.getRGB(x, y)
                val actualRgb = actual.getRGB(x, y)
                val delta = maxOf(
                    channelDelta(expectedRgb, actualRgb, 16),
                    channelDelta(expectedRgb, actualRgb, 8),
                    channelDelta(expectedRgb, actualRgb, 0),
                )
                maxDelta = maxOf(maxDelta, delta)
                if (delta > PER_CHANNEL_TOLERANCE) {
                    differing += 1
                }
            }
        }

        return Result(
            passed = differing <= allowed,
            width = actual.width,
            height = actual.height,
            differingPixels = differing,
            allowedDifferingPixels = allowed,
            maxChannelDelta = maxDelta,
            dimensionMismatch = false,
        )
    }

    fun writeDiff(expected: BufferedImage, actual: BufferedImage, path: Path) {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val diff = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x >= expected.width || y >= expected.height || x >= actual.width || y >= actual.height) {
                    diff.setRGB(x, y, Color(255, 0, 255).rgb)
                    continue
                }
                val expectedRgb = expected.getRGB(x, y)
                val actualRgb = actual.getRGB(x, y)
                val delta = maxOf(
                    channelDelta(expectedRgb, actualRgb, 16),
                    channelDelta(expectedRgb, actualRgb, 8),
                    channelDelta(expectedRgb, actualRgb, 0),
                )
                if (delta > PER_CHANNEL_TOLERANCE) {
                    diff.setRGB(x, y, Color(255, 0, 0).rgb)
                } else {
                    val red = (actualRgb ushr 16) and 0xff
                    val green = (actualRgb ushr 8) and 0xff
                    val blue = actualRgb and 0xff
                    val gray = ((red + green + blue) / 3).coerceIn(0, 255)
                    diff.setRGB(x, y, Color(gray, gray, gray).rgb)
                }
            }
        }
        check(ImageIO.write(diff, "png", path.toFile())) { "PNG writer unavailable" }
    }

    private fun channelDelta(expected: Int, actual: Int, shift: Int): Int =
        kotlin.math.abs(((expected ushr shift) and 0xff) - ((actual ushr shift) and 0xff))
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeVisualAcceptanceE2EBridge",
    plugin = "com.algorist.markflow",
)
private interface NativeVisualAcceptanceE2EBridgeRemote {
    fun prepareEditorChromeBeforeOpen()
    fun prepare(editor: Editor): String
    fun normalizeViewportForCapture(editor: Editor)
    fun normalizeEditorChromeForCapture(editor: Editor): Int
    fun environmentIdentity(editor: Editor): String
    fun captureViewportWithEvidence(editor: Editor, outputPath: String): String
    fun visualEvidence(editor: Editor): String
    fun structureGeometryEvidence(editor: Editor): String
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
