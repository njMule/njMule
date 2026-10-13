<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<p align="center"><img src="branding/njmule-icon.svg" alt="njMule logo" width="128"></p>

<h1 align="center">njMule</h1>

<p align="center">
    <a href="https://github.com/njMule/njMule/actions/workflows/build.yml"><img alt="Build status" src="https://github.com/njMule/njMule/actions/workflows/build.yml/badge.svg?branch=main"></a>
    <a href="https://sonarcloud.io/summary/new_code?id=njMule_njMule"><img alt="SonarQube Cloud quality gate" src="https://img.shields.io/sonar/quality_gate/njMule_njMule?server=https%3A%2F%2Fsonarcloud.io&logo=sonarcloud&logoColor=white&labelColor=24427A"></a>
    <a href="https://sonarcloud.io/summary/new_code?id=njMule_njMule"><img alt="SonarQube Cloud coverage" src="https://img.shields.io/sonar/coverage/njMule_njMule?server=https%3A%2F%2Fsonarcloud.io&logo=sonarcloud&logoColor=white&labelColor=24427A"></a>
    <a href="LICENSE"><img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-F5A65B?labelColor=24427A"></a>
    <a href="https://reuse.software"><img alt="REUSE compliant" src="https://img.shields.io/badge/REUSE-compliant-F5A65B?labelColor=24427A"></a>
    <a href="https://github.com/njMule/njMule/commits/main"><img alt="Last commit" src="https://img.shields.io/github/last-commit/njMule/njMule?color=F5A65B&labelColor=24427A"></a>
    <br>
    <a href="https://openjdk.org/projects/jdk/25/"><img alt="Java 25" src="https://img.shields.io/badge/Java-25-F5A65B?logo=openjdk&logoColor=white&labelColor=24427A"></a>
    <a href="https://www.graalvm.org/latest/reference-manual/native-image/"><img alt="GraalVM native image" src="https://img.shields.io/badge/GraalVM-native%20image-F5A65B?labelColor=24427A"></a>
    <a href="#building"><img alt="Platforms: Windows, macOS, Linux" src="https://img.shields.io/badge/platforms-Windows%20%7C%20macOS%20%7C%20Linux-F5A65B?labelColor=24427A"></a>
    <br>
    <a href="https://www.conventionalcommits.org/en/v1.0.0/"><img alt="Conventional Commits 1.0.0" src="https://img.shields.io/badge/Conventional%20Commits-1.0.0-F5A65B?logo=conventionalcommits&logoColor=white&labelColor=24427A"></a>
    <a href="https://semver.org/spec/v2.0.0.html"><img alt="Semantic Versioning 2.0.0" src="https://img.shields.io/badge/SemVer-2.0.0-F5A65B?labelColor=24427A"></a>
    <a href="https://github.com/google/google-java-format"><img alt="Code style: google-java-format" src="https://img.shields.io/badge/code%20style-google--java--format-F5A65B?labelColor=24427A"></a>
    <a href="CODE_OF_CONDUCT.md"><img alt="Contributor Covenant 2.1" src="https://img.shields.io/badge/Contributor%20Covenant-2.1-F5A65B?labelColor=24427A"></a>
</p>

**njMule — Next generation Java Mule** is a modern, open-source eD2K and Kad client written in
Java 25, wire-compatible with the eMule network and able to import an existing eMule profile
(downloads in progress, known files, credits, servers, Kad nodes).

> **Status: pre-alpha (milestone M4).** njMule connects to eD2K servers (plain or obfuscated),
> searches them over TCP and UDP, downloads files from other clients verifying every part, and
> shares files in turn: an upload queue with credits, secure identification and source exchange.
> The protocol codecs, file hashing and readers for every eMule data file are in place. Kad, the
> import of an eMule profile and the user interface come in the next milestones.

## Goals

- Full eD2K (servers, client-to-client transfers, source exchange, secure identification) and Kad2.
- A GUI-independent core with a JavaFX desktop front end (AtlantaFX themes) and, later, a web UI.
- Fast and secure by design: virtual threads, crash-safe persistence, hardened parsers — none of
  the defects found in the eMule code base.
- Native executables (GraalVM) and installers for Windows, macOS and Linux (64-bit).

## Building

Requirements: JDK 25. Maven is not needed — the Maven Wrapper downloads it.

```bash
./mvnw verify                                   # build, check formatting, run all tests
./mvnw -pl njmule-app -am package               # headless launcher (njmule-app/target)
./mvnw spotless:apply                           # format the code (google-java-format, AOSP)
./mvnw -pl njmule-bench -am package -DskipTests # JMH benchmarks: java -jar njmule-bench/target/benchmarks.jar
```

On Windows use `mvnw.cmd`. Run the headless engine (on Windows separate the module path with `;`):

```bash
java -p njmule-app/target/lib:njmule-app/target/classes -m org.njmule.app/org.njmule.app.Main --help
```

Build the native executable for the current OS with a local GraalVM Community for JDK 25
(`25.0.4.1.1`, the version used in CI) and `GRAALVM_HOME` pointing to it. On Windows,
`native-image` also needs the Visual Studio 2022 Build Tools with the C++ workload
(`winget install --id Microsoft.VisualStudio.2022.BuildTools --override "--wait --passive --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"`).

```bash
./mvnw -Pnative -pl njmule-app -am package -DskipTests   # njmule-app/target/njmule(.exe)
```

Without a local GraalVM, Docker builds the Linux x64 binary in the same image as CI. The script
compiles a copy of the sources inside the container, because on Docker Desktop (Windows, macOS)
Maven working on the bind-mounted checkout is several times slower (about 1.5 minutes instead of 6):

```bash
docker run --rm -v "$PWD:/work" -v "$HOME/.m2:/root/.m2" --entrypoint bash ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 /work/tools/native-docker.sh
```

In PowerShell write `"${PWD}:/work"`; in Git Bash prefix the command with `MSYS_NO_PATHCONV=1`.

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
| `njmule-bench` | JMH benchmarks of the hot paths (not distributed) |

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
