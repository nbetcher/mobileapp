# Fork-sync & release pipeline

Automated release pipeline for the **Pebble app fork** (`coredevices.coreapp`).
Implements [HLDD-004](../../../docs/hldd/HLDD-004-release-pipeline.md).

Two workflows:

| Workflow | Role |
|---|---|
| [`.github/workflows/fork-sync.yml`](../../.github/workflows/fork-sync.yml) | sync → merge → tag/release → build → finalize |
| [`.github/workflows/android-release.yml`](../../.github/workflows/android-release.yml) | reusable (`workflow_call`) android-only build + sign |

## What it tracks

Not `master` HEAD — the **released** commit. Upstream ships no tags and no releases, and the store
APK's `versionCode` is not a public commit count, but the changelog is machine-readable:
`tools/pebble_release_cutoff.py` reads the live store version, pulls that version's bullets from
`ndocs.repebble.com/changelog`, and fuzzy-matches them against commit *subjects* in public history.
Author dates survive Core Devices' internal→public rebase-sync (committer dates do not), so the
matched commits give a **release cutoff** — upstream HEAD minus a drift re-measured every run:

```
cutoff_position = branch_head_position - unreleased_commits_beyond_cutoff
```

The changelog table's **column 2** (col 1 = date, col 2 = version, col 3 = notes) is the version
string the pipeline records as `last_synced_version`.

## What it does (daily 21:30 America/Phoenix, or on dispatch)

1. **Disable upstream CI** — `gh workflow disable build.yml release.yml`, every run, idempotent.
   Upstream's `build.yml` runs an iOS job on `macos-15`; `release.yml` fires on `release:
   published` and commits to master, which would corrupt the mirror the first time we cut a release.
2. **Resolve the cutoff** — store version → changelog → fuzzy match. Exit 3 (`date-fallback`,
   fewer than 2 bullets matched) is *accepted* and flagged, not fatal: upstream changelogs are
   often 1–2 bullets, so the ≥2 bar frequently can't be cleared even when the answer is right.
3. **Check the ledger** — if the changelog version equals `last_synced_version`, exit quietly. This
   holds even if that version's build failed: a failed build is left alone, and only a genuinely
   **new** upstream version moves the pipeline.
4. **Merge** the cutoff into `tasker`, in three tiers — see below.
5. **Tap-drift check** (`tap-check.sh`) — greps the `// BRIDGE-TAP: <id>` anchors.
6. **Push** `tasker`, pin `release-track` to the cutoff, fast-forward the `master` mirror.
7. **Tag + release** `v<version>-tasker.<N>`, *before* the build.
8. **Build** via `android-release.yml` → `Pebble_<version>-<commit8>-<counter>.apk`, signed.
9. **Finalize** — attach the APK + `.sha256` on success; on failure delete the **release**, keep
   the **tag**, and strike the version.

## Merge tiers

1. **rerere replay** — `tools/pipeline/rr-cache/` is the pipeline's memory of every conflict a
   human or the AI tier has already resolved. Most runs never get past this.
2. **Gemini** — `google-github-actions/run-gemini-cli` (pinned by SHA) resolves what's left, on the
   runner, mid-merge. Its output is verified (no unmerged paths, no conflict markers, tap anchors
   intact) before anything is pushed, and whatever it resolved is folded back into `rr-cache` so
   tier 1 handles it for free next time. Needs `GEMINI_API_KEY`; without it merges fall through to
   tier 3. Runs with the workflow token **blanked** — it processes upstream-authored text under
   `--yolo`, so it is treated as untrusted input.
3. **Abort** — merge reverted, remote restored, ledger rolled back, `MERGE-FAIL` issue filed with
   the rr-cache preserved as a run artifact. The next tick retries the same version.

## The ledger (`sync-state.json` on the `pipeline-state` branch)

```jsonc
{
  "last_synced_version": "1.8.0.6",   // changelog col 2 — the only sync gate
  "last_synced_cutoff":  "<sha>",     // upstream commit it was cut from
  "previous":            { ... },     // rollback target if the merge fails
  "build_counters":      { "1.8.0.6": 2 },
  "build_strikes":       ["1.8.0.4"], // distinct versions that merged but failed to build
  "status": "built" | "merging" | "build-failed" | "merge-failed",
  "cutoff": { ... }                   // full resolver output: position, drift, method, matches
}
```

**Three strikes.** Three *distinct* versions that merge cleanly but fail to build ⇒ a
`CRITICAL-BUILD` issue and `gh workflow disable fork-sync.yml`. A green build clears the list.
Re-enable with:

```sh
gh workflow enable fork-sync.yml
gh workflow run fork-sync.yml -f reset_strikes=true -f force=true
```

## Scripts (`lib/`)

| Script | Role | Exit code on failure |
|---|---|---|
| `issue.sh` | open/comment the owner-assigned `pipeline` failure issue (de-duped per kind) | — |
| `tap-check.sh` | ⚠ patch-tap anchor grep on `libpebble3/src` | `31` |
| `sign.sh` | `zipalign` → `apksigner sign` → `verify` → `zipalign -c` | `41` |

> `detect.sh`, `rebase.sh` and `publish.sh` are **unused** — they belonged to the removed
> `upstream-sync.yml` (rebase-based, tracked `master` HEAD). Kept pending a decision, HLDD-004 §11.

## Required GitHub Actions secrets

| Secret | Purpose |
|---|---|
| `APP_KEYSTORE_B64` | base64 of the shared `tasker-plugin.p12` keystore (ADR-009) |
| `APP_KEYSTORE_PASSWORD` | keystore (store) password |
| `APP_KEY_ALIAS` | key alias inside the keystore |
| `APP_KEY_PASSWORD` | key password (falls back to the store password if unset) |
| `READ_PACKAGES_ACTOR` | GitHub login for GitHub Packages reads |
| `READ_PACKAGES_TOKEN` | token with `read:packages` for the above |
| `GEMINI_API_KEY` | Google AI Studio key — the AI merge tier |

`GITHUB_TOKEN` (auto-provided) covers releases, issues, and the workflow enable/disable calls via
`permissions: contents: write, issues: write, actions: write`.

> The keystore lives **only** in Actions secrets (+ offline backups). It is decoded onto the
> ephemeral runner at sign time and never logged or committed (HLDD-004 §9).

## Setup

1. **Default branch must be `tasker`.** GitHub resolves `schedule` and `workflow_dispatch` against
   the default branch only — with `master` (the upstream mirror) as default, this workflow can
   neither be dispatched nor fire on cron.
2. **Secrets** — the table above (*Settings → Secrets and variables → Actions*).
3. **State branch** — created automatically on the first run.
4. **Phone** — GitHub Android app (issue/Actions notifications on), and add the fork to Obtainium
   using [`obtainium.json`](obtainium.json), replacing `OWNER/REPO`.

## Running it manually

```bash
gh workflow run fork-sync.yml -f force=true
```

Other dispatch inputs: `version` (override the store lookup), `reset_strikes`, `no_ai_merge`,
`model` (e.g. `gemini-3.1-pro-preview` to escalate a merge), `allow_rewind`, `dry_run` (resolve and
merge locally, push nothing).

To rebuild an existing tag without re-syncing:

```bash
gh workflow run android-release.yml -f ref=v1.8.0.6-tasker.1 -f version_name=1.8.0.6 -f commit_hash=3d3c9971 -f build_counter=2
```
