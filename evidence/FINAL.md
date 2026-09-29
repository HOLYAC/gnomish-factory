# Adversarial Audit Report: Gnomish Factory

- **Original Audited Commit**: `7727344232d339867559ee968b4f695bf99f6a85`
- **Revalidated Base Commit**: `2cb47c2b9b057ebe71b27376e6433cc7122acf2f`
- **Revalidated Base Tree**: `cfcb2db8a7f0d9504e9c2a94a5a0926b8a715d20`
- **Audit Dates**: 2026-09-29 (initial audit), 2026-09-30 (base revalidation)
- **Evaluated Worktrees**: 6 decomposed audit lanes + 3 revalidation lanes executed under Native AGY Teamwork preview.

---

## 1. Executive Summary

We performed an adversarial audit of the Gnomish Factory codebase, originally pinned at commit `7727344232d339867559ee968b4f695bf99f6a85`, and systematically revalidated against current base commit `2cb47c2b9b057ebe71b27376e6433cc7122acf2f` (PR #71: `fix: close the operator blockers found in the first live runs`). The audit evaluated concrete code paths across tracker leases, heartbeat & reaper mechanics, git lifecycle crash consistency, container sandbox isolation, deterministic & judge verification, and reusable mechanisms.

### Core Verdict (Reconciled with Base 2cb47c2)
1. **Broken Invariant (CONFIRMED on 2cb47c2)**: The single-holder mutual exclusion guarantee fails when an issue has >100 comments. `GithubClaimLease.listComments` (`GithubClaimLease.java:176`) queries `/issues/{n}/comments?per_page=100` without following pagination Link headers. Beyond comment 100, boundary markers and competing claims are invisible, causing two concurrent claimers to both return `ClaimResult.Acquired` with dual execution on the same task branch. Commit 2cb47c2 added interrupt cleanup handling but did not add pagination.
2. **Compound Fence Bypass (CONFIRMED on 2cb47c2)**: Contrary to ADR 0002 claims, `BestEffortPush` non-fast-forward rejection is remote-origin protection only, not a hard logical fence—`BestEffortPush.pushBestEffort` (`BestEffortPush.java:73,110`) is void; on a nonzero push command result it calls `PushOutcome.describe(...)`, logs `WARN` when outcome text is non-null, and returns normally without throwing. `ZombieFenceSpec` depends entirely on the post-persist tracker ownership check (`tracker.fetchTask()`). In the >100-comment truncation scenario, `fetchTask` reads stale page 1 comments, sees the old holder as current, and allows a zombie worker to continue executing diverging local rounds.
3. **Judge Verification Smuggling & Permission Boundaries (CONFIRMED / CHANGED on 2cb47c2)**:
   - *Pass Smuggling (CONFIRMED)*: `JudgeVerdictExtractor` (`JudgeVerdictExtractor.java:74-81,127-149`) extracts the *first* markdown code fence and scans for the first balanced JSON block from text start. If a judge model quotes preliminary mock configs, test cases, or discussions containing `{"passed": true}` prior to its final verdict, the extractor emits `Verdict.Pass()`, ignoring a terminal failure and advancing failing work.
   - *Judge Tool-Permission Argv Collapse (CHANGED)*: While `AgentInvocationOptions.renderForJudge` still omits `--allowedTools` on empty intersection (`AgentInvocationOptionsSpec.groovy:268-278`), commit 2cb47c2 introduced role-fixed `AgentRole.JUDGE("dontAsk")` and `--strict-mcp-config` in the actually launched command line (`AgentCommandLine.java:56-67`). This is a new permission fence absent from the original risk model. Live successful `Write`/`Edit`/`Bash` execution was not physically exercised, so write exploitability under the current fence is unverified and not claimed.
4. **Resilient Boundaries (CONFIRMED / DISPROVED on 2cb47c2)**: The container sandbox strictly prevents host escapes (no `/var/run/docker.sock`, no host mounts, lexical traversal checks, non-root execution fail-closed; `BindingResolverSpec` passes). Git remote branch history cannot be overwritten (no `--force`, non-fast-forward push rejected by origin; `DeliveryAncestrySpec` passes).

---

## 2. What Actually Breaks

### A. The >100-Comment Pagination Race and Propagation
- **Root Cause**: `GithubClaimLease.listComments` (`adapters/github/.../GithubClaimLease.java:176`) executes:
  ```
  GET /repos/{owner}/{repo}/issues/{n}/comments?per_page=100
  ```
  It does not follow GitHub pagination Link headers or fetch subsequent pages.
- **Physical Reproduction**: `ClaimPaginationRaceCourt.groovy` sets up an issue with 100 non-marker comments on page 1, a boundary marker at index 101, and two competing claims from Instance A (id 102) and Instance B (id 103) on page 2.
  - Both instances fetch page 1 (`comments?per_page=100`).
  - `GithubClaimWindow` parses page 1: it contains neither instance's claim nor the boundary marker.
  - For both instances, `window.winnerAgainst(ownCommentId)` returns `Optional.empty()`.
  - Both fall back to `winnerId = ownCommentId` and return `ClaimResult.Acquired(new ClaimEpoch(ownId))`.
- **Propagation across Subsystems (Confirmed Surviving on 2cb47c2)**:
  - **Heartbeat Blinding**: `GithubHeartbeat.resolveClaim` (`GithubHeartbeat.java:98`) uses the same unpaginated `/comments?per_page=100`. When active claims exist at index >= 101, `resolveClaim` returns `Optional.empty()`, falsely emitting `BeatResult.Lost(ClaimGone)` and self-fencing healthy live workers.
  - **Reaper Blinding**: `GithubStaleClaimRemoval.reReadThread` (`GithubStaleClaimRemoval.java:163`) reads only page 1, operating on obsolete timestamps and failing to observe post-boundary tenures.
  - **Index Repair Blinding**: `GithubIndexRepair.java:100` similarly reads only page 1.
  - **Ownership Fetch Blinding**: `GithubAbortFactsReader.java:80` and `GithubTaskFetcher.java:111` fetch only page 1, returning stale holder tenures.

### B. The Git Fence vs. Local Durability Split Authority
- **The Defect**: `BestEffortPush.pushBestEffort` (`adapters/git/.../BestEffortPush.java:73,110`) is void. On a nonzero git push result, it calls `PushOutcome.describe("push", result)`, logs `WARN OperatorEvent.PUSH_FAILED` when outcome is non-null, and returns normally without throwing any exception.
- **The Durability Split**: `GitAttemptPersistence` treats the local git commit as the durability point. Because push failure does not throw, `GitAttemptPersistence.persist` succeeds locally!
- **Zombie Fence Defeat**: `ZombieFenceSpec` asserted that a zombie halts on revocation. However, that test passed only because the stubbed tracker explicitly returned `Working(NEW_HOLDER)`. In real operations on GitHub with >100 comments, `fetchTask` returns stale holder A. Because `BestEffortPush` does not throw, the zombie continues running subsequent rounds locally, producing diverging local commits and uncoordinated external effects.

### C. Judge Verification Pipeline Vulnerabilities
- **Pass Smuggling (`JudgeVerdictExtractor.java:74-81,127-149`)**: The extractor parses the first code fence. When a judge model quotes example configurations or test cases containing `{"passed": true}` before issuing its verdict `{"passed": false}`, the extractor emits `Verdict.Pass()`. Confirmed by passing `JudgeVerdictExtractorSpec`.
- **Premature CannotVerify Taint (`JudgeVerdictExtractor.java:74-81`, `JudgeVoting.java:82`)**: When a judge model quotes non-JSON code (Java, Python) with braces before its JSON fence, JSON parsing throws a syntax error, emitting `Verdict.CannotVerify`. In `JudgeVoting`, a single `CannotVerify` immediately short-circuits the voting loop, causing human escalation without consuming stage attempt budgets.
- **Argv Collapse on Empty Read-Only Intersection (`AgentInvocationOptions.java:85-102`) vs. Hard Permission Defense (`AgentCommandLine.java:56-67`, `AgentRole.java:4-19`)**:
  - *Surviving Policy Gap*: When stage manifest specifies only write tools or `[]`, the intersection with `['Read', 'Grep', 'Glob']` is empty, and `renderForJudge` omits `--allowedTools` from rendered invocation flags (`AgentInvocationOptionsSpec.groovy:268-278`).
  - *New Permission Fence on 2cb47c2*: `AgentCommandLine` mandatorily sets `--permission-mode dontAsk` and `--strict-mcp-config` for `AgentRole.JUDGE`. `AgentCommandLineSpec.groovy:86-96` physically verifies those argv tokens and verifies that neither `acceptEdits` nor `bypassPermissions` appears. The live provider behavior of `Write`/`Edit`/`Bash` under this exact empty-intersection argv was not exercised.
  - *Status*: CHANGED. Argv omission is confirmed and the launched argv now contains an additional permission fence. Live write execution remains unverified and is not claimed.
- **Shared Container Contamination (`FreshJudgeEnvironments.java:56-63`)**: Reuses the exact same container across multiple votes for the same attempt commit SHA. If an environment is modified, earlier votes can affect subsequent voters.

---

## 3. What Survives Attack

### A. Crash Consistency of Shapes and Recovery Ownership
- `BranchShape` (10 closed records) and `TrackerShape` (10 closed records) provide comprehensive state classification. Every frozen intermediate state maps to exactly one recovery owner (`REAPER`, `HOLDER`, `QUEUE`, `HUMAN`).
- Double-recovery idempotence tests in `TransitionKillPointSpec` prove that running recovery twice produces the identical state as running once.
- Intent-Effect-Receipt ordering ensures terminal park writes a pending marker to the branch before mutating tracker state, and confirms with a receipt after.
- Commit 2cb47c2 updated `Reaper.java` to support interrupt and stop cancellation without altering recovery ownership semantics.

### B. Remote Git Branch Immutability
- Disproved the hypothesis that a zombie worker could force-push or overwrite remote branch history. Git commands strictly avoid `--force` (enforced at compile time by `NoForcePushGuardSpec`), and remote origin rejects non-fast-forward updates. `DeliveryAncestrySpec` passes on current bytes. Remote branch truth remains intact.

### C. Sandbox Core Container Isolation
- **Shipped Core Boundaries**:
  - Task containers mount strictly a single per-task named volume (`/gnomish/work`).
  - No `/var/run/docker.sock` or host directories are mounted.
  - Container file channels use strict lexical path normalization, forbidding `.git/**` and `..`.
  - Anonymous image volumes are overridden with ephemeral tmpfs (`size=64m, mode=1777`).
  - Container execution requires non-root user (UID 1000); `EnvironmentSelfCheck` verifies this fail-closed.
  - Zero-base child environment scrubs host credentials (`AWS_*`, `GITHUB_*`).
  - Commit 2cb47c2 touched `BindingProperties`, but `BindingResolverSpec` and `BindingRatificationSpec` pass on current bytes. The operator-owned binding / repo-only tightening boundary remains enforced.
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

- **`evidence/PIN.json`**: Current repository pin at base `2cb47c2b9b057ebe71b27376e6433cc7122acf2f`, preserving historical audit pin `7727344232d339867559ee968b4f695bf99f6a85`.
- **`evidence/FINDINGS.json`**: Complete structured finding registry with exact source locators and revalidation status entries.
- **`evidence/REVALIDATION.md`**: Comprehensive current-byte revalidation report on base `2cb47c2b9b057ebe71b27376e6433cc7122acf2f`.
- **`evidence/REUSE_MATRIX.json`**: Comparison matrix of Gnomish Factory primitives vs. Omnivore primitives.
- **`evidence/revalidation-pagination-fence.json`**: Machine receipt for pagination & NFF fence revalidation.
- **`evidence/revalidation-judge.json`**: Machine receipt for judge argv, permissions, and verdict extraction revalidation.
- **`evidence/revalidation-unaffected.json`**: Machine receipt for unaffected audit boundaries and shape recovery revalidation.
- **`evidence/COURTS/`**:
  - `ClaimPaginationRaceCourt.groovy`: WireMock court reproducing dual `ClaimResult.Acquired` on >100 comments.
  - `JudgeVerificationCourt.groovy`: Spock court reproducing pass smuggling, premature CannotVerify, and argv collapse.
  - Structured court findings: `claim-pagination-race-court.json`, `git-split-authority-court.json`, `lease-reaper-takeover-court.json`, `sandbox-security-boundary-court.json`, `judge-verification-court.json`.

---

## 6. Revalidation Summary on Base 2cb47c2

All eleven core audit claims were reconciled against current bytes at base `2cb47c2b9b057ebe71b27376e6433cc7122acf2f`:
- **8 CONFIRMED**: GNOMISH-CLAIM-01, GNOMISH-FENCE-01, GNOMISH-FENCE-02, GNOMISH-TRACKER-02, GNOMISH-JUDGE-01, GNOMISH-JUDGE-02, GNOMISH-JUDGE-04, GNOMISH-SANDBOX-01.
- **1 CHANGED**: GNOMISH-JUDGE-03 (argv omission confirmed; current bytes add role-fixed `dontAsk` plus `--strict-mcp-config`; live write exploitability remains unverified).
- **2 DISPROVED**: GNOMISH-SANDBOX-02 (host escape disproved), GNOMISH-GIT-01 (remote git history overwrite disproved).

All targeted bounded test suites pass cleanly.
