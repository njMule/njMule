<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# CLAUDE.md — njMule project instructions

njMule — **N**ext generation **J**ava **Mule**: a modern Java 25 re-implementation of eMule
(eD2K + Kad2), wire-compatible with the eMule network. Licensed **GPL-3.0-or-later**.

The full implementation plan lives in `docs/PLAN.md` (milestones M0–M8). Read it before starting work.
`docs/` is **local only**: it is git-ignored by the maintainer's decision and must never be committed.

**Language: everything in the repository is written in English** — source code, comments, commit
messages, PR texts, documentation (`docs/`, README, changelog). Only the UI translations
(the `emule[_<lang>].properties` and `nj[_<lang>].properties` bundles) are in other languages.

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
- Merge strategy: squash merge into `main`, which is never committed to directly. A ruleset protects
  it: a pull request, its `CI result` check green (the summary job of `build.yml`), squash only,
  linear history, no force push or deletion, no bypass; no approval is required, the maintainer
  being the only reviewer. The squash commit takes the PR title and the commits' messages, their
  `Signed-off-by` lines included; merged branches are deleted.
- Dependabot's pull requests are completed on their own branch (locks and checksums rewritten, see
  Tooling) and squash-merged like the others.

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

- [Semantic Versioning 2.0.0](https://semver.org/). Single source: `version` in `gradle.properties`.
- `0.y.z` during milestones, pre-releases `-alpha.N` / `-beta.N` / `-rc.N`, `1.0.0` at feature parity.
- Tags are annotated: `vX.Y.Z`. `CHANGELOG.md` follows
  [Keep a Changelog](https://keepachangelog.com/): each user-visible change adds a short line under
  `[Unreleased]` (Added, Changed, Fixed, Security).
- A release: a pull request sets `version` and turns `[Unreleased]` into `[X.Y.Z] - <date>` under a
  new empty `[Unreleased]`; once merged, the annotated tag `vX.Y.Z` on that commit of `main` runs
  `release.yml`, whose draft release waits for the maintainer's approval of the `release`
  environment and is then published by hand. Then the pinned Roadmap issue (#38) gets the release
  under "Released" and its completed "Next" items ticked.

## Project layout

| Path | Content |
|---|---|
| `njmule-core` (`org.njmule.core`) | engine and remote-control API; exports only `api`, `config` and `remote` |
| `njmule-app` (`org.njmule.app`) | headless launcher/CLI, Logback set-up (`logging.internal` is not exported) |
| `njmule-gui` (`org.njmule.gui`) | JavaFX 27 + AtlantaFX desktop app, still in the packages until `njmule-desktop` replaces it; it holds the translation bundles and flags `njmule-ui` reads |
| `njmule-ui` | the one interface of the desktop window and the web interface (Vue 3, TypeScript, Vite, Reka UI, TanStack Table/Virtual); reaches njMule only through the remote-control API; built into njmule-webui's jar |
| `njmule-desktop` | the desktop window: a Tauri 2 shell (Rust, `src-tauri/`) that starts the native engine with a control endpoint and shows `njmule-ui`; not packaged yet |
| `njmule-webui` (`org.njmule.webui`) | web interface on Jetty (password, sessions, CSRF); serves `njmule-ui` and the core's remote-control API |
| `njmule-webapp` | the former browser app (Vue 3, Element Plus); no longer served, to be removed |
| `njmule-testkit` (`org.njmule.testkit`) | test doubles (fake server and peers, from M2) |
| `njmule-bench` | JMH benchmarks; classpath build so it reaches internal core packages; not distributed |
| `build-logic/` | Gradle convention plugin shared by the modules (`njmule.java-conventions`) |
| `gradle/` | `libs.versions.toml` (every library and plugin version) and the Gradle Wrapper |
| `config/` | license header, Checkstyle and SpotBugs configuration |
| `branding/` | original artwork (master SVG + generated PNG/ICO/ICNS) — never reuse eMule assets |
| `tools/` | single-file helper programs (`IconGen.java`) |
| `website/` | documentation website (Docusaurus): `docs/` user guide, `developer/` developer guide, published on GitHub Pages |
| `docs/` (local, git-ignored) | `PLAN.md`, `ARCHITECTURE.md`, `PROTOCOL.md`, `FORMATS.md`, `adr/` |

## Tooling and commands

- Build with the Gradle Wrapper (Java 25 toolchain): `./gradlew build` (Windows:
  `.\gradlew.bat build`) runs Spotless, Checkstyle, Error Prone, tests, JaCoCo (core ≥ 70% lines)
  and SpotBugs + FindSecBugs. Format first with `./gradlew spotlessApply`. The modules share their
  set-up through `build-logic` (`njmule.java-conventions`); versions live in
  `gradle/libs.versions.toml`.
- Builds are incremental and cached (build cache and configuration cache, `gradle.properties`): with
  nothing changed `./gradlew build` takes about a second, a change re-runs only what depends on it,
  and worktrees share the build cache in `~/.gradle`. `clean` is never needed for a correct build.
- Dependencies are locked and verified: every resolved version is in the `gradle.lockfile` of each
  project (and `buildscript-`/`settings-gradle.lockfile`), every downloaded artifact is checked
  against its SHA-256 in `gradle/verification-metadata.xml` (the OpenJFX jars of all five platforms
  included), and any difference fails the build. After changing a version (`libs.versions.toml`, a
  plugin, Gradle itself) rewrite both, then delete the entries of the versions no longer used and
  review the new checksums; Dependabot's pull requests need the same step. Without
  `--refresh-dependencies` Gradle leaves out the parent POMs and BOMs it already holds in its cache,
  and CI, which starts with an empty one, then fails on them:

  ```bash
  ./gradlew --no-configuration-cache --refresh-dependencies --write-verification-metadata sha256 \
      --write-locks spotlessCheck :njmule-app:collectReachabilityMetadata \
      :njmule-gui:generateLicenseReport cyclonedxBom
  ```
- Test options (`-P`): `test.excludedTags` (default `interop,private-fixtures,perf`), `test.tags`,
  `test.forks` (JVMs per module, default 4), `test.maxHeap` (default 4g: Gradle's own 512 MiB slows
  the window tests to a time-out) and `test.parallelModules=1` (one module's tests at a time, for
  machines with few cores; the CI's verify and Sonar jobs use it on their four CPUs). Any
  `-Pnjmule.<name>=<value>` reaches the tests as a system property. PowerShell splits an unquoted
  option at its first dot: write `'-Ptest.forks=1'`. One class:
  `./gradlew :njmule-core:test --tests '*TagCodecTest'`. Speed of the transfers on the loopback
  (prints MiB/s): `./gradlew :njmule-testkit:test --tests '*ThroughputTest' '-Ptest.tags=perf'
  '-Ptest.excludedTags=interop'`.
- Interface (`njmule-ui`): Gradle downloads Node.js (`nodejs` in `libs.versions.toml`, checked
  in `verification-metadata.xml` for every platform) and runs `npm ci` from `package-lock.json`
  without install scripts (`.npmrc`); `bundle` type-checks and builds it, `unitTest` runs Vitest,
  `formatCheck` runs Prettier (fix with `npm run format` there). The API's types come from
  `openapi.yaml` (`npm run api`). Its texts are the translation keys of `njmule-gui`'s bundles
  (`t('IDS_…')`, `t('nj.…')`), converted to JSON at build time; only literal keys are found. The
  `themes` task turns the AtlantaFX themes into CSS custom properties. A task that runs `npm ci`
  empties `node_modules`: stop a Vite dev server first.
- Desktop window (`njmule-desktop`): Rust and Cargo from rustup, at the version
  `src-tauri/rust-toolchain.toml` pins; the Tauri command line comes from npm.
  `./gradlew :njmule-desktop:shell` builds `src-tauri/target/release/njmule-gui(.exe)`, which starts
  the `njmule` executable beside it (or on the `PATH`) and shows `njmule-ui`. In `src-tauri`:
  `cargo test` runs its tests, `cargo fmt` formats it (`rustfmt.toml`: 120 columns).
- Documentation website (`website/`, Docusaurus, not part of `build`): `./gradlew :website:site` →
  `website/build/site` (it takes the desktop app's screenshots with `:njmule-gui:docsCaptures`, the web
  interface's (`njmule-ui`) with the installed Chrome or Edge through `website/scripts/capture-web.mjs`,
  the CLI help with `:njmule-app:cliHelp`, and makes
  the API reference from `openapi.yaml`); `./gradlew :website:siteCheck` checks its code (Prettier,
  TypeScript). Preview: `./gradlew :website:prepare`, then `npm start` in `website/`. Pages are MDX
  in English, text under CC BY-SA 4.0; screenshots are never committed (`<Screenshot id="…">`). A
  user-visible change updates its page in the same branch. `.github/workflows/docs.yml` builds it
  on changes and publishes on GitHub Pages only on a manual run with `deploy` or a `v*` tag.
- Other tasks: `:njmule-gui:generateLicenseReport` (THIRD-PARTY.txt in `build/reports/licenses`),
  `cyclonedxBom` (SBOM in `build/reports/cyclonedx`), `dependencyCheckAggregate` (OWASP
  dependency-check, needs `NVD_API_KEY`), `:njmule-app:nativeCompile` (GraalVM native image).
- Run headless: `./gradlew :njmule-app:run --args="--help"` (module path), or
  `./gradlew :njmule-app:installDist` and `njmule-app/build/install/njmule/bin/njmule`. Run the GUI:
  `./gradlew :njmule-gui:run` (JavaFX); the desktop window: `./gradlew :njmule-app:nativeCompile
  :njmule-desktop:shell`, with `njmule(.exe)` copied beside `njmule-gui(.exe)`.
- Benchmarks: `./gradlew :njmule-bench:shadowJar`, then
  `java -jar njmule-bench/build/libs/benchmarks.jar` (for the throughput benchmarks one operation is
  1 MiB, so ops/s = MiB/s).
- Static analysis on SonarQube Cloud: `./gradlew build sonar -Dsonar.token=<token>` (project
  `njMule_njMule`, organisation `njmule`); in CI the `sonar` job runs once the
  `SONAR_TOKEN` secret is set.
- Native image: GraalVM Community `25.0.4.1.1` (25 Innovation 4, same as CI). Locally it is installed
  in `%LOCALAPPDATA%\Programs\GraalVM` with `GRAALVM_HOME` set; on Windows `native-image` needs the
  Visual Studio 2022 Build Tools (C++ workload): `./gradlew :njmule-app:nativeCompile` →
  `njmule-app/build/native/nativeCompile/njmule.exe`. The Linux x64 binary comes from the pinned
  container `ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1` through `tools/native-docker.sh`
  (Gradle on a copy of the sources inside the container, much faster than on the bind mount; its user
  home in the Docker volume `njmule-gradle`): `docker run --rm -v "$PWD:/work" -v
  njmule-gradle:/root/.gradle --entrypoint bash <image> /work/tools/native-docker.sh` (Git Bash:
  prefix `MSYS_NO_PATHCONV=1`) → `njmule-app/build/native-linux/njmule`. A fully static x64 build
  linked with musl (no system libc needed: Synology DSM has glibc 2.26, our glibc build needs 2.34)
  comes from `tools/native-static-docker.sh` in the same container, adding `-v njmule-musl:/musl`
  for the toolchain it downloads and checks (`-Pnative.static=musl`) →
  `njmule-app/build/native-static/njmule`.
- Native optimization: `-O2` by default (property `native.optimization`). Checks may build faster
  with `-Pnative.optimization=-Ob`, as the CI does; releases always pass `-O2`, never `-Ob`.
- Native JavaFX app, no longer in the packages (GluonFX does not support Java 25/JavaFX 27):
  `native-image` on the module path through `./gradlew :njmule-gui:nativeGui` → `njmule-gui/build/native/` (exe plus the JDK
  libraries it loads). On Windows that exe is `njmule.exe`, a console program and the command line
  too; `./gradlew :njmule-gui:nativeLauncher` builds `build/launcher/njmule-gui.exe`
  (`native/windows/launcher.c`), the GUI program that starts it for the desktop with
  `NJMULE_DESKTOP=1`, as `javaw.exe` stands for `java.exe`. Its reachability metadata comes from
  the GUI tour (`GuiTour`, system property `njmule.gui.tour`): `./gradlew :njmule-gui:nativeTour`
  runs it under the tracing agent (accelerated and software rendering) and merges into
  `src/main/resources/META-INF/native-image/org.njmule/njmule-gui/tour`; `manual/` holds the JNI
  entries the tour cannot reach (file choosers). Regenerate after GUI changes on Windows, and for
  Linux with `tools/native-gui-docker.sh` (Ubuntu container, GraalVM Linux archive mounted at
  `/graalvm`); macOS adds its own in CI. Only GraalVM Community: Oracle GraalVM (PGO) is under GFTC,
  not compatible with distributing GPL binaries.
- Installers: every desktop package carries the headless engine `njmule(.exe)` of
  `:njmule-app:nativeCompile`, also the command line, and the desktop window `njmule-gui(.exe)` of
  `:njmule-desktop:shell`, which starts the engine beside it and answers the old Windows launcher's
  commands (`--apply-update`, `--keep-awake`, `--uninstall-profile`, `--restore-links`); Windows adds
  `njmule-service.exe`. `.github/scripts/desktop-smoke-test.*` checks the window starts the engine.
  WiX **5** (`dotnet tool install --global wix --version 5.0.2`; v6+ need the OSMF EULA)
  builds the per-user MSI from `packaging/windows/njmule.wxs`; nfpm builds deb/rpm from
  `packaging/linux/nfpm.yaml`; macOS gets an ad-hoc signed `.app` in a DMG; Synology DSM 7 gets an
  x86_64 `.spk` from `packaging/synology/build-spk.sh` around the static musl executable. All in
  `.github/workflows/release.yml` (manual run: packages only; tag `vX.Y.Z`: draft release).
- Regenerate icons (JSVG renders the SVG): see `branding/README.md`.
- Library versions are always the latest stable ones (`gradle/libs.versions.toml`); check Maven
  Central and the Gradle Plugin Portal before adding or bumping.
  Known constraint: palantir-java-format is pinned to the newest version Spotless supports.
- GitHub operations use the GitHub CLI (`gh pr create`, `gh release create`, `gh api`); remote is
  `git@github.com:njMule/njMule.git` (SSH).
- Push, PRs, tags and releases only when the maintainer explicitly asks.
- **Every change reaches `main` through a pull request** (since 0.1.0, when the milestones on
  `main` ended): a branch `<type>/<description>` from the latest `main`, `./gradlew build` green,
  then the pull request, squash-merged once its checks pass.

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
  and is formatted with [palantir-java-format](https://github.com/palantir/palantir-java-format) in
  its **PALANTIR** style: **4-space indent** (8 for continuations), **120 columns**, compact lambdas
  and call chains, Javadoc reflowed, sorted imports, no wildcard imports.
- **Indentation: 4 spaces** in every file type (Java, Kotlin build scripts, XML, YAML, scripts); tabs
  only in Makefiles.
- **Line endings: LF (Linux style)** everywhere; the only exception is `*.cmd`/`*.bat` (CRLF,
  required by cmd.exe). Spotless rewrites Java sources with LF; `.gitattributes` normalises the rest.
- Enforced by Spotless (`spotlessCheck`, part of `./gradlew build`): the build fails on unformatted
  code or a missing license header. Fix with `./gradlew spotlessApply` before committing.
- Always qualify instance fields with `this.` (e.g. `this.queue.offer(task)`), also where it is not
  needed to disambiguate, and call the object's own methods without it (`publish(event)`, not
  `this.publish(event)`). Checkstyle enforces both (`RequireThis` and a regexp check in
  `config/checkstyle/checkstyle.xml`).
- **Never give two members of a type names that differ only in case**, such as a constant
  `DIRECT` and a field `direct`, or `SETTINGS` and `settings`: SonarQube's rule `java:S1845` marks
  it a blocker and fails the quality gate, and neither the build nor Checkstyle catches it. Before
  adding a constant, field or method, check the type's other names, inherited ones included;
  rename the newcomer (`SETTINGS_PAGE`, `straight`). **This mistake recurs**: it comes back
  whenever a constant is named after what a field or method of the same type holds or does, as
  `SECONDS` beside `seconds`, `JSON` beside `json(...)`, `HOST` beside `host(...)`, `ENGLISH`
  beside `english()`, or a nested type `Windows` beside a field `windows`. Give such a constant
  what it is, not the same word (`SECONDS_KEPT`, `MAPPER`, `TEMPLATES`), and before each commit
  compare every new member's lower-cased name with the others of its type.
- Optional pre-commit hook: `git config core.hooksPath .githooks` (runs `spotlessCheck`).
- IDEs: install the palantir-java-format plugin (IntelliJ) and honour `.editorconfig`.
- Markdown is wrapped at ~100 columns.

## Code comments

- Be concise: comment only what the code cannot say — the *why*, invariants, security reasons,
  references to a spec or RFC.
- **Never reference the original project's source** in code, comments, tests or commit messages:
  no eMule file names (`*.cpp`, `*.h`), line numbers, C++ classes, functions or variables, and no
  "as eMule does" phrasing; describe the protocol or format itself. Name eMule only where the
  feature is about it (importing an eMule profile, interoperability tests).
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
  Java or CSS outside the token definitions. In `njmule-ui` the same colours are CSS custom
  properties: the `--color-*` its `themes` task makes from each AtlantaFX theme and the `--nj-*`
  tokens of `src/styles/njmule.css`.
- Pastel tones for fills and surfaces (`*-subtle`/`*-muted`, bars, charts, chips); text uses the
  theme's `-color-fg-*` and `-color-*-fg` tokens, never a pastel tone.
- Accessibility (WCAG 2.2 AA): text contrast ≥ 4.5:1 (≥ 3:1 for large text), essential icons and
  graphics ≥ 3:1; colour is never the only cue (add icon, tooltip, legend or pattern).
- Every token has a light and a dark value; saturated colours only for real errors and warnings.
- Brand: deep blue `#24427A`, off-white mark `#F4F6FB` and warm orange accents `#F5A65B`
  (`branding/`, README badges); orange stays an accent, never a large surface.

## Engineering rules

- Do not reinvent the wheel: use the JDK or a well-known, maintained open-source library
  (GPL-3.0-compatible license, native-image friendly) before writing a utility, codec, parser or
  algorithm. Write our own only for eD2K/Kad-specific code or when no suitable library exists or
  fits the constraints, and say why in a comment or the commit message.
- `njmule-core` must never depend on JavaFX/AWT or any GUI module.
- Code must stay GraalVM native-image friendly: no undeclared reflection, no dynamic proxies,
  register resources and `ServiceLoader` providers in the reachability metadata.
- Log only through SLF4J with `{}` placeholders; never use external data as a format string.
  Loggers are `private static final Logger logger` (lowerCamelCase: per the Google style a logger
  is not a constant), never `LOG`.
- Never write into the user's eMule installation or config directory.

## Tests

- Every test follows the given/when/then form of Gherkin in plain JUnit (no Cucumber): each test
  method has a meaningful name stating the expected behaviour and its body only calls private step
  methods, in order: the `given...()` steps (optional), one `when...()`, then the `then...()`
  steps. Steps are named as lowerCamelCase sentences and take parameters when reused; shared state
  lives in fields, and a failing `when...()` keeps its exception for `thenExceptionOccurred(...)` /
  `thenNoExceptionOccurred()`. A scenario has one action: independent cases become separate (or
  parameterized) tests, earlier stages of a flow become given steps. Example in `CONTRIBUTING.md`.
