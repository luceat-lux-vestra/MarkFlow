# Crash-Recoverable Agent Work

This document defines how an agent resumes non-trivial MarkFlow repository work after a session, connection, harness, or process interruption.

It governs repository-operation recovery. It does not change MarkFlow runtime/product crash-recovery semantics.

## Goal

A fresh agent session must be able to determine the exact safe continuation point from durable MarkFlow/GitHub state without depending on prior chat memory or a hand-written handoff.

The protocol is intentionally small:

```text
durable policy
  + compact checkpoint
  + authoritative GitHub state
  + reconciliation
  = safe resume
```

Documentation alone is insufficient for a lost-response window. A checkpoint may say what the agent last proved, but GitHub must be re-read to establish whether an external mutation actually committed.

## Authority hierarchy

For execution recovery, use this order:

1. current GitHub execution state;
2. current repository policy/documentation;
3. the active Issue checkpoint;
4. conversation/session memory.

GitHub owns observable execution truth for:

- Issue state and comments;
- branches and refs;
- commit SHAs;
- pull requests;
- workflow/check results;
- reviews and review threads;
- merge state;
- default-branch state.

The checkpoint is a resume hint. It never overrides current GitHub state.

Conversation memory can accelerate discovery but is never proof of current state.

## One active production PR

Existing MarkFlow work discipline remains authoritative.

Before starting new non-trivial work:

1. fresh-read open pull requests;
2. if an active production PR exists, recover and finish that PR before creating another;
3. only start a new work unit when no existing production PR owns the active work.

A checkpoint never authorizes parallel production work.

## Durable work anchor

Use the owning GitHub Issue as the durable work anchor.

For recoverable agent work, maintain exactly one mutable checkpoint comment containing this marker:

```text
<!-- agent-resume-checkpoint:v1 -->
```

Do not store the mutable checkpoint in a production branch file. Updating such a file would move HEAD and would invalidate exact-HEAD review/CI evidence.

## Checkpoint schema

Use this compact shape:

```yaml
schema: agent-resume/v1
repository: luceat-lux-vestra/MarkFlow
work_item: <issue-number>
work_unit: <stable-logical-id>

phase: selected | implementation-durable | pr-open | validating | merge-ready | merged | housekeeping | done

branch: <branch-or-null>
pr: <pr-number-or-null>

expected:
  branch_head: <sha-or-null>
  pr_head: <sha-or-null>
  base: main

last_verified:
  at: <timestamp>
  authority: github

next_safe_action: <short-semantic-action>

last_mutation:
  kind: <operation-or-none>
  outcome: verified | unknown | none

stop_condition: one-production-pr-plus-housekeeping
```

The checkpoint records identity and the last durable boundary. It should not duplicate volatile CI/check state.

Do not use model IDs, chat IDs, hidden reasoning, or local process IDs as durable foreign keys.

## Checkpoint boundaries

Update the checkpoint only after a durable postcondition has been verified.

Useful boundaries are:

1. work item selected;
2. implementation reaches a durable candidate commit/ref;
3. PR existence is verified;
4. final candidate HEAD is established;
5. merge result is verified;
6. post-merge housekeeping is verified;
7. work unit is done.

Do not checkpoint every tool call.

If a mutation succeeds but the checkpoint update does not happen, leave the checkpoint stale. Recovery must detect and reconcile that condition rather than assuming the mutation failed.

## Session-start protocol

A fresh agent session must perform these steps before mutating an interrupted work unit:

```text
1. read AGENTS.md and this document
2. fresh-read open production PRs
3. identify the active Issue and checkpoint
4. fresh-read Issue / branch / PR / HEAD / base
5. read checks, reviews and threads when they affect the next decision
6. compare checkpoint expectation with current GitHub state
7. classify the relationship
8. derive exactly one safe next action
9. mutate only after the required preconditions are proven
```

Use one of these classifications:

### MATCH

Checkpoint identities and current GitHub state agree.

Continue from `next_safe_action` after rechecking any volatile preconditions.

### CHECKPOINT_STALE_BUT_RECONCILABLE

GitHub is ahead of the checkpoint, but the newer state is uniquely attributable to the same work unit.

Examples:
- checkpoint says no PR, but exactly one PR exists for the checkpoint branch;
- checkpoint records HEAD A, but the same branch/PR is now HEAD B and the change can be attributed to the active work.

Do not replay the already-completed mutation. Reconcile the checkpoint only after the newer state is verified.

Any HEAD movement invalidates prior exact-HEAD evidence unless current repository policy explicitly permits reuse for the same exact SHA and producer.

### EXTERNAL_STATE_MISSING_RETRYABLE

The checkpoint expected an object/mutation, current authority proves it is absent, and retry preconditions remain valid.

Retry only after proving absence and duplicate safety.

### CONFLICT

Current state is valid but not safely attributable to the checkpointed work unit.

Examples:
- unexpected branch movement by another actor;
- multiple plausible PRs;
- base/ownership changed;
- concurrent work violates the one-PR rule.

Stop mutation until ownership is reconciled.

### UNKNOWN

Required authority cannot be read or available evidence cannot establish the safe state.

`UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE = FAIL`.

## Mutation protocol

Every material external mutation follows:

```text
fresh precondition read
  -> bounded mutation
  -> authoritative read-back
  -> classify postcondition
  -> checkpoint only after verified postcondition
```

The tool/API response is useful evidence but is not the sole completion proof where a lost-response window matters.

### Create operations

For branch/PR/issue-like creation:

- use a stable deterministic identity where possible;
- on resume, search/read current authority before retrying;
- never create a second object solely because the checkpoint did not advance.

For this repository, the work Issue and deterministic branch name normally provide enough identity to recover a PR.

### Push/commit/ref movement

After ref movement:

- fresh-read the branch/ref;
- record the verified SHA at the next checkpoint boundary;
- if later HEAD differs, treat old HEAD-specific evidence as stale.

### Merge

Before merge:

- re-read exact PR HEAD;
- satisfy the normal strict merge gate;
- pin the expected reviewed SHA.

After merge:

- re-read the PR and prove it is merged;
- verify the resulting commit/tree is on `main`;
- verify required post-main checks/state;
- only then checkpoint `merged`.

If the merge request response is lost, do not issue another merge blindly. Re-read the PR and `main` first.

## Failure model

### Read-only interruption

No external mutation needs recovery. Fresh-read and continue.

### Local-only work loss

Uncommitted/unpushed edits are not recoverable from GitHub.

For long implementation work, create logical candidate commits often enough to bound loss. Do not claim recovery of ephemeral edits that do not exist durably.

### Lost response after remote mutation

This is the primary target failure.

The next session must infer outcome from authoritative state, not from the stale checkpoint.

### Stale checkpoint / HEAD movement

Current branch/PR HEAD wins.

Establish why it moved. If ownership is clear, reconcile and invalidate stale evidence. If ownership is unclear, classify `CONFLICT` or `UNKNOWN`.

### Concurrent actor

Do not apply last-write-wins reasoning. Fresh-read and prove that the active work unit still owns the state before mutation.

### Partial multi-step work

Prefer sequences whose intermediate states are discoverable.

On resume:
- recover existing objects;
- complete only missing steps;
- do not duplicate already-completed mutations.

### Asynchronous CI/review transition

Do not mirror volatile CI state in the checkpoint.

When CI/review matters, read current results for the current exact HEAD.

### Authority unavailable

Fail closed for decisions that depend on unavailable state.

### Missing/corrupt checkpoint

The checkpoint is not a single point of truth.

Reconstruct from Issue, deterministic branch, PR, and GitHub state when there is one unambiguous work unit. If there are multiple plausible states, stop fail-closed.

## Exact-HEAD evidence

This protocol does not alter MarkFlow's exact-HEAD rule.

- review/evidence belongs to the exact reviewed PR HEAD;
- a stale checkpoint cannot preserve evidence for a superseded HEAD;
- current PR HEAD is authoritative;
- CI green alone is never sufficient;
- deterministic failures are classified and fixed rather than hidden by blind reruns.

## Post-merge recovery

A work unit is not done at `merged`.

Resume and complete:

1. merged PR read-back;
2. expected result on `main`;
3. required post-main checks/status;
4. linked Issue/tracker/status housekeeping;
5. checkpoint `done`;
6. stop.

The configured stop condition remains one production PR plus housekeeping.

## Privacy and public-repository safety

Checkpoint data must be safe for the repository's visibility.

Do not store:
- credentials or tokens;
- private prompts/transcripts;
- hidden reasoning;
- personal or company-confidential details;
- private repository provenance that does not belong in this public project.

Use only the minimum operational identity needed to resume safely.

## Pilot evaluation

Issue #326 owns the MarkFlow pilot.

Stage A deliberately injects a stale-checkpoint failure:

1. checkpoint `implementation-durable`;
2. create and verify the draft PR;
3. intentionally do not checkpoint `pr-open`;
4. end the session;
5. a fresh session must discover the existing PR and reconcile without duplication.

Stage B applies the protocol to one normal product PR with real asynchronous CI/E2E before the MarkFlow pilot is considered successful.

Do not generalize this pilot to other repositories until the declared portability gate is satisfied.
