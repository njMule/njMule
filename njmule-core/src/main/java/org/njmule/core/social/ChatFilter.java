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
package org.njmule.core.social;

import com.google.common.base.Splitter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Which incoming chat messages show. With {@code friendsOnly} only friends' do; a message holding one of the
 * {@code words} never does; {@code againstSpam} leaves out, from others than friends, a link in a message before we
 * wrote, and more than three messages we did not answer, and then everything from that client.
 */
public record ChatFilter(boolean friendsOnly, List<String> words, boolean againstSpam) {

    /** Shows every message. */
    public static final ChatFilter NONE = new ChatFilter(false, List.of(), false);

    /** Unanswered messages shown at most. */
    public static final int MAX_UNANSWERED = 3;

    // What tells a link: a scheme or a host name start.
    private static final List<String> LINKS = List.of("http:", "https:", "ftp:", "ed2k:", "www.", "ftp.");

    public ChatFilter {
        words = List.copyOf(words.stream()
                .map(w -> w.strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .toList());
    }

    /** The words of {@code text}, apart by {@code |}, as the settings write them. */
    public static List<String> words(String text) {
        return Splitter.on('|').splitToList(Objects.requireNonNull(text, "text"));
    }

    /** What a client's conversation knows: what it sent, whether we wrote, whether it spammed. */
    public static final class State {
        private int received;
        private boolean wrote;
        private boolean spammer;

        /** We wrote to the client. */
        public void wrote() {
            this.wrote = true;
            this.received = 0;
        }
    }

    /** Whether a message of {@code text} from a client (a friend or not) shows; {@code state} keeps count. */
    public boolean shows(State state, boolean friend, String text) {
        if (this.friendsOnly && !friend) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean spamming = !friend && this.againstSpam;
        if (this.words.stream().anyMatch(lower::contains)) {
            state.spammer |= spamming && !state.wrote;
            return false;
        }
        if (spamming) {
            if (!state.wrote
                    && (state.received >= MAX_UNANSWERED || LINKS.stream().anyMatch(lower::contains))) {
                state.spammer = true;
            }
            if (state.spammer) {
                return false;
            }
        }
        state.received++;
        return true;
    }
}
