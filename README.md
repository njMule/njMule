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
    <a href="#download-and-install"><img alt="Platforms: Windows, macOS, Linux" src="https://img.shields.io/badge/platforms-Windows%20%7C%20macOS%20%7C%20Linux-F5A65B?labelColor=24427A"></a>
    <br>
    <a href="https://www.conventionalcommits.org/en/v1.0.0/"><img alt="Conventional Commits 1.0.0" src="https://img.shields.io/badge/Conventional%20Commits-1.0.0-F5A65B?logo=conventionalcommits&logoColor=white&labelColor=24427A"></a>
    <a href="https://semver.org/spec/v2.0.0.html"><img alt="Semantic Versioning 2.0.0" src="https://img.shields.io/badge/SemVer-2.0.0-F5A65B?labelColor=24427A"></a>
    <a href="https://github.com/palantir/palantir-java-format"><img alt="Code style: palantir-java-format" src="https://img.shields.io/badge/code%20style-palantir--java--format-F5A65B?labelColor=24427A"></a>
    <a href="CODE_OF_CONDUCT.md"><img alt="Contributor Covenant 2.1" src="https://img.shields.io/badge/Contributor%20Covenant-2.1-F5A65B?labelColor=24427A"></a>
</p>

**njMule — Next generation Java Mule** is a modern, open-source client for the eD2K and Kad
file-sharing networks, written in Java 25.

> **Status: pre-alpha (`0.0.1-alpha.1`).** No release has been published yet: the packages
> described below are built by the release workflow and will be attached to the first
> [GitHub release](https://github.com/njMule/njMule/releases). Until 1.0.0, features and settings
> may still change. See [CHANGELOG.md](CHANGELOG.md) for everything done so far.

## Contents

- [Features](#features)
- [Download and install](#download-and-install)
- [Usage](#usage)
- [Building from source](#building-from-source)
- [Project layout](#project-layout)
- [Contributing](#contributing)
- [License](#license)

## Features

- **eD2K** — servers, plain or obfuscated (static servers, lists from a URL); searches on the
  server, on all known servers over UDP or on Kad, with file type, size, availability and
  extension filters; downloads checked part by part, with corrupt parts repaired through AICH
  recovery data; uploads with a credit-scored queue, secure identification and source exchange.
- **Kad 2** — routing table, keyword and source searches, publishing of the shared files,
  firewall check, `nodes.dat` from a URL.
- **Files** — shared folders with their subfolders, watched for changes; download and upload
  priorities, categories, preview in a video player, comments and ratings.
- **Desktop app** — JavaFX with AtlantaFX themes (Nord light and dark by default, following the
  system), English and Italian, an icon in the notification area, optional handling of the ed2k
  links clicked in a browser.
- **Web interface** — status, transfers, searches and servers, behind a password.
- **Network** — SOCKS 4/5 and HTTP proxy, UPnP port forwarding, IP filter (`ipfilter.dat`,
  PeerGuardian, P2B) updated from a URL, a weekly scheduler of limits and categories, chat with
  friends and spam filters.
- **Native** — GraalVM native executables and installers for Windows, macOS and Linux: no Java
  runtime is needed to run njMule.

Under the hood: a GUI-independent core on virtual threads, crash-safe persistence and hardened,
size-limited parsers for everything that comes from the network or from disk.

## Download and install

| System | Architecture | Packages |
|---|---|---|
| Windows | x64 (arm64 through the system's x64 emulation) | `.msi` installer, `.zip` archive |
| Linux | x64, arm64 | `.deb`, `.rpm`, `.tar.gz` archive |
| macOS 13 or later | Apple silicon (arm64) | `.dmg` disk image |

Every package carries two programs: **`njmule-gui`**, the desktop app, and **`njmule`**, the
command line, which also runs the engine without a window. Each release also lists a
`SHA256SUMS` file and a CycloneDX software bill of materials (`njmule-<version>-sbom.cdx.json`);
check the downloads with:

```bash
sha256sum --check --ignore-missing SHA256SUMS
```

### Windows

The MSI installs for the current user, without administrator rights: the programs go to
`%LOCALAPPDATA%\Programs\njMule`, with shortcuts in the Start menu and on the desktop and `njmule`
on the user's `PATH`. A newer MSI upgrades the installed one; uninstall from *Settings → Apps*.
An administrator can install for every user instead:

```powershell
msiexec /i njmule-<version>-windows-x64.msi MSIINSTALLPERUSER=""
```

The `.zip` holds the same programs, to run from any folder. The executables are not code-signed
yet, so SmartScreen may ask for confirmation (*More info → Run anyway*). GraalVM and JavaFX have no
Windows arm64 build yet: on Windows on Arm the x64 package runs under emulation.

### Linux

```bash
sudo apt install ./njmule_*_amd64.deb   # Debian, Ubuntu (arm64: *_arm64.deb)
sudo dnf install ./njmule-*.x86_64.rpm  # Fedora, RHEL (arm64: *.aarch64.rpm)
```

The packages put `njmule` and `njmule-gui` in `/usr/bin` (the desktop app with its libraries in
`/usr/lib/njmule`), a menu entry, AppStream metadata and icons, and depend on GTK 3, libXtst and
OpenGL. The `.tar.gz` holds the same programs to run from any folder; its `share/` directory has
the desktop entry, metadata and icon for a manual integration.

### macOS

Open the disk image and drag njMule into Applications. The app is signed ad hoc but not notarized:
macOS refuses it at the first start until it is allowed in *System Settings → Privacy & Security
→ Open Anyway*, or the quarantine flag is removed:

```bash
xattr -dr com.apple.quarantine /Applications/njMule.app
```

The command-line program is `/Applications/njMule.app/Contents/MacOS/njmule`.

### Where njMule keeps its data

| | Windows | macOS | Linux |
|---|---|---|---|
| Settings | `%APPDATA%\njMule\config` | `~/Library/Application Support/njMule/config` | `~/.config/njmule` |
| Data and temp files | `%LOCALAPPDATA%\njMule` | `~/Library/Application Support/njMule` | `~/.local/share/njmule` |
| Logs | `%LOCALAPPDATA%\njMule\logs` | `~/Library/Logs/njMule` | `~/.local/state/njmule/logs` |
| Finished downloads | `%USERPROFILE%\Downloads\njMule` | `~/Downloads/njMule` | `~/Downloads/njMule` |

On Linux the `XDG_*` variables and `XDG_DOWNLOAD_DIR` of `user-dirs.dirs` are honoured. The
option `--config-dir <dir>` keeps everything below one folder instead, for a second profile or
an installation on a removable drive. Uninstalling leaves these folders in place.

## Usage

### Desktop app

Start njMule from the Start menu, the applications menu or Applications. ed2k links given on the
command line (`njmule-gui "ed2k://|file|…|/"`) become downloads, handed to the njMule already
running with the same configuration; the options can make njMule open the ed2k links clicked in a
browser (Windows and Linux, off by default).

### Command line

`njmule` runs the engine without a window until it is stopped, or does one job and exits;
`njmule --help` lists every option.

| Command | Effect |
|---|---|
| `njmule` | run the engine without a window |
| `njmule --exit` | ask the running njMule (window or headless) to save and exit |
| `njmule --search "<expression>" [--global \| --kad]` | search, print the results and exit |
| `njmule --download <ed2k link>...` | download these files, then exit |
| `njmule --share <dir>...` | share these folders too (remembered) |
| `njmule --hash <file>...` | print the ed2k link of each file, with its AICH hash |
| `njmule --kad-bootstrap <host:port>` | join Kad through this node |
| `njmule --config-dir <dir>` | keep all data below `<dir>` |
| `njmule --log-level DEBUG` | override the log level of the preferences |

### Web interface

Turn the web interface on with a password (asked twice; at least 8 characters). It starts with
the engine, headless or with the desktop app, at `http://localhost:4711/` and for this computer
only, unless `--webui-lan` is given; the desktop options set it too. It is served over plain
HTTP: let other computers in only on a trusted network.

```bash
njmule --webui-password [--webui-port <port>] [--webui-lan]
```

## Building from source

| Tool | Needed for |
|---|---|
| JDK 25 (e.g. Eclipse Temurin) | everything; the Maven Wrapper downloads Maven |
| GraalVM Community `25.0.4.1.1` with `GRAALVM_HOME` set | native executables |
| Visual Studio 2022 Build Tools, C++ workload | native executables on Windows |
| Docker | Linux x64 native executables without a local GraalVM |
| [WiX](https://wixtoolset.org/) 5 (`dotnet tool install --global wix --version 5.0.2`), [nfpm](https://nfpm.goreleaser.com/) | Windows MSI, deb and rpm packages |

The Visual Studio Build Tools install with:
`winget install --id Microsoft.VisualStudio.2022.BuildTools --override "--wait --passive --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"`.

### Build and test

On Windows use `.\mvnw.cmd` in place of `./mvnw`.

```bash
./mvnw verify                          # build; formatting, Checkstyle, Error Prone, tests, coverage, SpotBugs
./mvnw spotless:apply                  # format the code (palantir-java-format)
./mvnw -Plicenses package -DskipTests  # THIRD-PARTY.txt and the CycloneDX SBOM
./mvnw -Psecurity verify               # OWASP dependency check (needs NVD_API_KEY)
```

### Run from the build

The command line and headless engine (on Windows separate the module path with `;`):

```bash
./mvnw -pl njmule-app -am package
java -p njmule-app/target/lib:njmule-app/target/classes -m org.njmule.app/org.njmule.app.Main --help
```

The desktop app:

```bash
./mvnw -pl njmule-gui -am install -DskipTests && ./mvnw -pl njmule-gui javafx:run
```

### Native executables

With a local GraalVM, for the current system:

```bash
./mvnw -Pnative -pl njmule-app,njmule-gui -am package -DskipTests
```

The command line is `njmule-app/target/njmule(.exe)`; the desktop app is in
`njmule-gui/target/native/`, its executable next to the JDK libraries it loads. `-pl njmule-app`
alone builds only the command line.

Without a local GraalVM, Docker builds the Linux x64 command line in the same image as CI. The
script compiles a copy of the sources inside the container, because on Docker Desktop (Windows,
macOS) Maven on the bind-mounted checkout is several times slower (about 1.5 minutes instead of 6).
In PowerShell write `"${PWD}:/work"`; in Git Bash prefix the command with `MSYS_NO_PATHCONV=1`:

```bash
docker run --rm -v "$PWD:/work" -v "$HOME/.m2:/root/.m2" --entrypoint bash ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 /work/tools/native-docker.sh
```

The desktop app needs reachability metadata, recorded by a tour of the whole interface under
GraalVM's tracing agent (`-Pnative-tour`) and kept in
`njmule-gui/src/main/resources/META-INF/native-image`; record it again after changing the GUI.
For Linux, `tools/native-gui-docker.sh` records it and builds both executables in an Ubuntu
container (usage in the script).

### Packages

The [release workflow](.github/workflows/release.yml) builds every package on its own system and
is the reference for the exact steps. Locally, after the native build, the Windows MSI:

```powershell
New-Item -ItemType Directory -Force stage\njmule | Out-Null
Copy-Item njmule-app\target\njmule.exe, njmule-gui\target\native\* stage\njmule
wix build packaging\windows\njmule.wxs -wx -arch x64 -d Version=1.2.3 -d Gui=yes -d "BinDir=$PWD\stage\njmule" -d "DocDir=$PWD" -o njmule.msi
```

and the Linux deb and rpm packages (`ARCH` is `amd64` or `arm64`):

```bash
mkdir -p stage/gui stage/doc dist
install -m 755 njmule-app/target/njmule stage/njmule
cp -r njmule-gui/target/native/. stage/gui/
cp README.md CHANGELOG.md NOTICE stage/doc/
VERSION=1.2.3 ARCH=amd64 nfpm package -f packaging/linux/nfpm.yaml -p deb -t dist/
VERSION=1.2.3 ARCH=amd64 nfpm package -f packaging/linux/nfpm.yaml -p rpm -t dist/
```

The macOS bundle is assembled from `packaging/macos/Info.plist` and packed with `hdiutil`.

### Releases

Pushing an annotated tag `vX.Y.Z` equal to `<revision>` in the root `pom.xml` runs the release
workflow: native builds on every system, the GUI tour as a smoke test, the packages, then a draft
GitHub release with the packages, the SBOM, `SHA256SUMS` and the notes of that version from
`CHANGELOG.md`. Publishing the draft stays a manual step; a manual run of the workflow only
builds the packages, as workflow artifacts.

### Benchmarks

JMH benchmarks of the hashing and cipher hot paths (one operation = 1 MiB, so ops/s = MiB/s):

```bash
./mvnw -pl njmule-bench -am package -DskipTests
java -jar njmule-bench/target/benchmarks.jar
```

## Project layout

| Path | Content |
|---|---|
| `njmule-core` | engine, protocol, persistence — no GUI dependency |
| `njmule-app` | headless launcher, command line, logging |
| `njmule-gui` | JavaFX desktop application |
| `njmule-webui` | web interface served by the JDK HTTP server |
| `njmule-testkit` | test doubles shared by the modules |
| `njmule-bench` | JMH benchmarks of the hot paths (not distributed) |
| `packaging` | Windows installer (WiX), Linux packages (nfpm), macOS bundle |
| `branding` | original artwork: master SVG and the generated icons |
| `config` | license header, Checkstyle and SpotBugs configuration |
| `tools` | helper programs and the Docker build scripts |

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
