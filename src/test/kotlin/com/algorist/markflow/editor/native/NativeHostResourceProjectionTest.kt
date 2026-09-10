package com.algorist.markflow.editor.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHostResourceProjectionTest {
    @Test
    fun plansLocalImagesAndHttpLinksWithoutTreatingDefinitionsAsContent() {
        val source = """[inline](https://example.com/docs "Docs")
![image](images/example.png "Image")
[reference][guide]
![reference image][asset]
[shortcut]
![collapsed][]

[guide]: https://example.com/guide "Guide"
[asset]: images/ref.png
[shortcut]: https://example.com/shortcut
[collapsed]: images/collapsed.png
"""
        val projections = plan(source)

        assertEquals(6, projections.size)
        assertEquals(
            listOf(
                NativeHostResourceKind.EXTERNAL_LINK,
                NativeHostResourceKind.LOCAL_IMAGE,
                NativeHostResourceKind.EXTERNAL_LINK,
                NativeHostResourceKind.LOCAL_IMAGE,
                NativeHostResourceKind.EXTERNAL_LINK,
                NativeHostResourceKind.LOCAL_IMAGE,
            ),
            projections.map { it.kind },
        )
        assertEquals(
            listOf(
                "https://example.com/docs",
                "images/example.png",
                "https://example.com/guide",
                "images/ref.png",
                "https://example.com/shortcut",
                "images/collapsed.png",
            ),
            projections.map { it.target },
        )
        assertEquals(3, projections.count { it.activationRange != null })
        assertTrue(projections.none { source.substring(it.sourceRange.startOffset, it.sourceRange.endOffset).contains(": ") })
    }

    @Test
    fun excludesCodeAndRejectsRemoteOrAbsoluteImageTargetsAndUnsafeLinks() {
        val source = """`[code](https://example.com/code)`

```text
![fenced](images/fenced.png)
[also fenced](https://example.com/fenced)
```

![remote](https://example.com/image.png)
![absolute](/tmp/image.png)
![file](file:///tmp/image.png)
[script](javascript:alert(1))
[file-link](file:///tmp/x)
[good](https://example.com/good)
"""
        val projections = plan(source)

        assertEquals(1, projections.size)
        assertEquals(NativeHostResourceKind.EXTERNAL_LINK, projections.single().kind)
        assertEquals("https://example.com/good", projections.single().target)
    }

    @Test
    fun keepsEmptyAltLocalImageButDoesNotInventNavigationActivation() {
        val source = "![](images/empty-alt.png)\n"
        val projection = plan(source).single()

        assertEquals(NativeHostResourceKind.LOCAL_IMAGE, projection.kind)
        assertEquals("images/empty-alt.png", projection.target)
        assertNull(projection.activationRange)
        assertEquals("![](images/empty-alt.png)", source.substring(projection.sourceRange.startOffset, projection.sourceRange.endOffset))
    }

    @Test
    fun malformedAndDegradedPlansAcquireNoHostCapability() {
        val malformed = "[broken](https://example.com\n![broken](images/a.png\n"
        assertTrue(plan(malformed).isEmpty())

        val identity = ProjectionSourceIdentity(1L, "[x](https://example.com)", 2L)
        val degraded = NativeProjectionPlan(
            identity = identity,
            projections = emptyList(),
            status = ProjectionPlanStatus.DEGRADED_TO_SOURCE,
            failureClass = "synthetic",
        )
        assertTrue(NativeHostResourceProjectionPlanner.plan(degraded).isEmpty())
    }

    private fun plan(source: String): List<NativeHostResourceProjection> {
        val base = NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 7L,
                    source = source,
                    configGeneration = 3L,
                )
            )
        )
        assertEquals(ProjectionPlanStatus.READY, base.status)
        return NativeHostResourceProjectionPlanner.plan(base)
    }
}
