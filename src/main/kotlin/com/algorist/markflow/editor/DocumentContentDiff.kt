package com.algorist.markflow.editor

internal data class ContentReplacement(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String
)

internal object DocumentContentDiff {
    fun compute(currentContent: CharSequence, nextContent: CharSequence): ContentReplacement? {
        if (currentContent == nextContent) {
            return null
        }

        val currentLength = currentContent.length
        val nextLength = nextContent.length
        val prefixLimit = minOf(currentLength, nextLength)

        var prefixLength = 0
        while (prefixLength < prefixLimit && currentContent[prefixLength] == nextContent[prefixLength]) {
            prefixLength++
        }

        var suffixLength = 0
        val currentRemaining = currentLength - prefixLength
        val nextRemaining = nextLength - prefixLength
        while (
            suffixLength < currentRemaining &&
            suffixLength < nextRemaining &&
            currentContent[currentLength - 1 - suffixLength] == nextContent[nextLength - 1 - suffixLength]
        ) {
            suffixLength++
        }

        val replacementStart = prefixLength
        val replacementEnd = nextLength - suffixLength
        return ContentReplacement(
            startOffset = replacementStart,
            endOffset = currentLength - suffixLength,
            replacement = nextContent.subSequence(replacementStart, replacementEnd).toString()
        )
    }
}
