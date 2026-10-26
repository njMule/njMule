<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<p align="center"><img src="branding/njmule-icon.svg" alt="njMule logo" width="128"></p>

<h1 align="center">njMule</h1>

<p align="center">
    <strong>Next generation Java Mule</strong> — a modern, open-source client for the eD2K and Kad
    file-sharing networks, written in Java 25.
</p>

<p align="center">
    <a href="https://github.com/njMule/njMule/actions/workflows/build.yml"><img alt="Build status" src="https://github.com/njMule/njMule/actions/workflows/build.yml/badge.svg?branch=main&style=flat-square"></a>
    <a href="https://sonarcloud.io/summary/new_code?id=njMule_njMule"><img alt="SonarQube Cloud quality gate" src="https://img.shields.io/sonar/quality_gate/njMule_njMule?server=https%3A%2F%2Fsonarcloud.io&logo=sonarcloud&logoColor=white&labelColor=24427A&style=flat-square"></a>
    <a href="https://sonarcloud.io/summary/new_code?id=njMule_njMule"><img alt="SonarQube Cloud coverage" src="https://img.shields.io/sonar/coverage/njMule_njMule?server=https%3A%2F%2Fsonarcloud.io&logo=sonarcloud&logoColor=white&labelColor=24427A&style=flat-square"></a>
    <a href="LICENSE"><img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-F5A65B?labelColor=24427A&style=flat-square"></a>
    <a href="https://openjdk.org/projects/jdk/25/"><img alt="Java 25" src="https://img.shields.io/badge/Java-25-F5A65B?logo=openjdk&logoColor=white&labelColor=24427A&style=flat-square"></a>
    <a href="#download-and-install"><img alt="Platforms: Windows, macOS, Linux, Docker, Synology DSM" src="https://img.shields.io/badge/platforms-Windows%20%7C%20macOS%20%7C%20Linux%20%7C%20Docker%20%7C%20Synology%20DSM-F5A65B?labelColor=24427A&style=flat-square"></a>
</p>

<p align="center">
    <a href="#features">Features</a> ·
    <a href="#download-and-install">Download</a> ·
    <a href="#usage">Usage</a> ·
    <a href="#building-from-source">Build</a> ·
    <a href="#contributing">Contribute</a> ·
    <a href="CHANGELOG.md">Changelog</a>
</p>

<p align="center">
    <picture>
        <source media="(prefers-color-scheme: dark)" srcset=".github/assets/njmule-transfers-dark.png">
        <img alt="The transfers of the njMule desktop app: the downloads with their progress, speed, sources, state and what is left, filtered by chips, the first one opened onto its sources, each with a coloured dot telling what it does" src=".github/assets/njmule-transfers-light.png" width="880">
    </picture>
</p>

> [!NOTE]
> **First release (`0.1.0`).** njMule works on the eD2K and Kad networks today; its features and
> settings may still change before 1.0.0. The packages are on the
> [releases page](https://github.com/njMule/njMule/releases).

## Overview

- 🔌 **Wire-compatible** — speaks eD2K and Kad 2, so it works with the existing eMule network.
- 📦 **Nothing to install first** — GraalVM native executables and installers for Windows, macOS
  and Linux, and a Docker image for servers and NAS; no Java runtime is required.
- 🛡️ **Hardened by design** — size-limited parsers for all input from the network and from disk,
  and crash-safe persistence.
- 🖥️ **Desktop, headless or remote** — a JavaFX app, a command-line engine, a web interface and a
  JSON API, all driving the same GUI-independent core built on virtual threads.

## Features

| | |
|---|---|
| 🌐 **eD2K** | Server connections with optional protocol obfuscation, static servers, a starting server list shipped with njMule and `server.met` updates from a URL. Searches on the connected server, on all known servers over UDP or on Kad, filtered by file type, size, availability and extension. Downloads verified part by part, with corrupt parts repaired from AICH recovery data. Uploads through a credit-scored queue, with secure identification and source exchange. |
| 🕸️ **Kad 2** | Routing table, keyword and source lookups, publishing of shared files and of their comments and ratings (notes), TCP and UDP firewall checks, buddies and callbacks through which firewalled clients are reached, bootstrap from the contacts shipped with njMule or from a `nodes.dat` URL. |
| 📂 **File management** | Shared directories with recursive sharing and change monitoring; download and upload priorities, categories, preview in an external video player, comments and ratings; downloads that receive nothing for a number of days can be deleted by themselves (off by default). |
| 📥 **Coming from eMule** | Import of an eMule profile, offered at the first start when one is found and only ever read: preferences, servers, Kad contacts, known files, shared directories and the IP filter, and on request the identity with its credits and copies of the unfinished downloads. |
| 🖥️ **Desktop app** | JavaFX with AtlantaFX themes (Nord light and dark by default, following the system, and the system colours under Windows high contrast), 36 languages including right-to-left ones with a mirrored layout, system tray integration, optional registration as the `ed2k://` link handler, start at login and keeping the computer awake while files are transferred (both off by default). |
| 🌍 **Web interface** | The desktop app in the browser, behind a password and in its 36 languages: transfers, searches with filters, shared folders, messages and friends, the network with its servers, Kad and log, statistics and the settings; a guest password of its own only lets one look. |
| 🔧 **Networking** | IPv6 alongside IPv4, connections between firewalled clients through their routers (QUIC or uTP), SOCKS 4/5 and HTTP CONNECT proxies, binding to one network interface (such as a VPN) that keeps njMule offline while it is gone, port forwarding with UPnP, NAT-PMP or PCP, IP filtering (`ipfilter.dat`, PeerGuardian, P2B) with updates from a URL, and a weekly scheduler for bandwidth limits and categories. |
| 📊 **Statistics** | Rates over the last ten minutes, hour or day, session and all-time totals with the protocol's overhead, figures for each shared file, a 90-day history of the clients met and, optionally, their countries with flags ([IP Geolocation by DB-IP](https://db-ip.com)). |
| 🔔 **Notifications** | A completed or failed download, a chat message or a temp disk short of space, told as chosen by an e-mail through the user's SMTP server (always encrypted), a web hook (ntfy, Gotify) or a program of the user's, all off by default; a sound of the user's for finished downloads and chat messages. |
| 💬 **Messaging** | Chat with CAPTCHA verification, a friends list and spam filters. |

<details>
<summary>The 36 languages of the desktop application</summary>

English, Catalan, Czech, Danish, German, Estonian, Spanish, French, Galician, Italian, Latvian,
Lithuanian, Hungarian, Dutch, Norwegian Bokmål, Polish, Brazilian and European Portuguese,
Romanian, Albanian, Slovenian, Finnish, Swedish, Vietnamese, Turkish, Greek, Bulgarian, Russian,
Ukrainian, Arabic, Persian, Hebrew, Simplified and Traditional Chinese, Japanese and Korean.

</details>

## Download and install

[![Latest release](https://img.shields.io/github/v/release/njMule/njMule?label=latest%20release&labelColor=24427A&color=F5A65B&style=flat-square)](https://github.com/njMule/njMule/releases/latest)

Download the package for your system from the
**[latest release](https://github.com/njMule/njMule/releases/latest)**; the Docker image is on the
**[GitHub Container Registry](https://github.com/njMule/njMule/pkgs/container/njmule)**.

| Operating system | Architecture | Files of the release |
|---|---|---|
| 🪟 Windows | x64 (arm64 through the system's x64 emulation) | installer `njmule-<version>-windows-x64.msi`, archive `njmule-<version>-windows-x64.zip` |
| 🐧 Linux | x64 | `njmule_<version>_amd64.deb`, `njmule-<version>-1.x86_64.rpm`, archive `njmule-<version>-linux-x64.tar.xz`; headless engine only, for any x64 Linux: `njmule-<version>-linux-x64-static.tar.xz` |
| 🐧 Linux | arm64 | `njmule_<version>_arm64.deb`, `njmule-<version>-1.aarch64.rpm`, archive `njmule-<version>-linux-arm64.tar.xz` |
| 🍎 macOS 13 or later | Apple silicon (arm64) | disk image `njmule-<version>-macos-arm64.dmg` |
| 🐳 Docker | `linux/amd64`, `linux/arm64` | image `ghcr.io/njmule/njmule`, tagged `latest`, `<major>.<minor>` and `<version>` (headless engine and web interface) |
| 🗄️ Synology DSM 7 | x86_64 | package `njmule-<version>-synology-x86_64.spk` (headless engine and web interface) |

With the [GitHub CLI](https://cli.github.com/) the files of the latest release can also be fetched
from a terminal, for example the Debian package of a 64-bit PC with its checksums:

```bash
gh release download --repo njMule/njMule --pattern '*_amd64.deb' --pattern 'SHA256SUMS*'
docker pull ghcr.io/njmule/njmule   # the Docker image
```

Every desktop package contains **`njmule-gui`**, the desktop application, and **`njmule`**, the
command-line interface, which also runs the engine headless, around a single native executable. On
Linux and macOS `njmule` is a script that runs the desktop application's executable without a
window. On Windows `njmule.exe` is that executable, a console program, and `njmule-gui.exe` a small
program that starts it for the desktop, as `javaw.exe` does for `java.exe`. Each release also
provides a `SHA256SUMS` file and a CycloneDX software bill of materials
(`njmule-<version>-sbom.cdx.json`). Verify the downloaded files with:

```bash
sha256sum --check --ignore-missing SHA256SUMS
```

`SHA256SUMS` is signed with [minisign](https://jedisct1.github.io/minisign/), with the key that
also signs the update information (`njmule-update.json`), and GitHub attests where each file was
built. Check the signature, then that a file comes from this repository's release workflow:

```bash
minisign -Vm SHA256SUMS -P RWQqpSb9M7CwntTJXDrwmcHlrGiA5i1y3EWMX7N2N9yWQMrO+BEz0i38
```

The key is the first one in
[`keys.pub`](njmule-core/src/main/resources/org/njmule/core/update/keys.pub); should it ever be
lost or leaked, releases are signed with the standby key listed after it.

```bash
gh attestation verify njmule-<version>-windows-x64.msi --repo njMule/njMule
```

<details>
<summary><strong>Windows</strong></summary>

The MSI performs a per-user installation and requires no administrator rights. It installs the
executables in `%LOCALAPPDATA%\Programs\njMule`, creates Start menu and desktop shortcuts and adds
the installation directory to the user's `PATH`. Installing a newer MSI upgrades the existing
installation; uninstall from *Settings → Apps*, which gives ed2k links back to the program that
opened them before, if njMule still opens them, and offers a box that also deletes the profile
(settings, lists, identity, unfinished downloads, caches and logs, to the Recycle Bin); the
completed downloads stay. A per-machine installation, for all users, requires administrator rights:

```powershell
msiexec /i njmule-<version>-windows-x64.msi MSIINSTALLPERUSER=""
```

njMule can also run as the Windows service "njMule", headless with its web interface, started with
Windows before anyone logs in. `njmule --service=install` sets it up (Windows asks for the
administrator's rights, then njMule for the web interface password; `--webui-lan` and the other web
interface options apply). The service runs as the virtual account `NT SERVICE\njMule`, keeps its
profile in `%ProgramData%\njMule`, and a firewall rule lets other clients in on private and domain
networks. `--service=start`, `stop`, `status` and `uninstall` do the rest; uninstalling keeps the
profile. A per-machine MSI installs the service at once with `NJMULE_SERVICE=1
NJMULE_WEBUI_PASSWORD_FILE=<file>`, a file holding the password; it stops the service while
upgrading and removes it with njMule.

The `.zip` archive contains the same executables and runs from any directory. The executables are
not code-signed yet, so SmartScreen may require confirmation (*More info → Run anyway*). GraalVM
and JavaFX do not support Windows on Arm yet; on those systems the x64 package runs under emulation.

</details>

<details>
<summary><strong>Linux</strong></summary>

```bash
sudo apt install ./njmule_*_amd64.deb   # Debian, Ubuntu (arm64: *_arm64.deb)
sudo dnf install ./njmule-*.x86_64.rpm  # Fedora, RHEL (arm64: *.aarch64.rpm)
```

The packages install `njmule` and `njmule-gui` in `/usr/bin` (the desktop application and its
shared libraries reside in `/usr/lib/njmule`; `njmule` runs it headless), together with a desktop
entry, AppStream metadata and icons. The desktop entry starts the application as `njMule`, the name
process lists show for it, however it was started. They depend on GTK 3, libXtst and OpenGL; the
command line itself needs none of them. The `.tar.xz` archive contains the same programs and runs
from any directory; its `share/` directory provides the desktop entry, metadata and icon for manual
desktop integration. Removing a package leaves each user's profile in their home (`~/.config`,
`~/.local/share`, `~/.local/state` and `~/.cache`, folder `njmule`); `njmule --remove-profile`,
before removing it, deletes the profile of the user who runs it, after asking. Where those
executables do not start, on a distribution with an older C library or with musl such as Alpine,
the headless engine of
`njmule-<version>-linux-x64-static.tar.xz` runs alone: it is linked statically and needs no
library of the system, and it uses less memory than the desktop executable running headless.

The packages also install a systemd user unit that runs the engine headless as the logged-in
user (never as root), restarting it if it fails:

```bash
systemctl --user enable --now njmule   # start now and at each login
loginctl enable-linger                 # optional: keep it running while logged out
journalctl --user -u njmule            # its log
```

The unit and the desktop application share the configuration, so only one of them runs at a
time; control the headless engine through the web interface.

</details>

<details>
<summary><strong>macOS</strong></summary>

Open the disk image and drag njMule into Applications. The application is ad-hoc signed but not
notarized, so Gatekeeper blocks its first launch: allow it in *System Settings → Privacy &
Security → Open Anyway*, or remove the quarantine attribute:

```bash
xattr -dr com.apple.quarantine /Applications/njMule.app
```

The command-line interface is `/Applications/njMule.app/Contents/MacOS/njmule`. *Uninstall njMule*,
in the disk image, moves the application to the Trash and, when its box is ticked, the profile in
`~/Library` too; the completed downloads stay.

</details>

<details>
<summary><strong>Docker</strong></summary>

The image `ghcr.io/njmule/njmule` runs the headless engine with its web interface. It holds only
the native executable: on amd64 the static one, alone on an empty base (no libc, no shell, no
package manager); on arm64 the glibc one on a distroless base. It runs as user and group 1000, and keeps all its data in the volume `/njmule` (`config`, `data`, `temp`, `incoming`, …);
incomplete (`/njmule/temp`) and completed downloads (`/njmule/incoming`) can be mounted on their
own, preferably on the same disk. At every start the web interface takes its password (8
characters at least) from the first line of the secret `/run/secrets/njmule_webui_password` and
listens on port 4711 of every address of the container:

```bash
mkdir -p ~/njmule/incoming
printf '%s\n' 'a password of 8 characters or more' > ~/njmule/webui_password
docker run -d --name njmule --restart unless-stopped --stop-timeout 30 \
    -p 4662:4662/tcp -p 4672:4672/udp -p 4711:4711/tcp \
    -v njmule:/njmule -v ~/njmule/incoming:/njmule/incoming \
    -v ~/njmule/webui_password:/run/secrets/njmule_webui_password:ro \
    ghcr.io/njmule/njmule
```

The same with Docker Compose ([`packaging/docker/compose.yaml`](packaging/docker/compose.yaml)),
the password in `webui_password.txt` beside it:

```yaml
services:
    njmule:
        image: ghcr.io/njmule/njmule:latest
        restart: unless-stopped
        stop_grace_period: 30s
        ports: ["4662:4662/tcp", "4672:4672/udp", "4711:4711/tcp"]
        volumes:
            - njmule:/njmule
            - ./incoming:/njmule/incoming
            - ./temp:/njmule/temp
        secrets: [njmule_webui_password]
volumes:
    njmule:
secrets:
    njmule_webui_password:
        file: ./webui_password.txt
```

- Open the web interface by the address of the host, `http://192.168.1.10:4711/` for example.
  Against DNS rebinding other host names than `localhost` are refused unless listed with
  `--webui-host`, such as `nas.local` or the public name of a reverse proxy (see
  [Web interface](#web-interface)): `docker run … ghcr.io/njmule/njmule --webui-password-file
  /run/secrets/njmule_webui_password --webui-lan --webui-host nas.local`.
- For a high ID, the eD2K TCP port (4662) and the client UDP port of eD2K and Kad (4672) must be
  forwarded to the host by the router; ports changed in the options are published unchanged
  (`-p 5000:5000/tcp`). UPnP does not cross Docker's bridge network, but works with
  `--network host`.
- `docker stop` asks njMule to save its state and exit (SIGTERM); `--stop-timeout 30` and
  `stop_grace_period: 30s` give it the time.
- Host directories mounted in the container must belong to UID 1000
  (`sudo chown -R 1000:1000 ~/njmule/incoming`). To run as another user, add `--user <uid>:<gid>`
  and mount a directory of that user on `/njmule` itself.
- A changed secret is the new password from the next start. Arguments replace the default ones
  (`--webui-password-file /run/secrets/njmule_webui_password --webui-lan`): add `--webui-api` to
  them for the remote-control API, then `docker exec njmule njmule --config-dir /njmule --remote
  status` drives it.
- `docker build -f packaging/docker/Dockerfile -t njmule .` builds the image from the sources, for
  the platform of the machine that builds it.

</details>

<details>
<summary><strong>Synology DSM</strong></summary>

The `.spk` package runs the headless engine with its web interface on DSM 7.0 or later (x86_64
models, those with a Celeron or Atom included), as a user of its own without administrator rights. Install it from *Package Center →
Manual Install*; DSM warns that the package is not signed by Synology. The install wizard asks for
the web interface password (8 characters at least), of which only a hash is kept.

- Completed downloads go to the shared folder `njmule` that the package creates; the settings,
  incomplete files and logs stay in the package's own folder, `/var/packages/njmule/var`.
- Open the interface from Package Center or the DSM main menu, or at `http://<NAS address>:4711/`.
  It answers to the addresses of the NAS, to its name (`<name>` and `<name>.local`) and to the
  QuickConnect name of the address DSM is opened by; other names, such as a reverse proxy's, go on
  its *Settings* page.
- The DSM firewall lists the ports as njMule services: 4711/tcp (web interface), 4662/tcp (eD2K)
  and 4672/udp (eD2K and Kad). For a high ID the router forwards the last two to the NAS, or UPnP
  asks it to.
- *Stop* in Package Center saves the state before njMule exits. A newer `.spk` installed the same
  way upgrades the package and keeps its settings and downloads.
- The executable inside is the static one of `njmule-<version>-linux-x64-static.tar.xz`, since the
  C library of DSM is too old for the other Linux executables.

</details>

<details>
<summary><strong>Data and configuration directories</strong>, portable mode</summary>

njMule follows the conventions of each operating system (Known Folders on Windows, the Library
on macOS, the XDG Base Directory specification on Linux). Default locations:

| Purpose | Windows | macOS | Linux |
|---|---|---|---|
| Configuration | `%APPDATA%\njMule\config` | `~/Library/Application Support/njMule/config` | `~/.config/njmule` |
| Desktop app state | `%APPDATA%\njMule\gui` | `~/Library/Application Support/njMule/gui` | `~/.config/njmule/gui` |
| Application data | `%LOCALAPPDATA%\njMule\data` | `~/Library/Application Support/njMule/data` | `~/.local/share/njmule` |
| Incomplete downloads | `%LOCALAPPDATA%\njMule\temp` | `~/Library/Application Support/njMule/temp` | `~/.local/share/njmule/temp` |
| Cache | `%LOCALAPPDATA%\njMule\cache` | `~/Library/Caches/org.njmule.njMule` | `~/.cache/njmule` |
| Logs | `%LOCALAPPDATA%\njMule\logs` | `~/Library/Logs/njMule` | `~/.local/state/njmule/logs` |
| Completed downloads | `%USERPROFILE%\Downloads\njMule` | `~/Downloads/njMule` | `~/Downloads/njMule` |

On Linux, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_CACHE_HOME` and `XDG_STATE_HOME` take
precedence when set, and the download directory follows `XDG_DOWNLOAD_DIR` (`user-dirs.dirs`).
The directories for incomplete and completed downloads can be changed in the options.
Uninstalling njMule does not remove any of these directories.

**Portable mode.** An empty `portable.flag` file next to the executables extracted from the `.zip`
or `.tar.xz` archive keeps all data in a `data` directory beside them, for example on a removable
drive. The option `--config-dir <dir>` places all data below `<dir>` instead, for example to run a
separate profile. In both cases the root contains `config`, `data`, `temp`, `cache`, `logs`, `gui`
and `incoming`.

</details>

### Updates

njMule tells when a new version is out: at each start and once a day it asks GitHub for the
update information of the newest release, through the proxy and the network interface chosen in the
options, and believes it only when it is signed by a key built into njMule. The request carries
nothing but njMule's version in its `User-Agent`; GitHub sees the address it comes from. The
desktop application then shows a notice and its *Updates* window, the web interface a notice on its
status page, the headless engine a line in its log.

*Download and install* fetches the package of the new version for this installation and keeps it
only when its length and SHA-256 hash are those of the signed information; a dropped download goes
on where it stopped. Where njMule may replace its own files, *Restart to update* saves everything,
stops, puts the new version in place and starts again: the Windows installer through msiexec
(Windows asks for administrator rights when njMule is installed for all users), the Windows zip and
the Linux archives file by file, the macOS app as a whole bundle. The old files stay beside the new
ones (`*.old`) until the new version has run for 30 seconds, and come back if it does not start;
the configuration is backed up before a new version first uses it. The Debian and RPM packages, the
Synology package and the Docker image are updated as usual (`apt`, `dnf`, the Package Center,
`docker compose pull`): njMule downloads the package and tells how.

In the options (or `update.mode`, `update.channel`, `update.installAt` and `update.nightHours` in
`njmule.properties`) the check can be left to the user's request, or new versions downloaded, or
also installed when njMule exits, and optionally in the night hours for a computer left on for days;
test versions (alpha, beta, release candidates) can be included, and a test version looks for test
versions by default. `njmule --check-update` looks once and exits with 0 when this version is the
newest, 100 when a newer one is out and 1 when the check failed; `njmule --update` also downloads and
installs it (stop the running njMule first). A service under systemd installs at its stop and, when
it restarts to update, exits with code 75: `Restart=on-failure` starts the new version.

## Usage

### Desktop application

Launch njMule from the Start menu, the applications menu or the Applications folder. At the first
start a wizard sets up the language and look, the nick, the folders, the ports and the speed of the
line, and offers to import an eMule profile found on the computer; every page can be skipped, and
*⋯ → Set up njMule* opens it again, as *⋯ → Import from eMule* does the import alone.

ed2k links passed on the command line (`njmule-gui "ed2k://|file|…|/"`) are added as downloads; if
an instance is already running with the same configuration, the links are forwarded to it. The
options can register njMule as the handler of `ed2k://` links opened in a browser (Windows and
Linux; disabled by default).

### Command-line interface

`njmule` either runs the engine headless until it is stopped (Ctrl+C or `njmule --exit`), or
performs a single task and exits. `njmule --help` lists all options.

| Command | Description |
|---|---|
| `njmule` | run the engine headless |
| `njmule --exit` | ask the running instance (desktop or headless) to save its state and exit, and wait for it (30 s at most) |
| `njmule --search "<expression>" [--global \| --kad]` | search, print the results and exit |
| `njmule --download <ed2k link>...` | download the given files, then exit |
| `njmule --share <dir>...` | share additional directories (persisted) |
| `njmule --hash <file>...` | print the ed2k link of each file, including its AICH hash |
| `njmule --kad-bootstrap <host:port>` | join Kad through the given node |
| `njmule --import-emule <dir> [--dry-run]` | import an eMule profile, only reading it; `--adopt-emule-identity` and `--import-downloads` also take its identity and its downloads |
| `njmule --config-dir <dir>` | keep all data below `<dir>` |
| `njmule --log-level DEBUG` | override the configured log level |
| `njmule --check-update` | look for a new version, print it with how to install it and exit (100 when one is out) |
| `njmule --update` | look for a new version, download it, check it and install it where njMule may replace itself |

### Web interface

The web interface is enabled by setting a password (prompted twice, minimum 8 characters), from
the command line or from the options of the desktop application:

```bash
njmule --webui-password [--webui-port <port>] [--webui-lan]
```

Scripts and services read the password from the first line of a file instead, such as a Docker or
systemd secret: with `--webui-password`, `--webui-password-file <file>` sets it without asking; on
its own, it sets the password at every start, so that the file stays the reference, and runs
njMule.

It starts together with the engine, headless or with the desktop application, on
`http://localhost:4711/`, and is laid out as the desktop application: the places in a sidebar, a
top bar with one field to search or paste eD2K links, the rates and the state of the networks. It listens on the loopback interface only, unless `--webui-lan` makes it
listen on all interfaces.

Against DNS rebinding it answers only to `localhost`, to IP addresses with `--webui-lan`, and to
the host names listed with `--webui-host <name>` (repeated, or separated by commas), in the web
interface settings of the desktop application or on the Settings page of the web interface itself.
With `--webui-lan` it also answers to the Synology QuickConnect name of the address it was reached
on (`192-168-1-80.<id>.direct.quickconnect.to`, which only ever points to that address), the one
under which DSM opens it:

```bash
njmule --webui-password --webui-lan --webui-host nas.local
```

Behind a reverse proxy (Traefik, nginx, Caddy) list its public name, `--webui-host
njmule.example.com`, and serve the interface at the root of that name, or under a path the proxy
strips before passing the request on (`https://example.com/njmule/`, with its last slash): the
pages link to one another, to their style sheet and to their forms relative to themselves. Forms
are then taken from that origin on any port, over HTTP or HTTPS, and the session cookie is
`Secure` when the browser comes over HTTPS. Traefik and Caddy pass the browser's host name on;
nginx needs `proxy_set_header Host $host;`, or it sends the upstream's address. All logins then
come from the proxy's address, so the lockout after repeated wrong passwords holds for every user
at once, unless the proxy is trusted to name its client: `--webui-proxy 172.17.0.1` (an address, a
CIDR block or a range, repeated or separated by commas) or Trusted proxies in the web interface
settings. The `X-Forwarded-For` header of a request from a trusted proxy is then read from the
right, past the trusted proxies, and the first other address is the client; from anywhere else the
header is never believed. Trust only the proxy in front of njMule.

> [!CAUTION]
> The interface is served over plain HTTP: enable LAN access only on a trusted network, or reach
> it through a reverse proxy that serves HTTPS.

### Remote control

With `--webui-api` (or the box in the web interface settings) the same server also answers a JSON
API under `/api/v1`, with the engine's events as server-sent events at `/api/v1/events`. Callers
send the token kept in the `api-token` file of the configuration directory as
`Authorization: Bearer <token>`; `njmule --api-token` prints it and `njmule --new-api-token`
replaces it. A running njMule is driven from the command line with `--remote`:

```bash
njmule --remote status
njmule --remote search kad ubuntu iso
njmule --remote get <search id> <hash>
njmule --remote update check
njmule --remote update install
njmule --remote help
```

`--remote-url http://<host>:4711/api/v1` reaches another computer, whose token is taken from the
`NJMULE_API_TOKEN` environment variable.

The desktop app shows and drives another njMule the same way, such as one on a NAS or a Windows
service: *⋯ → Connect to a remote njMule* asks for the address of its web interface and the token,
remembers them if asked (in a file of the profile that only its owner reads) and opens the window
of that njMule beside the own one. `njmule-gui --remote-url <address>` opens it directly, with the
token remembered or in `NJMULE_API_TOKEN`. What only the own computer can do (adding a server.met
or nodes.dat of this computer, previewing a download) ends with a message instead.

The API is described by an [OpenAPI 3.2](https://spec.openapis.org/oas/v3.2.0.html) document,
[`openapi.yaml`](njmule-core/src/main/resources/org/njmule/core/remote/openapi.yaml), which every
njMule also serves at `/api/v1/openapi.yaml`. It is the source of the API: requests are routed by its
paths and checked against its schemas before anything runs, and the tests hold every answer and
event to it. Clients can be generated from it with any OpenAPI 3.2 tool.

## Building from source

| Tool | Required for |
|---|---|
| JDK 25 (e.g. Eclipse Temurin) | all builds; the Gradle Wrapper provides Gradle, and Gradle the Node.js that builds the web interface |
| GraalVM Community `25.0.4.1.1`, with `GRAALVM_HOME` set | native executables |
| Visual Studio 2022 Build Tools, C++ workload | native executables on Windows |
| Docker | Linux x64 native executables without a local GraalVM |
| [WiX](https://wixtoolset.org/) 5.0.2, [nfpm](https://nfpm.goreleaser.com/) | the Windows MSI, the deb and rpm packages |

On Windows, the Build Tools and WiX can be installed with:

```powershell
winget install --id Microsoft.VisualStudio.2022.BuildTools --override "--wait --passive --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
dotnet tool install --global wix --version 5.0.2
```

### Build and test

On Windows, use `.\gradlew.bat` instead of `./gradlew`.

```bash
./gradlew build                               # full build with all quality gates (see below)
./gradlew spotlessApply                       # format the sources (palantir-java-format)
./gradlew :njmule-gui:generateLicenseReport   # THIRD-PARTY.txt (build/reports/licenses)
./gradlew cyclonedxBom                        # CycloneDX SBOM (build/reports/cyclonedx)
./gradlew dependencyCheckAggregate            # OWASP dependency check (requires NVD_API_KEY)
```

`build` checks formatting (Spotless) and license headers, runs Checkstyle, Error Prone, the
tests, JaCoCo coverage (at least 70% of the lines of `njmule-core`) and SpotBugs with FindSecBugs.
Builds are incremental and use Gradle's build cache: tasks whose inputs did not change are not run
again, so a build with nothing changed takes about a second.

Every dependency version is locked (`gradle.lockfile` in each project) and every downloaded
artifact is checked against its SHA-256 in `gradle/verification-metadata.xml`. After changing a
version, rewrite both and review the new checksums (`--refresh-dependencies` makes Gradle record
the parent POMs it already holds in its cache too):

```bash
./gradlew --no-configuration-cache --refresh-dependencies --write-verification-metadata sha256 \
    --write-locks spotlessCheck :njmule-app:collectReachabilityMetadata \
    :njmule-gui:generateLicenseReport cyclonedxBom
```

<details>
<summary><strong>Running from the build</strong></summary>

Command-line interface and headless engine, from the module path:

```bash
./gradlew :njmule-app:run --args="--help"
```

`./gradlew :njmule-app:installDist` also installs it with a launcher script in
`njmule-app/build/install/njmule/`. Desktop application:

```bash
./gradlew :njmule-gui:run
```

</details>

<details>
<summary><strong>Native executables</strong></summary>

With a local GraalVM, for the host system:

```bash
./gradlew :njmule-app:nativeCompile :njmule-gui:nativeGui
```

The command-line executable is `njmule-app/build/native/nativeCompile/njmule(.exe)`; the desktop
application is in `njmule-gui/build/native/`, next to the JDK shared libraries it loads.
`:njmule-app:nativeCompile` alone builds the command-line executable only. On Windows the desktop
application is `njmule.exe`, a console program that is the command line too, and
`./gradlew :njmule-gui:nativeLauncher` builds `njmule-gui/build/launcher/njmule-gui.exe`, which
starts it for the desktop from the same directory (with the Visual C++ tools that `native-image`
needs).

Without a local GraalVM, Docker builds the Linux x64 command-line executable in the same image as
CI, into `njmule-app/build/native-linux/`. The script compiles a copy of the sources inside the
container, because on Docker Desktop (Windows, macOS) builds are several times slower on a
bind-mounted checkout; Gradle's downloads stay in the Docker volume `njmule-gradle`. In PowerShell
use `"${PWD}:/work"`; in Git Bash prefix the command with `MSYS_NO_PATHCONV=1`:

```bash
docker run --rm -v "$PWD:/work" -v njmule-gradle:/root/.gradle --entrypoint bash ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 /work/tools/native-docker.sh
```

The native desktop application requires reachability metadata, recorded by an automated tour of
the whole interface under GraalVM's tracing agent (`./gradlew :njmule-gui:nativeTour`) and stored in
`njmule-gui/src/main/resources/META-INF/native-image`; record it again after changing the GUI. For
Linux, `tools/native-gui-docker.sh` records the metadata and builds both executables in an Ubuntu
container (usage in the script header).

</details>

<details>
<summary><strong>Packages</strong> and <strong>releases</strong></summary>

The [release workflow](.github/workflows/release.yml) builds every package on its target system
and is the reference for the exact steps. Locally, after the native build, the Windows MSI:

```powershell
New-Item -ItemType Directory -Force stage\njmule | Out-Null
Copy-Item njmule-gui\build\native\*, njmule-gui\build\launcher\njmule-gui.exe stage\njmule
wix build packaging\windows\njmule.wxs -wx -arch x64 -d Version=1.2.3 -d Gui=yes -d "BinDir=$PWD\stage\njmule" -d "DocDir=$PWD" -o njmule.msi
```

The Linux deb and rpm packages (`ARCH` is `amd64` or `arm64`):

```bash
mkdir -p stage/gui stage/doc dist
cp -r njmule-gui/build/native/. stage/gui/
cp README.md CHANGELOG.md NOTICE stage/doc/
VERSION=1.2.3 ARCH=amd64 nfpm package -f packaging/linux/nfpm.yaml -p deb -t dist/
VERSION=1.2.3 ARCH=amd64 nfpm package -f packaging/linux/nfpm.yaml -p rpm -t dist/
```

The macOS application bundle is assembled from `packaging/macos/Info.plist`, ad-hoc signed with
`codesign` and packed into a disk image with `hdiutil`.

Pushing an annotated tag `vX.Y.Z` that matches `version` in `gradle.properties` triggers the
release workflow: native builds on every target system, the GUI tour as a smoke test, packaging,
and finally a draft GitHub release with the packages, the SBOM, `SHA256SUMS` and the release notes
of that version from `CHANGELOG.md`. Publishing the draft is a manual step. A manual run of the
workflow only builds the packages, available as workflow artifacts.

</details>

<details>
<summary><strong>Benchmarks</strong></summary>

JMH benchmarks of the hashing and cipher hot paths (one operation processes 1 MiB, so ops/s
equals MiB/s):

```bash
./gradlew :njmule-bench:shadowJar
java -jar njmule-bench/build/libs/benchmarks.jar
```

</details>

## Project layout

| Path | Content |
|---|---|
| `njmule-core` | engine, protocols, persistence, remote-control API; no GUI dependency |
| `njmule-app` | headless launcher, command-line interface, logging |
| `njmule-gui` | JavaFX desktop application |
| `njmule-webui` | web interface served by Jetty, which also serves the remote-control API |
| `njmule-webapp` | the web interface's browser app (Vue 3, Element Plus, TypeScript), built into `njmule-webui` |
| `njmule-testkit` | test doubles shared by the modules |
| `njmule-bench` | JMH benchmarks of the hot paths (not distributed) |
| `packaging` | Windows installer (WiX), Linux packages (nfpm), macOS bundle metadata, Docker image, Synology package |
| `branding` | original artwork: master SVG and generated icons |
| `config` | license header, Checkstyle and SpotBugs configuration |
| `build-logic`, `gradle` | Gradle set-up shared by the modules, library versions, Gradle Wrapper |
| `tools` | helper programs and Docker build scripts |

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) (Conventional Commits,
Developer Certificate of Origin sign-off, code style) and the
[Code of Conduct](CODE_OF_CONDUCT.md). Security issues: see [SECURITY.md](SECURITY.md).

| Standards | |
|:--|:--|
| **Process** | [![Conventional Commits 1.0.0](https://img.shields.io/badge/Conventional%20Commits-1.0.0-F5A65B?logo=conventionalcommits&logoColor=white&labelColor=24427A&style=flat-square)](https://www.conventionalcommits.org/en/v1.0.0/) [![Semantic Versioning 2.0.0](https://img.shields.io/badge/SemVer-2.0.0-F5A65B?labelColor=24427A&style=flat-square)](https://semver.org/spec/v2.0.0.html) [![Contributor Covenant 2.1](https://img.shields.io/badge/Contributor%20Covenant-2.1-F5A65B?labelColor=24427A&style=flat-square)](CODE_OF_CONDUCT.md) |
| **Code** | [![Code style: palantir-java-format](https://img.shields.io/badge/code%20style-palantir--java--format-F5A65B?labelColor=24427A&style=flat-square)](https://github.com/palantir/palantir-java-format) [![GraalVM native image](https://img.shields.io/badge/GraalVM-native%20image-F5A65B?labelColor=24427A&style=flat-square)](https://www.graalvm.org/latest/reference-manual/native-image/) |
| **Licensing** | [![REUSE compliant](https://img.shields.io/badge/REUSE-compliant-F5A65B?labelColor=24427A&style=flat-square)](https://reuse.software) |

## Support the project

If njMule is useful to you, you can support its development:

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-F5A65B?logo=buymeacoffee&logoColor=white&labelColor=24427A&style=for-the-badge)](https://buymeacoffee.com/scoppolap)

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

## Acknowledgements

Many thanks to the eMule team and to everyone who has contributed to
[eMule](https://www.emule-project.net) over more than twenty years: their client keeps the eD2K
and Kad networks alive, and its open source code made njMule possible.
