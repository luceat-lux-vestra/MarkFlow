pluginManagement {
    repositories {
        // Plugin implementation dependencies also come from Maven Central.
        // Prefer their canonical repository over the Plugin Portal proxy.
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MarkFlow"
