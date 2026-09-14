package com.algorist.markflow.e2e

import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.time.Duration.Companion.minutes

class MarkFlowStarterSmokeTest {
    @Test
    fun launchesNativeFallbackAndKeepsAuthoritativeSourceExact() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val projectPath = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val fixturePath = projectPath.resolve("README.md")
        val expectedSource = Files.readString(fixturePath)
        val platformVersion = Properties().apply {
            Files.newInputStream(Path.of("gradle.properties")).use(::load)
        }.getProperty("platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("platformVersion is required in gradle.properties")
        val targetIde = IdeInfo.IdeaUltimate.copy(version = platformVersion)

        Starter.newContext(
            testName = "markflow-starter-driver-smoke",
            testCase = TestCase(targetIde, LocalProjectInfo(projectPath)),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
        }.applyVMOptionsPatch {
            addSystemProperty("ide.browser.jcef.enabled", false)
            addSystemProperty("ide.browser.jcef.testMode.enabled", true)
            addSystemProperty("idea.trust.all.projects", true)
            addSystemProperty("jb.consents.confirmation.enabled", false)
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            val markFlow = MarkFlowIdeDriver(this)
            val editor = markFlow.openMarkdown("README.md")
            check(markFlow.source(editor) == expectedSource) {
                "opened native editor Document differs from repository fixture bytes"
            }
            check(!markFlow.isDirty(editor)) { "opening the fixture must not dirty the authoritative Document" }
        }
    }
}
