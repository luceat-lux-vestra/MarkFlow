package com.algorist.markflow.e2e

private val STARTER_EAP_BUILD_NUMBER = Regex("""^\d{3}\.\d+\.\d+$""")

internal fun isStarterEapBuildNumber(version: String): Boolean =
    STARTER_EAP_BUILD_NUMBER.matches(version)
