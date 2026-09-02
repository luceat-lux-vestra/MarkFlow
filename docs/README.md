# MarkFlow Documentation

This directory contains the maintained engineering documentation for MarkFlow.

Bootstrap-era files under `plans/` are historical context unless an active issue or accepted decision explicitly promotes them. Before the #52 audit/design gate, current code and tests are evidence rather than target-architecture authority; authority is established by the approved target architecture, accepted ADRs, and the code/tests that implement those contracts.

## Architecture

- `architecture/README.md` — architecture boundaries and decision process
- `architecture/adr-template.md` — ADR template for significant decisions
- GitHub issue #52 — architecture-leap process, fresh-main audit, and target-architecture design gate

## Engineering

- `engineering/development-process.md` — definition of ready/done, PR and review workflow
- `engineering/testing-strategy.md` — risk-based test/evidence expectations
- `../CONTRIBUTING.md` — contributor workflow and local validation
- `../AGENTS.md` — engineering rules for coding agents and reviewers

## Security

- `../SECURITY.md` — vulnerability reporting and trust boundaries

## Release

- `release/process.md` — separate release/publication gate

## Project governance

- `../GOVERNANCE.md` — decision-making and maintainer responsibilities
- `.github/pull_request_template.md` — PR evidence contract
- `.github/ai-review-prompt.md` — strict exact-final-HEAD review prompt
- GitHub issue #54 — repository-hardening Epic; #51, #60, and #61 own governance, CI, and release tracks
- GitHub issue #52 — separate runtime architecture Epic; its initial execution child issues are intentionally created only after the fresh-main audit and target-architecture design

## Documentation rule

Documentation is evidence, not decoration. If implementation changes a contract, update the authoritative document or ADR in the same change. Do not preserve stale claims merely because they were previously written down.
