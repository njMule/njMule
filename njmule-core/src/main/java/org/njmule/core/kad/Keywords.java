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
package org.njmule.core.kad;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.njmule.core.files.hash.Ed2k;

/**
 * The keywords Kad indexes a file name under, and their ids: words of at least three bytes, in the network's fixed
 * lower case, without the extension; the id is the MD4 hash of the word in UTF-8.
 */
public final class Keywords {

    /** Characters that separate words. */
    public static final String DELIMITERS = " ()[]{}<>,._-!?:;\\/\"";

    private static final int MIN_BYTES = 3;

    private Keywords() {}

    /** The keywords of {@code text}, in order and without duplicates. */
    public static List<String> of(String text) {
        List<String> words = new ArrayList<>();
        String last = "";
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || DELIMITERS.indexOf(text.charAt(i)) >= 0) {
                last = text.substring(start, i);
                start = i + 1;
                if (last.getBytes(StandardCharsets.UTF_8).length >= MIN_BYTES) {
                    String word = KadLowerCase.toLowerCase(last);
                    words.remove(word);
                    words.add(word);
                }
            }
        }
        // A last part of three single-byte characters is taken for the extension.
        if (words.size() > 1 && last.length() == 3 && last.getBytes(StandardCharsets.UTF_8).length == 3) {
            words.removeLast();
        }
        return words;
    }

    /** The id a keyword is published under. */
    public static UInt128 id(String word) {
        byte[] utf8 = word.getBytes(StandardCharsets.UTF_8);
        return UInt128.of(Ed2k.md4(utf8, 0, utf8.length));
    }
}
