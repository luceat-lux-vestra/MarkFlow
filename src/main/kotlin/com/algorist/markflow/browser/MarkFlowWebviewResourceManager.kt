package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowDiagnostics
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

/**
 * Extracts and serves the isolated derived-renderer frontend for the retained JCEF renderer runtime.
 *
 * Source editing no longer uses a browser after #153, so this server deliberately has no document,
 * local-image, source-native, editor-session, navigation-bridge, or mutation endpoints.
 */
internal object MarkFlowWebviewResourceManager {
    private val LOG = Logger.getInstance(MarkFlowWebviewResourceManager::class.java)
    private val lock = Any()
    private val ownerCount = AtomicInteger(0)
    // Partial JAR extraction roots that could not be deleted immediately. Guarded by [lock].
    private val failedExtractionTempRoots = linkedSetOf<Path>()

    @Volatile
    private var failServerStartOnceForDiagnostics = false

    // Guarded by [lock]. Simulates an OS/filesystem refusal so retry cleanup is deterministic in CI.
    private var failTempRootDeleteOnceForDiagnostics = false

    @Volatile
    private var extractedWebviewRoot: Path? = null

    @Volatile
    private var extractedWebviewRootIsTemp = false

    @Volatile
    private var webviewHttpServer: HttpServer? = null

    @Volatile
    private var webviewServerPort: Int? = null

    internal data class Diagnostics(
        val ownerCount: Int,
        val serverRunning: Boolean,
        val extractedRootPresent: Boolean,
        val tempRootPresent: Boolean,
    )

    internal fun diagnosticsSnapshot(): Diagnostics = synchronized(lock) {
        val root = extractedWebviewRoot
        Diagnostics(
            ownerCount = ownerCount.get(),
            serverRunning = webviewHttpServer != null,
            extractedRootPresent = root != null,
            tempRootPresent = (extractedWebviewRootIsTemp && root != null && Files.exists(root)) ||
                failedExtractionTempRoots.any(Files::exists),
        )
    }

    internal fun extractedRootForDiagnostics(): Path? = synchronized(lock) {
        extractedWebviewRoot
    }

    fun acquire(): Int? {
        synchronized(lock) {
            // A zero-owner temporary root can only remain after cleanup failed. Never serve that
            // potentially partial root again: retry deletion first, then extract a fresh copy.
            if (!cleanupRetainedExtractedRootLocked()) {
                LOG.warn("MARKFLOW_UI renderer acquire blocked by retained renderer temp root")
                return null
            }
            // Never create another temporary extraction while a prior failed extraction is still
            // retained. Retry cleanup first so repeated acquisition cannot grow orphan roots.
            if (!cleanupFailedExtractionTempRootsLocked()) {
                LOG.warn("MARKFLOW_UI renderer acquire blocked by retained failed extraction temp root")
                return null
            }
            val extractedRoot = ensureExtractedWebviewRootLocked() ?: return null
            val port = ensureWebviewHttpServerLocked(extractedRoot)
            if (port == null) {
                cleanupUnownedResourcesLocked()
                return null
            }
            ownerCount.incrementAndGet()
            return port
        }
    }

    fun release() {
        synchronized(lock) {
            if (ownerCount.get() > 0) {
                ownerCount.decrementAndGet()
            }
            if (ownerCount.get() > 0) {
                return
            }
            cleanupUnownedResourcesLocked()
        }
    }

    internal fun failNextServerStartForDiagnostics() {
        synchronized(lock) {
            check(ownerCount.get() == 0) { "renderer resource diagnostics require zero owners" }
            check(webviewHttpServer == null) { "renderer resource diagnostics require no running server" }
            failServerStartOnceForDiagnostics = true
        }
    }

    internal fun failNextTempRootDeleteForDiagnostics() {
        synchronized(lock) {
            check(ownerCount.get() > 0) { "renderer temp-root cleanup diagnostics require an owner" }
            check(extractedWebviewRootIsTemp) { "renderer temp-root cleanup diagnostics require JAR extraction" }
            check(extractedWebviewRoot != null) { "renderer temp-root cleanup diagnostics require an extracted root" }
            check(!failTempRootDeleteOnceForDiagnostics) { "renderer temp-root cleanup failure already armed" }
            failTempRootDeleteOnceForDiagnostics = true
        }
    }

    private fun cleanupUnownedResourcesLocked() {
        check(ownerCount.get() == 0) { "cannot cleanup renderer resources while owners remain" }

        webviewHttpServer?.let { server ->
            try {
                server.stop(0)
            } catch (ex: Exception) {
                LOG.warn("MARKFLOW_UI failed to stop renderer resource server: ${ex.message}", ex)
            }
        }
        webviewHttpServer = null
        webviewServerPort = null

        if (extractedWebviewRootIsTemp) {
            val root = extractedWebviewRoot
            if (root != null && !deleteTempRootLocked(root, "renderer temp root")) {
                return
            }
        }
        extractedWebviewRoot = null
        extractedWebviewRootIsTemp = false
        cleanupFailedExtractionTempRootsLocked()
    }

    private fun cleanupRetainedExtractedRootLocked(): Boolean {
        if (ownerCount.get() > 0 || !extractedWebviewRootIsTemp) return true
        val root = extractedWebviewRoot ?: run {
            extractedWebviewRootIsTemp = false
            return true
        }
        if (!deleteTempRootLocked(root, "retained renderer temp root")) {
            return false
        }
        extractedWebviewRoot = null
        extractedWebviewRootIsTemp = false
        return true
    }

    private fun cleanupFailedExtractionTempRootsLocked(): Boolean {
        val iterator = failedExtractionTempRoots.iterator()
        while (iterator.hasNext()) {
            val root = iterator.next()
            if (!Files.exists(root) || deleteTempRootLocked(root, "failed renderer extraction temp root")) {
                iterator.remove()
            }
        }
        return failedExtractionTempRoots.isEmpty()
    }

    private fun deleteTempRootLocked(root: Path, label: String): Boolean {
        if (failTempRootDeleteOnceForDiagnostics) {
            failTempRootDeleteOnceForDiagnostics = false
            LOG.warn("MARKFLOW_UI diagnostic $label deletion failure: $root")
            return false
        }
        runCatching { root.toFile().deleteRecursively() }
            .onFailure { LOG.warn("MARKFLOW_UI failed to delete $label: ${it.message}", it) }
        if (Files.exists(root)) {
            LOG.warn("MARKFLOW_UI $label remained after cleanup: $root")
            return false
        }
        return true
    }

    private fun ensureWebviewHttpServerLocked(root: Path): Int? {
        webviewServerPort?.let { return it }
        if (failServerStartOnceForDiagnostics) {
            failServerStartOnceForDiagnostics = false
            return null
        }

        var server: HttpServer? = null
        return try {
            val createdServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server = createdServer
            createdServer.createContext("/") { exchange ->
                val requestPath = exchange.requestURI?.path.orEmpty()
                if (requestPath.isEmpty()) {
                    sendStatus(exchange, 404)
                    return@createContext
                }
                serveWebviewResource(exchange, root, requestPath)
            }
            createdServer.executor = null
            createdServer.start()
            webviewHttpServer = createdServer
            webviewServerPort = createdServer.address.port
            if (MarkFlowDiagnostics.enabled) {
                LOG.info("MARKFLOW_UI renderer resource server started on 127.0.0.1:${createdServer.address.port}")
            }
            createdServer.address.port
        } catch (ex: Exception) {
            runCatching { server?.stop(0) }
            webviewHttpServer = null
            webviewServerPort = null
            LOG.error("MARKFLOW_UI failed to start renderer resource server: ${ex.message}", ex)
            null
        }
    }

    private fun serveWebviewResource(exchange: HttpExchange, root: Path, requestPath: String) {
        val normalized = if (requestPath == "/") RENDERER_ENTRY_FILE else requestPath.removePrefix("/")
        val target = root.resolve(normalized).normalize()
        if (!target.startsWith(root) || !Files.exists(target) || Files.isDirectory(target)) {
            sendStatus(exchange, 404)
            return
        }
        serveFile(exchange, target)
    }

    private fun serveFile(exchange: HttpExchange, target: Path) {
        try {
            val contentType = Files.probeContentType(target)
                ?: URLConnection.guessContentTypeFromName(target.fileName.toString())
                ?: "application/octet-stream"
            exchange.responseHeaders["Content-Type"] = contentType
            exchange.responseHeaders["Cache-Control"] = "no-cache"
            exchange.responseHeaders["X-Content-Type-Options"] = "nosniff"
            exchange.sendResponseHeaders(200, Files.size(target))
            Files.newInputStream(target).use { input ->
                exchange.responseBody.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ioe: IOException) {
            LOG.warn("MARKFLOW_UI renderer resource read failed for $target: ${ioe.message}")
            try {
                sendStatus(exchange, 500)
            } catch (_: IOException) {
                exchange.close()
            }
        }
    }

    private fun sendStatus(exchange: HttpExchange, status: Int) {
        exchange.use {
            it.sendResponseHeaders(status, -1)
        }
    }

    private fun ensureExtractedWebviewRootLocked(): Path? {
        extractedWebviewRoot?.let { return it }

        val resource = MarkFlowWebviewResourceManager::class.java.classLoader.getResource(RENDERER_ENTRY_RESOURCE)
        if (resource == null) {
            LOG.error("MARKFLOW_UI renderer resource not found: $RENDERER_ENTRY_RESOURCE")
            return null
        }

        if (resource.protocol == "file") {
            return try {
                val entryPath = Path.of(resource.toURI())
                val root = entryPath.parent ?: return null
                extractedWebviewRoot = root
                extractedWebviewRootIsTemp = false
                root
            } catch (ex: Exception) {
                LOG.error("MARKFLOW_UI failed to resolve renderer resource: ${ex.message}", ex)
                null
            }
        }

        if (resource.protocol != "jar") {
            LOG.error("MARKFLOW_UI unsupported renderer resource protocol: ${resource.protocol}")
            return null
        }

        val pluginJarPath = resolveJarFilePath(resource)
        if (pluginJarPath == null) {
            LOG.error("MARKFLOW_UI failed to resolve jar path for renderer resource: $resource")
            return null
        }

        var tempRoot: Path? = null
        return try {
            val root = Files.createTempDirectory("markflow-renderer-")
            tempRoot = root
            JarFile(pluginJarPath.toFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith("webview/")) continue

                    val target = root.resolve(entry.name.removePrefix("webview/"))
                    target.parent?.let(Files::createDirectories)
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            extractedWebviewRoot = root
            extractedWebviewRootIsTemp = true
            root
        } catch (ex: Exception) {
            tempRoot?.let { root ->
                if (!deleteTempRootLocked(root, "failed renderer extraction temp root")) {
                    failedExtractionTempRoots.add(root)
                }
            }
            LOG.error("MARKFLOW_UI failed to extract renderer resources: ${ex.message}", ex)
            null
        }
    }

    private fun resolveJarFilePath(resource: URL): Path? {
        return try {
            val connection = resource.openConnection()
            val jarFileUrl = (connection as? JarURLConnection)?.jarFileURL
            if (jarFileUrl != null) {
                return Path.of(jarFileUrl.toURI())
            }

            val externalForm = resource.toExternalForm()
            val separator = externalForm.indexOf("!/")
            if (!externalForm.startsWith("jar:") || separator <= "jar:".length) {
                return null
            }

            Path.of(URI.create(externalForm.substring("jar:".length, separator)))
        } catch (ex: Exception) {
            if (MarkFlowDiagnostics.enabled) {
                LOG.debug("MARKFLOW_UI failed to resolve renderer jar path: ${ex.message}")
            }
            null
        }
    }

    private const val RENDERER_ENTRY_RESOURCE = "webview/derived-renderer.html"
    private const val RENDERER_ENTRY_FILE = "derived-renderer.html"
}
