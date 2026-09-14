package com.algorist.markflow.e2e

import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

class MarkFlowStarterSmokeTest {
    @Test
    fun launchesRealIdeWithMarkFlowAndOpensMarkdownFixture() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val projectPath = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()

        Starter.newContext(
            testName = "markflow-starter-driver-smoke",
            testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath)),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            openFile("README.md")
            waitForIndicators(5.minutes)
            ideFrame { }
        }
    }
}
