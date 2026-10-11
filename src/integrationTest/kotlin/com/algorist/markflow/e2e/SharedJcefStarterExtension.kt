package com.algorist.markflow.e2e

import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CI-only candidate (#375). One real pinned IDEA process for two compatible JCEF-enabled
 * acceptance classes. The independent Manifest (intentional FAIL) and no-JCEF Robot tests
 * must NEVER use this session. JUnit class/case identity and per-case assertions are intact.
 *
 * Store ownership is JUnit-root scoped: a partial --tests selection still reliably closes
 * the IDE. A failed test poisons the session, rather than allowing the next test to pass
 * against an untrusted, partially changed editor state.
 */
internal class SharedJcefStarterExtension : BeforeEachCallback, AfterTestExecutionCallback {
    override fun beforeEach(context: ExtensionContext) {
        context.root.getStore(ExtensionContext.Namespace.create(SharedJcefStarterExtension::class.java))
            .getOrComputeIfAbsent(
                "shared-pinned-jcef-2026.2.3",
                { SharedJcefStarterRun },
                ExtensionContext.Store.CloseableResource::class.java
            )
        SharedJcefStarterRun.beginCase()
    }

    override fun afterTestExecution(context: ExtensionContext) {
        SharedJcefStarterRun.endCase(context.executionException.orElse(null))
    }
}

internal object SharedJcefStarterRun : ExtensionContext.Store.CloseableResource {
    private val active = AtomicBoolean(false)
    @Volatile private var poisoned: Throwable? = null
    private val tempRoot = lazy { Files.createTempDirectory("markflow-375-jcef-shared-") }

    val project: Path by lazy {
        val path = tempRoot.value.resolve("project")
        val source = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath().normalize()
        check(source.toFile().copyRecursively(path.toFile(), overwrite = true)) {
            "Pinned fixture copy failed for shared genuine IDEA session"
        }
        check(Files.isRegularFile(path.resolve("PRODUCTION.md")))
        check(Files.isRegularFile(path.resolve("VISUAL-DERIVED.md")))
        path
    }

    private val session = lazy {
        val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
        check(Files.isRegularFile(plugin)) { "Exact packaged Starter plugin unavailable" }
        val platformVersion = requireNotNull(System.getProperty("markflow.test.platformVersion"))
        check(platformVersion == "2026.2.3") { "Shared Starter requires pinned IDEA 2026.2.3" }
        Starter.newContext(
            testName = "markflow-375-jcef-shared-production-preview",
            testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project))
                .useRelease(platformVersion)
        ).apply {
            PluginConfigurator(this).installPluginFromPath(plugin)
        }.applyVMOptionsPatch {
            addSystemProperty("ide.browser.jcef.enabled", true)
            addSystemProperty("ide.browser.jcef.testMode.enabled", true)
            addSystemProperty("idea.trust.all.projects", true)
            addSystemProperty("jb.consents.confirmation.enabled", false)
            // Required by the pre-existing source/geometry-anchored Preview capture.
            addSystemProperty("sun.java2d.uiScale", "1.0")
            addSystemProperty("ide.ui.scale", "1.0")
        }.runIdeWithDriver()
    }

    val driver get() = session.value.driver

    fun beginCase() {
        check(poisoned == null) { "Previous Starter JCEF test failed; shared process is poisoned" }
        check(active.compareAndSet(false, true)) {
            "Concurrent JCEF test invocation would corrupt shared IDE state"
        }
    }

    fun endCase(failure: Throwable?) {
        if (failure != null) poisoned = failure
        active.set(false)
    }

    override fun close() {
        try {
            if (session.isInitialized()) session.value.closeIdeAndWait()
        } finally {
            if (tempRoot.isInitialized()) {
                check(tempRoot.value.toFile().deleteRecursively()) {
                    "Failed to remove disposable shared IDEA fixture"
                }
            }
        }
    }
}
