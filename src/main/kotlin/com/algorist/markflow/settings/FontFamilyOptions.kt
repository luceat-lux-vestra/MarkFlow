package com.algorist.markflow.settings

import java.util.Locale

/**
 * A single selectable entry in the font-family dropdown.
 *
 * @property value the persisted setting. An empty string denotes the IDE default, which the
 *   webview resolves to the active IDE editor font.
 * @property displayName the label shown to the user in the dropdown.
 */
data class FontFamilyOption(
    val value: String,
    val displayName: String,
)

/**
 * Builds and resolves the ordered list of font-family dropdown options.
 *
 * The IDE default is always first and is displayed as `IDE Default (<actual family>)`. It is
 * followed by every installed family, including the IDE family itself so that an explicit choice
 * such as `JetBrains Mono` remains distinguishable from inheritance. The installed families are
 * sorted alphabetically (case-insensitively), de-duplicated, and blank names are removed. The
 * logic is pure and takes the installed families and IDE family as input so it can be unit-tested
 * without depending on the fonts present on the host machine.
 */
object FontFamilyOptions {

    /** Builds the dropdown options from the host's installed families and active IDE font. */
    fun build(installedFamilies: List<String>, ideFontFamily: String): List<FontFamilyOption> {
        val defaultFamily = ideFontFamily.trim()
        val defaultDisplayName = if (defaultFamily.isEmpty()) {
            "IDE Default"
        } else {
            "IDE Default ($defaultFamily)"
        }
        val options = mutableListOf(FontFamilyOption(value = "", displayName = defaultDisplayName))
        options += installedFamilies
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { FontFamilyOption(value = it, displayName = it) }
        return options
    }

    /**
     * Resolves a persisted family name to a dropdown option. An empty persisted value, or a value
     * whose family is not installed (e.g. settings synced from another machine), falls back to the
     * IDE default so an unavailable font never corrupts the selection.
     */
    fun resolve(options: List<FontFamilyOption>, persisted: String): FontFamilyOption {
        val trimmed = persisted.trim()
        return options.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }
            ?: options.firstOrNull { it.value.isEmpty() }
            ?: FontFamilyOption(value = "", displayName = "")
    }

    /** Resolves a persisted family to a safe single-family value for the runtime payload. */
    fun resolvePersistedValue(
        installedFamilies: List<String>,
        ideFontFamily: String,
        persisted: String
    ): String {
        return resolve(build(installedFamilies, ideFontFamily), persisted).value
    }
}
