package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.Editor
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
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #353 independent, test-owned bundled JCEF provider lifecycle candidate.
 * The 2-open preflight and the 50-open stress are independently opt-in.
 * Even 50 successful controlled-provider cycles cannot clear the original
 * platform FileEditor stress reproducer or 89-case full differential.
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
    fun twoIndependentBundledProviderPanelsCloseNatively() = runControlled(2, "preflight_2")

    @Test
    @EnabledIfEnvironmentVariable(named = "MARKFLOW_CONTROLLED_JCEF_STRESS_50", matches = "1")
    fun fiftyIndependentBundledProviderPanelsCloseNatively() = runControlled(50, "stress_50")

    /** Manual A/B in one IDE: ordinary async close, then synchronous RPC close. */
    @Test
    @EnabledIfEnvironmentVariable(named = "MARKFLOW_CONTROLLED_JCEF_SYNC_COMPARISON", matches = "1")
    fun comparesAsyncAndSyncRemoteNativeClose() = runControlled(2, "sync_compare_2")

    // This comparison is a separate, 50-cycle, DOM-query-only diagnostic. It
    // does not replace the original stress_50 or certify 89-case acceptance.
    @Test
    @EnabledIfEnvironmentVariable(named = "MARKFLOW_CONTROLLED_JCEF_DOM_RESOURCE_STRESS_50", matches = "1")
    fun fiftyTestOwnedJcefDomQueriesCloseNatively() = runControlled(50, "stress_dom_50")

    /**
     * #353 one real reviewed-corpus fixture: SOURCE-BOUND paired evidence only.
     * Not 89-case acceptance; does not alter existing 2/50-stress behavior.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "MARKFLOW_CONTROLLED_JCEF_CORPUS_ONE", matches = "1")
    fun capturesOneCorpusPairWithControlledNativeLifecycle() {
        val root = Path.of("").toAbsolutePath().normalize()
        val caseId = "md-atx-headings-h1-h6"
        val filename = "$caseId.md"
        val row = Files.readAllLines(root.resolve("fixtures/differential-acceptance/corpus.tsv"))
            .drop(1).map { it.split('\t') }.single { it[0] == caseId }
        check(row.size == 12 && row[3] == "supported" && row[6] == "exact-source" &&
            row[8] == "yes") { "Pilot case is no longer a source-identical comparable fixture" }
        val fixture = root.resolve(row[5]).normalize()
        check(fixture.startsWith(root) && Files.isRegularFile(fixture))
        val original = Files.readAllBytes(fixture)
        val source = String(original, StandardCharsets.UTF_8)
        check(source.toByteArray(StandardCharsets.UTF_8).contentEquals(original))
        val anchor = Files.readAllLines(
            root.resolve("fixtures/differential-acceptance/markdown-anchors.tsv"),
        ).drop(1).map { it.split('\t', limit = 3) }.single { it[0] == caseId }
        val line = anchor[1].toInt()
        check(source.split('\n').getOrNull(line) == anchor[2]) {
            "Pilot source-line anchor drifted"
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val version = requireNotNull(System.getProperty("markflow.test.platformVersion"))
        check(version == "2026.2.3")
        val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
        val head = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(root.toFile()).start().let {
                val result = it.inputStream.bufferedReader().readText().trim()
                check(it.waitFor() == 0 && result.matches(Regex("[0-9a-f]{40}")))
                result
            }
        System.getenv("STARTER_SOURCE_SHA")?.takeIf(String::isNotBlank)?.let {
            check(it == head) { "Pilot source and Starter checkout differ" }
        }
        val evidence = root.resolve("build/e2e-evidence/derived-content/jcef-corpus-one")
        Files.createDirectories(evidence)
        Files.writeString(evidence.resolve("source.md"), source)
        Files.writeString(evidence.resolve("run-contract.txt"),
            "schema=markflow-controlled-corpus-pilot/v1\n" +
                "source_head=$head\ncase_id=$caseId\nsource_sha256=$sha\n" +
                "source_line_zero_based=$line\nretries=0\n" +
                "scope=ONE_ACTUAL_CORPUS_PAIR_UNREVIEWED\nfull_differential_acceptance=false\n")
        val sandbox = Files.createTempDirectory("markflow-353-corpus-pilot-")
        val project = sandbox.resolve("project")
        try {
            val base = root.resolve("src/integrationTest/resources/projects/markdown-smoke")
            check(base.toFile().copyRecursively(project.toFile(), overwrite = true))
            val corpusFile = project.resolve(filename)
            check(!Files.exists(corpusFile))
            Files.write(corpusFile, original)
            Starter.newContext(
                testName = "markflow-353-owned-corpus-one-pilot",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project))
                    .useRelease(version),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                // Keep IntelliJ's default global JSQuery pool (0).
                // The bundled MarkdownJCEFHtmlPanel configures 20 slots on its OWN
                // JBCefClient before native browser creation; no global override needed.
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("sun.java2d.uiScale", "1.0")
                addSystemProperty("ide.ui.scale", "1.0")
            }.runIdeWithDriver(runTimeout = 12.minutes).useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val visual = driver.utility(ControlledCorpusVisualRemote::class)
                val reference = driver.utility(ControlledJcefRemote::class)
                // Install the platform table-inlay suppression before creating the native Editor.
                driver.withContext(OnDispatcher.EDT) {
                    visual.prepareEditorChromeBeforeOpen()
                }
                val editor = markFlow.openMarkdown(filename)
                val remote = editor.editor
                var failure: Throwable? = null
                try {
                    markFlow.assertProductionProjectionAttached(editor)
                    check(markFlow.source(editor) == source)
                    val stamp = markFlow.modificationStamp(editor)
                    driver.withContext(OnDispatcher.EDT) { visual.prepare(remote) }
                    fun screenshot(rect: Rectangle, filename: String): Boolean {
                        val robot = Robot()
                        robot.waitForIdle()
                        return ImageIO.write(
                            robot.createScreenCapture(rect), "png",
                            evidence.resolve(filename).toFile(),
                        )
                    }
                    val native = driver.withContext(OnDispatcher.EDT) {
                        Rectangle(
                            visual.contentScreenX(remote), visual.contentScreenY(remote),
                            visual.contentWidth(remote), visual.contentHeight(remote),
                        )
                    }
                    check(native.width > 100 && native.height > 100)
                    check(screenshot(native, "markflow.png"))
                    val journal = evidence.resolve("native-events.tsv")
                    val closeMarker = evidence.resolve("native-close.marker")
                    val opened = driver.withContext(OnDispatcher.EDT) {
                        reference.openCorpusReference(
                            remote, caseId, sha, line, journal.toString(),
                            closeMarker.toString(),
                        )
                    }
                    Files.writeString(evidence.resolve("open.txt"), opened)
                    check(opened.contains("scope=CONTROLLED_CORPUS_EXISTING_NATIVE_EDITOR\n") &&
                        opened.contains("source_sha256=$sha\n") &&
                        opened.contains("callback_failure=NONE\n"))
                    waitFor(
                        message = "Owned corpus native browser created",
                        timeout = 20.seconds,
                        getter = { driver.withContext(OnDispatcher.EDT) {
                            reference.snapshot().contains("native_created=true\n")
                        } },
                        checker = { it },
                    )
                    val created = driver.withContext(OnDispatcher.EDT) { reference.snapshot() }
                    Files.writeString(evidence.resolve("native-created.txt"), created)
                    check(created.lineSequence().single {
                        it.startsWith("cef_native_id=")
                    }.substringAfter('=').toInt() > 0)
                    var probe: Path? = null
                    var requests = 0
                    waitFor(
                        message = "Owned corpus actual rendered DOM text",
                        timeout = 20.seconds,
                        getter = {
                            val current = probe
                            when {
                                current == null -> {
                                    requests++
                                    val next = evidence.resolve("dom-$requests.txt")
                                    driver.withContext(OnDispatcher.EDT) {
                                        reference.requestRenderedText(next.toString())
                                    }
                                    probe = next
                                    false
                                }
                                !Files.exists(current) -> false
                                else -> {
                                    val text = Files.readString(current)
                                    if (text.contains("Heading level 1") &&
                                        text.contains("Paragraph after H6.")) {
                                        Files.writeString(
                                            evidence.resolve("dom-proof.txt"),
                                            "first_heading=true\nlast_paragraph=true\n",
                                        )
                                        true
                                    } else {
                                        probe = null
                                        false
                                    }
                                }
                            }
                        },
                        checker = { it },
                    )
                    val xywh = driver.withContext(OnDispatcher.EDT) {
                        reference.bounds().split(',').map(String::toInt)
                    }
                    check(xywh.size == 4)
                    val referenceRect = Rectangle(xywh[0], xywh[1], xywh[2], xywh[3])
                    val nonblank = MarkFlowStarterDifferentialPreviewSmokeTest()
                    waitFor(
                        message = "Owned corpus genuine bundled reference painted",
                        timeout = 20.seconds,
                        getter = {
                            val ok = screenshot(referenceRect, "intellij-preview.png")
                            check(ok)
                            nonblank.referenceHasVisibleContent(
                                requireNotNull(ImageIO.read(
                                    evidence.resolve("intellij-preview.png").toFile()
                                ))
                            )
                        },
                        checker = { it },
                    )
                    val closedRequest = driver.withContext(OnDispatcher.EDT) { reference.close() }
                    Files.writeString(evidence.resolve("wrapper-disposed.txt"), closedRequest)
                    check(closedRequest.contains("jbcef_disposed=true\n"))
                    waitFor(
                        message = "Owned corpus native onBeforeClose",
                        timeout = 20.seconds,
                        getter = { Files.exists(closeMarker) },
                        checker = { it },
                    )
                    val marker = Files.readString(closeMarker)
                    val cefIdentity = created.lineSequence().single {
                        it.startsWith("cef_identity=")
                    }
                    val browserIdentity = created.lineSequence().single {
                        it.startsWith("browser_identity=")
                    }
                    check(marker.lineSequence().any { it == cefIdentity } &&
                        marker.lineSequence().any { it == browserIdentity } &&
                        marker.lineSequence().any { it == "on_before_close=true" })
                    waitFor(
                        message = "Owned corpus native browser fully closed",
                        timeout = 20.seconds,
                        getter = { driver.withContext(OnDispatcher.EDT) {
                            reference.snapshot().contains("cef_closed=true\n")
                        } },
                        checker = { it },
                    )
                    val finished = driver.withContext(OnDispatcher.EDT) { reference.finish() }
                    Files.writeString(evidence.resolve("native-closed.txt"), finished)
                    check(finished.contains("native_before_close=true\n") &&
                        finished.contains("callback_failure=NONE\n"))
                    check(markFlow.source(editor) == source &&
                        markFlow.modificationStamp(editor) == stamp &&
                        !markFlow.isDirty(editor) &&
                        Files.readAllBytes(corpusFile).contentEquals(original))
                    Files.writeString(evidence.resolve("result.txt"),
                        "result=CAPTURED_UNVERIFIED\ncase_id=$caseId\n" +
                            "native_closed=true\nfull_differential_acceptance=false\n")
                } catch (cause: Throwable) {
                    failure = cause
                    throw cause
                } finally {
                    val cleanupError = runCatching {
                        markFlow.closeMarkdown(remote, filename)
                    }.exceptionOrNull()
                    if (cleanupError != null) {
                        if (failure == null) throw cleanupError
                        failure.addSuppressed(cleanupError)
                    }
                }
            }
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    private fun runControlled(iterations: Int, mode: String) {
        require((iterations == 2 && mode in setOf("preflight_2", "sync_compare_2")) ||
            (iterations == 50 && mode in setOf("stress_50", "stress_dom_50"))) {
            "Unsupported controlled JCEF run contract"
        }
        val domStress = mode == "stress_dom_50"
        val syncComparison = mode == "sync_compare_2"
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
        val evidenceDir = when (mode) {
            "preflight_2" -> "jcef-controlled-preflight"
            "sync_compare_2" -> "jcef-controlled-sync-compare"
            "stress_50" -> "jcef-controlled-stress"
            else -> "jcef-controlled-dom-stress"
        }
        val contractSchema = when (mode) {
            "preflight_2" -> "markflow-controlled-jcef-preflight/v1"
            "sync_compare_2" -> "markflow-controlled-jcef-sync-compare/v1"
            "stress_50" -> "markflow-controlled-jcef-stress/v1"
            else -> "markflow-controlled-jcef-dom-stress/v1"
        }
        val output = root.resolve("build/e2e-evidence/derived-content/$evidenceDir")
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("run-contract.txt"),
            "schema=$contractSchema\n" +
                "source_head=$sourceHead\nide_version=$version\n" +
                "scope=CONTROLLED_PROVIDER_ONLY_NOT_PLATFORM_FILE_EDITOR\n" +
                "mode=$mode\niterations=$iterations\nretries=0\nfull_differential_acceptance=false\n" +
                    (if (domStress) "dom_query_required=50\n" else ""),
        )
        Files.writeString(
            output.resolve("progress.txt"),
            "source_head=$sourceHead\nmode=$mode\nexpected=$iterations\ncompleted=0\n",
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
            var domQueryCompleted = 0
            val observedNativeIds = mutableSetOf<Int>()
            Starter.newContext(
                testName = when (mode) {
                    "preflight_2" -> "markflow-353-controlled-jcef-provider-preflight"
                    "stress_50" -> "markflow-353-controlled-jcef-provider-stress-50"
                    else -> "markflow-353-controlled-jcef-dom-stress-50"
                },
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project))
                    .useRelease(version),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                // Keep IntelliJ's default global JSQuery pool (0).
                // The bundled MarkdownJCEFHtmlPanel configures 20 slots on its OWN
                // JBCefClient before native browser creation; no global override needed.
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
                for (number in 1..iterations) {
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
                        opened.contains("host_ide_glass_pane=true\n") &&
                        opened.contains("host_showing=true\n") &&
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
                    val nativeId = created.lineSequence()
                        .single { it.startsWith("cef_native_id=") }
                        .substringAfter('=').toInt()
                    check(nativeId > 0 && observedNativeIds.add(nativeId)) {
                        "Controlled JCEF reused a native browser ID in iteration $number: $nativeId"
                    }

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

                    if (domStress) {
                        // Native first paint and the existing CefBrowser.getText proof
                        // already passed. Exercise the exact JBCefJSQuery callback path
                        // and bind its result to this browser before requesting close.
                        val outputFile = dest.resolve("jcef-js-query-dom.txt")
                        val identityFile = Path.of(outputFile.toString() + ".identity")
                        driver.withContext(OnDispatcher.EDT) {
                            preview.requestStressDomProbe(outputFile.toString())
                        }
                        waitFor(
                            message = "Test-owned exact JCEF DOM query: iteration $number",
                            timeout = 20.seconds,
                            getter = {
                                Files.isRegularFile(outputFile) && Files.isRegularFile(identityFile)
                            },
                            checker = { it },
                        )
                        val text = Files.readString(outputFile)
                        val identity = Files.readString(identityFile)
                        val expectedCefIdentity = created.lineSequence().single {
                            it.startsWith("cef_identity=")
                        }
                        val expectedWrapperIdentity = created.lineSequence().single {
                            it.startsWith("browser_identity=")
                        }
                        check(text.contains(anchorA) && text.contains(anchorB) &&
                            identity.lineSequence().any { it == expectedCefIdentity } &&
                            identity.lineSequence().any { it == expectedWrapperIdentity } &&
                            identity.contains("probe_name=lifecycle-dom-text\n") &&
                            identity.contains("source_sha256=NOT_APPLICABLE\n") &&
                            identity.contains("query_browser_bound=true\n") &&
                            identity.contains("status=OK\n")
                        ) { "JBCefJSQuery content or exact-browser identity mismatch: $number" }
                        domQueryCompleted++
                    }
                    if (syncComparison) {
                        val before = driver.withContext(OnDispatcher.DEFAULT) {
                            preview.nativeCloseBarrierSnapshot("before-close")
                        }
                        Files.writeString(dest.resolve("barrier-before.txt"), before)
                        check(before.contains("target_client_present=true\n") &&
                            before.contains("target_browser_present=true\n")) {
                            "CEF server does not own the live browser in iteration $number"
                        }
                        if (number == 2) {
                            driver.withContext(OnDispatcher.EDT) {
                                preview.prepareSynchronousNativeClose()
                            }
                            val rpc = driver.withContext(OnDispatcher.DEFAULT) {
                                preview.requestSynchronousNativeClose()
                            }
                            Files.writeString(dest.resolve("synchronous-rpc.txt"), rpc)
                            check(rpc.contains("rpc_returned=true\n"))
                        }
                    }
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
                    val nativeMarker = Files.readString(closeMarker)
                    val expectedCefIdentity = created.lineSequence()
                        .single { it.startsWith("cef_identity=") }
                    val expectedWrapperIdentity = created.lineSequence()
                        .single { it.startsWith("browser_identity=") }
                    check(nativeMarker.lineSequence().any { it == "on_before_close=true" } &&
                        nativeMarker.lineSequence().any { it == expectedCefIdentity } &&
                        nativeMarker.lineSequence().any { it == expectedWrapperIdentity }
                    ) { "Native close marker identity mismatch in iteration $number" }
                    Files.writeString(dest.resolve("native-close-proof.txt"),
                        "native_id=$nativeId\n" + nativeMarker)

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
                    if (syncComparison) {
                        var finalSnapshot: String? = null
                        waitFor(
                            message = "Target CID/BID absent on CEF server: iteration $number",
                            timeout = 20.seconds,
                            getter = {
                                val readback = driver.withContext(OnDispatcher.DEFAULT) {
                                    preview.nativeCloseBarrierSnapshot("after-close")
                                }
                                finalSnapshot = readback
                                readback.contains("target_client_present=false\n") &&
                                    readback.contains("target_browser_present=false\n")
                            },
                            checker = { it },
                        )
                        Files.writeString(dest.resolve("barrier-after.txt"), checkNotNull(finalSnapshot))
                    }
                    val finished = driver.withContext(OnDispatcher.EDT) { preview.finish() }
                    Files.writeString(dest.resolve("native-closed.txt"), finished)
                    check(finished.contains("native_before_close=true\n") &&
                        finished.contains("cef_closed=true\n") &&
                        finished.contains("callback_failure=NONE\n"))
                    check(Files.readAllBytes(fixturePath).contentEquals(original)) {
                        "Owned JCEF renderer mutated the source fixture"
                    }
                    completed++
                    Files.writeString(
                        output.resolve("progress.txt"),
                        "source_head=$sourceHead\nmode=$mode\nexpected=$iterations\ncompleted=$completed\n",
                    )
                }
            }
            check(completed == iterations) { "Controlled JCEF completed $completed/$iterations native lifecycles" }
            check(!domStress || domQueryCompleted == iterations) {
                "Controlled JCEF DOM query completed $domQueryCompleted/$iterations callbacks"
            }
            check(observedNativeIds.size == iterations) { "Native browser IDs were not unique" }
            Files.writeString(
                output.resolve("result.txt"),
                "result=PASS\nscope=CONTROLLED_PROVIDER_ONLY_NOT_PLATFORM_FILE_EDITOR\n" +
                    "mode=$mode\ncompleted=$completed\nfull_differential_acceptance=false\n" +
                        (if (domStress) "dom_query_completed=$domQueryCompleted\n" else "") +
                        (if (syncComparison) "native_close_barrier_proved=2\nsync_rpc_requested=1\n" else ""),
            )
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeVisualAcceptanceE2EBridge",
    plugin = "com.algorist.markflow",
)
private interface ControlledCorpusVisualRemote {
    fun prepareEditorChromeBeforeOpen()
    fun prepare(editor: Editor): String
    fun contentScreenX(editor: Editor): Int
    fun contentScreenY(editor: Editor): Int
    fun contentWidth(editor: Editor): Int
    fun contentHeight(editor: Editor): Int
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeControlledReferencePreviewE2EBridge",
    plugin = "com.algorist.markflow",
)
private interface ControlledJcefRemote {
    fun open(filename: String, journalPath: String, closeMarkerPath: String): String
    fun openCorpusReference(
        editor: Editor, caseId: String, expectedSourceSha256: String, sourceLine: Int,
        journalPath: String, closeMarkerPath: String,
    ): String
    fun snapshot(): String
    fun bounds(): String
    fun requestRenderedText(probePath: String)
    fun requestStressDomProbe(outputPath: String)
    fun close(): String
    fun finish(): String
    fun nativeCloseBarrierSnapshot(phase: String): String
    fun prepareSynchronousNativeClose()
    fun requestSynchronousNativeClose(): String
}
