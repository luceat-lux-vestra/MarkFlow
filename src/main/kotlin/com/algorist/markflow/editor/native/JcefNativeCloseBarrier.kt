package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.jetbrains.cef.remote.browser.RemoteBrowser
import org.cef.browser.CefBrowser
import java.nio.file.Files
import java.nio.file.Path

/**
 * #353 test-only Remote JCEF evidence. A synchronous Browser_Close RPC merely
 * submits native closure; it is NOT a native destruction or memory-free barrier.
 * Call all functions on a Driver DEFAULT thread, NEVER on the IDE EDT.
 */
internal object JcefNativeCloseBarrier {
    data class Target(val bid: Int, val cid: Int, val nativeId: Int)

    fun capture(browser: CefBrowser): Target {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        val remote = browser as? RemoteBrowser ?: error("Expected Remote JCEF browser")
        check(remote.bid >= 0 && remote.cid >= 0 && remote.nativeBrowserIdentifier > 0) {
            "Capture requires a native-created RemoteBrowser"
        }
        return Target(remote.bid, remote.cid, remote.nativeBrowserIdentifier)
    }

    fun requestSynchronousClose(browser: CefBrowser, target: Target): String {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        val remote = browser as? RemoteBrowser ?: error("Expected Remote JCEF browser")
        check(remote.bid == target.bid && remote.cid == target.cid && !remote.isClosing) {
            "Refusing to close an unrelated or already-closing native browser"
        }
        // Do not dispose the Java wrapper here. Its owner still must call
        // Disposer.dispose(panel) on EDT after this command returns.
        remote.owner.server.exec { rpc -> rpc.Browser_Close(target.bid) }
        val state = requireNotNull(
            remote.owner.server.execObj { rpc -> rpc.getServerInfo("state") },
        ) { "CEF server disconnected while requesting native close" }
        return "schema=markflow-jcef-sync-close-rpc/v1\n" +
            "bid=${target.bid}\ncid=${target.cid}\n" +
            "rpc_returned=true\nserver_state=$state\n" +
            "native_close_proved=false\n"
    }

    fun snapshot(browser: CefBrowser, target: Target, phase: String): String {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        check(phase == "before-close" || phase == "after-close")
        val remote = browser as? RemoteBrowser ?: error("Expected Remote JCEF browser")
        check(remote.bid == target.bid && remote.nativeBrowserIdentifier == target.nativeId) {
            "Barrier lost exact native browser identity"
        }
        val details = requireNotNull(
            remote.owner.server.execObj { rpc -> rpc.getServerInfo("state_with_details") },
        ) { "CEF server disconnected during ownership readback" }
        val clients = Regex("""(?m)^\s*cid=(\d+), RemoteBrowsers \[count=(\d+)]""")
            .findAll(details).map { it.groupValues[1].toInt() }.toList()
        val clientPresent = clients.contains(target.cid)
        val browserPresent = Regex(
            """(?m)^\s*(?:Active|Closing), bid=${target.bid}(?:,|\s|$)""",
        ).containsMatchIn(details)
        val factories = details.lineSequence().filter { "Factory<" in it }
            .joinToString("\n")
        return "schema=markflow-jcef-native-close-barrier/v1\n" +
            "phase=$phase\ntarget_cid=${target.cid}\ntarget_bid=${target.bid}\n" +
            "target_native_id=${target.nativeId}\n" +
            "target_client_present=$clientPresent\ntarget_browser_present=$browserPresent\n" +
            "server_client_ids=${clients.sorted().joinToString(",")}\n" +
            "cef_memory=\n${cefServerMemory()}\n" +
            "server_factories=\n$factories\n" +
            "server_details=\n$details\n"
    }

    private fun cefServerMemory(): String {
        if (!Files.isDirectory(Path.of("/proc"))) return "unsupported_platform"
        val processes = ProcessHandle.allProcesses().use { all ->
            all.filter { process ->
                process.info().command().orElse("").substringAfterLast('/') == "cef_server"
            }.toList()
        }
        if (processes.isEmpty()) return "cef_server_not_found"
        return processes.joinToString("\n") { process ->
            val values = runCatching {
                Files.readAllLines(Path.of("/proc", process.pid().toString(), "smaps_rollup"))
                    .mapNotNull { line ->
                        val key = line.substringBefore(':')
                        if (key !in setOf("Rss", "Pss", "Private_Dirty")) null
                        else key to line.substringAfter(':').trim().substringBefore(' ')
                    }.toMap()
            }.getOrDefault(emptyMap())
            "pid=${process.pid()} rss_kib=${values["Rss"] ?: "NA"} " +
                "pss_kib=${values["Pss"] ?: "NA"} " +
                "private_dirty_kib=${values["Private_Dirty"] ?: "NA"}"
        }
    }
}
