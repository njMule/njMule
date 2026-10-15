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

import java.io.IOException;
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

    private Friends friends;
    private boolean removed;

    @Test
    void shouldKeepTheKnownNameAndAddressWhenAFriendIsAddedAgainWithoutThem() throws IOException {
        givenTheFriendsOfTheProfile();
        givenTheFriend(new Friend(PAL, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662))));

        whenTheFriendIsAdded(new Friend(PAL, "", Optional.empty()));

        thenTheFriendsAre(new Friend(PAL, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662))));
        thenTheSavedFriendsAre(new Friend(PAL, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662))));
    }

    @Test
    void shouldReplaceTheOldNameAndAddressWithNewOnes() throws IOException {
        givenTheFriendsOfTheProfile();
        givenTheFriend(new Friend(PAL, "pal", Optional.of(new ServerAddress("10.0.0.7", 4662))));

        whenTheFriendIsAdded(new Friend(PAL, "new pal", Optional.of(new ServerAddress("10.0.0.8", 4663))));

        thenTheFriendsAre(new Friend(PAL, "new pal", Optional.of(new ServerAddress("10.0.0.8", 4663))));
    }

    @Test
    void shouldRemoveAFriendAndKeepTheOthersInOrder() throws IOException {
        givenTheFriendsOfTheProfile();
        givenTheFriend(new Friend(PAL, "pal", Optional.empty()));
        givenTheFriend(new Friend(OTHER, "other", Optional.empty()));
        givenTheFriendIsKnown(PAL);

        whenTheFriendIsRemoved(PAL);

        thenTheFriendIsGone();
        thenTheFriendIsUnknown(PAL);
        thenTheSavedFriendsAre(new Friend(OTHER, "other", Optional.empty()));
    }

    @Test
    void shouldNotRemoveAFriendTwice() throws IOException {
        givenTheFriendsOfTheProfile();
        givenTheFriend(new Friend(PAL, "pal", Optional.empty()));
        givenTheFriend(new Friend(OTHER, "other", Optional.empty()));
        givenTheFriendWasRemoved(PAL);

        whenTheFriendIsRemoved(PAL);

        thenNoFriendIsRemoved();
    }

    @Test
    void shouldCountABrokenFileAsNoFriends() throws IOException {
        givenABrokenFriendsFile();

        whenTheFriendsOfTheProfileAreLoaded();

        thenThereAreNoFriends();
    }

    private void givenTheFriendsOfTheProfile() {
        this.friends = Friends.load(this.dir);
    }

    private void givenTheFriend(Friend friend) throws IOException {
        this.friends.add(friend);
    }

    private void givenTheFriendIsKnown(Hash16 userHash) {
        assertThat(this.friends.contains(userHash)).isTrue();
    }

    private void givenTheFriendWasRemoved(Hash16 userHash) throws IOException {
        assertThat(this.friends.remove(userHash)).isTrue();
    }

    private void givenABrokenFriendsFile() throws IOException {
        Files.write(this.dir.resolve(Friends.FILE_NAME), new byte[] {0x0E, 5, 0, 0, 0, 1});
    }

    private void whenTheFriendIsAdded(Friend friend) throws IOException {
        this.friends.add(friend);
    }

    private void whenTheFriendIsRemoved(Hash16 userHash) throws IOException {
        this.removed = this.friends.remove(userHash);
    }

    private void whenTheFriendsOfTheProfileAreLoaded() {
        this.friends = Friends.load(this.dir);
    }

    private void thenTheFriendsAre(Friend... expected) {
        assertThat(this.friends.all()).containsExactly(expected);
    }

    private void thenTheSavedFriendsAre(Friend... expected) {
        assertThat(Friends.load(this.dir).all()).containsExactly(expected);
    }

    private void thenTheFriendIsGone() {
        assertThat(this.removed).isTrue();
    }

    private void thenNoFriendIsRemoved() {
        assertThat(this.removed).isFalse();
    }

    private void thenTheFriendIsUnknown(Hash16 userHash) {
        assertThat(this.friends.contains(userHash)).isFalse();
    }

    private void thenThereAreNoFriends() {
        assertThat(this.friends.all()).isEmpty();
    }
}
