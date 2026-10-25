package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.ProjectManager
import com.intellij.driver.sdk.singleProject
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * #451 production shared lifecycle for the two compatible JCEF-enabled derived-content proofs.
 *
 * Production Wiring and Differential Preview Smoke keep distinct fixture projects and their
 * original proof bodies while sharing one Starter/IDE/JCEF process. Heading Robot remains
 * isolated because it disables JCEF; the #353 Manifest lifecycle also remains independent.
 */
class MarkFlowStarterSharedJcefDerivedContentTest {
    @Test
    fun productionWiringAndPreviewSmokeShareOneJcefIdeAcrossDistinctProjects() {
        val plugin = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: error("path.to.build.plugin is required")
        val version = System.getProperty("markflow.test.platformVersion")
            ?: error("markflow.test.platformVersion is required")
        check(version == "2026.2.3") { "Use the same pinned IDEA platform as the baseline" }

        val sourceProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath().normalize()
        val sandbox = Files.createTempDirectory("markflow-shared-jcef-derived-")
        val productionProject = sandbox.resolve("production-project").toAbsolutePath().normalize()
        val previewProject = sandbox.resolve("preview-project").toAbsolutePath().normalize()
        val evidence = Path.of("build/e2e-evidence/derived-content/shared-jcef")
            .toAbsolutePath().normalize()
        Files.createDirectories(evidence)
        val previewEvidence = Path.of(
            "build/e2e-evidence/derived-content/differential-preview-smoke"
        ).toAbsolutePath().normalize()
        Files.createDirectories(previewEvidence)

        val timer = StarterLifecycleTimingProbe(
            "shared-jcef-production-wiring-then-preview-smoke",
            evidence,
        )
        var phase = "preparation"
        var productionPassed = false
        var previewPassed = false
        var projectSwitches = 0
        var productionMs: Long? = null
        var projectSwitchMs: Long? = null
        var previewMs: Long? = null
        var launchRequests = 0
        try {
            for (project in listOf(productionProject, previewProject)) {
                check(sourceProject.toFile().copyRecursively(project.toFile(), overwrite = true)) {
                    "Cannot prepare fixture project: $project"
                }
            }

            val productionFile = productionProject.resolve("PRODUCTION.md")
            val previewFile = previewProject.resolve("VISUAL-DERIVED.md")
            val productionBytes = Files.readAllBytes(productionFile)
            val previewBytes = Files.readAllBytes(previewFile)
            val productionSource = String(productionBytes, StandardCharsets.UTF_8)
            val previewSource = String(previewBytes, StandardCharsets.UTF_8)
            val anchor = previewSource.indexOf("graph LR")
            check(anchor >= 0 && previewSource.lastIndexOf("graph LR") == anchor)
            val sourceLine = previewSource.take(anchor).count { it == '\n' }

            phase = "ide-start"
            timer.begin()
            launchRequests += 1
            Starter.newContext(
                testName = "markflow-shared-jcef-derived-content",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(productionProject))
                    .useRelease(version)
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                // Preview Smoke requires deterministic 1.0 UI scale. Production Wiring's proof
                // is non-visual; running its exact proof body under this scale is the compatibility check.
                addSystemProperty("sun.java2d.uiScale", "1.0")
                addSystemProperty("ide.ui.scale", "1.0")
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                timer.ideReady()

                val initialProject = singleProject()
                check(Path.of(initialProject.getBasePath()).toAbsolutePath().normalize() == productionProject) {
                    "Starter did not open the isolated Production Wiring project"
                }

                phase = "production-wiring"
                val productionStart = System.nanoTime()
                MarkFlowStarterProductionWiringTest().proveInOpenProject(
                    this,
                    productionFile,
                    productionSource,
                    closeAfterProof = true,
                )
                productionMs = (System.nanoTime() - productionStart) / 1_000_000
                productionPassed = true
                check(Files.readAllBytes(productionFile).contentEquals(productionBytes))

                phase = "project-switch"
                val switchStart = System.nanoTime()
                // The platform otherwise asks "This Window / New Window" when another project
                // is open. Force the backend setting directly through the pinned Remote Driver
                // service surface so the replacement remains non-interactive without relying on
                // newer convenience helpers that are absent from the 2026.2.3 test classpath.
                service<GeneralSettingsRemote>().setConfirmOpenNewProject(OPEN_PROJECT_SAME_WINDOW)
                val opened = utility(ProjectUtilRemote::class).openOrImport(
                    previewProject.toString(),
                    initialProject,
                    false,
                )
                checkNotNull(opened) { "ProjectUtil failed to open the Preview Smoke project" }
                projectSwitches += 1

                val projectManager = service<ProjectManager>()
                waitFor(
                    message = "Production project closes and Preview Smoke becomes the sole active project",
                    timeout = 2.minutes,
                    getter = {
                        projectManager.getOpenProjects().map {
                            Path.of(it.getBasePath()).toAbsolutePath().normalize()
                        }
                    },
                    checker = { openProjects -> openProjects == listOf(previewProject) },
                )
                projectSwitchMs = (System.nanoTime() - switchStart) / 1_000_000
                waitForIndicators(5.minutes)

                phase = "differential-preview-smoke"
                val previewStart = System.nanoTime()
                MarkFlowStarterDifferentialPreviewSmokeTest().proveInOpenProject(
                    this,
                    previewFile,
                    previewBytes,
                    previewSource,
                    sourceLine,
                    previewEvidence,
                    version,
                )
                previewMs = (System.nanoTime() - previewStart) / 1_000_000
                previewPassed = true
                check(Files.readAllBytes(previewFile).contentEquals(previewBytes))
                timer.scenarioComplete()
            }
            phase = "completed"
            timer.success()
        } finally {
            timer.report()
            runCatching {
                val fields = listOf(
                    "schema=markflow-starter-shared-jcef-derived-content/v1",
                    "source_head=" + (System.getenv("STARTER_SOURCE_SHA") ?: "UNKNOWN"),
                    "authoritative=true",
                    "baseline_fixture_project_copies=2",
                    "active_ide_projects=2",
                    "separate_active_projects=true",
                    "project_switches=$projectSwitches",
                    "same_ide_process=true",
                    "ide_launch_requests=$launchRequests",
                    "shared_ui_scale=1.0",
                    "last_phase=$phase",
                    "production_wiring_passed=$productionPassed",
                    "differential_preview_passed=$previewPassed",
                    "production_wiring_ms=" + (productionMs?.toString() ?: "NA"),
                    "project_switch_ms=" + (projectSwitchMs?.toString() ?: "NA"),
                    "differential_preview_ms=" + (previewMs?.toString() ?: "NA"),
                )
                Files.writeString(
                    evidence.resolve("shared-result.txt"),
                    fields.joinToString("\n", postfix = "\n"),
                    StandardCharsets.UTF_8,
                )
                println("MARKFLOW_SHARED_JCEF_DERIVED_CONTENT " + fields.joinToString(" "))
            }.onFailure {
                System.err.println(
                    "MARKFLOW_SHARED_JCEF_DERIVED_CONTENT diagnostic_write_failed=" +
                        it.javaClass.simpleName
                )
            }
            sandbox.toFile().deleteRecursively()
        }
    }
}

@Remote("com.intellij.ide.impl.ProjectUtil")
private interface ProjectUtilRemote {
    fun openOrImport(path: String, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project?
}

@Remote("com.intellij.ide.GeneralSettings")
private interface GeneralSettingsRemote {
    fun setConfirmOpenNewProject(mode: Int)
}

private const val OPEN_PROJECT_SAME_WINDOW = 1
