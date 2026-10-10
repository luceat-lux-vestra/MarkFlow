import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.Sync
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.JavaForkOptions

import org.apache.tools.ant.taskdefs.condition.Os
import java.io.File

plugins {
    id("java") // Java support.
    alias(libs.plugins.kotlin) // Kotlin support.
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform plugin support.
    alias(libs.plugins.changelog) // Changelog automation.
    alias(libs.plugins.qodana) // Qodana static analysis.
    alias(libs.plugins.kover) // Code coverage reporting.
}

group = providers.gradleProperty("pluginGroup").get()

val resolvedPluginVersion = providers.gradleProperty("buildVersion").orElse("0.0.0-dev")
version = resolvedPluginVersion.get()

val rendererNodeVersion = providers.fileContents(layout.projectDirectory.file(".nvmrc")).asText.get().trim()
if (!Regex("""26\.\d+\.\d+""").matches(rendererNodeVersion)) {
    throw GradleException(".nvmrc must pin an exact Node 26.x.y version; found '$rendererNodeVersion'")
}
val rendererNodeOs = when {
    Os.isFamily(Os.FAMILY_MAC) -> "darwin"
    Os.isFamily(Os.FAMILY_WINDOWS) -> "win"
    Os.isFamily(Os.FAMILY_UNIX) -> "linux"
    else -> throw GradleException("Unsupported OS for renderer Node provisioning: ${System.getProperty("os.name")}")
}
val rendererNodeArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64" -> "x64"
    else -> throw GradleException("Unsupported architecture for renderer Node provisioning: ${System.getProperty("os.arch")}")
}
val rendererNodeClassifier = "$rendererNodeOs-$rendererNodeArch"
val rendererNodeArchiveExtension = if (Os.isFamily(Os.FAMILY_WINDOWS)) "zip" else "tar.gz"
val rendererNodeArchiveRoot = "node-v$rendererNodeVersion-$rendererNodeClassifier"
val rendererNodeDistribution = configurations.create("rendererNodeDistribution") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(25)
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

// Declare repositories used to resolve project dependencies.
repositories {
    mavenCentral()

    // Resolve the exact Node distribution as a normal Gradle dependency so IDE-launched
    // builds do not depend on the process PATH exposing a system node/npm installation.
    ivy {
        name = "NodeDistributions"
        url = uri("https://nodejs.org/dist/")
        patternLayout {
            artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")
        }
        metadataSources {
            artifact()
        }
        content {
            includeGroup("org.nodejs")
        }
    }

    // Add IntelliJ Platform repositories.
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure dependency coordinates.
dependencies {
    add(
        rendererNodeDistribution.name,
        "org.nodejs:node:$rendererNodeVersion:$rendererNodeClassifier@$rendererNodeArchiveExtension",
    )

    testImplementation(libs.junit)

    integrationTestImplementation(libs.junitJupiter)
    add("integrationTestRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    integrationTestImplementation(libs.coroutines)
    // Plugin packaging deliberately disables the implicit Kotlin stdlib dependency, but Starter runs in a
    // standalone test worker and must align its kotlin-reflect runtime with the Kotlin plugin version.
    integrationTestImplementation(kotlin("stdlib"))
    add("integrationTestRuntimeOnly", "org.jetbrains.teamcity:serviceMessages:2024.07")

    // Configure IntelliJ platform and plugin dependencies.
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Bundled IntelliJ plugins from `gradle.properties`.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Marketplace plugins from `gradle.properties`.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Bundled IntelliJ modules from `gradle.properties`.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
    }
}

// Configure IntelliJ Platform plugin metadata and publishing settings.
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = resolvedPluginVersion

        // Load plugin description from README markers.
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // Keep a local reference for configuration cache compatibility.
        // Render release notes from the versioned changelog entry or Unreleased fallback.
        changeNotes = resolvedPluginVersion.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "263.4732.28")
        }
    }
}

// Configure changelog generation.
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

// Configure Kover coverage reporting.
kover {
    currentProject {
        instrumentation {
            // Starter/Driver is an explicit real-IDE acceptance gate. Keep Kover's onCheck report
            // from pulling the expensive integrationTest task into ordinary check/build execution.
            disabledForTestTasks.add("integrationTest")
        }
    }
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// Build pipeline for the retained renderer frontend.
val isCi = providers.environmentVariable("CI").orNull == "true"
val webviewDir = file("webview")
val webviewOutputDir = file("build/webview")
val npmInstallSubcommand = if (isCi) "ci" else "install"
val rendererNodeInstallDir = layout.buildDirectory.dir("renderer-node")
val rendererNodeHome = rendererNodeInstallDir.map { it.dir(rendererNodeArchiveRoot) }
val rendererNodeExecutable = rendererNodeHome.map {
    if (Os.isFamily(Os.FAMILY_WINDOWS)) it.file("node.exe") else it.file("bin/node")
}
val rendererNpmCli = rendererNodeHome.map {
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        it.file("node_modules/npm/bin/npm-cli.js")
    } else {
        it.file("lib/node_modules/npm/bin/npm-cli.js")
    }
}
val rendererNodeArchive = rendererNodeDistribution.elements.map { files ->
    val resolved = files.map { it.asFile }
    if (resolved.size != 1) {
        throw GradleException("Expected exactly one renderer Node distribution, found $resolved")
    }
    resolved.single()
}
val rendererNodePath = rendererNodeExecutable.map { executable ->
    val inheritedPath = providers.environmentVariable("PATH").orNull.orEmpty()
    listOf(executable.asFile.parentFile.absolutePath, inheritedPath)
        .filter { it.isNotBlank() }
        .joinToString(File.pathSeparator)
}
val diagnosticsJvmProperty = "markflow.diagnostics"
val nativeEditorShellProbeOutput = layout.buildDirectory.file("native-editor-shell-probe/evidence.json")
val nativeEditorShellProbeProjectDir = layout.buildDirectory.dir("native-editor-shell-probe/project")
val nativeProjectionProbeOutput = layout.buildDirectory.file("native-projection-probe/evidence.json")
val nativeProjectionProbeProjectDir = layout.buildDirectory.dir("native-projection-probe/project")
val nativeEditingProbeOutput = layout.buildDirectory.file("native-editing-probe/evidence.json")
val nativeEditingProbeProjectDir = layout.buildDirectory.dir("native-editing-probe/project")
val noJcefNativeEditingProbeOutput = layout.buildDirectory.file("no-jcef-native-editing-probe/evidence.json")
val noJcefNativeEditingProbeProjectDir = layout.buildDirectory.dir("no-jcef-native-editing-probe/project")
val nativeHostResourcesProbeOutput = layout.buildDirectory.file("native-host-resources-probe/evidence.json")
val nativeHostResourcesProbeProjectDir = layout.buildDirectory.dir("native-host-resources-probe/project")

val provisionRendererNode = tasks.register<Sync>("provisionRendererNode") {
    group = "build setup"
    description = "Provisions the exact renderer Node distribution declared by .nvmrc"
    from(
        rendererNodeArchive.map { archive ->
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                zipTree(archive)
            } else {
                tarTree(resources.gzip(archive))
            }
        },
    )
    into(rendererNodeInstallDir)

    doLast {
        val executable = rendererNodeExecutable.get().asFile
        val npmCli = rendererNpmCli.get().asFile
        if (!executable.isFile) {
            throw GradleException("Provisioned renderer Node executable is missing: $executable")
        }
        if (!npmCli.isFile) {
            throw GradleException("Provisioned renderer npm CLI is missing: $npmCli")
        }
        if (!Os.isFamily(Os.FAMILY_WINDOWS) && !executable.canExecute()) {
            executable.setExecutable(true, false)
        }
        if (!Os.isFamily(Os.FAMILY_WINDOWS) && !executable.canExecute()) {
            throw GradleException("Provisioned renderer Node executable is not executable: $executable")
        }
    }
}

val verifyRendererNode = tasks.register<Exec>("verifyRendererNode") {
    group = "verification"
    description = "Verifies the Gradle-provisioned renderer Node version"
    dependsOn(provisionRendererNode)
    doFirst {
        commandLine(
            rendererNodeExecutable.get().asFile.absolutePath,
            "-e",
            "const expected='$rendererNodeVersion'; const actual=process.versions.node; if(actual!==expected){console.error('MarkFlow renderer requires Node '+expected+'; found '+actual); process.exit(1);} console.log('Renderer Node '+actual);",
        )
    }
}

val npmInstallWebview = tasks.register<Exec>("npmInstallWebview") {
    group = "build"
    description = "Installs webview dependencies with the Gradle-provisioned Node/npm toolchain"
    dependsOn(verifyRendererNode)
    workingDir = webviewDir
    inputs.files(file("webview/package.json"), file("webview/package-lock.json"))
    outputs.dir(file("webview/node_modules"))
    doFirst {
        environment("PATH", rendererNodePath.get())
        commandLine(
            rendererNodeExecutable.get().asFile.absolutePath,
            rendererNpmCli.get().asFile.absolutePath,
            npmInstallSubcommand,
            "--no-audit",
            "--no-fund",
        )
    }
}

val buildWebview = tasks.register<Exec>("buildWebview") {
    group = "build"
    description = "Builds the renderer-only Vite frontend"
    workingDir = webviewDir
    dependsOn(npmInstallWebview)
    inputs.files(
        fileTree("webview/src"),
        file("webview/derived-renderer.html"),
        file("webview/vite.derived-renderer.config.ts"),
        file("webview/tsconfig.json"),
        file("webview/package.json"),
        file("webview/package-lock.json"),
    )
    outputs.dir(webviewOutputDir)
    doFirst {
        environment("PATH", rendererNodePath.get())
        commandLine(
            rendererNodeExecutable.get().asFile.absolutePath,
            rendererNpmCli.get().asFile.absolutePath,
            "run",
            "build",
        )
    }
}

val testWebviewSource = tasks.register<Exec>("testWebviewSource") {
    group = "verification"
    description = "Runs renderer source tests with the Gradle-provisioned Node/npm toolchain"
    workingDir = webviewDir
    dependsOn(npmInstallWebview)
    inputs.files(
        fileTree("webview/tests"),
        fileTree("webview/src"),
        file("webview/package.json"),
        file("webview/package-lock.json"),
    )
    doFirst {
        environment("PATH", rendererNodePath.get())
        commandLine(
            rendererNodeExecutable.get().asFile.absolutePath,
            rendererNpmCli.get().asFile.absolutePath,
            "run",
            "test:source",
        )
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildWebview)
    from(webviewOutputDir) {
        into("webview")
    }
}

tasks.named("runIde") {
    dependsOn(buildWebview)
    if (this is JavaForkOptions) {
        jvmArgs("-D$diagnosticsJvmProperty=true")
    }
}

tasks.named("buildPlugin") {
    dependsOn(buildWebview)
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

intellijPlatformTesting {
    val integrationTest by testIdeUi.registering {
        task {
            val integrationTestSourceSet = sourceSets.getByName("integrationTest")
            testClassesDirs = integrationTestSourceSet.output.classesDirs
            classpath = integrationTestSourceSet.runtimeClasspath
            useJUnitPlatform()
            // Starter 263 resolves IntelliJ's MultiRoutingFsPath inside the Gradle test worker.
            // That class implements a JDK-internal sun.nio.fs interface, so the worker needs the
            // same explicit export expected by the IntelliJ platform runtime/tooling.
            jvmArgs("--add-exports=java.base/sun.nio.fs=ALL-UNNAMED")
            systemProperty("markflow.test.platformVersion", providers.gradleProperty("platformVersion").get())
            providers.gradleProperty("differentialCaseIds").orNull?.let { ids ->
                systemProperty("markflow.differential.caseIds", ids)
            }
        }
    }

    runIde {
        register("runIdeForNativeEditorShellProbe") {
            task {
                doFirst {
                    nativeEditorShellProbeProjectDir.get().asFile.mkdirs()
                }
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Dmarkflow.nativeEditorShellProbe.output=${nativeEditorShellProbeOutput.get().asFile.absolutePath}",
                        "-Dide.browser.jcef.enabled=false",
                        "-Dide.browser.jcef.testMode.enabled=true",
                        "-Didea.trust.all.projects=true",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(nativeEditorShellProbeProjectDir.get().asFile.absolutePath)
                }
            }
        }

        register("runIdeForNativeProjectionProbe") {
            task {
                doFirst {
                    nativeProjectionProbeProjectDir.get().asFile.mkdirs()
                }
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Dmarkflow.nativeProjectionProbe.output=${nativeProjectionProbeOutput.get().asFile.absolutePath}",
                        "-Dide.browser.jcef.enabled=false",
                        "-Dide.browser.jcef.testMode.enabled=true",
                        "-Didea.trust.all.projects=true",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(nativeProjectionProbeProjectDir.get().asFile.absolutePath)
                }
            }
        }

        register("runIdeForNativeEditingProbe") {
            task {
                doFirst {
                    nativeEditingProbeProjectDir.get().asFile.mkdirs()
                }
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Dmarkflow.nativeEditingProbe.output=${nativeEditingProbeOutput.get().asFile.absolutePath}",
                        "-Dide.browser.jcef.enabled=false",
                        "-Dide.browser.jcef.testMode.enabled=true",
                        "-Didea.trust.all.projects=true",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(nativeEditingProbeProjectDir.get().asFile.absolutePath)
                }
            }
        }

        register("runIdeForNativeHostResourcesProbe") {
            plugins {
                disablePlugin("com.intellij.modules.jcef")
            }
            task {
                doFirst {
                    nativeHostResourcesProbeProjectDir.get().asFile.mkdirs()
                }
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Dmarkflow.nativeHostResourcesProbe.output=${nativeHostResourcesProbeOutput.get().asFile.absolutePath}",
                        "-Didea.trust.all.projects=true",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(nativeHostResourcesProbeProjectDir.get().asFile.absolutePath)
                }
            }
        }

        register("runIdeForNoJcefNativeEditingProbe") {
            plugins {
                disablePlugin("com.intellij.modules.jcef")
            }
            task {
                doFirst {
                    noJcefNativeEditingProbeProjectDir.get().asFile.mkdirs()
                }
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Dmarkflow.noJcefNativeEditingProbe.output=${noJcefNativeEditingProbeOutput.get().asFile.absolutePath}",
                        "-Didea.trust.all.projects=true",
                        "-Didea.java.project.setup.disabled=true",
                        // This raw runIde task is an integration-test runtime. Match the maintained
                        // Starter 263 contract so proprietary first-run/agreement services do not
                        // treat the disposable sandbox as an interactive production installation.
                        "-Didea.is.integration.test=true",
                        "-Didea.local.statistics.without.report=true",
                        "-Dfeature.usage.event.log.send.on.ide.close=false",
                        "-Didea.updates.url=http://127.0.0.1",
                        // Keep platform errors in idea.log, but prevent EAP diagnostic/update dialogs
                        // from monopolizing the EDT before the dedicated MarkFlow proof executes.
                        "-Didea.fatal.error.notification=disabled",
                        "-Dide.no.platform.update=true",
                        // IntelliJ Starter disables the 263 EAP trace-data-sharing notification in
                        // integration runs. Without this, TraceDataSharingActivity can open a modal
                        // consent surface before the dedicated MarkFlow no-JCEF probe reaches EDT.
                        "-Dide.enable.notification.trace.data.sharing=false",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        // Keep this probe aligned with IntelliJ Starter's disableStartupDialogs()
                        // contract for maintained EAP builds. 263 EAP can otherwise surface
                        // additional AI/Marketplace/Writerside consent dialogs after project open.
                        "-Djb.consents.confirmation.enabled=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.privacy.policy.ai.assistant.text=<!--999.999-->",
                        "-Dmarketplace.eula.reviewed.and.accepted=true",
                        "-Dwriterside.eula.reviewed.and.accepted=true",
                        "-Dide.newUsersOnboarding=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(noJcefNativeEditingProbeProjectDir.get().asFile.absolutePath)
                }
            }
        }
    }
}
