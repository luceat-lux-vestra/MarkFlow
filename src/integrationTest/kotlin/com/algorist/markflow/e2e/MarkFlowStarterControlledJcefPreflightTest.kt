package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
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
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #353 independent, two-iteration controlled-provider CANDIDATE.
 * Deliberately not a pass for the original platform Markdown file-editor
 * stress reproducer, 50-cycle lifecycle gate or 89-case full differential.
 * Kept separately opt-in to avoid changing the existing test verdict.
 */
class MarkFlowStarterControlledJcefPreflightTest {
    private val filename = "JCEF-LIFECYCLE-STRESS.md"
    private val anchorA = "One plain Markdown document is reopened for every iteration."
    private val anchorB = "The fourth simple paragraph keeps the visible vertical span nontrivial."
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
    @EnabledIfEnvironmentVariable(named = "MARKFLOW_CONTROLLED_JCEF_PREFLIGHT", matches = "1")
    fun twoIndependentBundledProviderPanelsCloseNatively() {
        val root = Path.of("").toAbsolutePath().normalize()
        val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
        val version = requireNotNull(System.getProperty("markflow.test.platformVersion"))
        check(version == "2026.2.3") { "Use exact #353 platform" }
        val sourceHead = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(root.toFile()).start().let { process ->
                val sha = process.inputStream.bufferedReader().readText().trim()
                check(process.waitFor() == 0 && sha.matches(Regex("[0-9a-f]{40}")))
                sha
            }
        System.getenv("STARTER_SOURCE_SHA")?.takeIf(String::isNotBlank)?.let {
            check(it == sourceHead) { "Controlled JCEF plugin checkout drifted" }
        }
        val output = root.resolve("build/e2e-evidence/derived-content/jcef-controlled-preflight")
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("run-contract.txt"),
            "schema=markflow-controlled-jcef-preflight/v1\n" +
                "source_head=$sourceHead\nide_version=$version\n" +
                "scope=CONTROLLED_PROVIDER_ONLY_NOT_PLATFORM_FILE_EDITOR\n" +
                "mode=preflight_2\niterations=2\nretries=0\nfull_differential_acceptance=false\n",
        )
        val sandbox = Files.createTempDirectory("markflow-353-controlled-jcef-")
        val project = sandbox.resolve("project")
        try {
            val base = root.resolve("src/integrationTest/resources/projects/markdown-smoke")
            check(base.toFile().copyRecursively(project.toFile(), overwrite = true))
            val fixturePath = project.resolve(filename)
            check(!Files.exists(fixturePath)) { "Controlled fixture name collision" }
            val original = fixture.toByteArray(StandardCharsets.UTF_8)
            Files.write(fixturePath, original)
            var completed = 0
            Starter.newContext(
                testName = "markflow-353-controlled-jcef-provider-preflight",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project))
                    .useRelease(version),
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
            }.runIdeWithDriver(runTimeout = 35.minutes).useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val preview = driver.utility(ControlledJcefRemote::class)
                val robot = Robot()
                val nonblank = MarkFlowStarterDifferentialPreviewSmokeTest()
                for (number in 1..2) {
                    val dest = output.resolve("iteration-" + number.toString().padStart(3, '0'))
                    Files.createDirectories(dest)
                    val journal = dest.resolve("native-events.tsv")
                    val closeMarker = dest.resolve("native-close.marker")
                    val opened = driver.withContext(OnDispatcher.EDT) {
                        preview.open(filename, journal.toString(), closeMarker.toString())
                    }
                    Files.writeString(dest.resolve("open.txt"), opened)
                    check(opened.contains("provider=com.intellij.markdown.jcef.preview.JCEFHtmlPanelProvider\n") &&
                        opened.contains("scope=TEST_ONLY_NOT_BUNDLED_FILE_EDITOR\n") &&
                        opened.contains("callback_failure=NONE\n")) {
                        "Controlled provider did not create the real bundled renderer"
                    }
                    waitFor(
                        message = "Owned native JCEF browser created in iteration $number",
                        timeout = 20.seconds,
                        getter = {
                            driver.withContext(OnDispatcher.EDT) {
                                preview.snapshot()
                            }.contains("native_created=true\n")
                        },
                        checker = { it },
                    )
                    val created = driver.withContext(OnDispatcher.EDT) { preview.snapshot() }
                    check(created.contains("native_created=true\n") &&
                        created.contains("jbcef_disposed=false\n") &&
                        created.contains("panel_showing=true\n") &&
                        created.contains("callback_failure=NONE\n"))
                    Files.writeString(dest.resolve("native-created.txt"), created)

                    // The actual CEF getText callback must contain BOTH fixture
                    // anchors. A visible Swing shell or blank Chromium page
                    // cannot satisfy this readiness gate.
                    var pending: Path? = null
                    var requests = 0
                    waitFor(
                        message = "Owned bundled Markdown DOM ready in iteration $number",
                        timeout = 20.seconds,
                        getter = {
                            val current = pending
                            when {
                                current == null -> {
                                    requests++
                                    val next = dest.resolve("dom-" + requests.toString().padStart(3, '0') + ".txt")
                                    driver.withContext(OnDispatcher.EDT) {
                                        preview.requestRenderedText(next.toString())
                                    }
                                    pending = next
                                    false
                                }
                                !Files.exists(current) -> false
                                else -> {
                                    val rendered = Files.readString(current)
                                    if (rendered.contains(anchorA) && rendered.contains(anchorB)) {
                                        Files.writeString(
                                            dest.resolve("dom-proof.txt"),
                                            "anchor_a=true\nanchor_b=true\nprobe=" + current.fileName + "\n",
                                        )
                                        true
                                    } else {
                                        pending = null
                                        false
                                    }
                                }
                            }
                        },
                        checker = { it },
                    )
                    val xywh = driver.withContext(OnDispatcher.EDT) {
                        preview.bounds().split(',').map(String::toInt)
                    }
                    check(xywh.size == 4)
                    val bounds = Rectangle(xywh[0], xywh[1], xywh[2], xywh[3])
                    waitFor(
                        message = "Owned bundled JCEF viewport painted in iteration $number",
                        timeout = 20.seconds,
                        getter = {
                            robot.waitForIdle()
                            val image = robot.createScreenCapture(bounds)
                            check(ImageIO.write(image, "png", dest.resolve("reference-paint.png").toFile()))
                            val region = image.getSubimage(
                                image.width / 20, image.height / 8,
                                image.width * 3 / 4, image.height * 5 / 8,
                            )
                            nonblank.referenceHasVisibleContent(region)
                        },
                        checker = { it },
                    )

                    val closing = driver.withContext(OnDispatcher.EDT) { preview.close() }
                    Files.writeString(dest.resolve("wrapper-disposed.txt"), closing)
                    check(closing.contains("dispose_requested=true\n") &&
                        closing.contains("jbcef_disposed=true\n"))
                    // No Driver/EDT calls from this wait getter: a lost CEF
                    // callback must remain a hard FAIL at the original bound.
                    waitFor(
                        message = "Exact owned native onBeforeClose in iteration $number",
                        timeout = 20.seconds,
                        getter = { Files.exists(closeMarker) },
                        checker = { it },
                    )
                    waitFor(
                        message = "Owned JCEF Java wrapper observes native closed in iteration $number",
                        timeout = 20.seconds,
                        getter = {
                            driver.withContext(OnDispatcher.EDT) {
                                preview.snapshot()
                            }.contains("cef_closed=true\n")
                        },
                        checker = { it },
                    )
                    val finished = driver.withContext(OnDispatcher.EDT) { preview.finish() }
                    Files.writeString(dest.resolve("native-closed.txt"), finished)
                    check(finished.contains("native_before_close=true\n") &&
                        finished.contains("cef_closed=true\n") &&
                        finished.contains("callback_failure=NONE\n"))
                    check(Files.readAllBytes(fixturePath).contentEquals(original)) {
                        "Owned JCEF renderer mutated the source fixture"
                    }
                    completed++
                }
            }
            check(completed == 2) { "Controlled preflight did not complete both native lifecycles" }
            Files.writeString(
                output.resolve("result.txt"),
                "result=PASS\nscope=CONTROLLED_PROVIDER_ONLY_NOT_PLATFORM_FILE_EDITOR\n" +
                    "mode=preflight_2\ncompleted=2\nfull_differential_acceptance=false\n",
            )
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeControlledReferencePreviewE2EBridge",
    plugin = "com.algorist.markflow",
)
private interface ControlledJcefRemote {
    fun open(filename: String, journalPath: String, closeMarkerPath: String): String
    fun snapshot(): String
    fun bounds(): String
    fun requestRenderedText(probePath: String)
    fun close(): String
    fun finish(): String
}
