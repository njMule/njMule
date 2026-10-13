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
- Quality gates: palantir-java-format (Spotless), Checkstyle, Error Prone, SpotBugs with
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
- The desktop app as a GraalVM native executable (Windows, Linux, macOS), whose metadata is
  recorded by a tour of the whole interface under the tracing agent; the tour also serves as a
  smoke test of the native app.
- Installers: a per-user Windows MSI (no administrator rights, Start menu entry, launcher on the
  PATH), deb and rpm packages with the desktop entry, AppStream metadata and icons, a macOS disk
  image, and portable archives; the release workflow builds them all and opens a draft release
  for a tag.
- ed2k links given on the command line are added as downloads; a second start hands its links to
  the njMule already running with the same configuration, as when a link is clicked in a browser,
  and a second start without links brings the running window to the front.
- An option of the settings, off by default, makes njMule open the ed2k links of the system on
  Windows and Linux; turned off, it gives them back to what opened them before, such as eMule.
- Minimising the window hides it into an icon in the Windows notification area, next to the clock
  (a setting, on by default). A double click on the icon brings the window back, a click opens a
  small status window (connection, rates, finished downloads, free space), and its menu sets the
  speed limits with sliders, connects or disconnects, opens the options or exits.
- The download and upload capacity of the line (imported from eMule too) top the speed controls.
- Every input box takes only its kind of value (numbers within the engine's limits, times,
  addresses, host names, folders, eD2K links, search expressions): other characters are dropped as
  they are typed or pasted, and a box whose text is not valid is marked, says what it expects and
  keeps its action button off. The web interface checks its links and searches too.
- Download priorities (Low, Normal, High, or Auto, which favours rare files): higher ones ask for
  sources and start transfers first. Stop pauses a download and forgets its sources; finished
  downloads stay listed until cleared. Kept in the part.met as other clients write them.
- Upload priorities of shared files (Very low to Release, or Auto), which scale the queue scores of
  the clients waiting for them; kept in known.met as other clients write them.
- Downloads wait while the temp disk has less free space than a minimum (512 MiB unless set) and
  go on by themselves once there is room again; eMule's minimum is imported when its check is on.
- Folders can be shared with their subfolders, those made later too, and stop being shared; shared
  folders are watched, so new and changed files show within seconds. Sharing a disk's root, a
  system folder or the documents, desktop or pictures asks first.
- Download categories: a name, an incoming folder of their own, a tint, a priority for the
  downloads put in them and file name patterns that sort new downloads into them; the transfers
  screen shows one category at a time and moves downloads between them. eMule's categories are
  imported with their numbers, so its downloads stay in theirs.
- A server.met or a nodes.dat can be taken from an address, as in eMule: the box offers the
  addresses used before, and server list and nodes.dat links from the browser are followed. The
  lists are fetched over http or https from public addresses only, within size and redirect limits.
- The IP filter can be updated from an address in the settings (ipfilter.dat, PeerGuardian or P2B,
  zipped or not), with the addresses used before offered again; it applies at once.
- The Windows executable carries the njMule icon and version information, shown by Explorer and
  Task Manager; Linux desktops match the running window to its menu entry, icon and name.
- The orange of the logo marks the main action of each screen, the selected screen and the top bar.
- The Windows installer also puts a shortcut on the desktop.
- Searches take eMule's parameters (file type, minimum and maximum size, availability, complete
  sources, extension) and its Automatic method, which picks Kad or the server by what is connected;
  the words searched are offered again (eMule's history is imported). Each result tab has a filter
  box and its count, and results we download, share already show so.
- Search results can be marked as spam or cleared again, by file hash and full name, kept in
  eMule's `SearchSpam.met` (taken over by the import); the server's related search finds the files
  downloaded along with the selected ones, when the server supports it.
- The transfers screen shows eMule's client lists: clients we upload to, those waiting in our
  queue, the sources of our downloads (state, queue rank, parts, bytes received) and the banned
  addresses, whose ban can be lifted.
- The configuration is backed up as a dated zip at each exit and once a day, the latest three
  kept by default (`backup.copies`), in the data directory.
- Every list keeps how its columns were left (order, widths, which show and the sort), and offers
  a column menu to show or hide them.
- `njmule --exit` asks the njMule running on the same configuration (window or headless) to save
  and exit.
- The desktop app asks before exiting, as eMule does (with "Do not ask me again"), and can start
  minimised, into the notification area when it hides there.
- The server list can be updated at each start from the server.met addresses of `addresses.dat`
  (eMule's list, kept by the import and edited in the settings). The addresses typed in eMule's
  server.met and IP filter boxes are imported and offered again by njMule's.
- eMule's server options: dead servers leave the list after a number of failed attempts in a row
  (pings count until answered; static servers and the current one stay), the list can grow from
  the server we connect to and from the clients that greet us, automatic connection can keep to
  the static servers, and servers added by hand can take the high priority. eMule's settings for
  them are imported.
- Servers keep their ping time, soft and hard file limits, LowID users and software version (from
  the "server version" line); eMule's static servers (`staticservers.dat`) are loaded, and servers
  can be put in or taken out of the static list, given another priority, added from a link or
  removed in bulk.
- Port, network and UPnP changes in the settings apply at once: the new ports are listened on and
  announced again to the server, to Kad and through the router, without a restart.
- Kad connects and disconnects by hand, starting from the contacts it knows; contacts taken from a
  nodes.dat are looked up at once.
- The IP filter level can be set (`security.ipFilterLevel`, imported from eMule): ranges below it
  block, and a change applies at once.
- Parts that passed their check are remembered in the `.part.met`: after a restart they are not
  checked again while the data file is as it was saved (its date is the date of the `.part.met`).
- Among equally rare parts the download goes on with one already begun, so that parts complete
  and can be shared sooner, and its blocks end on the 180 KiB boundaries of AICH.
- AICH recovery data is sent to the clients that ask for it, and the AICH hash of a shared file
  to those that ask what it is; a finished download is hashed once more for its AICH tree.
- A part that fails its check is repaired with AICH recovery data from a source: only its bad
  blocks are downloaded again. The AICH hash comes from the link or, without one, from the
  agreement of the sources (at least ten networks and 92% of the announcements).
- Search results show the files downloaded or cancelled before (known files and `cancelled.met`,
  whose keys do not list the files); both lists can be turned off, and eMule's choices are imported.
- The search tabs left open at exit come back at the next start with their results
  (`StoredSearches.met`, so eMule's open searches come over with the import); it can be turned off.

### Changed

- UPnP port forwarding is on by default; tests never use it.
- The log names each port the router refuses to forward over UPnP, with the router's reason.
- The server list has eMule's columns (ping, soft and hard file limits, version, LowID users,
  obfuscation, static) and server menu: connect, priority, static list, copy and paste of ed2k
  server links, removal of the selected or of all servers. A box above the list filters it as one
  types; server links from the browser are added to the list.
- My Info beside the server list, as in eMule: the eD2K state with our address, ID and HighID or
  LowID, the server we are on (name, description, address, version, users, files, obfuscation),
  Kad's state, address and ID, and whether the web interface is on, with its address.
- The servers screen splits its log as eMule does: Server Info holds the connections and what the
  servers say, Log the application's events; dates follow the system's format, with a full year.
- Connect turns into Disconnect on the server we are on, and connecting to another server leaves
  the current one; the Kad screen connects and disconnects Kad too.
- The Italian interface keeps upload, download and the other terms that Italian software usually
  leaves in English, such as client, firewalled, Incoming and Temp.
- MD4, RSA keys and signatures, RC4, Diffie-Hellman, Base32, IP ranges, the engine scheduler and
  command-line parsing now come from the JDK, Bouncy Castle, Guava and picocli instead of our own
  code.

### Fixed

- The complete parts of a download resumed after a crash are checked again.
- Connections no longer hang on Windows when a socket write misses its wakeup.
- Compressed blocks are inflated to the end even when zlib holds output back.
- Connections back to a client and to a source are obfuscated only when the peer supports it.
- Pasted links whose file names contain spaces are kept whole.
- Private folders (home, configuration, temporary files and any folder holding a client key) and
  backup files are never shared.
- CAPTCHAs are taken only in answer to a message of ours and only with a picture of sane size.
- A client's credits, queue place and upload slot stay bound to its identity: another address
  claiming its user hash gets none of them, and a client that changes its user hash is banned.
- Ports read from Kad tags are taken only within 1..65535.
- The IP filter applies to servers, to Kad contacts and to every source, from links too.
- Kad keyword searches look up the longest word of the expression.
- UPnP finds the router on computers with virtual network adapters (WSL, Hyper-V, VPNs): the
  search goes out on every LAN rather than on the route of lowest metric.
- A second njMule cannot start on the same configuration.
- Part files stay sparse, and a finished download never replaces another file in the incoming
  folder, nor is left truncated there when it is copied to another volume.
- The server's check of whether we are reachable gets through, so we keep a HighID; a server
  cannot make us open any number of callback connections.
- The macOS desktop app runs the main run loop the system needs.
- Unusable search results (no name, no size, beyond the eD2K size limit, a known file with another
  size) are dropped, Kad source counts are not added up, and each server answer goes to the search
  it answers.
- The Windows installer contains the programs.
- At most 16 host names of a link are looked up, one after the other.
- Kad: answers that a NAT sends from another port are taken; a flooding sender loses its packets,
  not its place; a node that hears nothing reports itself disconnected; nodes.dat is not replaced
  by a handful of contacts; contacts are learnt only from answers to our own lookups; searches wait
  for late answers, count only usable results, outlive the server connection and can start again
  at once after being stopped.
- The engine never drops the completion of disk, hashing and network work, even with a full queue.
- A failed save never leaves a file missing; a download of which only the part.met backup is left
  is loaded again, and its number is never reused.
- Writes and part checks that end after a download was cancelled are ignored.
- Upload slots that their clients leave idle go back to the queue, a slot sends the file its
  client switched to, and banned clients are refused everywhere while the ban lasts.
- While Kad is firewalled our hello announces a LowID, as peers expect.
- Byte-swapped LowIDs in source exchange answers are read as LowIDs.
- njMule announces a compatible-client id that no other client uses.
- A source whose file status has the wrong number of parts is dropped.
- A client can download from us over the connection we download from it on, and a client
  connected to download from us is asked for our downloads over that same connection.
- Links whose separators a browser escaped (`%7C`) are read.
- The window cannot get so small that buttons are cut or hidden.
- The native desktop app no longer crashes on Windows at the first press of a mouse button, nor
  when a screen reader or another accessibility tool reads it.
- Closing the main window ends njMule even while one of its dialogs is open.
- The tray menu and the small status window follow a change of language at once.
- Dialogs and alerts show the njMule icon.
- A proxy keeps its type when the preferences are read again.
- Italian texts that carry a translator's note are no longer lost.
