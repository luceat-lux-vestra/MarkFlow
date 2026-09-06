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
- minimized Markdown/HTML/resource/renderer/message payload required to reproduce;
- suggested mitigation, if known;
- whether the issue or exploit is already public.

Never include production credentials, personal data, private documents, or third-party secrets.

## Target security boundaries

The accepted architecture removes browser authority from ordinary Markdown editing. Treat these as untrusted input:

- Markdown source and raw HTML;
- external/local resource references;
- clipboard/imported file data;
- Mermaid source/configuration;
- KaTeX/math source;
- derived renderer input/output;
- any message/request boundary of a retained isolated renderer runtime.

Security-sensitive changes must explicitly review:

- raw HTML/script/event/style/active-content containment;
- sanitization/isolation of derived preview without source rewriting;
- local path normalization, traversal/encoded traversal, symlink escape, media type and size/decoded-size bounds;
- explicit external-navigation scheme/user-action policy;
- renderer network/filesystem/navigation isolation;
- Mermaid security configuration;
- renderer request/origin/CSP policy if JCEF is actually retained below the renderer boundary;
- clipboard/file-import user authority, VFS destination/collision/rollback and read-only/non-local cases;
- diagnostics/log redaction and bounded payloads;
- dependency/supply-chain changes.

Opening/editing Markdown must not grant arbitrary filesystem, network, navigation, or active-content authority. Optional renderer failure must leave exact source editing available.

## Migration-period legacy surfaces

Until production cutover/purge, current `main` may still contain JCEF editor messages, host↔web mutation protocol, loopback routes, request filters, CSP/navigation controls and local-image capability tokens.

Those remain live attack surfaces **only while a current production consumer exists**. Changes touching them must preserve current-main fail-closed safety and real-runtime evidence as applicable, but they are not target trust authority and must not be generalized or revived. #154 owns their mandatory deletion after native cutover. If JCEF remains for rendering, renderer-specific containment is designed/proven separately rather than reusing the editor realm by default.

## Repository control boundaries

Live repository settings are evidence, not assumptions. Security/governance documentation must not describe unavailable scanning, secret-protection, branch/ruleset, or workflow controls as active until live readback proves them.

Workflow permissions, immutable action references, untrusted-PR boundaries and drift checks are delivery controls separate from runtime trust architecture.

## Disclosure and release

Security fixes require the same exact-final-HEAD proof-obligation review as other changes plus a separate release/publication decision. Do not publish exploit details before an appropriate remediation/disclosure plan exists.
