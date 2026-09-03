package com.algorist.markflow.document

/**
 * Monotonic logical ordering for accepted mutations of one authoritative IntelliJ document.
 *
 * This is deliberately independent from VFS timestamps, document modification stamps, and
 * transport/session identities. The document-domain stream starts at [INITIAL] and advances
 * once for each actual source-changing [com.intellij.openapi.editor.event.DocumentEvent].
 */
@JvmInline
value class DocumentRevision(val value: Long) : Comparable<DocumentRevision> {
    init {
        require(value >= 0L) { "DocumentRevision must be non-negative" }
    }

    override fun compareTo(other: DocumentRevision): Int = value.compareTo(other.value)

    fun next(): DocumentRevision {
        check(value < Long.MAX_VALUE) { "DocumentRevision overflow" }
        return DocumentRevision(value + 1L)
    }

    /** Lossless wire representation shared by Kotlin and JavaScript. */
    fun toWire(): String = value.toString()

    companion object {
        /** Revision seed: no accepted source mutation has occurred yet. */
        val INITIAL = DocumentRevision(0L)

        /** Parse the canonical non-negative decimal string used on the wire. */
        fun fromWire(value: String): DocumentRevision {
            require(value.matches(Regex("0|[1-9][0-9]*"))) {
                "DocumentRevision must be a canonical non-negative decimal string"
            }
            require(value.toLongOrNull() != null) { "DocumentRevision exceeds Long.MAX_VALUE" }
            return DocumentRevision(value.toLong())
        }
    }
}
