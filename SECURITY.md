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

## Post-purge renderer trust boundary

The browser editor, host↔web source-mutation protocol, editor loopback/resource realm, editor CSP/navigation machinery and browser-held local-image capabilities were purged by #154. They are not live production surfaces and must not be revived as generic infrastructure.

JCEF remains only as an optional isolated derived-renderer backend. Its renderer-specific request/network/navigation/filesystem containment is independent of source editing; renderer failure must leave the native `Document`/editor and exact Markdown source usable.

## Repository control boundaries

Live repository settings are evidence, not assumptions. Security/governance documentation must not describe unavailable scanning, secret-protection, branch/ruleset, or workflow controls as active until live readback proves them.

Workflow permissions, immutable action references, untrusted-PR boundaries and drift checks are delivery controls separate from runtime trust architecture.

## Disclosure and release

Security fixes require the same exact-final-HEAD proof-obligation review as other changes plus a separate release/publication decision. Do not publish exploit details before an appropriate remediation/disclosure plan exists.
