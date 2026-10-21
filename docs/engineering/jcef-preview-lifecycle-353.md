# #353 — bundled Markdown JCEF lifecycle: evidence and local recovery plan

Status: **OPEN / diagnostic; no release-grade 50-run or 89-case acceptance**  
Evidence captured: 2026-10-20–21  
Platform: IntelliJ IDEA Ultimate 2026.2.3, IntelliJ source ref `idea/262.10968.63`  
Diagnostic PR: [#453](https://github.com/luceat-lux-vestra/MarkFlow/pull/453)  
Baseline diagnostic HEAD: `f726cf5b235af0cab37d09f4778ce920bcf062a1`  
Issue: [#353](https://github.com/luceat-lux-vestra/MarkFlow/issues/353)

> Purpose: retain reproducible evidence for a possible future JetBrains report **without
> waiting for upstream** to unblock MarkFlow. This file is an investigation and an
> implementation decision record, **not** proof of a fix and not a release sign-off.
> Do not conflate a green two-iteration workflow with native close of **every**
> browser created during the experiment.

## Observed symptoms — supported by MarkFlow diagnostic runs

- [50-iteration failing run #39418855689](https://github.com/luceat-lux-vestra/MarkFlow/actions/runs/39418855689):
  iterations 1–38 completed, iteration 39 failed `CONTENT_NOT_READY` at the
  original 20-second DOM readiness gate; prior observations showed
  accumulating native browser instances. Failure artifact:
  `starter-jcef-lifecycle-stress-39418855689`.
- [Lifecycle identity run #39518612145](https://github.com/luceat-lux-vestra/MarkFlow/actions/runs/39518612145),
  HEAD `0d75594bf89f67d5740be98edbfe31b09d84d7c3`,
  `direct_split`, `preflight_2`: two `attachHtmlPanel()` calls and two
  native browsers were observed for the second opening. The first browser was
  **disposed before native creation completed**:
  - First panel identity `1194546503`, native CEF ID `12`.
  - First panel removed / diagnostic handler attached at **00:09:33.356 UTC**;
    `onAfterCreated` at **00:09:33.544 UTC** reported
    `jbcef_disposed_at_callback=true`, `cef_closing=true`,
    `cef_closed=false`, `callback_cef_same=true`.
  - No `onBeforeClose` for ID 12 during the 2-run test; it arrived only
    around **00:09:59.619 UTC**, during IDE shutdown.
  - The second native browser, ID `13`, was not disposed at its
    `onAfterCreated` and closed normally.
  - Artifact: `starter-jcef-lifecycle-stress-39518612145`.
- [Early-create counterfactual #39527823754](https://github.com/luceat-lux-vestra/MarkFlow/actions/runs/39527823754),
  baseline HEAD `f726cf5b235af0cab37d09f4778ce920bcf062a1`,
  mode `direct_split_eager_create`, `preflight_2`, workflow SUCCESS:
  - First component added 10:40:15.076 KST, server native creation started
    10:40:15.089, `createImmediately()` requested 10:40:15.127 and returned
    10:40:15.130.
  - At 10:40:15.309 `onAfterCreated` saw `disposed=true`.
  - Final observation around 10:40:23.592: `closed=false`.
  - Native close was observed only at 10:40:41.745 during IDE shutdown.
  - This **disproves a fix at the component-removal hook**, not an early
    builder-time creation strategy; creation was already underway before
    the method call. Artifact: `starter-jcef-lifecycle-stress-39527823754`.

All run IDs and artifact names above have been read back from GitHub Actions.
The fine-grained event timeline is from the captured diagnostic handoff
and should be independently cross-checked against the linked artifacts before
publishing a vendor issue. A `preflight_2` SUCCESS verifies the currently
gated active browser close, **not** all previously replaced browsers.

## Pinned IntelliJ code: what is confirmed

1. [MarkdownPreviewFileEditor.kt](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/plugins/markdown/core/src/org/intellij/plugins/markdown/ui/preview/MarkdownPreviewFileEditor.kt)
   schedules `attachHtmlPanel()` from its main-editor initialization coroutine
   and `componentShown`; a settings listener is another possible trigger.
   `componentShown` checks `panel == null` **before** scheduling on EDT,
   but `attachHtmlPanel()` (lines 284–297) does not recheck when invoked.
   `detachHtmlPanel()` removes the visible component, calls
   `Disposer.dispose(panel)`, and sets its panel field to null.
   Repeated attach is a **real observation** and a credible upstream bug;
   this does **not** prove a missing Java dispose call.
2. [JCEFHtmlPanelProvider.java](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/plugins/markdown/jcef/src/com/intellij/markdown/jcef/preview/JCEFHtmlPanelProvider.java)
   creates `MarkdownJCEFHtmlPanel(project, file)` directly.
3. [MarkdownJCEFHtmlPanel.kt](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/plugins/markdown/jcef/src/com/intellij/markdown/jcef/preview/MarkdownJCEFHtmlPanel.kt)
   extends `JCEFHtmlPanel`, accepts HTML via `setHtml(html, offset, file)`,
   and creates the bundled Preview page/DOM using its usual
   `IncrementalDOMBuilder`, scripts, styles and extensions.
4. [JCEFHtmlPanel.kt](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/ui.jcef/jcef/JCEFHtmlPanel.kt)
   constructs `JBCefBrowser` with a default builder. The bundled Markdown
   constructor has **no injection point** to select
   `JBCefBrowserBuilder.setCreateImmediately(true)`.
5. [JBCefBrowserBase.java](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/ui.jcef/jcef/JBCefBrowserBase.java)
   calls `stopLoad()`, `setCloseAllowed()`, `close(true)` from
   `dispose()` (lines 625–645). `createImmediately()` requests creation
   (lines 383–395); neither this method nor `dispose()` is a synchronous
   `onAfterCreated` / `onBeforeClose` completion barrier.

## Remote JCEF hypothesis (not yet proven on the exact bundled JCEF build)

The separate `JetBrains/jcef` repository has a concrete early-close-loss path:

- [Java RemoteBrowser.close](https://github.com/JetBrains/jcef/blob/bec2404d850877694ee0e4f6661f6a923b1822e9/java/com/jetbrains/cef/remote/browser/RemoteBrowser.java#L431-L443):
  sets `myIsClosing=true` and posts `Browser_Close(myBid)` if `myBid >= 0`.
- [C++ RemoteBrowser::close](https://github.com/JetBrains/jcef/blob/36fb5dce0e43dfb7f2c304bb01bdac7bc8597b50/remote/browser/RemoteBrowser.cpp#L149-L157):
  sets `myIsClosing=true`, calls `CloseBrowser(true)` only when
  `myCefBrowser != nullptr`. No deferred close is visible here.
- [C++ OnAfterCreated](https://github.com/JetBrains/jcef/blob/29403e04a72d4246a1170cd3c4fb512451c08791/remote/handlers/RemoteLifespanHandler.cpp#L58-L74):
  sets the native pointer after creation and relays the callback; no
  pending-close reconciliation is visible in the examined handler.

**Hypothesized order:** creation requested / bid assigned -> Java disposal
posts `Browser_Close` -> C++ handles close while native pointer is null and
marks closing -> native `OnAfterCreated` arrives -> pending close is never
replayed -> native `OnBeforeClose` delayed until IDE shutdown.

**Not yet established:** IDEA 2026.2.3's actual bundled JCEF source revision,
whether the server actually received `Browser_Close` before
`OnAfterCreated`, and whether the above is the causal path for CEF ID 12.
A Java-only state snapshot cannot establish the native RPC ordering.
Vendor report must preserve this fact/hypothesis separation.

### Upstream remediation candidates (deferred, not a release dependency)

- Markdown plugin: make `attachHtmlPanel()` itself idempotent / guarded
  on EDT, including pending initialization and visibility-change callbacks.
- Remote JCEF: preserve `close` requested before native creation and
  perform `CloseBrowser(true)` when `OnAfterCreated` completes; do not
  leave an irreversible `myIsClosing=true` state without a native close.
- For a vendor reproduction attach the three above run artifact links,
  exact IDE/JCEF build IDs, per-browser identity/creation/disposal/callback
  chronology, the CEF server trace containing RPC order, and a minimal
  standalone lifecycle reproduction once established. No upstream report
  has been filed by this investigation.

## MarkFlow-first recovery path — proposed, NOT implemented or validated

**Decision for the next semantic slice:** a *test-only, explicitly owned
reference renderer* using the real bundled Markdown JCEF provider, separate
from the platform-managed split Preview file editor.

The following public/experimental API route exists on the pinned IntelliJ
source:

1. Resolve the same available `MarkdownHtmlPanelProvider` selected by
   `MarkdownSettings`; insist on the bundled JCEF provider and fail closed
   if unavailable. [Provider API](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/plugins/markdown/core/src/org/intellij/plugins/markdown/ui/preview/MarkdownHtmlPanelProvider.java)
2. Create one `MarkdownHtmlPanel` through
   `provider.createHtmlPanel(project, virtualFile)`, attach exactly once to
   a visible **IDE-hosted** test-owned component, and explicitly own its
   `Disposable` and native CEF lifecycle. Do not instantiate
   `MarkdownPreviewFileEditor` in this controlled path.
3. Mirror the platform pipeline: use
   `provider.sourceTextPreprocessor.preprocessText(project, document, file)`
   in a read action and call `panel.setHtml(html, sourceOffset, sourceLine, file)`
   on the proper UI context. The pinned [file editor implementation](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/plugins/markdown/core/src/org/intellij/plugins/markdown/ui/preview/MarkdownPreviewFileEditor.kt#L220-L271)
   provides the corresponding steps. Do **not** send raw Markdown directly
   to `MarkdownJCEFHtmlPanel.setHtml`.
4. Await the exact owned browser's `onAfterCreated` **without blocking EDT**;
   wait for matching DOM text and visible paint; allow disposal only after
   native creation is proven. Await matching `onBeforeClose` before the
   next iteration. Record *every* created identity, not only the last active
   browser; failures remain failures.
5. First prove controlled-panel semantics, layout, width/DPI, script/style
   loading and visible paint are equivalent to the genuine bundled
   reference on a bounded counterexample. Then run a 2-iteration ownership
   preflight, a **single uninterrupted 50-iteration** lifecycle test, and
   finally 89-case differential acceptance. No retry, sharding, timeout
   inflation, reflection mutation, or release-gate relaxation.

**Critical non-equivalence:** direct creation uses the bundled renderer but
bypasses `MarkdownPreviewFileEditor`'s editor/split UI integration. It cannot
retroactively turn the original `editor_only_roundtrip` failure into PASS
and does not assert that JetBrains fixed its own file-editor lifecycle.
Maintain the old reproducer as diagnostic evidence; explicitly distinguish
what is tested by the new owner-controlled path. MarkFlow-owned acceptance
remains authoritative per #353 policy.

**Guardrails before implementation:** confirm usable bundled-plugin compile
API and extension provider selection; assert that a real visible IDE-hosted
container can be provided without spawning the affected platform Preview;
avoid leaking a test-only controller into ordinary MarkFlow product behavior.
If parity cannot be proved, abandon this path rather than silently lowering
the definition of full differential acceptance.

## Already ruled out

- Hidden Preview attachment precondition: `HIDDEN_PANEL_NOT_ATTACHED`.
- Looking for stale wrapper children after detach: wrapper child count was
  zero even when native browser remained.
- Missing `Disposer.dispose(panel)`: disproved by object identity tracking.
- Calling `createImmediately()` on the component removal event: invoked
  after native creation began and did not close the race.

## Release and CI boundary

- Original 50-iteration stress: **not passed**.
- 89-case Full Differential Acceptance: **not completed**.
- #453 plugin verifier `INTERNAL_API_USAGES` on
  `JBCefBrowserBase.isCefBrowserCreated()`: **unresolved**.
- #453 hardening audit `visual-gate` condition verification:
  **unresolved**.
- No code fix, new stress execution, merge, or 1.0 release authorization
  is claimed by this record.

Next checkpoint: implement only the bounded, test-only controlled-provider
candidate and its immediate invariant checks, then STOP for independent CI
classification. Do not bundle implementation, CI repair, merge and
post-merge bookkeeping into one user command.
