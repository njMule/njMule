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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash20;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;

/** The AICH root a Kad node tells with a keyword result, and when it is taken. */
class KadNodeAichTest {

    private static final Hash20 ROOT = root(0x0A);
    private static final Hash20 OTHER = root(0x0B);

    private final List<Tag> tags = new ArrayList<>();
    private Optional<Hash20> taken;

    @Test
    void shouldTakeTheOneRootAThirdOfThePublishersGave() {
        givenThePublishers(9);
        givenTheRootsTold(new int[] {3}, ROOT);

        whenTheRootIsRead();

        thenTheRootTakenIs(Optional.of(ROOT));
    }

    @Test
    void shouldTakeNoRootFewerThanAThirdOfThePublishersGave() {
        givenThePublishers(12);
        givenTheRootsTold(new int[] {3}, ROOT);

        whenTheRootIsRead();

        thenTheRootTakenIs(Optional.empty());
    }

    @Test
    void shouldTakeNoRootWhenThePublishersGaveTwo() {
        givenThePublishers(4);
        givenTheRootsTold(new int[] {2, 2}, ROOT, OTHER);

        whenTheRootIsRead();

        thenTheRootTakenIs(Optional.empty());
    }

    @Test
    void shouldTakeNoRootWithoutTheNumberOfPublishers() {
        givenTheRootsTold(new int[] {1}, ROOT);

        whenTheRootIsRead();

        thenTheRootTakenIs(Optional.empty());
    }

    @Test
    void shouldTakeNoRootFromACutAnswer() {
        givenThePublishers(1);
        givenACutRootTag();

        whenTheRootIsRead();

        thenTheRootTakenIs(Optional.empty());
    }

    // The publish info holds the number of publishers in its third byte.
    private void givenThePublishers(int publishers) {
        this.tags.add(new Tag.IntTag(TagName.id(FileTag.PUBLISHINFO), (long) publishers << 16));
    }

    private void givenTheRootsTold(int[] popularity, Hash20... roots) {
        PacketWriter out = new PacketWriter();
        out.u8(roots.length);
        for (int i = 0; i < roots.length; i++) {
            out.u8(popularity[i]);
            out.bytes(roots[i].toByteArray());
        }
        this.tags.add(new Tag.BsobTag(TagName.id(FileTag.KADAICHHASHRESULT), Bytes.of(out.toByteArray())));
    }

    private void givenACutRootTag() {
        this.tags.add(new Tag.BsobTag(TagName.id(FileTag.KADAICHHASHRESULT), Bytes.of(new byte[] {1, 1, 7})));
    }

    private void whenTheRootIsRead() {
        this.taken = KadNode.agreedAich(this.tags);
    }

    private void thenTheRootTakenIs(Optional<Hash20> expected) {
        assertThat(this.taken).isEqualTo(expected);
    }

    private static Hash20 root(int value) {
        byte[] bytes = new byte[Hash20.LENGTH];
        Arrays.fill(bytes, (byte) value);
        return Hash20.of(bytes);
    }
}
