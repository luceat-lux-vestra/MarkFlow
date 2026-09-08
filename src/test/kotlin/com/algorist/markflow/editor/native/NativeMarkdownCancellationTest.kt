package com.algorist.markflow.editor.native

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

class NativeMarkdownCancellationTest : BasePlatformTestCase() {
    fun testClipboardAuxiliaryFlavorReadRethrowsProcessCancellation() {
        val cancellingTransferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor

            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor != DataFlavor.stringFlavor) throw UnsupportedFlavorException(flavor)
                throw ProcessCanceledException()
            }
        }

        var propagated: ProcessCanceledException? = null
        try {
            NativeMarkdownClipboard.readMarkdownForPlatformText(
                cancellingTransferable,
                platformText = "plain",
                maxChars = 8,
            )
        } catch (failure: ProcessCanceledException) {
            propagated = failure
        }

        assertNotNull("ProcessCanceledException must propagate from auxiliary flavor reads", propagated)
    }

    fun testMarkdownFlavorDiscoveryFailureFallsBackSafely() {
        val brokenFlavorEnumeration = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> =
                throw IllegalStateException("broken flavor enumeration")

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor

            override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
                DataFlavor.stringFlavor -> "plain"
                else -> throw UnsupportedFlavorException(flavor)
            }
        }

        assertNull(
            NativeMarkdownClipboard.readMarkdownForPlatformText(
                brokenFlavorEnumeration,
                platformText = "plain",
                maxChars = 8,
            ),
        )
    }
}
