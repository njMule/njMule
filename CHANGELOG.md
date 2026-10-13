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

- Maven multi-module project (`njmule-core`, `njmule-testkit`, `njmule-app`, `njmule-gui`) with
  the Maven Wrapper, Java 25 and JPMS modules.
- Engine skeleton: single-threaded engine loop with timers, monotonic clock, crash-safe atomic
  file writes with backup fallback, OS-specific data directories (Windows Known Folders, macOS
  Library, XDG) with portable mode, typed preferences stored in `njmule.properties`.
- Headless launcher with `--version`, `--license`, `--warranty`, `--notice`, `--config-dir` and
  `--log-level`; programmatic SLF4J/Logback configuration with a rolling log file.
- Sober pastel colour palette and brand colours.
- Quality gates: google-java-format (Spotless), Checkstyle, Error Prone, SpotBugs with
  FindSecBugs, JaCoCo coverage.
- GPL-3.0-or-later licensing following REUSE: SPDX headers, `LICENSES/`, `NOTICE`, `AUTHORS`,
  contribution guide with DCO sign-off, code of conduct and security policy.
- GraalVM native executable of the headless launcher, built in CI and locally with Docker;
  GitHub Actions jobs for Linux and Windows builds, REUSE compliance, the native image and
  SonarQube Cloud analysis.
- Protocol codecs: bounded packet reader and writer, eD2K and Kad tag formats (every tag type,
  BSOB included), eD2K strings with a UTF-8 / Windows-1252 fallback, opcode tables, TCP and UDP
  framing, zlib packing with output caps and frame size limits before and after the handshake.
- Cryptography: MD4, RC4, TCP obfuscation towards clients and servers (DH-768 key exchange), UDP
  obfuscation for eD2K, Kad and server datagrams, RSA-384 secure identification with a bounded
  DER codec.
- File hashing: eD2K hash sets and AICH trees in one pass over the data; `njmule --hash <file>...`
  prints the ed2k link with the AICH hash.
- ed2k links (file, server, server list, nodes list, search) and magnet links.
- Search expression parser (boolean operators, attributes, size/number/length units) and the
  encoding of server search trees.
- Kad 128-bit identifiers and `nodes.dat` in every version.
- Readers and writers for the eMule data files: `server.met`, `known.met`, `.part.met`,
  `known2_64.met`, `cancelled.met`, `clients.met`, `emfriends.met`, `preferences.dat`,
  `preferencesKad.dat`, `nodes.dat`, the Kad index files, `StoredSearches.met`, `SearchSpam.met`,
  text lists, `ipfilter.dat` (also PeerGuardian and P2B) and INI files.
- Golden test vectors from an independent Python implementation, property-based tests and an
  optional test group that reads a real eMule profile.
- JMH benchmarks of the hashing and cipher hot paths (`njmule-bench`).
- IP filter (`ipfilter.dat`, PeerGuardian text and P2B) applied to incoming connections, with
  limits per address, in total and on the accept rate.
- Networking on virtual threads: framed TCP connections with per-state frame limits and bounded
  send queues, plain or obfuscated (Diffie-Hellman with servers, user-hash keys with clients), and
  a listening socket.
- eD2K servers: a server list kept in `server.met` (servers can be added from another client's
  list), login with HighID/LowID, server messages, status and identity, moving on through the
  known servers, challenge-checked UDP status pings and callbacks to firewalled clients.
- Searches on the connected server and, globally, on the other known servers over UDP, with
  results merged by hash.
- Hello exchange with other clients, answering incoming ones and greeting a given client.
- A user hash created at the first start and kept in `preferences.dat`.
- Command line: `--add-servers`, `--connect-server`, `--search` (with `--global`), `--hello` and
  `--headless`.
- Windows native executable in CI; documentation-only changes skip the build, and workflows are
  checked with actionlint.
- Downloads: part files in the eMule `.part.met` format, written in order on one thread per file,
  the rarest parts first; every part is checked against its hash and downloaded again when
  corrupt, and finished files move to the incoming directory under a sanitized, unique name.
- Download sessions with other clients: file request, part status and hash set, a place in the
  upload queue, then blocks plain or zlib-compressed (inflated with a cap at the requested size),
  32- and 64-bit offsets. Sources come from links and from the server; queued sources are asked
  again every 29 minutes and may call back with a slot.
- Engine API to add, list, pause, resume and cancel downloads, with progress events, and
  `njmule --download <ed2k link>...` on the command line.
- Test kit: a fake peer that shares a file, with queueing, short upload sessions and corrupt
  parts.
- Shared files: the incoming directory and the directories listed in `shareddir.dat`, hashed once
  and remembered in `known.met` and `known2_64.met`; finished downloads are shared at once.
- Uploads: a queue scored by waiting time, credits and file priority, slots by upload limit,
  bandwidth shared between slots, about one part per slot while others wait, compressed blocks,
  bans for clients that ask too often; the checked parts of downloads are shared too.
- Credits per user hash in `clients.met` and secure identification with our key in
  `cryptkey.dat`, on download, upload and hello connections; `--hello` reports the outcome.
- Source exchange with other clients (versions 1 to 4), asking and answering.
- Shared files offered to the connected server.
- `njmule --share <dir>...` on the command line, and engine API for the shared files.
- Kad 2: a node with its id in `preferencesKad.dat`, a routing table of zones (one contact per
  address, few per /24), contacts kept in `nodes.dat`, iterative lookups, keyword and source
  searches and publishes with an index of what others publish to us, flood control and answers
  taken only for our own requests, and a reachability check through other nodes.
- Downloads find sources on Kad, searches may run on Kad, and shared files are published there
  while we are reachable; `--kad-bootstrap <host:port>` and `--search ... --kad` on the command
  line.
- Import of an eMule profile, which is only read: preferences (nick, limits, networks,
  obfuscation; ports and directories stay njMule's), servers, Kad contacts, known files, shared
  directories, the IP filter and the lists kept for later features, each file checked first and
  reported. On request the identity (user hash with its key, credits, Kad id), applied at the next
  start and never while eMule runs, and copies of the downloads (paused ones while eMule runs),
  whose complete parts are checked again. `njmule --import-emule <dir>` with `--dry-run`,
  `--adopt-emule-identity` and `--import-downloads`.
- Engine API for front ends: the server list (with priority, limits and failures) and its
  removal, the clients uploading and waiting, one status with connections, rates and session
  totals, and the part map of each download.
- Desktop interface: a top bar with the connection, the eD2K and Kad state and the rates, a
  navigation rail (Ctrl+1 to Ctrl+7) and a status strip; screens for servers with the log,
  transfers with eMule-style part bars and the upload queue, searches in tabs, shared files, Kad,
  statistics with a rate graph and the options; the eMule import with a check before the import
  (offered at the first start when an eMule is found), messages for errors and finished downloads,
  and About. AtlantaFX themes (Nord by default), light, dark or as the system, with sober pastel
  colour tokens; English and Italian, with eMule's own interface texts converted from its string
  tables by `tools/Rc2Properties.java`. Tables keep their selection and order while they refresh.
- Proxy for outgoing TCP connections (SOCKS 4/5, with or without credentials, and HTTP CONNECT),
  also taken from an eMule profile.
- Scheduler: rules by weekday and time that change the transfer limits and connections; edited
  in the options and kept in `scheduler.properties`.
- Port forwarding with UPnP (off by default): the router is found on the local network, ports are
  mapped and renewed while njMule runs and removed on exit.
- Totals over all sessions (bytes, sessions, time) in `statistics.properties`.
- Messages with other clients and their CAPTCHA check, both ways, and friends kept in
  `emfriends.met`; a messages screen in the desktop interface.
- Web interface (`njmule-webui`): status, transfers, searches and servers as plain pages served by
  the JDK, behind a PBKDF2-hashed password, with HTTP-only same-site session cookies, form tokens,
  origin and host checks, a login lockout and a strict Content-Security-Policy; on this computer
  only unless other computers are allowed. Set with `njmule --webui-password` (`--webui-port`,
  `--webui-lan`) or in the desktop options, and started with the engine.

### Changed

- MD4, RSA keys and signatures, RC4, Diffie-Hellman, Base32, IP ranges, the engine scheduler and
  command-line parsing now come from the JDK, Bouncy Castle, Guava and picocli instead of our own
  code.

### Fixed

- The complete parts of a download resumed after a crash are checked again.
- Connections no longer hang on Windows when a socket write misses its wakeup.
- Compressed blocks are inflated to the end even when zlib holds output back.
- Connections back to a client and to a source are obfuscated only when the peer supports it.
