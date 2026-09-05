import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.JavaForkOptions

import org.apache.tools.ant.taskdefs.condition.Os
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("java") // Java support.
    alias(libs.plugins.kotlin) // Kotlin support.
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform plugin support.
    alias(libs.plugins.changelog) // Changelog automation.
    alias(libs.plugins.qodana) // Qodana static analysis.
    alias(libs.plugins.kover) // Code coverage reporting.
}

group = providers.gradleProperty("pluginGroup").get()

val buildTimestampVersion: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd.HHmmss"))
val resolvedPluginVersion: String = providers.gradleProperty("buildVersion").orNull ?: buildTimestampVersion
version = resolvedPluginVersion

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(25)
}

// Declare repositories used to resolve project dependencies.
repositories {
    mavenCentral()

    // Add IntelliJ Platform repositories.
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure dependency coordinates.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

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
    }
}

// Configure IntelliJ Platform plugin metadata and publishing settings.
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.provider { resolvedPluginVersion }

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
        changeNotes = providers.provider { resolvedPluginVersion }.map { pluginVersion ->
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
        // Timestamp versions do not encode channels; override with -PreleaseChannel when needed.
        channels = providers.gradleProperty("releaseChannel").map { listOf(it) }.orElse(listOf("default"))
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure changelog generation.
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
    // Versions are `yy.MM.dd.HHmmss` timestamps, so the default SemVer header parser rejects patched entries.
    headerParserRegex = """(\d+\.\d+\.\d+\.\d+)""".toRegex()
}

// Configure Kover coverage reporting.
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// Build pipeline for the webview frontend.
val isCi = providers.environmentVariable("CI").orNull == "true"
val webviewDir = file("webview")
val webviewOutputDir = file("build/webview")
val npmInstallCommand = if (isCi) "npm ci --no-audit --no-fund" else "npm install --no-audit --no-fund"
val diagnosticsJvmProperty = "markflow.diagnostics"
val jcefTransportProbeOutput = layout.buildDirectory.file("jcef-transport-probe/evidence.json")
val jcefTransportProbeProjectPath = layout.projectDirectory.asFile.absolutePath

val npmInstallWebview by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs webview dependencies"
    workingDir = webviewDir

    inputs.files(
        file("webview/package.json"),
        file("webview/package-lock.json")
    )
    outputs.dir(file("webview/node_modules"))

    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine(
            "cmd",
            "/c",
            npmInstallCommand
        )
    } else {
        commandLine(
            "sh",
            "-c",
            npmInstallCommand
        )
    }
}

val buildWebview by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Vite frontend webview"
    workingDir = webviewDir
    dependsOn(npmInstallWebview)

    inputs.files(
        fileTree("webview/src"),
        file("webview/index.html"),
        file("webview/vite.config.ts"),
        file("webview/tsconfig.json"),
        file("webview/tsconfig.node.json"),
        file("webview/package.json"),
        file("webview/package-lock.json")
    )
    outputs.dir(webviewOutputDir)

    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("cmd", "/c", "npm run build")
    } else {
        commandLine("sh", "-c", "npm run build")
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
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-D$diagnosticsJvmProperty=true",
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }

        register("runIdeForJcefTransportProbe") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Dmarkflow.jcefTransportProbe.output=${jcefTransportProbeOutput.get().asFile.absolutePath}",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(jcefTransportProbeProjectPath)
                }
            }
        }
    }
}

tasks.named("runIdeForUiTests") {
    dependsOn(buildWebview)
}

tasks.named("runIdeForJcefTransportProbe") {
    dependsOn(buildWebview)
}
