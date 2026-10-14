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
package org.njmule.core.persist.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.PartMet.Gap;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.Tag.IntTag;
import org.njmule.core.protocol.tag.TagName;

/** Corner cases of the binary files of an eMule profile. */
class MetFilesEdgeCaseTest {

    private static final Hash16 H = Hash16.parseHex("000102030405060708090A0B0C0D0E0F");

    @Test
    void gapTagsPairByTheirNumberNotItsSpelling() {
        PartMet.Contents contents = partMet(
                Tag.of(FileTag.FILESIZE, 1000),
                new IntTag(TagName.of("\t01"), 100),
                new IntTag(TagName.of("\n1"), 200),
                new IntTag(TagName.of("\t000"), 300),
                new IntTag(TagName.of("\n0"), 400));

        assertThat(contents.gaps()).containsExactly(new Gap(100, 200), new Gap(300, 400));
    }

    private static PartMet.Contents partMet(Tag... tags) {
        return new PartMet.Contents(PartMet.VERSION, 0, H, List.of(), List.of(tags));
    }
}
