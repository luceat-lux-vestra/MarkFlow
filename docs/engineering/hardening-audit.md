# Hardening audit operational contract

The `Hardening audit` workflow has two trust modes. The `audit` job performs
static, producer, workflow-security, and fixture checks without an
administration credential on pull requests, pushes, schedules, and manual
runs. Its `Hardening audit` context is staged and is not a required `main`
context.

That credential-less job is the default scheduled drift detector. A successful
run proves only repository-owned policy/workflow invariants that the job can
check without privileged GitHub administration access. It must never be cited
as proof that live rulesets, repository administration settings, or the live
label catalog were read successfully.

Authoritative live administration readback is a separate trust boundary. The
normal hardening exit/merge process performs that readback from an authenticated
admin-capable session and records the exact `main` SHA with the evidence. The
audit script retains its fail-closed report checks so the same comparisons can
also be invoked explicitly with a scoped administration credential.

The workflow's `live-readback` job is an optional automation path, not a
repository prerequisite. It is disabled by default. It runs only when the
repository variable `HARDENING_LIVE_READBACK_ENABLED` is explicitly set to
`true`, the event is a schedule or default-branch `workflow_dispatch`, and the
scoped `HARDENING_AUDIT_TOKEN` secret is separately provisioned. MarkFlow does
not require either the variable or the secret for normal PR, push, scheduled,
or manual hardening audits.

When authoritative live readback is performed, it checks the live
`main protection` ruleset, the `release tag immutability` ruleset, repository
labels referenced by automation, repository merge settings, and the declared
public-repository security/Actions settings against
`.github/merge-gate-policy.json`.

The 2026-09-22 public-repository re-audit makes security applicability explicit.
The canonical report contract requires Dependency Graph, Dependabot alerts and
security updates, secret scanning, push protection, and private vulnerability
reporting to be enabled. It also requires the default `GITHUB_TOKEN` workflow
permission to remain read-only and forbids Actions from approving pull-request
reviews. MarkFlow keeps checked-in CodeQL as the intentional analysis authority,
so GitHub CodeQL default setup is required to remain `not-configured` unless a
later reviewed authority migration changes both policy and implementation.

These are administration-backed report checks, not claims produced by the
credential-less PR audit. If the administration credential cannot read a
security field or endpoint, the result is a finding; missing data is never
coerced into an enabled/default-safe value.

Ruleset identity is fail-closed: the live ruleset list must contain exactly
one entry for each canonical name. Zero matches, duplicate names, or a
matched entry without an id stop the readback. The main ruleset must have
`target=branch` and exactly `include=["~DEFAULT_BRANCH"]`, while the release
ruleset must have `target=tag` and exactly `include=["~ALL"]`; both must have
`exclude=[]`.

## Staged-context re-evaluation — 2026-09-07

The original staging reason has been re-evaluated against current evidence,
not historical assumptions:

- ordinary same-repository PR evidence exists on PR #158 exact HEAD
  `7ca8cf38ed9bdc19eeb59215c4de5962aa312d70`; Hardening audit run
  `34082389808` completed successfully on that HEAD;
- merged-main evidence exists on exact `main`
  `9f882f92f7e875ea9c1b14207a6eb020568ec750`; push run `34092433264`
  completed successfully;
- an authenticated external hardening readback on that same `main` confirmed
  the live `main protection` and `release tag immutability` rulesets plus
  repository merge settings still match the checked-in policy;
- the repository currently has no forks, so there is no unprivileged fork-PR
  execution proving this additional context cannot wedge a first external
  contribution.

Under the proof-obligation gate, missing fork evidence is not promoted to a
claim of safety. `Hardening audit` therefore remains staged even though its
same-repository PR/main reliability and the external live-readback procedure
are proven. The explicit re-evaluation trigger is the first unprivileged fork
PR: the context must be emitted for that PR's exact HEAD, complete without
privileged repository state or secrets, and pass. Promotion then requires a
fresh ordinary-PR/main reliability read, authoritative live administration
readback, and one reviewed atomic change to both `.github/merge-gate-policy.json`
and the live `main protection` required contexts. No long-lived Actions admin
credential is required merely to reach parity.

Specialized runtime evidence workflows are not global required contexts.
Native-editor/JCEF probes are path/task-specific evidence producers whose
availability depends on the affected subsystem and migration phase. They remain
review obligations when applicable, not checks that every repository-only PR
must emit.

## Issue metadata automation disposition

The 2026-09-20 Hardening Reassessment supersedes the earlier assumption that
Issue Forms alone cover the repository's normal issue-creation paths. API/agent
creation is now routine and bypasses Issue Form default labels.

The repository still rejects generic natural-language classification. The issue
reconciler owns only explicit title protocol already used by maintained work:

- `fix|bug` -> `type:bug`;
- `feat|feature` -> `type:feature`;
- `security` -> `type:security`;
- `docs` -> `type:docs`;
- `research|rfc|adr|audit|design|spike|architecture` -> `type:research`;
- `task|build|ci|test|refactor|chore|perf|release|track|epic|hardening|governance` -> `type:task`.

An explicit protocol prefix may repair a conflicting managed type label.
Titles outside that protocol are diagnostic-only: existing maintainer metadata
is preserved and no body/NLP inference is attempted. Area labels remain outside
the issue classifier because arbitrary issue text is not authoritative path
evidence.

The write boundary is isolated in `.github/workflows/issue-labeler.yml` with
job-local `issues:write`. It executes the classifier from the trusted default
branch, disables checkout credential persistence, and never executes issue
title/body as code. Manual backlog reconciliation is explicitly opt-in
(`backfill=false` by default) and review-first (`dry_run=true` by default);
a mutating backfill is rejected unless the dispatch itself targets the default
branch. Static negative-contract tests run inside the credential-less
`Hardening audit` job so those operator-safety guards cannot silently regress.

### Hardening Reassessment — 2026-09-20

Current external guidance and repository operation were re-read rather than
treating the prior hardening completion as permanent evidence.

New controls are deliberately classified before promotion:

- `Dependency Review` is **required**. PR #234 proved the ordinary-PR
  producer, and PR #248 reconciled the checked-in merge-gate policy after the
  live `main protection` ruleset already required the same context. The
  2026-09-22 authoritative readback confirmed ruleset `22021368`, GitHub
  Actions integration id `15368`, and no bypass actors. Dependency Review is
  PR-diff-scoped, so merged-main execution is N/A; post-merge proof is the
  policy/ruleset readback rather than a nonexistent main check run.
- CodeQL for JavaScript/TypeScript and GitHub Actions is advisory security
  analysis. A Java/Kotlin leg was exercised during this reassessment with both
  `build-mode:none` and `autobuild`; the current CodeQL v4.38.1 / CLI 2.27.0
  run reported the maintained Kotlin toolchain as too new and could not produce
  a database. That surface is therefore an explicit temporary capability
  exception, not a false green. Required Qodana `Inspect code`, Build/Test and
  Plugin Verifier remain the Kotlin/JVM authority. Revisit when CodeQL explicitly
  supports the maintained Kotlin version.
- the existing `Hardening audit` remains staged for its already documented
  reason: unprivileged fork-PR execution evidence is still missing. This
  reassessment does not weaken or bypass that proof obligation.

Live repository/security-feature state remains an authoritative exit-audit
input. Missing live evidence is not inferred from green repository workflows.

## Credential contract

No long-lived administration credential is required by the default workflow.
For an explicit automated or local live readback, use a repository-scoped,
read-only administration credential (or equivalent GitHub App installation
credential) with only the permissions needed for the readback:

- repository `Administration: read` for rulesets and repository settings;
- repository `Issues: read` for the label catalog;
- the automatically available repository metadata read access.

It must not have contents write, tag/release mutation, publication, package,
or workflow-write permission. The workflow never prints the value. When the
audit script is run with a credential, it supplies the named credential
explicitly to each `gh` API invocation; an ambient `GH_TOKEN`, `GITHUB_TOKEN`,
or local gh login cannot be used as a fallback for authoritative readback.

If optional workflow live readback is explicitly enabled without its scoped
credential, that job fails rather than claiming an authoritative pass. If the
optional path is not enabled, it is skipped and the credential-less audit
remains the scheduled/manual evidence for its narrower scope. Missing
credential and ruleset-drift fixtures are exercised by
`.github/scripts/hardening-audit-test.sh` without changing repository secrets
or live rulesets.

An authoritative live pass means that a designated admin-capable readback
successfully read the live state and every expected comparison passed. Static
workflow analysis and fixture validation are supporting evidence; they are not
substitutes for live GitHub administration evidence. Promotion of `Hardening
audit` into the required contexts remains a separate reviewed
policy-and-ruleset decision.
