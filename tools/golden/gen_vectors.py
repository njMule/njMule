# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Golden vectors for the eD2K and AICH hashes, from a second, independent implementation.

The code follows the eMule C++ classes instead of the njMule Java code: CKnownFile::CreateFromFile
and CreateHash (KnownFile.cpp:346-489, 922-975) and CAICHHashTree (SHAHashSet.cpp), with MD4 from
RFC 1320 (Python's hashlib has no MD4). Pure Python is slow: generating takes about a minute.

Usage: python tools/golden/gen_vectors.py > njmule-core/src/test/resources/golden/file-hashes.txt
"""

import base64
import hashlib
import struct
import sys

PARTSIZE = 9_728_000
EMBLOCKSIZE = 184_320

SIZES = [1, 1000, EMBLOCKSIZE, EMBLOCKSIZE + 1, PARTSIZE - 1, PARTSIZE, PARTSIZE + 1,
         2 * PARTSIZE + EMBLOCKSIZE + 1, 3 * PARTSIZE]


def content(size):
    """Deterministic test data: byte i is (7 * i + i // 251) mod 256 (the Java test agrees)."""
    return bytes((7 * i + i // 251) & 0xFF for i in range(size))


# --- MD4, RFC 1320 -------------------------------------------------------------------------

def _rotl(x, s):
    x &= 0xFFFFFFFF
    return ((x << s) | (x >> (32 - s))) & 0xFFFFFFFF


def md4(data):
    a, b, c, d = 0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476
    msg = data + b"\x80" + b"\x00" * ((55 - len(data)) % 64) + struct.pack("<Q", 8 * len(data))
    for off in range(0, len(msg), 64):
        x = struct.unpack("<16I", msg[off:off + 64])
        aa, bb, cc, dd = a, b, c, d

        def f(x_, y, z):
            return (x_ & y) | (~x_ & z)

        def g(x_, y, z):
            return (x_ & y) | (x_ & z) | (y & z)

        def h(x_, y, z):
            return x_ ^ y ^ z

        for k in range(16):
            s = (3, 7, 11, 19)[k % 4]
            if k % 4 == 0:
                a = _rotl(a + f(b, c, d) + x[k], s)
            elif k % 4 == 1:
                d = _rotl(d + f(a, b, c) + x[k], s)
            elif k % 4 == 2:
                c = _rotl(c + f(d, a, b) + x[k], s)
            else:
                b = _rotl(b + f(c, d, a) + x[k], s)
        for i, k in enumerate((0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)):
            s = (3, 5, 9, 13)[i % 4]
            t = x[k] + 0x5A827999
            if i % 4 == 0:
                a = _rotl(a + g(b, c, d) + t, s)
            elif i % 4 == 1:
                d = _rotl(d + g(a, b, c) + t, s)
            elif i % 4 == 2:
                c = _rotl(c + g(d, a, b) + t, s)
            else:
                b = _rotl(b + g(c, d, a) + t, s)
        for i, k in enumerate((0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)):
            s = (3, 9, 11, 15)[i % 4]
            t = x[k] + 0x6ED9EBA1
            if i % 4 == 0:
                a = _rotl(a + h(b, c, d) + t, s)
            elif i % 4 == 1:
                d = _rotl(d + h(a, b, c) + t, s)
            elif i % 4 == 2:
                c = _rotl(c + h(d, a, b) + t, s)
            else:
                b = _rotl(b + h(c, d, a) + t, s)
        a, b, c, d = [(v + w) & 0xFFFFFFFF for v, w in ((a, aa), (b, bb), (c, cc), (d, dd))]
    return struct.pack("<4I", a, b, c, d)


# --- AICH tree, mirroring CAICHHashTree ------------------------------------------------------

class HashTree:
    def __init__(self, data_size, left_branch, base_size):
        self.data_size = data_size
        self.left_branch = left_branch
        self.base_size = base_size
        self.left = None
        self.right = None
        self.hash = None

    def find(self, start, size):
        """CAICHHashTree::FindHash: creates the path down to the node covering [start, size)."""
        if start == 0 and size == self.data_size:
            return self
        blocks = -(-self.data_size // self.base_size)
        n_left = (blocks + (1 if self.left_branch else 0)) // 2 * self.base_size
        n_right = self.data_size - n_left
        if start < n_left:
            if self.left is None:
                self.left = HashTree(n_left, True, EMBLOCKSIZE if n_left <= PARTSIZE else PARTSIZE)
            return self.left.find(start, size)
        if self.right is None:
            self.right = HashTree(n_right, False, EMBLOCKSIZE if n_right <= PARTSIZE else PARTSIZE)
        return self.right.find(start - n_left, size)

    def recalculate(self):
        """CAICHHashTree::ReCalculateHash."""
        if self.left is not None and self.right is not None:
            self.left.recalculate()
            self.right.recalculate()
            self.hash = hashlib.sha1(self.left.hash + self.right.hash).digest()


def ed2k_and_aich(data):
    size = len(data)
    root = HashTree(size, True, EMBLOCKSIZE if size <= PARTSIZE else PARTSIZE)
    part_hashes = []
    pos = 0
    part = 0
    while True:
        part_len = min(size - pos, PARTSIZE)
        chunk = data[pos:pos + part_len]
        if part_len:
            part_tree = root.find(part * PARTSIZE, part_len)
            for off in range(0, part_len, EMBLOCKSIZE):
                block = chunk[off:off + EMBLOCKSIZE]
                part_tree.find(off, len(block)).hash = hashlib.sha1(block).digest()
            part_tree.recalculate()
        part_hashes.append(md4(chunk))
        pos += part_len
        part += 1
        if pos == size:
            if part_len == PARTSIZE:
                continue  # one more, empty part (KnownFile.cpp:420-424)
            break
    root.recalculate()
    if size < PARTSIZE:
        file_hash, hashset = part_hashes[0], []
    else:
        file_hash, hashset = md4(b"".join(part_hashes)), part_hashes
    aich_parts = []
    if size > PARTSIZE:
        for p in range(-(-size // PARTSIZE)):
            aich_parts.append(root.find(p * PARTSIZE, min(PARTSIZE, size - p * PARTSIZE)).hash)
    return file_hash, hashset, root.hash, aich_parts


def b32(raw):
    return base64.b32encode(raw).decode("ascii").rstrip("=")


def main():
    # RFC 1320 self-check before trusting the pure Python MD4.
    assert md4(b"").hex() == "31d6cfe0d16ae931b73c59d7e0c089c0"
    assert md4(b"abc").hex() == "a448017aaf21d8525fc10ae87aa6729d"
    assert md4(b"1234567890" * 8).hex() == "e33b4ddc9c38f2199c3e7b164fcc0536"
    print("# Generated by tools/golden/gen_vectors.py; data: byte i = (7*i + i/251) mod 256")
    for size in SIZES:
        file_hash, hashset, master, aich_parts = ed2k_and_aich(content(size))
        print(" ".join([
            f"size={size}",
            f"md4={file_hash.hex().upper()}",
            "parts=" + (":".join(h.hex().upper() for h in hashset) or "-"),
            f"aich={b32(master)}",
            "aichparts=" + (":".join(b32(h) for h in aich_parts) or "-"),
        ]))
        sys.stdout.flush()


if __name__ == "__main__":
    main()
