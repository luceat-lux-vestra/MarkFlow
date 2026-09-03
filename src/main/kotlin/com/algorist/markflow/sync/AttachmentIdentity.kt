package com.algorist.markflow.sync

/**
 * Opaque host-owned identity for one live document synchronization attachment.
 *
 * This identity is intentionally unrelated to [com.algorist.markflow.document.DocumentRevision],
 * a browser/runtime lifetime, a file, or a transport request. Callers must retain the returned
 * value as an identity only; its string representation has no ordering semantics.
 */
@JvmInline
value class AttachmentId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        /** Creates one validated identity issued by the host attachment owner. */
        fun of(value: String): AttachmentId =
            AttachmentId(requireIdentityValue("AttachmentId", value))
    }
}

/**
 * Opaque identity for correlating one request within one [AttachmentId] lifetime.
 *
 * Request IDs deliberately do not implement ordering. Freshness is determined by the document
 * revision carried in the request, not by comparing request IDs.
 */
@JvmInline
value class RequestId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        /** Creates one validated request identity. */
        fun of(value: String): RequestId =
            RequestId(requireIdentityValue("RequestId", value))
    }
}

private fun requireIdentityValue(typeName: String, value: String): String {
    require(value.isNotBlank()) { "$typeName must not be blank" }
    require(value.none(Character::isISOControl)) { "$typeName must not contain control characters" }
    return value
}
