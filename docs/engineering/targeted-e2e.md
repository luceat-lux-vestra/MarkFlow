# Targeted E2E development loop (#515)

Manual diagnostic runs are **not** canonical required PR checks and **not**
reviewed 89-case release acceptance. Keep the ordinary work branch private to
development until the final candidate is stable, because an open PR triggers the
normal broad Build workflow again on every push.

## Exact branch HEAD

Use the same explicit immutable commit and dispatch ref. Do not paste an older
SHA after the branch has moved.

```bash
REPO=luceat-lux-vestra/MarkFlow
BRANCH=ci/515-branch-targeted-e2e  # replace with the current work branch
SHA="$(gh api "repos/$REPO/git/ref/heads/$BRANCH" --jq '.object.sha')"
```

Both existing workflow names are registered on default branch; their
`workflow_dispatch` can be run with `--ref "$BRANCH"`. The dispatched
workflow version and tested checkout are both restricted to this branch HEAD
for targeted mode.

## One differential case — minimal real-IDE capture

```bash
gh workflow run full-differential-acceptance.yml -R "$REPO" \
  --ref "$BRANCH" -f target_sha="$SHA" -f mode=targeted \
  -f case_ids=mermaid-info
```

Multiple expanded case IDs can be comma-separated, for example
`mermaid-flowchart-large--block-01,mermaid-flowchart-large--block-02`.
The validator rejects duplicate, unknown, unsupported, degraded or
Preview-noncomparable case IDs. A targeted run uses the existing
`differentialContract=capture` and `differentialCaseIds` Gradle properties,
checks that **all selected cases actually ran**, retains the exact plugin ZIP
SHA-256, and records `TARGETED_DIFFERENTIAL_CAPTURED_UNVERIFIED`. All other
corpus cases intentionally remain `NOT_EXECUTED`.

## One Starter E2E suite, shard, or visual job

```bash
gh workflow run starter-driver-e2e.yml -R "$REPO" \
  --ref "$BRANCH" -f mode=targeted -f target_sha="$SHA" \
  -f target_shard=derived-content \
  -f target_suite=com.algorist.markflow.e2e.MarkFlowStarterDifferentialManifestTest \
  -f differential_case_ids=mermaid-info
```

Omit `target_suite` to run every suite in an explicitly selected shard;
omit `differential_case_ids` to use the ordinary bounded manifest cohort.
The only valid case-specific combination is
`target_shard=derived-content` + the exact
`MarkFlowStarterDifferentialManifestTest` suite. Other allowed shards are
`canonical`, `core-editing`, `ordinary-markdown`, `lifecycle` and
`visual`. The visual option runs visual acceptance alone.

The separately named `Targeted E2E Diagnostic Gate` verifies the chosen
shard/visual result. It must never be treated as the required
`Starter Driver E2E Gate` or the repository's `Merge Gate`.

## Final complete candidate, separately from development

After code and reviewed ledger are frozen at a specific work-branch HEAD,
invoke the existing strict full corpus:

```bash
gh workflow run full-differential-acceptance.yml -R "$REPO" \
  --ref "$BRANCH" -f target_sha="$SHA" -f mode=full \
  -f controlled_reference=false
```

The strict full path still requires all 89 expanded cases, all 89 reviewed
ledger identities and a valid exact-source acceptance artifact. If reviewed
ledger prerequisites have not yet been established, the full run must fail
rather than infer success from controlled capture.

Then open the PR at the *same final candidate HEAD*. The repository's canonical
`Build` PR event must produce all applicable required checks and its
`Merge Gate` before squash merge. A successful manual targeted run does not
satisfy PR-required check contexts. If the candidate HEAD changes, rerun the
affected proof against the new SHA. Post-merge or release-candidate repeat
requirements (#348/#309) remain independent.

## Safety and maintenance

- `target_sha` must equal the SHA selected by `--ref` in targeted mode.
- Input suite/shard/case lists are allowlisted from the exact checked-out
  source; no shell commands or arbitrary Gradle tasks are accepted.
- `--mode full` rejects subset selectors.
- Existing named source-anchor / ownership / source-range diagnostic flags
  remain independent of the new targeted mode.
- The scheduled full differential workflow remains full-corpus only.
- A failure or missing source-bound result cannot be reclassified as accepted.
- Workflow topology edits require hardening/actionlint checks and the normal
  fail-closed exact-HEAD PR CI before merge.
