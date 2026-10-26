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

- Gradle multi-module build (`njmule-core`, `njmule-testkit`, `njmule-app`, `njmule-gui`) with the
  Gradle Wrapper, Java 25 and JPMS modules; incremental, with the build and configuration caches;
  locked dependency versions and SHA-256 verification of every downloaded artifact.
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
  results merged by hash; a global search asks no further server once it holds more than 100
  results, and a search keeps up to 2000, room for every "more results" a server gives.
- Hello exchange with other clients, answering incoming ones and greeting a given client. Our
  hello names njMule and its full version in the mod tag, which other clients show after the
  software they read; the mod a client names shows in brackets after its software, without
  control or direction characters.
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
  again every 29 minutes and may call back with a slot. Endgame: once a part or less is missing and
  every missing block is asked of a source already, a source with nothing left to send is asked for
  those blocks too (twice at most each), and the first copy to arrive is kept, so a slow or silent
  source no longer holds the end of a download back.
- Engine API to add, list, pause, resume and cancel downloads, with progress events, and
  `njmule --download <ed2k link>...` on the command line.
- Test kit: a fake peer that shares a file, with queueing, short upload sessions and corrupt
  parts.
- Shared files: the incoming directory and the directories listed in `shareddir.dat`, hashed once
  and remembered in `known.met` and `known2_64.met`; finished downloads are shared at once.
- Uploads: a queue scored by waiting time, credits and file priority, slots by upload limit,
  bandwidth shared between slots, about one part per slot while others wait, compressed blocks,
  bans for clients that ask too often; the checked parts of downloads are shared too. A block
  is compressed only when its first 16 KiB shrink, so that data compressed already, whatever the
  name of its file, is not compressed whole only to be sent as it is.
- Credits per user hash in `clients.met` and secure identification with our key in
  `cryptkey.dat`, on download, upload and hello connections; `--hello` reports the outcome.
- Source exchange with other clients (versions 1 to 4), asking and answering.
- Shared files offered to the connected server.
- `njmule --share <dir>...` on the command line, and engine API for the shared files.
- Kad 2: a node with its id in `preferencesKad.dat`, a routing table of zones (one contact per
  address, few per /24), the contacts heard from kept in `nodes.dat` (enough of them replace it),
  iterative lookups, keyword and source searches and publishes with an index of what others
  publish to us, flood control and answers taken only for our own requests, and a reachability
  check through other nodes.
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
  navigation rail (Ctrl+1 to Ctrl+7) and a status strip; screens for servers with the log, transfers
  with eMule-style part bars (after the speed, the percentage written over them) and the upload
  queue, searches in tabs, shared files, Kad, statistics with a rate graph and the options; the
  eMule import with a check before the import (offered at the first start when an eMule is found),
  messages for errors and finished downloads, and About. AtlantaFX themes (Nord by default), light,
  dark or as the system, with sober pastel colour tokens, buttons with rounded corners in every
  theme, outlined tabs and dialogs with buttons sized to their words; English and Italian, with
  eMule's own interface texts converted from its string tables by `tools/Rc2Properties.java`.
  Tables keep their selection and order while they refresh.
- Proxy for outgoing TCP connections (SOCKS 4/5, with or without credentials, and HTTP CONNECT)
  and for the server, Kad and IP filter lists fetched from the web (SOCKS 5 or HTTP), also taken
  from an eMule profile. With a SOCKS5 proxy the UDP of Kad, of the clients and of the servers
  (pings, global searches) goes through its UDP relay (UDP ASSOCIATE), asked for again when it
  ends; meanwhile, and for datagrams that come around the relay, nothing goes or is answered. A
  proxy that relays no UDP (HTTP, SOCKS4, a refusal) leaves UDP straight, which the log says.
- Scheduler: rules by weekday and time that change the transfer limits and connections; edited
  in the options and kept in `scheduler.properties`.
- Port forwarding with UPnP (on by default): the router is found on the local network, ports are
  mapped and renewed while njMule runs and removed on exit. A router that answers no UPnP is asked
  with NAT-PMP or PCP at the default gateway (read from the system's routing table), renewing at
  half the lease it grants; a mapping counts only with our own port outside.
- IPv6 alongside IPv4: clients connect to us over IPv6, counted with the rest of their /64 by the
  connection limits, bans and corruption checks. Our public IPv6 address (the one the system sends
  from, none while njMule keeps to an interface of its own) goes in the hello, in our Kad source
  publishes and in the extended source exchange with the clients that take it; theirs, from the
  same places, lets us reach a firewalled source directly, with no callback, or one IPv4 failed.
  Secure identification binds to IPv6 addresses too. Sources at IPv6 addresses can be added by
  hand or come in links as `[2001:db8::1]:4662`; the status shows our IPv6 address.
- IPv6 follow-ups: the router's IPv6 firewall is opened to our ports (UPnP IGDv2 pinholes, else
  PCP at the IPv6 default gateway) and closed on exit; sources are re-asked over UDP at their IPv6
  address; connected clients hear when our IPv6 address changes, and theirs is followed.
- Connections through routers between firewalled clients, on the client UDP port in the frames
  newer clients use: firewalled ourselves, we meet a firewalled Kad source by a rendezvous through
  its buddy; both sides punch holes in their routers and the connection opens over QUIC (TLS 1.3
  with Kwik, each side first proving its user hash) when the source has it, else over uTP (LEDBAT
  congestion control, selective acknowledgements). A host QUIC failed with is met over uTP for two
  minutes. Our hello and Kad publishes say that we take such connections.
- Web interface as a browser app (`njmule-webapp`: Vue, Element Plus, TypeScript, Vite) in place of
  the pages the server wrote, with the desktop app's Nord colours, icons and 36 languages: transfers
  (downloads, uploads, queue, banned, links added, priorities, categories, sources), search (tabs,
  filters, results), shared files (sharing any folder by its path, asking again before a personal
  or system one), messages (chats, friends, friend slot, CAPTCHA), network (servers, Kad, log),
  statistics (rates of the last day, free space), settings (the desktop app's pages, scheduler,
  updates, host names), the import from eMule, the set-up, notices of finished downloads and new
  messages with a chime, and the state and limits in the top bar. It calls the remote-control API with the login session, each change carrying the
  session's token in a header; only the login page is still written by the server. Gradle fetches
  Node.js and checks it, and npm installs exactly the packages of `package-lock.json`, running none
  of their scripts.
- Remote-control API: searches on the network picked automatically, folders shared and unshared,
  shared files renamed, the chats of the run, friends added and removed and the friend slot, every
  preference read and changed by the keys of `njmule.properties`, the scheduler, categories, server
  lists and their priority, the port test, the parts of a download, comments and Kad notes, spam
  marks, a client's files, the rates of the last day, the import from eMule and the free space.
- Web interface laid out as the desktop app: the places in a sidebar, a top bar with one field to
  search or paste eD2K links, the rates and the state of the networks, Nord light or dark after
  the system. Transfers in segments (downloads with state filters and the desktop app's columns,
  the details of one with its parts, sources and their names; uploads, the queue, the banned
  clients to unban), a cancel that asks first and can be undone; searches with filters, sortable
  columns, other names, more results, stop and spam marks; the shared files' counters of the
  session or of all; the network's cards (eD2K, Kad, ports with IPv6, NAT traversal and a port
  test) over its servers, Kad (contacts, current searches, bootstrap, nodes.dat) and log by level;
  statistics.
- The ports card of the desktop app's network tells our IPv6 address and whether firewalled
  clients are met through the routers.
- Port test, in the first-start wizard and in the connection options: the ports as chosen are
  applied, the router asked again through UPnP, then the server checks the TCP port at a new login
  (a HighID) and Kad nodes check both ports; the outcomes show as they come, with what to do about
  a closed port. The engine API's `testPorts` and `portTest` run it and tell how far it went.
- Totals over all sessions (bytes, sessions, time) in `statistics.properties`.
- Messages with other clients and their CAPTCHA check, both ways, and friends kept in
  `emfriends.met`; a messages screen in the desktop interface.
- Web interface (`njmule-webui`): status, transfers, searches (Automatic picks the network as the
  desktop app does; the latest ones stay as tabs, a page loads again by itself until its search
  ends, with the desktop app's columns) and servers as plain pages served by the JDK, with the
  njMule icon in the browser, behind a PBKDF2-hashed password, with HTTP-only same-site session cookies, form tokens,
  origin and host checks, a login lockout and a strict Content-Security-Policy; on this computer
  only unless other computers are allowed. Set with `njmule --webui-password` (`--webui-port`,
  `--webui-lan`) or in the desktop options, and started with the engine.
- The desktop app as a GraalVM native executable (Windows, Linux, macOS), whose metadata is
  recorded by a tour of the whole interface under the tracing agent; the tour also serves as a
  smoke test of the native app.
- Installers: a per-user Windows MSI (no administrator rights, Start menu entry, launcher on the
  PATH), deb and rpm packages with the desktop entry, AppStream metadata and icons, a macOS disk
  image, and archives that run from any folder, portable once a `portable.flag` file is put beside
  the programs; the release workflow builds them all and opens a draft release for a tag. The
  desktop app holds the date and number formats of the regions of its interface languages only, not
  every locale of the JDK. Each desktop package carries one native executable: on Linux and macOS
  the command line is a script that runs it headless; on Windows it is the console program
  njmule.exe, which njmule-gui.exe, a small GUI program, starts for the desktop, its windows grouped
  and pinned on the taskbar with the shortcuts. The packages are compressed with xz (LZX in the MSI,
  LZMA in the disk image).
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
- A nodes.dat can also be added from a file, as a server.met can: the Kad view of the desktop
  app has the button, and the engine API `addKadContacts(Path)`.
- The IP filter can be updated from up to 16 lists on the web (ipfilter.dat, PeerGuardian or P2B,
  zipped or not), one address per line in the settings, joined into one filter that applies at
  once; a list that cannot be fetched or read is left out and named in the log. The lists are
  fetched again once the filter is as many days old as the settings say (7 by default, 0 for by
  hand only), and six hours after a failed try (preferences `security.ipFilterLists` and
  `security.ipFilterUpdateDays`; engine API `updateIpFilter(List<URI>)`).
- The Windows executables, of the desktop app and of the command line, carry the njMule icon and
  version information, shown by Explorer and Task Manager; Linux desktops match the running window
  to its menu entry, icon and name, and process lists show the desktop app as njMule however it
  was started (its desktop entry runs it as `njMule`).
- The orange of the logo marks the main action of each screen, the selected screen and the top bar.
- The Windows installer also puts a shortcut on the desktop.
- Searches take eMule's parameters (file type, minimum and maximum size, availability, complete
  sources, extension) and its Automatic method, which picks Kad or the server by what is connected
  (with both, the server only when it is a static one, or a big one in a short server list); the
  words searched are offered again (eMule's history is imported). Each result tab has its
  count, and results we download, share already show so. One filter box right of the result tabs
  filters the tab shown on a column chosen from its menu (the file name at first, its title shown in
  the empty box): the text the column shows holds every word, whatever the case, and none of the
  words after a minus.
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
- The sources that served a download best (downloading, then queued by their place, then those
  that answered, with the most parts first), up to 100 reached directly, are kept in its
  `.part.met` every 10 minutes and on shutdown, and taken again at the next start until a week
  old; stopping a download forgets them, as it forgets its other sources.
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
  settings, typed or chosen with Browse; eMule's PreviewPrio and video player are imported.
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
- Remote desktop app: the desktop app shows and drives another njMule through its API, in a window
  of its own (*Connect to a remote njMule*, or `--remote-url` without `--remote`), with the
  connections and their tokens remembered in an owner-only file; the API gives what it needs for
  that (`/downloads?include=parts`, shared-file statistics, A4AF sources, Kad user estimates).
- OpenAPI 3.2 description of the remote-control API, served at `/api/v1/openapi.yaml`: the API is
  routed by its paths and operations, and checks every parameter and request body against its
  schemas (JSON Schema 2020-12) before acting; the event stream is described item by item.
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
- The web interface's Kad page shows the contacts by distance, as bars from the closest to the
  farthest, as the desktop app does.
- Chat smileys typed as text (`:)`, `;-)`, `:D`, `<3` and others) show as emoji in the desktop and
  web chats; the messages sent stay as typed.
- An option keeps the computer from sleeping while files are transferred, and lets it sleep again two
  minutes after the last one, through the Windows launcher, `caffeinate` on macOS or
  `systemd-inhibit` on Linux, which end with njMule anyway (off by default; desktop and web
  settings, `system.preventSleep`).
- A history of the clients met, kept 90 days in `clienthistory.json` (at most 20,000): the name and
  program each gave last, when it was first and last met and, when the countries are shown, where
  from, never its address, with the bytes exchanged of all times from the credits; the Known
  Clients list of the transfers in the desktop and web apps, and the API (`GET /clients/known`).
- The completed downloads stay in the list of the transfers, restarts included, until *Clear
  completed* takes them off, in the desktop and web apps and in the API
  (`DELETE /downloads/completed`); they are kept in `finished.json` of the profile, and cancelling
  leaves them out, as their files stay where they are.
- Delete takes the chosen completed downloads off the list, their files kept, and asks before
  cancelling the others as their menu does, in the desktop and web apps; the API takes one off with
  `DELETE /downloads/completed/{hash}`.
- The details of a download in the web interface are those of the desktop app: under the list and
  in sight as it scrolls, shown or hidden with *Details* or Alt+Enter and closed with their cross,
  as tall as last dragged and never lower than their buttons; the file's icon, name, size and
  priority, the share done, the bar of the parts, the buttons the state asks for, the link to copy
  and a menu with comments, priority, category, a source to add and cancelling; tabs for the sources
  (dot, country, program, place in the queue, the files they share, a move of an A4AF one), the
  facts with the hash to copy, the names the sources give with take over, clean up and rename, and
  the comments. The list's *More* menu takes preview first and the A4AF commands, as the desktop
  app's.
- The API keeps the search tabs for the next start (`GET`/`PUT /searches/saved`), tells the spam
  marks (`GET /searches/spam`), says hello to a client (`POST /clients/{address}/hello`), moves one
  A4AF source to a download (`POST /downloads/{hash}/sources/swap`), tidies a file name
  (`POST /names/cleanup`) and tells the kind of each download's file: the desktop app connected to
  another njMule does all of it there.
- The sources of a download show what they do as a dot, under the file and in its details, in the
  desktop and web apps: green downloading, yellow queued, red being reached, dark grey of no use
  for now (queue full, no needed parts, asked for another file), a question mark not asked yet. The
  API tells the states `queue_full` and `no_needed_parts`.
- The downloads of the desktop app open onto their sources with a double click, Enter or the right
  arrow (the left one closes them): a line each under the file, in the list's columns, with the
  name or address, what it sent, its parts, its speed, its program and place in the queue, and what
  it does. A double click or Enter on a completed download opens its file in the program the system
  gives its kind, and its menu opens its folder in the system's file manager, the file selected; on
  Windows each download shows that program's icon. The time left also tells how
  much is missing, in the units of the speeds.
- A *Buy me a coffee* button in the sidebar of the desktop and web apps, above the options and the
  logout, opens the page where whoever likes njMule can support it, in the system's browser.
- The columns of the search results and of the transfers in the web interface can be resized by
  dragging their edges in the head, and keep their widths in the browser; the rows chosen there stay
  chosen and tinted as the lists are read again, a click chooses a row alone (Ctrl or Cmd adds it),
  and the whole text of a cut cell shows after a moment under the pointer. As in the desktop app, a
  download opens onto its sources with its arrow or a double click, and the search results are
  coloured by what they are to us.
- The country of other clients, with its flag beside its code and its name as tooltip, in the
  sources of a download and among the uploads and the queue, in the desktop and web apps and in
  the API (off by default; `geo.countries`): njMule then downloads DB-IP's free IP to Country Lite
  database once a month into its cache (IP Geolocation by DB-IP, CC BY 4.0). The flags are
  flag-icons' (MIT), rendered to small PNG by `tools/FlagGen.java`.
- Notifications by e-mail and through a web hook (both off by default; desktop and web settings,
  `notify.*`): a completed or failed download, a chat message or a temp disk short of space, as
  chosen, are told by an e-mail through the user's SMTP server, never in the clear (STARTTLS required
  or TLS from the start, the server's name checked), and by an HTTPS request, as text with a Title
  header for ntfy or as JSON for Gotify; at most ten messages in ten minutes, and a button sends a
  test. The SMTP password stays out of the API, as the proxy's does. Jakarta Mail (Angus Mail)
  sends the e-mails.
- A guest of the web interface, with a password of its own set in the desktop or web settings (none
  by default): it sees the transfers, the searches made through the web interface, the servers, Kad
  and the statistics, and the server refuses it any change, the settings, the log, the chats and the
  shared files; its page leaves out what it cannot do. The API lists the searches it keeps
  (`GET /searches`), so that a page reloaded or opened elsewhere shows them again.
- Downloads that receive nothing for a number of days can be deleted by themselves (off by default;
  `download.autoDeleteDays`): only the time a download runs while njMule is connected counts, kept
  across restarts in its part.met, paused and stopped downloads are never deleted, and each link is
  first written to `deleted-downloads.txt` in the profile, to add it again at will.
- The statistics show the session's overhead sent and received, the protocol's bytes besides the
  file data over TCP and UDP, in the desktop and web apps and in the API's status.
- Uninstalling can delete the profile too: a box at the Windows uninstall (to the Recycle Bin), in
  the Synology uninstall wizard and in *Uninstall njMule* of the macOS disk image; elsewhere
  `njmule --remove-profile` deletes it after asking, and the deb and rpm packages tell where it
  stays. The completed downloads always stay.
- Windows service: `njmule --service=install|uninstall|start|stop|status` runs the headless engine
  with its web interface as the service "njMule" (`njmule-service.exe`), as the virtual account
  `NT SERVICE\njMule` on a profile of its own in `%ProgramData%\njMule`, with a firewall rule,
  restarted after a failure and stopped with a save; a per-machine MSI installs it with
  `NJMULE_SERVICE=1` and a password file.
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
- A manifest for each import of an eMule profile, in the `import` folder of the configuration:
  every file read, with its size and SHA-256, and what became of it.
- A network interface to bind to (by name, or an IPv4 address, also taken from eMule's BindAddr):
  every connection and web list goes through it only, and while it is gone, as when a VPN drops,
  njMule stays offline with a banner saying why, and comes back by itself once it is there again.
- Sources from the other servers: while connected, njMule asks the other live servers over UDP
  for the sources of the downloads that need more (up to 35 files per server, one request a
  second, then a pause of half an hour), and takes only the answers of a server it asked, for the
  files it asked about.
- Server names, descriptions and versions over UDP: a server that answers the status ping is asked
  for them, with a challenge its answer must carry, so the list shows them without connecting.
- A server connection with nothing sent for 15 minutes gets an empty file offer, so that routers
  that drop idle connections keep it.
- Re-asks over UDP between clients: shortly before a source that keeps us in its queue is due to be
  asked again, it is asked over UDP, and an answer (our place, its parts, a full queue, or that it no
  longer has the file) spares the connection until the next time; without one it is asked over TCP
  as before. Clients in our queue are answered the same way, found by address and UDP port, and
  the hello announces the UDP version while Kad runs. A source firewalled on Kad is asked through
  its buddy, which passes the re-ask on over its TCP link (once in 30 seconds per client at most)
  and the source answers straight over UDP; firewalled ourselves, we answer the re-asks our buddy
  passes on.
- Pasted eD2K links are shown before they are downloaded when there are several or one is known
  already (downloading, shared, downloaded or cancelled before): each can be left out, and all go
  in the category chosen (or that of their names), paused if asked; the engine API adds a download
  in a category and paused at once.
- The lists below the downloads (uploading, waiting, downloading from, banned) are segments with
  their counts, one list shown at a time.
- Search results rated for spam: a file marked as spam teaches its hash, name, its name without the
  words searched for (names a few edits away count too), its size (within 5 % and 5 MiB), the UDP
  servers that gave it and the source the answer named, and each server's share of spam over UDP
  is kept; the hits add up, and 60 or more is spam. Results only servers of mostly spam give count
  more, small popular programs from them most. Kept in `SearchSpam.met` as eMule does (its file is
  read and written), on by default (`search.spamFilter`, also from an eMule profile); engine API
  `rateSpam`, the rating in the web API's results. The desktop interface shows a result as spam by
  its rating, rates the results of every search tab again once a file is marked (or the filter is
  switched, in the options next to the IP filter), and rates the searches kept from the last run.
- Search results keep the AICH root Kad tells for a file, when it is the one root and a third of
  the publishers at least gave it, and none for the rest of the search once two disagree; it goes
  into the result's ed2k link, so that a download started from it checks and repairs its parts
  with it, and into `StoredSearches.met`.
- The complete sources of a search result show as a share of its sources, "?" where the network
  does not tell (Kad, a client's shared files) and "Yes" for a file of one part, whole wherever it
  is shared; a count not told is no longer stored as 0 in `StoredSearches.met`, and the web API
  gives `null` for it.
- The status bar counts the users and files of every server of the list that answers, and, with
  Kad up, the network Kad estimates in brackets: from the routing table's leaves, made up for the
  firewalled nodes the greeting nodes report, each node sharing what an average server user does
  (engine API `KadInfo.users` and `files`).
- Search results sort, once a column is chosen, with spam last in either direction, names
  by the language's rules whatever their case, complete sources by their share of the sources,
  types then extensions, missing media values last in either direction, and the columns chosen
  before break ties, also for results that come later.
- A search result found under several names keeps each with the sources that gave it, in any
  case, and shows the one most sources give (engine API `SearchResult.names`, web API `names`;
  a record for each name in `StoredSearches.met`). The desktop interface opens such a file on its
  arrow, or with + and −, to show its names under it, which stay there whatever the order, by
  name or by most sources; a name chosen downloads the file under that name.
- The name each source gives a download is kept with the source (engine API `SourceInfo`), and a
  download can be renamed (engine API `renameDownload`, web API `PUT
  /api/v1/downloads/{hash}/name`): the name, made safe for any file system, is the one its file
  gets once complete.
- The download details list the names its sources give it, each with how many give it, the most
  given first: one is taken over into the new name (or with a double click), which can be tidied
  and given to the download.
- File names tidied (engine API `FileNames.cleanUp`): escapes, dots and underscores used as spaces,
  tags in square brackets and the words of a list in the options go, and each word starts with a
  capital. New downloads can get the tidy name as they are added (an option, off by default); the
  list and the option are taken from an eMule profile too.
- Windows high contrast: while it is on, the desktop interface takes the system's colours (window,
  text, selection, links, disabled text) for every surface, text, selection, part bar and graph of
  the theme, alerts included, and follows the system when the scheme changes or is turned off.
- The rates graph of the statistics shows the last ten minutes, hour or day (one point a minute,
  the mean of its seconds), and under the pointer the time and rates of that moment.
- Statistics of each shared file: how often it was asked for, how many asks got a slot and how many
  bytes went out, in this run and in all (kept in known.met under eMule's tags); the shared files
  show them in their columns, beside a pane of the shared folders where a folder is added, removed
  or switched to share its subfolders too.
- Undo for cancelled downloads: a cancel asks first in a modal dialog naming the files, whose
  buttons say what they do (Cancel download, in red, and Keep downloading, the default), and its
  message then offers to take the downloads back; their files are kept for 15
  seconds (engine API `restoreDownload`), set aside so that a start in between does not load them,
  then deleted.
- A change of our public address is acted on at once: once the server and Kad saw us at the same
  address, a new one seen by the server has Kad check its firewall again, and one seen by Kad has
  njMule connect to the server again; the log tells it.
- Obfuscated UDP with servers: a global search goes encrypted, to the server's obfuscated UDP
  port, to every server that takes it and gave a UDP key for our current address, and its
  answers are decrypted; with obfuscation required, servers that cannot take it are not asked.
- A new profile starts from the server list and the Kad contacts of upd.emule-security.org,
  shipped with njMule: their servers fill a profile that never had a list (one emptied later stays
  empty), and Kad asks the shipped contacts for contacts one at a time, without taking them as its
  own, from the start next to the saved ones, and again after a five-minute pause when they ran
  out before Kad connected.
- While Kad is not connected, a client met over eD2K that runs Kad 2 is asked for contacts (one
  every ten seconds at most), so a working eD2K connection also brings Kad in.
- Sizes and rates use the units of the interface language as eMule's translations write them
  (Ko and Ko/s in French, Кб in Russian).
- The server in use stands out in the server list (bold, with an accent bar and a tooltip), and the one
  being tried is in italics, whether njMule chose it or the user did.
- A first-start wizard in the desktop interface: language and look, nick, incoming and shared
  folders, ports, UPnP and the speed of the line (with presets), and an eMule found on the computer
  to import, one page each and all skippable. It opens at the first start only and again from Tools
  and the command palette.
- A capture tour of the desktop interface for design reviews (`CaptureTour`, run on demand with
  `--tests CaptureTour -Pnjmule.capture=<folder>`): every screen, tab, menu, sub-menu, context menu,
  drop-down list, column menu, dialog and message kind, with example data, in light, dark, high
  contrast, English and right-to-left sets.
- Docker image `ghcr.io/njmule/njmule` for `linux/amd64` and `linux/arm64`: the native headless
  engine with its web interface on a distroless base, run as an unprivileged user, its data in the
  `/njmule` volume, the eD2K, Kad and web interface ports exposed, stopped cleanly by `docker stop`;
  the release workflow builds it from the Linux executables, checks it and pushes it for a tag.
  `packaging/docker` holds the Dockerfile, which also compiles from the sources, and a Compose file.
- Synology DSM 7 package (`.spk`, x64) built by `packaging/synology/build-spk.sh` around a fully
  static executable of the headless engine (musl, `tools/native-static-docker.sh`), since DSM's
  glibc is too old for the Linux one: it runs as its own unprivileged user, asks the web interface
  password in the install wizard (keeping only its hash), downloads into the shared folder `njmule`
  it creates, opens the web interface from Package Center and the DSM menu, accepts the NAS's name,
  declares its ports to the DSM firewall and saves on stop. The same executable also ships alone,
  `njmule-<version>-linux-x64-static.tar.xz`, for any x64 Linux whatever its C library.
- `--webui-password-file <file>` takes the web interface password from the first line of a file,
  such as a Docker or systemd secret, never printing or logging it: with `--webui-password` it is
  set without asking; on its own it is set at every start, before njMule runs.
- Host names for the web interface (`hosts` in `webui.properties`, `--webui-host <name>` beside
  `--webui-password` or `--webui-password-file`, a field of the web interface settings of the
  desktop app and the Settings page of the web interface itself, which keeps the name it is open
  under; with `--webui-lan` also the Synology QuickConnect name of the address a browser reached,
  `192-168-1-80.<id>.direct.quickconnect.to`, under which DSM opens it): it then
  answers under a LAN name such as `nas.local` and behind a reverse proxy (Traefik, nginx, Caddy),
  taking forms whose origin is one of the names on any port, over HTTP or HTTPS; other names are
  still refused against DNS rebinding and other origins against cross-site requests. No forwarded
  header is trusted; the session cookie is `Secure` when the browser logged in over HTTPS. The
  pages link to one another, to their style sheet and to their forms, and redirect, relative to
  themselves, so that a proxy may also serve the interface under a path it strips (`/njmule/`).
  Proxies named as trusted (`proxies` in `webui.properties`, `--webui-proxy <address>` with an
  address, a CIDR block or a range, a field of the web interface settings; none by default) name
  their client in `X-Forwarded-For`, read from the right past the trusted proxies, so that behind
  them each client has its own login and API token attempts; no other address is believed.
- Update check: at each start and once a day (or only when asked: `update.mode`), njMule fetches
  the update information of the newest GitHub release (test versions too on the `prerelease`
  channel, `update.channel`) through the proxy and network interface of the preferences, and takes
  it only when it is signed by a key built into njMule (minisign: Ed25519 over BLAKE2b-512). A
  newer version shows with its notes and how to install it by the way this copy was installed
  (installer, archive, disk image, apt or dnf, Package Center, docker compose): as a notice and in
  the Updates window of the desktop app, on the status and settings pages of the web interface,
  through `njmule --check-update` (exit code 100 when one is out) and `njmule --remote update
  [check|skip <version>]`, through the remote-control API (`/update`, `update.status` and
  `update.available` events) and in the log. A version can be skipped unless it fixes a security
  problem; a manifest older than one already seen is refused. Nothing is downloaded or installed.
  Each release carries the signed `njmule-update.json` and a signed `SHA256SUMS`, with provenance
  attestations for every file.
- Updates downloaded and installed: the Updates window, the web interface (settings),
  `njmule --update` and `njmule --remote update download|install [at-exit]` (API `POST
  /update/download` and `/update/install`, events `update.ready` and `update.restart`, and the
  state, progress and installer in `GET /update`) download the package of the new version for this
  installation through the proxy and the network interface, going on from where a dropped download
  stopped, keep it only when its length and SHA-256 are those of the signed update information, and
  unpack it. njMule installs it where it may replace itself: the per-user Windows installer through
  a copy of its launcher that runs msiexec once njMule has stopped (a per-machine installation asks
  for administrator rights and is never installed unattended), the Windows zip and the Linux
  archives by swapping their files, the macOS app by swapping its bundle, after a clean stop of the
  engine. Restart to update installs at once and starts njMule again; the new program must name its
  version, then run its engine for 30 s, or the old files, kept as `.old` until then, come back. The
  deb and rpm packages, Synology and Docker are only downloaded and told how to install. The
  preference `update.mode` gains `download` and `install` (installed when njMule exits, or also in
  the night hours: `update.installAt`, `update.nightHours`); a version whose installation failed is
  not installed again on its own. The configuration is backed up before a version first uses it.
  The release workflow checks on a manual run that a test build updates itself from a local server
  (Windows installer and zip, Linux archive in a container, macOS app).
- The desktop app draws its own line icons, one stroke on one grid, in the colour of the text beside
  them in every theme; in right-to-left languages only the icons that point the way text runs are
  mirrored.
- Titles and the wordmark of the desktop app are set in Manrope, bundled with it (SIL Open Font
  License); text stays in the system's face, which covers every script of the translations.
- A chosen toggle of the desktop app is a pastel fill in an accent outline instead of a solid accent
  block, table rows are 32 px tall, and the lines of the rate graph keep 3:1 against the surface in
  light and dark and stay apart for colour-blind eyes.
- The desktop app's frame follows its new design: a sidebar with the logo and labelled places, which
  folds to its icons with Ctrl+B and by itself in a narrow window; a top bar with the screen's title
  and a line on what it holds, a field that searches the network or takes eD2K links (Ctrl+K), the
  rates with their last half minute drawn beside, one neutral control for both networks whose menu
  connects or disconnects eD2K, Kad or both (the Connect buttons of the servers and of Kad are gone),
  and a More menu with the tools, the theme and About; at most two messages, at the bottom centre,
  the same one counting up instead of stacking; a status strip with the last event, the users and
  files, the speed limits and the free space where downloads go. The window works down to 900×600,
  its tools then showing only their icons.
- The transfers of the desktop app follow the new design: segments with counts choose the list
  (downloads, uploads, queue, banned) in place of the split below; chips with counts narrow the
  downloads to a state, beside a list of the categories and the filter (Ctrl+F). The table is
  compact: an icon of what each file holds and the dot of its category, a thin bar with the
  percentage beside it, the sources sending out of those known and the state on a chip; Completed,
  Priority and Category are in the column menu. The details of the chosen download stand under the
  list, behind a divider that can be dragged and is remembered: the share done in large figures, the
  part map with its legend, a note when no source has the file or the disk is full and what to do now
  on the left, beside tabs for its sources, its facts, the names the sources give it and the comments
  heard. Add links
  opens a dialog that finds the file links in what is pasted or typed, those on the clipboard already
  in it; Delete cancels the chosen downloads after the question and Ctrl+C copies their links. The
  download menu is shorter, the rarer commands under More, and the sources a file shares with
  other downloads are named in plain words.
- The search of the desktop app follows the new design: the filters of the next search (the type as
  chips, the sizes, the availability, the extension) open in a popup from a Filters button that
  counts those in use; with no search open the screen says how to word one; a result being
  downloaded is bold with a blue chip, one shared or downloaded already has a chip of its own, and
  the results are coloured by what they are to us, in the theme's tones: red when downloading or
  shared already, green when downloaded or cancelled before, muted when spam, the others from the
  text's colour towards blue as they have more sources; a bar
  under the results names the chosen ones with their size and sources and offers to download them,
  copy their links or search related files, and More, Stop and the filter sit beside the tabs.
- The network of the desktop app follows the new design: three cards say in plain words whether
  others reach us on eD2K and on Kad (the technical term in a tooltip) and which ports we use, each
  with what to do (connect, choose another server, recheck the firewall, test the ports); the switch
  below counts the servers and the Kad contacts. The details of the chosen server stand beside the
  list: its users, files, limits, version and ping, what it said in this session, Connect or
  Disconnect and the static list; Add servers opens the ways to add them in a popup, also offered by
  an empty list. Kad draws its contacts by distance, from the closest to the farthest, beside our
  node's facts. The log, the server info and My Info have a place of their own in the network, and
  chips with counts narrow the log to its errors, warnings or news; the last event in the status
  strip opens it.
- The options of the desktop app follow the new design: twelve pages chosen from a list beside them
  (General, Connection, Bandwidth, Server, Proxy, Directories, IP filter, Messages, Scheduler,
  events, Web interface, Extended), each option a row with its name, what it does and its control,
  switches for the yes-or-no ones and narrow fields for numbers. A box finds options by their words
  on every page and a chip keeps only those changed from their defaults, which carry a mark and a
  link back to the default. Each option applies as soon as it is set; the ports, UPnP, the proxy
  and the network interface wait for Apply and reconnect, the language for Apply and restart, in a
  bar under the page that also offers to discard them. A wrong value is named under its option,
  with what it takes; the proxy's fields are off while there is no proxy, and the scheduler has a
  page of its own, saving each rule as it is added, replaced or removed.
- The shared files, the messages and the statistics follow the new design. The shared files list
  their places in a tree beside the files: all of them, the incoming folder and each shared folder
  with the subfolders that hold files. One button shares a folder; the place chosen tells its files
  and their size, opens or unshares the folder and turns the sharing of its subfolders on or off,
  and a segment counts the requests and transfers of this session or of all of them. The messages
  put the conversations and the friends in one list, the newest first, each with the person's
  letters, a star for a friend, the last line and a count of what is new; a box finds people by
  name or address, a segment keeps the friends only, and a CAPTCHA stands on a light plate that
  reads in a dark theme too. The statistics open on cards of what happens now, then the rates over
  time with their times under the lines and a word when nothing moved, the session's and all
  sessions' figures side by side, and every statistic in a pane that opens on demand.
- The dialogs follow the new design, in one style: what is decided as the title, the words and the
  fields below with their names above them, and the answers on a band in the platform's order,
  named by their outcome; the safe answer is the default, ringed, and a risky one is outlined in
  red. Quitting, links copied in another program, removing all servers, sharing a folder (a whole
  drive or a personal folder with a warning whose default is choosing another), renaming a shared
  file, adding a source and cancelling downloads ask this way, and the windows of their own
  (About, categories, comments, a client's files, the import) share its header and band. The
  first start shows its steps beside the pages, with a check on each one done that leads back to
  it, in a shorter window; the import checks first and then makes Import the main action, with
  its outcomes as chips. The menu of the notification area opens on njMule's name with the state
  and the rates, keeps Show njMule as its default and Quit always there, and shows only Connect or
  Disconnect; MiniMule tells the state with a dot and draws the last half minute of the rates.
- The desktop app reads well in every theme: a test measures the contrast of the text and the marks
  it draws against what is behind them in all the AtlantaFX themes, light and dark, and the few
  theme tones that fell short of WCAG AA on its surfaces are a little stronger (the muted and red
  text of Nord Dark, the red and orange of Cupertino, the green tint of Dracula). Screen readers
  hear the progress bars as progress with their value, the state chips, file names and outcomes
  in the tables as words, and the rate graph as a sentence with the rates now and the highest.
  "All" reads right in the messages and the log in every language, three misspelt Arabic texts
  taken from eMule are corrected, and the README shows the redesigned transfers.

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
- The search parameters fold behind a button that counts those in use. The search has two rows: a
  wide box for the words with Start, then where to search (Automatic, Server, Global, Kad shown at
  once as joined buttons), the parameters and what acts on the results.
- The media columns of a search (artist, album, title, length, bitrate, codec) show while its results
  hold songs or films and hide otherwise, until the column menu shows or hides one of them.
- The options have an index of their sections and a box that finds an option by its words.
- The rate graph has a scale; chat tabs with unread messages are marked. The statistics show what
  happens now first, then the graph, then the session and the cumulative figures, each in a regular
  grid of cards; the statistics tree has dense rows.
- Chats show each line as a bubble that wraps, ours at the end of the row and theirs at the start,
  with the time and the author above the words; the friends list has dense rows and the address of
  a new conversation is named beside its box.
- Questions and text boxes drop the question-mark picture and the header that only repeated the kind
  of alert; warnings keep their sign.
- What was done with a search result (downloading, shared, downloaded, cancelled, spam) shows with
  an icon besides its tint and words.
- eD2K links dropped on the window become downloads; the shared folders window can share a folder.
- Rates switch to MB/s from a thousand KB/s on, and to GB/s from a thousand MB/s, in the desktop
  and the web interfaces, with the units of each language; the rate graph rounds its scale to whole
  units of what it shows. Speeds are typed that way too: the limits in the options and the scheduler
  in KB/s or MB/s with decimals, or "No limit" instead of 0; the speed of the line in Mbit/s, as
  lines are sold, with the same speed in bytes beside; the limit sliders of the top bar and the
  tray on a logarithmic scale, from no limit to the line's speed; `njmule --remote limits` takes
  500K, 1.5M or 1G. Settings and the API keep KiB/s.
- Ctrl+K opens a command palette: typing finds a place or a command of the menus, searches the
  network for the words typed, or downloads the eD2K links pasted.
- Kad tells its state in plain words with what to do about it, and the two ways to join it (a list
  of nodes from the web, or a client one knows) sit together, explained, in a section that is open
  while Kad is not connected.
- The servers list takes the whole width: My Info moved to a tab beside the server messages and the
  log, its sections side by side, and the ways to add servers (a server.met from the web, an
  address, a server.met file) sit together in a section that is open while the list is empty.
- The text of the server messages and of the log is chosen with the mouse, also across lines, and
  copied with the system's shortcut or the context menu, which also chooses it all; the lines keep
  their colours and icons.
- The commands that act on the chosen downloads, shared files or search results are off while none
  is chosen, and More and Stop while no search is open; a line in the transfers tool row sets them
  apart from the commands on the whole list.
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

- Kad started again at once (turned on again, or restarted for a new port) could fail to bind its
  UDP port, mostly on Linux: a closed UDP socket now frees its port before close returns. The same
  holds for the server UDP socket when Kad takes its port.
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
