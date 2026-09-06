package com.algorist.markflow.runtime

import com.algorist.markflow.browser.SourceNativeWebviewRoutePolicy
import org.cef.network.CefRequest
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browser-level request policy for one source-native JCEF realm.
 *
 * This policy grants no generic same-origin entitlement. It shares the canonical route contract
 * with the loopback HTTP boundary, while independently enforcing browser resource types,
 * navigation state and exact origin/entry identity.
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
            SourceNativeWebviewRoutePolicy.isCanonicalAsset(rawPath) ->
                candidate.rawQuery == null &&
                    resourceType != null &&
                    resourceType in ALLOWED_STATIC_RESOURCE_TYPES
            SourceNativeWebviewRoutePolicy.isCanonicalLocalImage(rawPath) ->
                candidate.rawQuery == null && resourceType == CefRequest.ResourceType.RT_IMAGE
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
        private val ALLOWED_STATIC_RESOURCE_TYPES = setOf(
            CefRequest.ResourceType.RT_SCRIPT,
            CefRequest.ResourceType.RT_STYLESHEET,
            CefRequest.ResourceType.RT_FONT_RESOURCE,
        )

        fun fromEntryUrl(url: String): SourceNativeBrowserRequestPolicy? {
            val uri = parseLoopbackHttp(url) ?: return null
            if (uri.rawPath != SourceNativeWebviewRoutePolicy.ENTRY_PATH ||
                uri.rawQuery.isNullOrBlank() ||
                uri.rawFragment != null
            ) {
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
    }
}
