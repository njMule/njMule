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
- Placeholder JavaFX window with the AtlantaFX Nord light/dark themes.
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

### Changed

- MD4, RSA keys and signatures, RC4, Diffie-Hellman, Base32, IP ranges, the engine scheduler and
  command-line parsing now come from the JDK, Bouncy Castle, Guava and picocli instead of our own
  code.
