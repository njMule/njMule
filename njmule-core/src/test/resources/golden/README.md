# Golden vectors and corpora

- `file-hashes.txt` and `obfuscation.txt`: vectors made by an independent Python implementation
  (`tools/golden/`), which the engine must match.
- `*.jsonl`: corpora, inputs of a decoder with the answers the Java engine gives them, which
  another engine replays and must answer alike. One JSON object per line: `kind`, the input `in`
  (hex) with what the kind needs to decode it, then the answer, or `"error":"decode"` where the
  input is refused. The tests that check them (`*CorpusTest`) write them again with
  `-Pnjmule.corpus.write=true`, keeping the inputs; a deleted file gets new ones.

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
