<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A documentation website (`website/`, Docusaurus) for GitHub Pages: a user guide for the desktop
  app, the web interface and servers, a developer guide and the remote-control API's reference, in
  the brand's colours, with screenshots taken by the build; its text is under CC BY-SA 4.0.

### Fixed

- With `--webui-password-file` at each start, as in the Docker image, only the password is set
  again: LAN access, host names, trusted proxies and the API keep their saved values, such as the
  host names added in the browser, unless the command line gives them.
- `njmule --service=install` takes `--webui-lan` and the other web interface options without a
  password option, keeping the service's password, and restarts a service that runs so that it
  takes them; the MSI's `NJMULE_WEBUI_LAN=1` opens the service's web interface to the network.
- The update panel of the web interface offers *Download and install* where njMule installs itself,
  tells how to install the new version for each kind of installation (the package manager's
  command, the Docker image to pull, the Synology Package Center) and no longer offers a download
  that cannot work.

### Security

- The profile of the Windows service, `%ProgramData%\njMule`, is closed to the other users of the
  computer before its web interface password is written; a service installed before gets it at its
  next `njmule --service=install`.
- `njmule.properties`, which holds the proxy and mail passwords, and its backup are readable by
  their owner only on Linux and macOS.
- Our public IPv6 address is no longer told to other clients and to Kad while a proxy carries the
  connections.
- E-mail and web hook notifications go through the proxy of the preferences and from the network
  interface chosen, and are not sent while that interface is gone.

## [0.1.0] - 2026-10-26

The first release of njMule, a new client for the eD2K and Kad networks written in Java 25, which
works with eMule and the other clients of the network.

### Added

#### eD2K

- Servers kept in `server.met`, starting from the list shipped with njMule and updated from
  `server.met` addresses: static servers, priorities, ping times, file limits and versions, names
  asked for over UDP, dead servers dropped after failed attempts. Login with a HighID or a LowID,
  plain or obfuscated, two servers tried at once, a lost connection replaced at once, and connection
  at start.
- Searches on the connected server, on every known server over UDP (encrypted where the server
  takes it) and on Kad, or picked automatically, with eMule's parameters (type, size, availability,
  complete sources, extension). Results are merged by hash with every name their sources give, their
  media details and the AICH root Kad tells, show what we did with them (downloading, shared,
  downloaded, cancelled) and lead to related files where the server supports it.
- A spam filter for search results that learns from the files marked as spam (their names, similar
  names, size and the servers that gave them), kept in eMule's `SearchSpam.met`; the search tabs
  left open come back at the next start (`StoredSearches.met`) and the words searched are offered
  again.
- Clients: a hello naming njMule and its version, secure identification (RSA-384) and credits in
  `clients.met`, source exchange (versions 1 to 4), re-asks over UDP, A4AF sources moved between
  downloads by themselves or by hand, the shared files of a client browsed (ours shown to everybody,
  friends or nobody), file comments and ratings, and chat with CAPTCHA, a friends list, the friend
  slot and spam filters.

#### Kad 2

- A Kad 2 node: routing table, iterative lookups, keyword, source and note searches and publishes,
  and an index of what others publish to us, kept across restarts; publishing spares loaded nodes
  and takes turns between keywords.
- TCP and UDP firewall checks. Firewalled, njMule takes a buddy and is called back through it, or
  directly over UDP; open, it serves a firewalled node as its buddy.
- Joining from the contacts shipped with njMule, a `nodes.dat` from an address or a file, a known
  node or the Kad clients met over eD2K; the status bar estimates the users and files of the
  network.
- Lookups trust no single node: a node must prove its id at its address, and a contact only one node
  tells of is asked last.

#### Downloads

- Part files in eMule's `.part.met` format: the rarest parts first, parts already begun preferred,
  every part checked against its hash, bad blocks repaired from AICH recovery data or as soon as the
  bad block comes again, sources that send corrupt data banned, and an endgame that asks the last
  blocks of more than one source.
- Sources from links, from the connected server and the others over UDP, from Kad, source exchange
  and by hand, at IPv4 or IPv6 addresses; the best ones are remembered across restarts, and each
  shows what it does as a coloured dot.
- Priorities (Low, Normal, High, Auto), categories with an incoming folder, a tint and name patterns
  of their own, pause, stop and resume, a paused download started when another ends, new downloads
  paused if asked, downloads that wait while the temp disk is short of space, preview with the first
  and last parts first, and file names taken from the sources, tidied or typed.
- Cancelling asks first and can be undone; completed downloads stay listed until cleared; downloads
  that receive nothing for a number of days can be deleted by themselves (off by default).
- ed2k links pasted, dropped on the window, given on the command line, opened in a browser or
  copied in another program (off by default) become downloads, shown first when several come at
  once or one is known already.

#### Uploads and sharing

- Shared folders, with their subfolders if asked, watched for new and changed files and hashed once
  into `known.met` and `known2_64.met`. Personal and system folders and the files other programs
  keep beside their work are never shared, and sharing a drive or a personal folder asks first.
- An upload queue scored by waiting time, credits and file priority (Very low to Release, or Auto),
  slots by the upload limit, compression only where it helps, bans for clients that ask too often
  or abuse their slots, and statistics for each shared file.

#### Connections

- IPv6 alongside IPv4: our IPv6 address in the hello, the Kad publishes and source exchange,
  sources reached directly over IPv6, and the router's IPv6 firewall opened to our ports.
- Firewalled clients connect to each other through their routers, over QUIC (TLS 1.3) or uTP.
- Port forwarding with UPnP, NAT-PMP or PCP, and a port test through the server and Kad nodes; new
  ports apply without a restart.
- SOCKS 4/5 and HTTP CONNECT proxies, with UDP through a SOCKS 5 relay, and binding to one network
  interface, such as a VPN, which keeps njMule offline while it is gone.
- An IP filter from `ipfilter.dat`, PeerGuardian or P2B files, with levels, a whitelist and up to 16
  lists updated from the web; LAN addresses are left out unless allowed.
- A weekly scheduler for the speed limits, the connections and the categories.

#### Coming from eMule

- Import of an eMule profile, which is only read: preferences, servers, Kad contacts, known files,
  shared folders, categories, the IP filter, friends, comments, spam marks and open searches, each
  file checked first and a manifest kept of what became of it; on request the identity (user hash,
  credits, Kad id) and copies of the unfinished downloads. Offered at the first start when an eMule
  is found, and from the desktop and web apps and `njmule --import-emule` (with `--dry-run`).
- eMule's files read and written as eMule does: `server.met`, `known.met`, `.part.met`,
  `clients.met`, `emfriends.met`, `nodes.dat`, `StoredSearches.met`, `SearchSpam.met` and the
  others.

#### Desktop app

- A JavaFX app with AtlantaFX themes (Nord light or dark by default, following the system), the
  system's colours under Windows high contrast, text and marks held to WCAG AA contrast in every
  theme, and its controls named for screen readers; its own line icons and Manrope titles.
- 36 languages with eMule's translations, mirrored for Arabic, Persian and Hebrew, with the sizes,
  rates and durations in each language's units.
- A sidebar of places and a top bar with the rates, the state of eD2K and Kad and one field that
  searches the network or takes eD2K links, beside a command palette (Ctrl+K): transfers with chips,
  sources under each download and a details pane; searches in tabs with filters; shared files by
  folder; conversations and friends; the network with its servers, Kad, log and our info;
  statistics; options on twelve pages with a search.
- A first-start wizard, an icon in the Windows notification area with a small status window and
  speed sliders, the ed2k link handler on Windows, Linux and macOS, start at login and keeping the
  computer awake while files are transferred (both off by default), and a window onto another
  njMule, on a NAS or a server, through its API.

#### Web interface

- The desktop app in the browser (Vue 3, Element Plus), in its 36 languages and with a layout for
  phones: transfers, searches, shared files, messages, network, statistics, settings and the import.
- Behind a password (PBKDF2) with session cookies, form tokens, origin and host checks, a login
  lockout and a strict Content-Security-Policy; on this computer only unless asked, answering to the
  host names listed and through reverse proxies, also under a path, and to a guest with a password
  of its own who can only look.

#### Remote control and command line

- A JSON API under `/api/v1` with the engine's events as server-sent events, behind a token,
  described by an OpenAPI 3.2 document that also routes and checks every request.
- `njmule` runs the engine headless or does one task (search, download, share, hash, import, exit
  the running njMule, look for updates), and `njmule --remote` drives a running njMule, here or on
  another computer.

#### Statistics and notifications

- Rates over the last ten minutes, hour or day, session and all-time totals with the protocol's
  overhead, a tree of every figure, the history of the clients met (90 days) and, optionally, the
  country and flag of each client (IP Geolocation by DB-IP).
- A completed or failed download, a chat message or a temp disk short of space, told by e-mail
  (always encrypted), a web hook (ntfy, Gotify), a program of the user's or a sound (off by
  default).

#### Packages and updates

- Native executables that need no Java: for Windows an MSI, per user or for every user with the
  Windows service, and a zip (arm64 through emulation); for Linux x64 and arm64 deb and rpm packages
  with a systemd user unit and archives, and a static headless executable for any x64 Linux; for
  macOS a disk image for Apple silicon; a Docker image for amd64 and arm64; a Synology DSM 7
  package. A `portable.flag` file keeps the data beside the programs.
- Uninstalling can delete the profile too; the completed downloads always stay.
- njMule tells when a new version is out, and downloads and installs it where it may replace
  itself, trusting only update information signed with a key built into it; each release carries a
  signed `SHA256SUMS`, build attestations and a CycloneDX bill of materials.
- The configuration is backed up as a dated zip at each exit and once a day.

#### Security and robustness

- Size-limited parsers for everything read from the network and from disk, bounded send queues, and
  crash-safe saves that never leave a file missing or half written.
- Abuse held back: limits on the connections of an address and on new connections, bans for
  clients that ask too often, hold idle slots, send corrupt data or claim another client's identity,
  and credits, slots and the friend slot bound to the proven identity.
- Names from the network lose their control characters and direction overrides, file names are made
  safe for every file system, and the files holding keys and settings are readable by their owner
  only (Linux and macOS).

#### For contributors

- A Gradle build with locked and verified dependencies, Spotless, Checkstyle, Error Prone, SpotBugs
  and JaCoCo; golden vectors from an independent implementation, property-based tests, a test kit of
  fake servers and peers, JMH benchmarks, and REUSE-compliant licensing.
