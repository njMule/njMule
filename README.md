<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<p align="center"><img src="branding/njmule-icon.svg" alt="njMule logo" width="128"></p>

<h1 align="center">njMule</h1>

<p align="center">
    <strong>Next generation Java Mule</strong> — a modern, open-source client for the eD2K and Kad
    file-sharing networks, compatible with eMule.
</p>

<p align="center">
    <a href="https://github.com/njMule/njMule/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/njMule/njMule?label=latest%20release&labelColor=24427A&color=F5A65B&style=flat-square"></a>
    <a href="https://github.com/njMule/njMule/actions/workflows/build.yml"><img alt="Build status" src="https://github.com/njMule/njMule/actions/workflows/build.yml/badge.svg?branch=main&style=flat-square"></a>
    <a href="https://sonarcloud.io/summary/new_code?id=njMule_njMule"><img alt="SonarQube Cloud quality gate" src="https://img.shields.io/sonar/quality_gate/njMule_njMule?server=https%3A%2F%2Fsonarcloud.io&logo=sonarcloud&logoColor=white&labelColor=24427A&style=flat-square"></a>
    <a href="https://sonarcloud.io/summary/new_code?id=njMule_njMule"><img alt="SonarQube Cloud coverage" src="https://img.shields.io/sonar/coverage/njMule_njMule?server=https%3A%2F%2Fsonarcloud.io&logo=sonarcloud&logoColor=white&labelColor=24427A&style=flat-square"></a>
    <a href="LICENSE"><img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-F5A65B?labelColor=24427A&style=flat-square"></a>
    <a href="https://njmule.github.io/njMule/"><img alt="Documentation" src="https://img.shields.io/badge/docs-njmule.github.io-F5A65B?labelColor=24427A&style=flat-square"></a>
</p>

<p align="center">
    <a href="https://njmule.github.io/njMule/docs/intro">Documentation</a> ·
    <a href="#download">Download</a> ·
    <a href="https://njmule.github.io/njMule/docs/features">Features</a> ·
    <a href="https://njmule.github.io/njMule/developer">Developers</a> ·
    <a href="CHANGELOG.md">Changelog</a>
</p>

<p align="center">
    <picture>
        <source media="(prefers-color-scheme: dark)" srcset=".github/assets/njmule-transfers-dark.png">
        <img alt="The transfers of the njMule desktop app: the downloads with their progress, speed, sources, state and what is left, filtered by chips, the first one opened onto its sources, each with a coloured dot telling what it does and, when known, the flag and code of its country" src=".github/assets/njmule-transfers-light.png" width="880">
    </picture>
</p>

## Overview

njMule speaks eD2K and Kad 2, so it works with the existing eMule network: its servers, its Kad
nodes and its clients. One engine runs it all:

- 🖥️ **A desktop app** for Windows, macOS and Linux, in 36 languages, with light, dark and high
  contrast themes;
- 🌍 **the same interface on the web**, behind a password, with a read-only guest;
- ⚙️ **a headless engine** with a command line, a JSON remote-control API and its event stream,
  for servers, NAS, Docker and scripts;
- 📥 **the import of an eMule profile**, only ever read: servers, contacts, known and shared files,
  preferences, and on request the identity with its credits and the unfinished downloads.

It ships as native executables: **no Java to install**. Networking includes protocol obfuscation,
IPv6, connections between firewalled clients over uTP or QUIC, UPnP, NAT-PMP and PCP, SOCKS and HTTP
proxies, binding to a VPN interface and IP filters. The
[feature tour](https://njmule.github.io/njMule/docs/features) has the complete list.

## Download

Every package is on the **[latest release](https://github.com/njMule/njMule/releases/latest)**,
with its checksums, minisign signature and software bill of materials; the Docker image is
`ghcr.io/njmule/njmule` on the
**[GitHub Container Registry](https://github.com/njMule/njMule/pkgs/container/njmule)**.

| System | Packages | Guide |
|---|---|---|
| 🪟 Windows (x64; arm64 through emulation) | `.msi` installer, `.zip` archive | [Install on Windows](https://njmule.github.io/njMule/docs/getting-started/install-windows) |
| 🍎 macOS 13+ (Apple silicon) | `.dmg` disk image | [Install on macOS](https://njmule.github.io/njMule/docs/getting-started/install-macos) |
| 🐧 Linux (x64, arm64) | `.deb`, `.rpm`, `.tar.xz`; static headless `.tar.xz` (x64) | [Install on Linux](https://njmule.github.io/njMule/docs/getting-started/install-linux) |
| 🐳 Docker (amd64, arm64) | `ghcr.io/njmule/njmule` | [Install with Docker](https://njmule.github.io/njMule/docs/getting-started/install-docker) |
| 🗄️ Synology DSM 7 (x86_64) | `.spk` package | [Install on Synology](https://njmule.github.io/njMule/docs/getting-started/install-synology) |

```bash
printf '%s\n' 'a password of 8 characters or more' > webui_password
docker run -d --name njmule -p 4662:4662/tcp -p 4672:4672/udp -p 4711:4711/tcp -v njmule:/njmule \
    -v "$PWD/webui_password:/run/secrets/njmule_webui_password:ro" ghcr.io/njmule/njmule
```

Then follow the [first start](https://njmule.github.io/njMule/docs/getting-started/first-run), and
[check your download](https://njmule.github.io/njMule/docs/getting-started/verify-downloads) if you
like. The executables are not code-signed yet: Windows SmartScreen and macOS Gatekeeper ask once.

## Documentation

The **[njMule documentation](https://njmule.github.io/njMule/)** covers everything:

- **[User guide](https://njmule.github.io/njMule/docs/intro)**: installing, importing from eMule,
  every screen and option of the desktop app, the web interface and reverse proxies, headless use,
  services and the command line, networking, your data, updates, troubleshooting and FAQ.
- **[Developer guide](https://njmule.github.io/njMule/developer)**: building from source, tests,
  native images, packaging, translations, releases and the
  [remote-control API](https://njmule.github.io/njMule/developer/api) reference.

The documentation's sources are in [`website/`](website); its text is under CC BY-SA 4.0.

## Building from source

With a JDK 25 (Gradle downloads everything else; the desktop window also needs Rust, from rustup):

```bash
./gradlew build                                # build, check and test
./gradlew :njmule-app:run --args="--help"      # the command line
./gradlew :njmule-desktop:shell                # the desktop window
```

[Building njMule](https://njmule.github.io/njMule/developer/building) explains the options, the
native executables and the packages.

## Contributing

Contributions are welcome: read [CONTRIBUTING.md](CONTRIBUTING.md) (Conventional Commits, signed-off
commits, code style, squash-merged pull requests to a protected `main`) and the
[Code of Conduct](CODE_OF_CONDUCT.md). To report a vulnerability, see [SECURITY.md](SECURITY.md).

[![Conventional Commits 1.0.0](https://img.shields.io/badge/Conventional%20Commits-1.0.0-F5A65B?logo=conventionalcommits&logoColor=white&labelColor=24427A&style=flat-square)](https://www.conventionalcommits.org/en/v1.0.0/)
[![Semantic Versioning 2.0.0](https://img.shields.io/badge/SemVer-2.0.0-F5A65B?labelColor=24427A&style=flat-square)](https://semver.org/spec/v2.0.0.html)
[![Contributor Covenant 2.1](https://img.shields.io/badge/Contributor%20Covenant-2.1-F5A65B?labelColor=24427A&style=flat-square)](CODE_OF_CONDUCT.md)
[![REUSE compliant](https://img.shields.io/badge/REUSE-compliant-F5A65B?labelColor=24427A&style=flat-square)](https://reuse.software)

## Support the project

If njMule is useful to you, you can support its development:

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-F5A65B?logo=buymeacoffee&logoColor=white&labelColor=24427A&style=for-the-badge)](https://buymeacoffee.com/scoppolap)

## License

Copyright (C) 2026 Salvatore Coppola and the njMule contributors.

njMule is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version. It is distributed in the hope that
it will be useful, but **WITHOUT ANY WARRANTY**; see the License for details. Third-party
components and the attribution to eMule are listed in [NOTICE](NOTICE); the project follows the
[REUSE](https://reuse.software) specification.

njMule is an independent re-implementation inspired by and derived from
[eMule](https://www.emule-project.net) (GPL-2.0-or-later). It is not affiliated with or endorsed
by the eMule project.

## Acknowledgements

Many thanks to the eMule team and to everyone who has contributed to
[eMule](https://www.emule-project.net) over more than twenty years: their client keeps the eD2K
and Kad networks alive, and its open source code made njMule possible.
