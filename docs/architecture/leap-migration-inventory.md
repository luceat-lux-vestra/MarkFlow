# Leap migration inventory and execution map

Status: repository migration authority for #141 under accepted ADR 0001 and ADR 0002; execution status reconciled through completed #155 renderer/toolchain convergence with #156 active as the final quality gate

Original #141 audited base:

- `main`: `c130ceb2648e262414cd0aed9cc463c78d9b27ec`
- tree: `971320e0a9fd77e8cc1bbcc82d25256fc5539a7c`
- commit: `docs(leap): select native-authority projection architecture (#142)`

The original inventory classified repository responsibilities without production migration implementation. Subsequent target Tasks may resolve lower-level choices recorded here; those resolutions are appended/reconciled rather than rewriting the audited base as if it had known the future.

Current execution resolutions relevant to this inventory:

- #139/#141 are completed;
- #143 is completed and selected **`PLATFORM_TEXT_EDITOR_AUGMENTATION`** — normal IntelliJ platform text-editor augmentation, not a MarkFlow-owned replacement `FileEditor` shell;
- #145 is completed and establishes the immutable snapshot/parser/projection-plan/per-editor-controller foundation over that selected shell;
- #146 established target paste/state/representative-rich-edit ownership as platform paste/command/undo/`FileEditorState` semantics plus narrowly scoped MarkFlow payload/local-edit behavior;
- #153 and #154 are completed, production editing is platform-native, superseded browser editor/provider/protocol machinery is deleted, and #155 completed retained renderer-only dependency/toolchain/JCEF/settings convergence. #156 is the active final quality/convergence gate.

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
8. Mermaid `11.17.2` and KaTeX `^0.18.7` remain the landed renderer baselines during this purge; renderer upgrades are separately gated.
9. Local resources and external navigation are host-owned capabilities.
10. Optional renderer/JCEF failure never makes source editing unavailable.
11. Every temporary old-editor mechanism has an explicit owner and deletion criterion.
12. End state has one production editor architecture and one engine per supported renderer.

## Responsibility-level classification

The rows below preserve the audited migration responsibility while recording the final disposition. Historical migration-only rows whose deletion/replacement criteria have been satisfied are marked `RESOLVED`; no `TEMPORARY` production mechanism remains unresolved at #156 entry. A superseded row is historical evidence, not permission to recreate the mechanism.

| Audited responsibility / mechanism | Classification | Target disposition | Temporary owner / deletion criterion |
| --- | --- | --- | --- |
| #78 product/fidelity contract + hostile/fidelity corpus | `RETAIN` | Product/evidence authority independent of editor implementation | Permanent while capability remains supported |
| `MarkFlowFileSupport` supported-file recognition | `RETAIN` | Host-level recognition | Revisit only by product-scope decision |
| current browser-backed `MarkFlowEditor` | `REPLACE` / `RESOLVED` | #143 selected normal platform text-editor augmentation over the same `Document` | #153 owns production cutover; #154 deletes old editor after cutover proof |
| `MarkFlowEditorProvider` JCEF-gated takeover / `HIDE_DEFAULT_EDITOR` | `REPLACE` / `RESOLVED` | #143 rejected target replacement-provider ownership; target augments the platform text editor and editing cannot depend on JCEF | Current provider remains migration-only until #153; #154 removes the superseded provider path |
| browser-shaped `MarkFlowEditorState` (`scrollTop`, JS caret/selection injection) | `EXTRACT` / `REPLACE` / `RESOLVED` | #146 keeps platform `TextEditor`/opaque `FileEditorState` as target state authority; no new MarkFlow native state DTO | #146 proves target state ownership; #155 removes obsolete persisted/browser shape after cutover/purge |
| `DocumentSession`/registry/custom web revision ownership | `REPLACE` / `DELETE` / `RESOLVED` | Native `Document` command/write/undo semantics only | Current editor owns until #153; #154 deletes when browser editor is unreachable |
| `SourceRevisionGate` | `DELETE` / `RESOLVED` | No web source proposal in target; derived work uses exact source/config identity | #154 after #153 |
| `DocumentContentDiff` / whole-content web->Document replacement | `DELETE` / `RESOLVED` | Native user edits already modify `Document` | #154 after last web-origin edit consumer |
| `sync/AttachmentIdentity`, mutation/ACK/recovery/wire protocol | `DELETE` / `RESOLVED` | No cross-runtime editable source | #154 after #153 |
| `SourceNativeEditorRuntime`, readiness/edit transport | `DELETE` / `RESOLVED` | Native platform editor owns editing | #154 after #153 |
| `JcefSourceNativeRuntimeTransport` editor transport/query lifecycle | `DELETE` / `RESOLVED` | Any retained JCEF is renderer-only behind separate adapter | #154 after #153; renderer-specific adapter belongs #144/#148/#149 if needed |
| shared editor browser lease/pool/recovery/prewarm/idle eviction | `DELETE` / `RESOLVED` | No browser editor lifecycle | Current runtime until #153; #154 deletes; never repurpose by default |
| `MarkFlowJcefSupport` generic editor availability gate | `EXTRACT` / `REPLACE` / `RESOLVED` | Narrow optional renderer-runtime capability check only if final renderer needs JCEF | #155 renames/relocates if retained, deletes if no renderer consumer |
| generic webview/loopback static/resource manager | `REPLACE` / `RESOLVED` | Editor serving deleted; renderer gets only minimum proven execution/assets | #154 deletes editor routes; #155 converges retained renderer assets/toolchain |
| source-native editor request/origin/CSP/navigation containment | `DELETE` / `RESOLVED` | Editor browser surface disappears | #154 after #153; renderer-specific containment belongs #149/#155 if needed |
| source-native/legacy browser local-image URL/token capability | `REPLACE` / `RESOLVED` | Host resolver -> bounded inert image artifact | #147 proves target; #154 removes old routes after cutover |
| browser external-navigation bridge | `REPLACE` / `RESOLVED` | Explicit host navigation action | #147 proves target; #154 removes bridge after cutover |
| legacy Crepe/Milkdown rich editor + source reconstruction/AST/LCS | `DELETE` / `RESOLVED` | No rich semantic source authority/reconstruction | #144 first extracts renderers; #153 cutover; #154 purge |
| CodeMirror/Lezer source-native editor/bootstrap/sync/live-preview integration | `DELETE` / `RESOLVED` | Native platform editor + source-neutral projection | #145/#152 prove target; #153 cutover; #154 purge |
| Markdown parsing/source-range lessons independent of browser editor | `EXTRACT` -> target `RETAIN` responsibility | #145 selects immutable exact `Document` snapshot -> bundled JetBrains Markdown parser -> immutable `NativeProjectionPlan`; no second editable model | #145 proves initial parser/range/presentation boundary; later replacement requires current evidence |
| Mermaid engine `11.17.2` | `RETAIN` | Single maintained Mermaid engine during migration | Replacement requires separate renderer decision |
| Mermaid config/theme/palette/size/zoom/error/cache/stale-result semantics | `EXTRACT` | One editor-independent derived-renderer service | #144 extracts/proves before editor adapter deletion |
| Mermaid `createCodeMirrorFeatureConfig`, `crepeSessionId`, DOM registry/IntersectionObserver/editor visibility coupling | `DELETE` after `EXTRACT` / `RESOLVED` | Native inlay consumes extracted service | #144 extracts; #148 proves native consumer; #154 deletes old adapters |
| KaTeX `^0.18.7`, CSS/fonts, compatible inline/display semantics | `RETAIN` / `EXTRACT` | Direct KaTeX adapter in derived-renderer service | #144 extracts/proves; retained while capability supported |
| `Crepe.Feature.Latex` | `REPLACE` / `RESOLVED` | Thin direct KaTeX adapter, no TeX reimplementation | #144 replaces service ownership; #154 deletes old adapter after #148/cutover |
| runtime Mermaid size/zoom/error, KaTeX density, theme/font/palette settings | `RETAIN` / `EXTRACT` | Native presentation + renderer settings | #148/#155 migrate shape while preserving approved semantics |
| browser settings payload/revision notifications | `REPLACE` / `RESOLVED` | Typed host settings + native presentation invalidation; retained renderer receives only typed render settings | #155 removes browser sink/notification shape |
| `previewOnlyByDefault` browser meaning | `DELETE` / `RESOLVED` | No native setting; native projection remains source-neutral and reveal behavior is explicit | #155 removes the no-op browser-era key and safely ignores persisted legacy values |
| `idleEvictAfterMs`, editor pool/prewarm/retry/debounce knobs | `DELETE` / `RESOLVED` | No target browser-editor pool | #154 removed the pool/runtime; #155 removes the persisted setting |
| `DiagramSecurityLevel` | `DELETE` / `RESOLVED` | Mermaid renderer is fail-closed at `securityLevel: strict`; no user-selectable weaker mode | #155 removes the unproven `LOOSE` surface while preserving renderer safety |
| IDE palette/font discovery | `RETAIN` / `EXTRACT` | Native presentation and renderer config | Remove only browser/CSS transport shape |
| web Markdown-aware clipboard behavior | `REPLACE` / `RESOLVED` | #146 uses native IntelliJ paste ownership plus inserted-payload-only Markdown preprocessing; no browser edit authority | #146 proves target; #154 removes web implementation after cutover |
| raw HTML exact source-preservation invariant | `RETAIN` | Source remains untouched | Permanent |
| browser raw-HTML preview integration | `REPLACE` / `RESOLVED` | Sanitized/isolated derived renderer -> inert artifact | #149 proves target; #154 removes old integration after cutover |
| Node/TypeScript/Vite | `RETAIN` / renderer-only | Required by the #144-selected Mermaid/KaTeX isolated renderer build; no editor bundle remains | #155 verifies renderer-only inputs/outputs; #68/#72 apply only to this retained toolchain |
| `@codemirror/*` dependencies | `DELETE` / `RESOLVED` | No target browser editor | #154 purged the editor stack; #155 verifies no retained dependency/import |
| `@milkdown/crepe` | `DELETE` / `RESOLVED` | No target rich editor | #154 purged the editor stack after renderer extraction; #155 verifies no retained dependency/import |
| mandatory `com.intellij.modules.jcef` plugin dependency | `REPLACE` / `RESOLVED` | JCEF is optional behind `markflow-jcef.xml`; native source editing/settings load without it | #144 selected isolated JCEF rendering; #155 preserves optional packaging and no-JCEF real-IDE proof |
| editor-specific JCEF transport probe/workflow/evidence | `DELETE` / `RESOLVED` | Editor transport proof is gone; retained JCEF evidence is renderer-specific | #154 removed editor transport machinery; #155 keeps only renderer/native degradation evidence |
| prior real-JCEF request/network hostile evidence | `RETAIN` as lesson; mechanism harness `RESOLVED` | Reuse hostile invariants for renderer-specific containment | #149/#155 replace only when retained renderer boundary is proven |
| mechanism-specific old browser edit tests | `DELETE` / `RESOLVED` | Preserve product/invariant fixtures; rewrite evidence around native target | #154 removed the mechanism-specific harness after native replacement proof |
| hardening/release governance, merge-gate CI, Plugin Verifier | `RETAIN` | Repository/release policy independent of editor architecture | Permanent |
| historical user-report intake #99 | `RETAIN` | Requirement evidence independent of implementation | #147/#150/#151 map image requirements explicitly |

## TEMPORARY ownership summary

No temporary item may be retained merely for rollback comfort.

- **#143 — COMPLETED**: owns the native shell selection evidence and selected platform text-editor augmentation; it does not cut production over.
- **#144 — COMPLETED**: extracted the single editor-independent Mermaid/KaTeX renderer service before old-editor deletion.
- **#145 — COMPLETED**: established the immutable snapshot/projection/controller foundation over #143.
- **#146–#152 — COMPLETED**: native editing/state, host resources, derived presentation, raw HTML, image import, and ordinary Markdown/table fidelity proofs are merged and closed.
- **#153 — COMPLETED**: production native-editor cutover made the old editor unreachable as a normal authority.
- **#154 — COMPLETED**: deleted superseded browser editors, source sync protocols, editor trust/resource realm, browser lease/recovery and mechanism-only tests.
- **#155 — COMPLETED**: dependency/toolchain/JCEF/settings convergence is complete over actual retained renderer consumers.
- **#156 — ACTIVE**: prove no hidden temporary mechanism remains through final compatibility/lifecycle/performance/release convergence.

#156 entry audit: unresolved `TEMPORARY` production rows = **0**. Any reintroduced temporary editor/protocol/trust mechanism is a new failure, not rollback compatibility.

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

Historical source-native editor request/CSP/loopback/local-image mechanisms were migration-only; #154 removes them rather than copying them into the retained renderer boundary.

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
| `SECURITY.md` | JCEF editor/protocol as generic target trust boundary | host resources + optional isolated renderer target; deleted editor surfaces are no longer live trust boundaries |
| `docs/engineering/testing-strategy.md` | host↔web synchronization as permanent contract suite | native source/projection/renderer proof; deleted protocol harnesses stay absent after purge |
| `README.md` | target described as browser custom editor + native fallback/coexistence | describes native production editing and optional renderer-only JCEF accurately |
| `docs/architecture/README.md` | transitional wording around PR #142 / pre-#143 execution state | records the completed #143–#155 migration chain and #156 as the active final convergence gate |

Historical `plans/*`, CHANGELOG entries and decision-time ADR context are preserved as historical record rather than rewritten into current runtime claims. #154 removed the temporary browser editor mechanisms; current guidance/tests may retain only explicit historical context or negative absence checks, not instructions to recreate them.

## Target-derived backlog #143–#156

### Completed foundation/capability slices

- **#143 — COMPLETED** `task(editor): prove and select the native IntelliJ editor integration shell` — selected `PLATFORM_TEXT_EDITOR_AUGMENTATION`.
- **#144 — COMPLETED** `task(renderer): extract one Mermaid and KaTeX derived-renderer service from editor coupling` — one isolated renderer service owns both retained engines.
- **#145 — COMPLETED** `task(editor): establish the native Markdown projection engine and per-editor controller` — immutable exact snapshots, bundled JetBrains Markdown parser, native markup/folding and per-editor presentation ownership are proven over #143.
- **#146 — COMPLETED** `task(editor): move paste, editor state and rich edit actions onto native Document semantics` — platform paste/command/undo/`FileEditorState` semantics and source-local rich edits are merged and post-main proven.
- **#147 — COMPLETED** `task(trust): implement host-owned local image projection and external navigation` — host resource/navigation ownership is merged and post-main proven.
- **#148 — COMPLETED** `task(renderer): render Mermaid and KaTeX through native editor inlays` — the native consumer uses the retained renderer service.
- **#149 — COMPLETED** `task(trust): add source-preserved sanitized raw-HTML derived rendering` — safe derived rendering and hostile exact-source degradation are implemented.
- **#150 — COMPLETED** `task(product): ratify the image-file import contract from historical public report #4` — product behavior is ratified.
- **#151 — COMPLETED** `task(editor): implement the approved host/VFS image-file import workflow` — the approved host/VFS transaction is implemented.
- **#152 — COMPLETED** `task(editor): complete native WYSIWYG Markdown capability and fidelity parity` — ordinary Markdown/table fidelity and supported native interaction parity are merged and post-main proven.

### Cutover/convergence slices

- **#153 — COMPLETED** `task(leap): cut production MarkFlow editing over to the native projection architecture` — production native ownership and post-main evidence are complete.
- **#154 — COMPLETED** `task(leap): purge superseded browser editors, sync protocols and editor trust machinery` — the unreachable browser editing architecture and editor transport machinery are removed.
- **#155 — COMPLETED** `task(build): converge renderer dependencies, toolchain, JCEF packaging and settings after editor purge` — renderer-only tooling/JCEF/settings convergence is merged and post-main proven.
- **#156 — ACTIVE** `task(quality): prove native Leap convergence across compatibility, lifecycle, performance and release gates` — the only remaining #143–#156 migration task; completion makes #84 eligible to close.

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

## Resolved and deferred lower-level choices

Resolved since the original #141 inventory:

- **native integration shell (#143):** `PLATFORM_TEXT_EDITOR_AUGMENTATION`; a MarkFlow-owned native replacement `FileEditor` shell is rejected.
- **parser/projection foundation (#145):** immutable exact `Document` snapshot + source/config identity -> bundled JetBrains Markdown parser -> immutable projection plan -> per-editor native controller.
- **native edit ownership (#146):** platform `TextEditor` owns paste insertion, input, caret/multicaret, command/undo/dirty/save and opaque `FileEditorState`; MarkFlow only performs the proven inserted-payload preprocessing and source-local edits. Historical browser-shaped editor state is not target authority.
- **host resources/navigation (#147):** local-image and external-navigation capabilities are host-owned and do not depend on an editor browser.
- **derived presentation (#144/#148/#149):** one retained Mermaid/KaTeX service feeds native presentation, while sanitized raw HTML remains source-preserved and degrades to exact source on hostile/unsupported input.
- **image import (#150/#151):** the ratified host/VFS import contract and transaction behavior are implemented.
- **ordinary Markdown/table parity (#152):** supported native interaction and fidelity baseline is complete.
- **renderer execution/package target (#144/#155):** retain TypeScript/Vite and Mermaid/KaTeX only behind optional renderer-only JCEF packaging; native source editing and base settings do not link to JCEF classes.
- **renderer security/settings target (#82/#155):** Mermaid security is fixed fail-closed at `strict`; browser-only `previewOnlyByDefault`, `idleEvictAfterMs`, and user-selectable `DiagramSecurityLevel` are not target settings.

Intentionally deferred work is optimization or future feature work, not an unresolved migration authority:

- incremental/changed-range parsing beyond the synchronous #145 baseline requires a measured bottleneck and separate correctness proof;
- new pooling/cache/prewarm/concurrency complexity requires measured benefit, explicit bounds, and ownership;
- additional unsupported constructs or interaction techniques require their own product/task contract rather than weakening the completed #152 baseline.

No deferred optimization or future feature may be presented as compatibility justification for the deleted browser editor.

## #68 / #72 toolchain reconciliation

#155 established that Node, TypeScript, and Vite remain real consumers of the isolated Mermaid/KaTeX renderer build. Issues #68 and #72 therefore remain open only as renderer-toolchain maintenance work:

- #68 may align the retained renderer build with a maintained Node runtime/types baseline;
- #72 may migrate the retained renderer compiler independently;
- neither issue may preserve or recreate browser-editor bundles, editing protocols, or browser editing authority.

## Historical #139/#141 completion record

The original #141 inventory required #142 acceptance, repository classification, stale-guidance reconciliation, Track/backlog consistency, #136/#137 disposition, explicit temporary ownership/deletion criteria, exact-final-HEAD CI/ruleset/thread review, squash merge with `expected_head_sha`, and post-main SHA/tree/signature/CI verification before #141/#139 completion.

Those gates were completed through #157. They are retained here as historical proof context, not as instructions to reopen #139/#141 or to treat already-resolved #143/#145 as an UNKNOWN. #146–#155 are completed. #156 alone owns the current exact-final-HEAD and post-main convergence gate before #84 becomes eligible to close.

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
