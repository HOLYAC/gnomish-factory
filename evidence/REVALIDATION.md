# Current-Byte Revalidation: Gnomish Factory on Base 2cb47c2

- **Revalidated Base Commit**: `2cb47c2b9b057ebe71b27376e6433cc7122acf2f`
- **Revalidated Base Tree**: `cfcb2db8a7f0d9504e9c2a94a5a0926b8a715d20`
- **Original Audited Commit**: `7727344232d339867559ee968b4f695bf99f6a85`
- **Revalidation Date**: 2026-09-30
- **Revalidation Lanes**:
  1. `refresh-pagination-fence`: Tracker pagination limits, NFF push fence vs local durability split
  2. `refresh-judge`: Judge argv rendering, permission mode fences, verdict extraction, environment caching
  3. `refresh-unaffected`: Sandbox core boundaries, git remote history protection, shape recovery ownership

---

## 1. Summary of Changes in Commit 2cb47c2

Commit `2cb47c2b9b057ebe71b27376e6433cc7122acf2f` merged PR #71 (`fix: close the operator blockers found in the first live runs`), introducing 142 changed files. The changes materially addressed:
1. **Agent CLI Permission Hardening**:
   - Added `AgentRole` enum (`EXECUTOR("acceptEdits")`, `JUDGE("dontAsk")`).
   - `AgentCommandLine.fromRenderedFlags` now mandatorily prepends/appends `--permission-mode dontAsk` and `--strict-mcp-config` when launching a judge round.
   - Enforced by `AgentCommandLineSpec`.
2. **Interrupt & Cancellation Handling**:
   - Added `GithubCallInterruptedException` and interrupt cleanup in `GithubClaimLease` (FR11).
   - Added interrupt handling in `Reaper` and `StandingReaperInterruptedStopSpec`.
3. **Delivery Scoping**:
   - Delivery scoped to task's own history via `StartedCommitOwnership` and `DeliveryAncestrySpec`.
4. **Sandbox & CLI Polish**:
   - `BindingProperties` binding example fix; CLI argument parsing strictness.

Crucially, commit 2cb47c2 **did not** modify:
- Unpaginated `/comments?per_page=100` queries in GitHub tracker implementations.
- Non-throwing `BestEffortPush.pushBestEffort` semantics.
- First-fence and first-JSON parsing in `JudgeVerdictExtractor`.
- Container sandbox L4/SNI egress boundaries.

---

## 2. Revalidation Matrix

| Finding ID | Classification on 7727344 | Status on 2cb47c2 | Physical Evidence & Analysis on 2cb47c2 |
|---|---|---|---|
| **GNOMISH-CLAIM-01** | CONFIRMED | **CONFIRMED** | `GithubClaimLease.java:176` still calls `/issues/{n}/comments?per_page=100` without following Link headers. `ClaimPaginationRaceCourt` WireMock reproducer demonstrates two concurrent claimers beyond comment 100 both return `ClaimResult.Acquired`. |
| **GNOMISH-FENCE-01** | CONFIRMED | **CONFIRMED** | `BestEffortPush.java:73,110` remains `void` and logs failures with `PushOutcome.describe(...)` without throwing. NFF rejection protects remote origin ref but does not fail-close local round execution. |
| **GNOMISH-FENCE-02** | CONFIRMED | **CONFIRMED** | `GithubAbortFactsReader.java:80` and `GithubTaskFetcher.java:111` read truncated comments (per_page=100). When an issue has >100 comments, `fetchTask` returns stale holder, defeating `RevocationCheckingAttemptPersistence:151,185`. |
| **GNOMISH-TRACKER-02** | CONFIRMED | **CONFIRMED** | `GithubHeartbeat.java:98`, `GithubStaleClaimRemoval.java:163`, `GithubIndexRepair.java:100` all retain unpaginated 100-comment reads, causing healthy workers with claims >100 to self-fence on `ClaimGone`. |
| **GNOMISH-JUDGE-01** | CONFIRMED | **CONFIRMED** | `JudgeVerdictExtractor.java:74-81,127-149` retains `FENCE.matcher(text).find()` scanning for the first code fence and first balanced JSON block. Pass smuggling via preliminary code fences confirmed by `JudgeVerdictExtractorSpec`. |
| **GNOMISH-JUDGE-02** | CONFIRMED | **CONFIRMED** | Non-JSON preliminary code fence causes JSON syntax error, emitting `Verdict.CannotVerify` and prematurely short-circuiting `JudgeVoting.java:82`. |
| **GNOMISH-JUDGE-03** | CONFIRMED | **CHANGED** | **Argv omission confirmed; permission boundary changed**: `AgentInvocationOptions.renderForJudge` still omits `--allowedTools` on empty intersection (`AgentInvocationOptionsSpec:268-278`), while `AgentCommandLine.fromRenderedFlags` now adds role-fixed `--permission-mode dontAsk` and `--strict-mcp-config`. Live successful `Write`/`Edit`/`Bash` execution under this current argv was not exercised and is not claimed. |
| **GNOMISH-JUDGE-04** | CONFIRMED | **CONFIRMED** | `FreshJudgeEnvironments.java:56-63` caches and reuses the exact same environment instance across judge votes for the same attempt commit SHA. |
| **GNOMISH-SANDBOX-01** | CONFIRMED | **CONFIRMED** | Shipped core egress guard (`EgressGuardConfig.java:53`, `EgressGuard.java:92`) enforces L4/SNI hostname allowlisting with `data.ignore_connection = True`. Hostname allowlisting permits any endpoint on that host; L7 path filtering is not in shipped core. |
| **GNOMISH-SANDBOX-02** | DISPROVED | **DISPROVED** | In-box container host escape / docker daemon socket access remains physically disproved. Containers use single per-task volume (`/gnomish/work`), no docker socket, non-root execution (UID 1000), lexical path normalization. `BindingResolverSpec` passes. |
| **GNOMISH-GIT-01** | DISPROVED | **DISPROVED** | Zombie force-push overwrite of remote history remains physically disproved. Git commands never supply `--force` (`NoForcePushGuardSpec`), and remote origin rejects non-fast-forward updates (`DeliveryAncestrySpec` passes). |

---

## 3. Detailed Analysis of Changed Finding (GNOMISH-JUDGE-03)

### The Pre-2cb47c2 Claim
The original audit found that when a stage manifest specified only write tools (e.g. `['Write', 'Bash']`) or an empty list `[]`, the intersection with `JUDGE_READ_ONLY_TOOLS` (`['Read', 'Grep', 'Glob']`) was empty. `AgentInvocationOptions.addToolListFlag` omitted `--allowedTools` entirely, producing an argv containing only `['--model', '<model>']`. The original risk assessment hypothesized this could lead to unconstrained tool execution.

### The 2cb47c2 Byte State
In 2cb47c2:
1. `AgentInvocationOptions.renderForJudge` still produces `invocationFlags = ['--model', '<model>']` when the read-only intersection is empty (`AgentInvocationOptionsSpec.groovy:275-278`).
2. However, `AgentCommandLine.fromRenderedFlags` receives `AgentRole.JUDGE`, which maps to `permissionMode = "dontAsk"`.
3. The assembled argv launched by the runner is:
   ```
   [<binary>, --print, --model, <model>, --permission-mode, dontAsk, --strict-mcp-config, --output-format, stream-json, --verbose]
   ```
4. `AgentCommandLineSpec` physically verifies that the launched judge argv contains `--permission-mode dontAsk` and `--strict-mcp-config`, and that neither `acceptEdits` nor `bypassPermissions` appears.
5. This new permission fence materially changes the original risk model, but no live provider run physically demonstrated either a successful `Write`/`Edit`/`Bash` or an absolute denial under the exact empty-intersection argv. The finding is therefore **CHANGED**, with write exploitability explicitly unverified.

---

## 4. Verification Evidence & Test Execution

The strongest bounded relevant tests identified by the three revalidation cells were executed against the current worktree on base commit `2cb47c2b9b057ebe71b27376e6433cc7122acf2f`:

```bash
# 1. Audit courts physically re-run on current bytes.
# The two evidence courts were temporarily copied into their Gradle source sets,
# executed, and removed before commit.
./gradlew --no-daemon --max-workers=2 \
  :adapters:github:test --tests 'com.github.oinsio.gnomish.adapter.tracker.github.ClaimPaginationRaceCourt' \
  :adapters:agent:test --tests 'com.github.oinsio.gnomish.adapter.agent.JudgeVerificationCourt'
# Result: BUILD SUCCESSFUL in 18s

# 2. Agent invocation options, launched command-line permissions, and verdict extraction
./gradlew :adapters:agent:test \
  --tests 'com.github.oinsio.gnomish.adapter.agent.AgentInvocationOptionsSpec' \
  --tests 'com.github.oinsio.gnomish.adapter.agent.AgentCommandLineSpec'
./gradlew --no-daemon --max-workers=2 :adapters:agent:test \
  --tests 'com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractorSpec'
# Result: BUILD SUCCESSFUL

# 3. Git delivery ancestry and remote-history protection
./gradlew --no-daemon --max-workers=2 :adapters:git:test \
  --tests 'com.github.oinsio.gnomish.adapter.git.DeliveryAncestrySpec'
# Result: BUILD SUCCESSFUL

# 4. Zombie fence revocation contract
./gradlew --no-daemon --max-workers=2 :application:test \
  --tests 'com.github.oinsio.gnomish.app.ZombieFenceSpec'
# Result: BUILD SUCCESSFUL

# 5. Sandbox binding resolver and ratification boundaries
./gradlew --no-daemon --max-workers=2 :sandbox:core:test \
  --tests 'com.github.oinsio.gnomish.sandbox.BindingResolverSpec' \
  --tests 'com.github.oinsio.gnomish.sandbox.BindingRatificationSpec'
# Result: BUILD SUCCESSFUL
```

All bounded verification tests passed cleanly.
