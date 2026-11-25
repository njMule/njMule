<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Fuzzing `njmule-engine`

Fuzz targets for the untrusted network and file decoders of `njmule-engine`, using `cargo-fuzz`
(`libfuzzer-sys`).

## Targets

- `packet_framing` — TCP and UDP packet framing, decompression, client `Hello` and client/server messages.
- `tag_codec` — eD2K and Kad tag decoders.
- `met_files` — `.met` / `.dat` persistence decoders (`server.met`, `known.met`, `known2_64.met`,
  `part.met`, `nodes.dat`, `cancelled.met`, `clients.met`, `emfriends.met`, `ipfilter.dat`,
  `preferences.dat`, `preferencesKad.dat`, Kad index files, stored searches and spam markers).
- `kad_messages` — Kad UDP datagram framing and all Kad message decoders.
- `ed2k_link` — `ed2k://` URI parser.
- `search_parser` — search expression parser and binary search-tree decoder.

## Running

Requires a Rust nightly toolchain and `cargo-fuzz` (`cargo install cargo-fuzz`) on Linux or macOS:

```bash
cd njmule-engine
cargo +nightly fuzz run packet_framing -- -max_total_time=60
```

Seed corpora can be extracted from the golden JSONL test files under
`njmule-engine/tests/golden/`.
