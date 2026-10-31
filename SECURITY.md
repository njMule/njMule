<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Security policy

njMule talks to untrusted peers on the Internet, so security reports are taken seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private vulnerability reporting:
<https://github.com/njMule/njMule/security/advisories/new>.

Include the affected version (`njmule --version`), a description, and if possible a reproducer
(a packet capture or a small test). You will get an acknowledgement within 7 days; fixes are
released as patch versions and credited in the advisory unless you prefer otherwise.

## Supported versions

Until 1.0.0 only the latest `0.y.z` release receives security fixes.

## Scope

In scope: remote input handling (eD2K/Kad packets, server lists, links), file parsing, the web
interface, the remote-control API, the desktop window's control endpoint, credential and identity
storage. Out of scope: weaknesses that are part of the eD2K protocol itself (e.g. obfuscation is
not encryption) unless njMule makes them worse.

## Hardening

Beyond what the protocol asks, njMule defends itself and its users in these ways:

- **Input**: every packet, tag, list and file is read by a size-limited parser; frames are capped
  per connection state, inflated data at the size asked for, and a peer that stops reading is
  dropped once 16 MiB wait for it.
- **Identity**: credits, queue places, upload slots, the friend slot and friends-only features are
  bound to the identity a client proved with secure identification, not to the user hash it claims;
  a client that changes its user hash is banned, and njMule never takes itself as a source.
- **Abuse**: limits on the connections of an address and on the rate of new ones; bans for clients
  that ask too often, take upload slots without asking for data, ask for many files we do not have,
  or send corrupt data (found by part checks and AICH recovery); source exchange answers only for
  the file asked for and never to a banned address.
- **Kad**: answers count only from the nodes a lookup asked; a node must prove its id at its
  address, a contact only one node tells of is asked last, and floods lose their packets.
- **Filtering**: the IP filter applies to servers, Kad contacts and every source; multicast and
  broadcast addresses are refused, LAN ones unless allowed, and a server cannot make njMule connect
  to them or to banned clients. Lists from the web come from public addresses only, within size and
  redirect limits, never downgraded from HTTPS to HTTP.
- **Files**: personal and system folders, keys, backups and files other programs are still writing
  are never shared; names from the network lose control characters and direction overrides, and
  file names are made safe for every file system (reserved device names included).
- **Storage**: saves are atomic with a backup, and keys and settings (proxy and mail passwords
  included) are readable by their owner only on Linux and macOS; the profile of the Windows service
  by the service and the administrators only.
- **Web interface**: PBKDF2-hashed password, a login lockout (per /64 for IPv6), form tokens, origin
  and host checks against cross-site requests and DNS rebinding, HTTP-only same-site cookies,
  `Secure` over HTTPS, a strict Content-Security-Policy, and forwarded addresses believed only from
  the proxies named as trusted.
- **Remote-control API**: callers with the API token are answered only when the web interface's
  settings allow them; the token holds 256 random bits, is compared in constant time, and a wrong
  one counts as a failed login of its address. A guest's session may only read, whatever its page
  asks. Only the desktop window on njMule's computer may change the web interface's settings, read
  the API token, or make njMule start at login and open eD2K links.
- **Desktop window**: the engine it starts answers it on the loopback address only, at a port chosen
  for that start, and only with a random token made for that start, which the window reads from the
  engine's output; a request from a page of any other origin is refused, and the window's own pages
  run under a strict Content-Security-Policy.
- **Updates**: update information is taken only when signed by a key built into njMule, a package
  only when its length and SHA-256 match it, and the old version comes back if the new one does not
  start.
