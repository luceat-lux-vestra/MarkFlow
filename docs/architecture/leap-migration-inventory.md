# Leap migration inventory and execution map

Status: repository migration authority for #141 under accepted ADR 0001 and ADR 0002; execution status reconciled through #146 target ownership

Original #141 audited base:

- `main`: `c130ceb2648e262414cd0aed9cc463c78d9b27ec`
- tree: `971320e0a9fd77e8cc1bbcc82d25256fc5539a7c`
- commit: `docs(leap): select native-authority projection architecture (#142)`

The original inventory classified repository responsibilities without production migration implementation. Subsequent target Tasks may resolve lower-level choices recorded here; those resolutions are appended/reconciled rather than rewriting the audited base as if it had known the future.

Current execution resolutions relevant to this inventory:

- #139/#141 are completed;
- #143 is completed and selected **`PLATFORM_TEXT_EDITOR_AUGMENTATION`** — normal IntelliJ platform text-editor augmentation, not a MarkFlow-owned replacement `FileEditor` shell;
- #145 is completed and establishes the immutable snapshot/parser/projection-plan/per-editor-controller foundation over that selected shell;
- #146 resolves the target paste/state/representative-rich-edit ownership as platform paste/command/undo/`FileEditorState` semantics plus narrowly scoped MarkFlow payload/local-edit behavior, subject to #146's own exact-final-HEAD and post-main proof gate;
- #153 remains the only production native-editor cutover owner, so the current browser editor/provider/protocol remain temporary until their explicit deletion criteria are met.

## Authority hierarchy

When sources disagree:

1. #78 / `docs/product/leap-capability-fidelity-contract.md` — product/source-fidelity authority;
2. accepted ADR 0001 and ADR 0002 — architecture authority;
3. reconciled responsibility Tracks #79–#84;
4. target-derived Tasks #143–#156, including completed resolution evidence from earlier Tasks;
5. current code/tests/historical docs — evidence only.

`docs/architecture/README.md` is the repository architecture index. `AGENTS.md`, contributor/review instructions, engineering/testing guidance and README must reflect this hierarchy. Historical `plans/*` remain historical context and are not rewritten as target plans merely because they mention current files.

## Classification vocabulary

- `RETAIN` — independently belongs in target with no architectural ownership change required;
- `EXTRACT` — responsibility remains but must be separated from a superseded owner/integration before deletion;
- `REPLACE` — product responsibility remains but current mechanism does not belong in target;
- `DELETE` — responsibility/mechanism is absent from target once its temporary consumer is gone;
- `TEMPORARY` — migration-only, with explicit owner and deletion criterion;
- `UNRESOLVED` — lower-level choice requiring proof; never permission to preserve current mechanism.

Sunk cost, recency, test volume and smaller diff size are not retention arguments. Classification is responsibility-level, never directory-level.

## Accepted target invariants

1. IntelliJ `Document` is the sole mutable live Markdown authority.
2. A native IntelliJ `Editor` edits that same `Document` directly.
3. MarkFlow presentation is derived, source-neutral, disposable and per-editor.
4. No browser/JavaScript/rich-model document participates in editing correctness.
5. Dirty/save/undo/redo and source durability use IntelliJ semantics, not browser flush/debounce/retry.
6. Stale parse/projection/render results apply only to the exact current source/config identity.
7. Unsupported/ambiguous/malformed/renderer-failed content degrades to exact source.
8. Mermaid `11.17.2` and KaTeX `^0.18.5` remain supported engines during editor migration.
9. Local resources and external navigation are host-owned capabilities.
10. Optional renderer/JCEF failure never makes source editing unavailable.
11. Every temporary old-editor mechanism has an explicit owner and deletion criterion.
12. End state has one production editor architecture and one engine per supported renderer.

## Responsibility-level classification

| Current responsibility / mechanism | Classification | Target disposition | Temporary owner / deletion criterion |
| --- | --- | --- | --- |
| #78 product/fidelity contract + hostile/fidelity corpus | `RETAIN` | Product/evidence authority independent of editor implementation | Permanent while capability remains supported |
| `MarkFlowFileSupport` supported-file recognition | `RETAIN` | Host-level recognition | Revisit only by product-scope decision |
| current browser-backed `MarkFlowEditor` | `REPLACE` + `TEMPORARY` | #143 selected normal platform text-editor augmentation over the same `Document` | #153 owns production cutover; #154 deletes old editor after cutover proof |
| `MarkFlowEditorProvider` JCEF-gated takeover / `HIDE_DEFAULT_EDITOR` | `REPLACE` + `TEMPORARY` | #143 rejected target replacement-provider ownership; target augments the platform text editor and editing cannot depend on JCEF | Current provider remains migration-only until #153; #154 removes the superseded provider path |
| browser-shaped `MarkFlowEditorState` (`scrollTop`, JS caret/selection injection) | `EXTRACT` / `REPLACE` + `TEMPORARY` | #146 keeps platform `TextEditor`/opaque `FileEditorState` as target state authority; no new MarkFlow native state DTO | #146 proves target state ownership; #155 removes obsolete persisted/browser shape after cutover/purge |
| `DocumentSession`/registry/custom web revision ownership | `REPLACE` / `DELETE` + `TEMPORARY` | Native `Document` command/write/undo semantics only | Current editor owns until #153; #154 deletes when browser editor is unreachable |
| `SourceRevisionGate` | `DELETE` + `TEMPORARY` | No web source proposal in target; derived work uses exact source/config identity | #154 after #153 |
| `DocumentContentDiff` / whole-content web->Document replacement | `DELETE` + `TEMPORARY` | Native user edits already modify `Document` | #154 after last web-origin edit consumer |
| `sync/AttachmentIdentity`, mutation/ACK/recovery/wire protocol | `DELETE` + `TEMPORARY` | No cross-runtime editable source | #154 after #153 |
| `SourceNativeEditorRuntime`, readiness/edit transport | `DELETE` + `TEMPORARY` | Native platform editor owns editing | #154 after #153 |
| `JcefSourceNativeRuntimeTransport` editor transport/query lifecycle | `DELETE` + `TEMPORARY` | Any retained JCEF is renderer-only behind separate adapter | #154 after #153; renderer-specific adapter belongs #144/#148/#149 if needed |
| shared editor browser lease/pool/recovery/prewarm/idle eviction | `DELETE` + `TEMPORARY` | No browser editor lifecycle | Current runtime until #153; #154 deletes; never repurpose by default |
| `MarkFlowJcefSupport` generic editor availability gate | `EXTRACT` / `REPLACE` + `TEMPORARY` | Narrow optional renderer-runtime capability check only if final renderer needs JCEF | #155 renames/relocates if retained, deletes if no renderer consumer |
| generic webview/loopback static/resource manager | `REPLACE` + `TEMPORARY` | Editor serving deleted; renderer gets only minimum proven execution/assets | #154 deletes editor routes; #155 converges retained renderer assets/toolchain |
| source-native editor request/origin/CSP/navigation containment | `DELETE` + `TEMPORARY` | Editor browser surface disappears | #154 after #153; renderer-specific containment belongs #149/#155 if needed |
| source-native/legacy browser local-image URL/token capability | `REPLACE` + `TEMPORARY` | Host resolver -> bounded inert image artifact | #147 proves target; #154 removes old routes after cutover |
| browser external-navigation bridge | `REPLACE` + `TEMPORARY` | Explicit host navigation action | #147 proves target; #154 removes bridge after cutover |
| legacy Crepe/Milkdown rich editor + source reconstruction/AST/LCS | `DELETE` + `TEMPORARY` | No rich semantic source authority/reconstruction | #144 first extracts renderers; #153 cutover; #154 purge |
| CodeMirror/Lezer source-native editor/bootstrap/sync/live-preview integration | `DELETE` + `TEMPORARY` | Native platform editor + source-neutral projection | #145/#152 prove target; #153 cutover; #154 purge |
| Markdown parsing/source-range lessons independent of browser editor | `EXTRACT` -> target `RETAIN` responsibility | #145 selects immutable exact `Document` snapshot -> bundled JetBrains Markdown parser -> immutable `NativeProjectionPlan`; no second editable model | #145 proves initial parser/range/presentation boundary; later replacement requires current evidence |
| Mermaid engine `11.17.2` | `RETAIN` | Single maintained Mermaid engine during migration | Replacement requires separate renderer decision |
| Mermaid config/theme/palette/size/zoom/error/cache/stale-result semantics | `EXTRACT` | One editor-independent derived-renderer service | #144 extracts/proves before editor adapter deletion |
| Mermaid `createCodeMirrorFeatureConfig`, `crepeSessionId`, DOM registry/IntersectionObserver/editor visibility coupling | `DELETE` after `EXTRACT` + `TEMPORARY` | Native inlay consumes extracted service | #144 extracts; #148 proves native consumer; #154 deletes old adapters |
| KaTeX `^0.18.5`, CSS/fonts, compatible inline/display semantics | `RETAIN` / `EXTRACT` | Direct KaTeX adapter in derived-renderer service | #144 extracts/proves; retained while capability supported |
| `Crepe.Feature.Latex` | `REPLACE` + `TEMPORARY` | Thin direct KaTeX adapter, no TeX reimplementation | #144 replaces service ownership; #154 deletes old adapter after #148/cutover |
| runtime Mermaid size/zoom/error, KaTeX density, theme/font/palette settings | `RETAIN` / `EXTRACT` | Native presentation + renderer settings | #148/#155 migrate shape while preserving approved semantics |
| browser settings payload/revision notifications | `REPLACE` + `TEMPORARY` | Typed host settings + presentation/renderer invalidation | #155 after actual consumers converge |
| `previewOnlyByDefault` browser meaning | `REPLACE` / `UNRESOLVED` | Explicit native projection/source-reveal UX meaning | #83/#155 must decide before old meaning removed |
| `idleEvictAfterMs`, editor pool/prewarm/retry/debounce knobs | `DELETE` + `TEMPORARY` | No target browser-editor pool | #155 after #154 |
| `DiagramSecurityLevel` | `EXTRACT` / security review | Retain only if #82 proves a safe meaningful renderer policy | #82/#149/#155; otherwise remove safely |
| IDE palette/font discovery | `RETAIN` / `EXTRACT` | Native presentation and renderer config | Remove only browser/CSS transport shape |
| web Markdown-aware clipboard behavior | `REPLACE` + `TEMPORARY` | #146 uses native IntelliJ paste ownership plus inserted-payload-only Markdown preprocessing; no browser edit authority | #146 proves target; #154 removes web implementation after cutover |
| raw HTML exact source-preservation invariant | `RETAIN` | Source remains untouched | Permanent |
| browser raw-HTML preview integration | `REPLACE` + `TEMPORARY` | Sanitized/isolated derived renderer -> inert artifact | #149 proves target; #154 removes old integration after cutover |
| Node/TypeScript/Vite | `TEMPORARY` / conditional `RETAIN` | Keep only for actual Mermaid/KaTeX/raw-HTML renderer execution | #155 after #154 decides final consumers; #68/#72 reconcile here |
| `@codemirror/*` dependencies | `DELETE` + `TEMPORARY` | No target browser editor | #155 after #154 |
| `@milkdown/crepe` | `DELETE` + `TEMPORARY` | No target rich editor; renderer extraction first | #155 after #154 |
| mandatory `com.intellij.modules.jcef` plugin dependency | `REPLACE` / `TEMPORARY` | Editing cannot require JCEF; final packaging follows renderer consumer | #155 after renderer adapter/cutover/purge evidence |
| editor-specific JCEF transport probe/workflow/evidence | `DELETE` / `REPLACE` + `TEMPORARY` | Remove editor transport probe; renderer evidence only if JCEF renderer survives | #154/#155 |
| prior real-JCEF request/network hostile evidence | `RETAIN` as lesson; mechanism harness `DELETE`/`REPLACE` | Reuse hostile invariants for renderer-specific containment | #149/#155 replace only when retained renderer boundary is proven |
| mechanism-specific old browser edit tests | `DELETE` after cutover | Preserve product/invariant fixtures; rewrite evidence around native target | #154 with deleted mechanism |
| hardening/release governance, merge-gate CI, Plugin Verifier | `RETAIN` | Repository/release policy independent of editor architecture | Permanent |
| historical user-report intake #99 | `RETAIN` | Requirement evidence independent of implementation | #147/#150/#151 map image requirements explicitly |

## TEMPORARY ownership summary

No temporary item may be retained merely for rollback comfort.

- **#143 — COMPLETED**: owns the native shell selection evidence and selected platform text-editor augmentation; it does not cut production over.
- **#144** owns renderer extraction while the old editor remains a migration consumer.
- **#145 — COMPLETED**: owns the initial immutable snapshot/projection/controller foundation over #143.
- **#146–#152** own remaining target capability proof needed before cutover; #146 resolves native edit ownership but remains subject to its own exact-final-HEAD/post-main completion gate.
- **#153** owns the production native-editor cutover and makes the old editor unreachable as a normal authority.
- **#154** owns mandatory deletion of superseded browser editors, source sync protocols, editor trust/resource realm, browser lease/recovery and mechanism-only tests.
- **#155** owns dependency/toolchain/JCEF/settings convergence after actual retained consumers are known.
- **#156** proves no hidden temporary mechanism remains through final compatibility/lifecycle/performance/release convergence.

If an item is still temporary after its listed criterion, that is a failing migration obligation, not an implicit reclassification.

## Renderer continuity / no-double-work order

Mermaid and KaTeX migration order is fixed:

1. existing renderer responsibilities are extracted/proven while current production rendering still works (#144);
2. one editor-independent derived-renderer service/runtime owns Mermaid/KaTeX execution;
3. native editor/inlay consumer uses that same service (#148);
4. capability/settings/failure parity is proven;
5. production native editor cuts over (#153);
6. old Crepe/CodeMirror renderer adapters are deleted with superseded editor code (#154);
7. only then dependency/toolchain/JCEF packaging is converged (#155).

Forbidden shortcuts:

- JVM Mermaid reimplementation;
- custom TeX layout implementation;
- delete renderer now / recreate later;
- two independent steady-state engines;
- keeping editor DOM/session identity inside renderer core;
- using renderer JCEF as an excuse to keep browser editing.

## Trust migration

Target trust ownership:

- local images: host path/media/size/symlink resolver -> inert artifact (#147);
- navigation: explicit host action with scheme policy (#147);
- raw HTML: exact source + sanitized/isolated derived preview (#149);
- image-file import: explicit product contract (#150) then host/VFS implementation (#151);
- renderer JCEF, if retained: renderer-specific containment, no ambient filesystem/network/navigation and no editing authority.

Historical source-native editor request/CSP/loopback/local-image mechanisms remain temporary until #154 and are not copied into the renderer boundary by default.

## #136 / PR #137 disposition

- #136 `task(trust): enforce source-native CSP and nonce-bound browser content` is closed `not planned`.
- PR #137 is closed without merge as a superseded source-native editor trust experiment.

Retained lessons: active content fails closed, real production-path evidence matters, request/navigation containment must be adversarially tested, and capability/path hostile cases remain useful.

If a JCEF renderer requires CSP/request/network containment, create/prove renderer-specific work under the target renderer/trust boundary. Do not reopen/rebase #136/#137 as editor work.

## Track reconciliation

- #79 — native `Document` authority, commands, save, undo semantics;
- #80 — historical synchronization correctness evidence only; not a target protocol mandate;
- #81 — native WYSIWYG projection + integration shell;
- #82 — host-owned resources/navigation + isolated renderer containment;
- #83 — native presentation/renderer settings + persistence migration;
- #84 — convergence, compatibility, performance and mandatory superseded-code purge.

#52 and the architecture index must describe the accepted target/current gate state consistently with these Tracks.

## Stale guidance reconciliation

Audited contributor/agent/engineering authority that could otherwise direct work back toward browser editing:

| Guidance | Previous stale assumption | Reconciled direction |
| --- | --- | --- |
| `AGENTS.md` | deterministic host↔web sync/versioned protocol/JCEF editor lifecycle as standing core contract | native `Document` authority, derived projection/renderer, legacy browser protocol only migration safety |
| `.codex/agents/architect-agent.toml` | establish host↔web/session/revision/JCEF lifecycle contracts | enforce native authority, renderer continuity, deletion ownership |
| `.codex/agents/backend-agent.toml` | stale callbacks/session generation/bridge adapter as target | native editor/command/projection lifecycle and host resources |
| `.codex/agents/frontend-agent.toml` | webview editor engine/protocol as permanent responsibility | derived renderer extraction/isolation; no browser editor authority |
| `.github/copilot-instructions.md` | host↔web sync/versioned bridge correctness default | native authority + renderer-only optional JCEF |
| `.github/ai-review-prompt.md` | source edit protocol/revision/flush races as universal review axis | native source/projection/renderer axes; legacy protocol only when temporary code is touched |
| `.github/pull_request_template.md` | host↔web/JCEF editor lifecycle required contract field | native source/projection/renderer/migration ownership fields |
| `.github/ISSUE_TEMPLATE/architecture_change.yml` | host-webview protocol/JCEF editor lifecycle treated as default architecture-contract fields | authority-first proposal, native source/projection/renderer contracts, and host-web/JCEF details only when an actual retained boundary requires them |
| `CONTRIBUTING.md` | architecture-significant change list centered on host↔web/JCEF editor; repository visibility stale | accepted native target and renderer continuity; public repo wording |
| `GOVERNANCE.md` | host↔web protocol/JCEF editor lifecycle always architecture-significant standing model | native source/projection/renderer boundaries; reintroducing edit protocol requires new proof |
| `SECURITY.md` | JCEF editor/protocol as generic target trust boundary | host resources + isolated renderer target; legacy editor surfaces explicitly temporary |
| `docs/engineering/testing-strategy.md` | host↔web synchronization as permanent contract suite | native source/projection/renderer proof; old protocol suite migration-only until purge |
| `README.md` | target described as browser custom editor + native fallback/coexistence | clearly distinguishes current JCEF runtime from accepted native-authority target |
| `docs/architecture/README.md` | transitional wording around PR #142 / pre-#143 execution state | records accepted architecture, completed #143/#145 foundations, and #146 native edit ownership resolution without claiming pre-gate completion |

Historical `plans/*` and CHANGELOG entries are preserved as historical record rather than rewritten. Current source comments/tests that accurately describe temporary production mechanisms remain implementation evidence until their owner Task deletes them; they are not elevated into architecture authority.

## Target-derived backlog #143–#156

### Completed prerequisite / independently eligible slices

- **#143 — COMPLETED** `task(editor): prove and select the native IntelliJ editor integration shell` — selected `PLATFORM_TEXT_EDITOR_AUGMENTATION`.
- **#144** `task(renderer): extract one Mermaid and KaTeX derived-renderer service from editor coupling` — independently eligible.
- **#145 — COMPLETED** `task(editor): establish the native Markdown projection engine and per-editor controller` — immutable exact snapshots, bundled JetBrains Markdown parser, native markup/folding and per-editor presentation ownership are proven over #143.
- **#150** `task(product): ratify the image-file import contract from historical public report #4` — independently eligible.

### Foundation/capability slices

- **#146 — ACTIVE until exact-head merge/post-main verification** `task(editor): move paste, editor state and rich edit actions onto native Document semantics` — depends on completed #143 + #145; target ownership uses platform paste/command/undo/`FileEditorState` semantics, inserted-payload-only Markdown preprocessing and source-local representative rich edits.
- **#147** `task(trust): implement host-owned local image projection and external navigation` — depends on #145.
- **#148** `task(renderer): render Mermaid and KaTeX through native editor inlays` — depends on #144 + #145 and uses the same renderer service.
- **#149** `task(trust): add source-preserved sanitized raw-HTML derived rendering` — depends on #145; may reuse #144 renderer execution boundary.
- **#151** `task(editor): implement the approved host/VFS image-file import workflow` — depends on #150 + #147.
- **#152** `task(editor): complete native WYSIWYG Markdown capability and fidelity parity` — depends on #145 + #146; specialized capabilities remain sibling Tasks.

### Cutover/convergence slices

- **#153** `task(leap): cut production MarkFlow editing over to the native projection architecture` — requires target evidence from #143–#152, including #150/#151 for approved product completeness.
- **#154** `task(leap): purge superseded browser editors, sync protocols and editor trust machinery` — hard dependency on #153.
- **#155** `task(build): converge renderer dependencies, toolchain, JCEF packaging and settings after editor purge` — after #154, based on actual renderer consumers.
- **#156** `task(quality): prove native Leap convergence across compatibility, lifecycle, performance and release gates` — final technical convergence; makes #84 eligible to close.

## Dependency graph

```text
#141 inventory / backlog reset
        |
        +--> #143 native shell proof [COMPLETED: platform augmentation]
        |
        +--> #144 renderer extraction
        |
        +--> #150 image-import product decision

#143 --> #145 projection foundation [COMPLETED]
#143 + #145 --> #146 native editing/actions/state

#145 --> #147 host image/navigation
#144 + #145 --> #148 Mermaid/KaTeX native consumer
#145 --> #149 raw HTML derived rendering
#150 + #147 --> #151 image import
#145 + #146 --> #152 ordinary Markdown/table parity

#143-#152 required target proof
        |
        v
#153 production native cutover
        |
        v
#154 mandatory old-editor/protocol/trust purge
        |
        v
#155 dependency/toolchain/JCEF/settings convergence
        |
        v
#156 final compatibility/lifecycle/performance/release convergence
        |
        v
#84 eligible to close
```

#139/#141 are completed architecture-reset gates, not the full implementation gate. #52 remains open through implementation/convergence.

## Resolved and unresolved lower-level choices

Resolved since the original #141 inventory:

- **native integration shell (#143):** `PLATFORM_TEXT_EDITOR_AUGMENTATION`; a MarkFlow-owned native replacement `FileEditor` shell is rejected.
- **#145 baseline parser/projection foundation:** immutable exact `Document` snapshot + source/config identity -> bundled JetBrains Markdown parser -> immutable projection plan -> per-editor native controller; native markup plus parser-proven safe folding supply the initial presentation/reveal proof.
- **#146 native edit ownership:** platform `TextEditor` retains actual paste insertion, input, caret/multicaret, command/undo/dirty/save and opaque `FileEditorState` ownership. MarkFlow may prefer/normalize only the inserted Markdown payload through `CopyPastePreProcessor` outside parser-proven code blocks and may perform explicitly selected, source-local representative rich edits through one native write command. Historical browser-shaped `MarkFlowEditorState` is not promoted to target authority. This resolution is implementation-complete only after #146's exact-final-HEAD/post-main gate passes.

Still unresolved or intentionally deferred:

- full incremental/changed-range parsing strategy beyond the synchronous #145 baseline; add complexity only with measured evidence;
- exact syntax reveal/presentation technique per remaining construct (#152 and specialized Tasks);
- renderer execution adapter, including whether isolated JCEF/TypeScript remains (#144/#155);
- inert artifact representation for Mermaid/KaTeX/raw HTML (#144/#148/#149);
- final raw-HTML sanitizer/render path (#149);
- image-import gestures/destination/collision/path/undo semantics (#150);
- final renderer-sensitive security setting surface (#82/#149/#155);
- final Node/TypeScript/Vite/JCEF packaging (#155);
- measured cache/concurrency strategy (#156 or focused evidence task if needed).

No implementation may convert an unresolved choice into a fact merely by reusing current code.

## #68 / #72 toolchain reconciliation

Node/TypeScript maintenance issues remain real signals but must not run as architecture-preservation work.

- If #155 proves the renderer runtime still consumes Node/TypeScript, rescope/execute upgrades against that retained renderer toolchain.
- If no consumer remains, close those migrations as superseded and remove dead toolchain state through #155.
- Until #154/#155 establish actual consumers, current toolchain existence is not sufficient retention evidence.

## Historical #139/#141 completion record

The original #141 inventory required #142 acceptance, repository classification, stale-guidance reconciliation, Track/backlog consistency, #136/#137 disposition, explicit temporary ownership/deletion criteria, exact-final-HEAD CI/ruleset/thread review, squash merge with `expected_head_sha`, and post-main SHA/tree/signature/CI verification before #141/#139 completion.

Those gates were completed through #157. They are retained here as historical proof context, not as instructions to reopen #139/#141 or to treat already-resolved #143/#145 as an UNKNOWN. Current Tasks #146–#156 each own their own exact-final-HEAD proof gate.

## Review obligations for ongoing reconciliation

Changes to this migration authority are architecture-significant prose and must be reviewed accordingly:

- preserve the original audited-base record when later Tasks resolve choices;
- never convert a Task candidate into a completed fact before its exact-head merge/post-main gate passes;
- no stale guidance may instruct contributors to recreate browser editing as target;
- current-runtime README/history may describe temporary browser behavior only when clearly labeled as current/migration state;
- renderer continuity must prevent deletion/reimplementation/double-engine migration;
- temporary owner/deletion criteria must cover all major old-editor mechanisms;
- issue graph #143–#156 must match actual issue contracts/current completion state;
- unresolved review threads must be zero for the PR changing this authority;
- any exact HEAD movement invalidates prior PASS/evidence for that PR.
