<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<p align="center"><img src="branding/njmule-icon.svg" alt="njMule logo" width="128"></p>

# njMule

**njMule — Next generation Java Mule** is a modern, open-source eD2K and Kad client written in
Java 25, wire-compatible with the eMule network and able to import an existing eMule profile
(downloads in progress, known files, credits, servers, Kad nodes).

> **Status: pre-alpha (milestone M0).** The project skeleton, the engine loop and the configuration
> layer exist; networking, downloads and the full user interface are being built milestone by
> milestone. It is not usable for file sharing yet.

## Goals

- Full eD2K (servers, client-to-client transfers, source exchange, secure identification) and Kad2.
- A GUI-independent core with a JavaFX desktop front end (AtlantaFX themes) and, later, a web UI.
- Fast and secure by design: virtual threads, crash-safe persistence, hardened parsers — none of
  the defects found in the eMule 0.72a code base.
- Native executables (GraalVM) and installers for Windows, macOS and Linux (64-bit).

## Building

Requirements: JDK 25. Maven is not needed — the Maven Wrapper downloads it.

```bash
./mvnw verify                                   # build, check formatting, run all tests
./mvnw -pl njmule-app -am package               # headless launcher (njmule-app/target)
./mvnw spotless:apply                           # format the code (google-java-format, AOSP)
```

On Windows use `mvnw.cmd`. Run the headless engine (on Windows separate the module path with `;`):

```bash
java -p njmule-app/target/lib:njmule-app/target/classes -m org.njmule.app/org.njmule.app.Main --help
```

Build the native executable (GraalVM; on any OS with Docker, this produces the Linux x64 binary
`njmule-app/target/njmule`):

```bash
docker run --rm -v "$PWD:/work" -v "$HOME/.m2:/root/.m2" -w /work --entrypoint ./mvnw ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 -B -Pnative -pl njmule-app -am package -DskipTests
```

Run the desktop GUI during development:

```bash
./mvnw -pl njmule-gui -am install -DskipTests && ./mvnw -pl njmule-gui javafx:run
```

## Project layout

| Module | Content |
|---|---|
| `njmule-core` | engine, protocol, persistence — no GUI dependency |
| `njmule-app` | headless launcher, command line, logging |
| `njmule-gui` | JavaFX desktop application |
| `njmule-testkit` | test doubles shared by the modules |

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) (Conventional Commits,
Developer Certificate of Origin sign-off, code style) and the
[Code of Conduct](CODE_OF_CONDUCT.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

Copyright (C) 2026 Salvatore Coppola and the njMule contributors.

njMule is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.

njMule is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

The project follows the [REUSE](https://reuse.software) specification: every file carries an SPDX
license identifier (or is listed in [REUSE.toml](REUSE.toml)); license texts are in
[LICENSES/](LICENSES). Third-party components and the attribution to eMule are listed in
[NOTICE](NOTICE).

njMule is an independent re-implementation inspired by and derived from
[eMule](https://www.emule-project.net) (GPL-2.0-or-later). It is not affiliated with or endorsed
by the eMule project.
