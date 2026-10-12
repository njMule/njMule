<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# CLAUDE.md — njMule project instructions

njMule — **N**ext generation **J**ava **Mule**: a modern Java 25 re-implementation of eMule 0.72a
(eD2K + Kad2), wire-compatible with the eMule network. Licensed **GPL-3.0-or-later**.

The full implementation plan lives in `docs/PLAN.md` (milestones M0–M8). Read it before starting work.
`docs/` is **local only**: it is git-ignored by the maintainer's decision and must never be committed.

**Language: everything in the repository is written in English** — source code, comments, commit
messages, PR texts, documentation (`docs/`, README, changelog). Only the UI translations
(`messages_<lang>.properties`) are in other languages.

## Git conventions (mandatory)

All three follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/).

### Commit messages

```
<type>[optional scope][!]: <description>

[optional body]

[optional footer(s)]
```

- **Types**: `feat` (new feature → SemVer MINOR), `fix` (bug fix → PATCH), `build`, `ci`, `docs`,
  `perf`, `refactor`, `style`, `test`, `chore`, `revert`.
- **Breaking changes**: `!` after type/scope (`feat(api)!: …`) and/or a `BREAKING CHANGE: <text>`
  footer → SemVer MAJOR (MINOR while the version is `0.y.z`).
- **Scopes** (lower-case module/area names): `core`, `api`, `engine`, `protocol`, `net`, `server`,
  `kad`, `files`, `download`, `upload`, `search`, `persist`, `config`, `security`, `social`,
  `extras`, `gui`, `webui`, `app`, `testkit`, `bench`, `packaging`, `branding`, `i18n`, `deps`,
  `docs`, `ci`, `release`.
- Description: imperative mood, lower case, no trailing period, ≤ 72 characters for the header.
- Body explains *what* and *why*; wrap at 72 columns. Footers use `Token: value`
  (`Refs: #12`, `Closes: #34`, `BREAKING CHANGE: …`, `Signed-off-by: …`).
- Authorship: the maintainer is the only author; never add AI/assistant attribution
  (`Co-Authored-By`, "Generated with …", session links) to commits, PRs, tags or release notes.
- One logical change per commit.

### Pull requests

- PR **title** is a Conventional Commits header (it becomes the squash-merge commit message),
  e.g. `feat(kad): add routing zone splitting`.
- PR body uses `.github/pull_request_template.md` (summary, security gates touched, interop
  checklist, test evidence).
- Merge strategy: squash merge into `main`; `main` is protected and never committed to directly.

### Branch names

```
<type>/<short-kebab-case-description>
```

- Same `type` vocabulary as commits; optional milestone prefix in the description:
  `feat/m0-project-skeleton`, `feat/m1-tag-codec`, `fix/server-udp-min-length`, `docs/protocol-spec`,
  `ci/native-image-job`, `chore/deps-logback-1.6.6`.
- Lower case, `a-z0-9-` only, ≤ 50 characters, no trailing slash.
- Release branches (if ever needed): `release/vX.Y.Z`.

## Versioning

- [Semantic Versioning 2.0.0](https://semver.org/). Single source: `<revision>` in the root `pom.xml`.
- `0.y.z` during milestones, pre-releases `-alpha.N` / `-beta.N` / `-rc.N`, `1.0.0` at feature parity.
- Tags are annotated: `vX.Y.Z`. `CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/).

## Project layout

| Path | Content |
|---|---|
| `njmule-core` (`org.njmule.core`) | engine; exports only `api` and `config`, `engine` only to the test kit |
| `njmule-app` (`org.njmule.app`) | headless launcher/CLI, Logback set-up (`logging.internal` is not exported) |
| `njmule-gui` (`org.njmule.gui`) | JavaFX 27 + AtlantaFX + Ikonli desktop app |
| `njmule-testkit` (`org.njmule.testkit`) | test doubles (`ManualTicker`, later fake server/peer) |
| `config/` | license header, Checkstyle and SpotBugs configuration |
| `branding/` | original artwork (master SVG + generated PNG/ICO/ICNS) — never reuse eMule assets |
| `tools/` | single-file helper programs (`IconGen.java`) |
| `docs/` (local, git-ignored) | `PLAN.md`, `ARCHITECTURE.md`, `PROTOCOL.md`, `FORMATS.md`, `adr/` |

## Tooling and commands

- Build with the Maven Wrapper (Java 25): `./mvnw verify` (Windows: `.\mvnw.cmd verify`) runs
  Spotless check, Checkstyle, Error Prone, tests, JaCoCo (core ≥ 70% lines) and SpotBugs +
  FindSecBugs. Format first with `./mvnw spotless:apply`.
- Other profiles: `-Plicenses` (THIRD-PARTY.txt + CycloneDX SBOM), `-Psecurity` (OWASP
  dependency-check, needs `NVD_API_KEY`), `-Pnative` in `njmule-app` (GraalVM native image).
- Run headless: `./mvnw -pl njmule-app -am package`, then
  `java -p njmule-app/target/lib:njmule-app/target/classes -m org.njmule.app/org.njmule.app.Main`
  (`;` as separator on Windows). Run the GUI: `./mvnw -pl njmule-gui javafx:run` after `install`.
- Native image: GraalVM Community in the pinned container
  `ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1` (same image as CI; no MSVC needed on
  Windows, it builds the Linux x64 binary `njmule-app/target/njmule`):
  `docker run --rm -v "$PWD:/work" -v "$HOME/.m2:/root/.m2" -w /work --entrypoint ./mvnw <image> -B -Pnative -pl njmule-app -am package -DskipTests`.
- Regenerate icons: `java -Djava.awt.headless=true tools/IconGen.java branding/njmule-icon.svg branding/generated`.
- Library versions are always the latest stable ones; check Maven Central before adding or bumping.
  Known constraint: google-java-format is pinned to the newest version Spotless supports.
- GitHub operations use the GitHub CLI (`gh pr create`, `gh release create`, `gh api`); remote is
  `git@github.com:salvatore-coppola/njMule.git` (SSH).
- Push, PRs, tags and releases only when the maintainer explicitly asks.
- **Initial phase (until the maintainer says otherwise): no pull requests.** Each milestone lives on
  its own branch (`feat/mN-…`); when the milestone is complete (exit criteria in `docs/PLAN.md`
  §10.1 met and `./mvnw verify` green) fast-forward `main` to it, push `main`, and start the next
  milestone from the updated `main`.

## Licensing rules (GPL-3.0-or-later, REUSE-compliant)

- Every source file starts with the SPDX header from `config/license/header-java.txt`
  (Spotless enforces it for Java). Other text files carry `SPDX-FileCopyrightText` and
  `SPDX-License-Identifier` comments; files that cannot (binaries, JSON, images) are covered by
  `REUSE.toml`.
- Third-party code or assets: record origin and license in `REUSE.toml` and `NOTICE`; only
  GPL-3.0-compatible licenses are allowed.
- **No graphical asset of eMule** (icons, bitmaps, GIFs, skins, logo) is ever used or derived: all
  artwork is original (`branding/`). eMule's source is used only as a reference for protocol, file
  formats, behaviour and UI strings/translations.
- Never commit personal data (eMule config copies, user hashes, nick names, IPs, screenshots of the
  maintainer's eMule) — use the git-ignored `fixtures-private/` and `.local/` folders.

## Code style and formatting

- Java code follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
  and is formatted with [google-java-format](https://github.com/google/google-java-format) in its
  **AOSP** variant: **4-space indent** (8 for continuations), 100 columns, sorted imports, no
  wildcard imports.
- **Indentation: 4 spaces** in every file type (Java, XML/POM, YAML, scripts); tabs only in Makefiles.
- **Line endings: LF (Linux style)** everywhere; the only exception is `*.cmd`/`*.bat` (CRLF,
  required by cmd.exe). Spotless rewrites Java sources with LF; `.gitattributes` normalises the rest.
- Enforced by Spotless in the `validate` phase: the build fails on unformatted code or a missing
  license header. Fix with `./mvnw spotless:apply` before committing.
- Always qualify instance members with `this.` (fields **and** method calls, e.g.
  `this.queue.offer(task)`, `this.publish(event)`), also where it is not needed to disambiguate.
  Checkstyle `RequireThis` enforces it (`config/checkstyle/checkstyle.xml`).
- Optional pre-commit hook: `git config core.hooksPath .githooks` (runs `spotless:check`).
- IDEs: install the google-java-format plugin (IntelliJ / Eclipse) and honour `.editorconfig`.
- Markdown is wrapped at ~100 columns.

## Code comments

- Be concise: comment only what the code cannot say — the *why*, invariants, security reasons,
  references to the eMule source (`PartFile.cpp:3624`) or to a spec.
- No comments that restate the code, no Javadoc on trivial getters, setters, builders or obvious
  methods; no change-history or "added by" comments.
- Public API Javadoc: one summary sentence; `@param`/`@return` only when not obvious from the name.
- Keep comments to one or two lines; trim long explanations to the essential and move design
  rationale to `docs/` (ARCHITECTURE.md, ADRs).

## UI colours

- Sober palette with **pastel tints**, always compatible with the AtlantaFX theme in use. The
  default theme is **Nord Light / Nord Dark** (itself a pastel palette), following the OS colour
  scheme; the other AtlantaFX themes stay selectable.
- Use only AtlantaFX looked-up colours (`-color-bg-*`, `-color-fg-*`, `-color-accent-*`,
  `-color-success-*`, …) and the `-nj-*` tokens of `docs/PLAN.md` §6.7; no colour literals in
  Java or CSS outside the token definitions.
- Pastel tones for fills and surfaces (`*-subtle`/`*-muted`, bars, charts, chips); text uses the
  theme's `-color-fg-*` and `-color-*-fg` tokens, never a pastel tone.
- Accessibility (WCAG 2.2 AA): text contrast ≥ 4.5:1 (≥ 3:1 for large text), essential icons and
  graphics ≥ 3:1; colour is never the only cue (add icon, tooltip, legend or pattern).
- Every token has a light and a dark value; saturated colours only for real errors and warnings.
- Brand: pastel periwinkle `#A9B8EA` with deep indigo ink `#26304F` (`branding/`).

## Engineering rules

- `njmule-core` must never depend on JavaFX/AWT or any GUI module.
- Code must stay GraalVM native-image friendly: no undeclared reflection, no dynamic proxies,
  register resources and `ServiceLoader` providers in the reachability metadata.
- Log only through SLF4J with `{}` placeholders; never use external data as a format string.
- Never write into the user's eMule installation or config directory.
