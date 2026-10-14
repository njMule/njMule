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
package org.njmule.core.peer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.tag.ClientTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

class PeerSoftwareTest {

    private static final Hash16 HASH = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    @Test
    void namesTheSoftwareFromItsVersionTag() {
        assertThat(PeerSoftware.describe(new Hello(HASH, 0, 4662, List.of(), 0, 0)))
                .isEqualTo("eDonkey compatible");
        assertThat(PeerSoftware.describe(hello(version(0, 0, 50, 1)))).isEqualTo("eMule v0.50b");
        assertThat(PeerSoftware.describe(hello(version(0, 0, 70, 7)))).isEqualTo("eMule v0.70h");
        assertThat(PeerSoftware.describe(hello(version(3, 2, 3, 1)))).isEqualTo("aMule v2.3.1");
        assertThat(PeerSoftware.describe(hello(version(40, 1, 2, 3)))).isEqualTo("eMule compatible (client 40) v1.2.3");
    }

    @Property
    void anyVersionTagIsDescribed(@ForAll int tag) {
        assertThat(PeerSoftware.describe(hello(tag))).isNotBlank();
    }

    private static int version(int compat, int major, int minor, int update) {
        return compat << 24 | major << 17 | minor << 10 | update << 7;
    }

    private static Hello hello(int version) {
        return new Hello(
                HASH,
                0,
                4662,
                List.of(new Tag.IntTag(TagName.id(ClientTag.EMULE_VERSION), Integer.toUnsignedLong(version))),
                0,
                0);
    }
}
