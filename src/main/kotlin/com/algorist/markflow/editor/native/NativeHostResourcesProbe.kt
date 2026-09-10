package com.algorist.markflow.editor.native

import com.algorist.markflow.trust.NativeLocalImageFailureCode
import com.algorist.markflow.trust.NativeLocalImageResolver
import com.algorist.markflow.trust.NativeLocalImageResult
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.image.BufferedImage
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** Production-independent real-IDE evidence for #147 host-owned resources/navigation. */
internal object NativeHostResourcesProbe {
    const val OUTPUT_PROPERTY = "markflow.nativeHostResourcesProbe.output"
    const val SELECTED_SURFACE = "PLATFORM_TEXT_EDITOR_HOST_RESOURCES"

    private const val PLATFORM_TEXT_EDITOR_TYPE_ID = "text-editor"
    private const val POLL_MILLIS = 100L
    private const val PROBE_TIMEOUT_SECONDS = 90L

    private val started = AtomicBoolean(false)

    fun startIfRequested(project: Project): Boolean {
        val output = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return false
        if (!started.compareAndSet(false, true)) return true
        ApplicationManager.getApplication().invokeLater {
            Runner(Paths.get(output), project).start()
        }
        return true
    }

    private class Runner(
        private val output: Path,
        private val project: Project,
    ) {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val cases = mutableListOf<CaseResult>()
        private val liveEditors = mutableListOf<PlatformEditorHandle>()
        private val liveControllers = mutableListOf<NativePresentationController>()
        private val extraRoots = mutableListOf<Path>()
        private var timeout: ScheduledFuture<*>? = null
        private var finished = false
        private var tempRoot: Path? = null
        private lateinit var fixture: Fixture
        private lateinit var provider: FileEditorProvider
        private lateinit var first: PlatformEditorHandle
        private lateinit var second: PlatformEditorHandle
        private lateinit var firstHost: NativeHostResourcePresentationController
        private lateinit var secondHost: NativeHostResourcePresentationController
        private lateinit var firstController: NativePresentationController
        private lateinit var secondController: NativePresentationController
        private val firstNavigations = mutableListOf<String>()
        private var sourceBefore = ""
        private var stampBefore = 0L
        private var dirtyBefore = false
        private var undoBefore = false

        fun start() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            armTimeout()
            try {
                check(!project.isDefault) { "#147 runtime proof requires a real opened project" }
                check(!project.isDisposed) { "#147 runtime proof project was already disposed" }

                case("jcef-plugin-absent-host-resource-path") {
                    val state = OptionalJcefProbeSupport.read()
                    check(!state.classPresent) { "JCEF class remained visible in #147 no-JCEF proof" }
                    check(!state.supported)
                    "jcefClassPresent=false jcefSupported=false projectDefault=false"
                }

                fixture = createFixture()
                provider = selectPlatformTextProvider(fixture.file)
                first = createPlatformTextEditor(provider, fixture.file)
                second = createPlatformTextEditor(provider, fixture.file)
                first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                second.editor.caretModel.moveToOffset(fixture.bodyOffset)
                sourceBefore = fixture.document.text
                stampBefore = fixture.document.modificationStamp
                dirtyBefore = FileDocumentManager.getInstance().isDocumentUnsaved(fixture.document)
                undoBefore = UndoManager.getInstance(project).isUndoAvailable(first.fileEditor)

                case("host-resource-projection-contract") {
                    val plan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(fixture.document, 1L))
                    check(plan.status == ProjectionPlanStatus.READY)
                    val resources = NativeHostResourceProjectionPlanner.plan(plan)
                    check(resources.count { it.kind == NativeHostResourceKind.LOCAL_IMAGE } == 4)
                    check(resources.count { it.kind == NativeHostResourceKind.EXTERNAL_LINK } == 1)
                    check(resources.none { it.target.startsWith("javascript:") || it.target == "relative.md" })
                    check(resources.all { it.sourceRange.isInside(sourceBefore) })
                    "localImages=4 externalLinks=1 unsafeLinks=0 rangesInBounds=true"
                }

                firstHost = createHost(first, firstNavigations)
                secondHost = createHost(second, mutableListOf())
                firstController = createController(first, firstHost)
                secondController = createController(second, secondHost)
                awaitInitialImages()
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun awaitInitialImages() {
            if (finished) return
            ApplicationManager.getApplication().assertIsDispatchThread()
            val firstEvidence = firstHost.evidenceSnapshot()
            val secondEvidence = secondHost.evidenceSnapshot()
            val terminalFailure = listOf(firstEvidence, secondEvidence).any { evidence ->
                evidence.pendingImageLoads == 0 &&
                    (evidence.decodedImages != 2 || evidence.imageFailures != 2L)
            }
            if (terminalFailure) {
                failAsyncCase(
                    "valid-and-failed-local-image-runtime",
                    "unexpected terminal image evidence first=$firstEvidence second=$secondEvidence",
                )
                return
            }
            val ready = listOf(firstEvidence, secondEvidence).all { evidence ->
                evidence.pendingImageLoads == 0 &&
                    evidence.localImages == 4 &&
                    evidence.decodedImages == 2 &&
                    evidence.ownedImageInlays == 2 &&
                    evidence.ownedImageFolds == 2 &&
                    evidence.imageFailures == 2L
            }
            if (ready) {
                runAfterInitialImages()
                return
            }
            schedule(::awaitInitialImages)
        }

        private fun runAfterInitialImages() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                case("valid-and-failed-local-image-runtime") {
                    val firstEvidence = firstHost.evidenceSnapshot()
                    val secondEvidence = secondHost.evidenceSnapshot()
                    check(firstEvidence.decodedImages == 2 && firstEvidence.ownedImageInlays == 2 && firstEvidence.ownedImageFolds == 2)
                    check(secondEvidence.decodedImages == 2 && secondEvidence.ownedImageInlays == 2 && secondEvidence.ownedImageFolds == 2)
                    check(firstEvidence.imageFailures == 2L && secondEvidence.imageFailures == 2L)
                    checkSourceStable()
                    "pngJpegDecoded=4 missingUnsupportedFailures=4 sourceFallback=true sourceStable=true"
                }

                case("caret-selection-exact-source-reveal") {
                    first.editor.caretModel.moveToOffset(fixture.firstImageOffset)
                    check(firstHost.evidenceSnapshot().ownedImageInlays == 1) {
                        "active image source was not revealed"
                    }
                    check(secondHost.evidenceSnapshot().ownedImageInlays == 2) {
                        "first editor caret reveal leaked into second editor"
                    }
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(firstHost.evidenceSnapshot().ownedImageInlays == 2)

                    first.editor.selectionModel.setSelection(fixture.secondImageStart, fixture.secondImageEnd)
                    check(firstHost.evidenceSnapshot().ownedImageInlays == 1) {
                        "image selection did not reveal exact source"
                    }
                    first.editor.selectionModel.removeSelection()
                    check(firstHost.evidenceSnapshot().ownedImageInlays == 2)
                    checkSourceStable()
                    "caretReveal=true selectionReveal=true restored=true sourceStable=true"
                }

                case("explicit-host-navigation-allow-deny") {
                    check(!firstHost.activateExternalLinkAt(fixture.allowedLinkOffset, explicitUserGesture = false))
                    check(firstNavigations.isEmpty())
                    check(firstHost.activateExternalLinkAt(fixture.allowedLinkOffset, explicitUserGesture = true))
                    check(firstNavigations == listOf("https://example.com/docs"))
                    check(!firstHost.activateExternalLinkAt(fixture.unsafeLinkOffset, explicitUserGesture = true))
                    check(!firstHost.activateExternalLinkAt(fixture.relativeLinkOffset, explicitUserGesture = true))
                    val evidence = firstHost.evidenceSnapshot()
                    check(evidence.navigationAccepted == 1L)
                    check(evidence.navigationRejected == 3L)
                    checkSourceStable()
                    "httpAccepted=1 implicitGestureRejected=true javascriptRejected=true relativeRejected=true sourceStable=true"
                }

                case("split-editor-presentation-isolation") {
                    first.editor.caretModel.moveToOffset(fixture.firstImageOffset)
                    check(firstHost.evidenceSnapshot().ownedImageInlays == 1)
                    check(secondHost.evidenceSnapshot().ownedImageInlays == 2)
                    check(first.editor.document === second.editor.document)
                    first.editor.caretModel.moveToOffset(fixture.bodyOffset)
                    check(firstHost.evidenceSnapshot().ownedImageInlays == 2)
                    "sharedDocument=true independentImagePresentation=true independentReveal=true"
                }

                runHostilePathCases()
                runMoveAndReopenCase()
                startStaleImageCase()
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun runHostilePathCases() {
            case("hostile-local-path-and-media-policy") {
                val hostile = listOf(
                    "../outside.png",
                    "images/../outside.png",
                    "%2e%2e/outside.png",
                    "%252e%252e/outside.png",
                    "images%2fvalid.png",
                    "images%5cvalid.png",
                    "images\\valid.png",
                    "/tmp/absolute.png",
                    "C:/temp/absolute.png",
                    "file:///tmp/absolute.png",
                )
                hostile.forEach { target ->
                    check(NativeLocalImageResolver.resolve(fixture.path, target) is NativeLocalImageResult.Failure) {
                        "hostile target unexpectedly resolved: $target"
                    }
                }

                val projectBase = project.basePath?.let(Paths::get) ?: error("project base unavailable")
                val outside = Files.createTempDirectory(projectBase, ".markflow-native-host-outside-")
                extraRoots.add(outside)
                val outsideImage = outside.resolve("outside.png")
                writeImage(outsideImage, "png", 4, 4)
                val escapeLink = fixture.root.resolve("images/escape.png")
                Files.createSymbolicLink(escapeLink, outsideImage)
                check(NativeLocalImageResolver.resolve(fixture.path, "images/escape.png") is NativeLocalImageResult.Failure)

                val mismatch = fixture.root.resolve("images/mismatch.png")
                writeImage(mismatch, "jpg", 4, 4)
                val mismatchResult = NativeLocalImageResolver.resolve(fixture.path, "images/mismatch.png")
                check(mismatchResult is NativeLocalImageResult.Failure && mismatchResult.code == NativeLocalImageFailureCode.UNSUPPORTED_MEDIA)

                val oversized = fixture.root.resolve("images/oversized.png")
                RandomAccessFile(oversized.toFile(), "rw").use { file ->
                    file.setLength(NativeLocalImageResolver.MAX_FILE_BYTES + 1)
                }
                val oversizedResult = NativeLocalImageResolver.resolve(fixture.path, "images/oversized.png")
                check(oversizedResult is NativeLocalImageResult.Failure && oversizedResult.code == NativeLocalImageFailureCode.FILE_TOO_LARGE)
                checkSourceStable()
                "hostileTargets=${hostile.size} symlinkEscapeRejected=true mediaMismatchRejected=true oversizeRejected=true sourceStable=true"
            }
        }

        private fun runMoveAndReopenCase() {
            case("document-move-and-reopen-context") {
                val moveA = fixture.root.resolve("move-a")
                val moveAImages = moveA.resolve("images")
                Files.createDirectories(moveAImages)
                val source = "![moved](images/moved.png)\n"
                val oldDocument = moveA.resolve("moved.md")
                Files.writeString(oldDocument, source, StandardCharsets.UTF_8)
                writeImage(moveAImages.resolve("moved.png"), "png", 8, 6)
                check(NativeLocalImageResolver.resolve(oldDocument, "images/moved.png") is NativeLocalImageResult.Success)

                val moveB = fixture.root.resolve("move-b")
                Files.move(moveA, moveB)
                val newDocument = moveB.resolve("moved.md")
                check(NativeLocalImageResolver.resolve(oldDocument, "images/moved.png") is NativeLocalImageResult.Failure)
                check(NativeLocalImageResolver.resolve(newDocument, "images/moved.png") is NativeLocalImageResult.Success)

                val reopenedFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(newDocument)
                    ?: error("moved Markdown VirtualFile unavailable")
                val reopenedDocument = FileDocumentManager.getInstance().getDocument(reopenedFile)
                    ?: error("moved Markdown Document unavailable")
                check(reopenedDocument.text == source)
                val reopenedHandle = createPlatformTextEditor(provider, reopenedFile)
                val reopenedHost = NativeHostResourcePresentationController(
                    editor = reopenedHandle.editor,
                    documentPathProvider = { newDocument },
                    externalNavigator = {},
                    richPresentationEnabled = { false },
                )
                val reopenedController = createController(reopenedHandle, reopenedHost)
                val evidence = reopenedHost.evidenceSnapshot()
                check(evidence.localImages == 1 && evidence.accessibilityFallbacks == 1L)
                disposeController(reopenedController)
                disposeEditor(reopenedHandle)
                check(reopenedDocument.text == source)
                "preMoveResolved=true oldContextRevoked=true movedContextResolved=true reopenedNativeOwner=true sourceStable=true"
            }
        }

        private fun startStaleImageCase() {
            val stalePath = fixture.root.resolve("stale.md")
            val staleSource = "![stale](images/valid.png)\n"
            Files.writeString(stalePath, staleSource, StandardCharsets.UTF_8)
            val staleFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(stalePath)
                ?: error("stale image fixture unavailable")
            val staleDocument = FileDocumentManager.getInstance().getDocument(staleFile)
                ?: error("stale image Document unavailable")
            val handle = createPlatformTextEditor(provider, staleFile)
            handle.editor.caretModel.moveToOffset(staleSource.length)
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val calls = AtomicInteger(0)
            val host = NativeHostResourcePresentationController(
                editor = handle.editor,
                documentPathProvider = { stalePath },
                imageResolver = { documentPath, target ->
                    if (calls.incrementAndGet() == 1) {
                        firstStarted.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS)) { "stale resolver release timed out" }
                        NativeLocalImageResolver.resolve(documentPath, target)
                    } else {
                        NativeLocalImageResult.Failure(NativeLocalImageFailureCode.NOT_FOUND)
                    }
                },
                externalNavigator = {},
            )
            val oldPlan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(staleDocument, 1L))
            host.applyPlan(oldPlan)
            check(firstStarted.await(5, TimeUnit.SECONDS)) { "stale resolver did not start" }

            WriteCommandAction.writeCommandAction(project)
                .withName("MarkFlow #147 stale image proof")
                .run<RuntimeException> {
                    staleDocument.insertString(staleDocument.textLength, "\nnew generation\n")
                }
            val freshPlan = NativeMarkdownProjectionPlanner.plan(ProjectionSnapshot.capture(staleDocument, 1L))
            host.applyPlan(freshPlan)
            releaseFirst.countDown()
            awaitStaleImageResult(handle, host)
        }

        private fun awaitStaleImageResult(
            handle: PlatformEditorHandle,
            host: NativeHostResourcePresentationController,
        ) {
            if (finished) return
            ApplicationManager.getApplication().assertIsDispatchThread()
            val evidence = host.evidenceSnapshot()
            if (evidence.pendingImageLoads == 0 && evidence.staleImageResultsRejected >= 1L) {
                try {
                    case("stale-image-result-rejected") {
                        check(evidence.ownedImageInlays == 0 && evidence.ownedImageFolds == 0)
                        check(evidence.staleImageResultsRejected >= 1L)
                        "staleRejected=${evidence.staleImageResultsRejected} staleOverwrite=false freshFailureSourceFallback=true"
                    }
                    Disposer.dispose(host)
                    disposeEditor(handle)
                    runLifecycleAndFinish()
                } catch (failure: Throwable) {
                    recordInternalFailure(failure)
                    finish("INCOMPLETE")
                }
                return
            }
            schedule { awaitStaleImageResult(handle, host) }
        }

        private fun runLifecycleAndFinish() {
            ApplicationManager.getApplication().assertIsDispatchThread()
            try {
                case("repeated-create-dispose-ownership") {
                    repeat(8) {
                        val handle = createPlatformTextEditor(provider, fixture.file)
                        handle.editor.caretModel.moveToOffset(fixture.bodyOffset)
                        val host = NativeHostResourcePresentationController(
                            editor = handle.editor,
                            documentPathProvider = { fixture.path },
                            externalNavigator = {},
                            richPresentationEnabled = { false },
                        )
                        val controller = createController(handle, host)
                        check(host.evidenceSnapshot().localImages == 4)
                        disposeController(controller)
                        check(host.evidenceSnapshot().ownedImageInlays == 0)
                        check(host.evidenceSnapshot().ownedImageFolds == 0)
                        disposeEditor(handle)
                    }
                    checkSourceStable()
                    "loops=8 ownedInlaysAfterDispose=0 ownedFoldsAfterDispose=0 sourceStable=true"
                }

                case("final-source-and-lifecycle-stability") {
                    checkSourceStable()
                    check(FileDocumentManager.getInstance().isDocumentUnsaved(fixture.document) == dirtyBefore)
                    check(UndoManager.getInstance(project).isUndoAvailable(first.fileEditor) == undoBefore)
                    disposeController(firstController)
                    disposeController(secondController)
                    disposeEditor(first)
                    disposeEditor(second)
                    check(liveControllers.isEmpty())
                    check(liveEditors.isEmpty())
                    check(EditorFactory.getInstance().getEditors(fixture.document, project).isEmpty()) {
                        "native editors retained after #147 proof disposal"
                    }
                    "sourceStable=true dirtyStable=true undoStable=true controllers=0 editors=0"
                }

                finish("PASS")
            } catch (failure: Throwable) {
                recordInternalFailure(failure)
                finish("INCOMPLETE")
            }
        }

        private fun createFixture(): Fixture {
            var result: Fixture? = null
            case("authoritative-document-fixture") {
                val projectBase = project.basePath?.let(Paths::get)
                    ?: error("opened #147 proof project has no basePath")
                val root = Files.createTempDirectory(projectBase, ".markflow-native-host-resources-")
                tempRoot = root
                val images = root.resolve("images")
                Files.createDirectories(images)
                writeImage(images.resolve("valid.png"), "png", 320, 180)
                writeImage(images.resolve("photo.jpg"), "jpg", 120, 80)
                Files.writeString(images.resolve("vector.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")

                val source = """# Host resources

![png](images/valid.png)
![jpeg](images/photo.jpg)
![missing](images/missing.png)
![unsupported](images/vector.svg)

[allowed link](https://example.com/docs)
[unsafe link](javascript:alert(1))
[relative link](relative.md)

Plain body line for inactive caret state.
"""
                val path = root.resolve("host-resources-proof.md")
                Files.writeString(path, source, StandardCharsets.UTF_8)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    ?: error("#147 fixture VirtualFile unavailable")
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: error("#147 fixture Document unavailable")
                check(document.text == source)
                check(!FileDocumentManager.getInstance().isDocumentUnsaved(document))

                val firstImageStart = source.indexOf("![png]")
                val secondImageStart = source.indexOf("![jpeg]")
                val secondImageEnd = secondImageStart + "![jpeg](images/photo.jpg)".length
                result = Fixture(
                    root = root,
                    path = path,
                    file = file,
                    document = document,
                    firstImageOffset = firstImageStart + 4,
                    secondImageStart = secondImageStart,
                    secondImageEnd = secondImageEnd,
                    allowedLinkOffset = source.indexOf("allowed link") + 2,
                    unsafeLinkOffset = source.indexOf("unsafe link") + 2,
                    relativeLinkOffset = source.indexOf("relative link") + 2,
                    bodyOffset = source.indexOf("Plain body") + 2,
                )
                "sourceLength=${source.length} png=true jpeg=true missing=true unsupported=true insideProject=true"
            }
            return result ?: error("#147 fixture was not created")
        }

        private fun selectPlatformTextProvider(file: VirtualFile): FileEditorProvider {
            var result: FileEditorProvider? = null
            case("platform-text-editor-selected") {
                val selected = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull { candidate ->
                    candidate.editorTypeId == PLATFORM_TEXT_EDITOR_TYPE_ID && accepts(candidate, file)
                } ?: error("platform text editor provider did not accept #147 Markdown fixture")
                result = selected
                "editorTypeId=${selected.editorTypeId} implementation=${selected.javaClass.name}"
            }
            return result ?: error("provider case did not select platform TextEditor")
        }

        private fun accepts(provider: FileEditorProvider, file: VirtualFile): Boolean = runCatching {
            if (provider.acceptRequiresReadAction()) {
                ReadAction.computeBlocking<Boolean, RuntimeException> { provider.accept(project, file) }
            } else {
                provider.accept(project, file)
            }
        }.getOrDefault(false)

        private fun createPlatformTextEditor(provider: FileEditorProvider, file: VirtualFile): PlatformEditorHandle {
            val fileEditor = provider.createEditor(project, file)
            check(fileEditor is TextEditor) { "selected provider returned ${fileEditor.javaClass.name}, not TextEditor" }
            return PlatformEditorHandle(provider, fileEditor, fileEditor.editor).also(liveEditors::add)
        }

        private fun createHost(
            handle: PlatformEditorHandle,
            navigations: MutableList<String>,
        ): NativeHostResourcePresentationController = NativeHostResourcePresentationController(
            editor = handle.editor,
            documentPathProvider = { fixture.path },
            externalNavigator = { uri -> navigations += uri.toString() },
        )

        private fun createController(
            handle: PlatformEditorHandle,
            host: NativeHostResourcePresentationController,
        ): NativePresentationController = NativePresentationController(
            editor = handle.editor,
            configGeneration = { 1L },
            hostResources = host,
        ).also(liveControllers::add)

        private fun disposeController(controller: NativePresentationController) {
            if (!liveControllers.remove(controller)) return
            Disposer.dispose(controller)
        }

        private fun disposeEditor(handle: PlatformEditorHandle) {
            if (!liveEditors.remove(handle)) return
            handle.provider.disposeEditor(handle.fileEditor)
        }

        private fun writeImage(path: Path, format: String, width: Int, height: Int) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            check(ImageIO.write(image, format, path.toFile())) { "ImageIO writer unavailable for $format" }
        }

        private fun checkSourceStable() {
            check(fixture.document.text == sourceBefore)
            check(fixture.document.modificationStamp == stampBefore)
        }

        private fun case(id: String, block: () -> String) {
            check(cases.none { it.id == id }) { "duplicate #147 evidence case id: $id" }
            val result = try {
                CaseResult(id, "PASS", block())
            } catch (failure: Throwable) {
                CaseResult(id, "FAIL", failureDetail(failure))
            }
            cases += result
            if (result.outcome != "PASS") throw ProbeCaseFailure(id, result.detail)
        }

        private fun failAsyncCase(id: String, detail: String) {
            if (cases.none { it.id == id }) cases += CaseResult(id, "FAIL", detail.take(400))
            finish("INCOMPLETE")
        }

        private fun recordInternalFailure(failure: Throwable) {
            if (cases.none { it.id == "probe-internal-failure" }) {
                cases += CaseResult("probe-internal-failure", "INCOMPLETE", failureDetail(failure))
            }
        }

        private fun schedule(action: () -> Unit) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                { ApplicationManager.getApplication().invokeLater(action) },
                POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun armTimeout() {
            timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    ApplicationManager.getApplication().invokeLater {
                        if (!finished) {
                            if (cases.none { it.id == "probe-timeout" }) {
                                cases += CaseResult("probe-timeout", "INCOMPLETE", "#147 real-IDE probe timed out")
                            }
                            finish("INCOMPLETE")
                        }
                    }
                },
                PROBE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }

        private fun finish(verdict: String) {
            if (finished) return
            finished = true
            timeout?.cancel(false)
            timeout = null
            cleanup()
            try {
                output.parent?.let(Files::createDirectories)
                Files.writeString(
                    output,
                    gson.toJson(
                        Evidence(
                            schemaVersion = 1,
                            selectedSurface = SELECTED_SURFACE,
                            ideBuild = ApplicationInfo.getInstance().build.asString(),
                            verdict = verdict,
                            cases = cases,
                        )
                    ),
                    StandardCharsets.UTF_8,
                )
            } finally {
                ApplicationManager.getApplication().exit(true, true, false)
            }
        }

        private fun cleanup() {
            liveControllers.toList().asReversed().forEach { controller -> runCatching { Disposer.dispose(controller) } }
            liveControllers.clear()
            liveEditors.toList().asReversed().forEach { handle -> runCatching { handle.provider.disposeEditor(handle.fileEditor) } }
            liveEditors.clear()
            extraRoots.asReversed().forEach { root -> runCatching { root.toFile().deleteRecursively() } }
            extraRoots.clear()
            tempRoot?.let { root -> runCatching { root.toFile().deleteRecursively() } }
            tempRoot = null
        }

        private fun failureDetail(failure: Throwable): String = buildString {
            append(failure.javaClass.name)
            failure.message?.takeIf(String::isNotBlank)?.let { message -> append(": ").append(message.take(400)) }
        }
    }

    private data class Fixture(
        val root: Path,
        val path: Path,
        val file: VirtualFile,
        val document: com.intellij.openapi.editor.Document,
        val firstImageOffset: Int,
        val secondImageStart: Int,
        val secondImageEnd: Int,
        val allowedLinkOffset: Int,
        val unsafeLinkOffset: Int,
        val relativeLinkOffset: Int,
        val bodyOffset: Int,
    )

    private data class PlatformEditorHandle(
        val provider: FileEditorProvider,
        val fileEditor: TextEditor,
        val editor: Editor,
    )

    private data class CaseResult(
        val id: String,
        val outcome: String,
        val detail: String,
    )

    private data class Evidence(
        val schemaVersion: Int,
        val selectedSurface: String,
        val ideBuild: String,
        val verdict: String,
        val cases: List<CaseResult>,
    )

    private class ProbeCaseFailure(id: String, detail: String) : RuntimeException("$id: $detail")
}
