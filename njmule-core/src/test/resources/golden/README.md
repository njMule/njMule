# Golden vectors and corpora

- `file-hashes.txt` and `obfuscation.txt`: vectors made by an independent Python implementation
  (`tools/golden/`), which the engine must match.
- `*.jsonl`: corpora, inputs of a decoder with the answers the Java engine gives them, which
  another engine replays and must answer alike. One JSON object per line: `kind`, the input `in`
  (hex) with what the kind needs to decode it, then the answer, or `"error":"decode"` where the
  input is refused. The Java tests that check them (`*CorpusTest`) write them again with
  `-Pnjmule.corpus.write=true`, keeping the inputs; a deleted file gets new ones. The Rust
  engine replays them in `njmule-engine/tests`.

## `tag-codec.jsonl`

Kinds `ed2k-tag` (one eD2K tag), `ed2k-tags` (`count` eD2K tags, at most 64) and `kad-tags` (a Kad
tag list), as the codec writes them and changed. Answers: `used`, the bytes read; the tags written
again in the new eD2K encoding (`new`), the old one (`old`) or as a Kad list (`kad`), null where
writing them is refused.

## `packets.jsonl`

Every message of `MutatedMessagesTest`, kind its name, as it is made and changed three times: the
packet's `protocol` and `opcode` with its payload in `in`. The answer `value` is the decoded message
in the form of `Canonical`: records as objects of their components, with an `@type` where they
implement a sealed interface (tags); integers as the unsigned values of their width; floats as the
hex of their bits; bytes, hashes and Kad ids (big-endian) in lower-case hex; a tag name as its id
or the hex of its bytes; an inner packet as `protocol`, `opcode` and `payload`.

## `profile-files.jsonl`

The files of a profile, kind their name: the `.met` files of `MetFilesEdgeCaseTest`, `nodes.dat`,
`preferences.dat`, the Kad indexes (`load_index.dat`, `src_index.dat`, `key_index.dat`) and the
text files `ipfilter.dat`, `staticservers.dat` and `shareddir.dat`, each as the engine writes it
and changed three times; `known.met salvaged` and `known2_64.met salvaged` read the same files as a
damaged one is read. The answer `value` is what the reader gives, in the form of `Canonical` (an
AICH tree of `known2_64.met` as its `masterHash` and `blockHashes`).

## Text corpora

The text a parser reads is in `text` (well-formed Unicode: a lone surrogate fits neither JSON nor
another engine's strings).

- `ed2k-links.jsonl`: links written by hand (with the mistakes browsers and sites make) and links
  the codec writes from generated names and hosts, each also changed; `value` is the link parsed,
  `"error":"invalid"` a refusal.
- `kad-keywords.jsonl`: the `lower-case` case lists every UTF-16 unit the network's lower case
  changes, as `[unit, lowered]`; each `keywords` case lists the words Kad publishes a name under
  with their ids (MD4 of the word in UTF-8, in hex).
- `search-expressions.jsonl`: searches written by hand and joined from the fragments of
  `SearchParserTest`; `value` is the search parsed and `wire` its bytes for a server that takes
  64-bit sizes, or `error` the reason it is refused.

## `hashes.jsonl`

What the golden vectors do not show, the data's own hashes being theirs:

- `ed2k-layout`: a `size` at the edges of blocks, parts, the empty last part, 32 bits and the
  largest file, with its `parts`, `ed2kParts` (with the empty last part), `hashSet` (part hashes a
  hash set holds) and `lastPartLength` (none for an empty file), or `"error":"invalid"`;
- `aich-tree`: a `size` and its `blocks` (the hashes of its 180 KiB blocks), with the tree's
  `master` hash and `parts` hashes;
- `aich-recovery`: the recovery data `in` of `part` of a file of `size` with the AICH `master`
  hash, as made and changed, with the block hashes it proves, or `"error":"unproven"`.
