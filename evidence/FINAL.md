# Adversarial Audit Report: Gnomish Factory

- **Commit**: `7727344232d339867559ee968b4f695bf99f6a85`
- **Tree**: `27abca489a07d77256ffc054f6e61ad5f839186b`
- **Date**: 2026-09-29
- **Evaluated Worktrees**: 6 decomposed audit lanes executed under Native AGY Teamwork preview.

---

## 1. Executive Summary

We performed an adversarial audit of the pinned Gnomish Factory codebase at commit `7727344232d339867559ee968b4f695bf99f6a85`. The audit evaluated concrete code paths across tracker leases, heartbeat & reaper mechanics, git lifecycle crash consistency, container sandbox isolation, deterministic & judge verification, and reusable mechanisms.

### Core Verdict
1. **Broken Invariant (CONFIRMED)**: The single-holder mutual exclusion guarantee fails when an issue has >100 comments. `GithubClaimLease.listComments` queries `/issues/{n}/comments?per_page=100` without following pagination Link headers. Beyond comment 100, boundary markers and competing claims are invisible, causing two concurrent claimers to both return `ClaimResult.Acquired` with dual execution on the same task branch.
2. **Compound Fence Bypass (CONFIRMED)**: Contrary to ADR 0002 claims, `BestEffortPush` non-fast-forward rejection is remote-origin protection only, not a hard logical fence—`BestEffortPush.pushBestEffort` is void; on a nonzero push command result it calls `PushOutcome.describe(...)`, logs `WARN` when outcome text is non-null, and returns normally without throwing. `ZombieFenceSpec` depends entirely on the post-persist tracker ownership check (`tracker.fetchTask()`). In the >100-comment truncation scenario, `fetchTask` reads stale page 1 comments, sees the old holder as current, and allows a zombie worker to continue executing diverging local rounds.
3. **Judge Verification Smuggling (CONFIRMED)**: `JudgeVerdictExtractor` extracts the *first* markdown code fence and scans for the first balanced JSON block from text start. If a judge model quotes preliminary mock configs, test cases, or discussions containing `{"passed": true}` prior to its final verdict, the extractor emits `Verdict.Pass()`, ignoring a terminal failure and advancing failing work.
4. **Resilient Boundaries (CONFIRMED/DISPROVED)**: The container sandbox strictly prevents host escapes (no `/var/run/docker.sock`, no host mounts, lexical traversal checks, non-root execution fail-closed). Git remote branch history cannot be overwritten (no `--force`, non-fast-forward push rejected by origin).

---

## 2. What Actually Breaks

### A. The >100-Comment Pagination Race and Propagation
- **Root Cause**: `GithubClaimLease.listComments` (`adapters/github/.../GithubClaimLease.java:185`) executes:
  ```
  GET /repos/{owner}/{repo}/issues/{n}/comments?per_page=100
  ```
  It does not follow GitHub pagination Link headers.
- **Physical Reproduction**: `ClaimPaginationRaceCourt.groovy` sets up an issue with 100 non-marker comments on page 1, a boundary marker at index 101, and two competing claims from Instance A (id 102) and Instance B (id 103) on page 2.
  - Both instances fetch page 1 (`comments?per_page=100`).
  - `GithubClaimWindow` parses page 1: it contains neither instance's claim nor the boundary marker.
  - For both instances, `window.winnerAgainst(ownCommentId)` returns `Optional.empty()`.
  - Both fall back to `winnerId = ownCommentId` and return `ClaimResult.Acquired(new ClaimEpoch(ownId))`.
- **Propagation across Subsystems**:
  - **Heartbeat Blinding**: `GithubHeartbeat.resolveClaim` uses the same unpaginated `/comments?per_page=100`. When active claims exist at index >= 101, `resolveClaim` returns `Optional.empty()`, falsely emitting `BeatResult.Lost(ClaimGone)` and self-fencing healthy live workers.
  - **Reaper Blinding**: `GithubStaleClaimRemoval.reReadThread` reads only page 1, operating on obsolete timestamps and failing to observe post-boundary tenures.
  - **Ownership Fetch Blinding**: `GithubTaskFetcher.fetchTask` fetches only page 1, returning stale holder tenures.

### B. The Git Fence vs. Local Durability Split Authority
- **The Defect**: `BestEffortPush.pushBestEffort` (`adapters/git/.../BestEffortPush.java:73-115`) is void. On a nonzero git push result, it calls `PushOutcome.describe("push", result)`, logs `WARN OperatorEvent.PUSH_FAILED` when outcome is non-null, and returns normally without throwing any exception.
- **The Durability Split**: `GitAttemptPersistence` treats the local git commit as the durability point. Because push failure does not throw, `GitAttemptPersistence.persist` succeeds locally!
- **Zombie Fence Defeat**: `ZombieFenceSpec` asserted that a zombie halts on revocation. However, that test passed only because the stubbed tracker explicitly returned `Working(NEW_HOLDER)`. In real operations on GitHub with >100 comments, `fetchTask` returns stale holder A. Because `BestEffortPush` does not throw, the zombie continues running subsequent rounds locally, producing diverging local commits and uncoordinated external effects.

### C. Judge Verification Pipeline Vulnerabilities
- **Pass Smuggling (`JudgeVerdictExtractor.java:45-72`)**: The extractor parses the first code fence. When a judge model quotes example configurations or test cases containing `{"passed": true}` before issuing its verdict `{"passed": false}`, the extractor emits `Verdict.Pass()`.
- **Premature CannotVerify Taint (`JudgeVerdictExtractor.java:55`)**: When a judge model quotes non-JSON code (Java, Python) with braces before its JSON fence, JSON parsing throws a syntax error, emitting `Verdict.CannotVerify`. In `JudgeVoting`, a single `CannotVerify` immediately short-circuits the voting loop, causing human escalation without consuming stage attempt budgets.
- **Argv Collapse on Empty Read-Only Intersection (`AgentInvocationOptions.java:85-102`)**: When stage manifest specifies only write tools or `[]`, the intersection with `['Read', 'Grep', 'Glob']` is empty, and `--allowedTools` is omitted from the argv.
  *Evidence Boundary*: The policy/argv collapse is physically confirmed by `JudgeVerificationCourt.groovy`. Live network execution against real providers was not executed due to Teamwork anti-recursion policy; the argv collapse itself is confirmed.
- **Shared Container Contamination (`FreshJudgeEnvironments.java:56-65`)**: Reuses the exact same container across multiple votes for the same attempt commit. If tools leak or mutations occur, earlier votes corrupt the environment for subsequent voters.

---

## 3. What Survives Attack

### A. Crash Consistency of Shapes and Recovery Ownership
- `BranchShape` (10 closed records) and `TrackerShape` (10 closed records) provide comprehensive state classification. Every frozen intermediate state maps to exactly one recovery owner (`REAPER`, `HOLDER`, `QUEUE`, `HUMAN`).
- Double-recovery idempotence tests in `TransitionKillPointSpec` prove that running recovery twice produces the identical state as running once.
- Intent-Effect-Receipt ordering ensures terminal park writes a pending marker to the branch before mutating tracker state, and confirms with a receipt after.

### B. Remote Git Branch Immutability
- Disproved the hypothesis that a zombie worker could force-push or overwrite remote branch history. Git commands strictly avoid `--force` (enforced at compile time by `NoForcePushGuardSpec`), and remote origin rejects non-fast-forward updates. Remote branch truth remains intact.

### C. Sandbox Core Container Isolation
- **Shipped Core Boundaries**:
  - Task containers mount strictly a single per-task named volume (`/gnomish/work`).
  - No `/var/run/docker.sock` or host directories are mounted.
  - Container file channels use strict lexical path normalization, forbidding `.git/**` and `..`.
  - Anonymous image volumes are overridden with ephemeral tmpfs (`size=64m, mode=1777`).
  - Container execution requires non-root user (UID 1000); `EnvironmentSelfCheck` verifies this fail-closed.
  - Zero-base child environment scrubs host credentials (`AWS_*`, `GITHUB_*`).
- **Residual Hardening Distinctions (Not Shipped in Core)**:
  - Shipped core egress guard uses mitmdump strictly at L4/SNI/CONNECT level without TLS decryption (`data.ignore_connection = True`).
  - Hostname allowlisting permits the network path to arbitrary endpoints on that host; successful write/exfiltration operations (such as git push to an external repository or posting to an issue/gist) depend on attacker-available or foreign credentials, or unauthenticated writable endpoints.
  - L7 path filtering, TLS termination, local AI gateway credential scrubbing, and microVM isolation are documented future proposals (`add-sandbox-hardening`, Change B), **not shipped in core**.

---

## 4. What is Worth Stealing for Omnivore

From `evidence/REUSE_MATRIX.json`, we identify five high-leverage primitives for transplantation into Omnivore:

| Mechanism | Gnomish Source Locator | Omnivore Target | Justification / Tradeoff |
|---|---|---|---|
| **Kill-Window Shape Law** | `domain/.../BranchShape.java:17` | Research Fabric phase transitions | Replaces ad-hoc status checking with a closed set of named frozen shapes and double-recovery idempotence tests. Eliminates ambiguous intermediate states. |
| **Self-Fencing on Uncertain Authority** | `application/.../RevocationCheckingAttemptPersistence.java:175` | Long-running subagent/toolbelt loops | Halts mutations when heartbeat confidence decays. Must be strengthened beyond Gnomish by throwing hard exceptions on push failure. |
| **Single Recovery Ownership** | `domain/.../RecoveryOwner.java:12` | Omnivore master reconcile | Maps every broken intermediate shape to exactly one recovery owner, preventing concurrency races between background sweeper and foreground execution. |
| **Fresh-Box Verification** | `sandbox/.../SelfCheckedEnvironment.java:19` | Oracle court verification | Verifies candidate commits in a fresh ephemeral environment pinned to the commit SHA, executing self-checks before running checks and preserving failed environments for forensics. |
| **Asymmetric Tightening Law** | `sandbox/core/.../SandboxReconciler.java:36` | Task manifest validation | Enforces the invariant that untrusted inputs may tighten security constraints (e.g. restrict egress or add checks), but can never loosen host bindings or disable isolation. |

---

## 5. Artifact Index

- **`evidence/PIN.json`**: Initial repository pin at `7727344232d339867559ee968b4f695bf99f6a85`.
- **`evidence/FINDINGS.json`**: Complete structured finding registry with exact source locators and classifications.
- **`evidence/REUSE_MATRIX.json`**: Detailed comparison matrix of Gnomish Factory primitives vs. Omnivore primitives.
- **`evidence/COURTS/`**:
  - `ClaimPaginationRaceCourt.groovy`: WireMock court reproducing dual `ClaimResult.Acquired` on >100 comments.
  - `JudgeVerificationCourt.groovy`: Spock court reproducing pass smuggling, premature CannotVerify, and argv collapse.
  - `claim-pagination-race-court.json`, `git-split-authority-court.json`, `lease-reaper-takeover-court.json`, `sandbox-security-boundary-court.json`, `judge-verification-court.json`.
