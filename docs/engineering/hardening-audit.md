# Hardening audit operational contract

The `Hardening audit` workflow has two trust modes. The `audit` job performs
static, producer, workflow-security, and fixture checks without an
administration credential on pull requests, pushes, schedules, and manual
runs. Its `Hardening audit` context is still staged and is not a required
`main` context.

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
labels referenced by automation, and repository merge settings against
`.github/merge-gate-policy.json`.

Ruleset identity is fail-closed: the live ruleset list must contain exactly
one entry for each canonical name. Zero matches, duplicate names, or a
matched entry without an id stop the readback. The main ruleset must have
`target=branch` and exactly `include=["~DEFAULT_BRANCH"]`, while the release
ruleset must have `target=tag` and exactly `include=["~ALL"]`; both must have
`exclude=[]`.

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
