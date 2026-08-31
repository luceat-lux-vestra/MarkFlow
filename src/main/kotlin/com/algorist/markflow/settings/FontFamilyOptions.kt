package com.algorist.markflow.settings

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
 * The IDE default is always first and is displayed using its actual family name. It is followed by
 * the other installed families sorted alphabetically (case-insensitively), de-duplicated, with
 * blank names removed. The logic is pure and takes the installed families and IDE family as input
 * so it can be unit-tested without depending on the fonts present on the host machine.
 */
object FontFamilyOptions {

    /** Builds the dropdown options from the host's installed families and active IDE font. */
    fun build(installedFamilies: List<String>, ideFontFamily: String): List<FontFamilyOption> {
        val defaultFamily = ideFontFamily.trim()
        val options = mutableListOf(FontFamilyOption(value = "", displayName = defaultFamily))
        options += installedFamilies
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals(defaultFamily, ignoreCase = true) }
            .distinct()
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
        return options.firstOrNull { it.value == trimmed }
            ?: options.firstOrNull { it.value.isEmpty() }
            ?: FontFamilyOption(value = "", displayName = "")
    }
}
