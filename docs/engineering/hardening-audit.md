# Hardening audit operational contract

The `Hardening audit` workflow has two trust modes. The pull-request and push
`audit` job performs static, producer, workflow-security, and fixture checks
without an administration credential. Its `Hardening audit` context is still
staged and is not a required `main` context.

The scheduled and manual `live-readback` job is the only authoritative live
mode. It runs only for the scheduled default-branch revision or a
`workflow_dispatch` from the repository default branch, and checks out that
default branch explicitly. It reads the live `main protection` ruleset, the
`release tag immutability` ruleset, and the repository labels referenced by
automation, plus the repository merge settings, then compares them with
`.github/merge-gate-policy.json`.

## Credential contract

The live job requires the repository secret `HARDENING_AUDIT_TOKEN`. It is a
repository-scoped, read-only administration credential (or equivalent GitHub
App installation credential) with only the permissions needed for this
readback:

- repository `Administration: read` for rulesets;
- repository `Issues: read` for the label catalog;
- the automatically available repository metadata read access.

It must not have contents write, tag/release mutation, publication, package,
or workflow-write permission. The workflow never prints the value. The audit
script supplies this named credential explicitly to each `gh` API invocation;
an ambient `GH_TOKEN`, `GITHUB_TOKEN`, or local gh login cannot be used as a
fallback for authoritative readback.

If the secret is absent, the live job fails with an unavailable-credential
error. It does not claim an authoritative pass. The offline PR job remains
safe to run because it never receives this secret. Missing-credential and
ruleset-drift fixtures are exercised by
`.github/scripts/hardening-audit-test.sh` without changing repository secrets
or live rulesets.

An authoritative pass means that the named credential successfully read the
live state and every expected comparison passed. Static workflow analysis,
fixture validation, and a local run using a separately supplied credential are
supporting evidence; they are not a scheduled/manual GitHub authoritative
readback. Promotion of `Hardening audit` into the required contexts remains a
separate reviewed policy-and-ruleset decision.
