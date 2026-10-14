/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.core.search;

import java.util.ArrayList;
import java.util.List;
import org.njmule.core.files.FileTypes;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.search.SearchExpr.And;
import org.njmule.core.search.SearchExpr.Comparison;
import org.njmule.core.search.SearchExpr.Keyword;
import org.njmule.core.search.SearchExpr.Not;
import org.njmule.core.search.SearchExpr.NumericAttr;
import org.njmule.core.search.SearchExpr.Or;
import org.njmule.core.search.SearchExpr.StringAttr;

/**
 * The search tree sent to servers, in prefix order: operators {@code <0><0 AND|1 OR|2 NOT>}, keywords
 * {@code <1><string>}, string attributes {@code <2><string><u16 1><tag>} and numeric attributes {@code <3><u32>} (or
 * {@code <8><u64>} for servers with 64-bit support) followed by {@code <comparison><u16 1><tag>}.
 */
public final class SearchTreeCodec {

    private static final int OPERATOR = 0;
    private static final int KEYWORD = 1;
    private static final int STRING = 2;
    private static final int UINT32 = 3;
    private static final int UINT64 = 8;

    private static final int MAX_NODES = 256;
    private static final int MAX_DEPTH = 64;

    private SearchTreeCodec() {}

    /**
     * The tree to send: an expression made only of ANDed keywords becomes one keyword with the words joined by spaces,
     * which servers handle more efficiently; archives and disc images are asked for as {@link #indexedTypes programs}.
     */
    public static SearchExpr forServer(SearchExpr expr) {
        List<String> words = new ArrayList<>();
        return onlyAndedKeywords(expr, words) && words.size() > 1
                ? new Keyword(String.join(" ", words))
                : indexedTypes(expr);
    }

    /**
     * {@code expr} with archives and disc images asked for as programs, the type servers and Kad nodes index them
     * under; the same tree when it asks for neither.
     */
    public static SearchExpr indexedTypes(SearchExpr expr) {
        return switch (expr) {
            case And e -> {
                SearchExpr left = indexedTypes(e.left());
                SearchExpr right = indexedTypes(e.right());
                yield left.equals(e.left()) && right.equals(e.right()) ? e : new And(left, right);
            }
            case Or e -> {
                SearchExpr left = indexedTypes(e.left());
                SearchExpr right = indexedTypes(e.right());
                yield left.equals(e.left()) && right.equals(e.right()) ? e : new Or(left, right);
            }
            case Not e -> {
                SearchExpr left = indexedTypes(e.left());
                SearchExpr right = indexedTypes(e.right());
                yield left.equals(e.left()) && right.equals(e.right()) ? e : new Not(left, right);
            }
            case StringAttr a
            when a.tag() == FileTag.FILETYPE
                    && (a.value().equals(FileTypes.ARCHIVE) || a.value().equals(FileTypes.DISC_IMAGE)) ->
                new StringAttr(a.tag(), FileTypes.PROGRAM);
            default -> expr;
        };
    }

    public static void write(PacketWriter out, SearchExpr expr, boolean supports64Bit) {
        switch (expr) {
            case And e -> {
                out.u8(OPERATOR).u8(0);
                write(out, e.left(), supports64Bit);
                write(out, e.right(), supports64Bit);
            }
            case Or e -> {
                out.u8(OPERATOR).u8(1);
                write(out, e.left(), supports64Bit);
                write(out, e.right(), supports64Bit);
            }
            case Not e -> {
                out.u8(OPERATOR).u8(2);
                write(out, e.left(), supports64Bit);
                write(out, e.right(), supports64Bit);
            }
            case Keyword k -> out.u8(KEYWORD).string16(k.text(), StringEncoding.UTF8);
            case StringAttr a ->
                out.u8(STRING).string16(a.value(), StringEncoding.UTF8).u16(1).u8(a.tag());
            case NumericAttr a -> {
                if (a.value() > 0xFFFF_FFFFL && supports64Bit) {
                    out.u8(UINT64).u64(a.value());
                } else {
                    out.u8(UINT32).u32(Math.min(a.value(), 0xFFFF_FFFFL));
                }
                out.u8(a.comparison().code()).u16(1).u8(a.tag());
            }
        }
    }

    /** Reads a tree as written by {@link #write}; bounded in depth and size. */
    public static SearchExpr read(PacketReader in) throws DecodeException {
        int[] nodes = {0};
        return read(in, 0, nodes);
    }

    private static SearchExpr read(PacketReader in, int depth, int[] nodes) throws DecodeException {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) {
            throw new DecodeException("search tree too large");
        }
        int type = in.u8();
        return switch (type) {
            case OPERATOR -> {
                int op = in.u8();
                SearchExpr left = read(in, depth + 1, nodes);
                SearchExpr right = read(in, depth + 1, nodes);
                yield switch (op) {
                    case 0 -> new And(left, right);
                    case 1 -> new Or(left, right);
                    case 2 -> new Not(left, right);
                    default -> throw new DecodeException("unknown search operator " + op);
                };
            }
            case KEYWORD -> {
                String text = in.string16();
                if (text.isEmpty()) {
                    throw new DecodeException("empty search keyword");
                }
                yield new Keyword(text);
            }
            case STRING -> {
                String value = in.string16();
                yield new StringAttr(tag(in), value);
            }
            case UINT32, UINT64 -> {
                long value = type == UINT32 ? in.u32() : in.u64();
                if (value < 0) {
                    throw new DecodeException("search value out of range");
                }
                int code = in.u8();
                if (code > Comparison.NOT_EQUAL.code()) {
                    throw new DecodeException("unknown comparison " + code);
                }
                yield new NumericAttr(tag(in), Comparison.of(code), value);
            }
            default -> throw new DecodeException("unknown search tree node " + type);
        };
    }

    private static int tag(PacketReader in) throws DecodeException {
        int length = in.u16();
        if (length != 1) {
            throw new DecodeException("search attributes use one-byte tag ids");
        }
        return in.u8();
    }

    private static boolean onlyAndedKeywords(SearchExpr expr, List<String> words) {
        return switch (expr) {
            case And e -> onlyAndedKeywords(e.left(), words) && onlyAndedKeywords(e.right(), words);
            case Keyword k -> words.add(k.text());
            default -> false;
        };
    }
}
