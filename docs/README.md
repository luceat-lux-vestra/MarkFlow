# MarkFlow Documentation

This directory contains the maintained engineering documentation for MarkFlow.

The Architecture Leap (#52/#84/#156) and the repository/release hardening tracks (#54/#60/#61) are completed. Their issues, ADRs, migration inventory, and convergence evidence remain useful provenance, but they are not active-phase instructions. Current runtime behavior is summarized in `../README.md`; current architecture authority starts with the product contract and accepted ADRs below.

## Architecture

- `architecture/README.md` — current architecture boundaries, authority order, and decision process
- `architecture/0001-native-authority-projection-architecture.md` — accepted native-source-authority architecture
- `architecture/0002-mermaid-katex-renderer-continuity.md` — accepted renderer-continuity decision plus later implementation notes
- `architecture/leap-migration-inventory.md` — completed Leap migration inventory and final disposition record
- `architecture/adr-template.md` — ADR template for future significant decisions

## Product contract

- `product/leap-capability-fidelity-contract.md` — authoritative capability, source-fidelity, fallback, state, and compatibility contract established during Leap and retained after completion
- `product/image-file-import-contract.md` — image import/insertion contract
- `../fixtures/markdown-fidelity/` — shared machine-readable baseline corpus; its validator checks fixture integrity only, not runtime conformance

## Engineering

- `engineering/development-process.md` — definition of ready/done, PR and review workflow
- `engineering/agent-recovery.md` — crash-recoverable agent checkpoint and reconciliation protocol
- `engineering/testing-strategy.md` — risk-based test/evidence expectations
- `engineering/leap-convergence-gate.md` — the convergence evidence contract established for #156 and retained as a regression/release guard
- `engineering/hardening-audit.md` — repository hardening/readback contract
- `../CONTRIBUTING.md` — contributor workflow and local validation
- `../AGENTS.md` — engineering rules for coding agents and reviewers

## Security

- `../SECURITY.md` — vulnerability reporting and trust boundaries

## Release

- `release/process.md` — maintained release/publication gate implemented under completed Track #61
- `release/recovery.md` — fail-closed recovery for ambiguous/partial Marketplace publication

## Governance and history

- `../GOVERNANCE.md` — decision-making and maintainer responsibilities
- `../plans/` — historical bootstrap planning documents; not current implementation guidance
- GitHub issues #52, #54, #60, #61, #78–#84, #139–#156 — completed design, migration, hardening, and convergence records referenced by maintained documents

## Documentation rule

Documentation is evidence, not decoration. If implementation changes a contract, update the authoritative document or ADR in the same change. Historical documents must be clearly labeled as historical; do not preserve stale current-state claims merely because they were previously written down.
