package com.algorist.markflow.runtime

import org.cef.network.CefRequest
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browser-level request policy for one source-native JCEF realm.
 *
 * This policy grants no generic same-origin entitlement. The one initial main-frame URL is exact,
 * static executable resources live only under the target-owned `source-native-assets/` namespace,
 * and document-local images remain isolated behind the existing source-native capability prefix.
 * Every other browser request shape fails closed before the network loader is allowed to proceed.
 */
internal class SourceNativeBrowserRequestPolicy private constructor(
    private val entryUri: URI,
) {
    private val initialNavigationAccepted = AtomicBoolean(false)
    private val initialNavigationResourceAccepted = AtomicBoolean(false)

    fun allowInitialMainNavigation(url: String, method: String, isRedirect: Boolean): Boolean {
        if (isRedirect || normalizedMethod(method) != "GET") return false
        val candidate = parseLoopbackHttp(url) ?: return false
        if (!sameExactEntry(candidate)) return false
        return initialNavigationAccepted.compareAndSet(false, true)
    }

    fun allowResourceRequest(
        url: String,
        method: String,
        resourceType: CefRequest.ResourceType?,
        isNavigation: Boolean,
        isDownload: Boolean,
    ): Boolean {
        if (isDownload) return false
        val normalizedMethod = normalizedMethod(method)
        if (normalizedMethod != "GET" && normalizedMethod != "HEAD") return false

        val candidate = parseLoopbackHttp(url) ?: return false
        if (!sameOrigin(candidate)) return false

        if (isNavigation) {
            return normalizedMethod == "GET" &&
                resourceType == CefRequest.ResourceType.RT_MAIN_FRAME &&
                sameExactEntry(candidate) &&
                initialNavigationResourceAccepted.compareAndSet(false, true)
        }

        val rawPath = candidate.rawPath ?: return false
        return when {
            isSourceNativeAssetPath(rawPath) ->
                candidate.rawQuery == null &&
                    resourceType != null &&
                    resourceType in ALLOWED_STATIC_RESOURCE_TYPES
            SOURCE_NATIVE_IMAGE_PATH.matches(rawPath) ->
                resourceType == CefRequest.ResourceType.RT_IMAGE
            else -> false
        }
    }

    private fun sameOrigin(candidate: URI): Boolean =
        candidate.scheme.equals(entryUri.scheme, ignoreCase = true) &&
            candidate.host == entryUri.host &&
            candidate.port == entryUri.port &&
            candidate.userInfo == null

    private fun sameExactEntry(candidate: URI): Boolean =
        sameOrigin(candidate) &&
            candidate.rawPath == entryUri.rawPath &&
            candidate.rawQuery == entryUri.rawQuery &&
            candidate.rawFragment == null

    companion object {
        private const val SOURCE_NATIVE_ENTRY_PATH = "/source-native.html"
        private const val SOURCE_NATIVE_ASSET_PREFIX = "/source-native-assets/"
        private val SOURCE_NATIVE_IMAGE_PATH =
            Regex("^/__markflow_source_image__/[A-Za-z0-9_-]{43,128}/.+$")
        private val SAFE_STATIC_PATH = Regex("^/source-native-assets/[A-Za-z0-9._/-]+$")
        private val ALLOWED_STATIC_RESOURCE_TYPES = setOf(
            CefRequest.ResourceType.RT_SCRIPT,
            CefRequest.ResourceType.RT_STYLESHEET,
            CefRequest.ResourceType.RT_FONT_RESOURCE,
        )

        fun fromEntryUrl(url: String): SourceNativeBrowserRequestPolicy? {
            val uri = parseLoopbackHttp(url) ?: return null
            if (uri.rawPath != SOURCE_NATIVE_ENTRY_PATH || uri.rawQuery.isNullOrBlank() || uri.rawFragment != null) {
                return null
            }
            return SourceNativeBrowserRequestPolicy(uri)
        }

        private fun parseLoopbackHttp(url: String): URI? {
            return try {
                val uri = URI(url)
                if (!uri.scheme.equals("http", ignoreCase = true) ||
                    uri.host != "127.0.0.1" ||
                    uri.port !in 1..65535 ||
                    uri.userInfo != null ||
                    uri.rawPath.isNullOrEmpty()
                ) {
                    null
                } else {
                    uri
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun normalizedMethod(method: String): String = method.uppercase(Locale.ROOT)

        private fun isSourceNativeAssetPath(rawPath: String): Boolean {
            if (!rawPath.startsWith(SOURCE_NATIVE_ASSET_PREFIX) || !SAFE_STATIC_PATH.matches(rawPath)) {
                return false
            }
            return rawPath.split('/').none { it == "." || it == ".." }
        }
    }
}
