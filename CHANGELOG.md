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
- Port forwarding with UPnP (on by default): the router is found on the local network, ports are
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
  image, and archives that run from any folder, portable once a `portable.flag` file is put beside
  the programs; the release workflow builds them all and opens a draft release for a tag.
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
- eD2K file links copied in other programs can become downloads (off by default); the links njMule
  copies itself are left alone.
- New downloads can start paused, and when a download completes or is cancelled a paused one can
  start: the one of highest priority, of the same category first or only. eMule's choices are
  imported.
- Servers, sources and Kad contacts at LAN addresses are left out unless the new
  `security.filterLan` setting is turned off (imported from eMule's "Filter LAN IPs").
- A shared file can be renamed, keeping its hashes and priority, and its folder opened with the
  file selected.
- Chat messages can be accepted from friends only, those with a filtered word are left out, and an
  anti-spam filter leaves out a link in a first message and more than three unanswered ones; the
  eMule settings are imported.
- Kad can check the firewall again on request (Recheck Firewall).
- The search, transfer and shared lists copy eD2K links as HTML anchors too.
- The statistics screen has a statistics tree beside the graph: ratios, session and cumulative
  transfers, average and top rates, run times, clients by software, servers, shared files and the
  free space of the temp and incoming disks.
- The connection settings take the new connections allowed in five seconds and the size of the
  upload queue (imported from eMule), and the maximum of connections now limits the incoming ones.
- A scheduler rule can stop the downloads of one category and resume those of another when it
  begins.
- The Kad screen lists the contacts of the routing table (id, type, distance, address, version),
  closest first, and the lookups running now.
- View Shared Files asks a client of the transfer lists for the files it shares, shown in a window
  to download from; our own list is shown to everybody, friends or nobody (the default; eMule's
  SeeShare setting is imported).
- A friend can be given the friend slot for the run: it goes before the queue, also when it is
  full, and keeps its upload slot.
- Preview: a download can fetch its first and last parts first (or every playable download, as
  set in the settings), and Preview opens what is downloaded so far in the video player of the
  settings; eMule's PreviewPrio and video player are imported.
- File comments and ratings: a shared file or a download can be commented and rated (kept in
  `fileinfo.ini`, imported from eMule); clients downloading it hear the comment after the file name, and
  the comments of a download's sources show beside ours, those holding a filtered word left out.
  The hello now tells that we take comments, and that our files may be viewed when they may.
- Add Sources lets a download take a source typed as host:port, at a LAN address too; the sources of
  a link are not held to the LAN filter either, the IP filter still applying to both.
- Remote control: a JSON API under `/api/v1` of the web interface (status, downloads, sources,
  uploads, shared files, servers, Kad, searches, categories, friends, messages, bans, speed limits,
  IP filter updates, log, shutdown) and the engine's events as server-sent events, for holders of
  the token in `api-token` (off by default: `--webui-api` or the web interface settings);
  `njmule --remote <command>` drives a running njMule through it, here or on another computer
  (`--remote-url`, `NJMULE_API_TOKEN`), and `--api-token` / `--new-api-token` print or replace the
  token.
- Web interface: Kad (state, connect, recheck firewall, bootstrap), shared files (with their
  upload priority) and log pages, and a layout for phones.
- The desktop interface in German, Spanish, French, Dutch, Polish, Brazilian Portuguese, Russian
  and Simplified Chinese, with eMule's own translations of its texts and njMule's texts in the same
  terms; the language box names each language in its own words, and the interface follows the
  system's language when it is one of them.
- The desktop interface also in European Portuguese, Swedish, Turkish, Traditional Chinese,
  Japanese and Korean; a system language in the traditional Chinese script (zh-Hant, or Taiwan,
  Hong Kong and Macao) gets Traditional Chinese, and Portuguese outside Brazil gets European
  Portuguese.
- The desktop interface in Czech, Danish, Greek, Finnish, Hungarian, Norwegian Bokmål (also for a
  system set to Norwegian or Nynorsk) and Ukrainian.
- The desktop interface in Catalan, Estonian, Lithuanian, Romanian and Bulgarian.
- The desktop interface in Galician, Latvian, Slovenian, Albanian and Vietnamese; the many texts
  that eMule's Latvian translation leaves in English are translated.
- The desktop interface in Arabic, Persian (with Latin digits) and Hebrew (also for a system that
  names it with the old code `iw`).
- A right-to-left layout for the languages written so: the window, its dialogs, menus and the
  windows of the notification area are mirrored, while paths, links, hashes, addresses, numbers in
  lists, logs and the fields for links and addresses stay left to right, file and user names read
  in the direction of their first letter, and the part bars and the rate graph keep their
  direction.
- Search results keep the media details that servers and Kad give (artist, album, title, length,
  bitrate, codec), by tag id or by the names servers use, and so do the search tabs kept at exit.
- The search results list shows them in Artist, Album, Title, Length, Bitrate and Codec columns,
  sorted by value, and offers the file hash in a File ID column of the column menu.
- An IP filter whitelist (`security.ipFilterWhitelist`): addresses, CIDR blocks and ipfilter.dat
  ranges, one per line, that the IP filter never blocks; a change applies at once.
- `njmule-gui --restore-links` gives the ed2k links back to what opened them before the desktop app
  took them, when it still has them.
- Uninstalling the Windows MSI runs it, so the ed2k links go back to eMule or whatever opened them
  before (an upgrade keeps them); the release workflow checks it on the arm64 runner.
- On macOS the app declares the ed2k scheme and follows the links the browser opens, which the
  system sends as events, also those that start it.
- A4AF (asked for another file): a client that is a source of several downloads is asked for one
  at a time, on one re-ask clock whatever the number of downloads, and the others list it as asked
  for another file. It is asked for another of them on the same connection when it has no part we
  need or lacks the file (then it is dropped for that one), moves to another when the download
  asked for is paused, stopped, cancelled or finished, and at its next ask goes to a better one
  (higher priority, parts we need) unless it sends us data or keeps us within rank 50 of its queue.
  By hand the A4AF sources of a download can be swapped to it, its sources to other downloads, or
  one source; a download can also take its A4AF sources by itself, kept in its `.part.met` as an
  njMule tag (eMule stores no such flag). Category-based A4AF options are not there.
- The transfers screen has a Source Handling menu on the downloads (swap every A4AF source to this
  file now, swap them to it by itself when possible, swap every source of this file to other files)
  and Swap to this file on an A4AF source; the downloading clients show each state with an icon of
  its own, and an A4AF source the file it is asked for.
- Event commands, run by the desktop and the headless app alike: a program of the user's on a
  completed or failed download, a chat message and a temp disk short of space, with `%NAME`,
  `%FILE`, `%HASH`, `%SIZE`, `%SENDER` and `%MESSAGE` filled in inside its arguments
  (set in the settings, or as `events.*` in `njmule.properties`). No shell runs it, and at most
  four run at once.
- The desktop app can play a WAV file of the user's with the notifications of completed downloads
  and new chat messages, chosen and tried in the settings; eMule's notifier sound is imported.
- Start njMule when I log in (off by default): the installed desktop app adds itself to the
  user's Run key on Windows, an XDG autostart entry on Linux or a launch agent on macOS, and reads
  the setting back from there; an eMule that started at login has njMule do the same once it is
  imported.
- The deb and rpm packages install a systemd user unit, `njmule.service`, that runs the headless
  engine as the user and restarts it on failure (`systemctl --user enable --now njmule`).
- Kad notes: our comment and rating of a shared file go to the Kad nodes closest to it, once a day,
  at once when they change and no more once taken away. As a Kad node njMule keeps the notes of
  others (150 per file and 50,000 in all, one per publisher and per address, each for a day) and
  answers searches for them. The comment dialog of a download or a shared file shows the notes found
  on Kad beside the comments of the sources, with the Kad icon, and Search Kad looks for more; each
  running download is searched once a run. Notes holding a filtered word, in the comment or in the
  file name, are left out.
- Kad UDP firewall test: nodes we never wrote to are asked over TCP to send a datagram to our UDP
  port (to the port a NAT shows too, learnt from other nodes); one that arrives means UDP is open,
  two helpers whose datagram never came mean it is firewalled. It runs at start, every hour and on
  Recheck Firewall; the Kad screen, the status bar, My Info and `--kad` lines show the outcome. We
  help other clients with their own test, once in a while per address.
- Kad buddies: firewalled on both ports, njMule asks the nodes near the complement of its Kad id
  for a buddy, keeps one link to it with pings and names it in its hello; open on both ports, it
  serves one firewalled node and relays to it the callback requests of other clients.
- Callbacks on Kad: a firewalled Kad source is asked to connect to us through its buddy, or by a
  direct UDP callback when its UDP port is open, and we are called back the same ways. Our source
  publishes say how to reach us while firewalled (through our buddy or by a direct callback), and
  nothing is published while nobody could.
- The server list and nodes.dat boxes are filled in with the well-known lists of
  upd.emule-security.org while no list was used; nothing is fetched until the user asks.
- Sizes and rates use the units of the interface language as eMule's translations write them
  (Ko and Ko/s in French, Кб in Russian).
- The server in use stands out in the server list (bold, with an accent bar and a tooltip), and the one
  being tried is in italics, whether njMule chose it or the user did.
- A capture tour of the desktop interface for design reviews (`CaptureTour`, run on demand with
  `-Dtest=CaptureTour -Dnjmule.capture=<folder>`): every screen, tab, menu, sub-menu, context menu,
  drop-down list, column menu, dialog and message kind, with example data, in light, dark, English
  and right-to-left sets.

### Changed

- The rail starts with what one does every day (transfers, search, shared files, messages), then the
  network and the statistics, with the options at the bottom. Servers and Kad are one place, the
  network, with a switch between them. The places count the running downloads and the unread
  messages, and the network shows whether it is up; Ctrl+1 to Ctrl+7 follow the new order.
- Connect takes the accent only while there is no connection; the eD2K and Kad chips open the
  network, the last log line opens the log, the Tools menu opens the incoming folder, the options and
  a list of the keyboard shortcuts (also F1), and the rates open the speed limits.
- A banner above the screens tells of a lasting state with its remedy: no network (Connect) or a
  network others cannot reach us on (the options).
- The transfers tell downloading, waiting and no sources apart, with an icon for every state; a box
  filters the downloads by name (Ctrl+F); the sources below are those of the download chosen; a
  Details pane (Alt+Enter) shows the parts drawn large with a legend and the facts the columns cut.
- The lists use dense rows; empty lists say how to fill them and offer the buttons that do it.
- The search parameters fold behind a button that counts those in use.
- The options have an index of their sections and a box that finds an option by its words.
- The rate graph has a scale; chat tabs with unread messages are marked.
- eD2K links dropped on the window become downloads; the shared folders window can share a folder.
- Ctrl+K opens a command palette: typing finds a place or a command of the menus, searches the
  network for the words typed, or downloads the eD2K links pasted.
- Messages come in three kinds: news, successes (a finished download) and problems, which carry a
  colour and an icon of their own and stay until closed. They stack in the bottom corner above the
  status strip, at most four at a time, instead of covering the tool rows.
- Risky questions name their outcome on the buttons ("Cancel and delete" / "Keep downloading",
  "Share anyway", "Remove all servers") and the safe answer is the default.
- Apply in the options stays enabled: with a wrong value it scrolls to the field, puts the cursor in
  it and tells which option it is and what it takes.
- Every entry of the transfer and shared file menus has an icon, as in the other menus; the log
  rows are one line tall, with an icon for warnings, errors and successes.
- Durations use the units of the interface language ("2 g 1 h", "59:22 min" in Italian).
- The rates in the top bar use the interface's font (they were in Courier New on Windows) at a
  steady width.
- A main button that cannot run yet is greyed out instead of a faded orange; the parts of a
  download that no source has show in a soft rose instead of a solid red.
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
- Kad searches made only of OR alternatives are refused with an explanation: Kad looks up one word
  that every result must contain.
- `njmule --exit` waits, up to 30 s, until the running njMule has saved and stopped, and together
  with links or other actions it is a usage error.
- The web interface's session cookie carries the port, so two njMule on one computer keep their own
  web sessions.
- Settings unknown to this version (from a newer one, or added by hand) are kept when the
  preferences are saved.
- Shared files cannot be renamed to names that are never shared (backup, temporary or hidden ones).
- Links copied to the clipboard are added after a confirmation.
- UPnP mappings are leased for an hour and renewed every half hour.
- Kad hellos say UDP-firewalled only when the UDP test found it so, rather than whenever the TCP
  port is firewalled; while UDP is firewalled, publishes of other nodes are not taken.

### Fixed

- The labels of the comment, category and import dialogs are no longer cut short.
- The friends list is titled "Friends" (it used the text meaning "visible to friends"), and a
  CAPTCHA request in a chat is no longer cut short.
- The native desktop app plays its notification sounds: the sound system no longer stops for want of
  a JDK around it.
- Results that a server sends at once are no longer lost before their search shows.
- A download that finishes while a scan reads its file gets its AICH tree, also when it is renamed
  meanwhile.
- `njmule --remote servers` says so when the server list is empty.
- Without a configuration directory, the restore of the ed2k links works in a temporary directory
  that only its owner may enter.
- The search parameters wrap onto a second line in a narrow window instead of cutting their labels
  short (French and other longer languages).
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
- Kad search results and publish answers count only from the nodes the lookup sent its request to.
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
- A firewalled client whose turn for an upload slot came while it was not connected gets a slot
  when it asks again, instead of waiting behind every client we can reach.
- A client that reads its upload slowly no longer takes the bandwidth of the other slots nor fills
  its connection until it is dropped, and our other packets to a client (answers, chat, the
  requests of our download from it) no longer wait behind the file data queued for it.
- An address that opens connections in a row can no longer use up the rate of new connections and
  lock everyone else out, and our own connections to clients count in the maximum number of
  connections: sources and waiting clients are called once there is room.
- The secure identification works with a LowID and without a server too: signatures are bound to
  and checked with the address the server reports or the one Kad nodes see, so peers that bind
  them to our address get their credits.
- Source exchange: sources are told only for a file the client asked for on its connection and
  never to a banned address; the wait between two answers goes by address and user hash, so
  another address claiming a user hash cannot take its answers, and old answers are forgotten.
- A client counts as asking too often only when it asks again for the same file within ten
  minutes, with a few seconds of tolerance: asks for its other files and the end of its upload
  slot no longer count against it, while asking again from a slot does.
- What a client did with our upload queue is remembered for a few hours by address and user hash:
  leaving the queue and coming back no longer clears its quick re-asks, and a client that takes
  eight slots within four hours without asking for any data is banned; a slot that sends data
  forgives the idle ones.
- A connection that asks for six files we neither share nor download is closed, and an address
  closed this way twice within two hours is banned.
- A full upload queue no longer stalls the engine: with 5,000 clients waiting, a queue step, the
  place told to a client and the list the front ends show each took a fifth of a second or more,
  now about a millisecond at most; file data is compressed off the engine thread.
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
- The desktop app honours the portable mode too: with a `portable.flag` file beside the programs
  it keeps its data in the `data` folder next to them, as the command line already did.
- Uploads no longer stop for good when an upload limit is set while it was unlimited, and the
  download speed limit is applied.
- The web interface works from a browser again: logging in and every form were refused because
  the pages made browsers send `Origin: null`. It answers 503 with the reason when the engine fails,
  HEAD with headers only, and reports actions on a download that is gone; the link field takes
  magnet links and full-length input.
- Web interface security: the login lockout can no longer be bypassed with parallel attempts and
  password checks are limited to two at a time; in LAN mode only IP addresses and loopback names are
  accepted as host (no DNS rebinding); `webui.properties` is readable by its owner only on Linux and
  macOS, and a broken one is logged. Desktop searches no longer push out the web's results.
- njMule starts again after eMule's identity was imported twice before a restart; an identity is
  never applied in part or mixed with an earlier import, and a dry run changes nothing. A folder
  left in the imported identity no longer keeps the other leftovers there.
- Friends, spam markers, cancelled files, comments, static servers and stored searches imported from
  eMule take effect at the next start instead of being overwritten by the running engine.
- eMule import: a free-space reserve of 2 GiB or more, every "start next paused file" value,
  Category.ini with a section spelt twice or fields too long for njMule, categories pointing at
  eMule's incoming folder, downloads in a category that was not taken, relative folders, files over
  256 MiB, a corrupt known2_64.met, a folder written two ways, and a configuration inside eMule's
  directory are all handled.
- The configuration copy just written is never deleted (copies are named in UTC); a settings file
  briefly held by another program is read once free instead of being replaced by its backup, and
  the user hash is never regenerated because of it; the secure identification key, the user hash
  and the backups are readable by their owner only on Linux and macOS.
- A byte order mark or a malformed `\u` escape in njmule.properties, categories.properties or
  scheduler.properties no longer loses a setting or prevents the start; INI sections with a trailing
  comment, part.met gap tags numbered "01", four-digit IP filter levels and a damaged
  cancelled.met, fileinfo.ini, StoredSearches.met or SearchSpam.met (set aside as `.corrupt-<time>`)
  are read correctly.
- Downloads: a link with mismatching part hashes can be downloaded; a part.met whose part hashes do
  not give the file hash no longer fails every part; data that could not be written is asked for
  again; a finished file that could not be moved is moved again on resume instead of staying at
  100%; cancelling while the finished file is moved is refused, so adding the link again no longer
  loses the new download; a completed download stops its disk thread; two part files of one file
  load as one download.
- A download no longer stalls with a free slot when a source leaves, ends its session or is
  dropped by an IP filter change, or when a corrupt part waits for its AICH recovery data; the
  rest of a compressed block whose stream ends early is asked for at once; resuming a running
  download no longer asks its sources again too early.
- Sources at 0.x.x.x, multicast or broadcast addresses are refused; LAN and loopback sources are
  passed on in source exchange only within their network; adding the link of a file being
  downloaded again takes its sources only when size and AICH hash match.
- "Start the next download" keeps the order downloads were added across restarts, starts only when
  a running download leaves, and leaves alone downloads a scheduler rule stopped; a new download is
  announced with its category and priority; preview starts once the first 16 KiB have arrived,
  and says so before.
- AICH: padded hashes in links are refused instead of breaking recovery; corrupt blocks of files
  smaller than one part are recovered and served; recovery state is reset for a file downloaded
  again; the AICH tree is no longer rebuilt for every request, and the AICH hash of a download in
  progress is told to those who ask.
- Shared files: a file that cannot be read no longer stops the scan of its folder; removing one of
  two copies keeps the content shared; a file renamed while being hashed stays shared; case-only
  renames work; NTFS junctions no longer lead a shared tree outside itself or round in a loop;
  renaming or completing a file during its upload keeps the client's slot.
- Files that other programs keep beside their work are never shared: Office owner files (`~$…`,
  which carry the user's name), macOS `._` files, lock files, NFS and sync-tool leftovers, and
  downloads a browser or another client is still writing (`.crdownload`, `.partial`, `.download`,
  `.!qB`, `.!ut`, …).
- A shared file that another program holds or locks for a moment, as while it is still being
  written, is read again for a few seconds instead of being left out until the next scan.
- A shared file written to while it is hashed is hashed again once it rests, instead of being
  shared under hashes of a mix of old and new content recorded with its old date.
- A shared file whose date moved by up to 2 seconds or by an hour, as after a copy to a FAT drive or
  a change of daylight saving time, is no longer hashed again; any other change of date still is.
- known.met and known2_64.met no longer grow without bound: a file touched again keeps one record
  (with its priority), AICH trees no record needs are dropped, and so are those of files not shared
  for a month (such a file is hashed again for its tree when it is shared again); known2_64.met is
  written only when its trees change. A damaged known2_64.met is read from its backup, and when a
  file and its backup are both damaged the records before the damage are kept. A save before the
  first scan of the shared folders no longer forgets the files not shared yet.
- The AICH trees of the shared files take about a third of the memory they took.
- The part.met files of the downloads are written on each download's own disk thread, after the
  data queued before, so a slow disk or a part.met held by another program no longer holds up the
  engine; an older save never follows a newer one, and a cancelled download's files are deleted
  only once its last save is done, so they cannot come back.
- A write that fails because the temp disk filled up no longer stops the download for good: the
  downloads wait for room and go on by themselves, also with no minimum free space set. The data a
  part.met tells as written is flushed to the disk before the part.met is saved.
- A finished download that a virus scanner or another program holds is moved to the incoming folder
  again after 2, 4, … 64 seconds instead of stopping with an error at once.
- A finished download is shared and recorded in known.met without the AICH hash its link or its
  sources gave until hashing it once more proves its own; a shared file whose record has no AICH
  tree is hashed again for one.
- Intelligent corruption handling: a part that fails its check keeps its data while it is
  downloaded again and is hashed after each block written into it, so it is whole again as soon as
  the bad block came again, without AICH; data for a range already whole is no longer written over
  it.
- A source that sends corrupt data is found and banned for two hours, so it can no longer make a
  part be downloaded again and again: njMule notes who sent each range, and a source is banned when
  more than 32% of its data that AICH recovery or part checks proved was corrupt (a source that
  alone sent failed parts only after three of them).
- eMule and aMule clients can browse our shared files (one "!Other" folder, never real paths; the
  list is built once per connection); browsing a client that sends a file comment first works.
- The friend slot, friends-only shared files and the friends-only chat filter need the friend's
  proven identity, not just its user hash.
- Secure identification accepts the address-bound signatures it used to refuse and binds ours to our
  address when we know it; a malformed peer RSA key fails cleanly.
- Packets queued just before a connection closes are sent; a peer that stops reading is dropped once
  16 MiB wait; a block request with a backwards range is skipped instead of disconnecting; files
  just under 4 GiB are not listed to clients that cannot take them.
- Servers: those known by host name are kept apart, resolved when connecting, filtered and never
  dialled at 0.0.0.0; static servers that are not taken stay in staticservers.dat; an out-of-range
  (obfuscated) port no longer blocks connecting, leaves the session "connecting" or logs errors;
  control characters in server names and messages are removed, so a server cannot add lines to
  staticservers.dat; IPv6 server addresses are written so that they can be read back.
- A server can no longer make njMule connect to LAN addresses (while they are filtered) or to banned
  clients; connecting by hand to a filtered server is refused; IPv6 datagrams no longer log errors;
  changing the IP filter, its level or the LAN filter drops the server, sources, clients and Kad
  contacts it blocks; removing several servers works when one name does not resolve.
- Searches: two overlapping global searches no longer mix their results, "more results" stays on
  its own tab, a truncated server answer keeps the results before the cut, results that cannot be
  downloaded are left out of saved searches and of a client's file list, sources counts are never
  negative, `@length` with a huge value is a syntax error, and marking a short common name such as
  `setup.exe` as spam no longer marks every file with that name.
- Kad: lookups end as soon as every node left was asked and stayed silent (publishing no longer
  crawls); searches use a word Kad indexes ("ubuntu 22.04.3", "linux -distribution") and an old
  search no longer ends a newer one; the index counts republished files and sources once and each
  publisher once per name, and answers nothing to a term of separators only; a contact can move
  within its own full /24; firewall checks get through the connection limit and a re-check keeps the
  last result meanwhile; Kad connected by hand stays connected when preferences change and starts
  after a port change if it could not bind.
- UPnP: mappings are renewed (and recreated after a router lost them), routers that keep only
  permanent mappings get one, nothing is left mapped after exit, a stalling or oversized router
  answer or an unreadable device no longer blocks discovery, and the WAN connection in use is
  chosen. On exit every file is saved before the mappings are removed.
- Fetching lists from the web has a deadline, takes the right file from a zip, refuses https-to-http
  redirects and cut-short IP filters, and uses the HTTP proxy of the preferences.
- Log lines written while njMule starts show in the desktop app and the web interface; the all-time
  statistics no longer wrap around; scheduler rules act once when they begin and forget a deleted
  category.
- Links and files from the network: reserved device names hidden by the name length cut are escaped
  (also COM0, LPT0, CONIN$, CONOUT$ and superscript digits), trailing dots and spaces are removed,
  long names get distinct numbered copies, `s=` web sources are only http/https, regular-expression
  categories such as `.*\.(avi|mkv)$` work, and sharing a folder that holds personal folders warns.
- Command line and links: an unusable server-list URL no longer breaks the start or the handoff;
  server links are added headless too; a link with a line break cannot make njMule exit; a damaged
  handoff file or a slow local program no longer blocks later starts; on Linux giving the ed2k links
  back keeps other handlers and a linked `mimeapps.list`; on Windows the handler is recognised in
  paths with non-ASCII letters.
- Desktop app: the category bar keeps following changes; opening the tray menu no longer changes
  the speed limits; the clipboard is read only when watching is on; a preview player no longer
  stalls on its own output; a connection that cannot start no longer stays "connecting"; the import
  dialog recovers from an error; the window no longer opens off screen; "Apply" writes only the
  settings changed; sizes, percentages and chat times follow the locale; chats from strangers are
  capped; text direction controls are kept out of the nick (refused by the engine too).
- Strings with a byte order mark followed by invalid UTF-8 are read as Windows-1252, long chat
  messages are no longer cut inside an emoji, and on Linux the last `XDG_DOWNLOAD_DIR` is used.
- A hello to a client that answers and closes the connection at once is reported straight away
  instead of after about 80 seconds.
- A command-line task that is interrupted still waits for the last saves and frees the
  configuration for the next start.
- A finished download renamed before its AICH tree was built is hashed under its new name, so it
  is shared with recovery data.
- Pressing Enter on a search the parser refuses tells what the box accepts instead of failing.
- A firewalled njMule that the server asks to call a client back now serves that client's requests
  over the connection, instead of leaving them unanswered; firewalled sources are no longer passed on
  by source exchange as if they could be connected to.
- With obfuscation required, sources that cannot take it are no longer connected to plain nor asked
  to call back; with obfuscation disabled, sources that require it are no longer tried.
- A block request in 32 bits whose end wrapped to 0 is read as ending at 4 GiB, so clients asking
  so for the block that ends there are served it.
- A source that gave us a slot but sends no data of the blocks we asked for is left after 100
  seconds with a cancel, even while it keeps sending other packets.
- A source that sends data of blocks we did not ask for, three times within ten seconds of each
  other, is told to stop with a cancel and its slot is left.
- A compressed block that does not inflate no longer costs the source: the block is asked for again
  and the rest of its stream dropped; only a third such block in a session drops the source.
- A source asked for another download, or again before its time, waits at least 11 minutes since
  the last ask instead of exactly the 10 that sources allow, so it no longer takes us for a client
  that asks too often.
- njMule never takes itself as a source: sources the server, Kad or other clients tell of with our
  user hash, or at our port on the address Kad found us reachable at, are left out, and a source
  that answers our hello with our user hash is dropped from every download; a client that greets
  us with our user hash gets our answer and nothing more.
- Kad index: a keyword publisher silent for a day no longer counts for its name nor keeps newer
  publishers out, names are kept up to 255 characters, and a file whose entry no answer could
  carry is not stored.
- Kad: the contacts of a bootstrap-only nodes.dat are filtered, only the 50 closest to our id are
  kept, and they are asked one at a time (every 2 seconds while no contact is known, every 15
  otherwise) only while Kad is not connected, obfuscated for the nodes that take it; the nodes that
  answer are no longer taken as contacts.
- Kad: plain datagrams from the DNS port are dropped; lookups no longer ask contacts without a UDP
  port, at the address of another of our contacts or under the id of a verified contact elsewhere;
  a result claiming more than 65,500 sources counts as unknown.
- Kad nodes older than version 8 can now be verified: one with keys answers a ping, an older one a
  lookup for a random target, and only verified contacts are used to answer and search.
- Kad: a keyword whose storing nodes report a load above 20% is not published again for up to a
  week, in proportion to the load, sparing the busiest nodes.
- Kad publishing takes turns: a keyword, source or note publish that cannot start now no longer
  holds back the others every two seconds, and a keyword with more than 150 files publishes the
  next ones each time instead of always the same.
- Kad searches and publishes send nothing new in their last 20 seconds, and one that has all the
  answers it wants still takes those arriving in the next 15 seconds instead of dropping them.
- Kad keyword search results must match the whole expression (other words, NOT terms, size, type
  and other attributes), not only the word looked up, whatever the answering node filtered.
- Kad remembers the id each node proved at its address: a node proving another id there within an
  hour is ignored for four hours, an id merely claimed there is not taken, and lookup and search
  answers under another id than the proven one are dropped.
- Kad lookups no longer trust a single node's word: a contact two nodes place at the same address
  (or one of our own) gets the search or publish first, one only a single node knows of only once
  nothing better is left, and one two nodes place at different addresses never.
- Kad searches wait for silent nodes only as long as nodes have been taking to answer (at most 3
  seconds, as before) before asking the next ones; publishes keep the full wait.
- Kad: the contacts closest to a target are found by walking the routing table from the target's
  side, sorting only the bins needed, instead of sorting every contact at each lookup and answer.
- Kad index searches split and lower-case the words searched for once per search and keep each
  file's most popular name until its names change, instead of working both out for every file.
- The Kad index (keywords, sources and notes other nodes published to us, and our keywords whose
  nodes are loaded) is saved when Kad stops and loaded at its start, written crash-safe and read
  back only as far as it is still alive, near our id and within the limits of live publishes; it
  is left out of the configuration backups.
- Web interface: login attempts from IPv6 are counted per /64 network, so changing the host part
  of the address no longer escapes the limit, and wrong passwords and API tokens are logged.
- IP filter lists whose ranges overlap with other levels keep each level: a blocked range inside an
  allowed one stays blocked, and a blocked range no longer grows over an allowed one.
- Source requests to the server follow the credit servers give: up to 15 at once, then one every 20
  seconds, instead of one a second without end, which got them ignored or the client dropped.
- A server search answer that cannot be read at all still counts as the answer to its search, so the
  answers that follow keep going to the searches that asked for them.
- The type of a search result comes from its file name, as for our own files; the type the publisher
  reported counts only for names that do not tell it, and only when it is a real file type.
- Searches for archives or disc images (`@type=arc`, `@type=iso`) ask servers and Kad for programs,
  the type both index them under, and keep only the archives or disc images among the answers; they
  found nothing before.
- Search result names lose their control characters and text direction overrides, and server names
  and descriptions from server lists their control characters, so the command line, the desktop app
  and the web interface never show terminal escapes or a disguised file extension.
- A compressed packet from the server that does not inflate is dropped on its own instead of closing
  the server connection.
- A server whose last ping time lies in the future (a clock set back, a server list from another
  computer) is pinged again instead of never.
- A new id the server gives while we are logged in is the one our hello, the downloads and Kad use
  from then on, and the log tells it.
- The UDP socket for servers never takes the client UDP port Kad listens on: it picks another at
  start, and moves when Kad is turned on or set to the port it holds, instead of leaving Kad unable
  to start.
- No more shared files are offered to a server than its soft limit, when it told one.
- With obfuscation requested, servers never pinged are tried obfuscated once on their main port and
  remembered as taking obfuscation when they do; when no server takes an obfuscated connection,
  every server is tried again plainly (unless obfuscation is required), instead of never obfuscating
  with them or never falling back.
- Looking for any server, two are tried at once, so a dead or silent server no longer holds up the
  connection for 25 seconds; each failed attempt shows in the log, and every server being tried may
  check our port for a HighID.
- The "Reconnect" preference works: a lost server connection is replaced at once (after 30 seconds
  when it was lost again soon after a reconnect), and when no server accepts us every server is
  tried again after 30 seconds, until Cancel or Disconnect; a list with nothing to try still ends at
  once. One-shot command-line runs and embedded engines connect only as asked.
- The "Connect at start" preference works: the desktop app and the headless service connect to a
  known server when they start.
