<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Contributing to njMule

Thank you for helping! This document explains how to propose changes.

## Licensing of contributions

njMule is licensed under the **GNU General Public License v3.0 or later**. By contributing you
agree that your contribution is licensed under the same terms (inbound = outbound); no copyright
assignment or CLA is required.

Every commit must be signed off according to the
[Developer Certificate of Origin 1.1](https://developercertificate.org/), certifying that you wrote
the change or have the right to submit it under the project license:

```bash
git commit -s -m "feat(kad): add routing zone splitting"
```

which adds `Signed-off-by: Your Name <you@example.org>` to the message. Use your real name.

Only add third-party code or assets whose license is compatible with GPL-3.0 (MIT, BSD, Apache-2.0,
LGPL, GPL-2.0-or-later, …), keep their original notices, and record them in `REUSE.toml` and
`NOTICE`.

## Workflow

1. Open an issue first for anything larger than a small fix.
2. Create a branch named `<type>/<short-kebab-description>` (e.g. `fix/server-udp-min-length`).
3. Commit with [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
   messages: `<type>[scope][!]: <description>` — types `feat`, `fix`, `build`, `ci`, `docs`,
   `perf`, `refactor`, `style`, `test`, `chore`, `revert`.
4. Run `./mvnw verify` (formatting, Checkstyle, Error Prone, tests, coverage, SpotBugs).
5. Open a pull request whose **title** is a Conventional Commits header; fill in the template.
   PRs are squash-merged.

## Code style

- [Google Java Style](https://google.github.io/styleguide/javaguide.html) formatted by
  [palantir-java-format](https://github.com/palantir/palantir-java-format) (PALANTIR style: 4-space
  indent, 120 columns): run `./mvnw spotless:apply`.
- Always qualify instance members with `this.` (enforced by Checkstyle).
- LF line endings, UTF-8, the SPDX header from `config/license/header-java.txt` on every Java file.
- Comments explain *why*, not *what*; keep them short.
- Optional hook: `git config core.hooksPath .githooks`.

## Tests

New behaviour needs tests (JUnit 5, AssertJ; jqwik for codecs and parsers). Never commit personal
data: copies of a real eMule profile, captures or screenshots belong in the git-ignored
`fixtures-private/` folder.
