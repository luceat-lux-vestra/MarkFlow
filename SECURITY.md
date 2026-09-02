# Security Policy

## Supported versions

MarkFlow is under active pre-release development. Security fixes are applied to the latest supported code on `main` unless a released version is explicitly documented as supported.

A supported-version matrix must be published before treating any release line as maintained.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability.

Use GitHub private vulnerability reporting when available. If the repository UI does not expose private reporting, contact the maintainer through a private channel rather than posting exploit details publicly.

Include:

- affected version or commit;
- impact and realistic attack scenario;
- reproduction steps or proof of concept;
- Markdown/HTML/resource/message payload required to reproduce, minimized where possible;
- suggested mitigation, if known;
- whether the issue or exploit is already public.

Never include production credentials, personal data, private documents, or third-party secrets.

## Security boundaries

Treat Markdown content, raw HTML, external/local resource references, Mermaid input/configuration, JCEF-originated messages, and host↔webview protocol payloads as untrusted input.

Security-sensitive changes must explicitly review:

- raw HTML/script/event/style injection;
- sanitization boundaries;
- navigation and local/remote resource loading;
- JCEF origin/resource serving;
- protocol schema, validation, size bounds, and unexpected messages;
- clipboard and file-system interactions;
- diagnostics/log redaction;
- dependency/supply-chain changes.

Do not weaken trust boundaries merely to make rendering or integration easier.

## Repository control boundaries

Live repository settings are evidence, not assumptions. The current repository exposes Dependabot alerts and has automated security fixes enabled. GitHub default code scanning is not enabled, and secret scanning/push protection are not enabled; these controls must not be described as active until live readback proves otherwise. The private-vulnerability-reporting endpoint is not currently exposed to the repository credential, so reporters must use a private maintainer channel and must not post exploit details in a public issue.

The repository-level Actions setting allows all actions and does not enforce SHA pinning at the platform setting level. Workflow permissions, immutable action references, untrusted-PR boundaries, and drift checks are delivery controls owned by Track #60; this policy does not treat their intended state as already live.

## Disclosure and release

Security fixes require the same exact-final-HEAD review as other changes, plus a separate release/publication decision. Do not publish exploit details before an appropriate remediation/disclosure plan exists.
