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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ServerAddress;

class FriendsTest {

    private static final Hash16 PAL = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final Hash16 OTHER = Hash16.parseHex("FEDCBA9876543210FEDCBA9876543210");

    @TempDir
    Path dir;

    @Test
    void addingAFriendAgainWithoutANameOrAnAddressKeepsTheOnesKnown() throws Exception {
        Friends friends = Friends.load(this.dir);
        friends.add(new Friend(PAL, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662))));

        friends.add(new Friend(PAL, "", Optional.empty()));

        Friend kept = new Friend(PAL, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662)));
        assertThat(friends.all()).containsExactly(kept);
        assertThat(Friends.load(this.dir).all()).containsExactly(kept);
    }

    @Test
    void aNewNameOrAddressReplacesTheOldOne() throws Exception {
        Friends friends = Friends.load(this.dir);
        friends.add(new Friend(PAL, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662))));

        friends.add(new Friend(PAL, "new pal", Optional.of(new ServerAddress("10.0.0.8", 4663))));

        assertThat(friends.all())
                .containsExactly(new Friend(PAL, "new pal", Optional.of(new ServerAddress("10.0.0.8", 4663))));
    }

    @Test
    void friendsAreRemovedAndKeptInOrder() throws Exception {
        Friends friends = Friends.load(this.dir);
        friends.add(new Friend(PAL, "pal", Optional.empty()));
        friends.add(new Friend(OTHER, "other", Optional.empty()));
        assertThat(friends.contains(PAL)).isTrue();

        assertThat(friends.remove(PAL)).isTrue();
        assertThat(friends.remove(PAL)).isFalse();

        assertThat(friends.contains(PAL)).isFalse();
        assertThat(Friends.load(this.dir).all()).containsExactly(new Friend(OTHER, "other", Optional.empty()));
    }

    @Test
    void aBrokenFileCountsAsNoFriends() throws Exception {
        Files.write(this.dir.resolve(Friends.FILE_NAME), new byte[] {0x0E, 5, 0, 0, 0, 1});

        assertThat(Friends.load(this.dir).all()).isEmpty();
    }
}
