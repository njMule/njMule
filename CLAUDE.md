<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# CLAUDE.md — njMule project instructions

njMule — **N**ew **J**ack **Mule** (a jack is the male donkey, a mule's sire): a modern Rust
re-implementation of eMule (eD2K + Kad2), wire-compatible with the eMule network, with a Vue 3 /
TypeScript user interface and a Tauri 2 desktop shell. Licensed **GPL-3.0-or-later**.

Design notes live in `docs/`, which is **local only**: it is git-ignored by the maintainer's
decision and must never be committed.

**Language: everything in the repository is written in English** — source code, comments, commit
messages, PR texts, documentation (`docs/`, README, changelog). Only the UI translations (the
`emule[_<lang>].properties` and `nj[_<lang>].properties` bundles) are in other languages.

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
- **Scopes** (lower-case module/area names): `engine`, `api`, `protocol`, `net`, `server`, `kad`,
  `files`, `download`, `upload`, `search`, `persist`, `config`, `security`, `social`, `extras`,
  `gui`, `webui`, `packaging`, `branding`, `i18n`, `deps`, `docs`, `ci`, `release`.
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
- Merge strategy: squash merge into `main`, which is never committed to directly. A ruleset protects
  it: a pull request, its `CI result` check green (the summary job of `build.yml`), squash only,
  linear history, no force push or deletion, no bypass; no approval is required, the maintainer
  being the only reviewer. The squash commit takes the PR title and the commits' messages, their
  `Signed-off-by` lines included; merged branches are deleted.
- Dependabot's pull requests are completed on their own branch (locks and checksums rewritten when
  Gradle versions change, see Tooling) and squash-merged like the others.

### Branch names

```
<type>/<short-kebab-case-description>
```

- Same `type` vocabulary as commits: `feat/kad-routing`, `fix/server-udp-min-length`,
  `docs/protocol-spec`, `ci/sonarqube-rust`, `chore/deps-tokio-1.54`.
- Lower case, `a-z0-9-` only, ≤ 50 characters, no trailing slash.
- Release branches (if ever needed): `release/vX.Y.Z`.

## Versioning

- [Semantic Versioning 2.0.0](https://semver.org/). Single source: `version` in `gradle.properties`
  (`njmule-engine/build.rs` and `njmule-desktop/build.gradle.kts` read it from there).
- `0.y.z` during pre-1.0 development, pre-releases `-alpha.N` / `-beta.N` / `-rc.N`, `1.0.0` at
  feature parity.
- Tags are annotated: `vX.Y.Z`. `CHANGELOG.md` follows
  [Keep a Changelog](https://keepachangelog.com/): each user-visible change adds a short line under
  `[Unreleased]` (Added, Changed, Fixed, Security).
- A release: a pull request sets `version` and turns `[Unreleased]` into `[X.Y.Z] - <date>` under a
  new empty `[Unreleased]`; once merged, the annotated tag `vX.Y.Z` on that commit of `main` runs
  `release.yml`, whose draft release waits for the maintainer's approval of the `release`
  environment and is then published by hand. Then the pinned Roadmap issue (#38) gets the release
  under "Released" and its completed "Next" items ticked.
- The local `release` skill (kept local like `docs/` and never committed) automates all of it,
  with a manual run of `release.yml` on the release branch before the tag, once the maintainer asks
  for a release of a version: that request is their approval of every step, including the `release`
  environment and the publication.

## Project layout

| Path | Content |
|---|---|
| `njmule-engine` | the Rust engine, CLI, web interface (Axum) and remote-control API (`Cargo.toml`, `src/`, `assets/`, `tests/`, `examples/web_capture_demo.rs`) |
| `njmule-ui` | the single interface of the desktop window and the web interface (Vue 3, TypeScript, Vite, Reka UI, TanStack Table/Virtual); reaches njMule only through the remote-control API; embedded by `njmule-engine` (gzipped) and `njmule-desktop` (uncompressed); holds the translation bundles (`i18n/`) and flags (`flags/`) |
| `njmule-desktop` | the desktop window: a Tauri 2 shell (Rust, `src-tauri/`) that starts the `njmule` executable with a control endpoint and shows `njmule-ui` |
| `website/` | documentation website (Docusaurus): `docs/` user guide, `developer/` developer guide, published on GitHub Pages |
| `gradle/` | `libs.versions.toml` (Node.js and JSVG versions), `verification-metadata.xml` and the Gradle Wrapper |
| `packaging/` | Windows installer (WiX 5) and Windows service host (`packaging/windows/service.c`), Linux packages (nfpm), macOS bundle metadata, Docker image, Synology DSM package |
| `branding/` | original artwork (master SVG + generated PNG/ICO/ICNS) — never reuse eMule assets |
| `tools/` | single-file helper programs (`IconGen.java`, `FlagGen.java`, `Rc2Properties.java`, `SocialPreview.java`, `UpdateManifest.java`), golden vector generators (`tools/golden/`) and the e2e update server (`tools/e2e-update-server.sh`) |
| `docs/` (local, git-ignored) | `PLAN.md`, `ARCHITECTURE.md`, `PROTOCOL.md`, `FORMATS.md`, `adr/` |

## Tooling and commands

- **Rust engine (`njmule-engine`)**: pinned toolchain in `njmule-engine/rust-toolchain.toml` (installed
  automatically by `rustup`). Build, lint and test from `njmule-engine/`:

  ```bash
  cargo fmt --check
  cargo clippy --locked --all-targets -- -D warnings
  cargo test --locked
  cargo deny check
  ```

  `Cargo.toml` forbids `unsafe_code` and denies `unwrap_used`, `expect_used`, `panic`,
  `indexing_slicing` and `arithmetic_side_effects` in production code. Release builds keep
  `overflow-checks = true`, thin LTO and stripped symbols. To embed the compressed web UI in a
  release binary, build `:njmule-ui:compress` first (`./gradlew :njmule-ui:compress`, then
  `cargo build --manifest-path njmule-engine/Cargo.toml --locked --release --bin njmule`).
- **Engine tests and coverage**: the integration tests of `njmule-engine/tests/` are modules of one
  test binary (`tests/main.rs`) so the engine is linked once; declare every new test file there
  (`mod foo_test;`) — `suite_modules_test` fails on any file left out. Run one file with
  `cargo test --locked --test suite -- foo_test::`. Golden corpora live in `tests/golden/` and
  recorded wire transcripts for black-box flows in `tests/transcripts/`. Measure coverage with
  `cargo llvm-cov --locked --fail-under-lines 70` (requires `rustup component add llvm-tools` and
  `cargo-llvm-cov`).
- **Notices and SBOM**: generated from `njmule-engine/Cargo.lock`:
  - `cargo about generate about.hbs -o target/THIRD-PARTY.txt`
  - `cargo cyclonedx --format json --spec-version 1.6 --describe binaries --no-build-deps --target all`
- **Static Linux build (musl)**: for Synology DSM and `linux-x64-static` (no system glibc needed),
  install `musl-tools` and `rustup target add x86_64-unknown-linux-musl`, build `:njmule-ui:compress`,
  then run:

  ```bash
  CC_x86_64_unknown_linux_musl=musl-gcc cargo build --manifest-path njmule-engine/Cargo.toml \
      --locked --release --target x86_64-unknown-linux-musl --bin njmule
  ```

- **Interface (`njmule-ui`)**: Gradle (JDK 25) downloads the pinned Node.js (`nodejs` in
  `gradle/libs.versions.toml`, checked in `gradle/verification-metadata.xml` for all five platforms)
  and runs `npm ci` from `package-lock.json` without install scripts (`.npmrc`).
  `./gradlew :njmule-ui:build` runs type generation from `njmule-engine/assets/openapi.yaml`
  (`npm run api`), `vue-tsc`, Vite build (`bundle`), Vitest (`unitTest`), Prettier (`formatCheck`,
  fix with `npm run format` in `njmule-ui/`), `site` and `compress`. Its texts are the translation
  keys in `i18n/` (`t('IDS_…')`, `t('nj.…')`), converted to JSON at build time; only literal keys
  are found. A task that runs `npm ci` empties `node_modules`: stop a Vite dev server first.
- **Desktop window (`njmule-desktop`)**: pinned Rust in `njmule-desktop/src-tauri/rust-toolchain.toml`;
  the Tauri CLI comes from npm. `./gradlew :njmule-desktop:shell` builds
  `njmule-desktop/src-tauri/target/release/njmule-gui(.exe)`, which starts the `njmule(.exe)`
  executable beside it (or on `PATH`) and shows `njmule-ui`. In `njmule-desktop/src-tauri/`:
  `cargo fmt --check`, `cargo clippy --locked --release --all-targets -- -D warnings`,
  `cargo test --locked --release` (after `./gradlew :njmule-ui:site`).
- **Windows service host**: `./gradlew nativeService` compiles `packaging/windows/service.c` with
  Visual Studio Build Tools into `build/windows/njmule-service.exe`.
- **Gradle locking and verification**: every Gradle dependency (`libs.versions.toml`) is locked in
  `gradle.lockfile` / `buildscript-gradle.lockfile` / `settings-gradle.lockfile` and verified by
  SHA-256 in `gradle/verification-metadata.xml` (including all five Node.js platform archives).
  After changing a Gradle or Node.js version, rewrite both (without `--refresh-dependencies`, Gradle
  can omit cached parent POMs and break CI):

  ```bash
  ./gradlew --no-configuration-cache --refresh-dependencies --write-verification-metadata sha256 \
      --write-locks :iconTools :njmule-ui:nodeSetup :njmule-desktop:nodeSetup :website:nodeSetup
  ```

  Then remove stale entries and keep all five `org.nodejs` platform checksums from Node.js's
  `SHASUMS256.txt`.
- **Documentation website (`website/`, Docusaurus)**: `./gradlew :website:site` →
  `website/build/site` (builds `njmule` and `examples/web_capture_demo` via `:website:engineTools`,
  captures screenshots with Chrome or Edge through `website/scripts/capture-web.mjs`, captures
  `--help` and `--remote help` via `:website:cliHelp`, and generates the API reference from
  `njmule-engine/assets/openapi.yaml`); `./gradlew :website:siteCheck` checks Prettier and
  TypeScript. Preview: `./gradlew :website:prepare`, then `npm start` in `website/`. Pages are MDX
  in English, text under CC BY-SA 4.0; screenshots are never committed (`<Screenshot id="…">`). A
  user-visible change updates its page in the same branch. `.github/workflows/docs.yml` checks PRs
  and publishes on GitHub Pages only on a manual run with `deploy` or a `v*` tag.
- **Installers and packages**: every desktop package carries `njmule(.exe)` and `njmule-gui(.exe)`
  (which handles `--apply-update`, `--keep-awake`, `--uninstall-profile`, `--restore-links`,
  `--exit`); Windows adds `njmule-service.exe`. WiX **5** (`dotnet tool install --global wix
  --version 5.0.2`; v6+ require the OSMF EULA) builds the MSI from `packaging/windows/njmule.wxs`;
  nfpm builds deb/rpm from `packaging/linux/nfpm.yaml`; macOS gets an ad-hoc signed `.app` in a
  DMG; Synology DSM 7 gets an x86_64 `.spk` from `packaging/synology/build-spk.sh` around the
  static musl executable; Docker builds multi-arch `linux/amd64` (static musl on `scratch`) and
  `linux/arm64` (glibc on distroless). All in `.github/workflows/release.yml`.
- **Static analysis on SonarQube Cloud**: project `njMule_njMule`, organisation `njmule`; CI runs it
  with `sonar.qualitygate.wait=true` so a red quality gate fails `CI result` and blocks merge.
- **Regenerate icons** (JSVG renders the SVG via `./gradlew iconTools`): see `branding/README.md`.
- **GitHub operations**: use `gh` CLI; remote is `git@github.com:njMule/njMule.git` (SSH).
- Push, PRs, tags and releases only when the maintainer explicitly asks; asking for a release of a
  version asks for all of its steps (the `release` skill).
- **Every change reaches `main` through a pull request**: branch `<type>/<description>` from the
  latest `main`, checks green locally, then squash-merged once `CI result` passes.

## Licensing rules (GPL-3.0-or-later, REUSE-compliant)

- Every source and text file starts with `SPDX-FileCopyrightText` and `SPDX-License-Identifier`
  comments; files that cannot (binaries, JSON, images, lock files, golden fixtures) are covered by
  `REUSE.toml`. `reuse lint` runs in CI on every push.
- Third-party code or assets: record origin and license in `REUSE.toml` and `NOTICE`; only
  GPL-3.0-compatible licenses are allowed (`njmule-engine/deny.toml` enforces this for Rust crates).
- **No graphical asset of eMule** (icons, bitmaps, GIFs, skins, logo) is ever used or derived: all
  artwork is original (`branding/`). eMule's source is used only as a reference for protocol, file
  formats, behaviour and UI strings/translations.
- Never commit personal data (eMule config copies, user hashes, nick names, IPs, screenshots of the
  maintainer's eMule) — use the git-ignored `fixtures-private/` and `.local/` folders.

## Code style and formatting

- **Rust (`njmule-engine`, `njmule-desktop/src-tauri`)**: formatted with `cargo fmt` (`rustfmt.toml`:
  4-space indent, 120 columns) and linted with `cargo clippy --locked --all-targets -- -D warnings`.
- **TypeScript / Vue (`njmule-ui`, `website`)**: formatted with Prettier (4 spaces, 120 columns,
  single quotes, LF) and type-checked strictly (`vue-tsc` / `tsc`).
- **Indentation: 4 spaces** in every file type (Rust, TypeScript, Kotlin build scripts, XML, YAML,
  scripts); tabs only in Makefiles.
- **Line endings: LF** everywhere; the only exception is `*.cmd`/`*.bat` (CRLF, required by
  `cmd.exe`). `.gitattributes` normalises checkouts.
- Optional pre-commit hook: `git config core.hooksPath .githooks` (runs `cargo fmt --check` on
  `njmule-engine` and `njmule-desktop/src-tauri`).
- Markdown is wrapped at ~100 columns.

## Code comments

- Be concise: comment only what the code cannot say — the *why*, invariants, security reasons,
  references to a spec or RFC.
- **Never reference the original project's source** in code, comments, tests or commit messages:
  no eMule file names (`*.cpp`, `*.h`), line numbers, C++ classes, functions or variables, and no
  "as eMule does" phrasing; describe the protocol or format itself. Name eMule only where the
  feature is about it (importing an eMule profile, interoperability tests). Never include an eMule
  version number in committed files.
- No comments that restate the code, no doc comments on trivial getters or obvious functions; no
  change-history or "added by" comments.
- Keep comments to one or two lines; move long design rationale to `docs/` (`ARCHITECTURE.md`, ADRs).

## UI colours

- Sober palette with **pastel tints**, always compatible with the theme in use. The default theme
  is **Nord Light / Nord Dark** (itself a pastel palette), following the OS colour scheme; the other
  themes (Primer, Cupertino, Dracula) stay selectable.
- Use only the theme's colours (`--color-bg-*`, `--color-fg-*`, `--color-accent-*`,
  `--color-success-*`, …, in `njmule-ui/src/styles/themes.css`) and the `--nj-*` tokens of
  `njmule-ui/src/styles/njmule.css`; no colour literals in TypeScript, Vue or CSS outside the token
  definitions.
- Pastel tones for fills and surfaces (`*-subtle`/`*-muted`, bars, charts, chips); text uses the
  theme's `--color-fg-*` and `--color-*-fg` tokens, never a pastel tone.
- Accessibility (WCAG 2.2 AA): text contrast ≥ 4.5:1 (≥ 3:1 for large text), essential icons and
  graphics ≥ 3:1; colour is never the only cue (add icon, tooltip, legend or pattern).
- Every token has a light and a dark value; saturated colours only for real errors and warnings.
- Brand: deep blue `#24427A`, off-white mark `#F4F6FB` and warm orange accents `#F5A65B`
  (`branding/`, README badges); orange stays an accent, never a large surface.

## Engineering rules

- Do not reinvent the wheel: use the standard library or a well-known, maintained open-source crate
  or library (GPL-3.0-compatible license) before writing a utility, codec, parser or algorithm. Write
  our own only for eD2K/Kad-specific code or when no suitable library exists or fits the
  constraints, and say why in a comment or the commit message.
- Keep `unsafe_code = "forbid"` in `njmule-engine`, and never panic, unwrap, index-slice or
  overflow on network input.
- Log through `tracing` with structured fields or format arguments; sanitize or keep untrusted
  network strings bounded, and never log secrets (passwords, API tokens).
- Never write into the user's eMule installation or config directory.

## Tests

- Every test follows the given/when/then form of Gherkin: each test function has a meaningful
  `should_...` name stating the expected behaviour, and its body calls helper steps in order —
  `given_...` (optional), one `when_...`, then one or more `then_...` steps — with shared scenario
  state in a test fixture struct. A scenario has one action: independent cases become separate tests
  or a loop over cases, and earlier stages of a flow become `given_...` steps. The interface's
  Vitest tests follow the same form with `given...` / `when...` / `then...`. Example in
  `CONTRIBUTING.md`.
