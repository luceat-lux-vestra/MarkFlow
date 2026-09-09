package com.algorist.markflow.trust

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO

class NativeLocalImageResolverTest {
    private lateinit var root: Path
    private lateinit var document: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("markflow-native-local-image-")
        document = root.resolve("document.md")
        Files.writeString(document, "![image](images/example.png)\n")
        Files.createDirectories(root.resolve("images"))
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun resolvesBoundedPngAndJpegIntoInertHostArtifacts() {
        val png = root.resolve("images/example.png")
        val jpeg = root.resolve("images/photo.jpg")
        writeImage(png, "png", 4, 3)
        writeImage(jpeg, "jpg", 5, 2)

        val pngResult = NativeLocalImageResolver.resolve(document, "images/example.png") as NativeLocalImageResult.Success
        assertEquals("image/png", pngResult.artifact.mediaType)
        assertEquals(4, pngResult.artifact.image.width)
        assertEquals(3, pngResult.artifact.image.height)

        val jpegResult = NativeLocalImageResolver.resolve(document, "images/photo.jpg") as NativeLocalImageResult.Success
        assertEquals("image/jpeg", jpegResult.artifact.mediaType)
        assertEquals(5, jpegResult.artifact.image.width)
        assertEquals(2, jpegResult.artifact.image.height)
    }

    @Test
    fun decodesPathOnceWhilePreservingLiteralPlus() {
        writeImage(root.resolve("images/a b.png"), "png", 2, 2)
        writeImage(root.resolve("images/a+b.png"), "png", 2, 2)

        assertTrue(NativeLocalImageResolver.resolve(document, "images/a%20b.png") is NativeLocalImageResult.Success)
        assertTrue(NativeLocalImageResolver.resolve(document, "images/a+b.png") is NativeLocalImageResult.Success)
    }

    @Test
    fun rejectsTraversalEncodedTraversalMixedSeparatorsAndAbsoluteTargets() {
        listOf(
            "../outside.png",
            "images/../outside.png",
            "%2e%2e/outside.png",
            "%252e%252e/outside.png",
            "images%2fexample.png",
            "images%5cexample.png",
            "images\\example.png",
            "/tmp/example.png",
            "C:/temp/example.png",
            "file:///tmp/example.png",
            "images/example.png?x=1",
            "images/example.png#fragment",
        ).forEach { target ->
            assertEquals(target, null, NativeLocalImageResolver.decodeRelativeTarget(target))
        }
    }

    @Test
    fun rejectsSymlinkEscapeAfterRealPathResolution() {
        val outside = Files.createTempDirectory("markflow-native-local-image-outside-")
        try {
            val outsideImage = outside.resolve("outside.png")
            writeImage(outsideImage, "png", 2, 2)
            val link = root.resolve("images/escape.png")
            try {
                Files.createSymbolicLink(link, outsideImage)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: SecurityException) {
                return
            }

            val result = NativeLocalImageResolver.resolve(document, "images/escape.png")
            assertTrue("symlink escape unexpectedly produced an artifact", result is NativeLocalImageResult.Failure)
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingUnknownMediaAndExtensionContentMismatch() {
        assertEquals(
            NativeLocalImageFailureCode.NOT_FOUND,
            (NativeLocalImageResolver.resolve(document, "images/missing.png") as NativeLocalImageResult.Failure).code,
        )

        val svg = root.resolve("images/vector.svg")
        Files.writeString(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
        assertEquals(
            NativeLocalImageFailureCode.UNSUPPORTED_MEDIA,
            (NativeLocalImageResolver.resolve(document, "images/vector.svg") as NativeLocalImageResult.Failure).code,
        )

        val mismatch = root.resolve("images/mismatch.png")
        writeImage(mismatch, "jpg", 2, 2)
        assertEquals(
            NativeLocalImageFailureCode.UNSUPPORTED_MEDIA,
            (NativeLocalImageResolver.resolve(document, "images/mismatch.png") as NativeLocalImageResult.Failure).code,
        )
    }

    @Test
    fun rejectsEncodedSizeAndDimensionBombsBeforeFullDecode() {
        val oversized = root.resolve("images/oversized.png")
        RandomAccessFile(oversized.toFile(), "rw").use { file ->
            file.setLength(NativeLocalImageResolver.MAX_FILE_BYTES + 1)
        }
        assertEquals(
            NativeLocalImageFailureCode.FILE_TOO_LARGE,
            (NativeLocalImageResolver.resolve(document, "images/oversized.png") as NativeLocalImageResult.Failure).code,
        )

        val hugeDimensions = root.resolve("images/huge.png")
        Files.write(hugeDimensions, pngHeader(width = NativeLocalImageResolver.MAX_DIMENSION + 1, height = 1))
        assertEquals(
            NativeLocalImageFailureCode.DIMENSIONS_TOO_LARGE,
            (NativeLocalImageResolver.resolve(document, "images/huge.png") as NativeLocalImageResult.Failure).code,
        )
    }

    @Test
    fun invalidDocumentContextFailsClosed() {
        writeImage(root.resolve("images/example.png"), "png", 2, 2)
        val missingDocument = root.resolve("missing.md")
        assertEquals(
            NativeLocalImageFailureCode.INVALID_DOCUMENT_CONTEXT,
            (NativeLocalImageResolver.resolve(missingDocument, "images/example.png") as NativeLocalImageResult.Failure).code,
        )
    }

    private fun writeImage(path: Path, format: String, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        assertTrue("ImageIO writer unavailable for $format", ImageIO.write(image, format, path.toFile()))
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = ByteArrayOutputStream()
            DataOutputStream(ihdr).use { chunk ->
                chunk.writeInt(width)
                chunk.writeInt(height)
                chunk.writeByte(8)
                chunk.writeByte(2)
                chunk.writeByte(0)
                chunk.writeByte(0)
                chunk.writeByte(0)
            }
            writeChunk(data, "IHDR", ihdr.toByteArray())
        }
        return output.toByteArray()
    }

    private fun writeChunk(output: DataOutputStream, type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(payload.size)
        output.write(typeBytes)
        output.write(payload)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        output.writeInt(crc.value.toInt())
    }
}
