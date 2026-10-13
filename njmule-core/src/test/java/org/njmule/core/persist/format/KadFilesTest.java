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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.NodesDat.Contact;
import org.njmule.core.persist.format.NodesDat.Contents;
import org.njmule.core.persist.format.NodesDat.UdpKey;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.util.Ipv4;

class KadFilesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final UInt128 ID = UInt128.parseHex("00112233445566778899AABBCCDDEEFF");
    // The id on the wire: four little-endian words, most significant first.
    private static final String ID_WIRE = "33221100" + "77665544" + "BBAA9988" + "FFEEDDCC";

    @Test
    void idsUseLittleEndianWordsOnTheWireAndBigEndianForHashes() throws DecodeException {
        assertThat(HEX.formatHex(ID.toWireBytes())).isEqualToIgnoringCase(ID_WIRE);
        assertThat(UInt128.fromWireBytes(HEX.parseHex(ID_WIRE))).isEqualTo(ID);
        assertThat(UInt128.of(Hash16.parseHex("00112233445566778899AABBCCDDEEFF")))
                .isEqualTo(ID);
        assertThat(ID.toHash16().toHex()).isEqualTo("00112233445566778899AABBCCDDEEFF");
    }

    @Test
    void bitsCountFromTheMostSignificant() {
        UInt128 top = UInt128.parseHex("80000000000000000000000000000001");

        assertThat(top.bit(0)).isOne();
        assertThat(top.bit(1)).isZero();
        assertThat(top.bit(127)).isOne();
        assertThat(top.top32()).isEqualTo(0x8000_0000L);
        assertThat(top).isGreaterThan(UInt128.parseHex("7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"));
        assertThat(top.xor(top)).isEqualTo(UInt128.ZERO);
    }

    @Test
    void readsVersionZeroWithContactTypes() throws DecodeException {
        byte[] file = HEX.parseHex("01000000" + ID_WIRE + "04030201" + "3612" + "3712" + "02");

        Contents contents = NodesDat.read(file);

        assertThat(contents.fileVersion()).isZero();
        Contact c = contents.contacts().getFirst();
        assertThat(c.id()).isEqualTo(ID);
        assertThat(Ipv4.format(c.ip())).isEqualTo("1.2.3.4");
        assertThat(c.udpPort()).isEqualTo(4662);
        assertThat(c.tcpPort()).isEqualTo(4663);
        assertThat(c.type()).isEqualTo(2);
    }

    @Test
    void readsVersionOneWithKadVersions() throws DecodeException {
        byte[] file =
                HEX.parseHex("00000000" + "01000000" + "01000000" + ID_WIRE + "04030201" + "3612" + "3712" + "08");

        Contact c = NodesDat.read(file).contacts().getFirst();

        assertThat(c.version()).isEqualTo(8);
        assertThat(c.verified()).isFalse();
    }

    @Test
    void writesAndReadsVersionTwo() throws DecodeException {
        Contact c = new Contact(
                ID, Ipv4.of(5, 6, 7, 8), 4672, 4662, 10, 0, new UdpKey(0x12345678, Ipv4.of(9, 9, 9, 9)), true);

        byte[] file = NodesDat.write(List.of(c));

        assertThat(file).hasSize(12 + 34);
        assertThat(HEX.formatHex(file, 0, 12)).isEqualTo("00000000" + "02000000" + "01000000");
        Contents contents = NodesDat.read(file);
        assertThat(contents.fileVersion()).isEqualTo(2);
        assertThat(contents.contacts()).containsExactly(c);
    }

    @Test
    void recognisesBootstrapLists() throws DecodeException {
        String record = ID_WIRE + "04030201" + "3612" + "3712" + "08";
        byte[] file = HEX.parseHex("00000000" + "03000000" + "01000000" + "02000000" + record + record);

        Contents contents = NodesDat.read(file);

        assertThat(contents.bootstrap()).isTrue();
        assertThat(contents.contacts()).hasSize(2);
        assertThatThrownBy(
                        () -> NodesDat.read(HEX.parseHex("00000000" + "03000000" + "01000000" + "02000000" + record)))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void ignoresUnknownVersionsAndRejectsImpossibleCounts() throws DecodeException {
        assertThat(NodesDat.read(HEX.parseHex("00000000" + "09000000")).contacts())
                .isEmpty();
        assertThat(NodesDat.read(HEX.parseHex("00000000")).contacts()).isEmpty();
        assertThatThrownBy(() -> NodesDat.read(HEX.parseHex("ffffff7f" + ID_WIRE)))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void preferencesKadRoundTrip() throws DecodeException {
        PreferencesKadDat.Contents prefs = new PreferencesKadDat.Contents(Ipv4.of(10, 0, 0, 1), ID);
        byte[] file = PreferencesKadDat.write(prefs);

        assertThat(file).hasSize(23);
        assertThat(HEX.formatHex(file)).isEqualToIgnoringCase("0100000a" + "0000" + ID_WIRE + "00");
        assertThat(PreferencesKadDat.read(file)).isEqualTo(prefs);
    }

    @Property
    void garbageOnlyFailsWithDecodeException(@ForAll @Size(max = 120) byte[] data) {
        try {
            NodesDat.read(data);
            PreferencesKadDat.read(data);
        } catch (DecodeException expected) {
            // malformed file
        }
    }
}
