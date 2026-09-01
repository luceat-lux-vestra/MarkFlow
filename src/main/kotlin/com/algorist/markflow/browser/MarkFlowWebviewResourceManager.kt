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
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

internal object MarkFlowWebviewResourceManager {
    private val LOG = Logger.getInstance(MarkFlowWebviewResourceManager::class.java)
    private val lock = Any()
    private val ownerCount = AtomicInteger(0)
    private val localDocumentResources = ConcurrentHashMap<String, LocalDocumentResource>()

    @Volatile
    private var extractedWebviewRoot: Path? = null

    @Volatile
    private var extractedWebviewRootIsTemp = false

    @Volatile
    private var webviewHttpServer: HttpServer? = null

    @Volatile
    private var webviewServerPort: Int? = null

    fun acquire(): Int? {
        synchronized(lock) {
            val extractedRoot = ensureExtractedWebviewRootLocked() ?: return null
            val port = ensureWebviewHttpServerLocked(extractedRoot) ?: return null
            ownerCount.incrementAndGet()
            return port
        }
    }

    fun loadWebviewIndexUrl(): String? {
        val port = ensurePort() ?: return null
        return "http://127.0.0.1:$port/index.html"
    }

    fun registerLocalDocument(documentPath: String): LocalDocumentRegistration? {
        val port = ensurePort() ?: return null
        val resource = createLocalDocumentResource(documentPath) ?: return null
        val token = UUID.randomUUID().toString()
        localDocumentResources[token] = resource
        return LocalDocumentRegistration(
            token = token,
            baseUrl = "http://127.0.0.1:$port/$LOCAL_DOCUMENT_PREFIX/$token/"
        )
    }

    fun unregisterLocalDocument(token: String?) {
        if (token.isNullOrBlank()) return
        localDocumentResources.remove(token)
    }

    fun ensurePort(): Int? {
        synchronized(lock) {
            val extractedRoot = ensureExtractedWebviewRootLocked() ?: return null
            return ensureWebviewHttpServerLocked(extractedRoot)
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

            localDocumentResources.clear()
            webviewHttpServer?.let { server ->
                try {
                    server.stop(0)
                } catch (ex: Exception) {
                    LOG.warn("MARKFLOW_UI failed to stop webview server: ${ex.message}", ex)
                }
            }
            webviewHttpServer = null
            webviewServerPort = null

            if (extractedWebviewRootIsTemp) {
                extractedWebviewRoot?.toFile()?.deleteRecursively()
            }
            extractedWebviewRoot = null
            extractedWebviewRootIsTemp = false
        }
    }

    private fun ensureWebviewHttpServerLocked(root: Path): Int? {
        webviewServerPort?.let { return it }

        return try {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val requestPath = exchange.requestURI?.path.orEmpty()
                if (requestPath.isEmpty()) {
                    sendStatus(exchange, 404)
                    return@createContext
                }

                if (requestPath.startsWith("/$LOCAL_DOCUMENT_PREFIX/")) {
                    serveLocalDocumentResource(exchange, requestPath)
                    return@createContext
                }

                serveWebviewResource(exchange, root, requestPath)
            }
            server.executor = null
            server.start()
            webviewHttpServer = server
            webviewServerPort = server.address.port
            if (MarkFlowDiagnostics.enabled) {
                LOG.info("MARKFLOW_UI webview server started on 127.0.0.1:${server.address.port}")
            }
            server.address.port
        } catch (ex: Exception) {
            LOG.error("MARKFLOW_UI failed to start webview server: ${ex.message}", ex)
            null
        }
    }

    private fun serveWebviewResource(exchange: HttpExchange, root: Path, requestPath: String) {
        val normalized = if (requestPath == "/") "index.html" else requestPath.removePrefix("/")
        val target = root.resolve(normalized).normalize()
        if (!target.startsWith(root) || !Files.exists(target) || Files.isDirectory(target)) {
            sendStatus(exchange, 404)
            return
        }

        serveFile(exchange, target, requireImage = false)
    }

    private fun serveLocalDocumentResource(exchange: HttpExchange, requestPath: String) {
        val prefix = "/$LOCAL_DOCUMENT_PREFIX/"
        val remainder = requestPath.removePrefix(prefix)
        val separator = remainder.indexOf('/')
        if (separator <= 0 || separator == remainder.lastIndex) {
            sendStatus(exchange, 404)
            return
        }

        val token = remainder.substring(0, separator)
        val relativePath = remainder.substring(separator + 1)
        val resource = localDocumentResources[token]
        if (resource == null) {
            sendStatus(exchange, 404)
            return
        }

        val target = resolveLocalResourcePath(resource.documentDirectory, relativePath)
        if (target == null) {
            sendStatus(exchange, 404)
            return
        }

        serveFile(exchange, target, requireImage = true)
    }

    private fun serveFile(exchange: HttpExchange, target: Path, requireImage: Boolean) {
        try {
            val contentType = Files.probeContentType(target)
                ?: URLConnection.guessContentTypeFromName(target.fileName.toString())
                ?: "application/octet-stream"
            if (requireImage && !contentType.startsWith("image/")) {
                sendStatus(exchange, 404)
                return
            }

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
            LOG.warn("MARKFLOW_UI webview server read failed for $target: ${ioe.message}")
            try {
                sendStatus(exchange, 500)
            } catch (_: IOException) {
                exchange.close()
            }
        }
    }

    private fun sendStatus(exchange: HttpExchange, status: Int) {
        try {
            exchange.sendResponseHeaders(status, -1)
        } finally {
            exchange.close()
        }
    }

    private fun createLocalDocumentResource(documentPath: String): LocalDocumentResource? {
        return try {
            val document = Path.of(documentPath).toAbsolutePath().normalize().toRealPath()
            val documentDirectory = document.parent?.toRealPath() ?: return null
            LocalDocumentResource(documentDirectory)
        } catch (ex: Exception) {
            if (MarkFlowDiagnostics.enabled) {
                LOG.debug("MARKFLOW_UI local image root unavailable for $documentPath: ${ex.message}")
            }
            null
        }
    }

    private fun ensureExtractedWebviewRootLocked(): Path? {
        extractedWebviewRoot?.let { return it }

        val resource = MarkFlowSharedBrowserService::class.java.classLoader.getResource(WEBVIEW_ENTRY_RESOURCE)
        if (resource == null) {
            LOG.error("MARKFLOW_UI webview resource not found: $WEBVIEW_ENTRY_RESOURCE")
            return null
        }

        if (resource.protocol == "file") {
            return try {
                val indexPath = Path.of(resource.toURI())
                val root = indexPath.parent ?: return null
                extractedWebviewRoot = root
                extractedWebviewRootIsTemp = false
                root
            } catch (ex: Exception) {
                LOG.error("MARKFLOW_UI failed to resolve file webview resource: ${ex.message}", ex)
                null
            }
        }

        if (resource.protocol != "jar") {
            LOG.error("MARKFLOW_UI unsupported webview resource protocol: ${resource.protocol}")
            return null
        }

        val pluginJarPath = resolveJarFilePath(resource)
        if (pluginJarPath == null) {
            LOG.error("MARKFLOW_UI failed to resolve jar path for webview resource: $resource")
            return null
        }

        return try {
            val tempRoot = Files.createTempDirectory("markflow-webview-")
            JarFile(pluginJarPath.toFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith("webview/")) continue

                    val target = tempRoot.resolve(entry.name.removePrefix("webview/"))
                    target.parent?.let(Files::createDirectories)
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            extractedWebviewRoot = tempRoot
            extractedWebviewRootIsTemp = true
            tempRoot
        } catch (ex: Exception) {
            LOG.error("MARKFLOW_UI failed to extract webview resources: ${ex.message}", ex)
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
                LOG.debug("MARKFLOW_UI failed to resolve jar file path from $resource: ${ex.message}")
            }
            null
        }
    }

    internal fun resolveLocalResourcePath(documentDirectory: Path, relativePath: String): Path? {
        if (relativePath.isBlank()) return null

        return try {
            val relative = Path.of(relativePath)
            if (relative.isAbsolute) return null

            val root = documentDirectory.toAbsolutePath().normalize().toRealPath()
            val candidate = root.resolve(relative).normalize()
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return null
            }

            candidate.toRealPath().takeIf { it.startsWith(root) }
        } catch (_: InvalidPathException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private data class LocalDocumentResource(
        val documentDirectory: Path
    )

    private const val WEBVIEW_ENTRY_RESOURCE = "webview/index.html"
    private const val LOCAL_DOCUMENT_PREFIX = "__markflow_local__"
}

internal data class LocalDocumentRegistration(
    val token: String,
    val baseUrl: String
)
