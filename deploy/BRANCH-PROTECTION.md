# Branch Protection & Required Checks — note for the repo owner

This repo follows gitflow-lite (CLAUDE.md §6): `main` is release/stable and
**PR-only**; `develop` is the integration branch; feature work branches off
`develop`. Configure GitHub branch protection to enforce that the CI gates in
`.github/workflows/ci.yml` pass before merge.

## Recommended settings

### `main` (strictest — release branch)
- Require a pull request before merging (no direct pushes — CLAUDE.md §6, §12).
- Require **1+ approving review**; dismiss stale approvals on new commits.
- Require status checks to pass before merging; **require branches up to date**.
- Required checks (job names below).
- Require conversation resolution before merging.
- Include administrators (no bypass).
- Restrict who can push (PRs from `develop` only).

### `develop` (integration branch)
- Require a pull request before merging (or fast-forward from short-lived features).
- Require status checks to pass before merging.
- Required checks (job names below).

## Which checks to mark "required"

Add these as required status checks. Names match the `name:` of each job in
`ci.yml`:

| Check (job name) | Always required? | Notes |
|---|---|---|
| `Backend (build + test)` | **Yes** | backend is always present on `develop`/`main` |
| `Backend SAST (CodeQL)` | **Yes** | SAST gate (CLAUDE.md §5) |
| `Container build + Trivy scan` | **Yes** | container + dependency scan gate |
| `Web Admin (Angular)` | Conditional | required **once `web-admin/` is merged** to `develop` |
| `Mobile (Flutter)` | Conditional | required **once `mobile/` is merged** to `develop` |

> Important nuance about conditional jobs: a job guarded by
> `if: needs.changes.outputs.<app> == 'true'` is **skipped** (not run) when its app
> dir is absent. GitHub treats a *required* check that never runs as **pending**,
> which can block a PR. So **do not mark `Web Admin` / `Mobile` as required until
> their directories actually exist on the target branch.** Add them to the required
> list in the same PR that merges the app in. The backend/container/SAST checks are
> safe to require now because `backend/` is always present.

## Suggested rollout order
1. Now: require `Backend (build + test)`, `Backend SAST (CodeQL)`,
   `Container build + Trivy scan` on both `develop` and `main`.
2. When `web-admin/` merges: add `Web Admin (Angular)` to required checks.
3. When `mobile/` merges: add `Mobile (Flutter)` to required checks.

## Secrets (Settings → Secrets and variables → Actions)
Only needed for image push/deploy — CI's build/test/SAST/scan run without them:
- `REGISTRY`, `REGISTRY_USERNAME`, `REGISTRY_PASSWORD`.

Keep an `prod` GitHub Environment with required reviewers for any future deploy job.
