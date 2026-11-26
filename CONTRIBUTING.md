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
4. Run the checks for what you changed:
   - Engine (`njmule-engine/`): `cargo fmt --check`, `cargo clippy --locked --all-targets -- -D warnings`,
     `cargo test --locked`, `cargo deny check`.
   - Interface: `cargo xtask ui` (API types, `vue-tsc`, Vite bundle, Vitest, Prettier, assets).
   - Desktop shell (`njmule-desktop/src-tauri/`, after `cargo xtask ui-site`):
     `cargo fmt --check`, `cargo clippy --locked --release --all-targets -- -D warnings`,
     `cargo test --locked --release`.
   A new or updated dependency also updates its lock file (`Cargo.lock` or `package-lock.json`; see
   [Dependencies](https://njmule.github.io/njMule/developer/dependencies)).
5. Open a pull request whose **title** is a Conventional Commits header; fill in the template.
   PRs are squash-merged.

## Code style

- Rust (`njmule-engine`, `njmule-desktop/src-tauri`): formatted with `cargo fmt` (4-space indent,
  120 columns, `rustfmt.toml`) and checked with Clippy (`-D warnings`). `njmule-engine` forbids
  `unsafe` code and denies `unwrap_used`, `expect_used`, `panic`, `indexing_slicing` and
  `arithmetic_side_effects` in production code.
- LF line endings, UTF-8, and the two `SPDX-FileCopyrightText` / `SPDX-License-Identifier` lines
  at the top of every source and text file (files that cannot carry comments are listed in
  `REUSE.toml`).
- The interface (`njmule-ui`) and website (`website`) are formatted with Prettier (`npm run format`
  there).
- Comments explain *why*, not *what*; keep them short. Never reference eMule's C++ source files or
  symbols.
- Optional hook: `git config core.hooksPath .githooks` (runs `cargo fmt --check`).

## Tests

New behaviour needs tests (`cargo test` and `proptest` for the engine, Vitest for the interface,
`cargo test` for the desktop window). Never commit personal data: copies of a real eMule profile,
captures or screenshots belong in the git-ignored `fixtures-private/` folder.

Tests follow the given/when/then form of Gherkin:

- each test function has a meaningful `should_...` name that states the expected behaviour, and its
  body calls step methods in order: `given_...` (if any), then **one** `when_...`, then one or
  more `then_...`;
- a scenario has one action: independent cases become separate tests, and the earlier stages of a
  longer flow become `given_...` steps;
- shared state lives in a fixture struct, and a `when_...` step that can fail stores its `Result`
  for `then_...` to inspect;
- integration tests in `njmule-engine/tests/` are modules of one test binary (`tests/main.rs`):
  declare every new file there (`mod foo_test;`).

```rust
#[derive(Default)]
struct Sanitizer {
    network_name: String,
    safe_name: String,
}

impl Sanitizer {
    fn given_a_name_from_the_network(&mut self, name: impl Into<String>) {
        self.network_name = name.into();
    }

    fn when_the_name_is_made_safe(&mut self) {
        self.safe_name = path_sanitizer::file_name(&self.network_name);
    }

    fn then_the_safe_name_is(&self, expected: &str) {
        assert_eq!(self.safe_name, expected);
    }

    fn then_the_safe_name_fits_the_length_limit(&self) {
        assert!(self.safe_name.len() <= 200);
    }
}

#[test]
fn should_escape_a_reserved_name_hidden_by_the_length_cut() {
    let mut test = Sanitizer::default();
    test.given_a_name_from_the_network(format!("CON{}x", " ".repeat(300)));

    test.when_the_name_is_made_safe();

    test.then_the_safe_name_is("_CON");
    test.then_the_safe_name_fits_the_length_limit();
}
```
