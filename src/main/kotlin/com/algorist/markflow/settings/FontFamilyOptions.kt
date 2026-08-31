package com.algorist.markflow.settings

/**
 * A single selectable entry in the font-family dropdown.
 *
 * @property value the persisted setting. An empty string denotes the built-in default ("MarkFlow
 *   Default"), which the webview resolves to the active IDE editor font.
 * @property displayName the label shown to the user in the dropdown.
 */
data class FontFamilyOption(
    val value: String,
    val displayName: String,
) {
    companion object {
        /** The built-in MarkFlow default. Persisted as an empty string; the webview uses the IDE font. */
        val DEFAULT = FontFamilyOption(value = "", displayName = "MarkFlow Default")
    }
}

/**
 * Builds and resolves the ordered list of font-family dropdown options.
 *
 * The built-in default is always first, followed by the installed families sorted alphabetically
 * (case-insensitively), de-duplicated, with blank names removed. The logic is pure and takes the
 * installed families as input so it can be unit-tested without depending on the fonts present on
 * the host machine.
 */
object FontFamilyOptions {

    /** Builds the dropdown options from the font families installed on the host machine. */
    fun build(installedFamilies: List<String>): List<FontFamilyOption> {
        val options = mutableListOf(FontFamilyOption.DEFAULT)
        options += installedFamilies
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { FontFamilyOption(value = it, displayName = it) }
        return options
    }

    /**
     * Resolves a persisted family name to a dropdown option. An empty persisted value, or a value
     * whose family is not installed (e.g. settings synced from another machine), falls back to the
     * built-in default so an unavailable font never corrupts the selection.
     */
    fun resolve(options: List<FontFamilyOption>, persisted: String): FontFamilyOption {
        val trimmed = persisted.trim()
        return options.firstOrNull { it.value == trimmed } ?: FontFamilyOption.DEFAULT
    }
}
