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
interface, credential and identity storage. Out of scope: weaknesses that are part of the eD2K
protocol itself (e.g. obfuscation is not encryption) unless njMule makes them worse.
