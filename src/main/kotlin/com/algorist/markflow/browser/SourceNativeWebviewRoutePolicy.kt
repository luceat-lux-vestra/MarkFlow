package com.algorist.markflow.browser

/**
 * Canonical route contract shared by the source-native loopback HTTP boundary and the JCEF
 * browser-request boundary.
 *
 * This object deliberately reasons about raw URL paths. Encoded aliases are never promoted into
 * canonical source-native routes. [isReservedRequest] additionally accepts the decoded request path
 * from the JDK HTTP server so an encoded alias cannot fall through to the legacy generic static
 * handler after decoding to a protected source-native namespace.
 */
internal object SourceNativeWebviewRoutePolicy {
    const val ENTRY_PATH = "/source-native.html"
    const val ASSET_PREFIX = "/source-native-assets/"
    const val LOCAL_IMAGE_PREFIX = "/__markflow_source_image__/"

    private val SAFE_STATIC_PATH = Regex("^/source-native-assets/[A-Za-z0-9._/-]+$")
    private val LOCAL_IMAGE_PATH = Regex("^/__markflow_source_image__/[A-Za-z0-9_-]{43}/.+$")

    fun isReservedRequest(decodedPath: String, rawPath: String): Boolean =
        decodedPath == ENTRY_PATH ||
            decodedPath.startsWith(ASSET_PREFIX) ||
            decodedPath.startsWith(LOCAL_IMAGE_PREFIX) ||
            rawPath == ENTRY_PATH ||
            rawPath.startsWith(ASSET_PREFIX) ||
            rawPath.startsWith(LOCAL_IMAGE_PREFIX)

    fun isCanonicalEntry(rawPath: String): Boolean = rawPath == ENTRY_PATH

    fun isCanonicalAsset(rawPath: String): Boolean {
        if (!rawPath.startsWith(ASSET_PREFIX) || !SAFE_STATIC_PATH.matches(rawPath)) {
            return false
        }
        return rawPath.split('/').none { it == "." || it == ".." }
    }

    fun isCanonicalLocalImage(rawPath: String): Boolean = LOCAL_IMAGE_PATH.matches(rawPath)
}
