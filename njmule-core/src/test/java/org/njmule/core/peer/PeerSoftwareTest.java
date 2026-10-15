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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.tag.ClientTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

class PeerSoftwareTest {

    private static final Hash16 HASH = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    private Hello hello;
    private String description;

    @Test
    void shouldNameAPeerWithoutVersionTagEDonkeyCompatible() {
        givenAHelloWithoutVersionTag();

        whenTheSoftwareIsDescribed();

        thenTheDescriptionIs("eDonkey compatible");
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0, 50, 1, eMule v0.50b",
        "0, 0, 70, 7, eMule v0.70h",
        "3, 2, 3, 1, aMule v2.3.1",
        "40, 1, 2, 3, eMule compatible (client 40) v1.2.3"
    })
    void shouldNameTheSoftwareFromItsVersionTag(int compat, int major, int minor, int update, String name) {
        givenAHelloWithTheVersion(compat, major, minor, update);

        whenTheSoftwareIsDescribed();

        thenTheDescriptionIs(name);
    }

    @Property
    void shouldDescribeAnyVersionTag(@ForAll int tag) {
        givenAHelloWithTheVersionTag(tag);

        whenTheSoftwareIsDescribed();

        thenTheDescriptionIsNotBlank();
    }

    private void givenAHelloWithoutVersionTag() {
        this.hello = new Hello(HASH, 0, 4662, List.of(), 0, 0);
    }

    private void givenAHelloWithTheVersion(int compat, int major, int minor, int update) {
        givenAHelloWithTheVersionTag(version(compat, major, minor, update));
    }

    private void givenAHelloWithTheVersionTag(int version) {
        this.hello = hello(version);
    }

    private void whenTheSoftwareIsDescribed() {
        this.description = PeerSoftware.describe(this.hello);
    }

    private void thenTheDescriptionIs(String expected) {
        assertThat(this.description).isEqualTo(expected);
    }

    private void thenTheDescriptionIsNotBlank() {
        assertThat(this.description).isNotBlank();
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
