---
name: Architecture / behavior change
about: Propose a contract, architecture, lifecycle, compatibility, or security change
title: "architecture: "
labels: ''
assignees: ''
---

## Problem

What concrete problem, failure mode, or architectural constraint is being addressed?

## Current behavior / ownership

Describe the current data flow and resource/state owners. Identify accidental coupling or unclear ownership.

## Proposed contract

Define the desired observable behavior and ownership. Avoid prescribing classes before the contract is clear.

Cover as applicable:

- Markdown/source fidelity
- IntelliJ Document/VFS/undo-redo
- host↔webview protocol and revision/order semantics
- JCEF/editor/project lifecycle and disposal
- failure/recovery behavior
- security/trust boundaries
- supported IDE/API compatibility
- performance/resource bounds

## Alternatives considered

Include the simplest viable alternative and why it is insufficient.

## Migration / deletion plan

Which existing implementation may be deleted? Which behavior/tests/evidence must be retained?

## Evidence required before acceptance

List tests, fixtures, measurements, compatibility checks, or spikes needed to validate the proposal.

## Exit criteria

Define conditions that can be verified on merged `main`.
