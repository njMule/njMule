---
name: release
description: Release njMule version X.Y.Z from start to finish, with no other step left to the maintainer - release pull request, test run of the packages, merge, tag, approval of the release environment, checks and publication of the GitHub release, Roadmap issue. Use when the maintainer asks for a release of a given version, in any language (for example "let's do release 0.3.0" or "/release 0.3.0").
argument-hint: X.Y.Z
---

<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Release njMule

The maintainer's request for a release of a version is their approval of every step below: pushing
the release branch, opening and merging its pull request, pushing the tag, approving the `release`
environment (which signs the update information) and publishing the release. Ask nothing more:
when the version is missing, or a check below fails, stop, say what is wrong and wait.

`website/developer/releases.mdx` explains what each step and the release workflow do. Commits, the
pull request and the tag carry no AI attribution (CLAUDE.md). Below, `V` is the version asked for
(`0.3.0`, `0.3.0-rc.1`), `P` the tag of the latest published release
(`gh release list --exclude-drafts --limit 1 --json tagName --jq '.[0].tagName'`), `N` the release
pull request and `RUN` a workflow run's id. Long waits run in the background, never as a sleep in
the foreground.

## 1. Check before changing anything

Stop at the first failure:

- `git fetch origin --tags --prune`. `V` is SemVer (`X.Y.Z`, optionally `-alpha.N`, `-beta.N` or
  `-rc.N`) and higher than `P`; neither the tag `vV` nor the branch `chore/release-V` exists,
  locally or on `origin`.
- No release is under way: no open pull request titled `chore(release): …`, no draft release.
- `[Unreleased]` in `CHANGELOG.md` of `origin/main` has entries.
- The latest `Build` run on `main` (`gh run list --workflow build.yml --branch main --limit 1`)
  succeeded; wait for it while it runs.

Then compare `V` with the changes since `P` (`git log --oneline P..origin/main` and `[Unreleased]`):
a `feat` commit or an `Added` entry raises the minor number, fixes alone the patch number, a `!` or
a `BREAKING CHANGE` the major number (the minor one while the version is `0.y.z`). When `V` does
not match, say so in one line with the version that would, and go on: the number is the
maintainer's choice, and they can stop you before the tag. Say also when `[Unreleased]` has a
`### Security` heading: the release becomes a critical update that users cannot skip.

## 2. The release pull request

1. Create the branch `chore/release-V` from `origin/main` in a worktree of its own, unless the
   session already runs in one: other sessions may be using the main checkout
   (`git worktree add .local/wt-release-V -b chore/release-V origin/main`).
2. `CHANGELOG.md`: the entries of `[Unreleased]` move under `## [V] - YYYY-MM-DD` (today's date),
   below a new, empty `## [Unreleased]`. Fix formatting slips only (a blank line inside a list,
   headings out of the order Added, Changed, Deprecated, Removed, Fixed, Security); never reword,
   add or drop an entry.
3. `gradle.properties`: `version=V`.
4. `./gradlew build` passes (Windows: `.\gradlew.bat build`).
5. Commit both files, signed off (`git commit -s`), as `chore(release): V`, with a body naming the
   main changes since `P`.
6. Push the branch and open the pull request titled `chore(release): V`, its body following
   `.github/pull_request_template.md` and the previous release's pull request
   (`gh pr list --state merged --search "chore(release) in:title" --limit 1`): the pull requests
   merged since `P` by type, why the version is `V`, the two files changed, the checklist, Security
   and Interoperability.

## 3. Test the packages before the tag

Right after the push, run the release workflow by hand on the branch. It builds every package at
version `V` and runs the end-to-end update tests, in about 20 minutes, and publishes nothing:

```bash
gh workflow run release.yml --ref chore/release-V
gh run list --workflow release.yml --branch chore/release-V --event workflow_dispatch --limit 1 \
    --json databaseId --jq '.[0].databaseId'
```

The run can take a few seconds to show in the list.

## 4. Merge

Wait for all the checks of the pull request (with the host's pull request tools when it has them,
otherwise `gh pr checks N --watch`) and for the manual run (`gh run watch RUN --exit-status`). If
one fails, stop and report the failed job with the relevant lines of its log; the branch stays for
the fix. When all pass: `gh pr merge N --squash`.

## 5. Tag

```bash
sha=$(gh pr view N --json mergeCommit --jq .mergeCommit.oid)
git fetch origin main
git show "$sha:gradle.properties" | grep -x 'version=V'
git tag -a vV -m "njMule V" "$sha"
git push origin vV
```

The tag starts `release.yml` (packages, Docker image, draft release) and `docs.yml` (the site). A
pushed tag is never moved or deleted: if its run fails, the version is lost, as 0.1.1 was; report
it and propose the fix and the next patch version.

## 6. Approve the `release` environment

The tag's run (`gh run list --workflow release.yml --branch vV --event push --limit 1`) builds for
about 25 minutes; then its `release` job waits for approval of the `release` environment, whose
secrets sign the update information. Wait in the background until it asks or the run ends:

```bash
repo=njMule/njMule
while :; do
    pending=$(gh api "repos/$repo/actions/runs/$RUN/pending_deployments" --jq 'length')
    status=$(gh run view "$RUN" --repo "$repo" --json status --jq .status)
    [[ "$pending" != 0 || "$status" == completed ]] && break
    sleep 60
done
```

If the run ended, a build failed: stop and report it. Otherwise check that the run is the tag's
(`gh run view RUN --json event,headBranch,headSha`: `push`, `vV`, the tagged commit), then approve
the `release` environment only:

```bash
env_id=$(gh api "repos/$repo/actions/runs/$RUN/pending_deployments" \
    --jq '.[] | select(.environment.name == "release") | .environment.id')
gh api -X POST "repos/$repo/actions/runs/$RUN/pending_deployments" -F "environment_ids[]=$env_id" \
    -f state=approved -f comment="Release V, requested by the maintainer"
```

Then wait for the run to end: `gh run watch RUN --exit-status`.

## 7. Check the draft release

In a scratch directory, stopping at the first difference:

- `gh release view vV --json isDraft,isPrerelease,assets,body`: a draft, a pre-release exactly when
  `V` has a pre-release part, and every file of the previous release (`gh release view P --json
  assets`) with `V` in place of its version. The notes are the changelog's section of `V`, followed
  by the VirusTotal links; without them the scan failed, which does not stop the release.
- `gh release download vV --pattern 'SHA256SUMS*' --pattern 'njmule-update.json*'`:
  `njmule-update.json` names the version `V`; both signatures verify with the first key of
  `njmule-core/src/main/resources/org/njmule/core/update/keys.pub` (`minisign -Vm FILE -P KEY`,
  when minisign is installed); `gh attestation verify SHA256SUMS --repo njMule/njMule` passes.
- The Docker image `ghcr.io/njmule/njmule:V` exists, which this prints as `200`:

  ```bash
  token=$(curl -s "https://ghcr.io/token?scope=repository:njmule/njmule:pull" \
      | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $token" \
      -H 'Accept: application/vnd.oci.image.index.v1+json' https://ghcr.io/v2/njmule/njmule/manifests/V
  ```

## 8. Publish

`gh release edit vV --draft=false`, with `--latest` unless `V` is a pre-release. For a stable
version, `gh api repos/njMule/njMule/releases/latest --jq .tag_name` then names `vV`; the links
`releases/latest/download/…` may lead to `P` for a few more minutes (GitHub's cache).

The tag's `docs.yml` run (`gh run list --workflow docs.yml --branch vV`) publishes the site: if it
was cancelled, run it again (`gh run rerun RUN`); if it failed, report it.

## 9. Roadmap

Update the pinned Roadmap issue #38 (`gh issue view 38 --json body`, then
`gh issue edit 38 --body-file FILE`), changing nothing else:

- under **Released**, after the last entry and in the same form:
  `- **V** (D Month YYYY): <what it brings, in one line from the changelog>. See the
  [release notes](https://github.com/njMule/njMule/releases/tag/vV).`
- under **Next**, tick (`- [x]`) only the items that a pull request of this release clearly
  completes.

## 10. Finish

- Fast-forward the main checkout to `origin/main` (`git merge --ff-only origin/main`) when it is on
  `main` and clean; remove the release worktree and its local branch.
- Report in a few lines: the release's link, the pull request, the runs, and anything worth
  noting (a version that does not match the changes, a critical release, no VirusTotal links, a
  site not published).
