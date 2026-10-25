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
package org.njmule.gui.infra;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text smileys of chat messages shown as emoji, as the other clients type them; only a smiley standing alone
 * between blanks changes, so that addresses and code keep their colons and brackets. The messages sent stay as typed.
 */
public final class Smileys {

    private static final Map<String, String> EMOJI = Map.ofEntries(
            Map.entry(":)", "🙂"),
            Map.entry(":-)", "🙂"),
            Map.entry(":(", "🙁"),
            Map.entry(":-(", "🙁"),
            Map.entry(";)", "😉"),
            Map.entry(";-)", "😉"),
            Map.entry(":D", "😃"),
            Map.entry(":-D", "😃"),
            Map.entry(":P", "😛"),
            Map.entry(":p", "😛"),
            Map.entry(":-P", "😛"),
            Map.entry(":O", "😮"),
            Map.entry(":o", "😮"),
            Map.entry(":'(", "😢"),
            Map.entry(":|", "😐"),
            Map.entry("8)", "😎"),
            Map.entry(":*", "😘"),
            Map.entry("<3", "❤"));

    private static final Pattern SMILEY = Pattern.compile("(?<=^|\\s)(:-?[)(DPpOo|*]|;-?\\)|:'\\(|8\\)|<3)(?=$|\\s)");

    private Smileys() {}

    /** {@code text} with its smileys as emoji. */
    public static String show(String text) {
        Matcher m = SMILEY.matcher(text);
        StringBuilder shown = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(shown, Matcher.quoteReplacement(EMOJI.getOrDefault(m.group(1), m.group(1))));
        }
        m.appendTail(shown);
        return shown.toString();
    }
}
